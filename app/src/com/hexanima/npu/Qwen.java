package com.hexanima.npu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Qwen-Image-2.1 (Turbo, 4-step Lightning LoRA folded in the DiT pack) on the NPU: files under files/qwen, one picture per qwen.sh call; with a source photo it edits it. */
final class Qwen {
    static final String PREFIX_ID = "qwen";
    static final String MODEL_ID = "qwen-image-21-turbo";
    static final String OVERLAY_BASE_ID = "hexanima-qwen-image-2.1-turbo-v1";
    static final long MAX_OVERLAY_IMPORT = 768L << 20;
    // V73 sidecar generated from qwen/vae_q/build_v73_contexts.sh. Keep these
    // exact sizes in sync with the verified manifest emitted by that script.
    static final long V73_VAE_SIZE = 527_971_856L;
    static final long V73_ENCODER_SIZE = 165_709_704L;
    static final String V73_VAE_SHA256 = "b7776f55405e6f96e4a4bfa5a55b09710711aa3f98a6f4d345b6bceeeab1a295";
    static final String V73_ENCODER_SHA256 = "3bd61a45bf5f958f793c75d29f61aa8cab13ac9b67fe083a7abe802813a1890a";
    static final long V73_RUNTIME_SIZE = V73_VAE_SIZE + V73_ENCODER_SIZE;
    static boolean is(String id) { return id != null && id.startsWith(PREFIX_ID); }
    static File dir(File home) { return new File(home, "qwen"); }
    static File lorasDir(File home) { return new File(dir(home), "loras"); }
    static File selectedOverlay(File home, String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,80}") || name.equals(".") || name.equals("..")) return null;
        File overlay = new File(lorasDir(home), name);
        return validOverlay(overlay) ? overlay : null;
    }
    static boolean validOverlay(File overlay) {
        try {
            JSONObject m = new JSONObject(Models.read(new File(overlay, "manifest.json")));
            if (!m.optString("format").equals("hexanima-qwen-overlay-v1") || m.optInt("chunk_bytes") != 4096 || m.optInt("first", -1) != 0 || m.optInt("last", -1) != 31) return false;
            JSONArray blocks = m.getJSONArray("blocks");
            if (blocks.length() != 32 || !new File(overlay, "glue.ovl").isFile()) return false;
            for (int i = 0; i < 32; i++) {
                JSONObject b = blocks.getJSONObject(i);
                File f = new File(overlay, String.format(java.util.Locale.US, "blk%02d.ovl", i));
                if (b.optInt("block", -1) != i || !f.isFile() || f.length() < 4 || f.length() != b.optLong("bytes", -1)) return false;
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    static String overlayTriggers(File overlay) {
        try {
            JSONObject manifest = new JSONObject(Models.read(new File(overlay, "manifest.json")));
            JSONObject adapter = manifest.optJSONObject("adapter");
            if (adapter == null) return "";
            JSONArray words = adapter.optJSONArray("trigger_words");
            if (words == null) return "";
            List<String> clean = new java.util.ArrayList<>();
            for (int i = 0; i < words.length(); i++) {
                String word = words.optString(i, "").trim();
                if (!word.isEmpty() && !clean.contains(word)) clean.add(word);
            }
            return String.join(", ", clean);
        } catch (Exception ignored) { return ""; }
    }

    /** Import only a flat ZIP emitted by the Qwen V73 converter; validate each overlay and pinned base hash before activation. */
    static String importOverlayZip(Context context, File home, Uri uri, String displayName) throws Exception {
        File model = dir(home);
        if (!new File(model, ".installed").isFile()) throw new IOException(L.s("Instala Qwen-Image 2.1 Turbo antes de importar una LoRA", "Install Qwen-Image 2.1 Turbo before importing a LoRA"));
        JSONObject meta = new JSONObject(Models.read(new File(model, "meta.json")));
        if (!MODEL_ID.equals(meta.optString("id"))) throw new IOException(L.s("El pack instalado no es la base Qwen V73 esperada", "The installed pack is not the expected Qwen V73 base"));
        if (home.getUsableSpace() < MAX_OVERLAY_IMPORT + (64L << 20)) throw new IOException(L.s("Falta espacio libre para importar el pack LoRA", "Not enough free space to import the LoRA pack"));

        JSONObject base;
        try (InputStream in = context.getAssets().open("qwen_v73_base_manifest.json")) { base = new JSONObject(readText(in)); }
        if (!OVERLAY_BASE_ID.equals(base.optString("base_pack_id")) || !MODEL_ID.equals(base.optString("model_id"))) throw new IOException(L.s("El manifiesto base de Qwen no es válido", "The pinned Qwen base manifest is invalid"));
        JSONArray baseBlocks = base.getJSONArray("blocks");
        if (baseBlocks.length() != 32) throw new IOException(L.s("El manifiesto base de Qwen no contiene 32 bloques", "The pinned Qwen base manifest does not contain 32 blocks"));

        File root = lorasDir(home); root.mkdirs();
        File tmp = new File(root, ".import-" + UUID.randomUUID().toString().replace("-", ""));
        if (!tmp.mkdirs()) throw new IOException("cannot create Qwen LoRA import directory");
        try {
            Set<String> seen = new HashSet<>(); long total = 0; int entries = 0;
            try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new IOException(L.s("No se pudo abrir el archivo ZIP", "Could not open the ZIP archive"));
                try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                    for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                        String name = entry.getName(); entries++;
                        if (entries > 34 || entry.isDirectory() || name.contains("/") || name.contains("\\") || !allowedOverlayEntry(name)) throw new IOException(L.f("El ZIP contiene una entrada no permitida: %s", "The ZIP contains an unsupported entry: %s", name));
                        if (!seen.add(name)) throw new IOException(L.f("El ZIP contiene una entrada duplicada: %s", "The ZIP contains a duplicate entry: %s", name));
                        File out = new File(tmp, name);
                        try (FileOutputStream stream = new FileOutputStream(out)) {
                            byte[] buffer = new byte[1 << 16];
                            for (int n; (n = zip.read(buffer)) > 0; ) {
                                total += n; if (total > MAX_OVERLAY_IMPORT) throw new IOException(L.s("El pack LoRA supera el límite de importación", "The LoRA pack exceeds the import size limit"));
                                stream.write(buffer, 0, n);
                            }
                            stream.getFD().sync();
                        }
                        zip.closeEntry();
                    }
                }
            }
            if (entries != 34 || !seen.contains("manifest.json") || !seen.contains("glue.ovl")) throw new IOException(L.s("El ZIP debe incluir el manifiesto, glue y los 32 bloques", "The ZIP must contain the manifest, glue and all 32 blocks"));
            verifyImportedOverlay(tmp, baseBlocks, base.optString("glue_sha256"));

            String requested = displayName == null ? "" : displayName.replaceFirst("(?i)\\.zip$", "");
            String clean = requested.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[._-]+", "");
            if (clean.isEmpty()) clean = "qwen_lora";
            if (clean.length() > 72) clean = clean.substring(0, 72);
            String chosen = clean; int suffix = 2;
            while (new File(root, chosen).exists()) chosen = clean + "-" + suffix++;
            File dest = new File(root, chosen);
            if (!tmp.renameTo(dest)) throw new IOException("cannot finalize imported Qwen overlay");
            return chosen;
        } catch (Exception e) { Models.deleteTree(tmp); throw e; }
    }

    private static boolean allowedOverlayEntry(String name) {
        if (name.equals("manifest.json") || name.equals("glue.ovl")) return true;
        if (!name.matches("blk[0-3][0-9]\\.ovl")) return false;
        int block = Integer.parseInt(name.substring(3, 5)); return block < 32;
    }

    private static void verifyImportedOverlay(File overlay, JSONArray baseBlocks, String baseGlueSha) throws Exception {
        JSONObject m = new JSONObject(Models.read(new File(overlay, "manifest.json")));
        if (!m.optString("format").equals("hexanima-qwen-overlay-v1") || m.optInt("chunk_bytes") != 4096 || m.optInt("first", -1) != 0 || m.optInt("last", -1) != 31 || !OVERLAY_BASE_ID.equals(m.optString("base_pack_id"))) throw new IOException(L.s("Este overlay corresponde a otro pack o formato de Qwen", "This overlay was made for another Qwen pack or format"));
        JSONArray blocks = m.getJSONArray("blocks");
        if (blocks.length() != 32) throw new IOException(L.s("El overlay debe incluir los 32 bloques de Qwen", "The overlay must include all 32 Qwen blocks"));
        for (int i = 0; i < 32; i++) {
            JSONObject block = blocks.getJSONObject(i), expected = baseBlocks.getJSONObject(i);
            File f = new File(overlay, String.format(Locale.US, "blk%02d.ovl", i));
            if (block.optInt("block", -1) != i || !block.optString("base_sha256").equals(expected.optString("sha256")) || !f.isFile() || f.length() != block.optLong("bytes", -1) || !Models.sha256(f).equalsIgnoreCase(block.optString("overlay_sha256"))) throw new IOException(L.f("Falló la verificación de base o hash del bloque Qwen %d", "Qwen block %d failed its base or hash check", i));
        }
        JSONObject glue = m.getJSONObject("glue"); File gf = new File(overlay, "glue.ovl");
        if (!glue.optString("base_sha256").equalsIgnoreCase(baseGlueSha) || !gf.isFile() || gf.length() != glue.optLong("bytes", -1) || !Models.sha256(gf).equalsIgnoreCase(glue.optString("overlay_sha256"))) throw new IOException(L.s("Falló la verificación de base o hash del glue Qwen", "Qwen glue failed its base or hash check"));
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[1 << 14];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toString("UTF-8");
    }
    static boolean installed(File home) {
        File root = dir(home);
        if (!new File(root, ".installed").exists()) return false;
        if (!Compat.dspArch().equals("v73")) return true;
        File vae = new File(root, "vae_v73/vae_t32.bin"), enc = new File(root, "vae_v73/enc_t32.bin");
        return vae.isFile() && vae.length() == V73_VAE_SIZE && enc.isFile() && enc.length() == V73_ENCODER_SIZE;
    }
    static File editSrc(File home) { return new File(dir(home), "edit_src.png"); }
    static File editMask(File home) { return new File(dir(home), "edit_mask.png"); }
    static File editDir(File home) { return new File(dir(home), "edit"); }

    /** size presets (W×H): the patch count (W/16·H/16) must be a multiple of 32 */
    static final int[][] PRESETS = {{512, 512}, {640, 640}, {768, 768}, {1024, 1024}, {512, 768}, {768, 512}, {640, 960}, {960, 640}, {576, 1024}, {1024, 576}, {768, 1024}, {1024, 768}, {896, 1152}, {1152, 896}};
    static final int MAX_KEYS = 6800;   // the attention of one DiT step holds target + condition + text tokens in the 8 MB VTCM
    static String sizeError(int w, int h) {
        if (w < 256 || h < 256) return L.s("Размер: минимум 256 по каждой стороне", "Size: at least 256 on each side");
        if (w % 16 != 0 || h % 16 != 0) return L.s("Ширина и высота должны быть кратны 16", "Width and height must be multiples of 16");
        int t = (w / 16) * (h / 16);
        if (t % 32 != 0) return L.f("Число патчей (%d) должно делиться на 32, например 640×640, 1024×1024, 704×832", "Patch count (%d) must be divisible by 32, e.g. 640×640, 1024×1024, 704×832", t);
        if (t > 4608) return L.s("Слишком большой размер: максимум 4608 патчей (например 1152×1024)", "Too large: at most 4608 patches (e.g. 1152×1024)");
        return null;
    }

    private static void writeIds(int[] ids, File out) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(ids.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : ids) b.putInt(i);
        try (FileOutputStream o = new FileOutputStream(out)) { o.write(b.array()); }
    }

    /** text-to-image tokens -> ids file; returns the number of leading system tokens that qwenrun drops */
    static int writeIds(File home, String prompt, File out) throws Exception {
        QwenTok t = new QwenTok(dir(home).getPath());
        int[] ids = t.encodeQwenImage(prompt), drop = {t.dropQwenImage()};
        if (ids.length > 480) { ids = java.util.Arrays.copyOf(ids, 480); }   // qwenrun takes at most 2048 tokens; 480 keeps the encoder quick
        writeIds(ids, out); return drop[0];
    }

    /** decode the picked photo (EXIF rotation applied) at most 2048 px on the long side, keep it as files/qwen/edit_src.png */
    static Bitmap importPhoto(android.content.ContentResolver cr, Uri uri, File home) throws Exception {
        Bitmap bm = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri), (d, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight(), m = Math.max(w, h);
            if (m > 2048) d.setTargetSize(Math.round(w * 2048f / m), Math.round(h * 2048f / m));
            d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        Bitmap result = storePhoto(bm, home);
        if (result != bm) bm.recycle();
        return result;
    }

    /** Store a generated bitmap as the next edit source, with the same size limit as a gallery import. */
    static Bitmap storePhoto(Bitmap input, File home) throws IOException {
        int w = input.getWidth(), h = input.getHeight(), m = Math.max(w, h);
        Bitmap bm = m > 2048 ? Bitmap.createScaledBitmap(input, Math.round(w * 2048f / m), Math.round(h * 2048f / m), true) : input;
        dir(home).mkdirs();
        editMask(home).delete();
        try (FileOutputStream o = new FileOutputStream(editSrc(home))) { bm.compress(Bitmap.CompressFormat.PNG, 100, o); }
        return bm;
    }

    static final class DetailCrop {
        Bitmap image; int left, top, right, bottom;
        int width() { return right - left; }
        int height() { return bottom - top; }
        void recycle() { if (image != null && !image.isRecycled()) image.recycle(); image = null; }
    }

    /** Crop around the painted area with generous context; null means the mask is too broad for a detail pass. */
    static DetailCrop detailCrop(Bitmap source, File maskFile) {
        Bitmap mask = BitmapFactory.decodeFile(maskFile.getPath()); if (mask == null) return null;
        try {
            int mw = mask.getWidth(), mh = mask.getHeight(), minX = mw, minY = mh, maxX = -1, maxY = -1;
            int[] row = new int[mw];
            for (int y = 0; y < mh; y++) {
                mask.getPixels(row, 0, mw, 0, y, mw, 1);
                for (int x = 0; x < mw; x++) if (((row[x] >> 16) & 255) > 127) { minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); }
            }
            if (maxX < minX || maxY < minY) return null;
            int sx0 = (int)Math.floor(minX * source.getWidth() / (double)mw), sy0 = (int)Math.floor(minY * source.getHeight() / (double)mh);
            int sx1 = (int)Math.ceil((maxX + 1) * source.getWidth() / (double)mw), sy1 = (int)Math.ceil((maxY + 1) * source.getHeight() / (double)mh);
            int bw = Math.max(1, sx1 - sx0), bh = Math.max(1, sy1 - sy0);
            if ((long)bw * bh > (long)source.getWidth() * source.getHeight() * .55) return null;
            int pad = Math.max(48, Math.max(bw, bh));
            DetailCrop c = new DetailCrop();
            c.left = Math.max(0, sx0 - pad); c.top = Math.max(0, sy0 - pad);
            c.right = Math.min(source.getWidth(), sx1 + pad); c.bottom = Math.min(source.getHeight(), sy1 + pad);
            if (c.width() < 64 || c.height() < 64 || (long)c.width() * c.height() > (long)source.getWidth() * source.getHeight() * .75) return null;
            c.image = Bitmap.createBitmap(source, c.left, c.top, c.width(), c.height());
            return c;
        } finally { mask.recycle(); }
    }

    /** Select a supported Qwen size close to the crop's aspect ratio at roughly 0.4 megapixels. */
    static int[] detailSize(int cw, int ch) {
        double ratio = cw / (double)ch, best = Double.POSITIVE_INFINITY; int[] pick = PRESETS[0];
        for (int[] s : PRESETS) { if (s[0] * s[1] > 800000) continue; double score = Math.abs(Math.log((s[0] / (double)s[1]) / ratio)) + Math.abs(s[0] * (double)s[1] - 393216) / 5e7; if (score < best) { best = score; pick = s; } }
        return new int[]{pick[0], pick[1]};
    }

    /** Apply a generated detail crop only under the mask and keep the source resolution and untouched pixels. */
    static byte[] applyDetailMask(byte[] generatedPng, Bitmap source, File maskFile, DetailCrop crop, int genW, int genH, int outW, int outH) throws IOException {
        Bitmap generated = BitmapFactory.decodeByteArray(generatedPng, 0, generatedPng.length), mask = BitmapFactory.decodeFile(maskFile.getPath());
        Bitmap result = null, detail = null, maskFit = null;
        try {
            if (generated == null || mask == null) throw new IOException("cannot decode Qwen detail image");
            int sw = source.getWidth(), sh = source.getHeight();
            int x = Math.max(0, Math.min(sw - 1, crop.left)), y = Math.max(0, Math.min(sh - 1, crop.top));
            int rw = Math.max(1, Math.min(sw - x, crop.width())), rh = Math.max(1, Math.min(sh - y, crop.height()));
            detail = Bitmap.createScaledBitmap(generated, rw, rh, true);
            maskFit = mask.getWidth() == sw && mask.getHeight() == sh ? mask : Bitmap.createScaledBitmap(mask, sw, sh, true);
            result = compositeMask(source, maskFit, detail, x, y);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); result.compress(Bitmap.CompressFormat.PNG, 100, bytes); return bytes.toByteArray();
        } finally {
            if (generated != null && !generated.isRecycled()) generated.recycle(); if (mask != null && !mask.isRecycled()) mask.recycle();
            if (maskFit != null && maskFit != mask && !maskFit.isRecycled()) maskFit.recycle();
            if (detail != null && detail != generated && !detail.isRecycled()) detail.recycle();
            if (result != null && !result.isRecycled()) result.recycle();
        }
    }

    /** Keep the imported photo's dimensions and exact unmasked pixels after a full-frame Qwen edit. */
    static byte[] applyEditMask(byte[] generatedPng, Bitmap source, File maskFile, int w, int h) throws IOException {
        Bitmap generated = BitmapFactory.decodeByteArray(generatedPng, 0, generatedPng.length);
        Bitmap mask = BitmapFactory.decodeFile(maskFile.getPath());
        Bitmap result = null, editFit = null, maskFit = null;
        try {
            if (generated == null || mask == null) throw new IOException("cannot decode Qwen image mask");
            if (generated.getWidth() != w || generated.getHeight() != h) throw new IOException("Qwen image size does not match mask");
            int sw = source.getWidth(), sh = source.getHeight();
            float scale = Math.max(w / (float)sw, h / (float)sh);
            int rw = Math.max(1, Math.min(sw, Math.round(w / scale))), rh = Math.max(1, Math.min(sh, Math.round(h / scale)));
            int x = (sw - rw) / 2, y = (sh - rh) / 2;
            editFit = Bitmap.createScaledBitmap(generated, rw, rh, true);
            maskFit = mask.getWidth() == sw && mask.getHeight() == sh ? mask : Bitmap.createScaledBitmap(mask, sw, sh, true);
            result = compositeMask(source, maskFit, editFit, x, y);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            result.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            return bytes.toByteArray();
        } finally {
            if (generated != null && !generated.isRecycled()) generated.recycle();
            if (editFit != null && editFit != generated && !editFit.isRecycled()) editFit.recycle();
            if (maskFit != null && maskFit != mask && !maskFit.isRecycled()) maskFit.recycle();
            if (mask != null && !mask.isRecycled()) mask.recycle();
            if (result != null && !result.isRecycled()) result.recycle();
        }
    }

    /** Composite the painted area with a soft edge: the brush is grown slightly and blurred so no hard seam shows, and the edit's
     *  colour is matched to the photo along the border (the VAE round trip can shift tone a little). Pixels outside the feathered
     *  brush stay byte-for-byte from source. */
    private static Bitmap compositeMask(Bitmap source, Bitmap mask, Bitmap edit, int left, int top) throws IOException {
        int w = source.getWidth(), h = source.getHeight(), rw = edit.getWidth(), rh = edit.getHeight();
        if (mask.getWidth() != w || mask.getHeight() != h || left < 0 || top < 0 || left + rw > w || top + rh > h)
            throw new IOException("Qwen mask/output geometry does not match the imported photo");
        // feather on a copy of at most 1024 px (a full-size photo can be 12 Mpx), then sample it bilinearly; the fine brush stays the floor
        float fs = Math.min(1f, 1024f / Math.max(w, h)); int fw = Math.max(1, Math.round(w * fs)), fh = Math.max(1, Math.round(h * fs));
        Bitmap small = fs < 1 ? Bitmap.createScaledBitmap(mask, fw, fh, true) : mask;
        float[] fm = featherMask(small, Math.max(2, Math.round(Math.max(fw, fh) * .006f)));
        if (small != mask) small.recycle();
        int[] maskRow = new int[w];
        float[] a = new float[rw * rh];   // indexed relative to (left, top)
        for (int y = top; y < top + rh; y++) {
            mask.getPixels(maskRow, 0, w, 0, y, w, 1);
            float sy = Math.max(0, Math.min(fh - 1, (y + .5f) * fh / h - .5f)); int y0 = (int) sy, y1 = Math.min(fh - 1, y0 + 1); float ty = sy - y0;
            for (int x = left; x < left + rw; x++) {
                float sx = Math.max(0, Math.min(fw - 1, (x + .5f) * fw / w - .5f)); int x0 = (int) sx, x1 = Math.min(fw - 1, x0 + 1); float tx = sx - x0;
                float v = (fm[y0 * fw + x0] * (1 - tx) + fm[y0 * fw + x1] * tx) * (1 - ty) + (fm[y1 * fw + x0] * (1 - tx) + fm[y1 * fw + x1] * tx) * ty;
                a[(y - top) * rw + (x - left)] = Math.max(v, ((maskRow[x] >> 16) & 255) / 255f);
            }
        }
        // colour offset from the ring just inside the feathered edge (0.05 < a < 0.6): mean(source) - mean(edit), clamped
        double[] sum = new double[6]; long n = 0;
        int[] srcRow = new int[w], dstRow = new int[w], editRow = new int[rw];
        for (int y = top; y < top + rh; y++) {
            source.getPixels(srcRow, 0, w, 0, y, w, 1); edit.getPixels(editRow, 0, rw, 0, y - top, rw, 1);
            for (int x = left; x < left + rw; x++) {
                float m = a[(y - top) * rw + (x - left)]; if (m <= .05f || m >= .6f) continue;
                int s = srcRow[x], e = editRow[x - left];
                sum[0] += (s >> 16) & 255; sum[1] += (s >> 8) & 255; sum[2] += s & 255; sum[3] += (e >> 16) & 255; sum[4] += (e >> 8) & 255; sum[5] += e & 255; n++;
            }
        }
        int[] off = new int[3];
        if (n >= 64) for (int c = 0; c < 3; c++) off[c] = (int) Math.max(-24, Math.min(24, Math.round((sum[c] - sum[c + 3]) / n)));
        Bitmap out = source.copy(Bitmap.Config.ARGB_8888, true);
        for (int y = top; y < top + rh; y++) {
            source.getPixels(srcRow, 0, w, 0, y, w, 1);
            out.getPixels(dstRow, 0, w, 0, y, w, 1); edit.getPixels(editRow, 0, rw, 0, y - top, rw, 1);
            for (int x = left; x < left + rw; x++) {
                float m = a[(y - top) * rw + (x - left)]; if (m <= 0f) continue;
                int s = srcRow[x], e = editRow[x - left];
                int er = clamp8(((e >> 16) & 255) + off[0]), eg = clamp8(((e >> 8) & 255) + off[1]), eb = clamp8((e & 255) + off[2]);
                int r = Math.round(((s >> 16) & 255) * (1 - m) + er * m);
                int g = Math.round(((s >> 8) & 255) * (1 - m) + eg * m);
                int b = Math.round((s & 255) * (1 - m) + eb * m);
                dstRow[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            out.setPixels(dstRow, 0, w, 0, y, w, 1);
        }
        return out;
    }
    private static int clamp8(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    /** brush coverage 0..1, dilated by r (running max) then softened with box blurs of radius r; the painted core stays 1 */
    static float[] featherMask(Bitmap mask, int r) {
        int w = mask.getWidth(), h = mask.getHeight(); float[] o = new float[w * h], m = new float[w * h], t = new float[w * h]; int[] row = new int[w];
        for (int y = 0; y < h; y++) { mask.getPixels(row, 0, w, 0, y, w, 1); for (int x = 0; x < w; x++) o[y * w + x] = ((row[x] >> 16) & 255) / 255f; }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { float v = 0; int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r); for (int k = x0; k <= x1; k++) v = Math.max(v, o[y * w + k]); t[y * w + x] = v; }
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) { float v = 0; int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r); for (int k = y0; k <= y1; k++) v = Math.max(v, t[k * w + x]); m[y * w + x] = v; }
        for (int pass = 0; pass < 2; pass++) { boxBlur(m, t, w, h, r, true); boxBlur(t, m, w, h, r, false); }
        for (int i = 0; i < m.length; i++) if (o[i] > m[i]) m[i] = o[i];
        return m;
    }
    private static void boxBlur(float[] in, float[] out, int w, int h, int r, boolean horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w; float inv = 1f / (2 * r + 1);
        for (int l = 0; l < lines; l++) {
            float acc = 0;
            for (int k = -r; k <= r; k++) { int i = Math.max(0, Math.min(len - 1, k)); acc += in[horiz ? l * w + i : i * w + l]; }
            for (int i = 0; i < len; i++) {
                out[horiz ? l * w + i : i * w + l] = acc * inv;
                int add = Math.min(len - 1, i + r + 1), sub = Math.max(0, i - r);
                acc += in[horiz ? l * w + add : add * w + l] - in[horiz ? l * w + sub : sub * w + l];
            }
        }
    }

    /** a copy of the photo an edit started from, kept beside the result for the before/after view (JPEG, last 40) */
    static File beforeDir(File home) { return new File(dir(home), "before"); }
    static File beforeFile(File home, File result) { return new File(beforeDir(home), result.getName().replaceFirst("(?i)\\.png$", "") + ".jpg"); }
    static void saveBefore(File home, Bitmap src, File result) {
        File d = beforeDir(home); d.mkdirs();
        try (FileOutputStream o = new FileOutputStream(beforeFile(home, result))) { src.compress(Bitmap.CompressFormat.JPEG, 92, o); } catch (IOException ignored) { }
        File[] fs = d.listFiles(); if (fs != null && fs.length > 40) { java.util.Arrays.sort(fs, (x, y) -> Long.compare(x.lastModified(), y.lastModified())); for (int i = 0; i < fs.length - 40; i++) fs[i].delete(); }
    }

    /** "before | after" side by side at the result's height (at most 1024), a label under each half */
    static Bitmap sideBySide(Bitmap before, Bitmap after, String l1, String l2, android.graphics.Typeface tf) {
        int ah = Math.min(1024, after.getHeight()), aw = Math.round(after.getWidth() * ah / (float) after.getHeight()), lh = Math.max(36, ah / 20), gap = 8;
        Bitmap g = Bitmap.createBitmap(aw * 2 + gap, ah + lh, Bitmap.Config.ARGB_8888); android.graphics.Canvas c = new android.graphics.Canvas(g); c.drawColor(0xFF1B172F);
        android.graphics.Paint fp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(before, coverRect(before, aw, ah), new android.graphics.Rect(0, 0, aw, ah), fp);
        c.drawBitmap(after, null, new android.graphics.Rect(aw + gap, 0, aw * 2 + gap, ah), fp);
        android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); tp.setColor(0xFFE9E6F5); tp.setTextSize(lh * .6f); if (tf != null) tp.setTypeface(tf); tp.setTextAlign(android.graphics.Paint.Align.CENTER);
        c.drawText(l1, aw / 2f, ah + lh * .72f, tp); c.drawText(l2, aw + gap + aw / 2f, ah + lh * .72f, tp);
        return g;
    }
    /** the centred part of b with the aspect of w x h (what a centre crop shows) */
    static android.graphics.Rect coverRect(Bitmap b, int w, int h) {
        float sa = b.getWidth() / (float) b.getHeight(), da = w / (float) h;
        if (sa > da) { int cw = Math.round(b.getHeight() * da); int x = (b.getWidth() - cw) / 2; return new android.graphics.Rect(x, 0, x + cw, b.getHeight()); }
        int ch = Math.round(b.getWidth() / da); int y = (b.getHeight() - ch) / 2; return new android.graphics.Rect(0, y, b.getWidth(), y + ch);
    }

    static final class Edit { int drop, cw, ch, nc, nm, tokens; }

    static final double COND_AREA = 393216.0, COND_AREA_HI = 1048576.0;   // ~0.4 Mpx by default; "high fidelity" lets the reference follow the output size up to 1 Mpx
    /** condition size: the photo's aspect at about min(target area, cap), sides multiples of 32 (what the ViT and the VAE both need) */
    static int[] condSize(int srcW, int srcH, int tw, int th) { return condSize(srcW, srcH, tw, th, COND_AREA); }
    static int[] condSize(int srcW, int srcH, int tw, int th, double cap) {
        double area = Math.min((double) tw * th, cap), ratio = (double) srcW / srcH;
        int cw = (int) Math.max(64, Math.round(Math.sqrt(area * ratio) / 32) * 32), ch = (int) Math.max(64, Math.round(area / cw / 32) * 32);
        return new int[]{cw, ch};
    }

    /** writes EDIT_DIR/{meta.txt, rgba.f32, px.f32} and the ids file for editing `src` with `prompt` into a tw×th picture */
    static Edit prepareEdit(File home, Bitmap src, String prompt, int tw, int th, File idsOut) throws Exception { return prepareEdit(home, src, prompt, tw, th, idsOut, COND_AREA); }
    static Edit prepareEdit(File home, Bitmap src, String prompt, int tw, int th, File idsOut, double cap) throws Exception {
        int[] cs = condSize(src.getWidth(), src.getHeight(), tw, th, cap); final int cw = cs[0], ch = cs[1];
        Bitmap b = src.copy(Bitmap.Config.ARGB_8888, false);
        while (b.getWidth() > 2 * cw || b.getHeight() > 2 * ch) { Bitmap next = Bitmap.createScaledBitmap(b, Math.max(cw, b.getWidth() / 2), Math.max(ch, b.getHeight() / 2), true); if (next != b) b.recycle(); b = next; }   // halve first: one big bilinear step would alias
        if (b.getWidth() != cw || b.getHeight() != ch) { Bitmap next = Bitmap.createScaledBitmap(b, cw, ch, true); if (next != b) b.recycle(); b = next; }
        int[] px = new int[cw * ch]; b.getPixels(px, 0, cw, 0, 0, cw, ch); b.recycle();
        File ed = editDir(home); ed.mkdirs();
        final String key = src.getWidth() + "x" + src.getHeight() + ":" + cw + "x" + ch + ":" + java.util.Arrays.hashCode(px) + ":" + editSrc(home).length() + ":" + editSrc(home).lastModified();
        boolean same = false; try { same = new File(ed, "key.txt").exists() && key.equals(Models.read(new File(ed, "key.txt"))) && new File(ed, "rgba.f32").exists() && new File(ed, "px.f32").exists(); } catch (Exception ignored) { }
        int n = cw * ch, gh = ch / 16, gw = cw / 16;
        if (!same) {   // the photo files (and the encoder's latent) only change with the photo or the condition size
        new File(ed, "cond_raw.f32").delete();
        float[] rgba = new float[4 * n]; float[] rgb = new float[3 * n];
        for (int i = 0; i < n; i++) {
            int p = px[i], a = p >>> 24, r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
            rgba[i] = r / 127.5f - 1f; rgba[n + i] = g / 127.5f - 1f; rgba[2 * n + i] = bl / 127.5f - 1f; rgba[3 * n + i] = a / 127.5f - 1f;
            float wa = a / 255f;   // the ViT was trained on the picture flattened over white
            rgb[i] = (r * wa + 255 * (1 - wa)) / 127.5f - 1f; rgb[n + i] = (g * wa + 255 * (1 - wa)) / 127.5f - 1f; rgb[2 * n + i] = (bl * wa + 255 * (1 - wa)) / 127.5f - 1f;
        }
        writeFloats(new File(ed, "rgba.f32"), rgba);
        // ViT input: 16x16 patches in 2x2 merge order, each (c, t=2 copies, 16, 16)
        float[] pv = new float[gh * gw * 1536]; int bw = gw / 2;
        for (int p = 0; p < gh * gw; p++) {
            int blk = p / 4, ir = (p / 2) % 2, ic = p % 2, row = (blk / bw) * 2 + ir, col = (blk % bw) * 2 + ic, o = p * 1536;
            for (int c = 0; c < 3; c++) for (int t = 0; t < 2; t++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                pv[o + ((c * 2 + t) * 16 + y) * 16 + x] = rgb[c * n + (row * 16 + y) * cw + col * 16 + x];
        }
        writeFloats(new File(ed, "px.f32"), pv);
        try (FileWriter kw = new FileWriter(new File(ed, "key.txt"))) { kw.write(key); }
        }
        try (FileWriter w = new FileWriter(new File(ed, "meta.txt"))) { w.write(cw + " " + ch + "\n"); }
        Edit e = new Edit(); e.cw = cw; e.ch = ch; e.nc = gh * gw; e.nm = e.nc / 4;
        QwenTok t = new QwenTok(dir(home).getPath());
        int[] ids = t.encodeQwenEdit(prompt, e.nm); e.drop = t.dropQwenImage(); e.tokens = ids.length;
        writeIds(ids, idsOut);
        return e;
    }
    private static void writeFloats(File f, float[] a) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN); bb.asFloatBuffer().put(a);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(bb.array()); }
    }

    /** number of decoder tiles along an axis of n latents for tile size T (the same overlap rule as the vaetile runner) */
    static int tiles(int n, int T) {
        if (n <= T) return 1;
        int om = Math.max(1, T / 8); return Math.max(2, (n - om + (T - om) - 1) / (T - om));
    }
    /** decoder context for the picture: the 512 px tile or, when installed, the 1024 px one, whichever decodes the size with fewer pixels computed (a 1024x1024 picture is 9 small tiles but one big one) */
    static String vaeCtx(File home, int w, int h) {
        long c32 = (long) tiles(w / 16, 32) * tiles(h / 16, 32) * 512 * 512, c64 = (long) tiles(w / 16, 64) * tiles(h / 16, 64) * 1024 * 1024;
        File vae = new File(dir(home), Compat.dspArch().equals("v73") ? "vae_v73" : "vae");
        return new File(vae, "vae_t64.bin").exists() && c64 < c32 ? "vae_t64.bin" : "vae_t32.bin";
    }

    /** raw fp32 RGB [3][h][w] in [-1,1] -> PNG bytes. Do not use Moss.png here: its DeGrid filter targets a 4-pixel artifact specific to the MOSS adapter and damages Qwen's texture and color. */
    static byte[] png(File raw, int w, int h) throws IOException {
        byte[] bytes = Models.readBytes(raw);
        int n = w * h;
        if (bytes.length != 3 * n * 4) throw new IOException("decoder output has " + bytes.length + " bytes");
        java.nio.FloatBuffer f = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int[] px = new int[n];
        for (int i = 0; i < n; i++) {
            int r = c(f.get(i)), g = c(f.get(n + i)), b = c(f.get(2 * n + i));
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        Bitmap bm = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }
    private static int c(float v) {
        return Math.round((Math.max(-1f, Math.min(1f, v)) + 1f) * 127.5f);
    }
}
