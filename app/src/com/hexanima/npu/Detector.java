package com.hexanima.npu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

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
import java.util.Collections;
import java.util.List;

/** Face and hand detectors on the NPU: face_yolov8n / hand_yolov8n (Bingsu/adetailer, Apache-2.0, the detectors ADetailer uses) as fp16 HTP contexts
 *  (detect/build.sh; lib/libfacedet_<arch>.so, lib/libhanddet_<arch>.so), run once by libvaerun.so: input [1,3,640,640] RGB 0..1 letterboxed,
 *  output [1,5,8400] = cx, cy, w, h, score in 640-pixel units. About 6 ms on the NPU plus the process start. */
final class Detector {
    static final String FACE = "face", HAND = "hand";
    static File context(Context c, String kind) {
        String a = Compat.dspArch(); if (a.isEmpty()) return null;
        File f = new File(c.getApplicationInfo().nativeLibraryDir, "lib" + kind + "det_" + a + ".so");
        return f.isFile() ? f : null;
    }
    static boolean available(Context c, String kind) { return context(c, kind) != null; }

    /** boxes in the bitmap's pixels, best first, overlapping duplicates removed */
    static List<RectF> detect(Context c, Bitmap src, String kind, float minScore) throws Exception {
        File ctx = context(c, kind); if (ctx == null) throw new IOException("no detector");
        final int S = 640, w = src.getWidth(), h = src.getHeight(); final float s = (float) S / Math.max(w, h);
        final int nw = Math.round(w * s), nh = Math.round(h * s), px = (S - nw) / 2, py = (S - nh) / 2;
        Bitmap lb = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(lb); cv.drawColor(Color.rgb(114, 114, 114));
        cv.drawBitmap(src, null, new RectF(px, py, px + nw, py + nh), new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] pix = new int[S * S]; lb.getPixels(pix, 0, S, 0, 0, S, S); lb.recycle();
        File dir = new File(c.getCacheDir(), "detect"); dir.mkdirs(); File in = new File(dir, "in.f32"), out = new File(dir, "out.f32");
        ByteBuffer bb = ByteBuffer.allocate(3 * S * S * 4).order(ByteOrder.LITTLE_ENDIAN); FloatBuffer fb = bb.asFloatBuffer();
        for (int ch = 0; ch < 3; ch++) for (int i = 0; i < S * S; i++) fb.put(((pix[i] >> (16 - 8 * ch)) & 255) / 255f);
        try (FileOutputStream fo = new FileOutputStream(in)) { fo.write(bb.array()); }
        String lib = c.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder pb = new ProcessBuilder(lib + "/libvaerun.so", lib + "/libQnnHtp.so", lib + "/libQnnSystem.so", ctx.getPath()).redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", lib + ":/system/lib64:/vendor/lib64"); pb.environment().put("ADSP_LIBRARY_PATH", lib);
        Process p = pb.start();
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(p.getInputStream())); Writer wr = new OutputStreamWriter(p.getOutputStream())) {
            StringBuilder log = new StringBuilder();
            for (String l; ; ) { l = rd.readLine(); if (l == null) throw new IOException("vaerun: " + log); log.append(l).append('\n'); if (l.startsWith("ready")) break; if (l.startsWith("ERROR")) throw new IOException(l); }
            wr.write("run " + in.getPath() + " " + out.getPath() + "\nquit\n"); wr.flush();
            for (String l; ; ) { l = rd.readLine(); if (l == null) throw new IOException("vaerun stopped"); if (l.startsWith("done")) break; if (l.startsWith("ERROR")) throw new IOException(l); }
        } finally { p.destroy(); }
        float[] o = new float[5 * 8400];
        try (RandomAccessFile rf = new RandomAccessFile(out, "r"); FileChannel chn = rf.getChannel()) { chn.map(FileChannel.MapMode.READ_ONLY, 0, o.length * 4L).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(o); }
        List<Integer> idx = new ArrayList<>(); for (int i = 0; i < 8400; i++) if (o[4 * 8400 + i] >= minScore) idx.add(i);
        Collections.sort(idx, (a, b) -> Float.compare(o[4 * 8400 + b], o[4 * 8400 + a]));
        List<RectF> keep = new ArrayList<>();
        for (int i : idx) {
            float cx = o[i], cy = o[8400 + i], bw = o[2 * 8400 + i], bh = o[3 * 8400 + i];
            RectF r = new RectF((cx - bw / 2 - px) / s, (cy - bh / 2 - py) / s, (cx + bw / 2 - px) / s, (cy + bh / 2 - py) / s);
            r.intersect(0, 0, w, h); if (r.width() < 4 || r.height() < 4) continue;
            boolean dup = false; for (RectF q : keep) { RectF x = new RectF(); if (x.setIntersect(r, q) && x.width() * x.height() > 0.45f * Math.min(r.width() * r.height(), q.width() * q.height())) { dup = true; break; } }
            if (!dup) keep.add(r);
            if (keep.size() >= 8) break;
        }
        return keep;
    }

    /** a mask like the painted ones (black, area to change in white) at the bitmap's size: each box grown by grow, faces as ellipses, hands as rounded boxes */
    static void writeMask(List<RectF> boxes, int w, int h, String kind, float grow, File f) throws IOException {
        Bitmap m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(m); c.drawColor(0xFF000000);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0xFFFFFFFF);
        for (RectF r : boxes) {
            float gx = r.width() * (grow - 1) / 2, gy = r.height() * (grow - 1) / 2; RectF g = new RectF(r.left - gx, r.top - gy, r.right + gx, r.bottom + gy);
            if (FACE.equals(kind)) c.drawOval(g, p); else c.drawRoundRect(g, g.width() * .25f, g.height() * .25f, p);
        }
        try (FileOutputStream o = new FileOutputStream(f)) { m.compress(Bitmap.CompressFormat.PNG, 100, o); } finally { m.recycle(); }
    }
}
