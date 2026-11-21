/* Single-block V73 runner for Krea K2B1 packs. */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include "rpcmem.h"
#include "remote.h"
#include "mega.h"
#include "blk_meta.h"

static int read_file(const char *dir, const char *name, void *dst, size_t want) {
  char path[512]; snprintf(path, sizeof path, "%s/%s", dir, name);
  FILE *f = fopen(path, "rb"); if (!f) { fprintf(stderr, "open %s: %s\n", path, strerror(errno)); return -1; }
  size_t got = fread(dst, 1, want, f); int extra = fgetc(f); fclose(f);
  if (got != want || extra != EOF) { fprintf(stderr, "%s: expected %zu bytes, got %zu%s\n", name, want, got, extra == EOF ? "" : " plus trailing bytes"); return -1; }
  return 0;
}
static int write_file(const char *dir, const char *name, const void *src, size_t bytes) {
  char path[512]; snprintf(path, sizeof path, "%s/%s", dir, name);
  FILE *f = fopen(path, "wb"); if (!f) { fprintf(stderr, "write %s: %s\n", path, strerror(errno)); return -1; }
  int ok = fwrite(src, 1, bytes, f) == bytes && fclose(f) == 0; return ok ? 0 : -1;
}
static size_t up4096(size_t x) { return (x + 4095) & ~(size_t)4095; }
typedef struct { unsigned long long f; } sync_t;
static void sync_fd(int fd) { sync_t s = {(1ull << 2) | 3}; ioctl(fd, _IOW('b', 0, sync_t), &s); }

int main(int argc, char **argv) {
  if (argc < 4) { fprintf(stderr, "usage: krea_blk_smoke DIR NTOK NVALID [STOP] [NW] [MODE=71|72] [chain] [NCHAIN=28]\n"); return 2; }
  const char *dir = argv[1]; int ntok = atoi(argv[2]), nvalid = atoi(argv[3]);
  int stop = argc > 4 ? atoi(argv[4]) : 0, nw = argc > 5 ? atoi(argv[5]) : 3;
  int mode = argc > 6 ? atoi(argv[6]) : KREA_MODE;
  int chain = argc > 7 && !strcmp(argv[7], "chain");
  int chain_count = argc > 8 ? atoi(argv[8]) : 28;
  if (chain_count < 1 || chain_count > 28) { fprintf(stderr, "NCHAIN must be in 1..28\n"); return 2; }
  if (mode != KREA_MODE && mode != KREA_Q8_MODE) { fprintf(stderr, "MODE must be 71 (q4a) or 72 (q8)\n"); return 2; }
  if (ntok < 32 || ntok % 32 || nvalid < 1 || nvalid > ntok) { fprintf(stderr, "NTOK must be a positive multiple of 32 and NVALID in 1..NTOK\n"); return 2; }
  const int ns = ntok / 32, D = 6144, KV = 1536, FF = 16384;
  struct { int id; size_t bytes; const char *name; } ent[] = {
    /* M_X uses [slab][feature tile][token-in-slab][feature-in-tile] float layout. */
    {M_X, (size_t)ntok * D * 4, "x32.bin"},
    {M_COS, (size_t)ntok * 64 * 4, "cos.bin"}, {M_SIN, (size_t)ntok * 64 * 4, "sin.bin"},
    {M_H, (size_t)ntok * D * 2, 0}, {M_Q, (size_t)ntok * D * 2, 0},
    {M_K, (size_t)ntok * KV * 2, 0}, {M_V, (size_t)ntok * KV * 2, 0},
    {M_O, (size_t)ntok * D * 2, 0}, {M_T, (size_t)ntok * D * 2, 0},
    {M_MID, (size_t)ntok * 2 * FF * 2, 0}, {M_ROWS, (size_t)6 * D * 4, 0}, {M_CS, (size_t)ntok * 4, "mask.bin"},
    {M_LQ, (size_t)ns * 12 * 2048, 0}, {M_LO, (size_t)ns * 4 * 2048, 0},
    {M_LU, (size_t)ns * 4 * 2048, 0}, {M_LD, (size_t)ns * 4 * 2048, 0},
  };
  int meta[M_NMETA]; memset(meta, 0, sizeof meta); setvbuf(stdout, NULL, _IONBF, 0);
  size_t blobbytes = 0; const int ne = (int)(sizeof ent / sizeof ent[0]);
  for (int i = 0; i < ne; i++) { meta[ent[i].id] = (int)blobbytes; blobbytes += up4096(ent[i].bytes); }
  uint8_t *wts = NULL; char wp[512]; snprintf(wp, sizeof wp, "%s/blk00.bin", dir);
  FILE *wf = fopen(wp, "rb"); if (!wf) { fprintf(stderr, "open %s: %s\n", wp, strerror(errno)); return 1; }
  if (fseek(wf, 0, SEEK_END)) { fclose(wf); fprintf(stderr, "cannot seek K2B1 pack\n"); return 1; }
  long wtlong = ftell(wf); if (wtlong < 4096) { fclose(wf); fprintf(stderr, "invalid K2B1 pack\n"); return 1; }
  rewind(wf); size_t wtbytes = (size_t)wtlong;
  rpcmem_init();
  wts = (uint8_t *)rpcmem_alloc(RPCMEM_HEAP_ID_SYSTEM, RPCMEM_DEFAULT_FLAGS, wtbytes);
  uint8_t *blob = (uint8_t *)rpcmem_alloc(RPCMEM_HEAP_ID_SYSTEM, RPCMEM_DEFAULT_FLAGS, blobbytes);
  uint8_t *mod = (uint8_t *)malloc((size_t)6 * D * 4);
  if (!wts || !blob || !mod) { fprintf(stderr, "allocation failed: weights=%zu blob=%zu\n", wtbytes, blobbytes); return 1; }
  rewind(wf); if (fread(wts, 1, wtbytes, wf) != wtbytes) { fclose(wf); fprintf(stderr, "short K2B1 read\n"); return 1; } fclose(wf);
  memset(blob, 0, blobbytes);
  if (read_file(dir, "x32.bin", blob + meta[M_X], ent[0].bytes) || read_file(dir, "cos.bin", blob + meta[M_COS], ent[1].bytes) || read_file(dir, "sin.bin", blob + meta[M_SIN], ent[2].bytes) || read_file(dir, "mod.bin", mod, (size_t)6 * D * 4)) return 1;
  int mask_enabled = 0; { char mp[512]; snprintf(mp, sizeof mp, "%s/mask.bin", dir); FILE *mf = fopen(mp, "rb"); if (mf) { fclose(mf); if (read_file(dir, "mask.bin", blob + meta[M_CS], (size_t)ntok * 4)) return 1; mask_enabled = 1; } }
  meta[M_MODE] = mode; meta[M_NCB] = 192; meta[M_NTOK] = ntok; meta[M_NVALID] = nvalid;
  meta[M_STOP] = stop; meta[M_NW] = nw; meta[M_FMT] = mask_enabled;
  struct remote_rpc_control_unsigned_module ctrl = {CDSP_DOMAIN_ID, 1};
  remote_session_control(DSPRPC_CONTROL_UNSIGNED_MODULE, &ctrl, sizeof ctrl);
  remote_handle64 h = 0; int r = mega_open(mega_URI CDSP_DOMAIN, &h);
  if (r) { fprintf(stderr, "mega_open=0x%x\n", r); return 1; }
  int wfd = rpcmem_to_fd(wts), bfd = rpcmem_to_fd(blob);
  if (fastrpc_mmap(CDSP_DOMAIN_ID, wfd, wts, 0, wtbytes, FASTRPC_MAP_FD) || fastrpc_mmap(CDSP_DOMAIN_ID, bfd, blob, 0, blobbytes, FASTRPC_MAP_FD)) { fprintf(stderr, "fastrpc_mmap failed\n"); return 1; }
  sync_fd(wfd); sync_fd(bfd);
  int times[32] = {0}, rc = 0;
  const int nblocks = chain ? chain_count : 1;
  for (int layer = 0; layer < nblocks; layer++) {
    if (chain) {
      char path[512]; snprintf(path, sizeof path, "%s/blk%02d.bin", dir, layer);
      FILE *lf = fopen(path, "rb"); if (!lf) { fprintf(stderr, "open %s: %s\n", path, strerror(errno)); return 1; }
      if (fread(wts, 1, wtbytes, lf) != wtbytes || fgetc(lf) != EOF) { fclose(lf); fprintf(stderr, "bad pack size: %s\n", path); return 1; } fclose(lf); sync_fd(wfd);
    }
    meta[M_FIRST] = layer == 0 || getenv("KREA_POWER_EACH") ? 1 : 2; meta[M_REL] = layer == nblocks - 1 || getenv("KREA_RELEASE_EACH") ? 1 : 2;
    memset(times, 0, sizeof times); rc = 0;
    r = mega_block(h, meta, M_NMETA, wfd, bfd, mod, (int)((size_t)6 * D * 4), times, 32, &rc);
    if (r || rc) { fprintf(stderr, "layer %d mega_block r=0x%x rc=%d\n", layer, r, rc); return 1; }
    sync_fd(bfd);
    if (chain && getenv("KREA_DUMP_LAYERS")) { char outpath[64]; snprintf(outpath, sizeof outpath, "x32_layer%02d.bin", layer); if (write_file(dir, outpath, blob + meta[M_X], ent[0].bytes)) return 1; }
    if (chain) { printf("layer %02d DSP stages (us):", layer); for (int i = 0; i < 12; i++) printf(" %d", times[i]); printf("\n"); }
  }
  if (write_file(dir, "x32_out.bin", blob + meta[M_X], ent[0].bytes)) return 1;
  printf("Krea mode %d ok: NTOK=%d NVALID=%d workers=%d%s mask=%d\n", mode, ntok, nvalid, nw, chain ? " chain" : "", mask_enabled);
  printf("DSP stages (us):"); for (int i = 0; i < 12; i++) printf(" %d", times[i]); printf("\n");
  mega_close(h); rpcmem_free(blob); rpcmem_free(wts); free(mod); return 0;
}
