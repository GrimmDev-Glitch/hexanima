// sdxlrun: minimal SDXL runner for QNN HTP context binaries (Android arm64, Hexagon V73). Feasibility prototype, not used by the app.
// Contexts in DIR: te_l.bin, te_g.bin (CLIP-L / CLIP-G), part0.bin..part7.bin (UNet pieces, fp16), vae.bin (decoder),
// plus emb.bin (fp32 weights of the time / size embedding MLPs, from export_parts.py emb) and te_proj.bin (CLIP-G text_projection [1280x1280]).
//
// One process (= one DSP session) can map only ~2 GB of contexts on the SM8550, and the fp16 UNet is ~5 GB, so the contexts are spread over
// worker processes forked before QNN is touched (SDXL_GROUPS, default "0,1,2;3,4,7;5,6"). The main process never opens QNN: it keeps every tensor
// in one shared anonymous mapping (fp32), tells a worker "run graph G at these offsets", and converts NCHW <-> NHWC when a producer and a
// consumer disagree (the converter keeps NCHW for model inputs/outputs but may leave an extra output in NHWC).
// Tensors are matched by name (UNet: h -> hout, emb, ctx, skips s0..s8; CLIP: ids -> hidden, last; VAE: z -> image). Files: fp32, NCHW.
// usage:
//   sdxlrun BACKEND SYSTEM DIR te   IDS_L IDS_G CTX_OUT POOL_OUT          ids: int32[77]; ctx [77,2048], pooled [1280]
//   sdxlrun BACKEND SYSTEM DIR unet X EMB CTX EPS_OUT [REPS]               one UNet call (timing and one-step comparison)
//   sdxlrun BACKEND SYSTEM DIR vae  Z IMG_OUT                              Z = latent / scaling_factor, IMG [3,H,W] in -1..1
//   sdxlrun BACKEND SYSTEM DIR gen  IDS_L IDS_G NEG_L NEG_G XT SIGMAS TIMESTEPS CFG W H OUT_PREFIX
//        Euler (diffusers EulerDiscreteScheduler), epsilon prediction; XT = initial noise (already * init_noise_sigma), SIGMAS [steps+1] and
//        TIMESTEPS [steps] fp32 (ref.py writes them). Writes OUT_PREFIX.lat (latent) and OUT_PREFIX.ppm. CFG 1 skips the negative prompt.
#include <dlfcn.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"

typedef std::chrono::steady_clock Clk;
static double since(Clk::time_point t) { return std::chrono::duration<double>(Clk::now() - t).count(); }
static std::string DIR;
static uint8_t* SHM = nullptr;
static const size_t SHM_BYTES = 768ull << 20;

static std::vector<uint8_t> readFile(const std::string& p) {
  FILE* f = fopen(p.c_str(), "rb");
  std::vector<uint8_t> v;
  if (!f) return v;
  fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
  v.resize(n);
  if (fread(v.data(), 1, n, f) != (size_t)n) v.clear();
  fclose(f);
  return v;
}
static std::vector<float> readF(const std::string& p, size_t n = 0) {
  auto b = readFile(p);
  if (b.empty() || (n && b.size() != n * 4)) { fprintf(stderr, "bad file %s (%zu bytes, want %zu)\n", p.c_str(), b.size(), n * 4); exit(1); }
  std::vector<float> v(b.size() / 4); memcpy(v.data(), b.data(), b.size()); return v;
}
static void writeF(const std::string& p, const std::vector<float>& v) { FILE* f = fopen(p.c_str(), "wb"); fwrite(v.data(), 4, v.size(), f); fclose(f); }

// ================= worker side (owns QNN) =================
static QNN_INTERFACE_VER_TYPE qi;
static QNN_SYSTEM_INTERFACE_VER_TYPE qsi;
static Qnn_BackendHandle_t backend = nullptr;
static Qnn_DeviceHandle_t device = nullptr;

static bool check(Qnn_ErrorHandle_t e, const char* what) {
  if (e != QNN_SUCCESS) { fprintf(stderr, "ERROR %s: 0x%lx\n", what, (unsigned long)e); return false; }
  return true;
}
static std::vector<uint32_t> tDims(const Qnn_Tensor_t& t) { return std::vector<uint32_t>(t.v1.dimensions, t.v1.dimensions + t.v1.rank); }
static size_t tElems(const Qnn_Tensor_t& t) { size_t n = 1; for (auto d : tDims(t)) n *= d; return n; }
static size_t bytesPer(Qnn_DataType_t d) {
  switch (d) { case QNN_DATATYPE_FLOAT_32: case QNN_DATATYPE_INT_32: return 4; case QNN_DATATYPE_FLOAT_16: case QNN_DATATYPE_UFIXED_POINT_16: case QNN_DATATYPE_SFIXED_POINT_16: return 2; default: return 1; }
}
static inline float h2f(uint16_t h) {
  uint32_t s = (h & 0x8000u) << 16, e = (h >> 10) & 0x1f, m = h & 0x3ff, u;
  if (e == 0) { if (!m) u = s; else { e = 127 - 15 + 1; while (!(m & 0x400)) { m <<= 1; e--; } m &= 0x3ff; u = s | (e << 23) | (m << 13); } }
  else if (e == 31) u = s | 0x7f800000u | (m << 13);
  else u = s | ((e + 112) << 23) | (m << 13);
  float f; memcpy(&f, &u, 4); return f;
}
static inline uint16_t f2h(float f) {
#if defined(__aarch64__)
  __fp16 h = (__fp16)f; uint16_t r; memcpy(&r, &h, 2); return r;
#else
  uint32_t u; memcpy(&u, &f, 4);
  uint32_t s = (u >> 16) & 0x8000, e = (u >> 23) & 0xff; int32_t ee = (int32_t)e - 127 + 15; uint32_t m = u & 0x7fffff;
  if (e == 0xff) return s | 0x7c00 | (m ? 0x200 : 0);
  if (ee >= 31) return s | 0x7c00;
  if (ee <= 0) { if (ee < -10) return s; m |= 0x800000; uint32_t sh = 14 - ee; uint32_t r = m >> sh; if ((m >> (sh - 1)) & 1) r++; return s | r; }
  uint32_t r = s | (ee << 10) | (m >> 13); if (m & 0x1000) r++; return r;
#endif
}
static void toNative(const Qnn_Tensor_t& t, const float* src, uint8_t* dst, size_t n) {
  auto dt = t.v1.dataType;
  if (dt == QNN_DATATYPE_FLOAT_32) { memcpy(dst, src, n * 4); return; }
  if (dt == QNN_DATATYPE_FLOAT_16) { auto* d = (uint16_t*)dst; for (size_t i = 0; i < n; i++) d[i] = f2h(src[i]); return; }
  if (dt == QNN_DATATYPE_INT_32) { auto* d = (int32_t*)dst; for (size_t i = 0; i < n; i++) d[i] = (int32_t)lrintf(src[i]); return; }
  float sc = t.v1.quantizeParams.scaleOffsetEncoding.scale; int32_t of = t.v1.quantizeParams.scaleOffsetEncoding.offset;
  if (dt == QNN_DATATYPE_UFIXED_POINT_16) { auto* d = (uint16_t*)dst; for (size_t i = 0; i < n; i++) { long q = lrintf(src[i] / sc) - of; d[i] = (uint16_t)(q < 0 ? 0 : q > 65535 ? 65535 : q); } return; }
  if (dt == QNN_DATATYPE_UFIXED_POINT_8) { for (size_t i = 0; i < n; i++) { long q = lrintf(src[i] / sc) - of; dst[i] = (uint8_t)(q < 0 ? 0 : q > 255 ? 255 : q); } return; }
  fprintf(stderr, "unsupported input type 0x%x for %s\n", dt, t.v1.name); exit(1);
}
static void fromNative(const Qnn_Tensor_t& t, const uint8_t* src, float* dst, size_t n) {
  auto dt = t.v1.dataType;
  if (dt == QNN_DATATYPE_FLOAT_32) { memcpy(dst, src, n * 4); return; }
  if (dt == QNN_DATATYPE_FLOAT_16) { auto* s = (const uint16_t*)src; for (size_t i = 0; i < n; i++) dst[i] = h2f(s[i]); return; }
  float sc = t.v1.quantizeParams.scaleOffsetEncoding.scale; int32_t of = t.v1.quantizeParams.scaleOffsetEncoding.offset;
  if (dt == QNN_DATATYPE_UFIXED_POINT_16) { auto* s = (const uint16_t*)src; for (size_t i = 0; i < n; i++) dst[i] = (s[i] + of) * sc; return; }
  if (dt == QNN_DATATYPE_UFIXED_POINT_8) { for (size_t i = 0; i < n; i++) dst[i] = (src[i] + of) * sc; return; }
  fprintf(stderr, "unsupported output type 0x%x for %s\n", dt, t.v1.name); exit(1);
}

struct Graph {
  std::string file;
  Qnn_ContextHandle_t ctx = nullptr;
  Qnn_GraphHandle_t graph = nullptr;
  std::vector<Qnn_Tensor_t> in, out;
  std::vector<std::vector<uint8_t>> inBuf, outBuf;
};

static void setPower() {   // burst profile, same as chainrun
  QnnDevice_Infrastructure_t infra = nullptr;
  if (!qi.deviceGetInfrastructure || qi.deviceGetInfrastructure(&infra) != QNN_SUCCESS || !infra) return;
  auto* hi = (QnnHtpDevice_Infrastructure_t*)infra; uint32_t pid = 0;
  if (hi->perfInfra.createPowerConfigId(0, 0, &pid) != QNN_SUCCESS) return;
  QnnHtpPerfInfrastructure_PowerConfig_t dc = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT;
  dc.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
  auto& d = dc.dcvsV3Config; const bool cool = getenv("MEGA_COOL") != nullptr;
  d.contextId = pid; d.setDcvsEnable = 1; d.dcvsEnable = 0; d.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
  d.setSleepLatency = 1; d.sleepLatency = 40; d.setSleepDisable = 1; d.sleepDisable = cool ? 0 : 1;
  d.setBusParams = 1; d.busVoltageCornerMin = d.busVoltageCornerTarget = d.busVoltageCornerMax = cool ? DCVS_VOLTAGE_VCORNER_NOM_PLUS : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
  d.setCoreParams = 1; d.coreVoltageCornerMin = d.coreVoltageCornerTarget = d.coreVoltageCornerMax = cool ? DCVS_VOLTAGE_VCORNER_NOM_PLUS : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
  QnnHtpPerfInfrastructure_PowerConfig_t rc = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT;
  rc.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY; rc.rpcControlLatencyConfig = 100;
  const QnnHtpPerfInfrastructure_PowerConfig_t* cv[] = {&dc, &rc, nullptr};
  Qnn_ErrorHandle_t pe = hi->perfInfra.setPowerConfig(pid, cv);
  if (pe != QNN_SUCCESS) fprintf(stderr, "setPowerConfig: 0x%lx\n", (unsigned long)pe);
}

static bool qnnInit(const char* be, const char* sys) {
  void* hb = dlopen(be, RTLD_NOW | RTLD_LOCAL); void* hs = dlopen(sys, RTLD_NOW | RTLD_LOCAL);
  if (!hb || !hs) { fprintf(stderr, "dlopen failed: %s\n", dlerror()); return false; }
  typedef Qnn_ErrorHandle_t (*GetP)(const QnnInterface_t***, uint32_t*);
  typedef Qnn_ErrorHandle_t (*GetSP)(const QnnSystemInterface_t***, uint32_t*);
  const QnnInterface_t** provs = nullptr; uint32_t np = 0;
  if (!check(((GetP)dlsym(hb, "QnnInterface_getProviders"))(&provs, &np), "getProviders")) return false;
  qi = provs[0]->QNN_INTERFACE_VER_NAME;
  const QnnSystemInterface_t** sprovs = nullptr; uint32_t nsp = 0;
  if (!check(((GetSP)dlsym(hs, "QnnSystemInterface_getProviders"))(&sprovs, &nsp), "getSystemProviders")) return false;
  qsi = sprovs[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
  if (!check(qi.backendCreate(nullptr, nullptr, &backend), "backendCreate")) return false;
  if (!check(qi.deviceCreate(nullptr, nullptr, &device), "deviceCreate")) return false;
  setPower();
  return true;
}

static bool loadGraph(Graph& G, const std::string& file) {
  G.file = file;
  std::vector<uint8_t> bin = readFile(DIR + "/" + file);
  if (bin.empty()) { fprintf(stderr, "cannot read %s\n", file.c_str()); return false; }
  QnnSystemContext_Handle_t sh = nullptr;   // kept alive: tensor names point into it
  if (!check(qsi.systemContextCreate(&sh), "systemContextCreate")) return false;
  const QnnSystemContext_BinaryInfo_t* bi = nullptr; Qnn_ContextBinarySize_t bis = 0;
  if (!check(qsi.systemContextGetBinaryInfo(sh, bin.data(), bin.size(), &bi, &bis), "getBinaryInfo")) return false;
  const QnnSystemContext_GraphInfo_t* g = bi->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1 ? bi->contextBinaryInfoV1.graphs
                                        : bi->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2 ? bi->contextBinaryInfoV2.graphs : bi->contextBinaryInfoV3.graphs;
  const char* gname; uint32_t nin, nout; Qnn_Tensor_t *gin, *gout;
  if (g->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) { auto& v = g->graphInfoV1; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
  else if (g->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) { auto& v = g->graphInfoV2; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
  else { auto& v = g->graphInfoV3; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
  if (!check(qi.contextCreateFromBinary(backend, device, nullptr, bin.data(), bin.size(), &G.ctx, nullptr), ("contextCreateFromBinary " + file).c_str())) return false;
  bin.clear(); bin.shrink_to_fit();   // QNN copied it
  if (!check(qi.graphRetrieve(G.ctx, gname, &G.graph), "graphRetrieve")) return false;
  for (uint32_t k = 0; k < nin + nout; k++) {
    Qnn_Tensor_t t = k < nin ? gin[k] : gout[k - nin];
    auto& vec = k < nin ? G.in : G.out; auto& buf = k < nin ? G.inBuf : G.outBuf;
    t.v1.memType = QNN_TENSORMEMTYPE_RAW;
    buf.emplace_back(tElems(t) * bytesPer(t.v1.dataType));
    t.v1.clientBuf.data = buf.back().data(); t.v1.clientBuf.dataSize = buf.back().size();
    vec.push_back(t);
  }
  return true;
}

// commands (one line each): "load F1 F2 ..." -> per graph "G idx file", per tensor "T in|out name dims...", then "ok" | "fail"
//                           "run IDX OFF..." (offsets in tensor order: inputs, then outputs) -> "ok MS" | "fail";  "free" -> "ok";  "quit"
static int workerMain(const char* be, const char* sys, int rfd, int wfd) {
  FILE* in = fdopen(rfd, "r"); FILE* out = fdopen(wfd, "w");
  if (!qnnInit(be, sys)) { fprintf(out, "fail\n"); fflush(out); return 1; }
  std::vector<Graph> G;
  char line[8192];
  while (fgets(line, sizeof line, in)) {
    std::istringstream ss(line); std::string cmd; ss >> cmd;
    if (cmd == "quit") break;
    if (cmd == "load") {
      std::string f; bool ok = true;
      while (ss >> f) {
        G.emplace_back();
        if (!loadGraph(G.back(), f)) { ok = false; break; }
        fprintf(out, "G %zu %s\n", G.size() - 1, f.c_str());
        for (int io = 0; io < 2; io++) for (auto& t : io ? G.back().out : G.back().in) {
          fprintf(out, "T %s %s 0x%x", io ? "out" : "in", t.v1.name, t.v1.dataType);
          for (auto d : tDims(t)) fprintf(out, " %u", d);
          fprintf(out, "\n");
        }
      }
      fprintf(out, ok ? "ok\n" : "fail\n"); fflush(out);
    } else if (cmd == "run") {
      size_t idx; ss >> idx; Graph& g = G[idx]; size_t off;
      for (size_t k = 0; k < g.in.size(); k++) { ss >> off; toNative(g.in[k], (float*)(SHM + off), g.inBuf[k].data(), tElems(g.in[k])); }
      auto t = Clk::now();
      bool ok = check(qi.graphExecute(g.graph, g.in.data(), g.in.size(), g.out.data(), g.out.size(), nullptr, nullptr), ("graphExecute " + g.file).c_str());
      double ms = since(t) * 1000;
      for (size_t k = 0; k < g.out.size() && ok; k++) { ss >> off; fromNative(g.out[k], g.outBuf[k].data(), (float*)(SHM + off), tElems(g.out[k])); }
      fprintf(out, ok ? "ok %.1f\n" : "fail\n", ms); fflush(out);
    } else if (cmd == "free") {
      for (auto& g : G) if (g.ctx) qi.contextFree(g.ctx, nullptr);
      G.clear(); fprintf(out, "ok\n"); fflush(out);
    }
  }
  return 0;
}

// ================= main side =================
struct Tref { std::string name; bool isIn; std::vector<uint32_t> dims; size_t elems; };
struct Worker { pid_t pid; FILE* to; FILE* from; };
struct GraphRef { int w; size_t idx; std::string file; std::vector<Tref> t; };
struct Env { size_t slot[2] = {0, 0}; size_t cap = 0; int cur = 0; std::vector<uint32_t> dims; bool has = false; };
static std::vector<Worker> W;
static std::map<std::string, GraphRef> graphs;   // by file
static std::map<std::string, Env> env;
static size_t shmTop = 0;

static std::string envName(const std::string& n) { return n == "hout" ? "h" : n; }
static size_t prod(const std::vector<uint32_t>& d) { size_t n = 1; for (auto x : d) n *= x; return n; }
static Env& envGet(const std::string& n, size_t elems) {
  Env& e = env[n];
  if (e.cap < elems) {
    size_t bytes = (elems * 4 + 4095) & ~size_t(4095);
    if (shmTop + 2 * bytes > SHM_BYTES) { fprintf(stderr, "shared memory full\n"); exit(1); }
    e.slot[0] = shmTop; e.slot[1] = shmTop + bytes; shmTop += 2 * bytes; e.cap = elems; e.has = false;
  }
  return e;
}
static float* envPtr(Env& e, int s) { return (float*)(SHM + e.slot[s]); }
static void setEnv(const std::string& n, const std::vector<float>& v, std::vector<uint32_t> dims) {
  Env& e = envGet(n, v.size()); memcpy(envPtr(e, e.cur), v.data(), v.size() * 4); e.dims = dims; e.has = true;
}
static std::vector<float> getEnv(const std::string& n) { Env& e = env.at(n); size_t k = prod(e.dims); return std::vector<float>(envPtr(e, e.cur), envPtr(e, e.cur) + k); }

static void spawnWorkers(int n, const char* be, const char* sys) {
  for (int i = 0; i < n; i++) {
    int a[2], b[2]; if (pipe(a) || pipe(b)) { perror("pipe"); exit(1); }
    pid_t p = fork();
    if (p == 0) { close(a[1]); close(b[0]); exit(workerMain(be, sys, a[0], b[1])); }
    close(a[0]); close(b[1]);
    W.push_back({p, fdopen(a[1], "w"), fdopen(b[0], "r")});
  }
}
static void send(int w, const std::string& s) { fputs((s + "\n").c_str(), W[w].to); fflush(W[w].to); }
static std::string recvLine(int w) { char buf[8192]; if (!fgets(buf, sizeof buf, W[w].from)) { fprintf(stderr, "worker %d died\n", w); exit(1); } std::string s(buf); while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back(); return s; }

// loads files on several workers at once (each list on its own worker); registers their tensors
static void loadOn(const std::vector<std::pair<int, std::vector<std::string>>>& plan) {
  auto t0 = Clk::now();
  for (auto& [w, files] : plan) { std::string c = "load"; for (auto& f : files) c += " " + f; send(w, c); }
  for (auto& [w, files] : plan) {
    GraphRef* cur = nullptr;
    while (true) {
      std::string l = recvLine(w); std::istringstream ss(l); std::string k; ss >> k;
      if (k == "ok") break;
      if (k == "fail") { fprintf(stderr, "worker %d failed to load %s\n", w, files[0].c_str()); exit(1); }
      if (k == "G") { size_t idx; std::string f; ss >> idx >> f; cur = &graphs[f]; *cur = GraphRef{w, idx, f, {}}; }
      if (k == "T") { std::string io, name, ty; ss >> io >> name >> ty; Tref t{name, io == "in", {}, 0}; uint32_t d; while (ss >> d) t.dims.push_back(d); t.elems = prod(t.dims); cur->t.push_back(t); }
    }
  }
  for (auto& [f, g] : graphs) for (auto& t : g.t) envGet(envName(t.name), t.elems);
  fprintf(stderr, "loaded");
  for (auto& [w, files] : plan) { fprintf(stderr, " [w%d:", w); for (auto& f : files) fprintf(stderr, " %s", f.c_str()); fprintf(stderr, "]"); }
  fprintf(stderr, " in %.1fs\n", since(t0));
}
static void freeOn(const std::vector<int>& ws) {
  for (int w : ws) send(w, "free");
  for (int w : ws) recvLine(w);
  for (auto it = graphs.begin(); it != graphs.end();) { bool del = false; for (int w : ws) if (it->second.w == w) del = true; it = del ? graphs.erase(it) : std::next(it); }
}

static bool nhwcOf(const std::vector<uint32_t>& a, const std::vector<uint32_t>& b) {   // a is the NHWC version of NCHW b
  return a.size() == 4 && b.size() == 4 && a[0] == b[0] && a[1] == b[2] && a[2] == b[3] && a[3] == b[1];
}
static void permute(const float* s, float* d, const std::vector<uint32_t>& from, bool toNHWC) {
  if (toNHWC) { uint32_t C = from[1], H = from[2], Wd = from[3]; for (uint32_t c = 0; c < C; c++) for (uint32_t y = 0; y < H; y++) for (uint32_t x = 0; x < Wd; x++) d[(y * Wd + x) * C + c] = s[(c * H + y) * Wd + x]; }
  else { uint32_t H = from[1], Wd = from[2], C = from[3]; for (uint32_t c = 0; c < C; c++) for (uint32_t y = 0; y < H; y++) for (uint32_t x = 0; x < Wd; x++) d[(c * H + y) * Wd + x] = s[(y * Wd + x) * C + c]; }
}

static std::map<std::string, double> partMs;
static double run(const std::string& file) {
  GraphRef& g = graphs.at(file);
  std::ostringstream c; c << "run " << g.idx;
  std::vector<std::string> inNames;
  for (auto& t : g.t) if (t.isIn) {
    std::string n = envName(t.name); Env& e = env.at(n); inNames.push_back(n);
    if (!e.has) { fprintf(stderr, "%s: input %s not set\n", file.c_str(), n.c_str()); exit(1); }
    if (e.dims != t.dims) {
      if (prod(e.dims) != t.elems) { fprintf(stderr, "%s: input %s has %zu values, want %zu\n", file.c_str(), n.c_str(), prod(e.dims), t.elems); exit(1); }
      if (nhwcOf(t.dims, e.dims)) permute(envPtr(e, e.cur), envPtr(e, 1 - e.cur), e.dims, true);
      else if (nhwcOf(e.dims, t.dims)) permute(envPtr(e, e.cur), envPtr(e, 1 - e.cur), e.dims, false);
      else memcpy(envPtr(e, 1 - e.cur), envPtr(e, e.cur), t.elems * 4);   // same order, other shape (e.g. [1,1280] vs [1280])
      e.cur = 1 - e.cur; e.dims = t.dims;
    }
    c << " " << e.slot[e.cur];
  }
  std::vector<std::pair<std::string, int>> outSlots;
  for (auto& t : g.t) if (!t.isIn) {
    std::string n = envName(t.name); Env& e = env.at(n);
    int s = e.cur; for (auto& i : inNames) if (i == n) s = 1 - e.cur;   // never write a tensor that is also read by this graph
    c << " " << e.slot[s]; outSlots.push_back({n, s});
  }
  send(g.w, c.str());
  std::string r = recvLine(g.w);
  if (r.rfind("ok", 0) != 0) { fprintf(stderr, "run %s failed\n", file.c_str()); exit(1); }
  size_t k = 0;
  for (auto& t : g.t) if (!t.isIn) { Env& e = env.at(outSlots[k].first); e.cur = outSlots[k].second; e.dims = t.dims; e.has = true; k++; }
  double ms = atof(r.c_str() + 3); partMs[file] += ms; return ms;
}

// ---------- embedding (diffusers Timesteps + TimestepEmbedding, SDXL: flip_sin_to_cos, shift 0) ----------
struct Lin { int in, out; std::vector<float> w, b; };   // w [out][in]
static void sinus(float t, int dim, float* o) {
  int half = dim / 2;
  for (int i = 0; i < half; i++) { float f = expf(-logf(10000.f) * i / half) * t; o[i] = cosf(f); o[half + i] = sinf(f); }
}
static std::vector<float> lin(const Lin& L, const std::vector<float>& x, bool silu_in) {
  std::vector<float> xs = x; if (silu_in) for (auto& v : xs) v = v / (1.f + expf(-v));
  std::vector<float> y(L.out);
  for (int o = 0; o < L.out; o++) { const float* w = &L.w[(size_t)o * L.in]; double s = L.b[o]; for (int i = 0; i < L.in; i++) s += w[i] * xs[i]; y[o] = (float)s; }
  return y;
}
struct Emb { Lin t1, t2, a1, a2; };
static Emb loadEmb() {   // emb.bin: t1 [1280x320]+b, t2 [1280x1280]+b, a1 [1280x2816]+b, a2 [1280x1280]+b
  auto v = readF(DIR + "/emb.bin"); size_t p = 0; Emb E;
  auto take = [&](Lin& L, int in, int out) { L.in = in; L.out = out; L.w.assign(v.begin() + p, v.begin() + p + (size_t)in * out); p += (size_t)in * out; L.b.assign(v.begin() + p, v.begin() + p + out); p += out; };
  take(E.t1, 320, 1280); take(E.t2, 1280, 1280); take(E.a1, 2816, 1280); take(E.a2, 1280, 1280);
  if (p != v.size()) { fprintf(stderr, "emb.bin size mismatch\n"); exit(1); }
  return E;
}
static std::vector<float> embed(const Emb& E, float t, const std::vector<float>& pooled, int Wd, int H) {
  std::vector<float> tp(320); sinus(t, 320, tp.data());
  auto te = lin(E.t2, lin(E.t1, tp, false), true);
  std::vector<float> a(2816); memcpy(a.data(), pooled.data(), 1280 * 4);
  float ids[6] = {(float)H, (float)Wd, 0, 0, (float)H, (float)Wd};
  for (int i = 0; i < 6; i++) sinus(ids[i], 256, &a[1280 + 256 * i]);
  auto ae = lin(E.a2, lin(E.a1, a, false), true);
  for (int i = 0; i < 1280; i++) te[i] += ae[i];
  return te;
}

// ---------- stages ----------
static void textEncode(const std::string& idsL, const std::string& idsG, std::vector<float>& ctx, std::vector<float>& pooled) {
  auto rdIds = [](const std::string& p) { auto b = readFile(p); if (b.size() != 77 * 4) { fprintf(stderr, "bad ids %s\n", p.c_str()); exit(1); }
    std::vector<int32_t> i(77); memcpy(i.data(), b.data(), 77 * 4); return i; };
  auto il = rdIds(idsL), ig = rdIds(idsG);
  setEnv("ids", std::vector<float>(il.begin(), il.end()), {1, 77}); run("te_l.bin");
  std::vector<float> hl = getEnv("hidden");                                        // [77,768] penultimate layer
  setEnv("ids", std::vector<float>(ig.begin(), ig.end()), {1, 77}); run("te_g.bin");
  std::vector<float> hg = getEnv("hidden"), last = getEnv("last");                 // [77,1280] penultimate, final-LN last layer
  ctx.resize(77 * 2048);
  for (int r = 0; r < 77; r++) { memcpy(&ctx[r * 2048], &hl[r * 768], 768 * 4); memcpy(&ctx[r * 2048 + 768], &hg[r * 1280], 1280 * 4); }
  int eos = 76; for (int r = 0; r < 77; r++) if (ig[r] == 49407) { eos = r; break; }   // first EOS = HF argmax(ids) for CLIP-G (pad id 0)
  auto P = readF(DIR + "/te_proj.bin", 1280 * 1280);
  pooled.assign(1280, 0.f);
  for (int o = 0; o < 1280; o++) { double s = 0; for (int i = 0; i < 1280; i++) s += P[(size_t)o * 1280 + i] * last[eos * 1280 + i]; pooled[o] = (float)s; }
}

static std::vector<std::vector<int>> parseGroups() {
  std::string g = getenv("SDXL_GROUPS") ? getenv("SDXL_GROUPS") : "0,1,2;3,4,7;5,6";
  std::vector<std::vector<int>> r(1); std::string num;
  for (char ch : g + ";") {
    if (ch == ',' || ch == ';') { if (!num.empty()) r.back().push_back(atoi(num.c_str())); num.clear(); if (ch == ';') r.emplace_back(); }
    else num += ch;
  }
  while (!r.empty() && r.back().empty()) r.pop_back();
  return r;
}
static std::vector<std::vector<int>> GROUPS;
static int nParts() { int n = 0; while (FILE* f = fopen((DIR + "/part" + std::to_string(n) + ".bin").c_str(), "rb")) { fclose(f); n++; } return n; }
static void loadUnet() {
  std::vector<std::pair<int, std::vector<std::string>>> plan;
  int n = nParts(); if (!n) { fprintf(stderr, "no part*.bin in %s\n", DIR.c_str()); exit(1); }
  for (size_t gi = 0; gi < GROUPS.size(); gi++) { std::vector<std::string> f; for (int p : GROUPS[gi]) if (p < n) f.push_back("part" + std::to_string(p) + ".bin"); if (!f.empty()) plan.push_back({(int)gi, f}); }
  loadOn(plan);
}
static void unetCall(const std::vector<float>& x, const std::vector<float>& emb, const std::vector<float>& ctx, std::vector<float>& eps, uint32_t h, uint32_t w) {
  std::vector<uint32_t> xd = {1, 4, h, w};
  for (auto& t : graphs.at("part0.bin").t) if (t.isIn && t.name == "h" && t.elems == x.size()) xd = t.dims;   // single-part tests feed other shapes
  setEnv("h", x, xd); setEnv("emb", emb, {1, 1280}); setEnv("ctx", ctx, {1, 77, 2048});
  for (int i = 0; i < nParts(); i++) run("part" + std::to_string(i) + ".bin");
  Env& e = env.at("h");
  if (nhwcOf(e.dims, {1, 4, h, w})) { permute(envPtr(e, e.cur), envPtr(e, 1 - e.cur), e.dims, false); e.cur = 1 - e.cur; e.dims = {1, 4, h, w}; }
  eps = getEnv("h");
}
static void vaeDecode(const std::vector<float>& z, std::vector<float>& img, uint32_t h, uint32_t w) {
  setEnv("z", z, {1, 4, h, w}); run("vae.bin");
  Env& e = env.at("image");
  if (nhwcOf(e.dims, {1, 3, h * 8, w * 8})) { permute(envPtr(e, e.cur), envPtr(e, 1 - e.cur), e.dims, false); e.cur = 1 - e.cur; e.dims = {1, 3, h * 8, w * 8}; }
  img = getEnv("image");
}
static void writePPM(const std::string& p, const std::vector<float>& img, int H, int Wd) {
  FILE* f = fopen(p.c_str(), "wb"); fprintf(f, "P6\n%d %d\n255\n", Wd, H);
  std::vector<uint8_t> row(Wd * 3);
  for (int y = 0; y < H; y++) { for (int x = 0; x < Wd; x++) for (int c = 0; c < 3; c++) { float v = (img[((size_t)c * H + y) * Wd + x] + 1.f) * 127.5f; row[x * 3 + c] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : lrintf(v)); } fwrite(row.data(), 1, row.size(), f); }
  fclose(f);
}

int main(int argc, char** argv) {
  if (argc < 5) { fprintf(stderr, "usage: sdxlrun BACKEND SYSTEM DIR te|unet|vae|gen ...\n"); return 2; }
  DIR = argv[3]; std::string cmd = argv[4];
  SHM = (uint8_t*)mmap(nullptr, SHM_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
  if (SHM == MAP_FAILED) { perror("mmap"); return 1; }
  GROUPS = parseGroups();
  spawnWorkers((int)GROUPS.size(), argv[1], argv[2]);
  auto T0 = Clk::now();
  auto all = [&]() { std::vector<int> v; for (size_t i = 0; i < W.size(); i++) v.push_back((int)i); return v; };

  if (cmd == "te" && argc >= 9) {
    loadOn({{0, {"te_l.bin", "te_g.bin"}}});
    std::vector<float> ctx, pooled; auto t = Clk::now(); textEncode(argv[5], argv[6], ctx, pooled);
    printf("te %.3fs\n", since(t)); writeF(argv[7], ctx); writeF(argv[8], pooled);
  } else if (cmd == "unet" && argc >= 9) {
    loadUnet();
    auto x = readF(argv[5]); auto emb = readF(argv[6], 1280); auto ctx = readF(argv[7], 77 * 2048);
    uint32_t hw = (uint32_t)lrint(sqrt(x.size() / 4.0));   // square latents in this test mode
    int reps = argc > 9 ? atoi(argv[9]) : 1; std::vector<float> eps;
    for (int r = 0; r < reps; r++) { auto t = Clk::now(); unetCall(x, emb, ctx, eps, hw, hw); printf("unet %.3fs\n", since(t)); fflush(stdout); }
    for (auto& [f, ms] : partMs) printf("  %s %.0f ms\n", f.c_str(), ms / reps);
    writeF(argv[8], eps);
  } else if (cmd == "vae" && argc >= 7) {
    loadOn({{0, {"vae.bin"}}});
    auto z = readF(argv[5]); uint32_t hw = (uint32_t)lrint(sqrt(z.size() / 4.0)); std::vector<float> img;
    auto t = Clk::now(); vaeDecode(z, img, hw, hw); printf("vae %.3fs\n", since(t));
    writeF(argv[6], img);
  } else if (cmd == "gen" && argc >= 16) {
    float cfg = atof(argv[12]); int Wd = atoi(argv[13]), H = atoi(argv[14]); std::string out = argv[15];
    uint32_t h = H / 8, w = Wd / 8;
    std::vector<float> ctxP, poolP, ctxN, poolN;
    { auto t = Clk::now(); loadOn({{0, {"te_l.bin", "te_g.bin"}}}); double tl = since(t); t = Clk::now();
      textEncode(argv[5], argv[6], ctxP, poolP);
      if (cfg != 1.f) textEncode(argv[7], argv[8], ctxN, poolN);
      printf("text: load %.1fs, encode %.2fs\n", tl, since(t)); fflush(stdout); freeOn({0}); }
    auto x = readF(argv[9], 4 * h * w); auto sig = readF(argv[10]); auto ts = readF(argv[11]); int steps = (int)ts.size();
    if ((int)sig.size() != steps + 1) { fprintf(stderr, "sigmas must have steps+1 values\n"); return 1; }
    Emb E = loadEmb();
    { auto t = Clk::now(); loadUnet(); printf("unet load %.1fs\n", since(t)); fflush(stdout); }
    partMs.clear(); double tot = 0;
    for (int i = 0; i < steps; i++) {
      auto t = Clk::now();
      float s = sig[i], c_in = 1.f / sqrtf(s * s + 1.f);
      std::vector<float> xin(x.size()); for (size_t k = 0; k < x.size(); k++) xin[k] = x[k] * c_in;
      std::vector<float> ec, eu;
      unetCall(xin, embed(E, ts[i], poolP, Wd, H), ctxP, ec, h, w);
      if (cfg != 1.f) { unetCall(xin, embed(E, ts[i], poolN, Wd, H), ctxN, eu, h, w); for (size_t k = 0; k < ec.size(); k++) ec[k] = eu[k] + cfg * (ec[k] - eu[k]); }
      float dt = sig[i + 1] - s; for (size_t k = 0; k < x.size(); k++) x[k] += dt * ec[k];
      double st = since(t); tot += st; printf("step %d/%d t=%.0f %.2fs\n", i + 1, steps, ts[i], st); fflush(stdout);
    }
    int calls = steps * (cfg != 1.f ? 2 : 1);
    printf("unet: %.2f s/step, %.2f s/call;", tot / steps, tot / calls); for (auto& [f, ms] : partMs) printf(" %s %.0fms", f.c_str(), ms / calls); printf("\n");
    writeF(out + ".lat", x);
    freeOn(all());
    { auto t = Clk::now(); loadOn({{0, {"vae.bin"}}}); printf("vae load %.1fs\n", since(t)); }
    const float SF = 0.13025f; std::vector<float> z(x.size()), img; for (size_t k = 0; k < x.size(); k++) z[k] = x[k] / SF;
    auto t = Clk::now(); vaeDecode(z, img, h, w); printf("vae %.2fs\n", since(t));
    writePPM(out + ".ppm", img, H, Wd);
    printf("total %.1fs\n", since(T0));
  } else { fprintf(stderr, "bad arguments\n"); return 2; }
  for (size_t i = 0; i < W.size(); i++) send((int)i, "quit");
  for (auto& w : W) waitpid(w.pid, nullptr, 0);
  return 0;
}
