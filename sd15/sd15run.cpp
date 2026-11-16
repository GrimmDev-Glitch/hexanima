// sd15run: Stable Diffusion 1.5 on the Snapdragon NPU for HexAnima (own implementation).
// Runs the prebuilt 8 Gen 2 model packs of xororz/sd-qnn ("<Model>_qnn2.28_8gen2.zip": unet.bin / vae_decoder.bin QNN 2.28 HTP contexts, W8A16 with
// uint16 inputs/outputs; clip_v2.mnn CLIP text encoder run on the CPU with MNN; token_emb.bin [49408][768] + pos_emb.bin [77][768] fp32).
// Facts used (checked on the files): the MNN graph takes token_emb[id] + pos_emb[pos] (it adds no position itself); the UNet predicts epsilon and takes the
// integer timestep; latents are divided by 0.18215 before the VAE decoder, whose output is in [-1, 1].
// Commands on stdin (one model loaded per process):
//   gen POS.i32 NEG.i32 STEPS CFG SEED SAMPLER OUT.rgb   (ids: int32[77], start 49406, padded with 49407; SAMPLER: dpm2m_karras | dpm2m | euler_a)
//        prints "step i n ms" lines, then "done <ms>"; OUT.rgb = uint8 RGB [512][512][3]
//   quit
// usage: sd15run BACKEND.so SYSTEM.so MODEL_DIR
#include <dlfcn.h>
#include <unistd.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <random>
#include <sstream>
#include <string>
#include <vector>

#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>

#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"

static QNN_INTERFACE_VER_TYPE qi;
static QNN_SYSTEM_INTERFACE_VER_TYPE qsi;
static Qnn_BackendHandle_t g_backend;
static Qnn_DeviceHandle_t g_device;
using clk = std::chrono::steady_clock;
static double ms_since(clk::time_point t) { return std::chrono::duration<double, std::milli>(clk::now() - t).count(); }

static bool check(Qnn_ErrorHandle_t e, const char* what) {
  if (e != QNN_SUCCESS) { fprintf(stderr, "ERROR %s: 0x%lx\n", what, (unsigned long)e); return false; }
  return true;
}
static std::vector<uint8_t> readFile(const std::string& p) {
  FILE* f = fopen(p.c_str(), "rb"); std::vector<uint8_t> v; if (!f) return v;
  fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET); v.resize(n);
  if (fread(v.data(), 1, n, f) != (size_t)n) v.clear(); fclose(f); return v;
}

// ---- one QNN graph with any number of inputs/outputs; float <-> uint16 / fp16 / int32 conversion per tensor ----
struct QTensor {
  Qnn_Tensor_t t; std::vector<uint8_t> buf; uint32_t n = 1; uint32_t type; float scale = 1; int32_t offset = 0; std::string name;
  void bind() {
    if (t.version == QNN_TENSOR_VERSION_1) { t.v1.memType = QNN_TENSORMEMTYPE_RAW; t.v1.clientBuf.data = buf.data(); t.v1.clientBuf.dataSize = buf.size(); }
    else { t.v2.memType = QNN_TENSORMEMTYPE_RAW; t.v2.clientBuf.data = buf.data(); t.v2.clientBuf.dataSize = buf.size(); }
  }
  void init(const Qnn_Tensor_t& src) {
    t = src; const bool v1 = t.version == QNN_TENSOR_VERSION_1;
    name = v1 ? t.v1.name : t.v2.name; type = v1 ? t.v1.dataType : t.v2.dataType;
    uint32_t rank = v1 ? t.v1.rank : t.v2.rank; const uint32_t* d = v1 ? t.v1.dimensions : t.v2.dimensions; n = 1; for (uint32_t i = 0; i < rank; i++) n *= d[i];
    const Qnn_QuantizeParams_t& q = v1 ? t.v1.quantizeParams : t.v2.quantizeParams;
    if (q.encodingDefinition == QNN_DEFINITION_DEFINED && q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) { scale = q.scaleOffsetEncoding.scale; offset = q.scaleOffsetEncoding.offset; }
    size_t es = (type == QNN_DATATYPE_UFIXED_POINT_16 || type == QNN_DATATYPE_FLOAT_16 || type == QNN_DATATYPE_UINT_16) ? 2 : (type == QNN_DATATYPE_UFIXED_POINT_8 ? 1 : 4);
    buf.assign((size_t)n * es, 0); bind();
  }
  void put(const float* x) {   // QNN fixed point: real = scale * (q + offset)
    if (type == QNN_DATATYPE_UFIXED_POINT_16) { uint16_t* d = (uint16_t*)buf.data(); const float inv = 1.f / scale; for (uint32_t i = 0; i < n; i++) { float q = std::nearbyint(x[i] * inv) - offset; d[i] = (uint16_t)(q < 0 ? 0 : q > 65535 ? 65535 : q); } }
    else if (type == QNN_DATATYPE_FLOAT_16) { __fp16* d = (__fp16*)buf.data(); for (uint32_t i = 0; i < n; i++) d[i] = (__fp16)x[i]; }
    else if (type == QNN_DATATYPE_FLOAT_32) memcpy(buf.data(), x, (size_t)n * 4);
    else if (type == QNN_DATATYPE_INT_32) { int32_t* d = (int32_t*)buf.data(); for (uint32_t i = 0; i < n; i++) d[i] = (int32_t)x[i]; }
  }
  void get(float* x) const {
    if (type == QNN_DATATYPE_UFIXED_POINT_16) { const uint16_t* s = (const uint16_t*)buf.data(); for (uint32_t i = 0; i < n; i++) x[i] = scale * ((float)s[i] + offset); }
    else if (type == QNN_DATATYPE_FLOAT_16) { const __fp16* s = (const __fp16*)buf.data(); for (uint32_t i = 0; i < n; i++) x[i] = (float)s[i]; }
    else memcpy(x, buf.data(), (size_t)n * 4);
  }
};
struct QGraph {
  std::vector<uint8_t> bin; Qnn_ContextHandle_t ctx = nullptr; Qnn_GraphHandle_t g = nullptr; std::vector<QTensor> in, out;
  QTensor* input(const char* nm) { for (auto& t : in) if (t.name == nm) return &t; return nullptr; }
  bool load(const std::string& path) {
    bin = readFile(path); if (bin.empty()) { fprintf(stderr, "cannot read %s\n", path.c_str()); return false; }
    QnnSystemContext_Handle_t sh = nullptr; if (!check(qsi.systemContextCreate(&sh), "systemContextCreate")) return false;
    const QnnSystemContext_BinaryInfo_t* bi = nullptr; Qnn_ContextBinarySize_t bis = 0;
    if (!check(qsi.systemContextGetBinaryInfo(sh, bin.data(), bin.size(), &bi, &bis), "getBinaryInfo")) return false;
    const QnnSystemContext_GraphInfo_t* gi = bi->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1 ? bi->contextBinaryInfoV1.graphs : bi->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2 ? bi->contextBinaryInfoV2.graphs : bi->contextBinaryInfoV3.graphs;
    const char* gname; uint32_t nin, nout; Qnn_Tensor_t *gin, *gout;
    if (gi->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) { auto& v = gi->graphInfoV1; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
    else if (gi->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) { auto& v = gi->graphInfoV2; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
    else { auto& v = gi->graphInfoV3; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
    in.resize(nin); out.resize(nout);
    for (uint32_t i = 0; i < nin; i++) in[i].init(gin[i]);
    for (uint32_t i = 0; i < nout; i++) out[i].init(gout[i]);
    if (!check(qi.contextCreateFromBinary(g_backend, g_device, nullptr, bin.data(), bin.size(), &ctx, nullptr), "contextCreateFromBinary")) return false;
    if (!check(qi.graphRetrieve(ctx, gname, &g), "graphRetrieve")) return false;
    std::vector<uint8_t>().swap(bin);   // the context keeps its own copy
    for (auto& t : in) fprintf(stderr, "  in  %s n=%u type=0x%x scale=%g off=%d\n", t.name.c_str(), t.n, t.type, t.scale, t.offset);
    for (auto& t : out) fprintf(stderr, "  out %s n=%u type=0x%x scale=%g off=%d\n", t.name.c_str(), t.n, t.type, t.scale, t.offset);
    return true;
  }
  bool run() {
    std::vector<Qnn_Tensor_t> ti, to; for (auto& t : in) ti.push_back(t.t); for (auto& t : out) to.push_back(t.t);
    if (const char* o = getenv("SD_ORDER")) if (in.size() == 3) { int a = o[0] - '0', b = o[2] - '0', c = o[4] - '0'; ti = {in[a].t, in[b].t, in[c].t}; }   // debug: input order
    return check(qi.graphExecute(g, ti.data(), ti.size(), to.data(), to.size(), nullptr, nullptr), "graphExecute");
  }
};

// ---- SD 1.5 noise schedule (scaled_linear 0.00085..0.012, 1000 steps), k-diffusion style sigmas ----
static std::vector<double> g_sigmas_train, g_log_sigmas;   // index t = 0..999
static void init_schedule() {
  const double b0 = std::sqrt(0.00085), b1 = std::sqrt(0.012); double acp = 1;
  for (int t = 0; t < 1000; t++) { double b = b0 + (b1 - b0) * t / 999.0; b *= b; acp *= 1 - b; double s = std::sqrt((1 - acp) / acp); g_sigmas_train.push_back(s); g_log_sigmas.push_back(std::log(s)); }
}
static double sigma_to_t(double sigma) {   // interpolated (continuous) timestep for a sigma
  double ls = std::log(sigma); if (ls <= g_log_sigmas[0]) return 0; if (ls >= g_log_sigmas[999]) return 999;
  int lo = 0; while (lo < 998 && g_log_sigmas[lo + 1] < ls) lo++;
  double w = (ls - g_log_sigmas[lo]) / (g_log_sigmas[lo + 1] - g_log_sigmas[lo]); return lo + w;
}
static std::vector<double> make_sigmas(int steps, bool karras) {
  std::vector<double> s;
  if (karras) {   // Karras et al. rho = 7 between the trained sigma range
    const double smin = g_sigmas_train[0], smax = g_sigmas_train[999], rho = 7, a = std::pow(smax, 1 / rho), b = std::pow(smin, 1 / rho);
    for (int i = 0; i < steps; i++) s.push_back(std::pow(a + (double)i / (steps - 1) * (b - a), rho));
  } else {        // "leading"-like even spacing of the trained timesteps, from 999 down
    for (int i = 0; i < steps; i++) { double t = 999.0 - 999.0 * i / (steps - 1); int lo = (int)t; double w = t - lo; s.push_back(std::exp(g_log_sigmas[lo] * (1 - w) + g_log_sigmas[std::min(lo + 1, 999)] * w)); }
  }
  s.push_back(0); return s;
}

int main(int argc, char** argv) {
  if (argc < 4) { fprintf(stderr, "usage: sd15run BACKEND SYSTEM MODEL_DIR\n"); return 2; }
  const std::string dir = argv[3];
  init_schedule();
  // QNN
  void* hb = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL); void* hs = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
  if (!hb || !hs) { fprintf(stderr, "dlopen failed: %s\n", dlerror()); return 1; }
  typedef Qnn_ErrorHandle_t (*GetP)(const QnnInterface_t***, uint32_t*); typedef Qnn_ErrorHandle_t (*GetSP)(const QnnSystemInterface_t***, uint32_t*);
  const QnnInterface_t** provs = nullptr; uint32_t np = 0; if (!check(((GetP)dlsym(hb, "QnnInterface_getProviders"))(&provs, &np), "getProviders")) return 1; qi = provs[0]->QNN_INTERFACE_VER_NAME;
  const QnnSystemInterface_t** sprovs = nullptr; uint32_t nsp = 0; if (!check(((GetSP)dlsym(hs, "QnnSystemInterface_getProviders"))(&sprovs, &nsp), "getSystemProviders")) return 1; qsi = sprovs[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
  if (!check(qi.backendCreate(nullptr, nullptr, &g_backend), "backendCreate")) return 1;
  if (!check(qi.deviceCreate(nullptr, nullptr, &g_device), "deviceCreate")) return 1;
  { // performance vote (same as the app's other NPU runners: max corners unless MEGA_COOL / MEGA_COOL_FILE)
    QnnDevice_Infrastructure_t infra = nullptr; uint32_t pid = 0;
    if (qi.deviceGetInfrastructure && qi.deviceGetInfrastructure(&infra) == QNN_SUCCESS && infra) {
      auto* hi = (QnnHtpDevice_Infrastructure_t*)infra;
      if (hi->perfInfra.createPowerConfigId(0, 0, &pid) == QNN_SUCCESS) {
        const char* cf = getenv("MEGA_COOL_FILE"); bool cool = getenv("MEGA_COOL") != nullptr || (cf && access(cf, F_OK) == 0);
        QnnHtpPerfInfrastructure_PowerConfig_t dc = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT; dc.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
        auto& d = dc.dcvsV3Config; d.contextId = pid; d.setDcvsEnable = 1; d.dcvsEnable = 0; d.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
        d.setSleepLatency = 1; d.sleepLatency = 40; d.setSleepDisable = 1; d.sleepDisable = cool ? 0 : 1;
        d.setBusParams = 1; d.busVoltageCornerMin = d.busVoltageCornerTarget = d.busVoltageCornerMax = cool ? DCVS_VOLTAGE_VCORNER_NOM_PLUS : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        d.setCoreParams = 1; d.coreVoltageCornerMin = d.coreVoltageCornerTarget = d.coreVoltageCornerMax = cool ? DCVS_VOLTAGE_VCORNER_NOM_PLUS : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
        QnnHtpPerfInfrastructure_PowerConfig_t rc = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT; rc.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY; rc.rpcControlLatencyConfig = 100;
        const QnnHtpPerfInfrastructure_PowerConfig_t* cfgs[] = {&dc, &rc, nullptr}; hi->perfInfra.setPowerConfig(pid, cfgs);
      }
    }
  }
  auto t0 = clk::now();
  QGraph unet, vae;
  fprintf(stderr, "unet:\n"); if (!unet.load(dir + "/unet.bin")) return 1;
  fprintf(stderr, "vae_decoder:\n"); if (!vae.load(dir + "/vae_decoder.bin")) return 1;
  QTensor *u_x = unet.input("sample"), *u_t = unet.input("timestep") ? unet.input("timestep") : unet.input("timestamp"), *u_c = unet.input("text_embedding");
  if (!u_x || !u_t || !u_c || unet.out.size() != 1 || vae.in.size() != 1 || vae.out.size() != 1) { fprintf(stderr, "unexpected graph interface\n"); return 1; }
  const int LAT = 4 * 64 * 64, PIX = 512 * 512;
  // CLIP
  std::vector<float> tok, pos; { auto a = readFile(dir + "/token_emb.bin"), b = readFile(dir + "/pos_emb.bin"); if (a.size() != 49408u * 768 * 4 || b.size() != 77u * 768 * 4) { fprintf(stderr, "bad embedding files\n"); return 1; } tok.resize(a.size() / 4); memcpy(tok.data(), a.data(), a.size()); pos.resize(b.size() / 4); memcpy(pos.data(), b.data(), b.size()); }
  std::shared_ptr<MNN::Interpreter> clip(MNN::Interpreter::createFromFile((dir + "/clip_v2.mnn").c_str()));
  if (!clip) { fprintf(stderr, "cannot load clip_v2.mnn\n"); return 1; }
  MNN::ScheduleConfig sc; sc.type = MNN_FORWARD_CPU; sc.numThread = 4; MNN::BackendConfig bc; bc.precision = getenv("SD_CLIP_LOW") ? MNN::BackendConfig::Precision_Low : getenv("SD_CLIP_HIGH") ? MNN::BackendConfig::Precision_High : MNN::BackendConfig::Precision_Normal;   // High: fp32 on the CPU (Normal/Low may run fp16 on ARM) sc.backendConfig = &bc;
  MNN::Session* cs = clip->createSession(sc); MNN::Tensor* cin = clip->getSessionInput(cs, "input_embedding");
  clip->resizeTensor(cin, {1, 77, 768}); clip->resizeSession(cs);
  auto encode = [&](const std::vector<int32_t>& ids, std::vector<float>& outv) -> bool {
    MNN::Tensor host(cin, MNN::Tensor::CAFFE); float* h = host.host<float>();
    for (int p = 0; p < 77; p++) { int id = ids[p]; if (id < 0 || id >= 49408) id = 49407; for (int c = 0; c < 768; c++) h[p * 768 + c] = tok[(size_t)id * 768 + c] + pos[p * 768 + c]; }
    cin->copyFromHostTensor(&host);
    if (clip->runSession(cs) != MNN::NO_ERROR) return false;
    MNN::Tensor* o = clip->getSessionOutput(cs, "last_hidden_state"); MNN::Tensor oh(o, MNN::Tensor::CAFFE); o->copyToHostTensor(&oh);
    outv.assign(oh.host<float>(), oh.host<float>() + 77 * 768);
    if (const char* dp = getenv("SD_DUMP_CLIP")) { FILE* f = fopen(dp, "wb"); if (f) { fwrite(outv.data(), 4, outv.size(), f); fclose(f); } }
    return true;
  };
  fprintf(stderr, "loaded in %.0f ms\n", ms_since(t0));
  printf("ready\n"); fflush(stdout);

  std::string line;
  while (std::getline(std::cin, line)) {
    std::istringstream ss(line); std::string cmd; ss >> cmd;
    if (cmd == "quit") break;
    if (cmd == "rt") {   // debug: VAE round trip of a uint8 RGB 512x512 picture through vae_encoder (mean) and vae_decoder
      static QGraph enc; static bool encok = false; if (!encok) { fprintf(stderr, "vae_encoder:\n"); encok = enc.load(dir + "/vae_encoder.bin"); }
      std::string fi, fo; ss >> fi >> fo; auto b = readFile(fi); if (!encok || b.size() != 3u * PIX) { printf("error rt\n"); fflush(stdout); continue; }
      std::vector<float> im(3 * PIX), mean(LAT), img(3 * PIX);
      for (int p = 0; p < PIX; p++) for (int c = 0; c < 3; c++) im[c * PIX + p] = b[p * 3 + c] / 127.5f - 1;
      enc.in[0].put(im.data()); enc.run(); enc.out[0].get(mean.data());
      double m = 0, s2 = 0; for (float v : mean) { m += v; s2 += v * v; } m /= LAT; fprintf(stderr, "latent mean %.3f std %.3f\n", m, std::sqrt(s2 / LAT - m * m));
      vae.in[0].put(mean.data()); vae.run(); vae.out[0].get(img.data());   // the encoder mean is unscaled: feed it straight back
      std::vector<uint8_t> rgb(3 * PIX); for (int p = 0; p < PIX; p++) for (int c = 0; c < 3; c++) { float v = (img[c * PIX + p] + 1) * 127.5f; rgb[p * 3 + c] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v + .5f); }
      FILE* f = fopen(fo.c_str(), "wb"); fwrite(rgb.data(), 1, rgb.size(), f); fclose(f); printf("done rt\n"); fflush(stdout); continue;
    }
    if (cmd == "dn") {   // debug: one UNet denoise of a real picture's latent: dn IN.rgb POS.i32 SIGMA OUT.rgb
      static QGraph enc; static bool encok = false; if (!encok) encok = enc.load(dir + "/vae_encoder.bin");
      std::string fi, fp, fo; double sg = 1; ss >> fi >> fp >> sg >> fo; auto b = readFile(fi); auto ib = readFile(fp);
      std::vector<int32_t> ids(77, 49407); if (ib.size() == 308) memcpy(ids.data(), ib.data(), 308);
      std::vector<float> im(3 * PIX), z(LAT), ec, eps(LAT), xin(LAT), img(3 * PIX);
      for (int p = 0; p < PIX; p++) for (int c = 0; c < 3; c++) im[c * PIX + p] = b[p * 3 + c] / 127.5f - 1;
      enc.in[0].put(im.data()); enc.run(); enc.out[0].get(z.data()); for (auto& v : z) v *= 0.18215f;
      encode(ids, ec); std::mt19937 rng(1); std::normal_distribution<float> nd(0.f, 1.f);
      std::vector<float> x(LAT); for (int k = 0; k < LAT; k++) x[k] = z[k] + (float)sg * nd(rng);
      const double ci = 1.0 / std::sqrt(sg * sg + 1); for (int k = 0; k < LAT; k++) xin[k] = (float)(x[k] * ci);
      const bool nhwc = getenv("SD_NHWC") != nullptr, noscale = getenv("SD_NOSCALE") != nullptr;
      if (noscale) for (int k = 0; k < LAT; k++) xin[k] = x[k];
      std::vector<float> tmp(LAT);
      if (nhwc || getenv("SD_INHWC")) { for (int c = 0; c < 4; c++) for (int p = 0; p < 4096; p++) tmp[p * 4 + c] = xin[c * 4096 + p]; xin = tmp; }
      if (getenv("SD_TRHW")) { for (int c = 0; c < 4; c++) for (int y = 0; y < 64; y++) for (int x2 = 0; x2 < 64; x2++) tmp[c * 4096 + x2 * 64 + y] = xin[c * 4096 + y * 64 + x2]; xin = tmp; }
      float t = (float)(int)sigma_to_t(sg); if (getenv("SD_T")) t = (float)atof(getenv("SD_T")); u_x->put(xin.data()); u_t->put(&t);
      if (getenv("SD_TFLOAT")) memcpy(u_t->buf.data(), &t, 4);   // debug: the raw float bits instead of an int32
      if (getenv("SD_TT")) { std::vector<float> tt(77 * 768); for (int a = 0; a < 77; a++) for (int c = 0; c < 768; c++) tt[c * 77 + a] = ec[a * 768 + c]; ec = tt; }
      if (getenv("SD_TZERO")) std::fill(ec.begin(), ec.end(), 0.f);
      u_c->put(ec.data()); unet.run(); unet.out[0].get(eps.data());
      if (nhwc || getenv("SD_ONHWC")) { for (int c = 0; c < 4; c++) for (int p = 0; p < 4096; p++) tmp[c * 4096 + p] = eps[p * 4 + c]; eps = tmp; }
      if (getenv("SD_TRHW")) { for (int c = 0; c < 4; c++) for (int y = 0; y < 64; y++) for (int x2 = 0; x2 < 64; x2++) tmp[c * 4096 + x2 * 64 + y] = eps[c * 4096 + y * 64 + x2]; eps = tmp; }
      double e2 = 0, d2 = 0, n2 = 0; for (int k = 0; k < LAT; k++) { double tn = (x[k] - z[k]) / sg; e2 += eps[k] * eps[k]; d2 += (eps[k] - tn) * (eps[k] - tn); n2 += tn * tn; }
      fprintf(stderr, "sigma %.3f t %.0f |eps| %.3f |true noise| %.3f |eps-true| %.3f\n", sg, t, std::sqrt(e2 / LAT), std::sqrt(n2 / LAT), std::sqrt(d2 / LAT));
      { const double a = 1 / std::sqrt(1 + sg * sg), s_ = sg / std::sqrt(1 + sg * sg); double dv = 0, dx = 0, v2 = 0, cs = 0, ce = 0, cn = 0;
        for (int k = 0; k < LAT; k++) { double tn = (x[k] - z[k]) / sg, v = a * tn - s_ * z[k]; dv += (eps[k] - v) * (eps[k] - v); v2 += v * v; dx += (eps[k] - z[k]) * (eps[k] - z[k]); cs += eps[k] * tn; ce += eps[k] * eps[k]; cn += tn * tn; }
        fprintf(stderr, "   |v_true| %.3f |out-v| %.3f |out-x0| %.3f corr(out,noise) %.3f\n", std::sqrt(v2 / LAT), std::sqrt(dv / LAT), std::sqrt(dx / LAT), cs / std::sqrt(ce * cn)); }
      if (getenv("SD_DUMP_EPS")) { FILE* f = fopen("eps_dump.f32", "wb"); std::vector<float> tn(LAT); for (int k = 0; k < LAT; k++) tn[k] = (float)((x[k] - z[k]) / sg); fwrite(eps.data(), 4, LAT, f); fwrite(tn.data(), 4, LAT, f); fwrite(xin.data(), 4, LAT, f); fclose(f); }
      for (int k = 0; k < LAT; k++) x[k] = (float)((x[k] - sg * eps[k]) / 0.18215);
      vae.in[0].put(x.data()); vae.run(); vae.out[0].get(img.data());
      std::vector<uint8_t> rgb(3 * PIX); for (int p = 0; p < PIX; p++) for (int c = 0; c < 3; c++) { float v = (img[c * PIX + p] + 1) * 127.5f; rgb[p * 3 + c] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v + .5f); }
      FILE* f = fopen(fo.c_str(), "wb"); fwrite(rgb.data(), 1, rgb.size(), f); fclose(f); printf("done dn\n"); fflush(stdout); continue;
    }
    if (cmd != "gen") continue;
    std::string fpos, fneg, fout, sampler; int steps = 20; float cfg = 7; unsigned seed = 0;
    ss >> fpos >> fneg >> steps >> cfg >> seed >> sampler >> fout;
    auto tg = clk::now();
    auto rid = [](const std::string& f) { auto b = readFile(f); std::vector<int32_t> v(77, 49407); if (b.size() == 77 * 4) memcpy(v.data(), b.data(), b.size()); return v; };
    std::vector<float> ec, eu;
    if (!encode(rid(fpos), ec) || (cfg != 1.f && !encode(rid(fneg), eu))) { printf("error clip\n"); fflush(stdout); continue; }
    fprintf(stderr, "clip %.0f ms\n", ms_since(tg));
    const bool karras = sampler.find("karras") != std::string::npos, ancestral = sampler == "euler_a";
    std::vector<double> sig = make_sigmas(std::max(1, steps), karras || ancestral ? karras : false);
    std::mt19937 rng(seed); std::normal_distribution<float> nd(0.f, 1.f);
    std::vector<float> x(LAT), eps(LAT), epsu(LAT), den(LAT), old(LAT), xin(LAT);
    for (auto& v : x) v = nd(rng) * (float)sig[0];
    bool have_old = false, ok = true;
    for (int i = 0; i < steps && ok; i++) {
      auto ts = clk::now();
      const double s = sig[i], sn = sig[i + 1], cin_s = 1.0 / std::sqrt(s * s + 1);
      for (int k = 0; k < LAT; k++) xin[k] = (float)(x[k] * cin_s);
      const float t = (float)(int)sigma_to_t(s);   // the graph takes an integer timestep
      u_x->put(xin.data()); u_t->put(&t);
      if (cfg != 1.f) { u_c->put(eu.data()); if (!unet.run()) { ok = false; break; } unet.out[0].get(epsu.data()); }
      u_c->put(ec.data()); if (!unet.run()) { ok = false; break; } unet.out[0].get(eps.data());
      if (cfg != 1.f) for (int k = 0; k < LAT; k++) eps[k] = epsu[k] + cfg * (eps[k] - epsu[k]);
      if (const char* em = getenv("SD_EPSMUL")) { const float m = (float)atof(em); for (auto& v : eps) v *= m; }   // debug
      for (int k = 0; k < LAT; k++) den[k] = (float)(x[k] - s * eps[k]);   // x0 estimate
      if (ancestral) {   // Euler ancestral (eta 1)
        double su = sn == 0 ? 0 : std::min(sn, std::sqrt(sn * sn * (s * s - sn * sn) / (s * s))), sd = std::sqrt(std::max(0.0, sn * sn - su * su));
        for (int k = 0; k < LAT; k++) { double d = (x[k] - den[k]) / s; x[k] = (float)(x[k] + d * (sd - s) + (su > 0 ? nd(rng) * su : 0)); }
      } else if (sn == 0) { x = den;
      } else {           // DPM-Solver++(2M)
        const double tcur = -std::log(s), tnext = -std::log(sn), h = tnext - tcur, ratio = sn / s, em = -std::expm1(-h);
        if (!have_old) for (int k = 0; k < LAT; k++) x[k] = (float)(ratio * x[k] + em * den[k]);
        else { const double hl = tcur - (-std::log(sig[i - 1])), r = hl / h, a = 1 + 1 / (2 * r), b = 1 / (2 * r);
               for (int k = 0; k < LAT; k++) x[k] = (float)(ratio * x[k] + em * (a * den[k] - b * old[k])); }
      }
      old = den; have_old = true;
      printf("step %d %d %.0f\n", i + 1, steps, ms_since(ts)); fflush(stdout);
    }
    if (!ok) { printf("error unet\n"); fflush(stdout); continue; }
    for (auto& v : x) v /= 0.18215f;
    auto tv = clk::now(); vae.in[0].put(x.data()); if (!vae.run()) { printf("error vae\n"); fflush(stdout); continue; }
    std::vector<float> img(3 * PIX); vae.out[0].get(img.data());
    std::vector<uint8_t> rgb(3 * PIX);
    for (int p = 0; p < PIX; p++) for (int c = 0; c < 3; c++) { float v = (img[c * PIX + p] + 1) * 127.5f; rgb[p * 3 + c] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v + .5f); }
    FILE* f = fopen(fout.c_str(), "wb"); if (f) { fwrite(rgb.data(), 1, rgb.size(), f); fclose(f); }
    fprintf(stderr, "vae %.0f ms\n", ms_since(tv));
    printf("done %.0f\n", ms_since(tg)); fflush(stdout);
  }
  return 0;
}
