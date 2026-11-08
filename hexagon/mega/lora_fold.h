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
#include <algorithm>
#include <cmath>
#include <functional>
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

// The 2.9B Anima models are the 2B ones with 12 blocks doubled (measured on Anima-2.9B-preview-v1 vs anima-base-v1.0: per-block norm weights identical):
// 2.9B block j comes from 2B block FROM2B[j]. A 28-block LoRA is applied to a 40-block model through this table, a 40-block LoRA to a 28-block model
// through the first 2.9B block of each 2B block.
static const int FROM2B[40] = {0, 1, 1, 2, 3, 3, 4, 5, 5, 6, 7, 7, 8, 9, 9, 10, 11, 11, 12, 13, 14, 14, 15, 16, 16, 17, 18, 18, 19, 20, 20, 21, 22, 22, 23, 24, 24, 25, 26, 27};
static int lora_blocks(const LoraFile& L) {   // highest block index + 1 found in the LoRA's DiT keys
  int n = 0;
  for (auto& kv : L.t) {
    const std::string& k = kv.first; size_t p = k.find("lora_unet_blocks_"); size_t q = k.find("diffusion_model.blocks.");
    const char* s = p == 0 ? k.c_str() + 17 : q != std::string::npos ? k.c_str() + q + 23 : nullptr;
    if (s) n = std::max(n, atoi(s) + 1);
  }
  return n;
}
/** LoRA block to read for model block mb (-1: none) */
static int lora_source(int mb, int model_blocks, int lora_n) {
  if (lora_n <= 28 && model_blocks == 40) return mb < 40 ? FROM2B[mb] : -1;
  if (lora_n > 28 && model_blocks == 28) { for (int j = 0; j < 40; j++) if (FROM2B[j] == mb) return j; return -1; }
  return mb;
}
// adds block b of the LoRA to one block's weights (fp16 crouton tiles, or q8 tiles when q8); returns the number of matrices changed (-1: shapes do not fit)
static int lora_fold_block(const LoraFile& L, float strength, int b, uint8_t* w, bool q8 = false) {
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
    const int KB = m.K / 32;
    auto delta = [&](int n, int k) { const float* bn = &B[(size_t)n * r]; const float* ak = &At[(size_t)k * r]; float d = 0; for (int i = 0; i < r; i++) d += bn[i] * ak[i]; return sc * d; };
    std::function<void(int, int)> job;
    if (!q8) {
      __fp16* W = (__fp16*)(w + m.off);
      job = [&, W](int nb0, int nb1) {   // W by value: it goes out of scope before the threads run
        for (int n = nb0 * 32; n < nb1 * 32; n++) { const int nb = n >> 5, c = n & 31;
          for (int k = 0; k < m.K; k++) {
            __fp16& e = W[((((size_t)nb * KB + (k >> 5)) * 16 + ((k & 31) >> 1)) * 32 + c) * 2 + (k & 1)];   // wt16 tile index: [nb][kb][k%32 / 2][c][k & 1]
            e = (__fp16)((float)e + delta(n, k));
          } }
      };
    } else {
      // q8 tiles (export_all.py q8tiles): 1152 B per [nb][kb] tile = 64 fp16 column scales (halfword l -> column l/2) + 1024 offset-binary int8 in the
      // fp16 tile's order; dequantise, add the delta, quantise again the same way (per-column max/127 as fp16, round to nearest even, clamp +-127)
      uint8_t* Wq = w + WQ(m.off);
      job = [&, Wq](int nb0, int nb1) {
        float a[1024];
        for (int nb = nb0; nb < nb1; nb++) for (int kb = 0; kb < KB; kb++) {
          uint8_t* rec = Wq + ((size_t)nb * KB + kb) * 1152; __fp16* sv = (__fp16*)rec; uint8_t* q = rec + 128;
          for (int r16 = 0; r16 < 16; r16++) for (int c = 0; c < 32; c++) for (int p = 0; p < 2; p++) {
            int i = (r16 * 32 + c) * 2 + p;
            a[i] = (float)((int)q[i] - 128) * (float)sv[2 * c] + delta(nb * 32 + c, kb * 32 + r16 * 2 + p);
          }
          for (int c = 0; c < 32; c++) {
            float mx = 0; for (int r16 = 0; r16 < 16; r16++) for (int p = 0; p < 2; p++) mx = std::max(mx, std::fabs((float)(__fp16)a[(r16 * 32 + c) * 2 + p]));
            float v = mx / 127.0f; __fp16 s = (__fp16)(v == 0 ? 1.0f : v); float sf = (float)s;
            sv[2 * c] = sv[2 * c + 1] = s;
            for (int r16 = 0; r16 < 16; r16++) for (int p = 0; p < 2; p++) {
              int i = (r16 * 32 + c) * 2 + p; float qv = std::nearbyint((float)(__fp16)a[i] / sf); qv = std::min(127.f, std::max(-127.f, qv)); q[i] = (uint8_t)((int)qv + 128);
            }
          }
        }
      };
    }
    const int nt = 4, NB = m.N / 32; std::vector<std::thread> th;
    for (int i = 0; i < nt; i++) th.emplace_back(job, NB * i / nt, NB * (i + 1) / nt);
    for (auto& x : th) x.join();
    done++;
  }
  return done;
}
