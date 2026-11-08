package com.hexanima.npu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
 *  Only Anima files are offered (they run on the NPU): LoRAs go to files/anima/lora (usable at once), checkpoints are converted on the phone (AnimaImport).
 *  Next to every file a <name>.json keeps what the app shows later (Civitai ids, base model, trigger words, preview). */
final class Civitai {
    static final String API = "https://civitai.com/api/v1/";
    static String key(Context c) { return c.getSharedPreferences("anima", 0).getString("civitai_key", "").trim(); }

    /** the one base model the app runs (SDXL-family models have no NPU path on the phone and were too slow and hot on the CPU) */
    static final String BASE = "Anima";
    static boolean anima(String base) { return base != null && base.toLowerCase(Locale.ROOT).startsWith("anima"); }


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
    static JSONObject search(Context c, String query, String type, String base, String sort, boolean nsfw, String cursor) throws Exception {
        StringBuilder u = new StringBuilder(API + "models?limit=20");
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
        if (type.equalsIgnoreCase("Checkpoint") && anima(base)) return new File(AnimaImport.importDir(home), AnimaLora.fileName(fileName));   // converted on the phone after the download (AnimaImport)
        msg[0] = L.f("База «%s» не поддерживается: только модели и LoRA для Anima (они работают на NPU)", "Base model \"%s\" is not supported: only Anima models and LoRAs (they run on the NPU)", base); return null;
    }

    /** sidecar <name>.json: what the library shows and inserts into the prompt */
    static void writeMeta(File f, JSONObject model, JSONObject version) {
        try {
            JSONObject m = new JSONObject().put("name", model.optString("name")).put("version", version.optString("name")).put("model_id", model.optLong("id")).put("version_id", version.optLong("id"))
                .put("type", model.optString("type")).put("base", version.optString("baseModel")).put("words", version.optJSONArray("trainedWords") != null ? version.optJSONArray("trainedWords") : new JSONArray())
                .put("preview", preview(version)).put("nsfw", model.optBoolean("nsfw"));
            try (FileWriter w = new FileWriter(metaFile(f))) { w.write(m.toString()); }
        } catch (Exception ignored) { }
    }
    static File metaFile(File f) { return new File(f.getParent(), f.getName().replaceAll("\\.safetensors$", "") + ".json"); }
    static JSONObject meta(File f) { try { return new JSONObject(Models.read(metaFile(f))); } catch (Exception e) { return null; } }
    /** "word1, word2" of a library file (empty if unknown) */
    static String words(File f) {
        JSONObject m = meta(f); if (m == null) return ""; JSONArray w = m.optJSONArray("words"); if (w == null) return "";
        List<String> out = new ArrayList<>(); for (int i = 0; i < w.length(); i++) { String s = w.optString(i).trim(); if (!s.isEmpty()) out.add(s); }
        return String.join(", ", out);
    }
}
