package com.hexanima.npu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pose control for Anima ("Anima Control - Pose" by Claquasse, a derivative of Anima: non-commercial): the pose of a picture guides the new picture.
 *  1. RTMW whole-body pose (133 keypoints, OpenMMLab, Apache-2.0) on the NPU finds the skeleton (pose/build.sh rtmw, about 5 ms).
 *  2. The skeleton is drawn like the adapter was trained on (thin coloured limbs, white joints, hand and face dots on black) at the picture's size.
 *  3. The Qwen-Image VAE encoder (pose/build.sh vaeenc, 384 px tiles on the NPU) turns it into the control latent, written to files/anima/control.bin.
 *  4. animad adds ControlEmbedder(control latent) * strength to the patch embedding; the adapter's LoRA part is an ordinary Anima LoRA (anima-pose-control).
 *  Files are downloaded on first use: the two contexts and the embedder from GrimmDev-Glitch/hexanima-packs (pose/), the LoRA from Claquasse/Anima-Control-Pose. */
final class Pose {
    static final String BASE = "https://huggingface.co/GrimmDev-Glitch/hexanima-packs/resolve/main/pose/";
    static final String LORA_URL = "https://huggingface.co/Claquasse/Anima-Control-Pose/resolve/8f559771d5a49a02fa03f7df2a05ccb7eecb3a2a/anima_pose_preview2.safetensors";
    static final String LORA = "anima-pose-control";
    // {file name on the phone, url, sha256, size}
    static final String[][] FILES = {
        {"control_w.bin", BASE + "anima_pose_preview2_control_w.bin", "00bd5f9e2d4e9bf055e59d0c72066e932fca235f88d6daefee69510859dbc181", "532480"},
        {"vae_enc_384_v73.bin", BASE + "vae_enc_384_v73.bin", "a4cae70d196820f9411b22605540aeacd906fd32534fdb412220726c55d55a02", "41626000"},
        {"rtmw_256x192_v73.bin", BASE + "rtmw_256x192_v73.bin", "c1afe1aacc4e300be610d49a9d76768b1ac9629a0a9c2293d65f512f07e39997", "120962424"},
        {LORA + ".safetensors", LORA_URL, "c10fa11628e9b1bf16dfe095ac5c6dc4ddd9ae77d7fe023d0a1857f7e29c516f", "75051096"}};
    static final long TOTAL = 532480L + 41626000L + 120962424L + 75051096L;
    static boolean supported() { return Compat.dspArch().equals("v73"); }
    static File dir(Context c) { return new File(c.getFilesDir(), "pose"); }
    static File loraFile(Context c) { return new File(AnimaLora.dir(c.getFilesDir()), LORA + ".safetensors"); }
    static File target(Context c, String name) { return name.endsWith(".safetensors") ? loraFile(c) : name.equals("control_w.bin") ? new File(new File(c.getFilesDir(), "anima"), name) : new File(dir(c), name); }
    static boolean installed(Context c) { for (String[] f : FILES) if (target(c, f[0]).length() != Long.parseLong(f[3])) return false; return new File(dir(c), ".ok").isFile(); }
    static void install(Context c, Civitai.Progress pg, AtomicBoolean cancel) throws Exception {
        dir(c).mkdirs(); AnimaLora.dir(c.getFilesDir()).mkdirs(); long done = 0;
        for (String[] f : FILES) {
            File out = target(c, f[0]); final long base = done, size = Long.parseLong(f[3]);
            if (out.length() != size || !Tagger.sha(out).equals(f[2])) {
                Civitai.download(c, f[1], out, size, (a, b) -> pg.at(base + a, TOTAL), cancel);
                if (!Tagger.sha(out).equals(f[2])) { out.delete(); throw new IOException("SHA-256: " + f[0]); }
            }
            done += size; pg.at(done, TOTAL);
        }
        new FileOutputStream(new File(dir(c), ".ok")).close();
    }

    // ---- the saved pose: 133 keypoints (x, y in 0..1 of the source picture, score) and the picture's aspect ----
    static File poseFile(Context c) { return new File(dir(c), "pose.json"); }
    static boolean active(Context c) { return poseFile(c).isFile() && installed(c); }
    static void clear(Context c) { poseFile(c).delete(); controlFile(c).delete(); }
    static File controlFile(Context c) { return new File(new File(c.getFilesDir(), "anima"), "control.bin"); }
    static float strength(Context c) { return c.getSharedPreferences("anima", 0).getFloat("pose_strength", 2f); }

    static Process runner(Context c, String ctxName) throws IOException {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder pb = new ProcessBuilder(lib + "/libvaerun.so", lib + "/libQnnHtp.so", lib + "/libQnnSystem.so", new File(dir(c), ctxName).getPath()).redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", lib + ":/system/lib64:/vendor/lib64"); pb.environment().put("ADSP_LIBRARY_PATH", lib);
        return pb.start();
    }
    static void waitFor(BufferedReader rd, String word) throws IOException {
        StringBuilder log = new StringBuilder();
        for (String l; ; ) { l = rd.readLine(); if (l == null) throw new IOException("vaerun: " + log); log.append(l).append('\n'); if (l.startsWith(word)) return; if (l.startsWith("ERROR")) throw new IOException(l); }
    }
    static void writeFloats(File f, float[] a) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN); bb.asFloatBuffer().put(a);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(bb.array()); }
    }
    static float[] readFloats(File f, int n) throws IOException {
        float[] o = new float[n];
        try (RandomAccessFile rf = new RandomAccessFile(f, "r"); FileChannel ch = rf.getChannel()) { ch.map(FileChannel.MapMode.READ_ONLY, 0, n * 4L).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(o); }
        return o;
    }

    /** finds the pose of the (single, whole-picture) person and saves it; returns how many of the 17 body joints were found (0 = no person) */
    static int detect(Context c, Bitmap src) throws Exception {
        final int IW = 192, IH = 256, w = src.getWidth(), h = src.getHeight();
        float bw = w * 1.25f, bh = h * 1.25f; if (bw > bh * 0.75f) bh = bw / 0.75f; else bw = bh * 0.75f;   // the whole picture as the box, padded, at the model's 3:4 shape
        float cx = w / 2f, cy = h / 2f, sc = IW / bw;
        Bitmap in = Bitmap.createBitmap(IW, IH, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(in); cv.drawColor(Color.BLACK);
        Matrix m = new Matrix(); m.setScale(sc, sc); m.postTranslate((bw / 2 - cx) * sc, (bh / 2 - cy) * sc);
        cv.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] px = new int[IW * IH]; in.getPixels(px, 0, IW, 0, 0, IW, IH); in.recycle();
        float[] mean = {103.53f, 116.28f, 123.675f}, std = {57.375f, 57.12f, 58.395f}, x = new float[3 * IW * IH];   // rtmlib feeds BGR with the (RGB-ordered) mean / std applied in place: channel 0 = B with mean[0] of the list
        float[] lm = {123.675f, 116.28f, 103.53f}, ls = {58.395f, 57.12f, 57.375f};
        for (int i = 0; i < IW * IH; i++) { int q = px[i]; float b = q & 255, g = (q >> 8) & 255, r = (q >> 16) & 255;
            x[i] = (b - lm[0]) / ls[0]; x[IW * IH + i] = (g - lm[1]) / ls[1]; x[2 * IW * IH + i] = (r - lm[2]) / ls[2]; }
        File wd = new File(c.getCacheDir(), "pose"); wd.mkdirs(); File fi = new File(wd, "in.f32"), fo = new File(wd, "out.f32");
        writeFloats(fi, x);
        Process p = runner(c, "rtmw_256x192_v73.bin");
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(p.getInputStream())); Writer wr = new OutputStreamWriter(p.getOutputStream())) {
            waitFor(rd, "ready"); wr.write("run " + fi.getPath() + " " + fo.getPath() + "\nquit\n"); wr.flush(); waitFor(rd, "done");
        } finally { p.destroy(); }
        float[] o = readFloats(fo, 133 * 896); JSONArray pts = new JSONArray(); int body = 0;
        for (int k = 0; k < 133; k++) {
            int bx = 0, by = 0; float mx = -1e9f, my = -1e9f;
            for (int i = 0; i < 384; i++) { float v = o[k * 896 + i]; if (v > mx) { mx = v; bx = i; } }
            for (int i = 0; i < 512; i++) { float v = o[k * 896 + 384 + i]; if (v > my) { my = v; by = i; } }
            float kx = bx / 2f / IW * bw + cx - bw / 2, ky = by / 2f / IH * bh + cy - bh / 2, s = (mx + my) / 2;
            if (kx < 0 || ky < 0 || kx > w || ky > h) s = 0;   // outside the picture: the padded border
            if (k < 17 && s >= 0.3f) body++;
            pts.put(new JSONArray().put(kx / w).put(ky / h).put(s));
        }
        if (body < 4) return 0;
        dir(c).mkdirs();
        try (FileWriter fw = new FileWriter(poseFile(c))) { fw.write(new JSONObject().put("aspect", (double) w / h).put("points", pts).toString()); }
        return body;
    }

    static final int[][] LIMBS = {{5, 7}, {7, 9}, {6, 8}, {8, 10}, {11, 13}, {13, 15}, {12, 14}, {14, 16}, {5, 6}, {11, 12}, {5, 11}, {6, 12}, {0, 5}, {0, 6}};
    static final int[] PAL = {0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF80FF00, 0xFF00FF00, 0xFF00FF80, 0xFF00FFFF, 0xFF0080FF, 0xFF0000FF, 0xFF8000FF, 0xFFFF00FF, 0xFFFF0080, 0xFFB4B4B4, 0xFFDCDCDC};
    /** the saved pose drawn at w x h (kept whole and centred when the shapes differ), in the adapter's "thin" style; null without a pose */
    static Bitmap render(Context c, int w, int h) {
        float[][] k = new float[133][3]; double aspect;
        try { JSONObject j = new JSONObject(Models.read(poseFile(c))); aspect = j.getDouble("aspect"); JSONArray a = j.getJSONArray("points");
            for (int i = 0; i < 133; i++) { JSONArray q = a.getJSONArray(i); k[i][0] = (float) q.getDouble(0); k[i][1] = (float) q.getDouble(1); k[i][2] = (float) q.getDouble(2); }
        } catch (Exception e) { return null; }
        float sw = w, sh = (float) (w / aspect); if (sh > h) { sh = h; sw = (float) (h * aspect); }
        float ox = (w - sw) / 2, oy = (h - sh) / 2;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(b); cv.drawColor(Color.BLACK);
        Paint p = new Paint(); p.setAntiAlias(false); p.setStrokeWidth(2); p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < LIMBS.length; i++) { int a = LIMBS[i][0], d = LIMBS[i][1]; if (k[a][2] < 0.3f || k[d][2] < 0.3f) continue;
            p.setColor(PAL[i % PAL.length]); cv.drawLine(ox + k[a][0] * sw, oy + k[a][1] * sh, ox + k[d][0] * sw, oy + k[d][1] * sh, p); }
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 133; i++) { if (k[i][2] < 0.3f) continue;
            float r = i < 17 ? 3 : i < 23 ? 1 : 2; p.setColor(i >= 91 ? 0xFF00FFFF : Color.WHITE);
            cv.drawCircle(ox + k[i][0] * sw, oy + k[i][1] * sh, r, p); }
        return b;
    }

    /** VAE-encodes the skeleton picture in 384 px tiles (256 px kept from each, 64 px of context around) -> latent mean [16][h/8][w/8] */
    static float[] encode(Context c, Bitmap skel) throws Exception {
        final int w = skel.getWidth(), h = skel.getHeight(), T = 384, K = 256, M = 64, lw = w / 8, lh = h / 8;
        int[] px = new int[w * h]; skel.getPixels(px, 0, w, 0, 0, w, h);
        float[] lat = new float[16 * lh * lw], x = new float[3 * T * T];
        File wd = new File(c.getCacheDir(), "pose"); wd.mkdirs(); File fi = new File(wd, "tile.f32"), fo = new File(wd, "lat.f32");
        Process p = runner(c, "vae_enc_384_v73.bin");
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(p.getInputStream())); Writer wr = new OutputStreamWriter(p.getOutputStream())) {
            waitFor(rd, "ready");
            for (int ty = 0; ty < h; ty += K) for (int tx = 0; tx < w; tx += K) {
                for (int y = 0; y < T; y++) for (int xx = 0; xx < T; xx++) {
                    int sy = ty - M + y, sx = tx - M + xx; int q = sy >= 0 && sy < h && sx >= 0 && sx < w ? px[sy * w + sx] : 0xFF000000; int i = y * T + xx;
                    x[i] = ((q >> 16) & 255) / 127.5f - 1; x[T * T + i] = ((q >> 8) & 255) / 127.5f - 1; x[2 * T * T + i] = (q & 255) / 127.5f - 1; }
                writeFloats(fi, x); wr.write("run " + fi.getPath() + " " + fo.getPath() + "\n"); wr.flush(); waitFor(rd, "done");
                float[] o = readFloats(fo, 16 * 48 * 48);
                for (int ch = 0; ch < 16; ch++) for (int y = 0; y < K / 8; y++) for (int xx = 0; xx < K / 8; xx++) {
                    int ly = ty / 8 + y, lx = tx / 8 + xx; if (ly >= lh || lx >= lw) continue;
                    lat[(ch * lh + ly) * lw + lx] = o[(ch * 48 + M / 8 + y) * 48 + M / 8 + xx]; }
            }
            wr.write("quit\n"); wr.flush();
        } finally { p.destroy(); }
        return lat;
    }

    static final float[] LAT_MEAN = {-0.7571f, -0.7089f, -0.9113f, 0.1075f, -0.1745f, 0.9653f, -0.1517f, 1.5508f, 0.4134f, -0.0715f, 0.5517f, -0.3632f, -0.1922f, -0.9497f, 0.2503f, -0.2921f};
    static final float[] LAT_STD = {2.8184f, 1.4541f, 2.3275f, 2.6558f, 1.2196f, 1.7708f, 2.6052f, 2.0743f, 3.2687f, 2.1526f, 2.8652f, 1.5579f, 1.6382f, 1.1253f, 2.8251f, 1.916f};
    static String lastKey = "";
    /** before an Anima picture: writes (or removes) files/anima/control.bin for this size and returns the prompt with the adapter's LoRA tag when a pose is on */
    static String apply(Context c, String prompt, int w, int h) {
        File cf = controlFile(c);
        if (!active(c)) { cf.delete(); lastKey = ""; return prompt; }
        try {
            float st = strength(c); boolean norm = c.getSharedPreferences("anima", 0).getBoolean("pose_norm", true);
            String key = w + "x" + h + ":" + st + ":" + norm + ":" + poseFile(c).lastModified();
            if (!key.equals(lastKey) || !cf.isFile()) {
                Bitmap sk = render(c, w, h); if (sk == null) { cf.delete(); return prompt; }
                float[] lat = encode(c, sk); sk.recycle(); int lh = h / 8, lw = w / 8;
                if (norm) for (int ch = 0; ch < 16; ch++) for (int i = 0; i < lh * lw; i++) lat[ch * lh * lw + i] = (lat[ch * lh * lw + i] - LAT_MEAN[ch]) / LAT_STD[ch];
                ByteBuffer bb = ByteBuffer.allocate(16 + lat.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                bb.putInt(0x314C5443).putInt(lh).putInt(lw).putFloat(st); bb.asFloatBuffer().put(lat);
                File tmp = new File(cf.getPath() + ".tmp"); try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(bb.array()); }
                cf.delete(); if (!tmp.renameTo(cf)) throw new IOException("control.bin");
                lastKey = key;
            }
            return prompt.contains("<lora:" + LORA) ? prompt : prompt + " <lora:" + LORA + ":1.0>";
        } catch (Exception e) { cf.delete(); lastKey = ""; return prompt; }
    }
}
