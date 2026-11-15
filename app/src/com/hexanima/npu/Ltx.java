package com.hexanima.npu;

import android.content.ContentValues;
import android.content.Context;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** LTX-Video 2B 0.9.8 distilled (Lightricks, LTXV Open Weights License): text to a 1 s clip, 512x320 x 25 frames, on the Snapdragon 8 Gen 2.
 *  T5-XXL runs on the CPU (libltxt5.so, ggml), the transformer (4 QNN contexts, 8 steps) and the VAE decoder on the NPU (libltxrun.so).
 *  The contexts were built with QAIRT 2.50 by timmyboy26 (huggingface.co/timmyboy26/LTX-Video-2B-0.9.8-distilled-QNN) and need that runtime;
 *  the APK ships it renamed (libqnn250*.so) next to the 2.28 runtime of the other models, and files/ltx/lib links the original names to it. */
final class Ltx {
    static final String ID = "ltxv-2b-v73";
    /** false: the Video screen and the video model in Models are hidden (LTX quality too low; the Wan 2.2 engine is still a prototype, see docs/WAN22_STUDY.md) */
    static final boolean SHOWN = false;
    static boolean is(String id) { return id != null && id.startsWith("ltxv"); }
    /** contexts for this shape exist only for V73 here (512x320x25); other Hexagon generations would need their own folder */
    static boolean supported() { return Compat.dspArch().equals("v73"); }
    static File dir(File home) { return new File(home, "ltx"); }
    static File outDir(File home) { File d = new File(dir(home), "videos"); d.mkdirs(); return d; }
    static boolean installed(File home) { return new File(dir(home), ".installed").exists() && new File(dir(home), "ltxv_0.9.5_vae_fp8_e4m3fn.safetensors").exists(); }   // the VAE encoder came with the second version of the pack
    static final int W = 512, H = 320, F = 25, FPS = 24;

    private static final String QNN = "https://huggingface.co/timmyboy26/LTX-Video-2B-0.9.8-distilled-QNN/resolve/e8c1f3edb48a1e4faa2e0d6a6c77c7b7222674b0/";
    private static final String GG = "https://huggingface.co/calcuis/ltxv-gguf/resolve/0990265881485e3419312141f6a1974ab2f7a4c6/";
    /** url, local path, size, sha256 */
    private static final String[][] FILES = {
        {QNN + "v73/ltxv_chunk0.bin", "v73/ltxv_chunk0.bin", "532620128", "4cb0250ea0d9aa6eecc67018a401cd8ec8158816e9dfee6f6abcb017e17172d2"},
        {QNN + "v73/ltxv_chunk1.bin", "v73/ltxv_chunk1.bin", "489185184", "8a09b8b6e739822fa6680d3251a445f0198d8eae05ad29c0052b632240b4812a"},
        {QNN + "v73/ltxv_chunk2.bin", "v73/ltxv_chunk2.bin", "489185184", "219a9f43421c7442ba97f8a492cbf8e868c4d6aad83b07317452363cc962a30f"},
        {QNN + "v73/ltxv_chunk3.bin", "v73/ltxv_chunk3.bin", "490160456", "a7af2491106cc4d4ee8618351352a7778e40501c33fd2a750dd20bef7ff71124"},
        {QNN + "v73/ltxv_vae_512x320x25.bin", "v73/ltxv_vae_512x320x25.bin", "635607096", "ee715cd6298be35aaf49d5692c09ff07c4dc51aafac9222441101c8dc32ef84e"},
        {GG + "t5xxl_fp16-q4_0.gguf", "t5xxl_fp16-q4_0.gguf", "2896123072", "6be2b0b7e2de7cf2919340c88cb802a103a997ce46c53131cec91958c1db1af4"},
        {GG + "ltxv_0.9.5_vae_fp8_e4m3fn.safetensors", "ltxv_0.9.5_vae_fp8_e4m3fn.safetensors", "1246943402", "126c45eaef59fbd250520ccb5185b4249389dfdf83f5e2fe7a51596da28ad8ae"},   // VAE encoder for image to video and longer clips (CPU)
        {QNN + "LICENSE.txt", "LICENSE.txt", "-1", ""},
    };
    static final String VAE_ENC = "ltxv_0.9.5_vae_fp8_e4m3fn.safetensors";
    static long size() { long t = 0; for (String[] f : FILES) t += Math.max(0, Long.parseLong(f[2])); return t; }

    static Models.Item item() {
        Models.Item it = new Models.Item(); it.id = ID; it.name = "LTX-Video 2B · " + L.s("видео", "video"); it.video = true;
        it.desc = "Короткие видео по тексту или из картинки: 512×320, от 1 до 15 с, около 30 с на секунду видео на NPU. Экспериментально.";
        it.descEn = "Short videos from text or from a picture: 512×320, 1 to 15 s, about 30 s per second of video on the NPU. Experimental.";
        it.params = "2B"; it.quant = "INT8"; it.author = "Lightricks · LTX-Video 0.9.8 distilled";
        it.size = size(); it.manifest = "";
        return it;
    }

    static void install(File home, Models.Progress pg, AtomicBoolean cancel) throws Exception {
        File d = dir(home); d.mkdirs(); new File(d, ".installed").delete();
        long total = size(), done = 0;
        for (String[] f : FILES) {
            final long before = done; final String name = f[1];
            Models.fetch(f[0], new File(d, f[1]), Long.parseLong(f[2]), f[3], n -> { if (pg != null) pg.on(before + n, total, name); }, cancel);
            done += Math.max(0, Long.parseLong(f[2]));
        }
        try (FileWriter w = new FileWriter(new File(d, "meta.json"))) { w.write(new JSONObject().put("id", ID).put("name", "LTX-Video 2B").toString()); }
        new File(d, ".installed").createNewFile();
    }

    static void delete(File home) { Models.deleteTree(new File(dir(home), "v73")); new File(dir(home), "t5xxl_fp16-q4_0.gguf").delete(); new File(dir(home), VAE_ENC).delete(); new File(dir(home), ".installed").delete(); Models.deleteTree(new File(dir(home), "lib")); }

    /** files/ltx/lib: the QAIRT 2.50 runtime under the names QNN looks for (links into the native lib dir, which may execute) + the VAE latent statistics */
    static void prepare(Context c) throws IOException {
        File d = dir(c.getFilesDir()), lib = new File(d, "lib"); lib.mkdirs();
        String nat = c.getApplicationInfo().nativeLibraryDir;
        String[][] links = {{"libQnnHtp.so", "libqnn250htp.so"}, {"libQnnSystem.so", "libqnn250system.so"}, {"libQnnHtpV73Stub.so", "libqnn250v73stub.so"}, {"libQnnHtpV73Skel.so", "libqnn250v73skel.so"}};
        for (String[] l : links) {
            File link = new File(lib, l[0]), target = new File(nat, l[1]);
            if (!target.exists()) throw new IOException("missing " + target);
            try { if (java.nio.file.Files.isSymbolicLink(link.toPath()) && java.nio.file.Files.readSymbolicLink(link.toPath()).toString().equals(target.getPath())) continue; } catch (IOException ignored) { }
            link.delete(); java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath());   // the native dir moves when the app is updated: relinked every run
        }
        File st = new File(d, "vae_stats.bin");
        if (st.length() != 1024) try (InputStream in = c.getAssets().open("ltx/vae_stats.bin"); OutputStream o = new FileOutputStream(st)) { byte[] b = new byte[4096]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
    }

    interface Phase { void on(String what, int permille); }

    /** frames of a clip of n segments: the first has 25, every next one adds 24 (its first frame repeats the last one of the previous segment) */
    static int frames(int segments) { return F + (F - 1) * (segments - 1); }
    /** seconds offered: 1 = one segment; longer clips chain segments, each starting from the last frame of the one before */
    static final int[] SECONDS = {1, 2, 3, 5, 10, 15};
    static int segments(int seconds) { int n = 1; while (frames(n) < seconds * FPS) n++; return n; }

    /** a child process with a line reader on its stdout */
    static final class Proc {
        static final String EOF = "\u0000eof";
        final Process p; final Writer w; final java.util.concurrent.LinkedBlockingQueue<String> q = new java.util.concurrent.LinkedBlockingQueue<>();
        Proc(ProcessBuilder pb) throws IOException {
            p = pb.start(); w = new OutputStreamWriter(p.getOutputStream(), "UTF-8");
            Thread t = new Thread(() -> { try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) { for (String l; (l = br.readLine()) != null; ) q.add(l.trim()); } catch (IOException ignored) { } q.add(EOF); });
            t.setDaemon(true); t.start();
        }
        void send(String line) throws IOException { w.write(line + "\n"); w.flush(); }
        /** next line starting with want or "error" (other lines go to cb); null when cancelled */
        String until(String want, Line cb, BooleanSupplier cancel) throws Exception {
            while (true) {
                if (cancel.getAsBoolean()) { kill(); return null; }
                String l = q.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS); if (l == null) continue;
                if (l.equals(EOF)) { p.waitFor(); return "exit " + p.exitValue(); }
                if (l.startsWith(want) || l.startsWith("error")) return l;
                cb.on(l);
            }
        }
        void kill() { p.destroy(); }
        void quit() { try { send("q"); send("quit"); w.close(); } catch (IOException ignored) { } try { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroy(); } catch (InterruptedException e) { p.destroy(); } }
    }

    /** per-channel mean (0-2) and standard deviation (3-5) of an RGB24 frame */
    static double[] stats(byte[] rgb) {
        double[] s = new double[6]; long n = rgb.length / 3;
        for (int i = 0; i < rgb.length; i++) s[i % 3] += rgb[i] & 255;
        for (int c = 0; c < 3; c++) s[c] /= n;
        for (int i = 0; i < rgb.length; i++) { double d = (rgb[i] & 255) - s[i % 3]; s[3 + i % 3] += d * d; }
        for (int c = 0; c < 3; c++) s[3 + c] = Math.sqrt(s[3 + c] / n);
        return s;
    }

    /** a picture scaled to cover 512x320 and cropped in the middle, as RGB24 */
    static byte[] fitRgb(android.graphics.Bitmap src) {
        float s = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        int sw = Math.max(W, Math.round(src.getWidth() * s)), sh = Math.max(H, Math.round(src.getHeight() * s));
        android.graphics.Bitmap sc = android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true);
        android.graphics.Bitmap cr = android.graphics.Bitmap.createBitmap(sc, (sw - W) / 2, (sh - H) / 2, W, H);
        int[] px = new int[W * H]; cr.getPixels(px, 0, W, 0, 0, W, H);
        byte[] rgb = new byte[W * H * 3]; for (int i = 0; i < px.length; i++) { rgb[i * 3] = (byte) (px[i] >> 16); rgb[i * 3 + 1] = (byte) (px[i] >> 8); rgb[i * 3 + 2] = (byte) px[i]; }
        if (cr != sc) cr.recycle(); if (sc != src) sc.recycle();
        return rgb;
    }

    /** prompt (+ optional first picture) -> mp4 of about `seconds` in files/ltx/videos, with a .json beside it; throws with the reason */
    static File generate(Context c, String prompt, long seed, android.graphics.Bitmap first, int seconds, Phase ph, BooleanSupplier cancel) throws Exception {
        File home = c.getFilesDir(), d = dir(home), work = new File(d, "work"); work.mkdirs();
        String nat = c.getApplicationInfo().nativeLibraryDir, libs = new File(d, "lib").getPath();
        prepare(c);
        final int segs = segments(seconds), total = frames(segs);
        final long t0 = System.currentTimeMillis();
        final int[] seg = {0};
        // overall progress: text 0-80, then the segments share 80-900
        java.util.function.IntUnaryOperator at = k -> 80 + (int) ((long) (900 - 80) * (seg[0] * 100 + k) / (segs * 100L));
        // 1. text: T5-XXL on the CPU
        ph.on(L.s("Подготовка текста…", "Preparing the text…"), 20);
        File emb = new File(work, "p.emb"), mask = new File(work, "p.mask"); emb.delete();
        ProcessBuilder pb = new ProcessBuilder(nat + "/libltxt5.so", new File(d, "t5xxl_fp16-q4_0.gguf").getPath(), new File(work, "p").getPath(), "6").directory(work);
        pb.redirectError(new File(work, "t5.log"));
        Proc t5 = new Proc(pb); t5.send(prompt.replace('\n', ' ').replace('\r', ' ')); t5.send("q");
        String r = t5.until("done", l -> { }, cancel);
        if (r == null) throw new InterruptedIOException("cancelled");
        if (!r.startsWith("done") || emb.length() != 128L * 4096 * 4) throw new IOException(L.s("T5 не завершился", "T5 did not finish") + " (" + r + ")");
        t5.quit();
        long tText = System.currentTimeMillis() - t0;
        // 2. the VAE encoder (CPU) when a picture starts the clip or the clip has several segments
        Proc enc = null, run = null; File cond = new File(work, "cond.lat"), still = new File(work, "still.rgb");
        final boolean useImg = first != null;
        File all = new File(work, "all.rgb"); all.delete();
        final int frameBytes = W * H * 3;
        try {
            if (useImg || segs > 1) {
                ph.on(L.s("Загрузка кодировщика VAE…", "Loading the VAE encoder…"), 60);
                pb = new ProcessBuilder(nat + "/libltxt5.so", "enc", new File(d, VAE_ENC).getPath(), new File(d, "vae_stats.bin").getPath(), "6").directory(work);
                pb.redirectError(new File(work, "enc.log"));
                enc = new Proc(pb);
                r = enc.until("ready", l -> { }, cancel);
                if (r == null) throw new InterruptedIOException("cancelled");
                if (!r.equals("ready")) throw new IOException(L.s("Кодировщик VAE не запустился", "The VAE encoder did not start") + " (" + r + ", " + tail(new File(work, "enc.log")) + ")");
            }
            try (RandomAccessFile out = new RandomAccessFile(all, "rw")) {
                for (seg[0] = 0; seg[0] < segs; seg[0]++) {
                    final String st = segs > 1 ? L.f("Часть %d из %d · ", "Part %d of %d · ", seg[0] + 1, segs) : "";
                    final boolean condNow = seg[0] > 0 || useImg;
                    if (condNow) {
                        ph.on(st + L.s("Кодирование кадра…", "Encoding the frame…"), at.applyAsInt(2));
                        if (seg[0] == 0) try (FileOutputStream o = new FileOutputStream(still)) { o.write(fitRgb(first)); }
                        cond.delete(); enc.send("img " + still + " " + W + " " + H + " " + cond);
                        r = enc.until("done", l -> { }, cancel);
                        if (r == null) throw new InterruptedIOException("cancelled");
                        if (!r.startsWith("done") || cond.length() != (long) (W / 32) * (H / 32) * 128 * 4) throw new IOException(L.s("Кодировщик VAE не завершился", "The VAE encoder did not finish") + " (" + r + ", " + tail(new File(work, "enc.log")) + ")");
                    }
                    // transformer (8 steps) and VAE decoder on the NPU; the runner stays alive across segments
                    if (run == null) {
                        ph.on(st + L.s("Загрузка модели в NPU…", "Loading the model on the NPU…"), at.applyAsInt(8));
                        pb = new ProcessBuilder(nat + "/libltxrun.so", libs + "/libQnnHtp.so", libs + "/libQnnSystem.so", new File(d, "v73").getPath(), new File(d, "vae_stats.bin").getPath()).directory(work);
                        pb.environment().put("LD_LIBRARY_PATH", libs + ":/system/lib64:/vendor/lib64");
                        pb.environment().put("LTX_MEMFD", "1");   // the 2.50 backend is loaded from memory: next to its file it would find the 2.28 stub of the other models
                        pb.environment().put("ADSP_LIBRARY_PATH", libs + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/vendor/dsp");
                        pb.redirectError(new File(work, "ltxrun.log"));
                        run = new Proc(pb);
                        r = run.until("ready", l -> { }, cancel);
                        if (r == null) throw new InterruptedIOException("cancelled");
                        if (!r.equals("ready")) throw new IOException(L.s("NPU не запустилась", "The NPU did not start") + " (" + r + ", " + tail(new File(work, "ltxrun.log")) + ")");
                    }
                    File rgb = new File(work, "seg.rgb"); rgb.delete();
                    ph.on(st + L.f("Шаг %d/8", "Step %d/8", 1), at.applyAsInt(12));
                    run.send("gen " + emb + " " + mask + " " + (seed + seg[0]) + " " + rgb + " " + (condNow ? cond.getPath() : "-"));
                    r = run.until("done", l -> {
                        if (l.startsWith("step ")) { int k = Integer.parseInt(l.substring(5, l.indexOf('/'))); ph.on(st + (k < 8 ? L.f("Шаг %d/8", "Step %d/8", k + 1) : L.s("Декодирование кадров…", "Decoding the frames…")), at.applyAsInt(12 + 9 * k)); }
                    }, cancel);
                    if (r == null) throw new InterruptedIOException("cancelled");
                    if (!r.startsWith("done") || rgb.length() != (long) frameBytes * F) throw new IOException(L.s("NPU не завершила видео", "The NPU did not finish the video") + " (" + r + ", " + tail(new File(work, "ltxrun.log")) + ")");
                    // append (a later segment's first frame repeats the previous last one) and keep the last frame for the next segment
                    byte[] fb = new byte[frameBytes];
                    // colour drift: every re-encode of the last frame pushes saturation a little; a later segment is mapped (per channel mean / std)
                    // so its first frame matches the frame it started from, and the whole segment follows
                    float[] gain = {1, 1, 1}, off = {0, 0, 0};
                    if (condNow && (seg[0] > 0 || useImg)) {
                        byte[] ref = java.nio.file.Files.readAllBytes(still.toPath()), f0 = new byte[frameBytes];
                        try (RandomAccessFile in = new RandomAccessFile(rgb, "r")) { in.readFully(f0); }
                        double[] a = stats(ref), b = stats(f0);
                        for (int ch = 0; ch < 3; ch++) { gain[ch] = (float) Math.max(0.5, Math.min(2.0, a[3 + ch] / Math.max(1e-3, b[3 + ch]))); off[ch] = (float) (a[ch] - gain[ch] * b[ch]); }
                    }
                    boolean corr = gain[0] != 1 || gain[1] != 1 || gain[2] != 1 || off[0] != 0 || off[1] != 0 || off[2] != 0;
                    try (RandomAccessFile in = new RandomAccessFile(rgb, "r")) {
                        for (int f = seg[0] == 0 ? 0 : 1; f < F; f++) {
                            in.seek((long) f * frameBytes); in.readFully(fb);
                            if (corr) for (int i = 0; i < frameBytes; i++) { int ch = i % 3; int v = Math.round((fb[i] & 255) * gain[ch] + off[ch]); fb[i] = (byte) (v < 0 ? 0 : v > 255 ? 255 : v); }
                            out.write(fb);
                        }
                    }
                    try (FileOutputStream o = new FileOutputStream(still)) { o.write(fb); }
                    rgb.delete();
                }
            }
        } finally {
            if (run != null) run.quit();
            if (enc != null) enc.quit();
        }
        // 3. MP4
        ph.on(L.s("Сохранение MP4…", "Saving the MP4…"), 960);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
        File mp4 = new File(outDir(home), "ltx_" + stamp + ".mp4");
        encode(all, mp4, total);
        all.delete();
        long ms = System.currentTimeMillis() - t0;
        try (FileWriter jw = new FileWriter(new File(mp4.getPath().replace(".mp4", ".json")))) {
            jw.write(new JSONObject().put("prompt", prompt).put("seed", seed).put("model", "LTX-Video 2B 0.9.8 distilled").put("width", W).put("height", H).put("frames", total).put("fps", FPS)
                .put("segments", segs).put("from_image", useImg).put("ms", ms).put("text_ms", tText).toString());
        }
        return mp4;
    }

    interface Line { void on(String l); }
    /** reads stdout lines until one starts with want or "error"; null when cancelled (the process is killed) */
    static String waitFor(Process p, String want, Line cb, BooleanSupplier cancel) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        final String[] res = {null}; final boolean[] end = {false};
        Thread t = new Thread(() -> {
            try { for (String l; (l = br.readLine()) != null; ) { l = l.trim(); if (l.startsWith(want) || l.startsWith("error")) { synchronized (res) { res[0] = l; } break; } cb.on(l); } }
            catch (IOException ignored) { }
            synchronized (res) { end[0] = true; }
        });
        t.start();
        while (true) {
            synchronized (res) { if (res[0] != null || end[0]) break; }
            if (cancel.getAsBoolean()) { p.destroy(); t.join(2000); return null; }
            Thread.sleep(100);
        }
        synchronized (res) { if (res[0] == null) { p.waitFor(); return "exit " + p.exitValue(); } }
        return res[0];
    }
    static String tail(File f) { try { String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim(); return s.substring(Math.max(0, s.length() - 300)); } catch (Exception e) { return ""; } }

    /** RGB24 frames -> H.264 MP4 with the hardware encoder */
    static void encode(File rgb, File out, int frames) throws Exception {
        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000); fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS); fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start();
        MediaMuxer mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1; boolean started = false, inDone = false; int frame = 0;
        byte[] buf = new byte[W * H * 3]; MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try (FileInputStream in = new FileInputStream(rgb)) {
            while (true) {
                if (!inDone) {
                    int ii = enc.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        long pts = frame * 1_000_000L / FPS;
                        if (frame >= frames || in.read(buf) != buf.length) { enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { Image img = enc.getInputImage(ii); toYuv(buf, img); enc.queueInputBuffer(ii, 0, W * H * 3 / 2, pts, 0); frame++; }
                    }
                }
                int oi = enc.dequeueOutputBuffer(bi, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(enc.getOutputFormat()); mux.start(); started = true; }
                else if (oi >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    if (bi.size > 0 && started) { ob.position(bi.offset); ob.limit(bi.offset + bi.size); mux.writeSampleData(track, ob, bi); }
                    enc.releaseOutputBuffer(oi, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally { enc.stop(); enc.release(); if (started) mux.stop(); mux.release(); }
    }

    /** one RGB24 frame into the encoder's YUV 4:2:0 image (BT.601 limited range), whatever its plane layout */
    static void toYuv(byte[] rgb, Image img) {
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int yr = pl[0].getRowStride(), yp = pl[0].getPixelStride(), ur = pl[1].getRowStride(), up = pl[1].getPixelStride(), vr = pl[2].getRowStride(), vp = pl[2].getPixelStride();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = (y * W + x) * 3, R = rgb[i] & 255, G = rgb[i + 1] & 255, B = rgb[i + 2] & 255;
            yb.put(y * yr + x * yp, (byte) Math.min(255, Math.max(0, ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16)));
            if ((y & 1) == 0 && (x & 1) == 0) {
                int r = 0, g = 0, b = 0;   // average of the 2x2 block
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) { int j = ((y + dy) * W + x + dx) * 3; r += rgb[j] & 255; g += rgb[j + 1] & 255; b += rgb[j + 2] & 255; }
                r >>= 2; g >>= 2; b >>= 2;
                ub.put((y / 2) * ur + (x / 2) * up, (byte) Math.min(255, Math.max(0, ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128)));
                vb.put((y / 2) * vr + (x / 2) * vp, (byte) Math.min(255, Math.max(0, ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128)));
            }
        }
    }

    /** copy to the phone's gallery: Movies/HexAnima */
    static Uri export(Context c, File mp4) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, mp4.getName()); v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HexAnima");
        Uri u = c.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("MediaStore");
        try (OutputStream o = c.getContentResolver().openOutputStream(u); InputStream in = new FileInputStream(mp4)) { byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
        return u;
    }
}
