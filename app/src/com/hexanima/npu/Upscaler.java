package com.hexanima.npu;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Picture upscaler on the NPU: Real-ESRGAN General x4v3 (BSD-3, Qualcomm AI Hub ONNX converted by upscale/build.sh) as one fp16 HTP context per
 *  Hexagon generation (lib/libesrgan_<arch>.so, 256x256 -> 1024x1024, RGB 0..1 NCHW), run tile by tile by libvaerun.so ("run IN OUT" -> "done ms").
 *  Tiles overlap by OV input pixels and each one keeps the part up to the middle of the overlap, so no seams; x2 averages the x4 tile 2x2. */
final class Upscaler {
    static final int T = 256, OV = 24;
    static final long MAX_OUT_PIXELS = 24_000_000L;   // 6144x4096 at most: the output is held as ints (~96 MB)
    /** set by the main screen while it generates: the NPU and the RAM belong to that run */
    static volatile boolean npuBusy;

    static File context(Context c) {
        String a = Compat.dspArch(); if (a.isEmpty()) return null;
        File f = new File(c.getApplicationInfo().nativeLibraryDir, "libesrgan_" + a + ".so");
        return f.isFile() ? f : null;
    }
    static boolean available(Context c) { return context(c) != null; }
    static boolean fits(int w, int h, int factor) { return (long) w * factor * h * factor <= MAX_OUT_PIXELS; }

    interface Progress { void at(int done, int total); }

    /** tile starts along one side (the last tile ends at the edge) */
    static int[] starts(int len) {
        List<Integer> s = new ArrayList<>(); int step = T - OV;
        for (int p = 0; ; p += step) { if (p + T >= len) { s.add(Math.max(0, len - T)); break; } s.add(p); }
        int[] r = new int[s.size()]; for (int i = 0; i < r.length; i++) r[i] = s.get(i); return r;
    }
    /** where tile i hands over to tile i+1: the middle of their overlap */
    static int[] seams(int[] st, int len) {
        int[] sm = new int[st.length + 1]; sm[0] = 0; sm[st.length] = len;
        for (int i = 0; i + 1 < st.length; i++) sm[i + 1] = (st[i + 1] + st[i] + T) / 2;
        return sm;
    }

    static Bitmap run(Context c, Bitmap src, int factor, Progress pr, BooleanSupplier cancel) throws Exception {
        File ctx = context(c); if (ctx == null) throw new IOException(L.s("Este chip no tiene el escalador", "This chip has no upscaler"));
        final int w = src.getWidth(), h = src.getHeight(), W = Math.max(w, T), H = Math.max(h, T);   // smaller pictures are padded with their edge pixels
        if (!fits(w, h, factor)) throw new IOException(L.s("La imagen es demasiado grande para ese aumento", "The picture is too large for that factor"));
        int[] px = new int[w * h]; src.getPixels(px, 0, w, 0, 0, w, h);
        final int ow = w * factor, oh = h * factor; int[] out = new int[ow * oh];
        int[] sx = starts(W), sy = starts(H), mx = seams(sx, W), my = seams(sy, H);
        File dir = new File(c.getCacheDir(), "upscale"); dir.mkdirs(); File in = new File(dir, "in.f32"), res = new File(dir, "out.f32");
        String lib = c.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder pb = new ProcessBuilder(lib + "/libvaerun.so", lib + "/libQnnHtp.so", lib + "/libQnnSystem.so", ctx.getPath()).redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", lib + ":/system/lib64:/vendor/lib64"); pb.environment().put("ADSP_LIBRARY_PATH", lib);
        Process p = pb.start();
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(p.getInputStream())); Writer wr = new OutputStreamWriter(p.getOutputStream())) {
            StringBuilder log = new StringBuilder();
            for (String l; ; ) { l = rd.readLine(); if (l == null) throw new IOException("vaerun: " + log); log.append(l).append('\n'); if (l.startsWith("ready")) break; if (l.startsWith("ERROR")) throw new IOException(l); }
            final int n = T * T, S = T * 4, N4 = S * S; float[] buf = new float[3 * n]; float[] o4 = new float[3 * N4];
            ByteBuffer bb = ByteBuffer.allocate(3 * n * 4).order(ByteOrder.LITTLE_ENDIAN);
            int total = sx.length * sy.length, done = 0;
            for (int ty = 0; ty < sy.length; ty++) for (int tx = 0; tx < sx.length; tx++) {
                if (cancel.getAsBoolean()) throw new InterruptedException();
                int x0 = sx[tx], y0 = sy[ty];
                for (int y = 0; y < T; y++) for (int x = 0; x < T; x++) {
                    int v = px[Math.min(y0 + y, h - 1) * w + Math.min(x0 + x, w - 1)], i = y * T + x;
                    buf[i] = ((v >> 16) & 255) / 255f; buf[n + i] = ((v >> 8) & 255) / 255f; buf[2 * n + i] = (v & 255) / 255f;
                }
                bb.clear(); bb.asFloatBuffer().put(buf);
                try (FileOutputStream fo = new FileOutputStream(in)) { fo.write(bb.array()); }
                wr.write("run " + in.getPath() + " " + res.getPath() + "\n"); wr.flush();
                for (String l; ; ) { l = rd.readLine(); if (l == null) throw new IOException("vaerun stopped"); if (l.startsWith("done")) break; if (l.startsWith("ERROR")) throw new IOException(l); }
                try (RandomAccessFile rf = new RandomAccessFile(res, "r"); FileChannel ch = rf.getChannel()) {
                    FloatBuffer fb = ch.map(FileChannel.MapMode.READ_ONLY, 0, (long) 3 * N4 * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); fb.get(o4);
                }
                // the part of this tile up to the seams, clipped to the real picture, written at the chosen factor
                int ax = mx[tx], bx = Math.min(mx[tx + 1], w), ay = my[ty], by = Math.min(my[ty + 1], h), q = 4 / factor;
                for (int y = ay * factor; y < by * factor; y++) for (int x = ax * factor; x < bx * factor; x++) {
                    int lx = (x - x0 * factor) * q, ly = (y - y0 * factor) * q; float r = 0, g = 0, b = 0;
                    for (int dy = 0; dy < q; dy++) for (int dx = 0; dx < q; dx++) { int k = (ly + dy) * S + lx + dx; r += o4[k]; g += o4[N4 + k]; b += o4[2 * N4 + k]; }
                    float d = q * q; out[y * ow + x] = 0xFF000000 | (c8(r / d) << 16) | (c8(g / d) << 8) | c8(b / d);
                }
                pr.at(++done, total);
            }
            wr.write("quit\n"); wr.flush();
        } finally { p.destroy(); in.delete(); res.delete(); }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888);
    }
    private static int c8(float v) { int i = Math.round(v * 255f); return i < 0 ? 0 : Math.min(255, i); }
}
