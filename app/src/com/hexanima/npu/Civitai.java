package com.hexanima.npu;

import android.content.Context;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Civitai (civitai.com / civitai.red) public REST API: model search, model details, file downloads. The user's own API key (Settings) is sent as a
 *  Bearer header to civitai.com only (never to the storage host a download redirects to); without it, files marked +18 or "login required" cannot be downloaded.
 *  Anima files install directly, MOSS A/B LoRAs install after validation, and Qwen 2.1 LoRAs are exported for host conversion into V73 overlays.
 *  Next to every file a <name>.json keeps what the app shows later (Civitai ids, base model, trigger words, preview). */
final class Civitai {
    static final String API = "https://civitai.com/api/v1/";
    static String key(Context c) { return c.getSharedPreferences("anima", 0).getString("civitai_key", "").trim(); }

    /** the one base model the app runs (SDXL-family models have no NPU path on the phone and were too slow and hot on the CPU) */
    static final String BASE = "Anima";
    static final String MOSS_BASE = "MOSS Image";
    static final String QWEN_BASE = "Qwen 2.1";
    static boolean anima(String base) { return base != null && base.toLowerCase(Locale.ROOT).startsWith("anima"); }
    static boolean moss(String base) { return base != null && base.toLowerCase(Locale.ROOT).replace('-', ' ').contains("moss image"); }
    static boolean qwen(String base) { return QWEN_BASE.equalsIgnoreCase(base == null ? "" : base.trim()); }
    static File qwenRawDir(File home) { return new File(new File(home, "qwen"), "civitai_raw"); }


    static HttpURLConnection open(Context c, String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20000); h.setReadTimeout(60000); h.setInstanceFollowRedirects(false);
        h.setRequestProperty("User-Agent", "HexAnima");
        String k = key(c); if (!k.isEmpty() && new URL(url).getHost().endsWith("civitai.com")) h.setRequestProperty("Authorization", "Bearer " + k);
        return h;
    }
    static String getText(Context c, String url) throws IOException {
        HttpURLConnection h = open(c, url);
        int code = h.getResponseCode();
        if (code == 401 || code == 403) throw new IOException(L.s("Нужен ключ API Civitai (Настройки)", "A Civitai API key is needed (Settings)"));
        if (code >= 400) throw new IOException("HTTP " + code);
        try (InputStream in = h.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) { byte[] b = new byte[1 << 15]; for (int n; (n = in.read(b)) > 0; ) bo.write(b, 0, n); return bo.toString("UTF-8"); }
    }

    /** search: type "LORA" / "Checkpoint", base "Anima" (BASE) or "" (all), sort "Most Downloaded" / "Highest Rated" / "Newest"; cursor from the previous page or null */
    static JSONObject search(Context c, String query, String type, String base, String sort, boolean nsfw, String cursor) throws Exception { return search(c, query, type, base, sort, nsfw, cursor, ""); }
    static JSONObject search(Context c, String query, String type, String base, String sort, boolean nsfw, String cursor, String tag) throws Exception {
        StringBuilder u = new StringBuilder(API + "models?limit=20");
        if (tag != null && !tag.isEmpty()) u.append("&tag=").append(URLEncoder.encode(tag, "UTF-8"));
        if (!query.isEmpty()) u.append("&query=").append(URLEncoder.encode(query, "UTF-8"));
        u.append("&types=").append(URLEncoder.encode(type, "UTF-8"));
        if (!base.isEmpty()) u.append("&baseModels=").append(URLEncoder.encode(base, "UTF-8"));
        if (query.isEmpty()) u.append("&sort=").append(URLEncoder.encode(sort, "UTF-8"));
        u.append("&nsfw=").append(nsfw);
        if (cursor != null) u.append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"));
        return new JSONObject(getText(c, u.toString()));
    }
    static JSONObject model(Context c, long id) throws Exception { return new JSONObject(getText(c, API + "models/" + id)); }

    /** a pasted link or id: civitai.com / civitai.red /models/<id>[...?modelVersionId=<v>] -> {model id, version id or 0}; null if it is not one */
    static long[] parseLink(String s) {
        Matcher m = Pattern.compile("civitai\\.(?:com|red|green)/models/(\\d+)(?:[^?#]*)?(?:\\?(?:.*&)?modelVersionId=(\\d+))?").matcher(s);
        if (m.find()) return new long[]{Long.parseLong(m.group(1)), m.group(2) != null ? Long.parseLong(m.group(2)) : 0};
        if (s.trim().matches("\\d{3,9}")) return new long[]{Long.parseLong(s.trim()), 0};
        return null;
    }

    /** first preview image of a version (images[] entries of type image), small size */
    static String preview(JSONObject version) {
        JSONArray im = version.optJSONArray("images"); if (im == null) return null;
        for (int i = 0; i < im.length(); i++) { JSONObject o = im.optJSONObject(i); if (o != null && !"video".equals(o.optString("type"))) return o.optString("url").replaceFirst("/width=\\d+/", "/width=320/"); }
        return null;
    }
    static int nsfwLevel(JSONObject version) {
        JSONArray im = version.optJSONArray("images"); if (im == null || im.length() == 0) return 0;
        return im.optJSONObject(0).optInt("nsfwLevel", 0);
    }
    static Bitmap thumb(String url, int maxPx) {
        if (url == null) return null;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection(); h.setConnectTimeout(15000); h.setReadTimeout(30000); h.setRequestProperty("User-Agent", "HexAnima");
            byte[] b; try (InputStream in = h.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) { byte[] buf = new byte[1 << 15]; for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n); b = bo.toByteArray(); }
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(b, 0, b.length, o);
            int s = 1; while (o.outWidth / (s * 2) >= maxPx) s *= 2;
            o = new BitmapFactory.Options(); o.inSampleSize = s; return BitmapFactory.decodeByteArray(b, 0, b.length, o);
        } catch (Exception e) { return null; }
    }

    /** the main .safetensors file of a version (or the first file) */
    static JSONObject mainFile(JSONObject version) {
        JSONArray fs = version.optJSONArray("files"); if (fs == null || fs.length() == 0) return null;
        // the model itself first: some versions also carry their text encoder or VAE (Anima "base-v1.0" lists qwen_3_06b_base.safetensors)
        for (int i = 0; i < fs.length(); i++) { JSONObject f = fs.optJSONObject(i); if (f != null && "Model".equalsIgnoreCase(f.optString("type")) && f.optString("name").endsWith(".safetensors")) return f; }
        for (int i = 0; i < fs.length(); i++) { JSONObject f = fs.optJSONObject(i); if (f != null && f.optBoolean("primary") && f.optString("name").endsWith(".safetensors")) return f; }
        for (int i = 0; i < fs.length(); i++) { JSONObject f = fs.optJSONObject(i); if (f != null && f.optString("name").endsWith(".safetensors")) return f; }
        return fs.optJSONObject(0);
    }

    interface Progress { void at(long done, long total); }
    /** downloads url to out (via a .part file); the Bearer key goes to civitai.com only, the storage host it redirects to gets no credentials */
    static void download(Context c, String url, File out, long size, Progress pg, AtomicBoolean cancel) throws IOException {
        File part = new File(out.getPath() + ".part"); part.getParentFile().mkdirs();
        String u = url; HttpURLConnection h = null;
        for (int hop = 0; hop < 6; hop++) {
            h = open(c, u); int code = h.getResponseCode();
            if (code >= 300 && code < 400) { String loc = h.getHeaderField("Location"); h.disconnect(); if (loc == null) throw new IOException("redirect without Location"); u = new URL(new URL(u), loc).toString(); continue; }
            if (code == 401 || code == 403) throw new IOException(key(c).isEmpty() ? L.s("Этот файл требует входа: добавьте ключ API Civitai в Настройках", "This file needs a login: add your Civitai API key in Settings")
                    : L.s("Civitai отказал в доступе (проверьте ключ API)", "Civitai refused access (check the API key)"));
            if (code >= 400) throw new IOException("HTTP " + code);
            break;
        }
        String ct = h.getContentType();
        if (ct != null && ct.startsWith("text/html")) throw new IOException(L.s("Civitai вернул страницу входа: нужен ключ API", "Civitai returned a login page: an API key is needed"));
        long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : size;
        // the storage URL needs no credentials: large files go through several ranges at once (ParDl)
        if (total > (32L << 20) && !u.contains("civitai.com")) {
            h.disconnect();
            try { if (ParDl.get(u, part, total, pg == null ? null : (d, t) -> pg.at(d, t), cancel)) { out.delete(); if (!part.renameTo(out)) throw new IOException("rename failed"); return; } }
            catch (IOException e) { if (cancel == null || !cancel.get()) throw e; part.delete(); new File(part.getPath() + ".map").delete(); throw e; }
            h = open(c, u);   // no ranges: one stream as before
        }
        try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(part)) {
            byte[] b = new byte[1 << 18]; long done = 0, last = 0;
            for (int n; (n = in.read(b)) > 0; ) {
                if (cancel != null && cancel.get()) throw new IOException("cancelled");
                o.write(b, 0, n); done += n;
                if (pg != null && done - last > (1 << 20)) { pg.at(done, total); last = done; }
            }
            if (total > 0 && done < total) throw new IOException("short download " + done + "/" + total);
        } catch (IOException e) { part.delete(); throw e; }
        out.delete(); if (!part.renameTo(out)) throw new IOException("rename failed");
    }

    /** where a version's file goes: null = this kind cannot be used yet (msg[0] = why) */
    static File target(File home, String type, String base, String fileName, String[] msg) {
        boolean lora = type.equalsIgnoreCase("LORA") || type.equalsIgnoreCase("LoCon") || type.equalsIgnoreCase("DoRA");
        if (lora && anima(base)) return new File(AnimaLora.dir(home), AnimaLora.fileName(fileName));
        if (lora && moss(base)) return new File(MossLora.civitaiDir(home), AnimaLora.fileName(fileName));
        if (lora && qwen(base) && fileName.toLowerCase(Locale.ROOT).endsWith(".safetensors")) return new File(qwenRawDir(home), AnimaLora.fileName(fileName));
        if (type.equalsIgnoreCase("Checkpoint") && anima(base)) return new File(AnimaImport.importDir(home), AnimaLora.fileName(fileName));   // converted on the phone after the download (AnimaImport)
        if (qwen(base)) {
            msg[0] = type.equalsIgnoreCase("Checkpoint")
                ? L.s("Los checkpoints Qwen de Civitai aún no se pueden convertir a un pack V73.", "Qwen checkpoints from Civitai cannot yet be converted into a V73 pack.")
                : L.s("Para Qwen V73 solo se guardan archivos Safetensors LoRA para convertirlos en el PC.", "For Qwen V73, only Safetensors LoRA files can be saved for conversion on the PC.");
            return null;
        }
        msg[0] = moss(base)
            ? L.s("Para MOSS solo se pueden usar LoRA A/B compatibles; los checkpoints y LoKR/LoHa aún no están admitidos.", "For MOSS, only compatible A/B LoRAs can be used; checkpoints and LoKR/LoHa are not supported yet.")
            : L.f("База «%s» не поддерживается: только модели и LoRA для Anima, LoRA A/B для MOSS-Image или LoRA Qwen 2.1 для конвертации V73", "Base model \"%s\" is not supported: only Anima models/LoRAs, MOSS-Image A/B LoRAs or Qwen 2.1 LoRAs for V73 conversion can be used", base); return null;
    }

    /** Copy a downloaded Qwen source file into public Downloads so it can be transferred to the PC converter over USB. */
    static String exportQwenToDownloads(Context context, File source) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HexAnima/Civitai");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException(L.s("No se pudo crear el archivo en Descargas", "Could not create the file in Downloads"));
        try {
            try (InputStream in = new BufferedInputStream(new FileInputStream(source)); OutputStream out = new BufferedOutputStream(context.getContentResolver().openOutputStream(uri, "w"))) {
                if (out == null) throw new IOException("cannot open Downloads output");
                byte[] b = new byte[1 << 20]; for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, values, null, null) <= 0) throw new IOException("cannot finalize Downloads file");
            return "Downloads/HexAnima/Civitai/" + source.getName();
        } catch (Exception e) {
            context.getContentResolver().delete(uri, null, null);
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException(e);
        }
    }

    /** sidecar <name>.json: what the library shows and inserts into the prompt */
    static void writeMeta(File f, JSONObject model, JSONObject version) {
        try {
            JSONObject m = new JSONObject().put("name", model.optString("name")).put("version", version.optString("name")).put("model_id", model.optLong("id")).put("version_id", version.optLong("id"))
                .put("type", model.optString("type")).put("base", version.optString("baseModel")).put("words", version.optJSONArray("trainedWords") != null ? version.optJSONArray("trainedWords") : new JSONArray())
                .put("preview", preview(version)).put("nsfw", model.optBoolean("nsfw")).put("tags", model.optJSONArray("tags") != null ? model.optJSONArray("tags") : new JSONArray());
            try (FileWriter w = new FileWriter(metaFile(f))) { w.write(m.toString()); }
        } catch (Exception ignored) { }
    }
    static File metaFile(File f) { return new File(f.getParent(), f.getName().replaceAll("\\.safetensors$", "") + ".json"); }
    static JSONObject meta(File f) { try { return new JSONObject(Models.read(metaFile(f))); } catch (Exception e) { return null; } }
    /** LoRA categories shown as filter chips: the Civitai tag that decides each one (the first match wins, in this order) */
    static final String[] CATS = {"character", "clothing", "poses", "style", "concept", "background", "objects"};
    static String catName(String c) {
        switch (c) {
            case "character": return L.s("Personaje", "Character"); case "clothing": return L.s("Ropa", "Clothing"); case "poses": return L.s("Pose", "Pose");
            case "style": return L.s("Estilo", "Style"); case "concept": return L.s("Concepto", "Concept"); case "background": return L.s("Fondo", "Background");
            case "objects": return L.s("Objeto", "Object"); default: return L.s("Sin categoría", "Uncategorised");
        }
    }
    /** the category of a library file: the user's own choice ("cat" in its JSON), else from the Civitai tags; "" when unknown */
    static String category(File f) {
        JSONObject m = meta(f); if (m == null) return "";
        String own = m.optString("cat", ""); if (!own.isEmpty()) return own;
        JSONArray t = m.optJSONArray("tags"); if (t == null) return "";
        java.util.Set<String> tags = new java.util.HashSet<>(); for (int i = 0; i < t.length(); i++) tags.add(t.optString(i).toLowerCase(Locale.ROOT).trim());
        if (tags.contains("pose")) tags.add("poses"); if (tags.contains("clothes") || tags.contains("outfit")) tags.add("clothing"); if (tags.contains("characters") || tags.contains("anime character")) tags.add("character");
        for (String c : CATS) if (tags.contains(c)) return c;
        return "";
    }
    /** stores the user's category for a file (creates the JSON for a locally imported one) */
    static void setCategory(File f, String cat) {
        try { JSONObject m = meta(f); if (m == null) m = new JSONObject().put("name", f.getName().replaceAll("(?i)\\.safetensors$", "")); m.put("cat", cat == null ? "" : cat); try (FileWriter w = new FileWriter(metaFile(f))) { w.write(m.toString()); } } catch (Exception ignored) { }
    }
    /** a library file downloaded before tags were stored: fetch its model's tags once (network) */
    static boolean fillTags(Context c, File f) {
        JSONObject m = meta(f); if (m == null || m.has("tags") || m.optLong("model_id") <= 0) return false;
        try { JSONObject model = model(c, m.optLong("model_id")); m.put("tags", model.optJSONArray("tags") != null ? model.optJSONArray("tags") : new JSONArray()); try (FileWriter w = new FileWriter(metaFile(f))) { w.write(m.toString()); } return true; }
        catch (Exception e) { return false; }
    }
    /** stores trigger words typed by the user (creates the JSON for a locally imported file) */
    static void setWords(File f, String csv) {
        try {
            JSONObject m = meta(f); if (m == null) m = new JSONObject().put("name", f.getName().replaceAll("(?i)\\.safetensors$", ""));
            JSONArray a = new JSONArray(); for (String t : csv.split(",")) { t = t.trim(); if (!t.isEmpty()) a.put(t); }
            m.put("words", a); try (FileWriter w = new FileWriter(metaFile(f))) { w.write(m.toString()); }
        } catch (Exception ignored) { }
    }
    /** "word1, word2" of a library file (empty if unknown) */
    static String words(File f) {
        JSONObject m = meta(f); if (m == null) return ""; JSONArray w = m.optJSONArray("words"); if (w == null) return "";
        List<String> out = new ArrayList<>(); for (int i = 0; i < w.length(); i++) { String s = w.optString(i).trim(); if (!s.isEmpty()) out.add(s); }
        return String.join(", ", out);
    }
}
