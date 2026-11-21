#include <stdint.h>

/* Use the shared V73+ convert-unit store path proven by Mage on V79. */
#if __HEXAGON_ARCH__ >= 73
static uint8_t *g_hmx_bias;
void hmx_store_cvt(uint8_t *out, uint32_t m, uint8_t *bias);
void hmx_scale_init(uint8_t *vtcm) {
  volatile uint32_t *w = (volatile uint32_t *)vtcm;
  for (int i = 0; i < 64; i++) w[i] = i < 32 ? 0x3C00u : 0u;
  g_hmx_bias = vtcm;
}
void hmx_store(uint8_t *out, uint32_t m) { hmx_store_cvt(out, m, g_hmx_bias); }
#else
void hmx_scale_init(uint8_t *vtcm) { (void)vtcm; }
#endif
