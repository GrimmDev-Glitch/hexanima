#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/ioctl.h>
#include "rpcmem.h"
#include "remote.h"
#include "mega.h"
#include "blk_meta.h"
#include "blk_wlayout.h"
static size_t fsz(const char *p) { FILE *f = fopen(p, "rb"); if (!f) return 0; fseek(f, 0, SEEK_END); size_t n = ftell(f); fclose(f); return n; }
static int rd(const char *dir, const char *name, void *dst, size_t want) {
  char p[512]; snprintf(p, sizeof p, "%s/%s", dir, name); FILE *f = fopen(p, "rb"); if (!f) { printf("missing %s\n", p); return -1; }
  size_t n = fread(dst, 1, want, f); fclose(f); if (n != want) { printf("%s: got %zu want %zu\n", name, n, want); return -1; } return 0;
}
int main(int argc, char **argv) {
  const char *dir = argv[1]; int reps = argc > 2 ? atoi(argv[2]) : 1;
  const int ntok = 1536, nctx = 512; int meta[M_NMETA]; memset(meta, 0, sizeof meta);
  size_t sz[M_NMETA] = {0}; const char *nm[M_NMETA] = {0};
  struct { int id; const char *name; size_t bytes; } ent[] = {
    {M_X, "x32.bin", 12582912}, {M_CTX, "ctx16.bin", 1048576}, {M_MOD, "mod.bin", 73728}, {M_COS, "cos.bin", 393216}, {M_SIN, "sin.bin", 393216},
    {M_H, 0, 6291456}, {M_Q, 0, 6291456}, {M_K, 0, 6291456}, {M_V, 0, 6291456}, {M_O, 0, 6291456}, {M_T, 0, 6291456}, {M_MID, 0, 25165824}, {M_CK, 0, 2097152}, {M_CV, 0, 2097152}};
  struct { int id; const char *name; unsigned off; size_t bytes; } went[] = {
    {M_WSQ, "w_sq.bin", W_SQ, 8388608}, {M_WSK, "w_sk.bin", W_SK, 8388608}, {M_WSV, "w_sv.bin", W_SV, 8388608}, {M_WSO, "w_so.bin", W_SO, 8388608},
    {M_WCQ, "w_cq.bin", W_CQ, 8388608}, {M_WCK, "w_ck.bin", W_CK, 4194304}, {M_WCV, "w_cv.bin", W_CV, 4194304}, {M_WCO, "w_co.bin", W_CO, 8388608},
    {M_WM1, "w_m1.bin", W_M1, 33554432}, {M_WM2, "w_m2.bin", W_M2, 33554432}, {M_GAIN, "gain.bin", W_GAIN, 2048}};
  size_t off = 0; int ne = sizeof ent / sizeof ent[0];
  for (int i = 0; i < ne; i++) { meta[ent[i].id] = (int)off; off += (ent[i].bytes + 4095) & ~(size_t)4095; }
  meta[M_NTOK] = ntok; meta[M_NCTX] = nctx; meta[M_STOP] = argc > 3 ? atoi(argv[3]) : 0; meta[M_NW] = argc > 4 ? atoi(argv[4]) : 0; setvbuf(stdout, 0, _IONBF, 0);
  rpcmem_init();
  uint8_t *wts = (uint8_t *)rpcmem_alloc(RPCMEM_HEAP_ID_SYSTEM, RPCMEM_DEFAULT_FLAGS, W_TOTAL); memset(wts, 0, W_TOTAL);
  for (unsigned k = 0; k < sizeof went / sizeof went[0]; k++) { meta[went[k].id] = went[k].off; if (rd(dir, went[k].name, wts + went[k].off, went[k].bytes)) return 1; }
  uint8_t *blob = (uint8_t *)rpcmem_alloc(RPCMEM_HEAP_ID_SYSTEM, RPCMEM_DEFAULT_FLAGS, off);
  if (!blob) { printf("rpcmem_alloc %zu failed\n", off); return 1; }
  printf("alloc ok %zu\n", off);
  if (getenv("REG")) { remote_register_buf_attr(blob, off, rpcmem_to_fd(blob), atoi(getenv("REG"))); printf("registered attr %s\n", getenv("REG")); } memset(blob, 0, off); printf("memset ok\n");
  for (int i = 0; i < ne; i++) if (ent[i].name && rd(dir, ent[i].name, blob + meta[ent[i].id], ent[i].bytes)) return 1;
  printf("files read\n");
  uint8_t *x0 = (uint8_t *)malloc(12582912); memcpy(x0, blob + meta[M_X], 12582912);
  struct remote_rpc_control_unsigned_module d = {CDSP_DOMAIN_ID, 1};
  remote_session_control(DSPRPC_CONTROL_UNSIGNED_MODULE, &d, sizeof d);
  remote_handle64 h = 0; int r = mega_open(mega_URI CDSP_DOMAIN, &h); if (r) { printf("open 0x%x\n", r); return 1; }
  { int mr = fastrpc_mmap(CDSP_DOMAIN_ID, rpcmem_to_fd(wts), wts, 0, W_TOTAL, FASTRPC_MAP_FD); if (mr) { printf("fastrpc_mmap rc=%d\n", mr); return 1; } }
  { int mr = fastrpc_mmap(CDSP_DOMAIN_ID, rpcmem_to_fd(blob), blob, 0, off, FASTRPC_MAP_FD); if (mr) { printf("blob mmap rc=%d\n", mr); return 1; } }
  meta[M_FIRST] = 1; meta[M_REL] = 1; meta[M_NW] = 3;
  typedef struct S_ { unsigned long long f; } S_;
  for (int rep = 0; rep < reps; rep++) {
    memcpy(blob + meta[M_X], x0, 12582912);
    { S_ sy = {(1ull << 2) | 3}; ioctl(rpcmem_to_fd(blob), _IOW('b', 0, S_), &sy); }
    int times[32]; int rc; struct timespec a, b; clock_gettime(CLOCK_MONOTONIC, &a);
    r = mega_block(h, meta, M_NMETA, rpcmem_to_fd(wts), rpcmem_to_fd(blob), (const uint8_t *)(blob + meta[M_MOD]), 73728, times, 32, &rc);
    clock_gettime(CLOCK_MONOTONIC, &b);
    { S_ sy = {3}; ioctl(rpcmem_to_fd(blob), _IOW('b', 0, S_), &sy); }
    printf("rep %d r=0x%x rc=%d total %.2f ms | stages(us):", rep, r, rc, (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6);
    for (int i = 0; i < 24; i++) printf(" %d", times[i]); printf("\n");
  }
  char p[600]; snprintf(p, sizeof p, "%s/out32.bin", dir); FILE *f = fopen(p, "wb"); fwrite(blob + meta[M_X], 1, 12582912, f); fclose(f);
  mega_close(h); return 0;
}
