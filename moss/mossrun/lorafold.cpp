// lorafold: user LoRA for MOSS-Image on the phone.
//   lorafold check FILE.safetensors
//       -> one line: "OK\tlayers=..\tmods=..\trank=..\talpha=..\tignored=..\twarn=.." or "ERR\tCODE\tdetail"
//   lorafold fold MODELDIR FILE.safetensors STRENGTH OUTDIR
//       folds turbo (rank 128, read back from MODELDIR/dit/blkNN.bin) + user*strength into the rank-128 LoRA slots (QR-free Gram + Jacobi SVD, rank 128+r -> 128)
//       and writes OUTDIR/blkNN.ovl = [u32 n] n x ([u64 offset][u32 len] bytes), patches mossrun lays over the pack after reading it. Prints "progress K 42".
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <cstring>
#include <cmath>
#include <string>
#include <vector>
#include <map>
#include <set>
#include <thread>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
typedef unsigned char u8; typedef uint16_t u16;

// ---------------------------------------------------------------- tiny JSON (enough for the safetensors header)
struct J { enum T { NUL, NUM, STR, ARR, OBJ } t = NUL; double n = 0; std::string s; std::vector<J> a; std::vector<std::pair<std::string, J>> o;
  const J* get(const std::string& k) const { for (auto& p : o) if (p.first == k) return &p.second; return nullptr; } };
struct JP { const char* p; const char* e; bool bad = false;
  void ws() { while (p < e && (*p == ' ' || *p == '\n' || *p == '\t' || *p == '\r')) p++; }
  std::string str() { std::string r; p++; while (p < e && *p != '"') { if (*p == '\\' && p + 1 < e) { p++; if (*p == 'u') { p += 4; r += '?'; } else r += *p == 'n' ? '\n' : *p; } else r += *p; p++; } p++; return r; }
  J val() { J v; ws(); if (p >= e) { bad = true; return v; }
    if (*p == '{') { v.t = J::OBJ; p++; ws(); while (p < e && *p != '}') { ws(); std::string k = str(); ws(); if (*p != ':') { bad = true; return v; } p++; v.o.emplace_back(k, val()); ws(); if (*p == ',') p++; ws(); if (bad) return v; } p++; }
    else if (*p == '[') { v.t = J::ARR; p++; ws(); while (p < e && *p != ']') { v.a.push_back(val()); ws(); if (*p == ',') p++; ws(); if (bad) return v; } p++; }
    else if (*p == '"') { v.t = J::STR; v.s = str(); }
    else { char* q; v.n = strtod(p, &q); if (q == p) { if (!strncmp(p, "null", 4)) { p += 4; return v; } bad = true; return v; } v.t = J::NUM; p = q; }
    return v; } };

// ---------------------------------------------------------------- safetensors
struct TI { int dt = 0; std::vector<long> shape; size_t o0 = 0, o1 = 0; };   // dt: 0 F32, 1 F16, 2 BF16
struct ST {
  u8* map = nullptr; size_t size = 0, base = 0; std::map<std::string, TI> t; std::map<std::string, std::string> meta; std::string err;
  bool open(const char* path) {
    int fd = ::open(path, O_RDONLY); if (fd < 0) { err = "cannot open file"; return false; }
    struct stat sb; fstat(fd, &sb); size = sb.st_size; if (size < 16) { err = "file too small"; ::close(fd); return false; }
    map = (u8*)mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0); ::close(fd); if (map == MAP_FAILED) { map = nullptr; err = "mmap failed"; return false; }
    uint64_t hl; memcpy(&hl, map, 8); if (hl > size - 8 || hl > (64u << 20)) { err = "not a safetensors file"; return false; }
    JP jp{(const char*)map + 8, (const char*)map + 8 + hl}; J h = jp.val(); if (jp.bad || h.t != J::OBJ) { err = "bad header"; return false; }
    base = 8 + hl;
    for (auto& kv : h.o) {
      if (kv.first == "__metadata__") { for (auto& m : kv.second.o) meta[m.first] = m.second.t == J::STR ? m.second.s : std::to_string(m.second.n); continue; }
      const J* dt = kv.second.get("dtype"); const J* sh = kv.second.get("shape"); const J* of = kv.second.get("data_offsets"); if (!dt || !sh || !of || of->a.size() != 2) continue;
      TI ti; const std::string& d = dt->s; ti.dt = d == "F32" ? 0 : d == "F16" ? 1 : d == "BF16" ? 2 : 3;
      for (auto& x : sh->a) ti.shape.push_back((long)x.n); ti.o0 = (size_t)of->a[0].n; ti.o1 = (size_t)of->a[1].n;
      if (base + ti.o1 > size) { err = "truncated file"; return false; }
      t[kv.first] = ti;
    }
    return true;
  }
  float at(const TI& ti, size_t i) const {
    const u8* p = map + base + ti.o0;
    if (ti.dt == 0) { float f; memcpy(&f, p + i * 4, 4); return f; }
    u16 h; memcpy(&h, p + i * 2, 2);
    if (ti.dt == 2) { uint32_t u = (uint32_t)h << 16; float f; memcpy(&f, &u, 4); return f; }
    _Float16 hf; memcpy(&hf, &h, 2); return (float)hf;
  }
};

// ---------------------------------------------------------------- which weights a MOSS block has
struct Mod { const char* name; long in, out; };
static const Mod MODS[] = {{"qkv", 3072, 9216}, {"q", 3072, 3072}, {"k", 3072, 3072}, {"v", 3072, 3072}, {"o", 3072, 3072}, {"ffn_up_gate", 3072, 16384}, {"ffn_down", 8192, 3072}};
static const int NMOD = 7, NLAYER = 42;
struct Pair { std::string A, B, alpha, w1, w2; };
struct Scan { std::map<std::pair<int, int>, Pair> m; int ignored = 0, te = 0, foreign = 0, unknown = 0; std::string firstUnknown; };

static bool strip(std::string& n, const char* pre) { size_t l = strlen(pre); if (n.compare(0, l, pre) == 0) { n.erase(0, l); return true; } return false; }
// Civitai's MOSS-Image exports use the flattened kohya-style name
// lora_unet_layers_0_proj_q.lora_down.weight. Convert only that exact
// namespace to the canonical MOSS names used by the packer.
static std::string normalise_moss_key(const std::string& key) {
  static const char* prefix = "lora_unet_layers_";
  if (key.compare(0, strlen(prefix), prefix) != 0) return key;
  const size_t layer0 = strlen(prefix), proj = key.find("_proj_", layer0);
  if (proj == std::string::npos || proj == layer0) return key;
  for (size_t i = layer0; i < proj; i++) if (key[i] < '0' || key[i] > '9') return key;
  const std::string rest = key.substr(proj + 6);
  if (rest.find('.') == std::string::npos) return key;
  return "layers." + key.substr(layer0, proj - layer0) + ".proj." + rest;
}
static Scan scan(const ST& st) {
  Scan sc;
  for (auto& kv : st.t) {
    std::string n = normalise_moss_key(kv.first);
    if (!strip(n, "diffusion_model.")) strip(n, "model.diffusion_model.");
    int layer = -1, used = 0;
    if (sscanf(n.c_str(), "layers.%d.proj.%n", &layer, &used) == 1 && used > 0) {
      std::string rest = n.substr(used); size_t d = rest.find('.'); int mi = -1;
      std::string mod = rest.substr(0, d), tail = d == std::string::npos ? "" : rest.substr(d + 1);
      for (int i = 0; i < NMOD; i++) if (mod == MODS[i].name) mi = i;
      if (mi < 0) { sc.unknown++; if (sc.firstUnknown.empty()) sc.firstUnknown = kv.first; continue; }
      Pair& p = sc.m[{layer, mi}];
      if (tail == "lora_A.weight" || tail == "lora_down.weight") p.A = kv.first; else if (tail == "lora_B.weight" || tail == "lora_up.weight") p.B = kv.first; else if (tail == "alpha") p.alpha = kv.first;
      else if (tail == "lokr_w1") p.w1 = kv.first; else if (tail == "lokr_w2") p.w2 = kv.first;
      else { sc.unknown++; if (sc.firstUnknown.empty()) sc.firstUnknown = kv.first; }
      continue;
    }
    if (n.find("text_embedder") != std::string::npos || n.find("text_encoder") != std::string::npos || n.find("lora_te") != std::string::npos || n.find("language_model") != std::string::npos) { sc.te++; continue; }
    static const char* foreign[] = {"double_blocks", "single_blocks", "transformer_blocks", "lora_unet", "down_blocks", "up_blocks", "mid_block", "input_blocks", "output_blocks", "joint_blocks", "single_transformer_blocks", "lora_transformer", "blocks."};
    bool f = false; for (const char* s : foreign) if (n.find(s) != std::string::npos) f = true;
    if (f) sc.foreign++; else { sc.unknown++; if (sc.firstUnknown.empty()) sc.firstUnknown = kv.first; }
  }
  return sc;
}

static int fail(const char* code, const std::string& d) { printf("ERR\t%s\t%s\n", code, d.c_str()); return 1; }
static std::string shp(const std::vector<long>& s) { std::string r = "["; for (size_t i = 0; i < s.size(); i++) r += (i ? "," : "") + std::to_string(s[i]); return r + "]"; }

struct Info { int layers = 0, mods = 0, rank = 0; float alpha = 0; bool lokr = false; };
static int check_lokr(const ST& st, Info& info, std::string& warn) {
  Scan sc = scan(st); std::set<int> layers; int mods = 0;
  for (const auto& kv : sc.m) {
    const int L = kv.first.first, mi = kv.first.second; const Pair& p = kv.second;
    if (p.A.size() || p.B.size()) return fail("MIXED", "LoKr and A/B tensors cannot share one selected adapter");
    if (p.w1.empty() && p.w2.empty()) continue;
    if (mi != 0 && mi != 4 && mi != 5 && mi != 6) return fail("PROJECTION", "V73 LoKr supports qkv, o, ffn_up_gate and ffn_down projections");
    if (L < 0 || L >= NLAYER) return fail("LAYER", "block " + std::to_string(L) + ", MOSS-Image has 42");
    if (p.w1.empty() || p.w2.empty()) return fail("NOTLORA", "block " + std::to_string(L) + " " + MODS[mi].name + ": lokr_w1/lokr_w2 pair is incomplete");
    const TI& a = st.t.at(p.w1); const TI& b = st.t.at(p.w2);
    if (a.dt > 2 || b.dt > 2) return fail("DTYPE", "LoKr factors must be F32, F16 or BF16");
    if (a.shape != std::vector<long>{4, 4} || b.shape != std::vector<long>{MODS[mi].out / 4, MODS[mi].in / 4})
      return fail("SHAPE", "block " + std::to_string(L) + " " + MODS[mi].name + ": W1" + shp(a.shape) + " W2" + shp(b.shape) + ", expected W1[4,4] W2[" + std::to_string(MODS[mi].out / 4) + "," + std::to_string(MODS[mi].in / 4) + "]");
    layers.insert(L); mods++;
  }
  if (!mods) return fail("NOTLORA", sc.firstUnknown.empty() ? "no supported MOSS LoKr projections" : sc.firstUnknown);
  std::string missing; for (int L = 0; L < NLAYER; L++) if (!layers.count(L)) { if (!missing.empty()) missing += ","; missing += std::to_string(L); }
  info.layers = (int)layers.size(); info.mods = mods; info.rank = 4; info.alpha = 1.f; info.lokr = true;
  if (!missing.empty()) warn += "missing_blocks=" + missing + ";";
  if (sc.unknown) warn += "unknown_keys=" + std::to_string(sc.unknown) + ";";
  if (sc.foreign || sc.te) return fail("OTHERMODEL", "extra foreign/text-encoder tensors are not supported by the V73 LoKr runner");
  return 0;
}
static int check(const ST& st, Info& info, std::string& warn) {
  bool hasLokr = false;
  for (const auto& kv : st.t) {
    if (kv.first.find(".lokr_") != std::string::npos) hasLokr = true;
    if (kv.first.find(".hada_") != std::string::npos) return fail("LOHA", "LoHa requires a Hadamard-weight converter");
  }
  if (hasLokr) return check_lokr(st, info, warn);
  // The experimental rank-limited converter emits ordinary A/B tensors, so the tensor checks below
  // would otherwise accept its highly lossy output. Refuse those explicitly marked approximations.
  auto conversion = st.meta.find("hexanima.conversion");
  if (conversion != st.meta.end() && conversion->second.find("LoKr") != std::string::npos && conversion->second.find("truncated") != std::string::npos)
    return fail("LOKR", "lossy rank-truncated LoKr conversion is not supported; use the original only after direct LoKr support is available");
  Scan sc = scan(st);
  std::string arch = st.meta.count("modelspec.architecture") ? st.meta.at("modelspec.architecture") : "", base = st.meta.count("ss_base_model_version") ? st.meta.at("ss_base_model_version") : "";
  if (sc.m.empty()) {
    std::string d = !arch.empty() ? arch : !base.empty() ? base : sc.firstUnknown;
    if (sc.foreign) return fail("OTHERMODEL", d); if (sc.te) return fail("TEXTONLY", d);
    return fail("NOTLORA", d);
  }
  std::set<int> layers; int maxr = 0; double asum = 0; int an = 0;
  for (auto& kv : sc.m) {
    int L = kv.first.first; const Mod& md = MODS[kv.first.second]; const Pair& p = kv.second;
    if (L >= NLAYER) return fail("LAYER", "block " + std::to_string(L) + ", MOSS-Image has 42");
    if (p.A.empty() || p.B.empty()) return fail("NOTLORA", "layer " + std::to_string(L) + " " + md.name + ": one of the pair is missing");
    const TI &a = st.t.at(p.A), &b = st.t.at(p.B);
    if (a.dt > 2 || b.dt > 2) return fail("DTYPE", "only F32/F16/BF16 weights are supported");
    if (a.shape.size() != 2 || b.shape.size() != 2) return fail("SHAPE", "layer " + std::to_string(L) + " " + md.name + ": " + shp(a.shape) + " / " + shp(b.shape));
    long r = a.shape[0];
    if (a.shape[1] != md.in || b.shape[0] != md.out || b.shape[1] != r) return fail("SHAPE", "layer " + std::to_string(L) + " " + md.name + ": A" + shp(a.shape) + " B" + shp(b.shape) + ", expected A[r," + std::to_string(md.in) + "] B[" + std::to_string(md.out) + ",r]");
    if (r < 1 || r > 128) return fail("RANK", "rank " + std::to_string(r) + " (supported 1..128)");
    maxr = std::max<int>(maxr, (int)r); layers.insert(L);
    if (!p.alpha.empty()) { asum += st.at(st.t.at(p.alpha), 0); an++; }
  }
  info.layers = (int)layers.size(); info.mods = (int)sc.m.size(); info.rank = maxr;
  float al = an ? (float)(asum / an) : st.meta.count("ss_network_alpha") ? (float)atof(st.meta.at("ss_network_alpha").c_str()) : (float)maxr; info.alpha = al;
  if (maxr > 64) warn += "rank>64;";
  if (sc.te) warn += "text_encoder_ignored;";
  if (sc.unknown) warn += "unknown_keys=" + std::to_string(sc.unknown) + ";";
  if (sc.foreign) warn += "foreign_keys=" + std::to_string(sc.foreign) + ";";
  info.layers = (int)layers.size(); (void)arch; (void)base;
  return 0;
}

// ---------------------------------------------------------------- tile formats (same as export_all_q8.py: wt16 + q8tiles)
static const size_t TILE = 1152;
static void quant(const float* p, size_t rs, size_t cs, int K, int N, const int* colmap, u8* out) {   // value(r,c) = p[r*rs + c*cs]; tiles [nb][kb]
  const int KB = K / 32;
  for (int nb = 0; nb < N / 32; nb++) {
    const int lc = colmap ? colmap[nb] : nb * 32;
    for (int kb = 0; kb < KB; kb++) {
      u8* t = out + ((size_t)nb * KB + kb) * TILE; float a[32][32], sf[32];
      for (int r = 0; r < 32; r++) for (int c = 0; c < 32; c++) a[r][c] = (float)(_Float16)p[(size_t)(kb * 32 + r) * rs + (size_t)(lc + c) * cs];
      for (int c = 0; c < 32; c++) {
        float m = 0; for (int r = 0; r < 32; r++) m = std::max(m, fabsf(a[r][c]));
        float s = m / 127.f; if (s < 6.2e-5f) s = 6.2e-5f; _Float16 h = (_Float16)s; sf[c] = (float)h; u16 hb; memcpy(&hb, &h, 2);
        memcpy(t + 4 * c, &hb, 2); memcpy(t + 4 * c + 2, &hb, 2);
      }
      for (int r = 0; r < 32; r++) for (int c = 0; c < 32; c++) {
        float q = nearbyintf(a[r][c] / sf[c]); q = std::min(127.f, std::max(-127.f, q));
        t[128 + (((r >> 1) * 32 + c) << 1) + (r & 1)] = (u8)((int)q + 128);
      }
    }
  }
}
static inline float deq(const u8* t, int r, int c) { u16 hb; memcpy(&hb, t + 4 * c, 2); _Float16 h; memcpy(&h, &hb, 2); return ((int)t[128 + (((r >> 1) * 32 + c) << 1) + (r & 1)] - 128) * (float)h; }

struct LokrHead { char magic[4]; uint32_t version, mask, w1[4], w2[4], out_n[4], in_n[4]; };
static void align128(std::vector<u8>& v) { v.resize((v.size() + 127) & ~(size_t)127, 0); }
static bool fold_lokr_block(const ST& st, const Scan& sc, int L, float strength, const std::string& outdir) {
  static const int map[4] = {0, 4, 5, 6};
  LokrHead h{}; memcpy(h.magic, "MLKR", 4); h.version = 1;
  std::vector<u8> side(sizeof(LokrHead), 0);
  for (int slot = 0; slot < 4; slot++) {
    const int mi = map[slot]; auto it = sc.m.find({L, mi}); if (it == sc.m.end() || it->second.w1.empty()) continue;
    const Pair& p = it->second; const TI& w1 = st.t.at(p.w1); const TI& w2 = st.t.at(p.w2);
    const int on = (int)w2.shape[0], ik = (int)w2.shape[1]; h.mask |= 1u << slot; h.out_n[slot] = on; h.in_n[slot] = ik;
    h.w1[slot] = (uint32_t)side.size();
    float alpha = st.meta.count("ss_network_alpha") ? (float)atof(st.meta.at("ss_network_alpha").c_str()) : 4.f;
    float scale = alpha / 4.f * strength;
    for (int i = 0; i < 16; i++) { _Float16 x = (_Float16)(st.at(w1, (size_t)i) * scale); u16 b; memcpy(&b, &x, 2); size_t o = side.size(); side.resize(o + 2); memcpy(side.data() + o, &b, 2); }
    align128(side); h.w2[slot] = (uint32_t)side.size();
    const size_t n = (size_t)on * ik; std::vector<float> trans(n);
    for (int i = 0; i < ik; i++) for (int o = 0; o < on; o++) trans[(size_t)i * on + o] = st.at(w2, (size_t)o * ik + i);
    std::vector<u8> q8((size_t)(on / 32) * (ik / 32) * TILE);
    quant(trans.data(), on, 1, ik, on, nullptr, q8.data());
    side.insert(side.end(), q8.begin(), q8.end());
  }
  memcpy(side.data(), &h, sizeof h);
  std::string path = outdir + "/" + (L < 10 ? "blk0" : "blk") + std::to_string(L) + ".ovl";
  FILE* f = fopen(path.c_str(), "wb"); if (!f) return false;
  if (h.mask) { uint32_t count = 1, len = (uint32_t)side.size(); uint64_t off = 135397376u;
    bool ok = fwrite(&count, 4, 1, f) == 1 && fwrite(&off, 8, 1, f) == 1 && fwrite(&len, 4, 1, f) == 1 && fwrite(side.data(), 1, side.size(), f) == side.size(); fclose(f); return ok;
  }
  uint32_t count = 0; bool ok = fwrite(&count, 4, 1, f) == 1; fclose(f); return ok;
}

// ---------------------------------------------------------------- dense helpers (double)
static float dotf(const float* a, const float* b, size_t n) { double tot = 0; for (size_t o = 0; o < n; o += 2048) { size_t m = std::min<size_t>(2048, n - o); float s = 0; for (size_t i = 0; i < m; i++) s += a[o + i] * b[o + i]; tot += s; } return (float)tot; }
static void gram(const float* X, int R, size_t n, std::vector<double>& G) {   // rows of X [R][n] -> X X^T
  G.assign((size_t)R * R, 0); for (int i = 0; i < R; i++) for (int j = i; j < R; j++) { double d = dotf(X + (size_t)i * n, X + (size_t)j * n, n); G[(size_t)i * R + j] = G[(size_t)j * R + i] = d; } }
static void eigsym(std::vector<double>& A, int n, std::vector<double>& V, std::vector<double>& lam) {   // cyclic Jacobi; V columns are eigenvectors (V[t*n+i] = component t of vector i)
  V.assign((size_t)n * n, 0); for (int i = 0; i < n; i++) V[(size_t)i * n + i] = 1;
  for (int sweep = 0; sweep < 30; sweep++) {
    double off = 0, dg = 0; for (int i = 0; i < n; i++) { dg += A[(size_t)i * n + i] * A[(size_t)i * n + i]; for (int j = i + 1; j < n; j++) off += A[(size_t)i * n + j] * A[(size_t)i * n + j]; }
    if (off <= 1e-26 * (dg + 1e-300)) break;
    for (int p = 0; p < n - 1; p++) for (int q = p + 1; q < n; q++) {
      double apq = A[(size_t)p * n + q]; if (fabs(apq) < 1e-300) continue;
      double th = (A[(size_t)q * n + q] - A[(size_t)p * n + p]) / (2 * apq), t = (th >= 0 ? 1.0 : -1.0) / (fabs(th) + sqrt(th * th + 1)), c = 1 / sqrt(t * t + 1), s = t * c;
      for (int k = 0; k < n; k++) { double a = A[(size_t)k * n + p], b = A[(size_t)k * n + q]; A[(size_t)k * n + p] = c * a - s * b; A[(size_t)k * n + q] = s * a + c * b; }
      for (int k = 0; k < n; k++) { double a = A[(size_t)p * n + k], b = A[(size_t)q * n + k]; A[(size_t)p * n + k] = c * a - s * b; A[(size_t)q * n + k] = s * a + c * b; }
      for (int k = 0; k < n; k++) { double a = V[(size_t)k * n + p], b = V[(size_t)k * n + q]; V[(size_t)k * n + p] = c * a - s * b; V[(size_t)k * n + q] = s * a + c * b; }
    }
  }
  lam.resize(n); for (int i = 0; i < n; i++) lam[i] = A[(size_t)i * n + i];
}
static void svdj(std::vector<double>& M, int n, std::vector<double>& W, std::vector<double>& S) {   // one-sided Jacobi: M (columns rotated in place) = U S, W accumulates the rotations
  W.assign((size_t)n * n, 0); for (int i = 0; i < n; i++) W[(size_t)i * n + i] = 1;
  std::vector<double> Mt((size_t)n * n); for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) Mt[(size_t)j * n + i] = M[(size_t)i * n + j];   // work on columns as rows
  std::vector<double> Wt((size_t)n * n); for (int i = 0; i < n; i++) Wt[(size_t)i * n + i] = 1;
  for (int sweep = 0; sweep < 40; sweep++) {
    bool rot = false;
    for (int p = 0; p < n - 1; p++) for (int q = p + 1; q < n; q++) {
      double* a = &Mt[(size_t)p * n]; double* b = &Mt[(size_t)q * n]; double al = 0, be = 0, ga = 0;
      for (int k = 0; k < n; k++) { al += a[k] * a[k]; be += b[k] * b[k]; ga += a[k] * b[k]; }
      if (fabs(ga) <= 1e-15 * sqrt(al * be) || fabs(ga) < 1e-300) continue;
      rot = true; double ze = (be - al) / (2 * ga), t = (ze >= 0 ? 1.0 : -1.0) / (fabs(ze) + sqrt(1 + ze * ze)), c = 1 / sqrt(1 + t * t), s = c * t;
      double* wa = &Wt[(size_t)p * n]; double* wb = &Wt[(size_t)q * n];
      for (int k = 0; k < n; k++) { double x = a[k], y = b[k]; a[k] = c * x - s * y; b[k] = s * x + c * y; x = wa[k]; y = wb[k]; wa[k] = c * x - s * y; wb[k] = s * x + c * y; }
    }
    if (!rot) break;
  }
  S.resize(n); for (int j = 0; j < n; j++) { double s = 0; for (int k = 0; k < n; k++) s += Mt[(size_t)j * n + k] * Mt[(size_t)j * n + k]; S[j] = sqrt(s); }
  M = Mt; W = Wt;   // M rows = U_j * S_j (column j of U S), W rows = column j of W
}

// ---------------------------------------------------------------- fold one matrix
// BT [R][N] (rows 0..127 turbo B^T incl. its scale, rows 128.. user B^T scaled), AM [R][I]; returns B'^T [128][N] and A' [128][I]
static void fold(const std::vector<float>& BT, const std::vector<float>& AM, int R, size_t N, size_t I, std::vector<float>& Bo, std::vector<float>& Ao) {
  std::vector<double> Gb, Ga, Vb, Va, lb, la; gram(BT.data(), R, N, Gb); gram(AM.data(), R, I, Ga);
  eigsym(Gb, R, Vb, lb); eigsym(Ga, R, Va, la);
  double mb = 0, ma = 0; for (int i = 0; i < R; i++) { mb = std::max(mb, lb[i]); ma = std::max(ma, la[i]); }
  std::vector<double> sb(R), sa(R), ib(R), ia(R);
  for (int i = 0; i < R; i++) { sb[i] = lb[i] > mb * 1e-12 ? sqrt(lb[i]) : 0; ib[i] = sb[i] > 0 ? 1 / sb[i] : 0; sa[i] = la[i] > ma * 1e-12 ? sqrt(la[i]) : 0; ia[i] = sa[i] > 0 ? 1 / sa[i] : 0; }
  std::vector<double> C((size_t)R * R, 0);   // C = Vb^T Va
  for (int t = 0; t < R; t++) for (int i = 0; i < R; i++) { double v = Vb[(size_t)t * R + i]; if (v == 0) continue; for (int j = 0; j < R; j++) C[(size_t)i * R + j] += v * Va[(size_t)t * R + j]; }
  std::vector<double> M((size_t)R * R); for (int i = 0; i < R; i++) for (int j = 0; j < R; j++) M[(size_t)i * R + j] = sb[i] * C[(size_t)i * R + j] * sa[j];
  std::vector<double> W, S; svdj(M, R, W, S);   // M rows = (U S) columns, W rows = W columns
  std::vector<int> ord(R); for (int i = 0; i < R; i++) ord[i] = i; std::sort(ord.begin(), ord.end(), [&](int x, int y) { return S[x] > S[y]; });
  const int K = 128; std::vector<float> Tb((size_t)R * K), Ta((size_t)K * R);
  for (int c = 0; c < K; c++) {
    int j = ord[c]; double s = S[j]; if (s < 1e-300) { for (int t = 0; t < R; t++) { Tb[(size_t)t * K + c] = 0; Ta[(size_t)c * R + t] = 0; } continue; }
    // U column j = M row j / s ; W column j = W row j
    std::vector<double> u(R), w(R); for (int i = 0; i < R; i++) { u[i] = M[(size_t)j * R + i] / s; w[i] = W[(size_t)j * R + i]; }
    for (int t = 0; t < R; t++) { double x = 0, y = 0; for (int i = 0; i < R; i++) { x += Vb[(size_t)t * R + i] * ib[i] * u[i]; y += Va[(size_t)t * R + i] * ia[i] * w[i]; } Tb[(size_t)t * K + c] = (float)(x * s); Ta[(size_t)c * R + t] = (float)y; }
  }
  Bo.assign((size_t)K * N, 0); Ao.assign((size_t)K * I, 0);
  for (int k = 0; k < K; k++) { float* o = &Bo[(size_t)k * N]; for (int c = 0; c < R; c++) { float f = Tb[(size_t)c * K + k]; if (f == 0) continue; const float* x = &BT[(size_t)c * N]; for (size_t n = 0; n < N; n++) o[n] += f * x[n]; } }
  for (int k = 0; k < K; k++) { float* o = &Ao[(size_t)k * I]; for (int c = 0; c < R; c++) { float f = Ta[(size_t)k * R + c]; if (f == 0) continue; const float* x = &AM[(size_t)c * I]; for (size_t n = 0; n < I; n++) o[n] += f * x[n]; } }
}

// ---------------------------------------------------------------- pack geometry
struct MatG { const char* name; int K, N; };   // pack order
static const MatG MG[] = {{"q", 3072, 3072}, {"k", 3072, 3072}, {"v", 3072, 3072}, {"o", 3072, 3072}, {"ffn_up_gate", 3072, 16384}, {"ffn_down", 8192, 3072}};
static size_t panel_off(int mi) { size_t o = 0; for (int i = 0; i < mi; i++) o += (size_t)(MG[i].N / 32) * (MG[i].K / 32 + 4) * TILE; return o; }
static size_t a_off(int which) { size_t o = panel_off(6); static const size_t sz[] = {1327104, 442368, 442368, 1179648}; for (int i = 0; i < which; i++) o += sz[i]; return o; }   // 0 A_qkv 1 A_o 2 A_up_gate 3 A_down

static bool pread_all(int fd, void* b, size_t n, size_t off) { u8* p = (u8*)b; while (n) { ssize_t r = pread(fd, p, n, off); if (r <= 0) return false; p += r; n -= r; off += r; } return true; }

static bool fold_block(const ST& st, const Scan& sc, const std::string& pack, int L, float ustr, const std::string& outdir) {
  std::vector<std::pair<uint64_t, std::vector<u8>>> recs;
  bool any = false; for (int mi = 0; mi < NMOD; mi++) if (sc.m.count({L, mi})) any = true;
  if (any) {
    int fd = open(pack.c_str(), O_RDONLY); if (fd < 0) { fprintf(stderr, "cannot open %s\n", pack.c_str()); return false; }
    // user factors for the 6 pack matrices: A [r][in] (pointer to the tensor), B rows [N][r] with row offset; scale
    struct U { const TI* A = nullptr; const TI* B = nullptr; size_t brow0 = 0; int r = 0; float sc = 0; bool has = false; };
    U us[6]; auto setup = [&](int mi, int dst, size_t row0) { auto it = sc.m.find({L, mi}); if (it == sc.m.end()) return; const Pair& p = it->second; U& u = us[dst]; u.A = &st.t.at(p.A); u.B = &st.t.at(p.B); u.r = (int)u.A->shape[0]; u.brow0 = row0;
      float al = p.alpha.empty() ? (st.meta.count("ss_network_alpha") ? (float)atof(st.meta.at("ss_network_alpha").c_str()) : (float)u.r) : st.at(st.t.at(p.alpha), 0); u.sc = al / u.r * ustr; u.has = true; };
    setup(0, 0, 0); setup(0, 1, 3072); setup(0, 2, 6144); setup(1, 0, 0); setup(2, 1, 0); setup(3, 2, 0); setup(4, 3, 0); setup(5, 4, 0); setup(6, 5, 0);
    std::vector<u8> aqkv[3];
    for (int mi = 0; mi < 6; mi++) {
      if (!us[mi].has) continue;
      const MatG& g = MG[mi]; const U& u = us[mi]; const int R = 128 + u.r, KB = g.K / 32, NB = g.N / 32; const size_t N = g.N, I = g.K;
      std::vector<float> BT((size_t)R * N), AM((size_t)R * I);
      // turbo B^T from the panels' 4 LoRA tiles (pack nb order; up_gate interleaved up_j, gate_j)
      std::vector<u8> pn(4 * TILE); const size_t po = panel_off(mi), pnl = (size_t)(KB + 4) * TILE;
      for (int nb = 0; nb < NB; nb++) {
        if (!pread_all(fd, pn.data(), pn.size(), po + nb * pnl + (size_t)KB * TILE)) { close(fd); return false; }
        int lb = mi == 4 ? ((nb & 1) ? NB / 2 + nb / 2 : nb / 2) : nb;
        for (int t = 0; t < 4; t++) for (int r = 0; r < 32; r++) for (int c = 0; c < 32; c++) BT[(size_t)(t * 32 + r) * N + lb * 32 + c] = deq(&pn[t * TILE], r, c);
      }
      // turbo A [128][I] from the A_* regions: A matrix is [I][cols]; qkv: cols 128*j.. for q,k,v
      { int which = mi < 3 ? 0 : mi == 3 ? 1 : mi == 4 ? 2 : 3; int cols = mi < 3 ? 384 : 128, cofs = mi < 3 ? 128 * mi : 0; size_t ncb = cols / 32;
        std::vector<u8> ar(ncb * KB * TILE); if (!pread_all(fd, ar.data(), ar.size(), a_off(which))) { close(fd); return false; }
        for (size_t cb = cofs / 32; cb < (size_t)(cofs + 128) / 32; cb++) for (int kb = 0; kb < KB; kb++) { const u8* t = &ar[(cb * KB + kb) * TILE];
          for (int r = 0; r < 32; r++) for (int c = 0; c < 32; c++) AM[(size_t)((cb * 32 + c) - cofs) * I + kb * 32 + r] = deq(t, r, c); } }
      // user part
      for (int c = 0; c < u.r; c++) {
        float* bt = &BT[(size_t)(128 + c) * N]; for (size_t n = 0; n < N; n++) bt[n] = st.at(*u.B, (u.brow0 + n) * u.r + c) * u.sc;
        float* am = &AM[(size_t)(128 + c) * I]; for (size_t i = 0; i < I; i++) am[i] = st.at(*u.A, (size_t)c * I + i);
      }
      std::vector<float> Bo, Ao; fold(BT, AM, R, N, I, Bo, Ao);
      // B' tiles: matrix [128][N]; only the 4 LoRA tiles of every panel
      std::vector<int> cmap; if (mi == 4) { cmap.resize(NB); for (int nb = 0; nb < NB; nb++) cmap[nb] = ((nb & 1) ? NB / 2 + nb / 2 : nb / 2) * 32; }
      std::vector<u8> tiles((size_t)NB * 4 * TILE); quant(Bo.data(), N, 1, 128, g.N, mi == 4 ? cmap.data() : nullptr, tiles.data());
      for (int nb = 0; nb < NB; nb++) recs.push_back({po + nb * pnl + (size_t)KB * TILE, std::vector<u8>(tiles.begin() + (size_t)nb * 4 * TILE, tiles.begin() + (size_t)(nb + 1) * 4 * TILE)});
      // A' matrix [I][128] as tiles
      std::vector<u8> at((size_t)4 * KB * TILE); quant(Ao.data(), 1, I, g.K, 128, nullptr, at.data());
      if (mi < 3) aqkv[mi] = std::move(at); else recs.push_back({a_off(mi == 3 ? 1 : mi == 4 ? 2 : 3), std::move(at)});
    }
    if (us[0].has || us[1].has || us[2].has) {   // A_qkv = q|k|v parts (4 n-blocks each); a projection the user LoRA does not cover keeps its turbo part
      const size_t part = (size_t)4 * 96 * TILE; std::vector<u8> all(3 * part);
      for (int j = 0; j < 3; j++) { if (!aqkv[j].empty()) memcpy(&all[j * part], aqkv[j].data(), part); else if (!pread_all(fd, &all[j * part], part, a_off(0) + j * part)) { close(fd); return false; } }
      recs.push_back({a_off(0), std::move(all)});
    }
    close(fd);
  }
  FILE* f = fopen((outdir + "/" + (L < 10 ? "blk0" : "blk") + std::to_string(L) + ".ovl").c_str(), "wb"); if (!f) return false;
  uint32_t n = (uint32_t)recs.size(); fwrite(&n, 4, 1, f);
  for (auto& r : recs) { uint32_t l = (uint32_t)r.second.size(); fwrite(&r.first, 8, 1, f); fwrite(&l, 4, 1, f); fwrite(r.second.data(), 1, l, f); }
  fclose(f); return true;
}

// ---------------------------------------------------------------- LoKr baked into the base weights (no kernel change)
// bake-lokr MODELDIR FILE STRENGTH OUTDIR: OUTDIR becomes a model folder whose dit/blkNN.bin hold W + strength * kron(w1, w2)
// requantised to the same q8 tiles; every other entry of MODELDIR (glue, te, ref, vae, lora8...) is a symlink, so mossrun just loads OUTDIR.
static void quant_tile(float a[32][32], u8* t) {
  for (int c = 0; c < 32; c++) {
    float m = 0; for (int r = 0; r < 32; r++) { a[r][c] = (float)(_Float16)a[r][c]; m = std::max(m, fabsf(a[r][c])); }
    float sc = m / 127.f; if (sc < 6.2e-5f) sc = 6.2e-5f; _Float16 h = (_Float16)sc; float sf = (float)h; u16 hb; memcpy(&hb, &h, 2);
    memcpy(t + 4 * c, &hb, 2); memcpy(t + 4 * c + 2, &hb, 2);
    for (int r = 0; r < 32; r++) { float q = nearbyintf(a[r][c] / sf); q = std::min(127.f, std::max(-127.f, q)); t[128 + (((r >> 1) * 32 + c) << 1) + (r & 1)] = (u8)((int)q + 128); }
  }
}
static bool bake_block(const ST& st, const Scan& sc, const std::string& src, const std::string& dst, int L, float strength, double* relOut) {
  int fd = open(src.c_str(), O_RDONLY); if (fd < 0) return false;
  struct stat sb; fstat(fd, &sb); std::vector<u8> buf(sb.st_size); bool ok = pread_all(fd, buf.data(), buf.size(), 0); close(fd); if (!ok) return false;
  double dn = 0, wn = 0;
  // pack matrices touched by each LoKr projection: qkv -> q,k,v (rows 0/3072/6144), o -> o, ffn_up_gate -> up_gate (interleaved panels), ffn_down -> down
  struct T { int lokrMi, packMi, rowOff; } targets[] = {{0, 0, 0}, {0, 1, 3072}, {0, 2, 6144}, {4, 3, 0}, {5, 4, 0}, {6, 5, 0}};
  int lastMi = -1; std::vector<float> w2; float w1[16] = {0}; int on4 = 0, in4 = 0;
  for (const T& tg : targets) {
    auto it = sc.m.find({L, tg.lokrMi}); if (it == sc.m.end() || it->second.w1.empty() || it->second.w2.empty()) continue;
    if (lastMi != tg.lokrMi) {
      const TI& a = st.t.at(it->second.w1); const TI& b = st.t.at(it->second.w2);
      for (int i = 0; i < 16; i++) w1[i] = st.at(a, i) * strength;
      on4 = (int)b.shape[0]; in4 = (int)b.shape[1]; w2.resize((size_t)on4 * in4); for (size_t i = 0; i < w2.size(); i++) w2[i] = st.at(b, i);
      lastMi = tg.lokrMi;
    }
    const MatG& g = MG[tg.packMi]; const int KB = g.K / 32, NB = g.N / 32; const size_t po = panel_off(tg.packMi), pnl = (size_t)(KB + 4) * TILE;
    for (int nb = 0; nb < NB; nb++) {
      const int lb = tg.packMi == 4 ? ((nb & 1) ? NB / 2 + nb / 2 : nb / 2) : nb;
      for (int kb = 0; kb < KB; kb++) {
        u8* t = &buf[po + nb * pnl + (size_t)kb * TILE]; float a[32][32];
        for (int r = 0; r < 32; r++) for (int c = 0; c < 32; c++) {
          const int O = tg.rowOff + lb * 32 + c, I = kb * 32 + r;
          const float w = deq(t, r, c), d = w1[(O / on4) * 4 + I / in4] * w2[(size_t)(O % on4) * in4 + (I % in4)];
          a[r][c] = w + d; dn += (double)d * d; wn += (double)w * w;
        }
        quant_tile(a, t);
      }
    }
  }
  if (relOut) *relOut = wn > 0 ? sqrt(dn / wn) : 0;
  std::string tmp = dst + ".part"; FILE* f = fopen(tmp.c_str(), "wb"); if (!f) return false;
  ok = fwrite(buf.data(), 1, buf.size(), f) == buf.size(); ok = fclose(f) == 0 && ok;
  return ok && rename(tmp.c_str(), dst.c_str()) == 0;
}
#include <dirent.h>
#include <climits>
static int bake_lokr(const ST& st, const Scan& sc, const std::string& mdir0, float strength, const std::string& out) {
  char rp[PATH_MAX]; if (!realpath(mdir0.c_str(), rp)) return fail("FILE", "model folder not found"); const std::string mdir = rp;
  mkdir(out.c_str(), 0777); const std::string od = out + "/dit"; mkdir(od.c_str(), 0777);
  struct stat t; if (stat(od.c_str(), &t) || !S_ISDIR(t.st_mode)) return fail("FOLD", "cannot create output directory");
  // links to everything the runner reads besides dit/ (skip our own outputs and logs)
  if (DIR* d = opendir(mdir.c_str())) { while (dirent* e = readdir(d)) { std::string n = e->d_name;
      if (n == "." || n == ".." || n == "dit" || n == "lokr" || n == "lora" || n == "dsp" || n == "moss.log" || n == "z.raw" || n.find(".part") != std::string::npos) continue;
      std::string l = out + "/" + n; unlink(l.c_str()); if (symlink((mdir + "/" + n).c_str(), l.c_str())) { closedir(d); return fail("FOLD", "symlink " + n); } }
    closedir(d); }
  std::atomic<int> next(0), done(0), bad(0); std::mutex mu; std::vector<double> rel(NLAYER, 0);
  std::vector<std::thread> th; int nth = 3;   // a block buffer is 135 MB: 3 at a time keeps RAM near 400 MB
  for (int i = 0; i < nth; i++) th.emplace_back([&] { for (;;) { int L = next++; if (L >= NLAYER) break;
    char src[700], dst[700]; snprintf(src, sizeof src, "%s/dit/blk%02d.bin", mdir.c_str(), L); snprintf(dst, sizeof dst, "%s/blk%02d.bin", od.c_str(), L);
    bool any = false; for (int mi : {0, 4, 5, 6}) { auto it = sc.m.find({L, mi}); if (it != sc.m.end() && !it->second.w1.empty()) any = true; }
    bool ok; if (any) ok = bake_block(st, sc, src, dst, L, strength, &rel[L]); else { unlink(dst); ok = symlink(src, dst) == 0; }
    if (!ok) bad++; int dd = ++done; std::lock_guard<std::mutex> lk(mu); printf("progress %d %d\n", dd, NLAYER); fflush(stdout); } });
  for (auto& x : th) x.join(); if (bad) return fail("FOLD", "failed on " + std::to_string(bad.load()) + " blocks");
  double mx = 0, sum = 0; int n = 0; for (double r : rel) if (r > 0) { mx = std::max(mx, r); sum += r; n++; }
  printf("OK\tblocks=%d\tdelta_mean=%.4f\tdelta_max=%.4f\n", n, n ? sum / n : 0, mx); return 0;
}

static int fold_lokr(const ST& st, const Scan& sc, float strength, const std::string& outdir) {
  if (mkdir(outdir.c_str(), 0777) && errno != EEXIST) return fail("FOLD", "cannot create output directory");
  std::atomic<int> next(0), done(0), bad(0); std::mutex mu;
  std::vector<std::thread> th; int nth = std::max(1u, std::min(6u, std::thread::hardware_concurrency()));
  for (int i = 0; i < nth; i++) th.emplace_back([&] { for (;;) { int L = next++; if (L >= NLAYER) break;
    if (!fold_lokr_block(st, sc, L, strength, outdir)) bad++; int d = ++done; std::lock_guard<std::mutex> lk(mu); printf("progress %d %d\n", d, NLAYER); fflush(stdout); } });
  for (auto& t : th) t.join(); if (bad) return fail("FOLD", "failed on " + std::to_string(bad.load()) + " blocks"); printf("OK\n"); return 0;
}

int main(int argc, char** argv) {
  if (argc >= 3 && !strcmp(argv[1], "check")) {
    ST st; if (!st.open(argv[2])) return fail("FILE", st.err);
    Info inf; std::string warn; int rc = check(st, inf, warn); if (rc) return rc;
    printf("OK\tkind=%s\tlayers=%d\tmods=%d\trank=%d\talpha=%g\twarn=%s\n", inf.lokr ? "lokr" : "lora", inf.layers, inf.mods, inf.rank, inf.alpha, warn.c_str()); return 0;
  }
  if (argc >= 6 && !strcmp(argv[1], "fold-lokr")) {
    ST st; if (!st.open(argv[3])) return fail("FILE", st.err); Info inf; std::string warn; if (check(st, inf, warn)) return 1;
    if (!inf.lokr) return fail("NOTLORA", "selected file is not LoKr");
    Scan sc = scan(st); return fold_lokr(st, sc, (float)atof(argv[4]), argv[5]);
  }
  if (argc >= 6 && !strcmp(argv[1], "bake-lokr")) {
    ST st; if (!st.open(argv[3])) return fail("FILE", st.err); Info inf; std::string warn; if (check(st, inf, warn)) return 1;
    if (!inf.lokr) return fail("NOTLORA", "selected file is not LoKr");
    Scan sc = scan(st); return bake_lokr(st, sc, argv[2], (float)atof(argv[4]), argv[5]);
  }
  if (argc >= 6 && !strcmp(argv[1], "fold")) {
    ST st; if (!st.open(argv[3])) return fail("FILE", st.err); Info inf; std::string warn; if (check(st, inf, warn)) return 1;
    if (inf.lokr) return fail("LOKR", "use fold-lokr for factored LoKr adapters");
    Scan sc = scan(st); const float strength = (float)atof(argv[4]); std::string mdir = argv[2], out = argv[5];
    std::atomic<int> next(0), done(0), bad(0); std::mutex mu; int nth = std::max(1u, std::min(6u, std::thread::hardware_concurrency()));
    std::vector<std::thread> th; for (int i = 0; i < nth; i++) th.emplace_back([&] { for (;;) { int L = next++; if (L >= NLAYER) break;
      char p[600]; snprintf(p, sizeof p, "%s/dit/blk%02d.bin", mdir.c_str(), L);
      if (!fold_block(st, sc, p, L, strength, out)) bad++; int d = ++done; std::lock_guard<std::mutex> lk(mu); printf("progress %d %d\n", d, NLAYER); fflush(stdout); } });
    for (auto& t : th) t.join(); if (bad) return fail("FOLD", "failed on " + std::to_string(bad.load()) + " blocks"); printf("OK\n"); return 0;
  }
  fprintf(stderr, "usage: lorafold check FILE | fold MODELDIR FILE STRENGTH OUTDIR\n"); return 2;
}
