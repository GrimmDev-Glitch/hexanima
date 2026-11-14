package com.hexanima.npu;

import android.graphics.Bitmap;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** MOSS-Image (5B DiT + Qwen3.5-9B text encoder + FLUX.2 VAE) on the NPU: files under files/moss, one picture per moss.sh call. */
final class Moss {
    static final String PREFIX_ID = "moss";
    static boolean is(String id) { return id != null && id.toLowerCase(java.util.Locale.ROOT).contains(PREFIX_ID); }
    static File dir(File home) { return new File(home, "moss"); }
    /** Optional 8-step DMD adapter: separate glue and overlay files shared by base MOSS and RealSkin. */
    static final String ID8 = "moss-sft-q5-8";
    static boolean is8(String id) { return ID8.equals(id); }
    static void prepare8Install(File home) throws IOException {
        File overlays = new File(dir(home), "lora8");
        if (java.nio.file.Files.isSymbolicLink(overlays.toPath()) && !java.nio.file.Files.deleteIfExists(overlays.toPath())) throw new IOException("cannot replace stale MOSS DMD overlay link");
        if (!overlays.isDirectory() && !overlays.mkdirs()) throw new IOException("cannot create MOSS DMD overlay directory");
    }
    static boolean installed8(File home) {
        File m = dir(home), glue = new File(m, "glue8.bin"), overlays = new File(m, "lora8");
        if (!glue.isFile() || glue.length() != 41083552L) return false;
        for (int i = 0; i < 42; i++) { File f = new File(overlays, String.format(java.util.Locale.ROOT, "blk%02d.ovl", i)); if (!f.isFile() || f.length() != 7974580L) return false; }
        return true;
    }
    static void delete8(File home) { File m = dir(home); new File(m, "glue8.bin").delete(); deleteTreeNoFollow(new File(m, "lora8")); }
    private static void deleteTreeNoFollow(File f) {
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) { f.delete(); return; }
        File[] children = f.listFiles(); if (children != null) for (File child : children) deleteTreeNoFollow(child);
        f.delete();
    }
    /** RealSkin MOSS is a DiT/refiner/glue variant that reuses the installed MOSS text encoder, tokenizer and VAE. */
    static final String IDRS = "moss-realskin-1";
    static final String IDHM = "moss-harem-11";
    static boolean isRs(String id) { if (id == null) return false; String s = id.toLowerCase(java.util.Locale.ROOT); return IDRS.equals(s) || s.startsWith("moss-realskin-"); }
    static boolean isHm(String id) { if (id == null) return false; String s = id.toLowerCase(java.util.Locale.ROOT); return IDHM.equals(s) || s.startsWith("moss-harem-"); }
    static boolean isVariant(String id) { return isRs(id) || isHm(id); }
    static String variantKey(String id) { return isRs(id) ? "rs" : isHm(id) ? "hm" : ""; }
    static File variantDir(File home, String id) { return new File(dir(home), variantKey(id)); }
    static String variantId(File home, String key) {
        try { return readText(new File(new File(dir(home), key), ".model_id")).trim(); } catch (Exception e) { return ""; }
    }
    static boolean installedRs(File home) { return installedVariant(home, "rs"); }
    static boolean installedHm(File home) { return installedVariant(home, "hm"); }
    static boolean installedVariant(File home, String key) {
        File r = new File(dir(home), key);
        return !variantId(home, key).isEmpty() && new File(r, "dit/blk41.bin").isFile() && new File(r, "ref/ref1.bin").isFile() && new File(r, "glue.bin").isFile();
    }
    static String readText(File f) throws IOException { try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[(int) f.length()]; int n = in.read(b); return new String(b, 0, Math.max(0, n), "UTF-8"); } }
    /** Build a private runner view with variant weights and symlinks to shared base-model files. */
    static File variantView(File home, String id) throws IOException {
        String key = variantKey(id); if (key.isEmpty()) throw new IOException("unknown MOSS variant " + id);
        File m = dir(home), v = new File(m, "v" + key), variant = variantDir(home, id);
        if (!installedVariant(home, key)) throw new IOException("MOSS variant is incomplete: " + key);
        if (!v.isDirectory() && !v.mkdirs()) throw new IOException("cannot create " + v);
        String[][] links = {{"te", "te"}, {"embd.q4k", "embd.q4k"}, {"vae", "vae"},
            {"vae_v73", "vae_v73"},
            {"tok_merges.txt", "tok_merges.txt"}, {"tok_special.txt", "tok_special.txt"}, {"tok_vocab.txt", "tok_vocab.txt"},
            {"glue8.bin", "glue8.bin"}, {"lora8", "lora8"},
            {"dit", key + "/dit"}, {"ref", key + "/ref"}, {"glue.bin", key + "/glue.bin"}};
        for (String[] pair : links) {
            File target = new File(m, pair[1]);
            if (!target.exists() && (pair[0].equals("vae_v73") || pair[0].equals("glue8.bin") || pair[0].equals("lora8"))) continue;
            if (!target.exists()) throw new IOException("MOSS variant missing shared file " + target.getName());
            java.nio.file.Path link = new File(v, pair[0]).toPath();
            try {
                java.nio.file.Files.deleteIfExists(link);
                java.nio.file.Files.createSymbolicLink(link, target.toPath());
            } catch (Exception e) { throw new IOException("cannot link " + pair[0], e); }
        }
        return v;
    }
    static boolean installed(File home) { return new File(dir(home), ".installed").exists(); }

    /** QAIRT's HTP context is built for a specific Hexagon generation, so distribute the tested SM8550 decoder with the APK. */
    static void ensureV73Vae(File home, AssetManager assets) throws IOException {
        if (!Compat.dspArch().equals("v73")) return;
        File out = new File(new File(dir(home), "vae_v73"), "vae_512x512.bin");
        if (out.isFile() && out.length() == 64032744L) return;
        File parent = out.getParentFile(); if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("cannot create " + parent);
        File part = new File(parent, out.getName() + ".part"); part.delete();
        try (InputStream in = assets.open("moss/vae_v73/vae_512x512.bin"); FileOutputStream stream = new FileOutputStream(part)) {
            byte[] b = new byte[1 << 20]; for (int n; (n = in.read(b)) >= 0; ) if (n > 0) stream.write(b, 0, n);
        } catch (IOException e) { part.delete(); throw new IOException(L.s("Falta el contexto MOSS V73; compila con tools/stage_jnilibs.sh.", "MOSS V73 context is missing; stage it with tools/stage_jnilibs.sh."), e); }
        if (part.length() != 64032744L || !part.renameTo(out)) { part.delete(); out.delete(); throw new IOException("invalid MOSS V73 VAE context"); }
    }

    /** size presets (W×H): around 1 Mpx is what the 4-step adapter was trained on (1024², 896×1152, 832×1216, 768×1344 and their landscape twins); smaller ones are faster */
    static final int[][] PRESETS = {{512, 512}, {640, 640}, {768, 768}, {1024, 1024}, {512, 768}, {768, 512}, {576, 1024}, {1024, 576}, {640, 1152}, {1152, 640}, {768, 1024}, {1024, 768},
        {896, 1152}, {1152, 896}, {832, 1216}, {1216, 832}, {768, 1344}, {1344, 768}};
    /** any size with sides that are multiples of 16, at least 256, at most 6144 patches (the DiT attention must fit the 8 MB VTCM); null = fine */
    static String sizeError(int w, int h) {
        if (w < 256 || h < 256) return L.s("Размер: минимум 256 по каждой стороне", "Size: at least 256 on each side");
        if (w % 16 != 0 || h % 16 != 0) return L.s("Ширина и высота должны быть кратны 16", "Width and height must be multiples of 16");
        if ((w / 16) * (h / 16) > 6144) return L.s("Слишком большой размер: максимум 6144 патча (например 1536×1024)", "Too large: at most 6144 patches (e.g. 1536×1024)");
        return null;
    }

    /** tokens of the prompt (chat template included) -> ids file for mossrun */
    static void writeIds(File home, String prompt, File out) throws Exception {
        QwenTok t = new QwenTok(dir(home).getPath());
        int[] ids = t.encodePrompt(prompt);
        if (ids.length > 480) ids = java.util.Arrays.copyOf(ids, 480);   // mossrun takes at most 512 tokens
        ByteBuffer b = ByteBuffer.allocate(ids.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : ids) b.putInt(i);
        try (FileOutputStream o = new FileOutputStream(out)) { o.write(b.array()); }
    }

    /** raw fp32 RGB [3][h][w] in [-1,1] -> PNG bytes */
    static byte[] png(File raw, int w, int h) throws IOException {
        byte[] bytes = Models.readBytes(raw);
        if (bytes.length != 3 * w * h * 4) throw new IOException("decoder output has " + bytes.length + " bytes");
        java.nio.FloatBuffer f = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int[] px = new int[w * h]; int n = w * h;
        float[][] ch = new float[3][n];   // 0..255, not rounded yet: the 4-px lattice of the adapter is removed first (see DeGrid)
        for (int k = 0; k < 3; k++) for (int i = 0; i < n; i++) ch[k][i] = (f.get(k * n + i) + 1f) * 127.5f;   // not clamped: the lattice dips below black in dark areas and must stay a clean sinusoid for the filter
        for (float[] z : ch) DeGrid.apply(z, w, h);
        for (int i = 0; i < n; i++) px[i] = 0xFF000000 | (c8(ch[0][i]) << 16) | (c8(ch[1][i]) << 8) | c8(ch[2][i]);
        Bitmap bm = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }
    private static int c8(float v) { int x = Math.round(v); return x < 0 ? 0 : x > 255 ? 255 : x; }
    private static int c(float v) { int x = Math.round((Math.max(-1f, Math.min(1f, v)) + 1f) * 127.5f); return x < 0 ? 0 : x > 255 ? 255 : x; }
}
