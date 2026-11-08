// terun: persistent runner for a CHAIN of single-input/single-output fp16 QNN HTP context binaries (Qwen3 text encoder slices). Files are fp32;
// "run IN OUT" feeds IN to the first context, each output is the next input (no copies) -> prints "done <ms>"; "quit".
// usage: terun BACKEND.so SYSTEM.so CTX0.bin CTX1.bin ...
#include <dlfcn.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"

static QNN_INTERFACE_VER_TYPE qi;
static QNN_SYSTEM_INTERFACE_VER_TYPE qsi;

static bool check(Qnn_ErrorHandle_t e, const char* what) {
  if (e != QNN_SUCCESS) { fprintf(stderr, "ERROR %s: 0x%lx\n", what, (unsigned long)e); return false; }
  return true;
}

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

static void setBuf(Qnn_Tensor_t& t, void* data, uint32_t bytes) {
  if (t.version == QNN_TENSOR_VERSION_1) {
    t.v1.memType = QNN_TENSORMEMTYPE_RAW; t.v1.clientBuf.data = data; t.v1.clientBuf.dataSize = bytes;
  } else {
    t.v2.memType = QNN_TENSORMEMTYPE_RAW; t.v2.clientBuf.data = data; t.v2.clientBuf.dataSize = bytes;
  }
}
static const char* tName(const Qnn_Tensor_t& t) { return t.version == QNN_TENSOR_VERSION_1 ? t.v1.name : t.v2.name; }
static uint32_t tElems(const Qnn_Tensor_t& t) {
  uint32_t rank = t.version == QNN_TENSOR_VERSION_1 ? t.v1.rank : t.v2.rank;
  const uint32_t* d = t.version == QNN_TENSOR_VERSION_1 ? t.v1.dimensions : t.v2.dimensions;
  uint32_t n = 1;
  for (uint32_t i = 0; i < rank; i++) n *= d[i];
  return n;
}


static uint32_t tType(const Qnn_Tensor_t& t) { return t.version == QNN_TENSOR_VERSION_1 ? t.v1.dataType : t.v2.dataType; }

int main(int argc, char** argv) {
  if (argc < 4) { fprintf(stderr, "usage: terun BACKEND SYSTEM CTX.bin...\n"); return 2; }
  void* hb = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  void* hs = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
  if (!hb || !hs) { fprintf(stderr, "dlopen failed: %s\n", dlerror()); return 1; }
  typedef Qnn_ErrorHandle_t (*GetP)(const QnnInterface_t***, uint32_t*);
  typedef Qnn_ErrorHandle_t (*GetSP)(const QnnSystemInterface_t***, uint32_t*);
  const QnnInterface_t** provs = nullptr; uint32_t np = 0;
  if (!check(((GetP)dlsym(hb, "QnnInterface_getProviders"))(&provs, &np), "getProviders")) return 1;
  qi = provs[0]->QNN_INTERFACE_VER_NAME;
  const QnnSystemInterface_t** sprovs = nullptr; uint32_t nsp = 0;
  if (!check(((GetSP)dlsym(hs, "QnnSystemInterface_getProviders"))(&sprovs, &nsp), "getSystemProviders")) return 1;
  qsi = sprovs[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
  Qnn_BackendHandle_t backend = nullptr;
  if (!check(qi.backendCreate(nullptr, nullptr, &backend), "backendCreate")) return 1;
  Qnn_DeviceHandle_t device = nullptr;
  if (!check(qi.deviceCreate(nullptr, nullptr, &device), "deviceCreate")) return 1;
    // burst power profile (what qnn-net-run --perf_profile burst does)
  QnnDevice_Infrastructure_t infra = nullptr;
  if (qi.deviceGetInfrastructure && qi.deviceGetInfrastructure(&infra) == QNN_SUCCESS && infra) {
    auto* hi = (QnnHtpDevice_Infrastructure_t*)infra;
    uint32_t pid = 0;
    if (hi->perfInfra.createPowerConfigId(0, 0, &pid) == QNN_SUCCESS) {
      QnnHtpPerfInfrastructure_PowerConfig_t dc = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT;
      dc.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
      auto& d = dc.dcvsV3Config;
      d.contextId = pid; d.setDcvsEnable = 1; d.dcvsEnable = 0;
      d.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
      const bool cool = getenv("MEGA_COOL") != nullptr;   // the app's cool mode: the same lower corners as megarun (the DSP runs at the highest vote of all its clients)
      d.setSleepLatency = 1; d.sleepLatency = 40; d.setSleepDisable = 1; d.sleepDisable = cool ? 0 : 1;
      d.setBusParams = 1; d.busVoltageCornerMin = d.busVoltageCornerTarget = d.busVoltageCornerMax = cool ? DCVS_VOLTAGE_VCORNER_NOM_PLUS : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      d.setCoreParams = 1; d.coreVoltageCornerMin = d.coreVoltageCornerTarget = d.coreVoltageCornerMax = cool ? DCVS_VOLTAGE_VCORNER_NOM_PLUS : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      QnnHtpPerfInfrastructure_PowerConfig_t rc = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_INIT;
      rc.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
      rc.rpcControlLatencyConfig = 100;
      const QnnHtpPerfInfrastructure_PowerConfig_t* cfgs[] = {&dc, &rc, nullptr};
      hi->perfInfra.setPowerConfig(pid, cfgs);
    }
  }



  int N = argc - 3;
  std::vector<std::vector<uint8_t>> bufs;  // bufs[i] = input of context i, bufs[N] = final output (fp16)
  std::vector<Qnn_GraphHandle_t> graphs(N);
  std::vector<Qnn_Tensor_t> tins(N), touts(N);
  QnnSystemContext_Handle_t sh = nullptr;
  if (!check(qsi.systemContextCreate(&sh), "systemContextCreate")) return 1;
  for (int c = 0; c < N; c++) {
    std::vector<uint8_t>* bin = new std::vector<uint8_t>(readFile(argv[3 + c]));
    if (bin->empty()) { fprintf(stderr, "cannot read %s\n", argv[3 + c]); return 1; }
    const QnnSystemContext_BinaryInfo_t* bi = nullptr; Qnn_ContextBinarySize_t bis = 0;
    if (!check(qsi.systemContextGetBinaryInfo(sh, bin->data(), bin->size(), &bi, &bis), "getBinaryInfo")) return 1;
    const QnnSystemContext_GraphInfo_t* g = nullptr;
    if (bi->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) g = bi->contextBinaryInfoV1.graphs;
    else if (bi->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) g = bi->contextBinaryInfoV2.graphs;
    else g = bi->contextBinaryInfoV3.graphs;
    const char* gname; uint32_t nin, nout; Qnn_Tensor_t *gin, *gout;
    if (g->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) { auto& v = g->graphInfoV1; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
    else if (g->version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) { auto& v = g->graphInfoV2; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
    else { auto& v = g->graphInfoV3; gname = v.graphName; nin = v.numGraphInputs; gin = v.graphInputs; nout = v.numGraphOutputs; gout = v.graphOutputs; }
    Qnn_ContextHandle_t ctx = nullptr;
    if (!check(qi.contextCreateFromBinary(backend, device, nullptr, bin->data(), bin->size(), &ctx, nullptr), "contextCreateFromBinary")) return 1;
    if (!check(qi.graphRetrieve(ctx, gname, &graphs[c]), "graphRetrieve")) return 1;
    delete bin;  // the HTP backend copied it; keeping 220 MB per slice resident pushed the phone into swap
    if (nin != 1 || nout != 1) { fprintf(stderr, "expected 1 input and 1 output\n"); return 1; }
    tins[c] = gin[0]; touts[c] = gout[0];
    if (tType(tins[c]) != QNN_DATATYPE_FLOAT_16 || tType(touts[c]) != QNN_DATATYPE_FLOAT_16) { fprintf(stderr, "terun needs fp16 I/O\n"); return 1; }
    if (c == 0) bufs.emplace_back(tElems(tins[0]) * 2);
    bufs.emplace_back(tElems(touts[c]) * 2);
  }
  for (int c = 0; c < N; c++) { setBuf(tins[c], bufs[c].data(), bufs[c].size()); setBuf(touts[c], bufs[c + 1].data(), bufs[c + 1].size()); }
  uint32_t ni = tElems(tins[0]), no = tElems(touts[N - 1]);
  fprintf(stderr, "terun: %d contexts, in %u elems, out %u elems\n", N, ni, no);
  printf("ready %u\n", ni); fflush(stdout);   // input elements: animad derives the sequence length (ni / 1024) the contexts were built for
  std::string line;
  while (std::getline(std::cin, line)) {
    std::istringstream ss(line); std::string cmd, fi, fo; ss >> cmd >> fi >> fo;
    if (cmd == "quit") break;
    if (cmd != "run") continue;
    auto b = readFile(fi);
    if (b.size() != ni * 4) { fprintf(stderr, "bad input size %s\n", fi.c_str()); printf("error\n"); fflush(stdout); continue; }
    { const float* s = (const float*)b.data(); __fp16* d = (__fp16*)bufs[0].data(); for (uint32_t i = 0; i < ni; i++) d[i] = (__fp16)s[i]; }
    auto s0 = std::chrono::steady_clock::now();
    bool ok = true;
    for (int c = 0; c < N && ok; c++) ok = qi.graphExecute(graphs[c], &tins[c], 1, &touts[c], 1, nullptr, nullptr) == QNN_SUCCESS;
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - s0).count();
    if (!ok) { fprintf(stderr, "execute failed\n"); printf("error\n"); fflush(stdout); continue; }
    std::vector<float> o(no);
    { const __fp16* s = (const __fp16*)bufs[N].data(); for (uint32_t i = 0; i < no; i++) o[i] = (float)s[i]; }
    FILE* f = fopen(fo.c_str(), "wb"); fwrite(o.data(), 4, no, f); fclose(f);
    printf("done %.0f\n", ms); fflush(stdout);
  }
  return 0;
}
