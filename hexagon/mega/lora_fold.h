#pragma once
// Anima LoRA folded into the fp16 crouton block weights right after megarun reads them (no copy of the model on disk, the cost is paid at load).
// MEGA_LORA=<file.safetensors> MEGA_LORA_S=<strength>: kohya/sd-scripts names "lora_unet_blocks_<b>_<module>.lora_down/lora_up/alpha" (diffusers-style
// "diffusion_model.blocks.<b>.<module>.lora_A/lora_B" also work). W' = W + strength * alpha / rank * up[out][r] @ down[r][in] for the 10 matrices of a block;
// the text-encoder part (lora_te_*) is not applied (the text encoder is a precompiled NPU graph). q8 blocks (2.9B models) are left as they are.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>
#include "blk_wlayout.h"

struct LoraFile {
  struct T { std::string dtype; std::vector<int64_t> shape; size_t off = 0, end = 0; };
  std::map<std::string, T> t; const uint8_t* data = nullptr; size_t size = 0;
  LoraFile() = default; LoraFile(const LoraFile&) = delete; LoraFile& operator=(const LoraFile&) = delete;
  ~LoraFile() { if (data) munmap((void*)data, size); }   // megarun lives on: do not keep the (touched) file pages around
  bool open(const char* path) {
    int fd = ::open(path, O_RDONLY); if (fd < 0) return false;
    struct stat st; fstat(fd, &st); size = st.st_size;
    void* m = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0); ::close(fd);
    if (m == MAP_FAILED) { size = 0; return false; }
    data = (const uint8_t*)m; if (size < 8) return false; uint64_t n; memcpy(&n, data, 8); if (8 + n > size) return false;
    std::string h((const char*)data + 8, n); const uint8_t* base = data + 8 + n;
    // minimal parser for the safetensors header: {"name":{"dtype":"BF16","shape":[a,b],"data_offsets":[x,y]},...}; values are objects, skipped
    // with brace matching that ignores braces inside strings (checkpoint metadata often holds JSON text)
    size_t p = h.find('{'); if (p == std::string::npos) return false; p++;
    auto str = [&](std::string& out) {
      p = h.find('"', p); if (p == std::string::npos) return false;
      out.clear(); size_t q = p + 1; for (; q < h.size() && h[q] != '"'; q++) { if (h[q] == '\\' && q + 1 < h.size()) q++; out += h[q]; }
      p = q + 1; return q < h.size();
    };
    auto object_end = [&](size_t ob) {   // index just past the object that starts at ob
      int depth = 0; bool in = false;
      for (size_t i = ob; i < h.size(); i++) {
        char ch = h[i];
        if (in) { if (ch == '\\') i++; else if (ch == '"') in = false; continue; }
        if (ch == '"') in = true; else if (ch == '{') depth++; else if (ch == '}' && --depth == 0) return i + 1;
      }
      return h.size();
    };
    while (true) {
      std::string k; if (!str(k)) break;
      size_t ob = h.find_first_of("{\"", h.find(':', p)); if (ob == std::string::npos) break;
      if (h[ob] == '"') { std::string skip; p = ob; if (!str(skip)) break; continue; }   // a string value (not expected at top level)
      size_t ce = object_end(ob); std::string body = h.substr(ob, ce - ob); p = ce;
      if (k == "__metadata__") continue;
      T x; size_t d = body.find("\"dtype\""); if (d != std::string::npos) { size_t a = body.find('"', d + 7); size_t b = body.find('"', a + 1); x.dtype = body.substr(a + 1, b - a - 1); }
      size_t s = body.find("\"shape\""); if (s != std::string::npos) { size_t a = body.find('[', s), b = body.find(']', a); std::string v = body.substr(a + 1, b - a - 1); for (char* c = &v[0]; *c;) { x.shape.push_back(strtoll(c, &c, 10)); while (*c == ',' || *c == ' ') c++; } }
      size_t o = body.find("\"data_offsets\""); if (o != std::string::npos) { size_t a = body.find('[', o); char* c = &body[a + 1]; x.off = strtoull(c, &c, 10); while (*c == ',' || *c == ' ') c++; x.end = strtoull(c, &c, 10); }
      x.off += base - data; x.end += base - data; t[k] = x;
    }
    return !t.empty();
  }
  // tensor as fp32 (F32 / F16 / BF16)
  bool get(const std::string& k, std::vector<float>& out, std::vector<int64_t>* shape = nullptr) const {
    auto it = t.find(k); if (it == t.end() || it->second.end > size) return false;
    const T& x = it->second; size_t n = 1; for (auto d : x.shape) n *= d; out.resize(n);
    const uint8_t* s = data + x.off;
    if (x.dtype == "F32") memcpy(out.data(), s, n * 4);
    else if (x.dtype == "F16") for (size_t i = 0; i < n; i++) out[i] = (float)((const __fp16*)s)[i];
    else if (x.dtype == "BF16") for (size_t i = 0; i < n; i++) { uint32_t u = (uint32_t)((const uint16_t*)s)[i] << 16; memcpy(&out[i], &u, 4); }
    else return false;
    if (shape) *shape = x.shape; return true;
  }
  bool has(const std::string& k) const { return t.count(k) != 0; }
};

// adds the LoRA of block b to its fp16 crouton weights; returns the number of matrices changed (-1: LoRA shapes do not fit this model)
static int lora_fold_block(const LoraFile& L, float strength, int b, uint8_t* w) {
  struct M { const char* name; uint32_t off; int K, N; };   // K = in features, N = out features (the file holds W^T [K][N] as tiles)
  static const M mats[10] = {{"self_attn_q_proj", W_SQ, 2048, 2048}, {"self_attn_k_proj", W_SK, 2048, 2048}, {"self_attn_v_proj", W_SV, 2048, 2048}, {"self_attn_output_proj", W_SO, 2048, 2048},
                             {"cross_attn_q_proj", W_CQ, 2048, 2048}, {"cross_attn_k_proj", W_CK, 1024, 2048}, {"cross_attn_v_proj", W_CV, 1024, 2048}, {"cross_attn_output_proj", W_CO, 2048, 2048},
                             {"mlp_layer1", W_M1, 2048, 8192}, {"mlp_layer2", W_M2, 8192, 2048}};
  int done = 0;
  for (const M& m : mats) {
    std::string mod = m.name, dot = mod; size_t u = dot.find('_', dot.find('_') + 1);   // self_attn_q_proj -> self_attn.q_proj, mlp_layer1 -> mlp.layer1
    if (mod.compare(0, 4, "mlp_") == 0) dot = "mlp." + mod.substr(4); else dot = mod.substr(0, u) + "." + mod.substr(u + 1);
    std::string kk = "lora_unet_blocks_" + std::to_string(b) + "_" + mod, kd = "diffusion_model.blocks." + std::to_string(b) + "." + dot;
    std::string dn, upk, al;
    if (L.has(kk + ".lora_down.weight")) { dn = kk + ".lora_down.weight"; upk = kk + ".lora_up.weight"; al = kk + ".alpha"; }
    else if (L.has(kd + ".lora_A.weight")) { dn = kd + ".lora_A.weight"; upk = kd + ".lora_B.weight"; al = kd + ".alpha"; }
    else continue;
    std::vector<float> A, B, alpha; std::vector<int64_t> sa, sb;
    if (!L.get(dn, A, &sa) || !L.get(upk, B, &sb) || sa.size() != 2 || sb.size() != 2) continue;
    const int r = (int)sa[0];
    if (sa[1] != m.K || sb[0] != m.N || sb[1] != r) return -1;
    const float sc = strength * (L.get(al, alpha) && !alpha.empty() ? alpha[0] / r : 1.f);
    std::vector<float> At((size_t)m.K * r);   // down transposed [K][r]: the inner dot is contiguous on both sides
    for (int i = 0; i < r; i++) for (int k = 0; k < m.K; k++) At[(size_t)k * r + i] = A[(size_t)i * m.K + k];
    __fp16* W = (__fp16*)(w + m.off); const int KB = m.K / 32;
    auto job = [&](int n0, int n1) {
      for (int n = n0; n < n1; n++) {
        const float* bn = &B[(size_t)n * r]; const int nb = n >> 5, c = n & 31;
        for (int k = 0; k < m.K; k++) {
          const float* ak = &At[(size_t)k * r]; float d = 0; for (int i = 0; i < r; i++) d += bn[i] * ak[i];
          // wt16 tile index: [nb][kb][k%32 / 2][c][k & 1]
          __fp16& e = W[((((size_t)nb * KB + (k >> 5)) * 16 + ((k & 31) >> 1)) * 32 + c) * 2 + (k & 1)];
          e = (__fp16)((float)e + sc * d);
        }
      }
    };
    const int nt = 4; std::vector<std::thread> th;
    for (int i = 0; i < nt; i++) th.emplace_back(job, m.N * i / nt, m.N * (i + 1) / nt);
    for (auto& x : th) x.join();
    done++;
  }
  return done;
}
