package com.hexanima.npu;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.json.JSONObject;

/** Generation settings inside the PNG (iTXt chunks): "anima" = our JSON, "parameters" = the A1111-style text other tools understand. */
final class PngMeta {
    static volatile boolean keep = true;   // Settings "Keep metadata in the picture"; off = the PNG has no prompt / seed / model chunk (the History keeps its JSON)
    static byte[] embed(byte[] png, JSONObject m) {
        if (!keep) return png;
        try {
            String params = parameters(m);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(png, 0, 33);   // signature + IHDR (8 + 25 bytes)
            chunk(o, "anima", m.toString()); chunk(o, "parameters", params);
            // the rest, without the text chunks sd.cpp wrote itself ("parameters" with its own settings, which would confuse Civitai) or an older copy of ours
            int q = 33;
            while (q + 12 <= png.length) {
                int len = ((png[q] & 255) << 24) | ((png[q + 1] & 255) << 16) | ((png[q + 2] & 255) << 8) | (png[q + 3] & 255);
                if (len < 0 || q + 12 + len > png.length) { o.write(png, q, png.length - q); q = png.length; break; }
                String type = new String(png, q + 4, 4, StandardCharsets.ISO_8859_1); boolean drop = false;
                if (type.equals("tEXt") || type.equals("iTXt") || type.equals("zTXt")) { int z = q + 8; while (z < q + 8 + len && png[z] != 0) z++; String k = new String(png, q + 8, z - q - 8, StandardCharsets.ISO_8859_1); drop = k.equals("parameters") || k.equals("anima"); }
                if (!drop) o.write(png, q, 12 + len);
                q += 12 + len;
            }
            if (q < png.length) o.write(png, q, png.length - q);
            return o.toByteArray();
        } catch (Exception e) { return png; }
    }

    static volatile java.io.File home;   // the app's files dir (set by Main): LoRA files and their Civitai .json next to them
    static volatile String appVersion = "";
    private static final java.util.Map<String, String> HASHES = new java.util.concurrent.ConcurrentHashMap<>();
    /** AutoV2: the first 10 hex digits of the file's SHA-256, upper case (what Civitai and A1111 put in "Lora hashes"); cached by path + size + time */
    static String autoV2(java.io.File f) {
        String k = f.getPath() + ":" + f.length() + ":" + f.lastModified(); String h = HASHES.get(k); if (h != null) return h;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256"); byte[] b = new byte[1 << 20];
            for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n);
            StringBuilder sb = new StringBuilder(); for (byte x : md.digest()) sb.append(String.format("%02X", x));
            h = sb.substring(0, 10); HASHES.put(k, h); return h;
        } catch (Exception e) { return null; }
    }
    static String sampler(String s) {   // sd.cpp names -> the names A1111 / Civitai show
        switch (s == null ? "" : s) {
            case "euler": return "Euler"; case "euler_a": return "Euler a"; case "heun": return "Heun"; case "dpm2": return "DPM2"; case "dpm++2s_a": return "DPM++ 2S a";
            case "dpm++2m": return "DPM++ 2M"; case "dpm++2mv2": return "DPM++ 2M v2"; case "ipndm": return "IPNDM"; case "ipndm_v": return "IPNDM_V"; case "lcm": return "LCM"; case "ddim_trailing": return "DDIM"; case "tcd": return "TCD";
            default: return s == null || s.isEmpty() ? "Euler" : s;
        }
    }
    /** the A1111-style text: prompt (with its <lora:name:weight> tags), negative, then the settings, the LoRA hashes and the Civitai ids of the model and
     *  LoRAs that came from Civitai (their .json keeps the version id), so an upload to Civitai links the resources */
    static String parameters(JSONObject m) {
        StringBuilder p = new StringBuilder(m.optString("prompt"));
        String neg = m.optString("neg"); if (!neg.isEmpty()) p.append("\nNegative prompt: ").append(neg.replace('\n', ' '));
        p.append("\nSteps: ").append(m.optInt("steps", 4)).append(", Sampler: ").append(sampler(m.optString("sampler")));
        String sch = m.optString("scheduler"); if (!sch.isEmpty() && !sch.equals("discrete")) p.append(", Schedule type: ").append(sch);
        p.append(", CFG scale: ").append(m.optString("cfg", "1")).append(", Seed: ").append(m.optString("seed")).append(", Size: ").append(m.optInt("w")).append("x").append(m.optInt("h"));
        p.append(", Model: ").append(m.optString("model").replace(",", " "));
        int upscale = m.optInt("upscaled");
        if (upscale > 1) p.append(", Hires upscale: ").append(upscale).append(", Hires upscaler: ").append(m.optString("upscaler", "unknown"));
        org.json.JSONArray corrections = m.optJSONArray("corrections");
        if (corrections != null) for (int i = 0; i < corrections.length(); i++) {
            JSONObject step = corrections.optJSONObject(i); if (step == null) continue;
            String type = step.optString("type"), prompt = step.optString("prompt");
            if (type.equals("face") || type.equals("hands")) {
                p.append(", ADetailer model: Qwen masked edit (").append(type).append(")");
                if (!prompt.isEmpty()) p.append(", ADetailer prompt: ").append(prompt.replace(',', ' '));
                String seed = step.optString("seed"); if (!seed.isEmpty()) p.append(", ADetailer seed: ").append(seed);
            } else if (type.equals("resolution")) {
                p.append(", Hires upscale: ").append(step.optInt("factor", upscale)).append(", Hires upscaler: ").append(step.optString("upscaler", m.optString("upscaler", "unknown")));
            }
        }
        org.json.JSONArray civ = new org.json.JSONArray();
        String id = m.optString("id"); if (id.startsWith("cv")) { try { civ.put(new JSONObject().put("type", "checkpoint").put("modelVersionId", Long.parseLong(id.substring(2)))); } catch (Exception ignored) { } }
        org.json.JSONArray res = m.optJSONArray("res"); StringBuilder lh = new StringBuilder();
        if (res != null) for (int i = 0; i < res.length(); i++) {
            String it = res.optString(i); int st = it.lastIndexOf('*'); java.io.File f = new java.io.File(st > 0 ? it.substring(0, st) : it);
            float w = 1f; try { if (st > 0) w = Float.parseFloat(it.substring(st + 1)); } catch (Exception ignored) { }
            String name = f.getName().replaceAll("\\.safetensors$", ""), h = f.isFile() ? autoV2(f) : null;
            if (h != null) { if (lh.length() > 0) lh.append(", "); lh.append(name.replace(",", " ").replace(":", " ")).append(": ").append(h); }
            java.io.File js = new java.io.File(f.getParent(), name + ".json");
            if (js.isFile()) try { JSONObject j = new JSONObject(Models.read(js)); long v = j.optLong("version_id"); if (v > 0) civ.put(new JSONObject().put("type", "lora").put("weight", w).put("modelVersionId", v)); } catch (Exception ignored) { }
        }
        if (lh.length() > 0) p.append(", Lora hashes: \"").append(lh).append("\"");
        if (!appVersion.isEmpty()) p.append(", Version: HexAnima ").append(appVersion);
        if (civ.length() > 0) p.append(", Civitai resources: ").append(civ.toString());
        return p.toString();
    }

    static void chunk(ByteArrayOutputStream o, String key, String text) throws Exception {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        d.write(key.getBytes(StandardCharsets.UTF_8)); d.write(0); d.write(0); d.write(0); d.write(0); d.write(0);   // key, NUL, not compressed, method, empty language, empty translated key
        d.write(text.getBytes(StandardCharsets.UTF_8));
        byte[] data = d.toByteArray(), type = {'i', 'T', 'X', 't'};
        CRC32 crc = new CRC32(); crc.update(type); crc.update(data);
        int2(o, data.length); o.write(type); o.write(data); int2(o, (int) crc.getValue());
    }

    static void int2(ByteArrayOutputStream o, int v) { o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v); }

    /** the text stored under `key` (iTXt without compression, or tEXt), null if absent */
    static String read(byte[] b, String key) {
        int p = 8;
        while (p + 12 <= b.length) {
            int len = ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16) | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255);
            String type = new String(b, p + 4, 4, StandardCharsets.ISO_8859_1);
            if (len < 0 || p + 12 + len > b.length) return null;
            if (type.equals("IDAT")) return null;   // text chunks come before the picture data
            if (type.equals("iTXt") || type.equals("tEXt")) {
                int s = p + 8, e = s + len, z = s; while (z < e && b[z] != 0) z++;
                if (new String(b, s, z - s, StandardCharsets.UTF_8).equals(key)) {
                    int t = z + 1;
                    if (type.equals("iTXt")) { if (b[t] != 0) return null; t += 2; while (t < e && b[t] != 0) t++; t++; while (t < e && b[t] != 0) t++; t++; }   // flags, language, translated key
                    return new String(b, t, e - t, type.equals("iTXt") ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
                }
            }
            p += 12 + len;
        }
        return null;
    }

    /** settings of a picture: our JSON, or parsed from an A1111-style "parameters" text; null if the file has none */
    static JSONObject settings(byte[] png) {
        try {
            String j = read(png, "anima");
            if (j != null) return new JSONObject(j);
            String t = read(png, "parameters");
            if (t == null) return null;
            JSONObject m = new JSONObject();
            int i = t.indexOf("\nSteps:");
            m.put("prompt", (i >= 0 ? t.substring(0, i) : t).trim());
            java.util.regex.Matcher s = java.util.regex.Pattern.compile("Seed: (-?\\d+)").matcher(t), z = java.util.regex.Pattern.compile("Size: (\\d+)x(\\d+)").matcher(t);
            if (s.find()) m.put("seed", s.group(1));
            if (z.find()) { m.put("w", Integer.parseInt(z.group(1))); m.put("h", Integer.parseInt(z.group(2))); }
            return m;
        } catch (Exception e) { return null; }
    }
}
