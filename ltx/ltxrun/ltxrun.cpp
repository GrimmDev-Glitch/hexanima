// ltxrun: LTX-Video 2B 0.9.8 distilled on the Hexagon NPU through QNN context binaries
// (transformer in 4 chunks + VAE decoder; contexts from huggingface.co/timmyboy26/LTX-Video-2B-0.9.8-distilled-QNN).
// Written for HexAnima; the tensor layout follows those contexts (prelude+blocks 0-6, 7-13, 14-20, 21-27+head).
//
// usage: ltxrun BACKEND.so SYSTEM.so DIR STATS.bin
//   DIR holds ltxv_chunk0..3.bin and ltxv_vae_<W>x<H>x<F>.bin; STATS.bin = 128 means + 128 stds of the VAE latents (float32)
// stdin: "gen EMB MASK SEED OUT [W H F fps]"  EMB = 128x4096 f32 T5 states, MASK = 128 f32 (0 / -10000)
//        writes OUT = F frames of W*H RGB bytes; prints "step k/8 <ms>", "vae <ms>", "done <ms>"
//        "quit" ends.
#include <android/dlext.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <map>
#include <memory>
#include <random>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

#include "HTP/QnnHtpContext.h"
#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "HTP/QnnHtpSystemContext.h"
#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"

static QNN_INTERFACE_VER_TYPE qi;
static QNN_SYSTEM_INTERFACE_VER_TYPE qsi;
static Qnn_BackendHandle_t backend = nullptr;
static Qnn_DeviceHandle_t device = nullptr;

static double now_ms() { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
static bool check(Qnn_ErrorHandle_t e, const char* what) {
  if (e != QNN_SUCCESS) { fprintf(stderr, "ERROR %s: 0x%lx\n", what, (unsigned long)e); return false; }
  return true;
}
static std::vector<uint8_t> readFile(const std::string& p) {
  FILE* f = fopen(p.c_str(), "rb"); std::vector<uint8_t> v; if (!f) return v;
  fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET); v.resize(n);
  if (fread(v.data(), 1, n, f) != (size_t)n) v.clear(); fclose(f); return v;
}

// ---- one graph: its tensors with our own copies of the metadata and host buffers ----
struct Tensor {
  std::string name; std::vector<uint32_t> dims; Qnn_Tensor_t t; std::vector<uint8_t> buf;
  uint32_t elems = 1; Qnn_DataType_t type; float scale = 1.f; int32_t offset = 0;
  bool is16() const { return type == QNN_DATATYPE_FLOAT_16; }
  bool quant() const { return type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_UFIXED_POINT_8 || type == QNN_DATATYPE_SFIXED_POINT_16 || type == QNN_DATATYPE_SFIXED_POINT_8; }
  void set(const float* s, size_t n) {
    if (n != elems) { fprintf(stderr, "size mismatch %s: %zu vs %u\n", name.c_str(), n, elems); exit(3); }
    switch (type) {
      case QNN_DATATYPE_FLOAT_32: memcpy(buf.data(), s, n * 4); break;
      case QNN_DATATYPE_FLOAT_16: { __fp16* d = (__fp16*)buf.data(); for (size_t i = 0; i < n; i++) d[i] = (__fp16)s[i]; } break;
      case QNN_DATATYPE_UFIXED_POINT_16: { uint16_t* d = (uint16_t*)buf.data(); for (size_t i = 0; i < n; i++) { long q = lroundf(s[i] / scale) - offset; d[i] = (uint16_t)std::min(65535L, std::max(0L, q)); } } break;
      case QNN_DATATYPE_UFIXED_POINT_8: { uint8_t* d = buf.data(); for (size_t i = 0; i < n; i++) { long q = lroundf(s[i] / scale) - offset; d[i] = (uint8_t)std::min(255L, std::max(0L, q)); } } break;
      case QNN_DATATYPE_SFIXED_POINT_16: { int16_t* d = (int16_t*)buf.data(); for (size_t i = 0; i < n; i++) { long q = lroundf(s[i] / scale) - offset; d[i] = (int16_t)std::min(32767L, std::max(-32768L, q)); } } break;
      default: fprintf(stderr, "unsupported input type 0x%x for %s\n", type, name.c_str()); exit(3);
    }
  }
  void get(float* d) const {
    switch (type) {
      case QNN_DATATYPE_FLOAT_32: memcpy(d, buf.data(), elems * 4); break;
      case QNN_DATATYPE_FLOAT_16: { const __fp16* s = (const __fp16*)buf.data(); for (uint32_t i = 0; i < elems; i++) d[i] = (float)s[i]; } break;
      case QNN_DATATYPE_UFIXED_POINT_16: { const uint16_t* s = (const uint16_t*)buf.data(); for (uint32_t i = 0; i < elems; i++) d[i] = ((int)s[i] + offset) * scale; } break;
      case QNN_DATATYPE_UFIXED_POINT_8: { const uint8_t* s = buf.data(); for (uint32_t i = 0; i < elems; i++) d[i] = ((int)s[i] + offset) * scale; } break;
      case QNN_DATATYPE_SFIXED_POINT_16: { const int16_t* s = (const int16_t*)buf.data(); for (uint32_t i = 0; i < elems; i++) d[i] = ((int)s[i] + offset) * scale; } break;
      default: fprintf(stderr, "unsupported output type 0x%x for %s\n", type, name.c_str()); exit(3);
    }
  }
};
static size_t typeBytes(Qnn_DataType_t t) {
  switch (t) { case QNN_DATATYPE_FLOAT_32: return 4; case QNN_DATATYPE_FLOAT_16: case QNN_DATATYPE_UFIXED_POINT_16: case QNN_DATATYPE_SFIXED_POINT_16: return 2; default: return 1; }
}
static void copyTensor(const Qnn_Tensor_t& src, Tensor& d) {
  d.t = src;
  bool v1 = src.version == QNN_TENSOR_VERSION_1;
  d.name = v1 ? src.v1.name : src.v2.name;
  uint32_t rank = v1 ? src.v1.rank : src.v2.rank; const uint32_t* dims = v1 ? src.v1.dimensions : src.v2.dimensions;
  d.dims.assign(dims, dims + rank); for (uint32_t x : d.dims) d.elems *= x;
  d.type = v1 ? src.v1.dataType : src.v2.dataType;
  const Qnn_QuantizeParams_t& qp = v1 ? src.v1.quantizeParams : src.v2.quantizeParams;
  if (qp.encodingDefinition == QNN_DEFINITION_DEFINED && qp.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) { d.scale = qp.scaleOffsetEncoding.scale; d.offset = qp.scaleOffsetEncoding.offset; }
  d.buf.assign(d.elems * typeBytes(d.type), 0);
  if (v1) { d.t.v1.name = d.name.c_str(); d.t.v1.dimensions = d.dims.data(); d.t.v1.memType = QNN_TENSORMEMTYPE_RAW; d.t.v1.clientBuf.data = d.buf.data(); d.t.v1.clientBuf.dataSize = d.buf.size(); }
  else { d.t.v2.name = d.name.c_str(); d.t.v2.dimensions = d.dims.data(); d.t.v2.memType = QNN_TENSORMEMTYPE_RAW; d.t.v2.clientBuf.data = d.buf.data(); d.t.v2.clientBuf.dataSize = d.buf.size(); }
}

struct Graph {
  std::string name; Qnn_ContextHandle_t ctx = nullptr; Qnn_GraphHandle_t graph = nullptr;
  std::vector<Tensor> in, out;
  Tensor& I(const std::string& n) { for (auto& t : in) if (t.name == n) return t; fprintf(stderr, "%s: no input %s\n", name.c_str(), n.c_str()); exit(3); }
  Tensor& O(const std::string& n) { for (auto& t : out) if (t.name == n) return t; fprintf(stderr, "%s: no output %s\n", name.c_str(), n.c_str()); exit(3); }
  bool run() {
    std::vector<Qnn_Tensor_t> a, b; for (auto& t : in) a.push_back(t.t); for (auto& t : out) b.push_back(t.t);
    return check(qi.graphExecute(graph, a.data(), a.size(), b.data(), b.size(), nullptr, nullptr), name.c_str());
  }
  void free() { if (ctx) qi.contextFree(ctx, nullptr); ctx = nullptr; graph = nullptr; }
};
static const char* tname(Qnn_DataType_t t) { switch (t) { case QNN_DATATYPE_FLOAT_32: return "f32"; case QNN_DATATYPE_FLOAT_16: return "f16"; case QNN_DATATYPE_UFIXED_POINT_16: return "u16q"; case QNN_DATATYPE_UFIXED_POINT_8: return "u8q"; case QNN_DATATYPE_SFIXED_POINT_16: return "s16q"; default: return "?"; } }

// group: register the chunks as one group so they share a spill-fill buffer (the HTP address space of V73 is tight)
static bool loadGraph(const std::string& path, Graph& g, Qnn_ContextHandle_t first = nullptr, uint64_t spill = 0) {
  double t0 = now_ms();
  std::vector<uint8_t> bin = readFile(path);
  if (bin.empty()) { fprintf(stderr, "cannot read %s\n", path.c_str()); return false; }
  QnnSystemContext_Handle_t sh = nullptr;
  if (!check(qsi.systemContextCreate(&sh), "systemContextCreate")) return false;
  const QnnSystemContext_BinaryInfo_t* bi = nullptr; Qnn_ContextBinarySize_t bis = 0;
  if (!check(qsi.systemContextGetBinaryInfo(sh, bin.data(), bin.size(), &bi, &bis), "getBinaryInfo")) return false;
  const QnnSystemContext_GraphInfo_t* gi = bi->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1 ? bi->contextBinaryInfoV1.graphs
                                         : bi->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2 ? bi->contextBinaryInfoV2.graphs : bi->contextBinaryInfoV3.graphs;
  const char* gname; uint32_t nin, nout; Qnn_Tensor_t *gin, *gout;
  if (gi->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) { auto& v = gi->graphInfoV1; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
  else if (gi->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) { auto& v = gi->graphInfoV2; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
  else { auto& v = gi->graphInfoV3; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
  g.name = gname; g.in.resize(nin); g.out.resize(nout);
  for (uint32_t i = 0; i < nin; i++) copyTensor(gin[i], g.in[i]);
  for (uint32_t i = 0; i < nout; i++) copyTensor(gout[i], g.out[i]);

  QnnHtpContext_CustomConfig_t grp = {}; QnnContext_Config_t cc = {}; const QnnContext_Config_t* cfgs[] = {&cc, nullptr};
  bool grouped = first != nullptr || spill > 0;
  if (grouped) {
    grp.option = QNN_HTP_CONTEXT_CONFIG_OPTION_REGISTER_MULTI_CONTEXTS;
    grp.groupRegistration.firstGroupHandle = first;   // null for the first context of the group
    grp.groupRegistration.maxSpillFillBuffer = spill;
    cc.option = QNN_CONTEXT_CONFIG_OPTION_CUSTOM; cc.customConfig = &grp;
  }
  bool ok = check(qi.contextCreateFromBinary(backend, device, grouped ? cfgs : nullptr, bin.data(), bin.size(), &g.ctx, nullptr), ("contextCreateFromBinary " + path).c_str())
         && check(qi.graphRetrieve(g.ctx, gname, &g.graph), "graphRetrieve");
  qsi.systemContextFree(sh);
  if (!ok) return false;
  fprintf(stderr, "loaded %s (%zu MB) in %.0f ms\n", g.name.c_str(), bin.size() >> 20, now_ms() - t0);
  if (getenv("LTX_VERBOSE")) {
    for (auto& t : g.in) { fprintf(stderr, "   in  %-8s %-5s [", t.name.c_str(), tname(t.type)); for (auto d : t.dims) fprintf(stderr, " %u", d); fprintf(stderr, " ] scale %g off %d\n", t.scale, t.offset); }
    for (auto& t : g.out) { fprintf(stderr, "   out %-8s %-5s [", t.name.c_str(), tname(t.type)); for (auto d : t.dims) fprintf(stderr, " %u", d); fprintf(stderr, " ] scale %g off %d\n", t.scale, t.offset); }
  }
  return true;
}
// the spill-fill buffer a context binary asks for (HTP graph blob info), so a group can share the largest one
static uint64_t spillOf(const std::string& path) {
  std::vector<uint8_t> bin = readFile(path); if (bin.empty()) return 0;
  QnnSystemContext_Handle_t sh = nullptr; if (qsi.systemContextCreate(&sh) != QNN_SUCCESS) return 0;
  const QnnSystemContext_BinaryInfo_t* bi = nullptr; Qnn_ContextBinarySize_t bis = 0; uint64_t r = 0;
  if (qsi.systemContextGetBinaryInfo(sh, bin.data(), bin.size(), &bi, &bis) == QNN_SUCCESS && bi->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
    const auto& v3 = bi->contextBinaryInfoV3;
    for (uint32_t i = 0; i < v3.numGraphs; i++) {
      const auto& g = v3.graphs[i];
      if (g.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3 && g.graphInfoV3.graphBlobInfo) {
        auto* b = (const QnnHtpSystemContext_GraphBlobInfo_t*)g.graphInfoV3.graphBlobInfo;
        if (b->version == QNN_SYSTEM_CONTEXT_HTP_GRAPH_INFO_BLOB_VERSION_V1) r = std::max<uint64_t>(r, b->contextBinaryGraphBlobInfoV1.spillFillBufferSize);
      }
    }
  }
  qsi.systemContextFree(sh); return r;
}

// same dtype and size: hand the bytes over directly, else go through float
static void pass(const Tensor& from, Tensor& to) {
  if (from.type == to.type && from.buf.size() == to.buf.size() && from.scale == to.scale && from.offset == to.offset) { memcpy(to.buf.data(), from.buf.data(), from.buf.size()); return; }
  std::vector<float> f(from.elems); from.get(f.data()); to.set(f.data(), f.size());
}

// loads a library from an anonymous memory file: the QNN HTP backend then does not see the app's native lib dir as "its" folder
// (it looks for libQnnHtpV73Stub.so next to itself, and that folder holds the 2.28 stub of the other models)
static void* dlopenMem(const char* path, const char* name) {
  std::vector<uint8_t> b = readFile(path); if (b.empty()) return nullptr;
  int fd = (int)syscall(__NR_memfd_create, name, 0); if (fd < 0) { fprintf(stderr, "memfd_create failed\n"); return nullptr; }
  for (size_t o = 0; o < b.size(); ) { ssize_t w = write(fd, b.data() + o, b.size() - o); if (w <= 0) { close(fd); return nullptr; } o += w; }
  android_dlextinfo ex = {}; ex.flags = ANDROID_DLEXT_USE_LIBRARY_FD; ex.library_fd = fd;
  void* h = android_dlopen_ext(name, RTLD_NOW | RTLD_LOCAL, &ex);
  if (!h) fprintf(stderr, "android_dlopen_ext %s: %s\n", name, dlerror());
  return h;
}

int main(int argc, char** argv) {
  if (argc < 5) { fprintf(stderr, "usage: ltxrun BACKEND SYSTEM DIR STATS.bin\n"); return 2; }
  const std::string dir = argv[3];
  void* hb = getenv("LTX_MEMFD") ? dlopenMem(argv[1], "libQnnHtp.so") : dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  void* hs = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
  if (!hb || !hs) { fprintf(stderr, "dlopen failed: %s\n", dlerror()); return 1; }
  typedef Qnn_ErrorHandle_t (*GetP)(const QnnInterface_t***, uint32_t*);
  typedef Qnn_ErrorHandle_t (*GetSP)(const QnnSystemInterface_t***, uint32_t*);
  const QnnInterface_t** provs = nullptr; uint32_t np = 0;
  if (!check(((GetP)dlsym(hb, "QnnInterface_getProviders"))(&provs, &np), "getProviders")) return 1;
  qi = provs[0]->QNN_INTERFACE_VER_NAME;
  fprintf(stderr, "QNN API %u.%u.%u, backend %u.%u.%u\n", provs[0]->apiVersion.coreApiVersion.major, provs[0]->apiVersion.coreApiVersion.minor, provs[0]->apiVersion.coreApiVersion.patch,
          provs[0]->apiVersion.backendApiVersion.major, provs[0]->apiVersion.backendApiVersion.minor, provs[0]->apiVersion.backendApiVersion.patch);
  const QnnSystemInterface_t** sprovs = nullptr; uint32_t nsp = 0;
  if (!check(((GetSP)dlsym(hs, "QnnSystemInterface_getProviders"))(&sprovs, &nsp), "getSystemProviders")) return 1;
  qsi = sprovs[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
  if (!check(qi.backendCreate(nullptr, nullptr, &backend), "backendCreate")) return 1;
  if (!check(qi.deviceCreate(nullptr, nullptr, &device), "deviceCreate")) return 1;
  // performance vote for the whole run
  QnnDevice_Infrastructure_t infra = nullptr;
  if (qi.deviceGetInfrastructure && qi.deviceGetInfrastructure(&infra) == QNN_SUCCESS && infra) {
    auto* hi = (QnnHtpDevice_Infrastructure_t*)infra; uint32_t pid = 0;
    if (hi->perfInfra.createPowerConfigId(0, 0, &pid) == QNN_SUCCESS) {
      QnnHtpPerfInfrastructure_PowerConfig_t dc = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT;
      dc.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3; auto& d = dc.dcvsV3Config;
      d.contextId = pid; d.setDcvsEnable = 1; d.dcvsEnable = 0; d.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
      d.setSleepLatency = 1; d.sleepLatency = 40; d.setSleepDisable = 1; d.sleepDisable = 1;
      d.setBusParams = 1; d.busVoltageCornerMin = d.busVoltageCornerTarget = d.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      d.setCoreParams = 1; d.coreVoltageCornerMin = d.coreVoltageCornerTarget = d.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      const QnnHtpPerfInfrastructure_PowerConfig_t* cfgs[] = {&dc, nullptr}; hi->perfInfra.setPowerConfig(pid, cfgs);
    }
  }
  std::vector<uint8_t> st = readFile(argv[4]);
  if (st.size() != 256 * 4) { fprintf(stderr, "bad stats file\n"); return 1; }
  const float* mean = (const float*)st.data(); const float* stdv = mean + 128;

  Graph ch[4]; Graph vae; bool dit = false; uint64_t spill = 0;
  if (!getenv("LTX_NOGROUP")) { for (int i = 0; i < 4; i++) spill = std::max(spill, spillOf(dir + "/ltxv_chunk" + std::to_string(i) + ".bin")); fprintf(stderr, "spill-fill %llu MB\n", (unsigned long long)(spill >> 20)); }
  auto loadDit = [&]() {
    if (dit) return true;
    for (int i = 0; i < 4; i++) if (!loadGraph(dir + "/ltxv_chunk" + std::to_string(i) + ".bin", ch[i], i ? ch[0].ctx : nullptr, spill)) return false;
    dit = true; return true;
  };
  if (!loadDit()) return 1;
  printf("ready\n"); fflush(stdout);

  std::string line;
  while (std::getline(std::cin, line)) {
    std::istringstream ss(line); std::string cmd, fe, fm, fo; long seed = 0; int W = 512, H = 320, F = 25; float fps = 24.f;
    ss >> cmd; if (cmd == "quit") break; if (cmd != "gen") continue;
    ss >> fe >> fm >> seed >> fo; ss >> W >> H >> F >> fps;
    double T0 = now_ms();
    const int w = W / 32, h = H / 32, f = (F - 1) / 8 + 1, T = w * h * f, C = 128, D = 2048, TT = 128;
    auto emb = readFile(fe), msk = readFile(fm);
    if (emb.size() != (size_t)TT * 4096 * 4 || msk.size() != TT * 4) { printf("error inputs\n"); fflush(stdout); continue; }
    if (!loadDit()) { printf("error dit\n"); fflush(stdout); continue; }

    // RoPE tables (LTX-Video 0.9: interleaved over the full inner dim, causal pixel coordinates; dim/6 frequencies per axis, the first dim/2-3n pairs untouched)
    std::vector<float> cs((size_t)T * D / 2, 1.f), sn((size_t)T * D / 2, 0.f);
    { const int n = D / 6, pad = D / 2 - 3 * n; std::vector<double> ind(n);
      for (int i = 0; i < n; i++) ind[i] = std::exp(std::log(10000.0) * (n > 1 ? (double)i / (n - 1) : 0.0)) * (M_PI / 2);
      size_t tok = 0;
      for (int t = 0; t < f; t++) { double pt = std::max(0.0, (double)(t * 8 + 1 - 8)) / fps / 20.0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++, tok++) {
          double co[3] = {pt, (double)(y * 32) / 2048.0, (double)(x * 32) / 2048.0}; size_t base = tok * D / 2 + pad;
          for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) { double fr = ind[i] * (co[a] * 2 - 1); cs[base + i * 3 + a] = (float)std::cos(fr); sn[base + i * 3 + a] = (float)std::sin(fr); } } } }

    std::mt19937_64 rng(seed); std::normal_distribution<float> nd(0.f, 1.f);
    std::vector<float> x((size_t)T * C); for (auto& v : x) v = nd(rng);   // token-major [T, C], tokens = frame, row, column
    const float sig[9] = {1.0f, 0.9937f, 0.9875f, 0.9812f, 0.975f, 0.9094f, 0.725f, 0.4219f, 0.0f};
    std::vector<float> tf((size_t)T * 256), v((size_t)T * C);
    for (int i = 0; i < 4; i++) { ch[i].I("mask").set((const float*)msk.data(), TT); ch[i].I("cos").set(cs.data(), cs.size()); ch[i].I("sin").set(sn.data(), sn.size()); }
    ch[0].I("caption").set((const float*)emb.data(), (size_t)TT * 4096);
    bool ok = true;
    for (int s = 0; s < 8 && ok; s++) {
      double t1 = now_ms();
      { std::vector<float> one(256); const int half = 128;   // diffusers Timesteps(256, flip_sin_to_cos) of sigma * 1000
        for (int i = 0; i < half; i++) { double a = (double)sig[s] * 1000.0 * std::exp(-std::log(10000.0) * i / half); one[i] = (float)std::cos(a); one[half + i] = (float)std::sin(a); }
        for (int t = 0; t < T; t++) memcpy(&tf[(size_t)t * 256], one.data(), 256 * 4); }
      ch[0].I("latent").set(x.data(), x.size()); ch[0].I("t_freq").set(tf.data(), tf.size());
      ok = ch[0].run();
      for (int i = 1; i < 4 && ok; i++) {
        pass(i == 1 ? ch[0].O("x") : ch[i - 1].O("x_out"), ch[i].I("x"));
        pass(ch[0].O("tmod"), ch[i].I("tmod")); pass(ch[0].O("context"), ch[i].I("context"));
        if (i == 3) pass(ch[0].O("emb"), ch[3].I("emb"));
        ok = ch[i].run();
      }
      if (!ok) break;
      ch[3].O("out").get(v.data());
      const float dt = sig[s + 1] - sig[s];   // flow matching Euler: the model predicts the velocity
      for (size_t k = 0; k < x.size(); k++) x[k] += dt * v[k];
      printf("step %d/8 %.0f\n", s + 1, now_ms() - t1); fflush(stdout);
    }
    if (!ok) { printf("error dit run\n"); fflush(stdout); continue; }
    if (getenv("LTX_DUMP")) { FILE* d = fopen((fo + ".latent").c_str(), "wb"); fwrite(x.data(), 4, x.size(), d); fclose(d); }

    // VAE: the transformer does not fit next to it on V73, so free it first (reloaded for the next prompt)
    double t2 = now_ms();
    for (auto& g : ch) g.free(); dit = false;
    std::string vp = dir + "/ltxv_vae_" + std::to_string(W) + "x" + std::to_string(H) + "x" + std::to_string(F) + ".bin";
    if (!loadGraph(vp, vae)) { printf("error vae load\n"); fflush(stdout); continue; }
    // un-normalize per channel and add the small decode noise of the distilled pipeline (decode_noise_scale 0.025 at timestep 0.05)
    std::vector<float> z((size_t)C * T);
    const float dn = getenv("LTX_DECODE_NOISE") ? atof(getenv("LTX_DECODE_NOISE")) : 0.025f;
    for (int c = 0; c < C; c++) for (int t = 0; t < T; t++) { float lat = x[(size_t)t * C + c]; lat = (1 - dn) * lat + dn * nd(rng); z[(size_t)c * T + t] = lat * stdv[c] + mean[c]; }
    vae.I("z").set(z.data(), z.size()); float temb = 0.05f; vae.I("temb").set(&temb, 1);
    if (!vae.run()) { printf("error vae run\n"); fflush(stdout); vae.free(); continue; }
    Tensor& vo = vae.O("video"); std::vector<float> o(vo.elems); vo.get(o.data()); vae.free();
    // [1, 3, F, H, W] in -1..1 -> F frames of RGB24
    std::vector<uint8_t> rgb((size_t)F * H * W * 3); const size_t plane = (size_t)F * H * W;
    for (size_t p = 0; p < plane; p++) for (int c = 0; c < 3; c++) { float val = (o[c * plane + p] + 1.f) * 127.5f; rgb[p * 3 + c] = (uint8_t)std::min(255.f, std::max(0.f, val + 0.5f)); }
    FILE* fw = fopen(fo.c_str(), "wb"); fwrite(rgb.data(), 1, rgb.size(), fw); fclose(fw);
    printf("vae %.0f\n", now_ms() - t2);
    printf("done %.0f\n", now_ms() - T0); fflush(stdout);
  }
  return 0;
}
