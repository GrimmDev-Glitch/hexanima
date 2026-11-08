#include <stdint.h>
/* V73+ HMX: the fp16 store (hmx_store, hmxacc.S) goes through the convert unit, which reads a per-column scale/bias block from VTCM:
   32 words with the scale (fp16) in the low half and the bias (fp16) in the high half, then 128 zero bytes. Scale 1.0 / bias 0 gives the
   plain accumulator, like after.hf on V69. Call hmx_scale_init with 256 free, 256-aligned VTCM bytes after every VTCM acquire. No-op on V69. */
__attribute__((visibility("hidden"))) uint8_t *hmx_scale_blk;   /* hidden: hmxacc.S reaches it PC-relative */
void hmx_scale_init(uint8_t *vtcm) {
#if __HEXAGON_ARCH__ >= 73
  volatile uint32_t *w = (volatile uint32_t *)vtcm;
  for (int i = 0; i < 64; i++) w[i] = i < 32 ? 0x3C00u : 0u;
  hmx_scale_blk = vtcm;
#else
  (void)vtcm;
#endif
}
