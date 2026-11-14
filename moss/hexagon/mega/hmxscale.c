#include <stdint.h>

/* V73+ needs 256 bytes in VTCM for the HMX convert scale/bias block. */
__attribute__((visibility("hidden"))) uint8_t *hmx_scale_blk;

void hmx_scale_init(uint8_t *vtcm) {
#if __HEXAGON_ARCH__ >= 73
  volatile uint32_t *w = (volatile uint32_t *)vtcm;
  for (int i = 0; i < 64; i++) w[i] = i < 32 ? 0x3C00u : 0u;  /* scale 1.0, bias 0 */
  hmx_scale_blk = vtcm;
#else
  (void)vtcm;
#endif
}
