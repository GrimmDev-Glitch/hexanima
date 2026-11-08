package com.hexanima.npu;

import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** User LoRA for the Anima 2B models. The picked .safetensors is copied to files/anima/lora/user.safetensors and checked here from its header (names, shapes, rank, dtype).
 *  anima.sh hands it to megarun (LORA / LORA_S), which folds it into the fp16 block weights while it loads them (hexagon/mega/lora_fold.h): a new LoRA or strength reloads the
 *  model (a few seconds), nothing extra is written to storage. Only the DiT part is applied: the text encoder is a precompiled NPU graph. The 2.9B models (q8) are left as they are. */
final class AnimaLora {
    static File dir(File home) { return new File(new File(home, "anima"), "lora"); }
    static File src(File home) { return new File(dir(home), "user.safetensors"); }

    /* The LoRA dir is a library: every picked or downloaded LoRA is kept under its own (cleaned) file name. One of them is the selected LoRA of the
       main screen; any of them can also be called from the prompt as <lora:name> or <lora:name:weight> (name = file name without .safetensors). */
    static String fileName(String display) {
        String b = display.replaceAll("(?i)\\.safetensors$", "").replaceAll("[^A-Za-z0-9._-]+", "_");
        return (b.isEmpty() ? "lora" : b) + ".safetensors";
    }
    static File find(File home, String name) {
        File[] fs = dir(home).listFiles(); if (fs == null) return null;
        String want = name.trim().replaceAll("(?i)\\.safetensors$", "");
        for (File f : fs) if (f.getName().endsWith(".safetensors") && f.getName().substring(0, f.getName().length() - 12).equalsIgnoreCase(want)) return f;
        String alt = fileName(want); for (File f : fs) if (f.getName().equalsIgnoreCase(alt)) return f;   // names typed with spaces etc.
        return null;
    }
    static List<String> names(File home) {
        List<String> out = new ArrayList<>(); File[] fs = dir(home).listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".safetensors") && !f.getName().startsWith("new.")) out.add(f.getName().substring(0, f.getName().length() - 12));
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER); return out;
    }
    /** <lora:name> / <lora:name:weight> tags of a prompt: the prompt without them, the "file*weight" items found and the names not in the library */
    static final class Tags { String prompt; List<String> items = new ArrayList<>(), missing = new ArrayList<>(), used = new ArrayList<>(); }
    static Tags tags(File home, String prompt) {
        Tags t = new Tags(); java.util.regex.Matcher m = java.util.regex.Pattern.compile("<lora:([^:>]+)(?::\\s*(-?[0-9]*\\.?[0-9]+))?\\s*>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(prompt);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            float w = 1f; try { if (m.group(2) != null) w = Float.parseFloat(m.group(2)); } catch (NumberFormatException ignored) { }
            File f = find(home, m.group(1));
            if (f == null) t.missing.add(m.group(1).trim());
            else if (w != 0f) { t.items.add(f.getPath() + "*" + String.format(Locale.US, "%.2f", w)); t.used.add(m.group(1).trim() + ":" + String.format(Locale.US, "%.2f", w)); }
            m.appendReplacement(sb, "");
        }
        m.appendTail(sb);
        t.prompt = sb.toString().replaceAll("[ \\t]*,[ \\t]*(,[ \\t]*)+", ", ").replaceAll("\\s+\\n", "\n").trim();
        return t;
    }

    static final class Check {
        boolean ok; String why = ""; int blocks, mats, rank; boolean te;
        String message() {
            if (!ok) return why;
            StringBuilder s = new StringBuilder(L.f("Подходит: ранг %d, блоков %d из 28", "Compatible: rank %d, %d of 28 blocks", rank, blocks));
            if (te) s.append("\n").append(L.s("Часть для текстового энкодера не применяется", "The text-encoder part is not applied"));
            return s.toString();
        }
    }
    static Check fail(String why) { Check c = new Check(); c.why = why; return c; }

    /** in/out features of the 10 block matrices of Anima 2B (D = 2048, text context 1024, MLP 8192) */
    static final Map<String, int[]> MATS = new HashMap<>();
    static {
        for (String m : new String[]{"self_attn_q_proj", "self_attn_k_proj", "self_attn_v_proj", "self_attn_output_proj", "cross_attn_q_proj", "cross_attn_output_proj"}) MATS.put(m, new int[]{2048, 2048});
        MATS.put("cross_attn_k_proj", new int[]{1024, 2048}); MATS.put("cross_attn_v_proj", new int[]{1024, 2048});
        MATS.put("mlp_layer1", new int[]{2048, 8192}); MATS.put("mlp_layer2", new int[]{8192, 2048});
    }

    static Check check(File f) {
        JSONObject h;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            byte[] lb = new byte[8]; in.readFully(lb);
            long n = 0; for (int i = 7; i >= 0; i--) n = (n << 8) | (lb[i] & 0xFF);
            if (n <= 0 || n > (64 << 20) || n > f.length()) return fail(L.s("Это не файл .safetensors.", "This is not a .safetensors file."));
            byte[] hb = new byte[(int) n]; in.readFully(hb); h = new JSONObject(new String(hb, "UTF-8"));
        } catch (Exception e) { return fail(L.f("Не удалось прочитать файл (%s). Нужен .safetensors.", "Cannot read the file (%s). A .safetensors file is needed.", e.getMessage())); }
        Check c = new Check(); Set<Integer> blocks = new HashSet<>(); boolean other = false;
        for (Iterator<String> it = h.keys(); it.hasNext(); ) {
            String k = it.next(); if (k.equals("__metadata__")) continue;
            if (k.startsWith("lora_te")) { c.te = true; continue; }
            if (k.startsWith("lora_unet_input_blocks") || k.startsWith("lora_unet_down_blocks") || k.startsWith("lora_unet_double_blocks") || k.startsWith("lora_unet_single_blocks") || k.startsWith("lora_unet_joint_blocks")) { other = true; continue; }
            if (!k.startsWith("lora_unet_blocks_") || !k.endsWith(".lora_down.weight")) continue;
            String base = k.substring("lora_unet_blocks_".length(), k.length() - ".lora_down.weight".length());
            int us = base.indexOf('_'); if (us < 0) continue;
            int b; try { b = Integer.parseInt(base.substring(0, us)); } catch (NumberFormatException e) { continue; }
            int[] io = MATS.get(base.substring(us + 1)); if (io == null) continue;
            JSONObject d = h.optJSONObject(k), u = h.optJSONObject(k.replace(".lora_down.", ".lora_up."));
            if (d == null || u == null) continue;
            String dt = d.optString("dtype");
            if (!dt.equals("F32") && !dt.equals("F16") && !dt.equals("BF16")) return fail(L.s("Неподдерживаемый формат весов, нужны F32, F16 или BF16.", "Unsupported weight format: F32, F16 or BF16 are needed."));
            org.json.JSONArray ds = d.optJSONArray("shape"), uss = u.optJSONArray("shape");
            if (ds == null || uss == null) continue;
            int r = ds.optInt(0);
            if (ds.optInt(1) != io[0] || uss.optInt(0) != io[1])
                return fail(L.s("Размеры слоёв не совпадают с Anima 2B. Скорее всего это LoRA для другой модели или версии.", "Layer sizes do not match Anima 2B. It is probably a LoRA for another model or version."));
            if (b < 0 || b >= 28) return fail(L.s("В LoRA есть блоки, которых нет в Anima 2B.", "The LoRA has blocks that Anima 2B does not have."));
            blocks.add(b); c.mats++; c.rank = Math.max(c.rank, r);
        }
        if (c.mats == 0) return fail(other ? L.s("Это LoRA для другой модели (SDXL / Illustrious, FLUX или похожей). Для Anima она не подходит.", "This LoRA is for another model (SDXL / Illustrious, FLUX or similar). It does not fit Anima.")
                : c.te ? L.s("В файле только LoRA для текстового энкодера. Такие пока не поддерживаются.", "The file only has text-encoder LoRA weights. Those are not supported yet.")
                : L.s("Файл не похож на LoRA для Anima: не найдены слои lora_unet_blocks_N_*.", "This file does not look like an Anima LoRA: the lora_unet_blocks_N_* layers are missing."));
        c.ok = true; c.blocks = blocks.size(); return c;
    }
}
