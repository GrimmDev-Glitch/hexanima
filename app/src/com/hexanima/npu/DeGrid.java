package com.hexanima.npu;

/** Removes the faint 4-pixel lattice that the MOSS-Image 4-step adapter leaves in textured areas (fur, sand, hair): a horizontal and a vertical line pattern with a period of exactly 4 px,
 *  visible as dotted noise in dark areas. The decoder is exact (NPU = CPU decoder), so the pattern is in the latent. Per axis: high-pass, demodulate with cos/sin(pi*pos/2), low-pass (the amplitude changes slowly),
 *  re-modulate and subtract; the correction is capped. Smooth areas and ordinary detail are untouched (the pattern has a very narrow spectrum). Works on one channel in 0..255 units, in place. */
final class DeGrid {
    static final int ALONG = 64, ACROSS = 31, HP = 8; static final float CAP = 14f;

    static void apply(float[] a, int w, int h) {
        final int n = w * h; final float[] cs = {1, 0, -1, 0}, sn = {0, 1, 0, -1};
        float[] t = new float[n], hp = new float[n], p = new float[n], q = new float[n], lvl = new float[n];
        box(a, t, w, h, 8, true); box(t, lvl, w, h, 8, false);   // local brightness: the correction fades out next to pure black / pure white, where the picture is clipped and has no lattice to remove
        for (int axis = 0; axis < 2; axis++) {
            boolean alongY = axis == 0;
            box(a, t, w, h, HP, alongY); box(t, hp, w, h, HP, alongY);
            for (int i = 0; i < n; i++) hp[i] = a[i] - hp[i];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { int i = y * w + x, k = (alongY ? y : x) & 3; p[i] = hp[i] * cs[k]; q[i] = hp[i] * sn[k]; }
            for (float[] z : new float[][]{p, q}) { box(z, t, w, h, ALONG, alongY); box(t, z, w, h, ACROSS, !alongY); }
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int i = y * w + x, k = (alongY ? y : x) & 3;
                float art = 2f * (p[i] * cs[k] + q[i] * sn[k]);
                float g = Math.max(0f, Math.min(1f, Math.min((lvl[i] - 3f) / 10f, (252f - lvl[i]) / 10f)));
                a[i] -= g * Math.max(-CAP, Math.min(CAP, art));
            }
        }
    }

    /** moving average over n samples (window i-n/2 .. i-n/2+n-1, edges replicated) along y or x */
    static void box(float[] src, float[] dst, int w, int h, int n, boolean alongY) {
        final int len = alongY ? h : w, lines = alongY ? w : h, step = alongY ? w : 1, ls = alongY ? 1 : w, r = n / 2;
        for (int l = 0; l < lines; l++) {
            final int base = l * ls; double s = 0;
            for (int k = -r; k < -r + n; k++) s += src[base + Math.max(0, Math.min(len - 1, k)) * step];
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = (float) (s / n);
                s += src[base + Math.min(len - 1, i - r + n) * step] - src[base + Math.max(0, i - r) * step];
            }
        }
    }
}
