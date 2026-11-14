package com.hexanima.npu;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Model packs: a catalog (catalog.json) lists models; every model and the shared "common" pack have a manifest with raw files (size + sha256) that are
 * downloaded one by one (resumable, verified). Nothing is zipped: the weights do not compress and per-file resume/verification is simpler.
 * The multi-GB safetensors files that sd.cpp opens are NOT downloaded: only their JSON header (hdr.bin) is, and the app rebuilds a sparse stub of the real size.
 * Layout: files/anima/{te*.bin, te_embed.f16, vae.bin} (common), files/anima/m_ID/{small.bin, blkNN.bin}, files/sd/{ID.safetensors stub, llm stub, taew2_1.safetensors}.
 */
final class Models {
    private static final String MAGE_PACK = "https://huggingface.co/ayanoby/hexanima-mage-packs/resolve/1937989fd5327472322d273366aa9ee8d944729d/";
    private static final long MAGE_PACK_SIZE = 8710533044L;
    static class Item { String id, name, desc, descEn, params = "", quant = "", author = "", manifest, runtimeManifest = "", mossVariant = ""; long size, runtimeSize; int speed; boolean moss, qwen, mage, runtimeAvailable = true; }   // speed: seconds per picture at 512x768
    interface Progress { void on(long done, long total, String what); }

    final File anima, sd, home;
    Models(File home) { this.home = home; anima = new File(home, "anima"); sd = new File(home, "sd"); anima.mkdirs(); sd.mkdirs(); }

    static String get(String url) throws IOException {
        HttpURLConnection c = open(url, 0);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    static HttpURLConnection open(String url, long from) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
        if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
        return c;
    }

    long commonSize;
    List<Item> catalog(String base) throws Exception {
        String txt;
        try { txt = get(base + "catalog6.json"); List<Item> out = catalogPacks(txt, base); checkRuntimePacks(out); ensureMage(out); return out; } catch (Exception e6) { }
        try { txt = get(base + "catalog5.json"); List<Item> out = catalogPacks(txt, base); checkRuntimePacks(out); ensureMage(out); return out; } catch (Exception e5) { }
        try { txt = get(base + "catalog2.json"); } catch (Exception e) { txt = get(base + "catalog.json"); }
        JSONObject j = new JSONObject(txt);
        commonSize = j.optLong("common_size", 0);
        List<Item> out = new ArrayList<>();
        JSONArray a = j.getJSONArray("models");
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.getJSONObject(i); Item it = new Item();
            it.id = m.getString("id"); it.name = m.getString("name"); it.desc = m.optString("desc", ""); it.size = m.getLong("size"); it.manifest = m.getString("manifest");
            it.descEn = m.optString("desc_en", it.desc); it.params = m.optString("params", ""); it.quant = m.optString("quant", ""); it.author = m.optString("author", ""); it.speed = m.optInt("speed", 0); it.moss = m.optString("kind", "").equals("moss"); it.qwen = m.optString("kind", "").equals("qwen");
            configureRuntime(it, base);
            out.add(it);
        }
        checkRuntimePacks(out); ensureMage(out);
        return out;
    }

    /** Keep Mage available when the selected Anima pack catalog predates that model family. */
    static void ensureMage(List<Item> items) {
        for (Item it : items) if (Mage.is(it.id)) return;
        Item it = new Item(); it.id = "mage-flow-edit-turbo"; it.name = "Mage-Flow-Edit-Turbo";
        it.desc = "Generación y edición de imágenes con Mage-Flow; runner experimental para Hexagon V73.";
        it.descEn = "Image generation and editing with Mage-Flow; experimental Hexagon V73 runner.";
        it.params = "12 bloques DiT"; it.quant = "8-bit"; it.author = "Microsoft · Mage-Flow";
        it.size = MAGE_PACK_SIZE; it.manifest = MAGE_PACK + "manifest.json"; it.mage = true;
        items.add(it);
    }

    /** catalog5/6 describe models in families and downloadable packs; flatten each mode for the existing picker. */
    List<Item> catalogPacks(String txt, String base) throws Exception {
        JSONObject root = new JSONObject(txt), packs = root.getJSONObject("packs");
        List<Item> out = new ArrayList<>(); JSONArray models = root.getJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i); JSONArray modes = model.getJSONArray("modes");
            for (int k = 0; k < modes.length(); k++) {
                JSONObject mode = modes.getJSONObject(k), pack = packs.getJSONObject(mode.getString("pack")); Item it = new Item();
                String family = model.optString("family", "Anima"), modelName = model.optString("name", "");
                if (!family.equals("Anima") && !family.equals("MOSS-Image") && !family.equals("Qwen-Image") && !family.equals("Mage-Flow")) continue;
                String modeInfo = (mode.optString("name", "") + " " + mode.optString("label", "") + " " + mode.optString("id", "")).toLowerCase(java.util.Locale.ROOT);
                boolean moss8 = family.equals("MOSS-Image") && (Moss.ID8.equals(mode.optString("id")) || modeInfo.contains("8 step") || modeInfo.endsWith("-8"));
                if (family.equals("MOSS-Image") && mode.optInt("steps", moss8 ? 8 : 4) > 4 && !moss8) continue;
                it.id = mode.getString("id"); it.name = mode.optString("name", model.getString("name"));
                it.size = pack.optLong("size", 0); String packUrl = pack.has("url") ? pack.getString("url") : base + pack.optString("path");
                if (!packUrl.endsWith("/")) packUrl += "/";
                it.manifest = packUrl + pack.optString("manifest", "manifest.json");
                it.desc = model.optString("desc", ""); it.descEn = model.optString("desc_en", it.desc);
                it.params = model.optString("params", ""); it.quant = model.optString("quant", ""); it.author = model.optString("author", "");
                it.speed = mode.optInt("speed", 0);
                it.moss = family.equals("MOSS-Image"); it.qwen = family.equals("Qwen-Image"); it.mage = family.equals("Mage-Flow");
                if (moss8) { it.name = "MOSS-Image · DMD 8-step add-on"; it.params = "DMD add-on"; it.quant = ""; it.speed = 0; it.desc = "Complemento de 8 pasos DMD para MOSS-Image. Requiere MOSS-Image 5B; en RealSkin aparece como un modo opcional."; it.descEn = "8-step DMD add-on for MOSS-Image. Requires MOSS-Image 5B; it appears as an optional mode for RealSkin."; }
                configureRuntime(it, base);
                String lower = (modelName + " " + it.name + " " + it.id).toLowerCase(java.util.Locale.ROOT);
                if (it.moss && lower.contains("realskin")) { it.mossVariant = "rs"; it.id = Moss.IDRS; }
                else if (it.moss && lower.contains("harem")) { it.mossVariant = "hm"; it.id = Moss.IDHM; }
                out.add(it);
            }
        }
        return out;
    }

    void configureRuntime(Item it, String base) {
        if (it.qwen && Compat.dspArch().equals("v73")) {
            it.runtimeManifest = commonBase(base) + "qwen_v73/manifest.json";
            it.runtimeSize = Qwen.V73_RUNTIME_SIZE;
            it.size += it.runtimeSize;
        }
    }

    void checkRuntimePacks(List<Item> items) {
        for (Item it : items) if (it.qwen && !it.runtimeManifest.isEmpty()) {
            try {
                JSONObject manifest = new JSONObject(get(it.runtimeManifest)); JSONArray files = manifest.getJSONArray("files");
                boolean vae = false, enc = false;
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i); String path = f.getString("path");
                    if (path.equals("vae_t32.bin") && f.getLong("size") == Qwen.V73_VAE_SIZE && f.optString("sha256").equals(Qwen.V73_VAE_SHA256)) vae = true;
                    if (path.equals("enc_t32.bin") && f.getLong("size") == Qwen.V73_ENCODER_SIZE && f.optString("sha256").equals(Qwen.V73_ENCODER_SHA256)) enc = true;
                }
                it.runtimeAvailable = vae && enc && files.length() == 2;
            } catch (Exception e) { it.runtimeAvailable = false; }
        }
    }

    // ---- state ----------------------------------------------------------------------------------
    File modelDir(String id) { return Moss.is(id) ? Moss.dir(home) : Qwen.is(id) ? Qwen.dir(home) : Mage.is(id) ? Mage.dir(home) : new File(anima, "m_" + id); }
    File doneMark(File dir) { return new File(dir, ".installed"); }
    /** a 40-block (2.9B, q8) model: told by its block files, not its id (imported ids like "cv2945208" contain "29" too) */
    /** distilled for 4 steps? the catalog packs are (turbo LoRA merged); imported checkpoints only when their meta or name says so */
    boolean turbo(String id) {
        if (Moss.is(id) || id.isEmpty()) return true;
        try { JSONObject m = new JSONObject(read(new File(modelDir(id), "meta.json")));
            if (m.has("turbo")) return m.getBoolean("turbo");
            return !m.optBoolean("imported") || m.optString("name").toLowerCase(java.util.Locale.ROOT).contains("turbo");
        } catch (Exception e) { return true; }
    }
    boolean is29(String id) { return !Moss.is(id) && new File(modelDir(id), "blk39.bin").exists(); }
    boolean modelInstalled(String id) { if (Moss.is8(id)) return Moss.installed8(home); if (Moss.is(id)) return Moss.installed(home) && (Moss.isVariant(id) ? id.equals(Moss.variantId(home, Moss.variantKey(id))) && Moss.installedVariant(home, Moss.variantKey(id)) : id.equals(mossId())); if (Qwen.is(id)) return Qwen.installed(home) && id.equals(qwenId()); if (Mage.is(id)) return Mage.installed(home) && id.equals(mageId()); return commonInstalled() && doneMark(modelDir(id)).exists() && new File(sd, id + ".safetensors").exists(); }
    /** v69 keeps the original "common" folder (older apps use it); other Hexagon generations have their own QNN contexts in common_<arch> */
    static String commonDir() { String a = Compat.dspArch(); return a.isEmpty() || a.equals("v69") ? "common" : "common_" + a; }
    /** where common_<arch> lives: the V73 contexts (Snapdragon 8 Gen 2 port) are in their own repository; the models stay in the main one */
    static String commonBase(String base) { return Compat.dspArch().equals("v73") ? "https://huggingface.co/GrimmDev-Glitch/hexanima-packs/resolve/main/" : base; }
    boolean commonInstalled() { return doneMark(new File(anima, commonDir())).exists(); }
    String qwenId() { try { return new JSONObject(read(new File(Qwen.dir(home), "meta.json"))).getString("id"); } catch (Exception e) { return ""; } }
    String mageId() { try { return new JSONObject(read(new File(Mage.dir(home), "meta.json"))).getString("id"); } catch (Exception e) { return ""; } }
    String mossId() { try { return new JSONObject(read(new File(Moss.dir(home), "meta.json"))).getString("id"); } catch (Exception e) { return ""; } }
    /** ids of installed models, in catalog order of installation directory names */
    List<String> installed() {
        List<String> out = new ArrayList<>();
        if (Moss.installed(home)) {
            out.add(mossId());
            String rs = Moss.variantId(home, "rs"), hm = Moss.variantId(home, "hm");
            if (Moss.installedRs(home) && !rs.isEmpty()) out.add(rs);
            if (Moss.installedHm(home) && !hm.isEmpty()) out.add(hm);
        }
        if (Qwen.installed(home)) out.add(qwenId());
        if (Mage.installed(home) && Mage.V73_RUNNER_VALIDATED) out.add(mageId());
        File[] ds = anima.listFiles();
        if (ds != null) for (File d : ds) if (d.getName().startsWith("m_") && modelInstalled(d.getName().substring(2))) out.add(d.getName().substring(2));
        java.util.Collections.sort(out);
        return out;
    }
    String name(String id) { if (Moss.is8(id)) return "MOSS-Image 8 step · DMD"; if (Moss.isRs(id)) return "MiaoMiao RealSkin · MOSS-Image 1.0"; if (Moss.isHm(id)) return "MiaoMiao Harem · MOSS-Image 1.1"; try { return new JSONObject(read(new File(modelDir(id), "meta.json"))).getString("name"); } catch (Exception e) { return id; } }

    /** seconds per picture at 512x768 (from the catalog at install time; models installed before that get the measured value of their size class) */
    int speed(String id) { try { int v = new JSONObject(read(new File(modelDir(id), "meta.json"))).optInt("speed", 0); if (v > 0) return v; } catch (Exception e) { } return is29(id) ? 12 : 7; }

    static byte[] readBytes(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[(int) f.length()]; int o = 0; for (int n; o < b.length && (n = in.read(b, o, b.length - o)) > 0; ) o += n; return b; }
    }
    static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[(int) f.length()]; int o = 0; for (int n; o < b.length && (n = in.read(b, o, b.length - o)) > 0; ) o += n; return new String(b, "UTF-8"); }
    }

    // ---- install --------------------------------------------------------------------------------
    void installCommon(String base, Progress pg, AtomicBoolean cancel) throws Exception {
        if (commonInstalled()) return;
        base = commonBase(base);
        JSONObject m = new JSONObject(get(base + commonDir() + "/manifest.json"));
        File marker = new File(anima, commonDir()); marker.mkdirs();
        long total = total(m.getJSONArray("files")) + total(m.getJSONArray("sd"));
        long[] done = {0};
        fetchAll(base + commonDir() + "/", m.getJSONArray("files"), anima, total, done, pg, cancel);
        fetchAll(base + commonDir() + "/", m.getJSONArray("sd"), sd, total, done, pg, cancel);
        JSONObject llm = m.getJSONObject("llm_stub");
        File hdr = new File(marker, "llm_hdr.bin");
        fetch(base + commonDir() + "/" + llm.getString("hdr"), hdr, llm.optLong("hdr_size", -1), llm.optString("hdr_sha256", null), null, cancel);
        stub(hdr, new File(sd, llm.getString("name")), llm.getLong("size"));
        doneMark(marker).createNewFile();
    }

    void installModel(String base, Item it, Progress pg, AtomicBoolean cancel) throws Exception {
        final String murl = it.manifest.startsWith("http") ? it.manifest : base + it.manifest;   // a pack may live in another repository (MOSS-Image: Apache-2.0, not in the Anima repo)
        JSONObject m = new JSONObject(get(murl));
        File dir = modelDir(it.id); dir.mkdirs();
        JSONArray files = m.getJSONArray("files");
        long[] done = {0};
        if (Moss.is8(it.id)) {
            if (!Moss.installed(home)) throw new IOException(L.s("Primero instala MOSS-Image 5B", "Install MOSS-Image 5B first"));
            Moss.prepare8Install(home);
            fetchAll(murl.substring(0, murl.lastIndexOf('/') + 1), files, dir, total(files), done, pg, cancel);
            if (!Moss.installed8(home)) throw new IOException(L.s("El complemento MOSS DMD está incompleto", "The MOSS DMD add-on is incomplete"));
            return;
        }
        if (Moss.isVariant(it.id)) {
            if (!Moss.installed(home)) throw new IOException(L.s("Instala primero MOSS-Image", "Install MOSS-Image first"));
            File variant = Moss.variantDir(home, it.id); variant.mkdirs();
            fetchAll(murl.substring(0, murl.lastIndexOf('/') + 1), files, variant, total(files), done, pg, cancel);
            try (FileWriter w = new FileWriter(new File(variant, ".model_id"))) { w.write(it.id); }
            return;
        }
        doneMark(dir).delete();
        long modelBytes = total(files);
        boolean qwenV73 = it.qwen && Compat.dspArch().equals("v73") && !it.runtimeManifest.isEmpty();
        JSONObject runtime = qwenV73 ? new JSONObject(get(it.runtimeManifest)) : null;
        JSONArray runtimeFiles = runtime == null ? null : runtime.getJSONArray("files");
        long installBytes = modelBytes + (runtimeFiles == null ? 0 : total(runtimeFiles));
        fetchAll(murl.substring(0, murl.lastIndexOf('/') + 1), files, dir, installBytes, done, pg, cancel);
        if (runtimeFiles != null) {
            String runtimeBase = it.runtimeManifest.substring(0, it.runtimeManifest.lastIndexOf('/') + 1);
            fetchAll(runtimeBase, runtimeFiles, new File(dir, "vae_v73"), installBytes, done, pg, cancel);
        }
        if (it.moss || it.qwen || it.mage) {   // NPU model packs: raw files only, no sd.cpp stub
            try (FileWriter w = new FileWriter(new File(dir, "meta.json"))) { w.write(new JSONObject().put("id", it.id).put("name", it.name).put("speed", it.speed).toString()); }
            doneMark(dir).createNewFile(); return;
        }
        JSONObject st = m.getJSONObject("stub");
        File hdr = new File(dir, st.getString("hdr"));
        stub(hdr, new File(sd, it.id + ".safetensors"), st.getLong("size"));
        try (FileWriter w = new FileWriter(new File(dir, "meta.json"))) { w.write(new JSONObject().put("id", it.id).put("name", it.name).put("speed", it.speed).toString()); }
        doneMark(dir).createNewFile();
    }

    /** installed models take the name / speed of the current catalog (names are shown from meta.json) */
    void refreshMeta(Item it) {
        if (Moss.is8(it.id) || Moss.isVariant(it.id) || !modelInstalled(it.id)) return;
        try (FileWriter w = new FileWriter(new File(modelDir(it.id), "meta.json"))) { w.write(new JSONObject().put("id", it.id).put("name", it.name).put("speed", it.speed).toString()); } catch (Exception ignored) { }
    }

    void delete(String id) {
        File dir = modelDir(id);
        if (Moss.is8(id)) { Moss.delete8(home); return; }
        if (Moss.isVariant(id)) { String key = Moss.variantKey(id); deleteTree(new File(dir, key)); deleteTree(new File(dir, "v" + key)); return; }
        if (Moss.is(id) || Qwen.is(id)) { deleteTree(dir); return; }
        File[] fs = dir.listFiles(); if (fs != null) for (File f : fs) f.delete();
        dir.delete(); new File(sd, id + ".safetensors").delete();
    }

    /** removes a folder; a symbolic link is removed itself, never what it points to (variant views and the LoKr cache link to the real model files) */
    static void deleteTree(File f) { if (java.nio.file.Files.isSymbolicLink(f.toPath())) { f.delete(); return; } File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); f.delete(); }

    /** sparse stub of a safetensors file: the real header, then a hole up to the real size (sd.cpp maps the file but never touches tensor data when the DiT/LLM run on the NPU) */
    static void stub(File hdr, File out, long size) throws IOException {
        try (FileInputStream in = new FileInputStream(hdr); FileOutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[65536]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        }
        try (RandomAccessFile r = new RandomAccessFile(out, "rw")) { r.setLength(size); }
    }

    static long total(JSONArray a) throws Exception { long t = 0; for (int i = 0; i < a.length(); i++) t += a.getJSONObject(i).getLong("size"); return t; }

    void fetchAll(String urlBase, JSONArray files, File dir, long total, long[] done, Progress pg, AtomicBoolean cancel) throws Exception {
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            File out = new File(dir, f.getString("path"));
            final long before = done[0];
            fetch(urlBase + f.getString("path"), out, f.getLong("size"), f.optString("sha256", null), n -> { if (pg != null) pg.on(before + n, total, f.optString("path")); }, cancel);
            done[0] += f.getLong("size");
        }
    }

    interface Count { void on(long n); }

    /** download url to out (resumes a .part file), verify size and sha256, rename; finished files are reused only after manifest verification */
    static void fetch(String url, File out, long size, String sha, Count cb, AtomicBoolean cancel) throws Exception {
        if (out.exists()) {
            boolean rightSize = size < 0 || out.length() == size;
            boolean rightHash = rightSize && (sha == null || sha.isEmpty() || sha256(out).equalsIgnoreCase(sha));
            if (rightSize && rightHash) { if (cb != null) cb.on(out.length()); return; }
            // A previous install may have left a truncated or same-size corrupt file. Do not
            // count it as a successful Qwen V73 companion install just because its length fits.
            if (!out.delete()) throw new IOException("cannot replace invalid pack file " + out);
        }
        File part = new File(out.getPath() + ".part");
        out.getParentFile().mkdirs();
        // large files: several ranges at once (ParDl); a server without ranges falls through to the single stream below
        if (size > (32L << 20) && !(new File(out.getPath() + ".part").exists() && !new File(out.getPath() + ".part.map").exists())) {
            if (ParDl.get(url, part, size, (d, t) -> { if (cb != null) cb.on(d); }, cancel)) {
                if (sha != null && !sha.isEmpty() && !sha256(part).equalsIgnoreCase(sha)) { part.delete(); throw new IOException("sha256 mismatch " + url); }
                if (!part.renameTo(out)) throw new IOException("rename " + out);
                return;
            }
        }
        for (int attempt = 0; ; attempt++) {
            try {
                long have = part.exists() ? part.length() : 0;
                if (size >= 0 && have > size) { part.delete(); have = 0; }
                if (size < 0 || have < size) {
                    HttpURLConnection c = open(url, have);
                    int code = c.getResponseCode();
                    if (code == 200 && have > 0) { part.delete(); have = 0; }   // server ignored Range
                    else if (code != 200 && code != 206) throw new IOException("HTTP " + code + " " + url);
                    try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(part, have > 0)) {
                        byte[] b = new byte[1 << 18]; long n = have;
                        for (int r; (r = in.read(b)) > 0; ) {
                            if (cancel != null && cancel.get()) throw new InterruptedIOException("cancelled");
                            o.write(b, 0, r); n += r; if (cb != null) cb.on(n);
                        }
                    } finally { c.disconnect(); }
                }
                if (size >= 0 && part.length() != size) throw new IOException("size " + part.length() + " != " + size + " " + url);
                if (sha != null && !sha.isEmpty() && !sha256(part).equalsIgnoreCase(sha)) { part.delete(); throw new IOException("sha256 mismatch " + url); }
                if (!part.renameTo(out)) throw new IOException("rename " + out);
                return;
            } catch (InterruptedIOException e) { throw e;
            } catch (IOException e) { if (attempt >= 5) throw e; Thread.sleep(2000L * (attempt + 1)); }   // flaky network: resume from the .part file
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[1 << 20]; for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n); }
        StringBuilder sb = new StringBuilder(); for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
