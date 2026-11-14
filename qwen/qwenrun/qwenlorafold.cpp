// qwenlorafold: a Qwen-Image 2.1 LoRA folded on the phone into the V73 pack v2 (no host weights, no PC step).
//   qwenlorafold check LORA.safetensors            -> "OK\tblocks=..\tmods=..\trank=..\twarn=.." or "ERR\tCODE\tdetail"
//   qwenlorafold fold DITDIR LORA STRENGTH OUTDIR [NAME] [TRIGGERS]
//       writes OUTDIR/blk00..31.ovl + glue.ovl + manifest.json (hexanima-qwen-overlay-v1, what qwenrun's QWEN_LORA reads); prints "progress K 32".
// Every module has a 128-rank LoRA slot and the built-in Lightning adapter uses ranks 0..63 (export_qwen2.py):
//   q / k / v / out / down: the user LoRA fills ranks 64..127 (its 32-rank groups are quantised apart, so the Lightning records stay as they are);
//   the fused gate|proj matrix gives 64 ranks to each half, so Lightning (read back from the pack) + user is re-factored to rank 64 by SVD.
// Reference implementation and tests: qwen/qwen_lora_fold.py (matches the PC converter: q/k/v/o/down exactly, gate/proj within the same truncation error).
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

// ---------------------------------------------------------------- tiny JSON + safetensors (from moss/mossrun/lorafold.cpp)
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

// ---------------------------------------------------------------- Gram + Jacobi SVD (from lorafold.cpp)
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

// ---------------------------------------------------------------- rank-r factorisation of B·A (B^T rows [R][N], A rows [R][I]) -> B'^T [k][N], A' [k][I]
static double fold(const std::vector<float>& BT, const std::vector<float>& AM, int R, size_t N, size_t I, int K, std::vector<float>& Bo, std::vector<float>& Ao) {
  std::vector<double> Gb, Ga, Vb, Va, lb, la; gram(BT.data(), R, N, Gb); gram(AM.data(), R, I, Ga);
  eigsym(Gb, R, Vb, lb); eigsym(Ga, R, Va, la);
  double mb = 0, ma = 0; for (int i = 0; i < R; i++) { mb = std::max(mb, lb[i]); ma = std::max(ma, la[i]); }
  std::vector<double> sb(R), sa(R), ib(R), ia(R);
  for (int i = 0; i < R; i++) { sb[i] = lb[i] > mb * 1e-12 ? sqrt(lb[i]) : 0; ib[i] = sb[i] > 0 ? 1 / sb[i] : 0; sa[i] = la[i] > ma * 1e-12 ? sqrt(la[i]) : 0; ia[i] = sa[i] > 0 ? 1 / sa[i] : 0; }
  std::vector<double> C((size_t)R * R, 0);
  for (int t = 0; t < R; t++) for (int i = 0; i < R; i++) { double v = Vb[(size_t)t * R + i]; if (v == 0) continue; for (int j = 0; j < R; j++) C[(size_t)i * R + j] += v * Va[(size_t)t * R + j]; }
  std::vector<double> M((size_t)R * R); for (int i = 0; i < R; i++) for (int j = 0; j < R; j++) M[(size_t)i * R + j] = sb[i] * C[(size_t)i * R + j] * sa[j];
  std::vector<double> W, S; svdj(M, R, W, S);
  std::vector<int> ord(R); for (int i = 0; i < R; i++) ord[i] = i; std::sort(ord.begin(), ord.end(), [&](int x, int y) { return S[x] > S[y]; });
  double tot = 0, kept = 0; for (int i = 0; i < R; i++) { tot += S[i] * S[i]; } for (int c = 0; c < K && c < R; c++) kept += S[ord[c]] * S[ord[c]];
  std::vector<float> Tb((size_t)R * K, 0), Ta((size_t)K * R, 0);
  for (int c = 0; c < K && c < R; c++) {
    int j = ord[c]; double s = S[j]; if (s < 1e-300) continue;
    std::vector<double> u(R), w(R); for (int i = 0; i < R; i++) { u[i] = M[(size_t)j * R + i] / s; w[i] = W[(size_t)j * R + i]; }
    double rs = sqrt(s);   // split the singular value evenly between B' and A'
    for (int t = 0; t < R; t++) { double x = 0, y = 0; for (int i = 0; i < R; i++) { x += Vb[(size_t)t * R + i] * ib[i] * u[i]; y += Va[(size_t)t * R + i] * ia[i] * w[i]; } Tb[(size_t)t * K + c] = (float)(x * rs); Ta[(size_t)c * R + t] = (float)(y * rs); }
  }
  Bo.assign((size_t)K * N, 0); Ao.assign((size_t)K * I, 0);
  for (int k = 0; k < K; k++) { float* o = &Bo[(size_t)k * N]; for (int c = 0; c < R; c++) { float f = Tb[(size_t)c * K + k]; if (f == 0) continue; const float* x = &BT[(size_t)c * N]; for (size_t n = 0; n < N; n++) o[n] += f * x[n]; } }
  for (int k = 0; k < K; k++) { float* o = &Ao[(size_t)k * I]; for (int c = 0; c < R; c++) { float f = Ta[(size_t)k * R + c]; if (f == 0) continue; const float* x = &AM[(size_t)c * I]; for (size_t n = 0; n < I; n++) o[n] += f * x[n]; } }
  return tot > 0 ? kept / tot : 1.0;
}

// ---------------------------------------------------------------- Qwen LoRA names -> (block, module)
enum { M_Q, M_K, M_V, M_O, M_GU, M_DOWN, NM };
static const char* MNAME[NM] = {"to_q", "to_k", "to_v", "to_out.0", "gate_up", "out"};
static const long MIN_[NM] = {4096, 4096, 4096, 4096, 4096, 12288}, MOUT[NM] = {4096, 4096, 4096, 4096, 24576, 4096};
struct Pair { std::string A, B, alpha; };
struct Scan { std::map<std::pair<int, int>, Pair> m; int skipped = 0; std::string firstSkipped; };
static bool strip(std::string& n, const char* pre) { size_t l = strlen(pre); if (n.compare(0, l, pre) == 0) { n.erase(0, l); return true; } return false; }
static void repl(std::string& s, const char* a, const char* b) { size_t p = s.find(a); if (p != std::string::npos) s.replace(p, strlen(a), b); }
static Scan scan(const ST& st) {
  Scan sc;
  for (auto& kv : st.t) {
    std::string n = kv.first; bool kohya = n.find("lora_unet") != std::string::npos;
    for (int r = 0; r < 2; r++) { strip(n, "diffusion_model."); strip(n, "transformer."); strip(n, "base_model.model."); strip(n, "model."); strip(n, "lora_unet_"); }
    size_t d = n.find(".default"); if (d != std::string::npos) n.erase(d, 8);
    if (kohya || n.compare(0, 19, "transformer_blocks_") == 0) {
      repl(n, "transformer_blocks_", "transformer_blocks.");
      static const char* KR[][2] = {{"_attn_to_out_0", ".attn.to_out.0"}, {"_attn_to_q", ".attn.to_q"}, {"_attn_to_k", ".attn.to_k"}, {"_attn_to_v", ".attn.to_v"},
        {"_img_mlp_net_0_proj", ".img_mlp.gate_up"}, {"_img_mlp_gate_up", ".img_mlp.gate_up"}, {"_img_mlp_net_2", ".img_mlp.out"}, {"_img_mlp_out", ".img_mlp.out"}};
      for (auto& k : KR) repl(n, k[0], k[1]);
    }
    int blk = -1, used = 0;
    if (sscanf(n.c_str(), "transformer_blocks.%d.%n", &blk, &used) != 1 || used <= 0) { sc.skipped++; if (sc.firstSkipped.empty()) sc.firstSkipped = kv.first; continue; }
    std::string rest = n.substr(used), tail, mod; int kind = -1;
    static const char* TAILS[][2] = {{".lora_A.weight", "A"}, {".lora_down.weight", "A"}, {".lora_B.weight", "B"}, {".lora_up.weight", "B"}, {".alpha", "a"}};
    for (auto& t : TAILS) { size_t l = strlen(t[0]); if (rest.size() > l && rest.compare(rest.size() - l, l, t[0]) == 0) { mod = rest.substr(0, rest.size() - l); kind = t[1][0]; break; } }
    int mi = -1;
    if (mod == "attn.to_q") mi = M_Q; else if (mod == "attn.to_k") mi = M_K; else if (mod == "attn.to_v") mi = M_V; else if (mod == "attn.to_out.0") mi = M_O;
    else if (mod == "img_mlp.gate_up" || mod == "img_mlp.net.0.proj") mi = M_GU; else if (mod == "img_mlp.out" || mod == "img_mlp.net.2") mi = M_DOWN;
    if (mi < 0 || kind < 0) { sc.skipped++; if (sc.firstSkipped.empty()) sc.firstSkipped = kv.first; continue; }   // text stream (add_*, txt_mlp), norms, glue…
    Pair& p = sc.m[{blk, mi}]; if (kind == 'A') p.A = kv.first; else if (kind == 'B') p.B = kv.first; else p.alpha = kv.first;
  }
  return sc;
}
static int fail(const char* code, const std::string& d) { printf("ERR\t%s\t%s\n", code, d.c_str()); return 1; }
static std::string shp(const std::vector<long>& s) { std::string r = "["; for (size_t i = 0; i < s.size(); i++) r += (i ? "," : "") + std::to_string(s[i]); return r + "]"; }
struct Info { int blocks = 0, mods = 0, rank = 0; };
static int check(const ST& st, const Scan& sc, Info& info, std::string& warn) {
  if (sc.m.empty()) {
    bool other = false; for (auto& kv : st.t) { const std::string& k = kv.first; if (k.find("double_blocks") != std::string::npos || k.find("single_blocks") != std::string::npos || k.find("down_blocks") != std::string::npos || k.find("input_blocks") != std::string::npos || k.find("layers.") != std::string::npos || k.find("lokr") != std::string::npos || k.find("hada") != std::string::npos) other = true; }
    return fail(other ? "OTHERMODEL" : "NOTLORA", sc.firstSkipped);
  }
  std::set<int> blocks; int maxr = 0;
  for (auto& kv : sc.m) {
    int b = kv.first.first, mi = kv.first.second; const Pair& p = kv.second;
    if (b < 0 || b >= 32) return fail("LAYER", "block " + std::to_string(b) + ", Qwen-Image 2.1 Turbo has 32");
    if (p.A.empty() || p.B.empty()) return fail("NOTLORA", "block " + std::to_string(b) + " " + MNAME[mi] + ": incomplete A/B pair");
    const TI &a = st.t.at(p.A), &bt = st.t.at(p.B);
    if (a.dt > 2 || bt.dt > 2) return fail("DTYPE", "only F32/F16/BF16 weights are supported");
    if (a.shape.size() != 2 || bt.shape.size() != 2) return fail("SHAPE", std::string(MNAME[mi]) + ": " + shp(a.shape) + " / " + shp(bt.shape));
    long r = a.shape[0];
    if (a.shape[1] != MIN_[mi] || bt.shape[0] != MOUT[mi] || bt.shape[1] != r) return fail("SHAPE", "block " + std::to_string(b) + " " + MNAME[mi] + ": A" + shp(a.shape) + " B" + shp(bt.shape));
    if (r < 1 || r > 256) return fail("RANK", "rank " + std::to_string(r) + " (supported 1..256)");
    maxr = std::max<int>(maxr, (int)r); blocks.insert(b);
  }
  info.blocks = (int)blocks.size(); info.mods = (int)sc.m.size(); info.rank = maxr;
  if (maxr > 64) warn += "rank>64;";
  if (sc.skipped) warn += "skipped=" + std::to_string(sc.skipped) + ";";
  return 0;
}

// ---------------------------------------------------------------- pack v2 records (export_qwen2.py): q8 tile 1152 B / q4a 768 B, [nb][kb] order
static void enc_q8(const float a[32][32], u8* t) {   // a[k][n] -> record (export_all_q8.wt16 + q8tiles)
  float h[32][32], sf[32];
  for (int r = 0; r < 32; r++) for (int c = 0; c < 32; c++) h[r][c] = (float)(_Float16)a[r][c];
  for (int c = 0; c < 32; c++) {
    float m = 0; for (int r = 0; r < 32; r++) m = std::max(m, fabsf(h[r][c]));
    float s = m / 127.f; if (s < 6.2e-5f) s = 6.2e-5f; _Float16 hs = (_Float16)s; sf[c] = (float)hs; u16 hb; memcpy(&hb, &hs, 2); memcpy(t + 4 * c, &hb, 2); memcpy(t + 4 * c + 2, &hb, 2);
  }
  for (int r = 0; r < 32; r++) for (int c = 0; c < 32; c++) { float q = nearbyintf(h[r][c] / sf[c]); q = std::min(127.f, std::max(-127.f, q)); t[128 + (((r >> 1) * 32 + c) << 1) + (r & 1)] = (u8)((int)q + 128); }
}
static void dec_q8(const u8* t, float a[32][32]) {
  for (int c = 0; c < 32; c++) { u16 hb; memcpy(&hb, t + 4 * c, 2); _Float16 h; memcpy(&h, &hb, 2); float s = (float)h; for (int r = 0; r < 32; r++) a[r][c] = ((int)t[128 + (((r >> 1) * 32 + c) << 1) + (r & 1)] - 128) * s; }
}
static inline int q4i(int r, int c) { return (r >> 1) * 64 + c * 2 + (r & 1); }   // halfword index [r/2][c][r&1]
static void enc_q4a(const float a[32][32], u8* t) {   // quant_b per column over its 32 rows + q4a_tiles
  u8 q[1024];
  for (int c = 0; c < 32; c++) {
    float mn = a[0][c], mx = a[0][c]; for (int r = 1; r < 32; r++) { mn = std::min(mn, a[r][c]); mx = std::max(mx, a[r][c]); }
    float S = (mx - mn) / 15.0f;
    for (int r = 0; r < 32; r++) { float v = S > 0 ? nearbyintf((a[r][c] - mn) / S) : 0; v = std::min(15.f, std::max(0.f, v)); q[q4i(r, c)] = (u8)v; }
    _Float16 hs = (_Float16)S, hm = (_Float16)mn; u16 bs, bm; memcpy(&bs, &hs, 2); memcpy(&bm, &hm, 2);
    memcpy(t + 4 * c, &bs, 2); memcpy(t + 4 * c + 2, &bs, 2); memcpy(t + 128 + 4 * c, &bm, 2); memcpy(t + 128 + 4 * c + 2, &bm, 2);
  }
  for (int m = 0; m < 4; m++) for (int b = 0; b < 128; b++) t[256 + m * 128 + b] = (u8)(q[m * 256 + b] | (q[m * 256 + 128 + b] << 4));
}
static void dec_q4a(const u8* t, float a[32][32]) {
  u8 q[1024]; for (int m = 0; m < 4; m++) for (int b = 0; b < 128; b++) { u8 x = t[256 + m * 128 + b]; q[m * 256 + b] = x & 15; q[m * 256 + 128 + b] = x >> 4; }
  for (int c = 0; c < 32; c++) { u16 bs, bm; memcpy(&bs, t + 4 * c, 2); memcpy(&bm, t + 128 + 4 * c, 2); _Float16 hs, hm; memcpy(&hs, &bs, 2); memcpy(&hm, &bm, 2); float S = (float)hs, M = (float)hm; for (int r = 0; r < 32; r++) a[r][c] = q[q4i(r, c)] * S + M; }
}

struct Pack {
  int fd = -1; int off[11]; int fmt[6]; std::vector<std::pair<uint64_t, std::vector<u8>>> recs;
  bool open_(const std::string& p) { fd = ::open(p.c_str(), O_RDONLY); if (fd < 0) return false; int32_t h[18]; if (pread(fd, h, sizeof h, 0) != (ssize_t)sizeof h || h[0] != 0x00325751) return false; for (int i = 0; i < 11; i++) off[i] = h[1 + i]; for (int i = 0; i < 6; i++) fmt[i] = h[12 + i]; return true; }
  ~Pack() { if (fd >= 0) close(fd); }
  // matrices 0..5 = q k v o gate|proj down (K+128 rows: the LoRA B groups are the last 4 kb), 6..9 = A_qkv A_o A_gp A_down
  int KB(int i) const { if (i < 6) return ((i == 5 ? 12288 : 4096) + 128) / 32; return (i == 9 ? 12288 : 4096) / 32; }
  int RS(int i) const { return i < 6 && fmt[i] == 2 ? 768 : 1152; }
  uint64_t ro(int i, int nb, int kb) const { return (uint64_t)off[i] + ((uint64_t)nb * KB(i) + kb) * RS(i); }
  bool get(int i, int nb, int kb, float a[32][32]) const { u8 b[1152]; if (pread(fd, b, RS(i), ro(i, nb, kb)) != RS(i)) return false; if (RS(i) == 768) dec_q4a(b, a); else dec_q8(b, a); return true; }
  void put(int i, int nb, int kb, const float a[32][32]) { std::vector<u8> b(RS(i)); if (RS(i) == 768) enc_q4a(a, b.data()); else enc_q8(a, b.data()); recs.push_back({ro(i, nb, kb), std::move(b)}); }
};

static int HPERM(int j) { int h = j / 128, i = j % 128; return h * 128 + (i < 64 ? 2 * i : 2 * (i - 64) + 1); }   // packed q/k column -> original output row
struct UF { std::vector<float> A, B; int r = 0; };   // A [r][I], B [N][r] (scaled)
static UF user(const ST& st, const Pair& p, float strength, size_t rowsN, size_t row0) {
  UF u; const TI &a = st.t.at(p.A), &b = st.t.at(p.B); u.r = (int)a.shape[0]; const size_t I = a.shape[1], R = u.r;
  float alpha = p.alpha.empty() ? (st.meta.count("ss_network_alpha") ? (float)atof(st.meta.at("ss_network_alpha").c_str()) : (float)R) : st.at(st.t.at(p.alpha), 0);
  float mult = strength * alpha / R;
  u.A.resize(R * I); for (size_t i = 0; i < R * I; i++) u.A[i] = st.at(a, i);
  u.B.resize(rowsN * R); for (size_t n = 0; n < rowsN; n++) for (size_t c = 0; c < R; c++) u.B[n * R + c] = st.at(b, (row0 + n) * R + c) * mult;
  return u;
}
// a user adapter above rank 64 keeps its best 64 ranks
static void cut64(UF& u, size_t N, size_t I) {
  if (u.r <= 64) return;
  std::vector<float> BT((size_t)u.r * N), Bo, Ao; for (int c = 0; c < u.r; c++) for (size_t n = 0; n < N; n++) BT[(size_t)c * N + n] = u.B[n * u.r + c];
  fold(BT, u.A, u.r, N, I, 64, Bo, Ao); u.A = Ao; u.B.assign(N * 64, 0); for (int c = 0; c < 64; c++) for (size_t n = 0; n < N; n++) u.B[n * 64 + c] = Bo[(size_t)c * N + n]; u.r = 64;
}

static bool fold_block(const ST& st, const Scan& sc, const std::string& dit, int L, float strength, const std::string& outdir, std::string& note) {
  Pack P; std::string path = dit + "/blk" + (L < 10 ? "0" : "") + std::to_string(L) + ".bin";
  bool any = false; for (int mi = 0; mi < NM; mi++) if (sc.m.count({L, mi})) any = true;
  if (any) {
    if (!P.open_(path)) { note = "cannot read " + path; return false; }
    float t[32][32];
    // q k v o down: user ranks go to slot ranks 64..127 (B groups 2,3 of every column block; A columns 64..127)
    static const int AMAT[NM] = {6, 6, 6, 7, 8, 9}, ANB0[NM] = {2, 6, 10, 2, 0, 2};
    for (int mi : {M_Q, M_K, M_V, M_O, M_DOWN}) {
      auto it = sc.m.find({L, mi}); if (it == sc.m.end()) continue;
      const int pm = mi == M_DOWN ? 5 : mi; const size_t I = MIN_[mi], N = 4096;
      UF u = user(st, it->second, strength, N, 0); cut64(u, N, I);
      const int kb0 = P.KB(pm) - 4;
      for (int nb = 0; nb < 128; nb++) for (int g = 0; g < 2; g++) {
        for (int k = 0; k < 32; k++) for (int c = 0; c < 32; c++) { int rank = g * 32 + k, col = nb * 32 + c, row = (mi == M_Q || mi == M_K) ? HPERM(col) : col; t[k][c] = rank < u.r ? u.B[(size_t)row * u.r + rank] : 0.f; }
        P.put(pm, nb, kb0 + 2 + g, t);
      }
      const int am = AMAT[mi];
      for (int j = 0; j < 2; j++) for (int kb = 0; kb < P.KB(am); kb++) {
        for (int k = 0; k < 32; k++) for (int c = 0; c < 32; c++) { int rank = j * 32 + c; t[k][c] = rank < u.r ? u.A[(size_t)rank * I + kb * 32 + k] : 0.f; }
        P.put(am, ANB0[mi] + j, kb, t);
      }
    }
    // gate|proj: Lightning (read back) + user, re-factored to rank 64 for each half
    auto it = sc.m.find({L, M_GU});
    if (it != sc.m.end()) {
      const size_t I = 4096, N = 12288; const int kb0 = P.KB(4) - 4;
      std::vector<float> AL((size_t)128 * I);   // A_gp [4096][128] -> rows = ranks
      for (int nb = 0; nb < 4; nb++) for (int kb = 0; kb < 128; kb++) { if (!P.get(8, nb, kb, t)) { note = "read error"; return false; } for (int k = 0; k < 32; k++) for (int c = 0; c < 32; c++) AL[(size_t)(nb * 32 + c) * I + kb * 32 + k] = t[k][c]; }
      for (int half = 0; half < 2; half++) {
        UF u = user(st, it->second, strength, N, half ? 12288 : 0);
        const int R = 64 + u.r; std::vector<float> BT((size_t)R * N), AM((size_t)R * I), Bo, Ao;
        for (int jb = 0; jb < 384; jb++) { int nb = 2 * jb + half; for (int g = 0; g < 2; g++) { if (!P.get(4, nb, kb0 + 2 * half + g, t)) { note = "read error"; return false; } for (int k = 0; k < 32; k++) for (int c = 0; c < 32; c++) BT[(size_t)(g * 32 + k) * N + jb * 32 + c] = t[k][c]; } }
        memcpy(AM.data(), &AL[(size_t)64 * half * I], (size_t)64 * I * 4);
        for (int c = 0; c < u.r; c++) { for (size_t n = 0; n < N; n++) BT[(size_t)(64 + c) * N + n] = u.B[n * u.r + c]; memcpy(&AM[(size_t)(64 + c) * I], &u.A[(size_t)c * I], I * 4); }
        double kept = fold(BT, AM, R, N, I, 64, Bo, Ao);
        char buf[64]; snprintf(buf, sizeof buf, "%s %.4f;", half ? "proj" : "gate", kept); note += buf;
        for (int jb = 0; jb < 384; jb++) for (int g = 0; g < 2; g++) { for (int k = 0; k < 32; k++) for (int c = 0; c < 32; c++) t[k][c] = Bo[(size_t)(g * 32 + k) * N + jb * 32 + c]; P.put(4, 2 * jb + half, kb0 + 2 * half + g, t); }
        for (int j = 0; j < 2; j++) for (int kb = 0; kb < 128; kb++) { for (int k = 0; k < 32; k++) for (int c = 0; c < 32; c++) t[k][c] = Ao[(size_t)(j * 32 + c) * I + kb * 32 + k]; P.put(8, 2 * half + j, kb, t); }
      }
    }
  }
  // merge adjacent records and write the overlay (an untouched block gets an empty one: the runner wants all 32)
  std::sort(P.recs.begin(), P.recs.end(), [](auto& a, auto& b) { return a.first < b.first; });
  std::vector<std::pair<uint64_t, std::vector<u8>>> mg;
  for (auto& r : P.recs) { if (!mg.empty() && mg.back().first + mg.back().second.size() == r.first) mg.back().second.insert(mg.back().second.end(), r.second.begin(), r.second.end()); else mg.push_back(std::move(r)); }
  std::string out = outdir + "/blk" + (L < 10 ? "0" : "") + std::to_string(L) + ".ovl", tmp = out + ".part";
  FILE* f = fopen(tmp.c_str(), "wb"); if (!f) { note = "cannot write " + out; return false; }
  uint32_t n = (uint32_t)mg.size(); fwrite(&n, 4, 1, f);
  for (auto& r : mg) { uint32_t l = (uint32_t)r.second.size(); fwrite(&r.first, 8, 1, f); fwrite(&l, 4, 1, f); fwrite(r.second.data(), 1, l, f); }
  bool ok = fflush(f) == 0; ok &= fclose(f) == 0; return ok && rename(tmp.c_str(), out.c_str()) == 0;
}

static long fsize(const std::string& p) { struct stat s; return stat(p.c_str(), &s) ? -1 : (long)s.st_size; }
static std::string jesc(const std::string& s) { std::string o; for (char c : s) { if (c == '"' || c == '\\') { o += '\\'; o += c; } else if ((unsigned char)c < 32) o += ' '; else o += c; } return o; }

int main(int argc, char** argv) {
  if (argc >= 3 && !strcmp(argv[1], "check")) {
    ST st; if (!st.open(argv[2])) return fail("FILE", st.err); Scan sc = scan(st); Info inf; std::string warn; if (check(st, sc, inf, warn)) return 1;
    printf("OK\tblocks=%d\tmods=%d\trank=%d\twarn=%s\n", inf.blocks, inf.mods, inf.rank, warn.c_str()); return 0;
  }
  if (argc >= 6 && !strcmp(argv[1], "fold")) {   // fold DITDIR LORA STRENGTH OUTDIR [NAME] [TRIGGERS]
    ST st; if (!st.open(argv[3])) return fail("FILE", st.err); Scan sc = scan(st); Info inf; std::string warn; if (check(st, sc, inf, warn)) return 1;
    const std::string dit = argv[2], outdir = argv[5]; const float strength = (float)atof(argv[4]);
    if (mkdir(outdir.c_str(), 0777) && errno != EEXIST) return fail("FOLD", "cannot create output directory");
    std::atomic<int> next(0), done(0), bad(0); std::mutex mu; std::string firstErr;
    std::vector<std::thread> th; int nth = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    for (int i = 0; i < nth; i++) th.emplace_back([&] { for (;;) { int L = next++; if (L >= 32) break; std::string note;
      bool ok = fold_block(st, sc, dit, L, strength, outdir, note); std::lock_guard<std::mutex> lk(mu); if (!ok) { bad++; if (firstErr.empty()) firstErr = note; }
      printf("progress %d 32%s%s\n", ++done, note.empty() ? "" : "\t", note.c_str()); fflush(stdout); } });
    for (auto& t : th) t.join();
    if (bad) return fail("FOLD", firstErr);
    { FILE* g = fopen((outdir + "/glue.ovl").c_str(), "wb"); uint32_t z = 0; if (!g) return fail("FOLD", "glue.ovl"); fwrite(&z, 4, 1, g); fclose(g); }   // glue tensors are not touched
    std::string name = argc > 6 ? argv[6] : "", trig = argc > 7 ? argv[7] : "";
    std::string m = "{\n  \"format\": \"hexanima-qwen-overlay-v1\",\n  \"chunk_bytes\": 4096,\n  \"first\": 0,\n  \"last\": 31,\n  \"source\": \"phone\",\n  \"blocks\": [\n";
    for (int L = 0; L < 32; L++) { char b[160]; snprintf(b, sizeof b, "    {\"block\": %d, \"bytes\": %ld}%s\n", L, fsize(outdir + "/blk" + (L < 10 ? "0" : "") + std::to_string(L) + ".ovl"), L < 31 ? "," : ""); m += b; }
    char sb[64]; snprintf(sb, sizeof sb, "%.2f", strength);
    m += "  ],\n  \"glue\": {\"bytes\": 4},\n  \"adapter\": {\"name\": \"" + jesc(name) + "\", \"strength\": " + sb + ", \"rank\": " + std::to_string(inf.rank) + ", \"trigger_words\": [";
    { size_t p = 0; bool first = true; while (p <= trig.size()) { size_t q = trig.find(',', p); if (q == std::string::npos) q = trig.size(); std::string w = trig.substr(p, q - p); while (!w.empty() && w[0] == ' ') w.erase(0, 1); while (!w.empty() && w.back() == ' ') w.pop_back(); if (!w.empty()) { m += (first ? "\"" : ", \"") + jesc(w) + "\""; first = false; } p = q + 1; } }
    m += "]}\n}\n";
    FILE* mf = fopen((outdir + "/manifest.json").c_str(), "wb"); if (!mf) return fail("FOLD", "manifest"); fputs(m.c_str(), mf); fclose(mf);
    printf("OK\tblocks=%d\tmods=%d\trank=%d\twarn=%s\n", inf.blocks, inf.mods, inf.rank, warn.c_str()); return 0;
  }
  fprintf(stderr, "usage: qwenlorafold check LORA | fold DITDIR LORA STRENGTH OUTDIR [NAME] [TRIGGERS]\n"); return 2;
}
