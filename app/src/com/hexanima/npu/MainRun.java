package com.hexanima.npu;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.widget.*;
import java.io.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** part of the main screen (split out of Main.java): MainRun */
abstract class MainRun extends MainLora {
    volatile long picStart, picExpect;   // start of the current picture and its expected seconds (for "~30 s left")
    static final String AD_SHA = "aa44f31aed57284eec9fe6c8152c03e7625424f78ef276868baae7efc952c3d0";
    static final String AD_URL = "https://huggingface.co/GrimmDev-Glitch/hexanima-packs/resolve/main/adetailer/face_yolov8n.safetensors";
    static final int STEPS = 4;   // the models are distilled for 4 steps (see Info)
    ProgressBar bar, ramBar;
    volatile int curIdx, curN = 1;
    LinearLayout editBox; ImageView editThumb; TextView editInfo; Button editPick, editDrop, editMaskBtn, editMaskClear; volatile Bitmap editBm;   // Qwen-Image: the photo to edit
    ImageView img;
    String model = "";   // id of the selected installed model; anima.sh gets MID/MDIR
    LinearLayout editOpts, editRow, refsRow, refActs; volatile boolean refining; Button editAddRef; Button compareBtn; volatile File pendingCompare;   // Qwen edit options, before/after view, the side-by-side of the last single edit
    TextView chipName, chipDot, state, status, saved, placeholder, ramTxt, warnTxt, sizeText;
    volatile long pvTime;
    volatile int remainS = -1;   // seconds left for the current picture, from the pace of the DiT calls (-1 = unknown)
    volatile int resW = 512, resH = 768;
    String cfgOv; int stepsOv, cutOv;
    List<String> tagLoras = new ArrayList<>();
    volatile File varInit; int countOv;   // one batch of "similar variations": its init picture; one batch with its own picture count
    abstract File adDir();
    abstract File adModel();
    abstract long availMb();
    abstract String cfgNow();
    abstract boolean cfgOnNow();
    abstract void coolTick(boolean force);
    abstract int cutNow();
    static String dur(long s) { return s < 60 ? s + " s" : String.format(java.util.Locale.US, "%d min %02d s", s / 60, s % 60); }
    abstract File initSrc();
    abstract boolean isMage();
    abstract boolean isQwen();
    abstract boolean loraFitsModel();
    abstract String loraId();
    abstract boolean loraOn();
    abstract float loraStr();
    abstract File maskSrc();
    abstract String modelName();
    abstract String mp();
    abstract float pf(String key, float def);
    abstract File previewFile();
    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    abstract void refreshExtras();
    abstract void showImg(boolean on);
    /** The NPU pipeline needs width/height multiples of 16, patch count divisible by 32 and at most 6144 patches (megarun TMAX). */
    static String sizeError(int w, int h) {
        if (w < 256 || h < 256) return L.s("Размер: минимум 256 по каждой стороне", "Size: at least 256 on each side");
        if (w % 16 != 0 || h % 16 != 0) return L.s("Ширина и высота должны быть кратны 16", "Width and height must be multiples of 16");
        int t = (w / 16) * (h / 16);
        if (t % 32 != 0) return L.f("Число патчей (%d) должно делиться на 32, например 640×640, 1024×1024, 704×832", "Patch count (%d) must be divisible by 32, e.g. 640×640, 1024×1024, 704×832", t);
        if (t > 65536) return L.s("Слишком большой размер: максимум 65536 патчей (например 4096×4096)", "Too large: at most 65536 patches (e.g. 4096×4096)");   // chunked attention and windows (upstream 1.7.1 port): only the time grows
        return null;
    }
    abstract String su(String cmd, boolean poll);
    /** One Mage-Flow image. This route intentionally has no step previews. */
    static final String REFINE_PROMPT = "Improve the picture quality: natural anatomy, correct hands and fingers, one coherent face, clean sharp details and natural colours. Keep the person, the face, the outfit, the pose and the background exactly the same.";
    File runOneMage(String pr, String seedS, int w, int h, long t0) {
        final File home = getFilesDir(), m = Mage.dir(home), ids = new File(m, "ids.i32"), raw = new File(m, "img.rgb");
        final int mageSteps = 4; final Bitmap src = editBm; final boolean edit = src != null;
        // masked edit (same tools as Qwen): only the painted area changes, the rest of the photo keeps its exact pixels and resolution.
        // A small painted area is edited as a crop with context around it (more pixels for the detail), then pasted back with a soft edge.
        final File maskFile = refining ? new File(home, "no_mask_when_refining") : Qwen.editMask(home);
        final java.util.List<File> refFiles = edit && !refining ? Qwen.extraRefs(home) : new ArrayList<>();   // more pictures: "Image 2", "Image 3" (no painted area then)
        final boolean masked = edit && maskFile.isFile();   // with reference pictures too: only the painted area of photo 1 takes the result (body, pose, background stay)
        final Qwen.DetailCrop detail = masked && refFiles.isEmpty() ? Qwen.detailCrop(src, maskFile) : null;
        final Bitmap condition = detail != null ? detail.image : src;
        final int[] runDims = !edit ? new int[]{w, h} : detail != null ? Qwen.detailSize(detail.width(), detail.height()) : Mage.editSize(src.getWidth(), src.getHeight(), w, h, 1 + refFiles.size());
        final int runW = runDims[0], runH = runDims[1];
        setProgressUi(2, edit ? L.s("Preparando foto…", "Preparing photo…") : L.s("Tokenizando…", "Tokenising…"), t0);
        int drop, keys; String editArg = "";
        try {
            if (edit) {
                if ((runW / 16) * (runH / 16) > Mage.MAX_EDIT_PATCHES) { if (detail != null) detail.recycle(); ui.post(() -> Toast.makeText(this, L.s("Para editar, usa un tamaño de hasta 1024×768", "For editing, use a size up to 1024×768"), Toast.LENGTH_LONG).show()); return null; }
                java.util.List<Bitmap> extras = new ArrayList<>();
                for (File rf : refFiles) { Bitmap rb = BitmapFactory.decodeFile(rf.getPath()); if (rb != null) extras.add(rb); }
                Mage.Edit e = Mage.prepareEdit(home, condition, extras, pr, runW, runH, new File(Mage.editDir(home), "ids.i32"));
                for (Bitmap rb : extras) rb.recycle();
                drop = e.drop; editArg = " " + q(Mage.editDir(home).getPath()); keys = 2 * (runW / 16) * (runH / 16) + e.extraTokens + e.tokens - e.drop;
            } else { drop = Mage.writeIds(home, pr, ids); keys = (w / 16) * (h / 16) + (int) (ids.length() / 4) - drop; }
            if (keys > Mage.MAX_KEYS) { if (detail != null) detail.recycle(); ui.post(() -> Toast.makeText(this, L.s("El texto es demasiado largo para este tamaño; acórtalo o reduce la imagen", "The prompt is too long for this size; shorten it or reduce the image size"), Toast.LENGTH_LONG).show()); return null; }
        } catch (Exception e) { if (detail != null) detail.recycle(); ui.post(() -> Toast.makeText(this, "Mage: " + e, Toast.LENGTH_LONG).show()); return null; }
        final boolean[] done = {false};
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException ignored) { return; }
                String s = su("echo $(grep -c '^vit:' " + m + "/mage.log) $(grep -c '^te:' " + m + "/mage.log) $(grep -c '^txt_in:' " + m + "/mage.log) $(grep -c '^step' " + m + "/mage.log) $(grep -c '^vae dec total' " + m + "/mage.log)", true);
                try { String[] a = s.split("\\s+"); int vit = Integer.parseInt(a[0]), te = Integer.parseInt(a[1]), ti = Integer.parseInt(a[2]), st = Integer.parseInt(a[3]), dn = Integer.parseInt(a[4]);
                    if (dn > 0 || st >= mageSteps) setProgressUi(94, L.s("Decodificando…", "Decoding…"), t0);
                    else if (ti > 0 || st > 0) setProgressUi(30 + 60 * st / mageSteps, L.f("Paso %d/%d", "Step %d/%d", Math.min(st + 1, mageSteps), mageSteps), t0);
                    else if (te > 0) setProgressUi(28, L.s("Preparando texto…", "Preparing text…"), t0);
                    else if (edit && vit > 0) setProgressUi(15, L.s("Analizando foto en NPU…", "Analyzing photo on NPU…"), t0);
                    else if (edit) setProgressUi(5, L.s("Analizando foto…", "Analyzing photo…"), t0);
                    else setProgressUi(8, L.s("Codificando texto en NPU…", "Encoding text on NPU…"), t0);
                } catch (Exception ignored) { }
            }
        });
        poll.start();
        su("rm -f " + q(raw.getPath()) + "; M=" + q(m.getPath()) + " sh " + D + "/mage.sh " + q(ids.getPath()) + " " + drop + " " + runW + " " + runH + " " + seedS + " " + mageSteps + " " + q(raw.getPath()) + editArg + " < /dev/null >> " + D + "/gen.log 2>&1", false);
        done[0] = true; try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) { if (detail != null) detail.recycle(); return null; }
        try {
            byte[] png = Mage.png(raw, runW, runH); raw.delete();
            if (masked) png = detail != null ? Qwen.applyDetailMask(png, src, maskFile, detail, runW, runH, w, h) : Qwen.applyStretchedMask(png, src, maskFile);
            if (detail != null) detail.recycle();
            final int outW = masked ? src.getWidth() : runW, outH = masked ? src.getHeight() : runH;
            JSONObject meta = new JSONObject().put("prompt", pr).put("seed", seedS).put("w", outW).put("h", outH).put("model", modelName()).put("id", model).put("sec", (System.currentTimeMillis() - t0) / 1000.0);
            if (edit) meta.put("edit", true).put("masked_edit", masked).put("detail_crop", detail != null);
            File out = Gallery.save(this, PngMeta.embed(png, meta), meta);
            if (edit && out != null) {   // same as Qwen: keep the photo it came from, for the before/after slider in the viewer and the ⇆ button
                Qwen.saveBefore(home, src, out);
                if (prefs().getBoolean("qwen_compare", false)) {
                    Bitmap after = BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (after != null) { pendingCompare = saveSideBySide(src, after, meta); after.recycle(); }
                }
            }
            return out;
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Error de generación: %s (registro files/mage/mage.log)", "Generation error: %s (log files/mage/mage.log)", e.getMessage()), Toast.LENGTH_LONG).show()); return null; }
    }
    File runOne(String pr, String seedS, int w, int h, long t0) {
        if (isMage() || isQwen()) {
            File o = isMage() ? runOneMage(pr, seedS, w, h, t0) : runOneQwen(pr, seedS, w, h, t0);
            return o;   // (a second "refine" pass was tried: Mage invents details - abs, skin marks - instead of cleaning them)
        }
        if (isMoss()) return runOneMoss(pr, seedS, w, h, t0);
        final String fullPrompt = pr;   // kept with its <lora:...> tags in the PNG
        if (Pose.active(this)) setProgressUi(0, L.s("Preparando la pose…", "Preparing the pose…"), t0);
        pr = Pose.apply(this, pr, w, h);   // pose control: writes the control latent for this size and adds the adapter's LoRA
        AnimaLora.Tags tg = AnimaLora.tags(getFilesDir(), pr);
        pr = tg.prompt; tagLoras = tg.items;
        if (!tg.missing.isEmpty()) { final String miss = String.join(", ", tg.missing); ui.post(() -> Toast.makeText(this, L.f("LoRA не найдена: %s", "LoRA not found: %s", miss), Toast.LENGTH_LONG).show()); }
        if (!loaded) {
            setProgressUi(0, L.s("Загружаю модель…", "Loading the model…"), t0);
            su("rm -f " + D + "/cancel; " + mp() + "sh " + D + "/anima.sh load < /dev/null >/dev/null 2>&1", false);
            if (cancelled) return null;
            loaded = true;
        }
        coolTick(true);
        final String f = D + "/last.png";
        final boolean[] done = {false};
        final long[] tStep1 = {0}; remainS = -1;
        final int[] base = count();
        // img2img ("Edit a photo", similar variations): sd.cpp runs only the last steps*strength steps, and the CFG ones among them are what is left of the CFG part
        final int stepsAll = stepsOv > 0 ? stepsOv : Settings.steps(this);
        final File i2iSrc = varInit != null ? varInit : initSrc(); final float i2iStr = varInit != null ? 0.45f : pf("x_strength", .6f);
        final int steps = i2iSrc.exists() ? Math.max(1, Math.min(stepsAll, (int) (stepsAll * i2iStr))) : stepsAll;
        final int cfgRun = cfgOnNow() ? Math.max(0, Settings.cfgSteps(stepsAll, cutNow()) - (stepsAll - steps)) : 0;
        final int calls = Math.max(1, (steps + cfgRun) * (Settings.twoCalls(Settings.sampler(this)) ? 2 : 1));   // animad logs one "step" line per DiT call: CFG / 2nd-order samplers make several per step
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                coolTick(false);
                int[] c = count();
                int st = c[0] - base[0];   // DiT calls done (the live preview also decodes after every step, so the VAE lines do not mean "finished")
                long nowMs = System.currentTimeMillis();
                if (st >= 1 && tStep1[0] == 0) tStep1[0] = nowMs;
                remainS = st >= 2 && st < calls ? (int) Math.ceil((nowMs - tStep1[0]) / 1000.0 / (st - 1) * (calls - st) + 0.5) : -1;   // from the pace of the calls after the first
                if (st >= calls) setProgressUi(94, L.s("Декодирование…", "Decoding…"), t0);
                else if (c[2] > base[2] || st > 0) setProgressUi(15 + (int) (76 * stepShare(st)), L.f("Шаг %d/%d", "Step %d/%d", Math.min(steps, st * steps / calls + 1), steps), t0);  // cached text skips the adapter log, but the first DiT call still started
                else setProgressUi(8, L.s("Кодирование текста…", "Encoding the text…"), t0);
            }
        });
        poll.start();
        final String samp = "CFG=" + q(cfgNow()) + " SAMPLER=" + q(Settings.sampler(this)) + " SCHED=" + q(Settings.scheduler(this)) + (cfgOnNow() ? " NEG=" + q(Settings.negative(this)) + " CFG_CUT=" + cutNow() : "");
        su("cd " + D + " && rm -f last.png && " + mp() + "PREFIX=" + q(Settings.prefix(this)) + " STEPS=" + stepsAll + " " + samp + " " + extrasEnv(w, h, t0) + "W=" + w + " H=" + h + " LOGRE='learned_condition|completed in' sh ./anima.sh " + q(pr) + " " + seedS + " " + f + " < /dev/null >> gen.log 2>&1", false);
        done[0] = true; remainS = -1;
        try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) return null;
        byte[] png = new byte[0];
        try { png = Models.readBytes(new File(f)); } catch (Exception ignored) { }
        if (png.length == 0 || BitmapFactory.decodeByteArray(png, 0, png.length) == null) return null;
        try {
            JSONObject meta = new JSONObject().put("prompt", fullPrompt).put("seed", seedS).put("w", w).put("h", h).put("model", modelName()).put("id", model).put("sec", (System.currentTimeMillis() - t0) / 1000.0)
                .put("steps", stepsAll).put("cfg", cfgNow()).put("sampler", Settings.sampler(this)).put("scheduler", Settings.scheduler(this));
            if (cfgOnNow() && !Settings.negative(this).isEmpty()) meta.put("negative", Settings.negative(this));
            if (loraOn() && loraFitsModel()) meta.put("lora", prefs().getString("anima_lora_name", "LoRA")).put("lora_strength", loraStr());
            if (!tg.used.isEmpty() && loraFitsModel()) meta.put("prompt_loras", String.join(", ", tg.used));
            return Gallery.save(this, PngMeta.embed(png, meta), meta);
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()); return null; }
    }
    /** one MOSS-Image picture: tokenise here, text encoder + DiT + VAE on the NPU through moss.sh, PNG with the settings inside */
    File runOneMoss(String pr, String seedS, int w, int h, long t0) {
        final File home = getFilesDir(), M = Moss.dir(home);
        try { Moss.ensureV73Vae(home, getAssets()); } catch (Exception e) { ui.post(() -> Toast.makeText(this, "VAE: " + e.getMessage(), Toast.LENGTH_LONG).show()); return null; }
        final File MR;
        try { MR = Moss.isVariant(model) ? Moss.variantView(home, model) : M; }
        catch (Exception e) { ui.post(() -> Toast.makeText(this, "MOSS variant: " + e.getMessage(), Toast.LENGTH_LONG).show()); return null; }
        final File ids = new File(MR, "ids.i32"), raw = new File(MR, "img.f32");
        final boolean useLora = loraOn(); final MossLora.Check activeLora = useLora ? MossLora.check(BIN, MossLora.src(home)) : null;
        final boolean lokrSelected = activeLora != null && activeLora.ok && activeLora.lokr;
        final boolean rsDmd = Moss.isRs(model) && prefs().getString("rs_mode", "4").equals("8") && Moss.installed8(home) && (!useLora || lokrSelected);
        final int mossSteps = rsDmd ? 8 : 4;   // the distilled MOSS models are made for 4 steps
        setProgressUi(3, L.s("Токенизация…", "Tokenising…"), t0);
        try { Moss.writeIds(home, pr, ids); } catch (Exception e) { ui.post(() -> Toast.makeText(this, "tokenizer: " + e, Toast.LENGTH_LONG).show()); return null; }
        String loraEnv = ""; final float lstr = loraStr(); File lokrDir = null;
        if (useLora) {
            if (activeLora == null || !activeLora.ok) { ui.post(() -> Toast.makeText(this, activeLora == null ? L.s("No se pudo validar la LoRA", "Could not validate the LoRA") : activeLora.message(), Toast.LENGTH_LONG).show()); return null; }
            final String[] msg = {""};
            File ovl = activeLora.lokr
                ? MossLora.ensureLokr(home, BIN, MR, Moss.isVariant(model) ? Moss.variantKey(model) : "base", loraId(), lstr, (d, t) -> setProgressUi(1 + 5 * d / t, L.f("Preparando el LoKr… %d/%d", "Preparing the LoKr… %d/%d", d, t), t0), () -> cancelled, msg)
                : MossLora.ensure(home, BIN, loraId(), lstr, (d, t) -> setProgressUi(1 + 5 * d / t, L.f("Подготовка LoRA… %d/%d", "Preparing the LoRA… %d/%d", d, t), t0), () -> cancelled, msg);
            if (ovl == null) { if (!cancelled) ui.post(() -> Toast.makeText(this, L.f("Не удалось подготовить LoRA: %s", "Could not prepare the LoRA: %s", msg[0]), Toast.LENGTH_LONG).show()); return null; }
            if (activeLora.lokr) lokrDir = ovl; else loraEnv = "MOSS_LORA=" + q(ovl.getPath()) + " ";
        }
        final File RUN = lokrDir != null ? lokrDir : MR;   // the baked LoKr folder replaces the model folder for this run (log and output live there too)
        final boolean[] done = {false};
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                String s = su("echo $(grep -c '^te:' " + RUN + "/moss.log) $(grep -c '^refiner:' " + RUN + "/moss.log) $(grep -c '^step ' " + RUN + "/moss.log) $(grep -c '^done' " + RUN + "/moss.log) $(grep -c '^te: cached' " + RUN + "/moss.log) $(grep -c '^step_start ' " + RUN + "/moss.log)", true);
                try {
                    String[] a = s.split("\\s+"); int te = Integer.parseInt(a[0]), rf = Integer.parseInt(a[1]), st = Integer.parseInt(a[2]), dn = Integer.parseInt(a[3]), tc = Integer.parseInt(a[4]), ss = Integer.parseInt(a[5]);
                    if (dn > 0) setProgressUi(94, L.s("Декодирование…", "Decoding…"), t0);
                    else if (rf > 0 || st > 0 || ss > 0) { int step = Math.min(Math.max(st, ss), mossSteps); setProgressUi(22 + 70 * step / mossSteps, L.f("Paso %d/%d", "Step %d/%d", step, mossSteps), t0); }
                    else if (te > 0 && tc > 0) setProgressUi(20, L.s("Texto reutilizado; preparando el primer paso…", "Text reused; preparing the first step…"), t0);
                    else if (te > 0) setProgressUi(20, L.s("Preparando el texto…", "Preparing the text…"), t0);
                    else setProgressUi(8, L.s("Кодирование текста на NPU…", "Encoding the text on the NPU…"), t0);
                } catch (Exception ignored) { }
            }
        });
        su("rm -f " + q(RUN.getPath() + "/moss.log"), true);
        poll.start();
        su("rm -f " + q(raw.getPath()) + "; " + loraEnv + (rsDmd ? "MODE=8 " : "") + "M=" + q(RUN.getPath()) + " sh " + D + "/moss.sh " + q(ids.getPath()) + " " + w + " " + h + " " + seedS + " " + mossSteps + " " + q(raw.getPath()) + " < /dev/null >> " + D + "/gen.log 2>&1", false);
        done[0] = true; remainS = -1;
        try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) return null;
        try {
            byte[] png = Moss.png(raw, w, h); raw.delete();
            JSONObject meta = new JSONObject().put("prompt", pr).put("seed", seedS).put("w", w).put("h", h).put("model", modelName()).put("id", model).put("steps", mossSteps).put("sec", (System.currentTimeMillis() - t0) / 1000.0);
            if (useLora) meta.put("lora", prefs().getString("moss_lora_name", "LoRA")).put("lora_strength", lstr);
            return Gallery.save(this, PngMeta.embed(png, meta), meta);
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Ошибка генерации: %s (лог files/moss/moss.log)", "Generation failed: %s (log files/moss/moss.log)", e.getMessage()), Toast.LENGTH_LONG).show()); return null; }
    }
    /** one Qwen-Image picture (or an edit of the picked photo): tokenise / prepare the photo here, everything else on the NPU through qwen.sh */
    File runOneQwen(String pr, String seedS, int w, int h, long t0) {
        final File home = getFilesDir(), Q = Qwen.dir(home), ids = new File(Q, "ids.i32"), raw = new File(Q, "img.f32");
        final java.util.List<File> refFiles = editBm != null && !refining ? Qwen.extraRefs(home) : new ArrayList<>();
        final java.util.List<Bitmap> pics = new ArrayList<>();   // several reference pictures: each one goes to the model as its own picture (<image1>, <image2>, ...)
        if (editBm != null && !refFiles.isEmpty()) { pics.add(editBm); for (File rf : refFiles) { Bitmap rb = BitmapFactory.decodeFile(rf.getPath()); if (rb != null) pics.add(rb); } }
        final Bitmap src = editBm; final boolean edit = src != null; final int nRefs = pics.size() > 1 ? pics.size() : edit ? 1 : 0;
        final String prE = pr;
        final File maskFile = refining ? new File(home, "no_mask_when_refining") : Qwen.editMask(home);   // with reference pictures too: only the painted area takes the result
        final Qwen.DetailCrop detail = edit && maskFile.isFile() && nRefs < 2 ? Qwen.detailCrop(src, maskFile) : null;
        final Bitmap condition = detail != null ? detail.image : src;
        final int[] detailDims = detail != null ? Qwen.detailSize(detail.width(), detail.height()) : edit && (nRefs < 2 || maskFile.isFile()) && prefs().getBoolean("qwen_keep_aspect", true) ? Qwen.editSize(src.getWidth(), src.getHeight(), w, h) : new int[]{w, h};
        final int runW = detailDims[0], runH = detailDims[1];
        setProgressUi(2, edit ? L.s("Подготовка фото…", "Preparing the photo…") : L.s("Токенизация…", "Tokenising…"), t0);
        int drop; String edArg = "";
        try {
            if (edit) {
                boolean hifi = prefs().getBoolean("qwen_hifi", false) || nRefs > 1;   // with a reference picture always: its clothes and details are copied much closer
                Qwen.Edit e = nRefs > 1 ? Qwen.prepareEditMulti(home, pics, prE, runW, runH, ids, hifi ? Qwen.COND_AREA_HI : Qwen.COND_AREA) : Qwen.prepareEdit(home, condition, prE, runW, runH, ids, hifi ? Qwen.COND_AREA_HI : Qwen.COND_AREA); drop = e.drop; edArg = " " + q(Qwen.editDir(home).getPath());
                int keys = (runW / 16) * (runH / 16) + e.nc + (e.tokens - e.drop - e.nm);
                if (hifi && keys > Qwen.MAX_KEYS) {   // too many tokens for one attention pass: fall back to the standard reference size
                    e = nRefs > 1 ? Qwen.prepareEditMulti(home, pics, prE, runW, runH, ids, Qwen.COND_AREA) : Qwen.prepareEdit(home, condition, prE, runW, runH, ids, Qwen.COND_AREA); drop = e.drop;
                    keys = (runW / 16) * (runH / 16) + e.nc + (e.tokens - e.drop - e.nm);
                    ui.post(() -> Toast.makeText(this, L.s("Alta fidelidad no cabe con este tamaño: se usa la referencia normal", "High fidelity does not fit this size: using the standard reference"), Toast.LENGTH_LONG).show());
                }
                if (keys > Qwen.MAX_KEYS) { if (detail != null) detail.recycle(); ui.post(() -> Toast.makeText(this, L.s("Foto y resultado juntos son demasiado grandes: elige un tamaño menor", "The photo and result together are too large: pick a smaller size"), Toast.LENGTH_LONG).show()); return null; }
            } else drop = Qwen.writeIds(home, pr, ids);
        } catch (Exception e) { if (detail != null) detail.recycle(); ui.post(() -> Toast.makeText(this, "qwen: " + e, Toast.LENGTH_LONG).show()); return null; }
        final boolean[] done = {false};
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                String s = su("echo $(grep -c '^vit:' " + Q + "/qwen.log) $(grep -c '^te:' " + Q + "/qwen.log) $(grep -c '^txt_in:' " + Q + "/qwen.log) $(grep -c '^step' " + Q + "/qwen.log) $(grep -c '^done' " + Q + "/qwen.log)", true);
                try {
                    String[] a = s.split("\\s+"); int vit = Integer.parseInt(a[0]), te = Integer.parseInt(a[1]), ti = Integer.parseInt(a[2]), st = Integer.parseInt(a[3]), dn = Integer.parseInt(a[4]);
                    if (dn >= (edit ? 2 : 1)) setProgressUi(94, L.s("Декодирование…", "Decoding…"), t0);
                    else if (ti > 0 || st > 0) setProgressUi(30 + 62 * st / STEPS, st == 0 && edit ? L.s("Paso 1/4 (lectura de foto)", "Step 1/4 (reading the photo)") : L.f("Paso %d/%d", "Step %d/%d", Math.min(st, STEPS), STEPS), t0);
                    else if (te > 0) setProgressUi(28, L.s("Подготовка текста…", "Preparing the text…"), t0);
                    else if (edit && vit > 0) setProgressUi(15, L.s("Кодирование текста на NPU…", "Encoding the text on the NPU…"), t0);
                    else if (edit) setProgressUi(5, L.s("Анализ фото на NPU…", "Analysing the photo on the NPU…"), t0);
                    else setProgressUi(8, L.s("Кодирование текста на NPU…", "Encoding the text on the NPU…"), t0);
                } catch (Exception ignored) { }
            }
        });
        File qwenOverlay = Qwen.selectedOverlay(home, prefs().getString("qwen_lora_dir", ""));
        String qwenLoraEnv = qwenOverlay == null ? "" : "QWEN_LORA=" + q(qwenOverlay.getPath()) + " ";
        su("rm -f " + q(Q + "/qwen.log"), true);
        poll.start();
        su("rm -f " + q(raw.getPath()) + "; " + qwenLoraEnv + "Q=" + q(Q.getPath()) + " VAE=" + Qwen.vaeCtx(home, runW, runH) + " sh " + D + "/qwen.sh " + q(ids.getPath()) + " " + drop + " " + runW + " " + runH + " " + seedS + " " + STEPS + " " + q(raw.getPath()) + edArg + " < /dev/null >> " + D + "/gen.log 2>&1", false);
        done[0] = true; remainS = -1;
        try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) { if (detail != null) detail.recycle(); return null; }
        try {
            byte[] png = Qwen.png(raw, runW, runH); raw.delete();
            if (edit && maskFile.isFile()) png = detail != null
                ? Qwen.applyDetailMask(png, src, maskFile, detail, runW, runH, w, h)
                : Qwen.applyEditMask(png, src, maskFile, runW, runH);
            int finalW = edit && maskFile.isFile() ? src.getWidth() : runW, finalH = edit && maskFile.isFile() ? src.getHeight() : runH;
            JSONObject meta = new JSONObject().put("prompt", pr).put("seed", seedS).put("w", finalW).put("h", finalH).put("model", modelName()).put("id", model).put("sec", (System.currentTimeMillis() - t0) / 1000.0);
            if (nRefs > 1) meta.put("refs", nRefs);
            if (edit) meta.put("edit", true).put("masked_edit", maskFile.isFile()).put("detail_crop", detail != null).put("hifi", prefs().getBoolean("qwen_hifi", false) || nRefs > 1);
            File out = Gallery.save(this, PngMeta.embed(png, meta), meta);
            if (edit && out != null) {
                Qwen.saveBefore(home, src, out);
                if (prefs().getBoolean("qwen_compare", false)) {
                    Bitmap after = BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (after != null) { pendingCompare = saveSideBySide(src, after, meta); after.recycle(); }
                }
            }
            return out;
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Ошибка генерации: %s (лог files/qwen/qwen.log)", "Generation failed: %s (log files/qwen/qwen.log)", e.getMessage()), Toast.LENGTH_LONG).show()); return null; }
        finally { if (detail != null) detail.recycle(); }
    }
    /** the four pictures side by side with a label under each, saved like any other picture */
    File saveGrid(List<Bitmap> bms, List<String> labels, String prompt, String seedS, int w, int h) {
        int cw = Math.min(512, bms.get(0).getWidth()), ch = Math.round(cw * bms.get(0).getHeight() / (float) bms.get(0).getWidth()), lh = 44;
        Bitmap g = Bitmap.createBitmap(cw * 2, (ch + lh) * 2, Bitmap.Config.ARGB_8888); android.graphics.Canvas c = new android.graphics.Canvas(g); c.drawColor(0xFF1B172F);
        android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); tp.setColor(0xFFE9E6F5); tp.setTextSize(28); tp.setTypeface(F_SEMI); tp.setTextAlign(android.graphics.Paint.Align.CENTER);
        for (int i = 0; i < bms.size() && i < 4; i++) {
            int x = (i % 2) * cw, y = (i / 2) * (ch + lh);
            c.drawBitmap(bms.get(i), null, new android.graphics.Rect(x, y, x + cw, y + ch), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            c.drawText(labels.get(i), x + cw / 2f, y + ch + 31, tp);
        }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); g.compress(Bitmap.CompressFormat.PNG, 100, bo);
            JSONObject meta = new JSONObject().put("prompt", prompt).put("seed", seedS).put("w", w).put("h", h).put("model", modelName()).put("id", model).put("grid", android.text.TextUtils.join(" | ", labels));
            return Gallery.save(this, PngMeta.embed(bo.toByteArray(), meta), meta);
        } catch (Exception e) { return null; }
    }
    /** Contact sheet of a batch, at most 2048 px on its long side. The layout keeps the pictures as large as possible and avoids empty
     *  cells when that costs little (3 portraits sit in one row instead of 2×2 with a hole); an incomplete last row is centred.
     *  Each picture gets its number on a dark badge, with a thin gap between the cells. */
    /** contact sheets of a batch, 9 pictures each at most (balanced: 10 pictures make two sheets of 5); the numbers go on from sheet to sheet */
    List<File> saveBatchGrids(List<File> files, String prompt, int w, int h) {
        List<File> out = new ArrayList<>(); if (files == null || files.size() < 2) return out;
        int pages = (files.size() + 8) / 9, per = (files.size() + pages - 1) / pages;
        for (int i = 0; i < files.size(); i += per) { File g = saveBatchGrid(files.subList(i, Math.min(files.size(), i + per)), prompt, w, h, i, files.size()); if (g != null) out.add(g); }
        return out;
    }
    File saveBatchGrid(List<File> files, String prompt, int w, int h, int offset, int total) {
        if (files == null || files.isEmpty()) return null;
        final int n = files.size(), side = 2048, gap = 8;
        BitmapFactory.Options first = new BitmapFactory.Options(); first.inJustDecodeBounds = true; BitmapFactory.decodeFile(files.get(0).getPath(), first);
        float aspect = first.outWidth > 0 && first.outHeight > 0 ? first.outWidth / (float) first.outHeight : 1f;
        int cols = 1, cellW = 1, cellH = 1; double best = -1;
        for (int c = 1; c <= n; c++) {
            int r = (n + c - 1) / c; if (r > 1 && c > 1 && (r - 1) * c >= n) continue;   // no layout with a whole empty row
            int mw = Math.max(1, (side - gap * (c - 1)) / c), mh = Math.max(1, (side - gap * (r - 1)) / r), cw, ch;
            if (aspect >= mw / (float) mh) { cw = mw; ch = Math.max(1, Math.round(mw / aspect)); } else { ch = mh; cw = Math.max(1, Math.round(mh * aspect)); }
            double score = (double) cw * ch * (1 - .12 * (c * r - n));   // picture area, each empty cell costs 12 %
            if (score > best + 1e-6) { best = score; cols = c; cellW = cw; cellH = ch; }
        }
        final int rows = (n + cols - 1) / cols;
        Bitmap grid;
        try { grid = Bitmap.createBitmap(cellW * cols + gap * (cols - 1), cellH * rows + gap * (rows - 1), Bitmap.Config.ARGB_8888); }
        catch (OutOfMemoryError e) { System.gc(); return null; }
        android.graphics.Canvas c = new android.graphics.Canvas(grid); c.drawColor(0xFF181522);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.Paint label = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), pill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        label.setColor(0xFFFFFFFF); label.setTextSize(Math.max(18, Math.min(44, cellW * 0.08f))); label.setTypeface(F_SEMI); label.setTextAlign(android.graphics.Paint.Align.CENTER);
        pill.setColor(0xB0000000);
        for (int i = 0; i < n; i++) {
            File f = files.get(i); BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue;
            int sample = 1; while (bounds.outWidth / (sample * 2) >= cellW && bounds.outHeight / (sample * 2) >= cellH) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options(); opt.inSampleSize = sample;
            Bitmap tile = BitmapFactory.decodeFile(f.getPath(), opt); if (tile == null) continue;
            int row = i / cols, inRow = row == rows - 1 ? n - row * cols : cols;   // the last row may be shorter: centre it
            int rowStart = (grid.getWidth() - (inRow * cellW + gap * (inRow - 1))) / 2;
            int x = rowStart + (i % cols) * (cellW + gap), y = row * (cellH + gap);
            float scale = Math.min(cellW / (float) tile.getWidth(), cellH / (float) tile.getHeight());
            int dw = Math.round(tile.getWidth() * scale), dh = Math.round(tile.getHeight() * scale);
            android.graphics.Rect dst = new android.graphics.Rect(x + (cellW - dw) / 2, y + (cellH - dh) / 2, x + (cellW + dw) / 2, y + (cellH + dh) / 2);
            c.drawBitmap(tile, null, dst, paint); tile.recycle();
            float r = label.getTextSize() * .9f, cx = dst.left + r + 14, cy = dst.top + r + 14;
            c.drawCircle(cx, cy, r, pill); c.drawText(String.valueOf(offset + i + 1), cx, cy + label.getTextSize() * .35f, label);
        }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); grid.compress(Bitmap.CompressFormat.PNG, 100, bo); grid.recycle();
            JSONObject meta = new JSONObject().put("prompt", prompt).put("seed", "batch").put("w", w).put("h", h).put("model", modelName()).put("id", model).put("grid", "batch " + total).put("count", n).put("first", offset + 1);
            return Gallery.save(this, PngMeta.embed(bo.toByteArray(), meta), meta);
        } catch (Exception e) { if (!grid.isRecycled()) grid.recycle(); return null; }
    }
    /** "Done" notification when the app is not on screen; tapping it opens the app */
    void notifyDone(android.content.Context app, Bitmap bm, String pr, int made) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("done", L.s("Готовые картинки", "Finished pictures"), NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder nb = new Notification.Builder(app, "done").setSmallIcon(android.R.drawable.ic_menu_gallery).setAutoCancel(true)
                .setContentTitle(made > 1 ? L.f("Готово: %d картинки", "Done: %d pictures", made) : L.s("Картинка готова", "Picture is ready")).setContentText(pr)
                .setContentIntent(PendingIntent.getActivity(app, 0, new Intent(app, Main.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            if (bm != null) nb.setLargeIcon(bm).setStyle(new Notification.BigPictureStyle().bigPicture(bm));
            nm.notify(2, nb.build());
        } catch (Exception ignored) { }
    }
    /** [steps done, vae runs, adapter runs (text encoder finished)] from the animad log. */
    int[] count() {
        String s = su("echo $(grep -c 'animad: step' " + D + "/animad.log) $(grep -c 'animad: vae' " + D + "/animad.log) $(grep -c 'animad: adapter' " + D + "/animad.log)", true);
        try {
            String[] a = s.split("\\s+");
            return new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])};
        } catch (Exception e) {
            return new int[]{0, 0, 0};
        }
    }
    /** live preview: sd-cli renames a fresh preview.png into place after every step; show it while the picture is being made */
    void showPreview(long t0) {
        File pv = previewFile(); long m = pv.lastModified();
        if (!busy || m == 0 || m == pvTime || m < t0) return;
        pvTime = m; final int imageAt = curIdx; final Bitmap b = BitmapFactory.decodeFile(pv.getPath()); if (b == null) return;
        ui.post(() -> { if (!busy || curIdx != imageAt || previewFile().lastModified() != m) return; img.setImageBitmap(b); showImg(true); placeholder.setVisibility(View.GONE); });
    }
    void setProgressUi(int pct, String msg, long t0) {
        showPreview(t0);
        final int all = (curIdx * 100 + pct) / curN;
        final String pre = curN > 1 ? "(" + (curIdx + 1) + "/" + curN + ")  " : "";
        long now = System.currentTimeMillis(), inPic = (now - picStart) / 1000;
        long rem = remainS > 0 ? remainS : picExpect - inPic;   // Anima: from the pace of its steps; others: the measured (or estimated) time minus what has passed
        if (rem < 2 && pct > 15 && pct < 100) rem = inPic * (100 - pct) / pct;   // slower than expected: from the progress itself
        rem = Math.max(1, rem) + (long) Math.max(0, curN - curIdx - 1) * picExpect;
        final String t = picStart > 0 && picExpect > 0 ? L.f("%s  ·  quedan ~%s", "%s  ·  ~%s left", pre + msg, dur(rem)) : L.f("%s  ·  %.0f с", "%s  ·  %.0f s", pre + msg, (now - t0) / 1000.0);
        ui.post(() -> { bar.setProgress(all); status.setText(t); });
    }
    /** Share of the DiT time spent after `st` steps: the first two of the 4 steps use a half-size latent and cost ~0.3 (512x768) / ~0.22 (960x1536) of a full step. */
    double stepShare(int st) {
        if (!Settings.defaultSampling(this)) return Math.min(1.0, st / (double) Settings.calls(this));   // other sampling: no half-size steps, every DiT call costs the same
        double lo = resW * resH < 500000 ? 0.3 : 0.22;
        double done = 0, total = 0;
        for (int i = 0; i < STEPS; i++) { double c = i < 2 ? lo : 1.0; total += c; if (i < st) done += c; }
        return done / total;
    }
    File ensureAd(long t0) {
        File f = adModel(); if (f != null) return f;
        setProgressUi(1, L.s("Загрузка детектора лиц (6 МБ)…", "Downloading the face detector (6 MB)…"), t0);
        try { adDir().mkdirs(); File out = new File(adDir(), "face_yolov8n.safetensors"); Models.fetch(AD_URL, out, -1, AD_SHA, null, new java.util.concurrent.atomic.AtomicBoolean()); ui.post(this::refreshExtras); return out; }
        catch (Exception e) { ui.post(() -> Toast.makeText(this, "ADetailer: " + e.getMessage(), Toast.LENGTH_LONG).show()); return null; }
    }
    /** hires target: about scale × the size, sides multiples of 16 with a patch count the NPU blocks run (multiple of 32, at most 6144) */
    int[] hiresSize(int w, int h) {
        double s = Math.max(1.0, Math.min(3.0, pf("x_hires_scale", 1.5f)));
        for (; s > 1.0; s -= 0.05) {
            int w2 = (int) Math.round(w * s / 16) * 16, h2 = (int) Math.round(h * s / 16) * 16;
            if (sizeError(w2, h2) == null) return new int[]{w2, h2};
        }
        return null;
    }
    /** the mask scaled + cropped exactly like initFor(w, h) */
    File maskFor(int w, int h) {
        if (!maskSrc().exists() || !initSrc().exists()) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(maskSrc().getPath()); if (b == null) return null;
            float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            Bitmap sc = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            Bitmap cr = Bitmap.createBitmap(sc, (sc.getWidth() - w) / 2, (sc.getHeight() - h) / 2, w, h);
            File out = new File(getFilesDir(), "mask_" + w + "x" + h + ".png");
            try (FileOutputStream o = new FileOutputStream(out)) { cr.compress(Bitmap.CompressFormat.PNG, 100, o); }
            return out;
        } catch (Exception e) { return null; }
    }
    /** INIT / STRENGTH / MASK for anima.sh */
    String initEnv(int w, int h) {
        File init = initFor(w, h); if (init == null) return "";
        String e = "INIT=" + q(init.getPath()) + " STRENGTH=" + (varInit != null ? "0.45" : pf("x_strength", .6f)) + " ";
        File m = varInit != null ? null : maskFor(w, h); if (m != null) e += "MASK=" + q(m.getPath()) + " ";
        return e;
    }
    /** writes the init image fitted (scale + center crop) to w x h; null if none */
    File initFor(int w, int h) {
        File src = varInit != null ? varInit : initSrc();   // "similar variations" of a picture use it as the init image for that batch only
        if (!src.exists()) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(src.getPath()); if (b == null) return null;
            float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            Bitmap sc = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            Bitmap cr = Bitmap.createBitmap(sc, (sc.getWidth() - w) / 2, (sc.getHeight() - h) / 2, w, h);
            File out = new File(getFilesDir(), "init_" + w + "x" + h + ".png");
            try (FileOutputStream o = new FileOutputStream(out)) { cr.compress(Bitmap.CompressFormat.PNG, 100, o); }
            return out;
        } catch (Exception e) { return null; }
    }
    String extrasEnv(int w, int h, long t0) {
        StringBuilder e = new StringBuilder();
        if (prefs().getBoolean("x_preview", true)) e.append("PREVIEW=").append(q(previewFile().getPath())).append(" ");
        if (availMb() < 3000 && prefs().getBoolean("x_hires", false)) {   // the larger passes need room: skip them rather than swap
            ui.post(() -> Toast.makeText(this, L.s("Мало памяти: hires fix пропущен для этой картинки", "Low memory: hires fix skipped for this picture"), Toast.LENGTH_LONG).show());
            e.append(initEnv(w, h));
            return e.toString();
        }
        if (prefs().getBoolean("x_hires", false)) {
            int[] hs = hiresSize(w, h);
            if (hs != null) e.append("HIRES=1 HIRES_W=").append(hs[0]).append(" HIRES_H=").append(hs[1]).append(" HIRES_DENOISE=").append(pf("x_hires_den", .45f)).append(" HIRES_STEPS=").append((int) pf("x_hires_steps", 4)).append(" ");
            else ui.post(() -> Toast.makeText(this, L.s("Hires fix: этот размер уже максимальный для NPU", "Hires fix: this size is already the NPU's largest"), Toast.LENGTH_LONG).show());
        }
        if (prefs().getBoolean("x_ad", false)) { File ad = ensureAd(t0); if (ad != null) e.append("AD=").append(q(ad.getPath())).append(" "); }
        e.append(initEnv(w, h));
        return e.toString();
    }
}
