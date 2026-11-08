// how many / how large can fastrpc_mmap'd rpcmem buffers be in one DSP process? usage: maprobe SIZE_MB [openskel=1]
#include <cstdio>
#include <cstdlib>
#include <vector>
extern "C" {
#include "rpcmem.h"
#include "remote.h"
#include "mega.h"
}
#include <chrono>
static double now() { return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
int main(int argc, char** argv) {
  size_t mb = atoi(argv[1]); rpcmem_init();
  struct remote_rpc_control_unsigned_module ud = {CDSP_DOMAIN_ID, 1}; remote_session_control(DSPRPC_CONTROL_UNSIGNED_MODULE, &ud, sizeof ud);
  remote_handle64 h = 0; if (mega_open(mega_URI CDSP_DOMAIN, &h)) { printf("open failed\n"); return 1; }
  { void* p = rpcmem_alloc(RPCMEM_HEAP_ID_SYSTEM, RPCMEM_DEFAULT_FLAGS, mb << 20); for (size_t i = 0; i < (mb << 20); i += 4096) ((char*)p)[i] = 1;
    for (int r = 0; r < 3; r++) { double t0 = now(); int rc = fastrpc_mmap(CDSP_DOMAIN_ID, rpcmem_to_fd(p), p, 0, mb << 20, FASTRPC_MAP_FD); double t1 = now(); fastrpc_munmap(CDSP_DOMAIN_ID, rpcmem_to_fd(p), p, 0); double t2 = now();
      printf("map %zu MB rc=%d: %.1f ms, unmap %.1f ms\n", mb, rc, (t1 - t0) * 1e3, (t2 - t1) * 1e3); } }
  std::vector<void*> v; size_t tot = 0;
  for (int i = 0; i < 64; i++) {
    void* p = rpcmem_alloc(RPCMEM_HEAP_ID_SYSTEM, RPCMEM_DEFAULT_FLAGS, mb << 20); if (!p) { printf("alloc failed at %d (total %zu MB)\n", i, tot); break; }
    int rc = fastrpc_mmap(CDSP_DOMAIN_ID, rpcmem_to_fd(p), p, 0, mb << 20, FASTRPC_MAP_FD);
    if (rc) { printf("mmap failed at buffer %d rc=%d (total mapped %zu MB)\n", i, rc, tot); break; }
    tot += mb; v.push_back(p); if (i == 63) printf("64 buffers ok (%zu MB)\n", tot);
  }
  printf("mapped %zu buffers of %zu MB = %zu MB\n", v.size(), mb, tot); return 0;
}
