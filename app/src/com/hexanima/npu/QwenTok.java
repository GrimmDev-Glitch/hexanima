package com.hexanima.npu;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Qwen2/3.5 byte-level BPE tokenizer (tokenizer.json -> tok_vocab.txt / tok_merges.txt / tok_special.txt) + the MOSS-Image chat template. */
public class QwenTok {
    private static final Pattern PRE = Pattern.compile("(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+");
    private final HashMap<String, Integer> vocab = new HashMap<>(400000);
    private final HashMap<String, Integer> rank = new HashMap<>(400000);
    private final HashMap<String, Integer> special = new HashMap<>();
    private final String[] b2u = new String[256];
    private final HashMap<String, int[]> cache = new HashMap<>();

    public QwenTok(String dir) throws Exception {
        int[] bs = new int[256]; int n = 0; boolean[] in = new boolean[256];
        for (int b = 33; b <= 126; b++) in[b] = true; for (int b = 161; b <= 172; b++) in[b] = true; for (int b = 174; b <= 255; b++) in[b] = true;
        int extra = 0;
        for (int b = 0; b < 256; b++) b2u[b] = String.valueOf((char) (in[b] ? b : 256 + extra++));
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(dir + "/tok_vocab.txt"), "UTF-8"), 1 << 16)) {
            String l; int id = 0; while ((l = r.readLine()) != null) vocab.put(l, id++);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(dir + "/tok_merges.txt"), "UTF-8"), 1 << 16)) {
            String l; int i = 0; while ((l = r.readLine()) != null) rank.put(l, i++);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(dir + "/tok_special.txt"), "UTF-8"))) {
            String l; while ((l = r.readLine()) != null) { int t = l.indexOf('\t'); if (t > 0) special.put(l.substring(t + 1), Integer.parseInt(l.substring(0, t))); }
        }
    }

    private int[] bpe(String w) {   // w: byte-level unicode string of one pre-token
        int[] c = cache.get(w); if (c != null) return c;
        ArrayList<String> p = new ArrayList<>(); for (int i = 0; i < w.length(); i++) p.add(w.substring(i, i + 1));
        while (p.size() > 1) {
            int best = Integer.MAX_VALUE, bi = -1;
            for (int i = 0; i + 1 < p.size(); i++) { Integer r = rank.get(p.get(i) + " " + p.get(i + 1)); if (r != null && r < best) { best = r; bi = i; } }
            if (bi < 0) break;
            p.set(bi, p.get(bi) + p.get(bi + 1)); p.remove(bi + 1);
        }
        int[] out = new int[p.size()]; for (int i = 0; i < out.length; i++) out[i] = vocab.get(p.get(i));
        cache.put(w, out); return out;
    }

    private void text(String s, List<Integer> out) {
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        Matcher m = PRE.matcher(s);
        while (m.find()) {
            byte[] bytes; try { bytes = m.group().getBytes("UTF-8"); } catch (Exception e) { throw new RuntimeException(e); }
            StringBuilder sb = new StringBuilder(); for (byte b : bytes) sb.append(b2u[b & 255]);
            for (int id : bpe(sb.toString())) out.add(id);
        }
    }

    /** encode with special tokens recognised in the text (like the HF tokenizer's added tokens) */
    public int[] encode(String s) {
        ArrayList<Integer> out = new ArrayList<>(); int pos = 0;
        while (pos < s.length()) {
            int bestAt = -1; String bestTok = null;
            for (String t : special.keySet()) { int at = s.indexOf(t, pos); if (at >= 0 && (bestAt < 0 || at < bestAt || (at == bestAt && t.length() > bestTok.length()))) { bestAt = at; bestTok = t; } }
            if (bestAt < 0) { text(s.substring(pos), out); break; }
            if (bestAt > pos) text(s.substring(pos, bestAt), out);
            out.add(special.get(bestTok)); pos = bestAt + bestTok.length();
        }
        int[] r = new int[out.size()]; for (int i = 0; i < r.length; i++) r[i] = out.get(i); return r;
    }

    /** MOSS-Image prompt: the Qwen3.5 chat template with the thinking generation prompt (like transformers' apply_chat_template(enable_thinking=True)) */
    public int[] encodePrompt(String prompt) { return encode("<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n<think>\n"); }
}
