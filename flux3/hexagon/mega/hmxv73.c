#include <stdint.h>
/* V73+ HMX (Snapdragon 8 Gen 2 port): an fp16 accumulator store goes through the convert unit, which reads a bias/scale block from VTCM
   (hmx_store_cvt in hmxacc.S). hmx_bias_setup writes 32 (scale = 1.0, bias = 0) pairs and zero padding into the last 4 KB of a VTCM
   reservation; hmx_store is the plain store the kernels call. Below v73 hmxacc.S has its own hmx_store and this file is a no-op.
   The block layout is the one verified on the phone for the V73 Mage, Qwen and MOSS kernels (mage/hexagon/mega/hmxscale.c). */
#if __HEXAGON_ARCH__ >= 73
static uint8_t *g_hmx_bias;
void hmx_store_cvt(uint8_t *out, uint32_t m, uint8_t *bias);
void hmx_bias_setup(uint8_t *vt) {
  volatile uint32_t *w = (volatile uint32_t *)vt;
  for (int i = 0; i < 64; i++) w[i] = i < 32 ? 0x3C00u : 0u;
  g_hmx_bias = vt;
}
void hmx_store(uint8_t *out, uint32_t m) { hmx_store_cvt(out, m, g_hmx_bias); }
#else
void hmx_bias_setup(uint8_t *vt) { (void)vt; }
#endif
