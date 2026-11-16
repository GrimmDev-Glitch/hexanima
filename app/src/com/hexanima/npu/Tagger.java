package com.hexanima.npu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** "Prompt from a picture": WD ViT Tagger v3 (SmilingWolf, Apache-2.0) as an fp16 HTP context (tagger/build.sh), run once by libvaerun.so.
 *  Input [1,448,448,3] BGR 0..255 (white-padded square), output [1,10861] tag probabilities in selected_tags.csv order. About 90 ms on the V73 NPU.
 *  The context (190 MB) and the tag list are downloaded on first use from GrimmDev-Glitch/hexanima-packs (tagger/), checked by SHA-256. */
final class Tagger {
    static final String BASE = "https://huggingface.co/GrimmDev-Glitch/hexanima-packs/resolve/main/tagger/";
    static final String CTX = "wd_vit_448_v73.bin", CTX_SHA = "6e22d05a8bef90b942404c0c260d901401a09f8f4960657baba55a84ec01cdf0", TAGS = "selected_tags.csv", TAGS_SHA = "298633d94d0031d2081c0893f29c82eab7f0df00b08483ba8f29d1e979441217";
    static final long CTX_SIZE = 192998256L, TAGS_SIZE = 308468L;
    static File dir(Context c) { return new File(c.getFilesDir(), "tagger"); }
    static boolean supported() { return Compat.dspArch().equals("v73"); }   // the context is built for the Snapdragon 8 Gen 2
    static boolean installed(Context c) { File d = dir(c); return new File(d, CTX).length() == CTX_SIZE && new File(d, TAGS).length() == TAGS_SIZE && new File(d, ".ok").isFile(); }

    /** downloads both files (resumable through Civitai.download's .part), checks them, marks .ok */
    static void install(Context c, Civitai.Progress pg, AtomicBoolean cancel) throws Exception {
        File d = dir(c); d.mkdirs();
        String[][] fs = {{TAGS, TAGS_SHA, String.valueOf(TAGS_SIZE)}, {CTX, CTX_SHA, String.valueOf(CTX_SIZE)}};
        for (String[] f : fs) {
            File out = new File(d, f[0]);
            if (out.length() != Long.parseLong(f[2]) || !sha(out).equals(f[1])) {
                Civitai.download(c, BASE + f[0], out, Long.parseLong(f[2]), f[0].equals(CTX) ? pg : (a, b) -> { }, cancel);
                if (!sha(out).equals(f[1])) { out.delete(); throw new IOException("SHA-256: " + f[0]); }
            }
        }
        new FileOutputStream(new File(d, ".ok")).close();
    }
    static String sha(File f) throws Exception {
        if (!f.isFile()) return "";
        MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[1 << 20];
        try (InputStream in = new FileInputStream(f)) { for (int r; (r = in.read(b)) > 0; ) md.update(b, 0, r); }
        StringBuilder s = new StringBuilder(); for (byte x : md.digest()) s.append(String.format("%02x", x)); return s.toString();
    }

    static final class Tag { final String name; final int cat; final float p; Tag(String n, int c, float p) { name = n; cat = c; this.p = p; } }

    /** tags of the picture, best first: general (category 0) above 0.35, characters (4) above 0.85; the rating tags are left out */
    static List<Tag> tag(Context c, Bitmap src) throws Exception { return tag(c, src, 0.35f); }
    /** thr: the lowest score of a general tag (copying an outfit reads more of its details: bows, trims, straps) */
    static List<Tag> tag(Context c, Bitmap src, float thr) throws Exception {
        final int S = 448, w = src.getWidth(), h = src.getHeight(), m = Math.max(w, h);
        Bitmap sq = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(sq); cv.drawColor(Color.WHITE);
        float s = (float) S / m; float nw = w * s, nh = h * s;
        cv.drawBitmap(src, null, new RectF((S - nw) / 2, (S - nh) / 2, (S + nw) / 2, (S + nh) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] pix = new int[S * S]; sq.getPixels(pix, 0, S, 0, 0, S, S); sq.recycle();
        File wd = new File(c.getCacheDir(), "tagger"); wd.mkdirs(); File in = new File(wd, "in.f32"), out = new File(wd, "out.f32");
        ByteBuffer bb = ByteBuffer.allocate(S * S * 3 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int q : pix) { bb.putFloat(q & 255); bb.putFloat((q >> 8) & 255); bb.putFloat((q >> 16) & 255); }   // B, G, R
        try (FileOutputStream fo = new FileOutputStream(in)) { fo.write(bb.array()); }
        String lib = c.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder pb = new ProcessBuilder(lib + "/libvaerun.so", lib + "/libQnnHtp.so", lib + "/libQnnSystem.so", new File(dir(c), CTX).getPath()).redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", lib + ":/system/lib64:/vendor/lib64"); pb.environment().put("ADSP_LIBRARY_PATH", lib);
        Process p = pb.start();
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(p.getInputStream())); Writer wr = new OutputStreamWriter(p.getOutputStream())) {
            StringBuilder log = new StringBuilder();
            for (String l; ; ) { l = rd.readLine(); if (l == null) throw new IOException("vaerun: " + log); log.append(l).append('\n'); if (l.startsWith("ready")) break; if (l.startsWith("ERROR")) throw new IOException(l); }
            wr.write("run " + in.getPath() + " " + out.getPath() + "\nquit\n"); wr.flush();
            for (String l; ; ) { l = rd.readLine(); if (l == null) throw new IOException("vaerun stopped"); if (l.startsWith("done")) break; if (l.startsWith("ERROR")) throw new IOException(l); }
        } finally { p.destroy(); }
        List<String[]> names = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(new File(dir(c), TAGS)), "UTF-8"))) {
            r.readLine(); for (String l; (l = r.readLine()) != null; ) { String[] a = l.split(",", -1); if (a.length >= 3) names.add(new String[]{a[1], a[2]}); }
        }
        float[] o = new float[names.size()];
        try (RandomAccessFile rf = new RandomAccessFile(out, "r"); FileChannel ch = rf.getChannel()) { ch.map(FileChannel.MapMode.READ_ONLY, 0, o.length * 4L).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(o); }
        List<Tag> t = new ArrayList<>();
        for (int i = 0; i < o.length; i++) {
            int cat = Integer.parseInt(names.get(i)[1]);
            if ((cat == 0 && o[i] >= thr) || (cat == 4 && o[i] >= 0.85f)) t.add(new Tag(names.get(i)[0], cat, o[i]));
        }
        t.sort((a, b) -> Float.compare(b.p, a.p));
        return t;
    }

    /** "character only": the tags that describe who it is (subject, hair, eyes, body traits, clothes, accessories, character names), not the pose,
     *  the framing, the place or the picture's style, so a new pose / style / scene can be written around them (for Anima and MOSS, which take no reference picture) */
    static final String[] LOOK = {"hair", "eyes", "ears", "tail", "wings", "horns", "halo", "skin", "freckles", "mole", "fang", "pupils", "eyelashes", "eyebrows", "bangs", "ponytail", "twintails", "braid", "bun", "ahoge", "sidelocks",
        "dress", "shirt", "uniform", "skirt", "jacket", "coat", "kimono", "hoodie", "sweater", "pants", "jeans", "shorts", "swimsuit", "bikini", "armor", "suit", "bowtie", "bow", "ribbon", "hat", "gloves", "boots", "sneakers", "shoes",
        "thighhighs", "socks", "pantyhose", "scarf", "apron", "cape", "glasses", "necktie", "vest", "leotard", "sleeves", "collar", "earrings", "necklace", "choker", "hairband", "headband", "hairclip", "hair ornament", "crown", "mask",
        "belt", "robe", "cloak", "raincoat", "serafuku", "sailor", "headwear", "jewelry", "bracelet", "nail polish", "tattoo", "makeup", "lipstick", "elf", "animal", "cat", "fox", "wolf", "rabbit",
        "nude", "naked", "topless", "bottomless", "breasts", "nipples", "areolae", "pussy", "penis", "testicles", "pubic hair", "navel", "abs", "muscular", "thighs", "ass", "cleavage", "lingerie", "panties", "bra", "underwear",
        "curvy", "plump", "petite", "skinny", "toned", "tan", "tanlines", "piercing", "body", "chest"};
    static final String[] NOT_LOOK = {"background", "looking", "smile", "open mouth", "closed mouth", "blush", "standing", "sitting", "lying", "walking", "full body", "upper body", "cowboy shot", "portrait", "close-up", "solo", "realistic", "shadow", "lips", "holding", "arms", "hand", "from ", "outdoors", "indoors"};
    static boolean isLook(Tag t) {
        if (t.cat == 4) return true;
        String n = clean(t.name);
        for (String s : SUBJECTS) if (n.equals(s)) return true;
        for (String x : NOT_LOOK) if (n.contains(x)) return false;
        for (String x : LOOK) if (n.equals(x) || n.endsWith(" " + x) || n.startsWith(x + " ") || n.contains(" " + x + " ")) return true;
        return false;
    }
    /** clothes of a picture (with their colours and patterns), for "copy its outfit" */
    static final String[] CLOTH_WORDS = {"dress", "shirt", "t-shirt", "uniform", "skirt", "jacket", "coat", "kimono", "yukata", "hoodie", "sweater", "pants", "jeans", "shorts", "swimsuit", "bikini", "armor", "suit", "bowtie",
        "bow", "ribbon", "hat", "cap", "gloves", "boots", "sneakers", "shoes", "sandals", "heels", "thighhighs", "socks", "pantyhose", "leggings", "legwear", "scarf", "apron", "cape", "necktie", "vest", "leotard", "sleeves", "collar",
        "choker", "belt", "robe", "cloak", "serafuku", "sailor", "headwear", "lingerie", "panties", "bra", "underwear", "bodysuit", "corset", "top", "crop top", "tank top", "camisole", "blouse", "cardigan", "overalls", "frills",
        "lace", "polka dot", "striped", "plaid", "floral print", "print", "halterneck", "strapless", "string", "side-tie", "o-ring", "bikini top", "bikini bottom", "one-piece", "highleg", "maid", "nurse", "china dress", "gown", "off-shoulder", "trim", "halter", "bandeau", "ruffle", "straps", "ribbon trim", "lace trim"};
    static boolean isClothing(Tag t) {
        if (t.cat != 0) return false; String n = " " + clean(t.name) + " ";
        if (n.contains(" hair ") || n.contains(" eyes ")) return false;
        for (String w : CLOTH_WORDS) if (n.contains(" " + w + " ")) return true;
        return false;
    }
    /** the pose of a picture, for "copy its pose" */
    static final String[] POSE_WORDS = {"standing", "sitting", "squatting", "kneeling", "lying", "crouching", "jumping", "walking", "running", "leaning", "bent over", "arm", "arms", "hand on", "hands on", "hand up", "hands up",
        "legs apart", "spread legs", "crossed legs", "legs up", "knees up", "knee up", "on back", "on stomach", "on side", "all fours", "from behind", "looking back", "head tilt", "v", "peace sign", "waving", "salute", "seiza",
        "wariza", "indian style", "contrapposto", "stretching", "outstretched", "spread arms", "hugging own legs", "fetal position", "one knee", "straddling", "reaching", "pointing", "hand in own hair", "hand on own head"};
    static boolean isPose(Tag t) {
        if (t.cat != 0) return false; String n = " " + clean(t.name) + " ";
        if (n.contains(" armor ") || n.contains("strap") || n.contains("sleeve") || n.contains("band ") || n.contains("warmers") || n.contains("armlet")) return false;
        for (String w : POSE_WORDS) if (n.contains(" " + w + " ")) return true;
        return false;
    }
    static List<String> pick(List<Tag> t, boolean clothes, int max) {
        List<String> o = new ArrayList<>(); for (Tag x : t) if ((clothes ? isClothing(x) : isPose(x)) && !clean(x.name).equals("swimsuit")) { o.add(clean(x.name)); if (o.size() >= max) break; }
        List<String> keep = new ArrayList<>(); for (String a : o) { boolean sub = false; for (String b : o) if (!a.equals(b) && b.contains(a)) sub = true; if (!sub) keep.add(a); }   // "bikini" goes when "black bikini" is there
        return keep;
    }
    static List<Tag> characterOnly(List<Tag> t) { List<Tag> o = new ArrayList<>(); for (Tag x : t) if (isLook(x)) o.add(x); return o; }
    static String clean(String n) { return n.length() <= 3 ? n : n.replace('_', ' '); }   // "silver_hair" -> "silver hair"; short emoticons like ^_^ stay
    static final int SHORT = 0, MEDIUM = 1, LONG = 2;
    static final String[] SUBJECTS = {"1girl", "2girls", "3girls", "multiple girls", "1boy", "2boys", "multiple boys", "1other", "no humans"};
    static final String[] FILLER = {"solo", "general", "sensitive", "questionable", "explicit", "simple background", "looking at viewer"};

    /** Danbooru tags for Anima: short = subject and the strongest few, medium = up to 14, long = all (up to 40) */
    static String tags(List<Tag> t, int len) {
        int max = len == SHORT ? 6 : len == MEDIUM ? 14 : 40; List<String> out = new ArrayList<>();
        for (Tag x : t) { if (x.cat == 4) out.add(clean(x.name)); }   // characters first
        List<String> all = new ArrayList<>(); for (Tag x : t) if (x.cat == 0) all.add(clean(x.name));
        Set<String> drop = new HashSet<>(); for (String a : all) for (String b : all) if (!a.equals(b) && b.endsWith(" " + a)) drop.add(a);   // "shirt" when "white shirt" is there
        if (len != LONG) drop.addAll(Arrays.asList("solo", "general", "simple background"));
        for (String n : all) { if (drop.contains(n)) continue; if (out.size() >= max) break; out.add(n); }
        return String.join(", ", out);
    }

    /** a plain sentence for MOSS / Qwen / Mage: short "a girl with long silver hair", medium adds eyes, clothes, expression, pose, place; long adds the other tags */
    static String sentence(List<Tag> t, int len) {
        Set<String> n = new LinkedHashSet<>(); for (Tag x : t) if (x.cat == 0) n.add(clean(x.name));
        List<String> chars = new ArrayList<>(); for (Tag x : t) if (x.cat == 4) chars.add(clean(x.name));
        String subj = n.contains("no humans") ? "a scene" : n.contains("2girls") ? "two girls" : n.contains("3girls") ? "three girls" : n.contains("multiple girls") ? "a group of girls"
            : n.contains("2boys") ? "two boys" : n.contains("multiple boys") ? "a group of boys" : n.contains("1boy") && n.contains("1girl") ? "a boy and a girl" : n.contains("1boy") ? "a boy" : n.contains("1girl") ? "a girl" : "a person";
        if (!chars.isEmpty()) subj = chars.get(0) + " (" + subj + ")";
        Set<String> used = new HashSet<>(Arrays.asList(SUBJECTS)); used.addAll(Arrays.asList(FILLER));
        String hairLen = null, hairCol = null;
        for (String x : n) if (x.equals("very long hair") || x.equals("long hair") || x.equals("medium hair") || x.equals("short hair")) { if (hairLen == null) hairLen = x; used.add(x); }   // the most likely length
        for (String x : n) if (x.endsWith(" hair") && !x.contains("long") && !x.contains("short") && !x.contains("medium") && !used.contains(x)) { hairCol = x.substring(0, x.length() - 5); used.add(x); break; }
        String hair = hairLen != null || hairCol != null ? ((hairLen != null ? hairLen.replace(" hair", "") + " " : "") + (hairCol != null ? hairCol + " " : "") + "hair").trim() : null;
        StringBuilder s = new StringBuilder(subj.substring(0, 1).toUpperCase(Locale.ROOT) + subj.substring(1));
        if (hair != null) s.append(" with ").append(hair);
        if (len == SHORT) return s.append('.').toString();
        String eyes = null; for (String x : n) if (x.endsWith(" eyes") && !used.contains(x) && !x.startsWith("closed")) { eyes = x; used.add(x); break; }
        if (eyes != null) s.append(hair != null ? " and " : " with ").append(eyes);
        List<String> wear = new ArrayList<>();
        String[] cloth = {"dress", "shirt", "t-shirt", "uniform", "skirt", "jacket", "coat", "kimono", "hoodie", "sweater", "pants", "jeans", "shorts", "swimsuit", "bikini", "armor", "suit", "bowtie", "bow", "ribbon", "hat", "gloves", "boots", "sneakers", "thighhighs", "scarf", "apron", "cape", "glasses", "necktie", "vest", "leotard"};
        for (String x : n) { if (used.contains(x)) continue; for (String cl : cloth) if (x.equals(cl) || x.endsWith(" " + cl)) { wear.add(x); used.add(x); break; } }
        wear = dropContained(wear);
        int nw = len == MEDIUM ? 3 : wear.size();
        List<String> wa = new ArrayList<>(); for (String x : wear.subList(0, Math.min(nw, wear.size()))) wa.add(article(x));
        if (!wa.isEmpty()) s.append(", wearing ").append(list(wa));
        String nud = first(n, used, "completely nude", "nude", "naked", "topless", "bottomless");   // nudity is part of the description, not a detail
        if (nud != null) s.append(wa.isEmpty() ? ", " : " and ").append(nud.equals("completely nude") ? "completely nude" : nud);
        String[] body = {"huge breasts", "large breasts", "medium breasts", "small breasts", "flat chest", "nipples", "pussy", "penis", "pubic hair", "abs", "muscular", "curvy", "thick thighs", "wide hips", "cleavage", "navel"};
        List<String> bd = new ArrayList<>(); for (String a : body) if (n.contains(a)) { bd.add(a); used.add(a); }
        if (!bd.isEmpty()) s.append(", ").append(list(bd.subList(0, Math.min(len == MEDIUM ? 4 : bd.size(), bd.size()))));
        String[] acts = {"smile", "open mouth", "blush", "standing", "sitting", "lying", "walking", "running", "holding", "arms at sides", "hand up", "looking back", "from side", "full body", "upper body", "cowboy shot", "portrait", "close-up"};
        List<String> act = new ArrayList<>(); for (String a : acts) if (n.contains(a)) { act.add(a.equals("smile") ? "smiling" : a); used.add(a); }
        if (!act.isEmpty()) s.append(", ").append(list(act.subList(0, Math.min(len == MEDIUM ? 2 : act.size(), act.size()))));
        String[] places = {"outdoors", "indoors", "cherry blossoms", "sky", "night", "city", "beach", "forest", "classroom", "bedroom", "street", "water", "snow", "flower", "grey background", "white background", "black background", "gradient background"};
        List<String> pl = new ArrayList<>(); for (String a : places) if (n.contains(a)) { pl.add(a); used.add(a); }
        if (!pl.isEmpty()) s.append(", ").append(list(pl.subList(0, Math.min(len == MEDIUM ? 2 : pl.size(), pl.size()))));
        if (len == LONG) { List<String> rest = new ArrayList<>(); for (String x : n) if (!used.contains(x) && rest.size() < 20) rest.add(x); if (!rest.isEmpty()) s.append(". Details: ").append(String.join(", ", rest)); }
        return s.append('.').toString();
    }
    static final String[] PLURAL = {"jeans", "pants", "shorts", "sneakers", "boots", "gloves", "thighhighs", "glasses", "shoes", "socks", "pantyhose"};
    static String article(String x) { for (String p : PLURAL) if (x.equals(p) || x.endsWith(" " + p)) return x; return ("aeiou".indexOf(x.charAt(0)) >= 0 ? "an " : "a ") + x; }
    static String first(Set<String> n, Set<String> used, String... opts) { for (String o : opts) if (n.contains(o)) { used.add(o); return o; } return null; }
    static List<String> dropContained(List<String> l) { List<String> o = new ArrayList<>(); for (String a : l) { boolean sub = false; for (String b : l) if (!a.equals(b) && b.endsWith(" " + a)) sub = true; if (!sub) o.add(a); } return o; }   // "dress" goes when "red dress" is there
    static String list(List<String> l) { if (l.size() <= 1) return l.isEmpty() ? "" : l.get(0); return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1); }
}
