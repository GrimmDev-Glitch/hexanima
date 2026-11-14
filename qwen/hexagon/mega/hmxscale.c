#include <stdint.h>
/* V73+ HMX: the fp16 store goes through the convert unit. A 256-byte VTCM block
   contains 32 (scale=1,bias=0) pairs followed by zero padding. */
__attribute__((visibility("hidden"))) uint8_t *hmx_scale_blk;
void hmx_scale_init(uint8_t *vtcm) {
#if __HEXAGON_ARCH__ >= 73
  volatile uint32_t *w = (volatile uint32_t *)vtcm;
  for (int i = 0; i < 64; i++) w[i] = i < 32 ? 0x3C00u : 0u;
  hmx_scale_blk = vtcm;
#else
  (void)vtcm;
#endif
}
