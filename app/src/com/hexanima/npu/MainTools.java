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

/** part of the main screen (split out of Main.java): MainTools */
abstract class MainTools extends MainRun {
    Models store;
    abstract String shownModel(String id);
    int gridKind;          // 0 off, 1 seeds, 2 CFG, 3 steps, 4 CFG cutoff (set for one generate() call)
    volatile double appGb; volatile boolean appBusy;
    int tick;
    abstract void refreshState();
    static final String DEFAULT_BASE = "https://huggingface.co/ayanoby/anima-npu-packs/resolve/main/";
    static final String[][] PALETTE = {
        {"Calidad|Quality", "masterpiece", "best quality", "very aesthetic", "absurdres", "highres", "newest", "detailed", "score_9"},
        {"Encuadre|Framing", "portrait", "upper body", "cowboy shot", "full body", "close-up", "looking at viewer", "from above", "from below", "from side", "dutch angle", "wide shot"},
        {"Luz|Lighting", "soft lighting", "cinematic lighting", "dramatic lighting", "rim light", "backlighting", "sunlight", "golden hour", "volumetric lighting", "neon lights", "night"},
        {"Estilo|Style", "anime coloring", "flat color", "watercolor (medium)", "oil painting (medium)", "sketch", "3d", "realistic", "pixel art", "chibi", "retro artstyle"},
        {"Expresión|Expression", "smile", "blush", "open mouth", "closed eyes", "serious", "smug", "surprised", "angry", "crying", "wink"},
        {"Fondo|Background", "simple background", "white background", "outdoors", "indoors", "city", "forest", "beach", "sky", "bedroom", "cherry blossoms"},
    };
    static final String[] PRESET_KEYS = {"steps", "cfg", "sampler", "sched", "neg"};
    static final int REQ_UPSCALE_PICK = 31, REQ_TAG_PICK = 32;
    /** "Prompt from a picture": the tagger model is downloaded once (asks first), then the photo picker */
    void promptFromPicture() {
        if (Tagger.installed(this)) { pickImage(REQ_TAG_PICK); return; }
        builder().setTitle(L.s("Prompt desde una imagen", "Prompt from a picture"))
            .setMessage(L.s("Lee una imagen y escribe su prompt: etiquetas (1girl, silver hair…) o una frase. Usa WD ViT Tagger v3 de SmilingWolf (Apache-2.0) en la NPU, en menos de un segundo. Hay que descargarlo una vez: 190 MB.",
                "Reads a picture and writes its prompt: tags (1girl, silver hair…) or a sentence. It uses WD ViT Tagger v3 by SmilingWolf (Apache-2.0) on the NPU, in under a second. It is downloaded once: 190 MB."))
            .setNegativeButton(L.s("Отмена", "Cancel"), null)
            .setPositiveButton(L.s("Descargar (190 MB)", "Download (190 MB)"), (x, y) -> {
                final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
                final ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000);
                final TextView pt = text("", 12, DIM, false);
                LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(12), dp(20), 0); v.addView(pb); v.addView(pt);
                final AlertDialog dl = builder().setTitle(L.s("Загрузка", "Downloading")).setView(v).setCancelable(false).setNegativeButton(L.s("Отмена", "Cancel"), (a, b) -> stop.set(true)).show();
                ex2.submit(() -> {
                    try {
                        Tagger.install(this, (done, total) -> ui.post(() -> { if (total > 0) pb.setProgress((int) (1000 * done / total)); pt.setText(String.format(java.util.Locale.US, "%.0f / %.0f MB", done / 1048576.0, total / 1048576.0)); }), stop);
                        ui.post(() -> { dl.dismiss(); pickImage(REQ_TAG_PICK); });
                    } catch (Exception e) { ui.post(() -> { dl.dismiss(); if (!stop.get()) builder().setMessage(L.f("No se pudo descargar: %s", "Could not download: %s", String.valueOf(e.getMessage()))).setPositiveButton("OK", null).show(); }); }
                });
            }).show();
    }
    /** runs the tagger on the picked picture and shows tags / a sentence, short / medium / long, to use, add or copy */
    void showPicturePrompt(final File pic) {
        final Toast t0 = Toast.makeText(this, L.s("Leyendo la imagen…", "Reading the picture…"), Toast.LENGTH_SHORT); t0.show();
        ex2.submit(() -> {
            try {
                Bitmap b = BitmapFactory.decodeFile(pic.getPath()); if (b == null) throw new IOException(L.s("No se pudo abrir la imagen", "Could not open the picture"));
                final long t = System.currentTimeMillis(); final List<Tagger.Tag> tags = Tagger.tag(this, b); b.recycle(); pic.delete();
                final long ms = System.currentTimeMillis() - t;
                ui.post(() -> {
                    final boolean[] sent = {prefs().getBoolean("tag_sentence", isMoss() || isQwen() || isMage())}; final int[] len = {prefs().getInt("tag_len", Tagger.MEDIUM)};
                    final boolean[] look = {prefs().getBoolean("tag_char_only", false)}; final List<Tagger.Tag> lookTags = Tagger.characterOnly(tags);
                    LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(8));
                    v.addView(text(L.s("Prompt desde una imagen", "Prompt from a picture"), 20, TXT, true));
                    v.addView(text(L.f("%d etiquetas en %.1f s", "%d tags in %.1f s", tags.size(), ms / 1000.0), 12, DIM, false));
                    final EditText out = field(""); out.setTextSize(15); out.setMinLines(3); out.setGravity(Gravity.TOP);
                    final Runnable fill = () -> { List<Tagger.Tag> use = look[0] ? lookTags : tags; out.setText(sent[0] ? Tagger.sentence(use, len[0]) : Tagger.tags(use, len[0])); };
                    LinearLayout kind = new LinearLayout(this); kind.setPadding(0, dp(10), 0, 0);
                    LinearLayout size = new LinearLayout(this); size.setPadding(0, dp(6), 0, 0);
                    final Button[] kb = {button(L.s("Etiquetas", "Tags"), FIELD, ACC), button(L.s("Frase", "Sentence"), FIELD, ACC)};
                    final Button[] sb = {button(L.s("Corto", "Short"), FIELD, ACC), button(L.s("Medio", "Medium"), FIELD, ACC), button(L.s("Largo", "Long"), FIELD, ACC)};
                    final Runnable paint = () -> { for (int i = 0; i < 2; i++) { boolean on = sent[0] == (i == 1); kb[i].setBackground(bg(on ? ACC : FIELD, 12)); kb[i].setTextColor(on ? ON_ACC : ACC); }
                        for (int i = 0; i < 3; i++) { boolean on = len[0] == i; sb[i].setBackground(bg(on ? ACC : FIELD, 12)); sb[i].setTextColor(on ? ON_ACC : ACC); } fill.run(); };
                    for (int i = 0; i < 2; i++) { final int k = i; kb[i].setTextSize(13); kb[i].setAllCaps(false); kb[i].setOnClickListener(x -> { sent[0] = k == 1; prefs().edit().putBoolean("tag_sentence", sent[0]).apply(); paint.run(); });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1); if (i > 0) lp.leftMargin = dp(6); kind.addView(kb[i], lp); }
                    for (int i = 0; i < 3; i++) { final int k = i; sb[i].setTextSize(13); sb[i].setAllCaps(false); sb[i].setOnClickListener(x -> { len[0] = k; prefs().edit().putInt("tag_len", k).apply(); paint.run(); });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1); if (i > 0) lp.leftMargin = dp(6); size.addView(sb[i], lp); }
                    v.addView(kind); v.addView(size);
                    android.widget.CheckBox only = new android.widget.CheckBox(this); only.setText(L.s("Solo el personaje (sin pose ni fondo): añade tú el estilo, la pose o el lugar", "Character only (no pose or background): add the style, pose or place yourself"));
                    only.setTextColor(TXT); only.setTextSize(13); only.setButtonTintList(ColorStateList.valueOf(ACC)); only.setChecked(look[0]);
                    only.setOnCheckedChangeListener((b2, on) -> { look[0] = on; prefs().edit().putBoolean("tag_char_only", on).apply(); fill.run(); });
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(6); v.addView(only, cl);
                    LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, -2); ol.topMargin = dp(10); v.addView(out, ol);
                    TextView hint = text(L.s("Puedes editar el texto antes de usarlo. Anima entiende mejor las etiquetas; MOSS, Qwen y Mage, la frase.", "You can edit the text before using it. Anima understands tags best; MOSS, Qwen and Mage, the sentence."), 11, DIM, false);
                    hint.setPadding(dp(2), dp(4), 0, 0); v.addView(hint);
                    LinearLayout bot = new LinearLayout(this); bot.setPadding(0, dp(10), 0, 0);
                    Button cp = button(L.s("Copiar", "Copy"), FIELD, ACC); bot.addView(cp, new LinearLayout.LayoutParams(0, dp(44), 1));
                    Button add = button(L.s("Añadir", "Add"), FIELD, ACC); LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, dp(44), 1); al.leftMargin = dp(8); bot.addView(add, al);
                    Button use = button(L.s("Usar", "Use"), ACC, ON_ACC); LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(0, dp(44), 1); ul.leftMargin = dp(8); bot.addView(use, ul);
                    v.addView(bot);
                    paint.run();
                    final AlertDialog dlg = dialog(v);
                    cp.setOnClickListener(x -> { android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("prompt", out.getText().toString())); Toast.makeText(this, L.s("Copiado", "Copied"), Toast.LENGTH_SHORT).show(); });
                    add.setOnClickListener(x -> { String p = prompt.getText().toString().trim(); prompt.setText(p.isEmpty() ? out.getText().toString() : p + (sent[0] ? " " : ", ") + out.getText().toString()); dlg.dismiss(); });
                    use.setOnClickListener(x -> { prompt.setText(out.getText().toString()); dlg.dismiss(); });
                });
            } catch (Exception e) { ui.post(() -> builder().setMessage(L.f("No se pudo leer la imagen: %s", "Could not read the picture: %s", String.valueOf(e.getMessage()))).setPositiveButton("OK", null).show()); }
        });
    }
    long coolCheck; boolean coolOn;
    int count = 1;       // pictures per press of Generate
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    java.util.List<String> ids = new java.util.ArrayList<>();   // installed model ids
    File lastFile;
    final List<JSONObject> queue = new ArrayList<>(); Runnable afterGen;
    final Runnable ramTick = new Runnable() {
        @Override public void run() {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            double g = 1024.0 * 1024 * 1024, tot = mi.totalMem / g, free = mi.availMem / g, used = tot - free;
            if (!appBusy) { appBusy = true; new Thread(() -> { appGb = Compat.appRamGb(); appBusy = false; }).start(); }   // /proc scan off the UI thread
            ramTxt.setText(L.f("ОЗУ  всего %.1f  ·  занято %.1f  ·  свободно %.1f ГБ", "RAM  total %.1f  ·  used %.1f  ·  free %.1f GB", tot, used, free) + (appGb > 0.05 ? L.f("  ·  приложение %.1f ГБ", "  ·  app %.1f GB", appGb) : "") + tempLabel());
            ramBar.setProgress((int) (1000 * used / tot));
            ramBar.setProgressTintList(ColorStateList.valueOf(free < 0.8 ? BAD : free < 1.5 ? WARN : ACC));
            List<String> w = new ArrayList<>();
            if (!Compat.chipAllowed(MainTools.this)) w.add(L.f("Чип %s не поддерживается", "Chip %s is not supported", Compat.soc()));
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) w.add(L.s("Телефон горячий: скорость снижена", "The phone is hot: speed is reduced"));
            if (pm.isPowerSaveMode()) w.add(L.s("Экономия заряда замедляет NPU", "Battery saver slows the NPU"));
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) { int lvl = bat.getIntExtra("level", -1) * 100 / Math.max(1, bat.getIntExtra("scale", 100)), st = bat.getIntExtra("status", 0); if (lvl >= 0 && lvl < 15 && st != 2 && st != 5) w.add(L.f("Низкий заряд батареи (%d%%)", "Low battery (%d%%)", lvl)); }
            warnTxt.setVisibility(w.isEmpty() ? View.GONE : View.VISIBLE);
            warnTxt.setText("⚠  " + android.text.TextUtils.join("  ·  ", w));
            if (++tick % 5 == 0 && !busy) refreshState();
            ui.postDelayed(this, 2000);
        }
    };
    EditText seed, wEdit, hEdit;
    abstract void generate(boolean newSeed);
    abstract void openModels();
    abstract void refreshModels();
    abstract void setModel(String m);
    abstract void setModelUi(boolean isLoaded, boolean working, String msg);
    abstract void setRes(int w, int h);
    /** configuration templates: a named snapshot of everything that shapes a picture - model, prompt, negative, steps, CFG, sampler, scheduler, size, seed,
     *  pictures per run, quality prefix, LoRAs (Anima tags live in the prompt; the MOSS and Qwen LoRA selection), extras, edit options - loaded back with one tap */
    static final String[] CFG_SKIP = {"civitai_key", "saved", "saved_neg", "presets_anima", "config_templates", "queue", "lang", "theme", "welcomed", "seen_version", "recent_cleared", "wall", "fold_", "t_", "alias", "model_names", "tag_", "var_count", "upscale_model", "cool", "keepon", "idle", "hidden_last"};
    static boolean cfgKey(String k, String model) {
        for (String x : CFG_SKIP) if (k.equals(x) || k.startsWith(x)) return false;
        if (k.contains("@")) return k.endsWith("@" + model);   // per-model values: only the template's model
        return k.equals("model") || k.equals("prompt") || k.equals("seed") || k.equals("w") || k.equals("h") || k.equals("count") || k.startsWith("x_") || k.startsWith("moss_lora") || k.startsWith("qwen_") || k.startsWith("anima_lora") || k.equals("rs_mode") || k.equals("prefix");
    }
    void presets() {
        prefs().edit().putString("prompt", prompt.getText().toString()).putString("seed", seed.getText().toString()).putString("model", model).putInt("w", resW).putInt("h", resH).putInt("count", count).apply();   // the fields as they are now
        JSONObject all; try { all = new JSONObject(prefs().getString("config_templates", "{}")); } catch (Exception e) { all = new JSONObject(); }
        final JSONObject ps = all;
        List<String> names = new ArrayList<>(); for (Iterator<String> it = ps.keys(); it.hasNext(); ) names.add(it.next()); java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(8));
        v.addView(text(L.s("Plantillas de configuración", "Configuration templates"), 20, TXT, true));
        v.addView(text(L.s("Guarda modelo, prompt, negativo, pasos, CFG, sampler, tamaño, semilla, LoRAs y extras con un nombre, y vuelve a cargarlo todo con un toque.", "Saves the model, prompt, negative, steps, CFG, sampler, size, seed, LoRAs and extras under a name, and loads it all back with one tap."), 12, DIM, false));
        Button save = button(L.s("＋ Guardar la configuración actual", "＋ Save the current configuration"), ACC, ON_ACC); save.setAllCaps(false);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(46)); sl.topMargin = dp(10); v.addView(save, sl);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); ScrollView sv = new ScrollView(this); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, -2, 1));
        final AlertDialog dlg = dialog(v);
        if (names.isEmpty()) { TextView e = text(L.s("Aún no hay plantillas.", "No templates yet."), 13, DIM, false); e.setPadding(dp(2), dp(10), 0, 0); list.addView(e); }
        for (final String n : names) {
            JSONObject t = ps.optJSONObject(n); if (t == null) continue;
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setBackground(bg(FIELD, 12)); r.setPadding(dp(12), dp(10), dp(8), dp(10));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(n, 15, TXT, true));
            String m = t.optJSONObject("v") != null ? t.optJSONObject("v").optString("model") : "";
            TextView sub = text(shownModel(m) + "  ·  " + t.optJSONObject("v").optString("prompt").replace('\n', ' '), 12, DIM, false); sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(sub);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView x = text("✕", 16, DIM, false); x.setPadding(dp(12), dp(4), dp(8), dp(4)); r.addView(x);
            x.setOnClickListener(z -> builder().setMessage(L.f("¿Borrar la plantilla «%s»?", "Delete the template \"%s\"?", n)).setNegativeButton(L.s("Отмена", "Cancel"), null)
                .setPositiveButton(L.s("Удалить", "Delete"), (a, b) -> { ps.remove(n); prefs().edit().putString("config_templates", ps.toString()).apply(); dlg.dismiss(); presets(); }).show());
            r.setOnClickListener(z -> { dlg.dismiss(); applyTemplate(n, t); });
            r.setOnLongClickListener(z -> { builder().setTitle(n).setItems(new String[]{L.s("Sobrescribir con la configuración actual", "Overwrite with the current settings")}, (a, b) ->
                builder().setMessage(L.f("¿Reemplazar «%s» con la configuración que tienes ahora?", "Replace \"%s\" with your current settings?", n)).setNegativeButton(L.s("Отмена", "Cancel"), null)
                    .setPositiveButton(L.s("Sobrescribir", "Overwrite"), (c, k) -> { try { ps.put(n, snapshot()); prefs().edit().putString("config_templates", ps.toString()).apply();
                        Toast.makeText(this, L.f("Plantilla «%s» actualizada", "Template \"%s\" updated", n), Toast.LENGTH_SHORT).show(); dlg.dismiss(); presets(); } catch (Exception ignored) { } }).show()).show(); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6); list.addView(r, lp);
        }
        save.setOnClickListener(z -> {
            final EditText nm = field(L.s("Nombre, p. ej. 3D Style", "Name, e.g. 3D Style")); nm.setSingleLine(true);
            LinearLayout box = new LinearLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(nm, new LinearLayout.LayoutParams(-1, -2));
            builder().setTitle(L.s("Guardar plantilla", "Save template")).setView(box).setNegativeButton(L.s("Отмена", "Cancel"), null).setPositiveButton("OK", (d2, k) -> {
                try {
                    String n = nm.getText().toString().trim(); if (n.isEmpty()) n = L.f("Plantilla %d", "Template %d", ps.length() + 1);
                    ps.put(n, snapshot());
                    prefs().edit().putString("config_templates", ps.toString()).apply();
                    Toast.makeText(this, L.f("Plantilla «%s» guardada", "Template \"%s\" saved", n), Toast.LENGTH_SHORT).show();
                    dlg.dismiss(); presets();
                } catch (Exception ignored) { }
            }).show();
        });
    }
    /** the current settings as a template entry {v: values, t: types, time} (the fields as they are now are stored first) */
    JSONObject snapshot() throws Exception {
        prefs().edit().putString("prompt", prompt.getText().toString()).putString("seed", seed.getText().toString()).putString("model", model).putInt("w", resW).putInt("h", resH).putInt("count", count).commit();
        JSONObject vals = new JSONObject(), types = new JSONObject();
        for (java.util.Map.Entry<String, ?> en : prefs().getAll().entrySet()) {
            if (!cfgKey(en.getKey(), model) || en.getValue() == null) continue;
            Object o = en.getValue(); vals.put(en.getKey(), o instanceof java.util.Set ? new JSONArray((java.util.Set<?>) o) : o);
            types.put(en.getKey(), o instanceof Boolean ? "b" : o instanceof Integer ? "i" : o instanceof Long ? "l" : o instanceof Float ? "f" : o instanceof java.util.Set ? "s" : "t");
        }
        vals.put("model", model);
        return new JSONObject().put("v", vals).put("t", types).put("time", System.currentTimeMillis());
    }
    /** writes the template's values back and restarts the screen so every field shows them; a model that is no longer installed is left as it is */
    boolean skipSave;   // set while the screen restarts after a template was loaded
    void applyTemplate(String n, JSONObject t) {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        JSONObject v = t.optJSONObject("v"), ty = t.optJSONObject("t"); if (v == null || ty == null) return;
        String m = v.optString("model"); boolean have = m.isEmpty() || store.installed().contains(m);
        SharedPreferences.Editor e = prefs().edit();
        for (Iterator<String> it = v.keys(); it.hasNext(); ) {
            String k = it.next(); if (k.equals("model") && !have) continue;
            switch (ty.optString(k, "t")) {
                case "b": e.putBoolean(k, v.optBoolean(k)); break;
                case "i": e.putInt(k, v.optInt(k)); break;
                case "l": e.putLong(k, v.optLong(k)); break;
                case "f": e.putFloat(k, (float) v.optDouble(k)); break;
                case "s": { java.util.Set<String> st = new java.util.HashSet<>(); JSONArray a = v.optJSONArray(k); if (a != null) for (int i = 0; i < a.length(); i++) st.add(a.optString(i)); e.putStringSet(k, st); break; }
                default: e.putString(k, v.optString(k));
            }
        }
        e.apply(); skipSave = true;
        Toast.makeText(this, have ? L.f("Plantilla «%s» cargada", "Template \"%s\" loaded", n) : L.f("Plantilla «%s» cargada; su modelo ya no está instalado", "Template \"%s\" loaded; its model is no longer installed", n), Toast.LENGTH_LONG).show();
        if (loaded && !m.equals(model)) { final String D0 = D; ex.submit(() -> { su("sh " + D0 + "/anima.sh stop < /dev/null >/dev/null 2>&1", false); loaded = false; ui.post(this::recreate); }); }
        else recreate();
    }
    // queue: prompt + seed + size + count are captured now; the settings are the ones in effect when the queue runs
    void enqueue() {
        String pr = prompt.getText().toString().trim(); if (pr.isEmpty()) { Toast.makeText(this, L.s("Введите описание", "Enter a prompt"), Toast.LENGTH_SHORT).show(); return; }
        try { queue.add(new JSONObject().put("p", pr).put("s", seed.getText().toString().trim()).put("w", resW).put("h", resH).put("n", count).put("m", model)); } catch (Exception ignored) { }
        saveQueue(); Toast.makeText(this, L.f("В очереди: %d", "Queued: %d", queue.size()), Toast.LENGTH_SHORT).show();
    }
    void saveQueue() { prefs().edit().putString("queue", new JSONArray(queue).toString()).apply(); refreshExtras(); }
    void showQueue() {
        if (queue.isEmpty()) return;
        String[] it = new String[queue.size()]; for (int i = 0; i < it.length; i++) { JSONObject o = queue.get(i); it[i] = (i + 1) + ". " + o.optString("p").replace('\n', ' ') + "  (" + o.optInt("w") + "×" + o.optInt("h") + " ×" + o.optInt("n") + ")"; }
        builder().setTitle(L.s("Очередь (нажмите, чтобы убрать)", "Queue (tap to remove)")).setItems(it, (d, i) -> { queue.remove(i); saveQueue(); })
            .setPositiveButton(L.s("Очистить", "Clear"), (d, i) -> { queue.clear(); saveQueue(); }).setNegativeButton(L.s("Закрыть", "Close"), null).show();
    }
    /** runs the queued items one after another through the normal Generate path */
    void runQueue() {
        if (busy || queue.isEmpty()) { if (queue.isEmpty()) Toast.makeText(this, L.s("Очередь пуста", "Queue is empty"), Toast.LENGTH_SHORT).show(); return; }
        JSONObject o = queue.remove(0); saveQueue();
        String m = o.optString("m"); if (!m.isEmpty() && ids.contains(m) && !m.equals(model)) setModel(m);
        prompt.setText(o.optString("p")); seed.setText(o.optString("s", "-1")); count = Math.max(1, o.optInt("n", 1)); setRes(o.optInt("w", resW), o.optInt("h", resH));
        afterGen = () -> { if (!queue.isEmpty() && !cancelled) ui.postDelayed(this::runQueue, 500); };
        generate(false);
    }
    // ---- menu, dialogs ------------------------------------------------------------------------
    void menu(View anchor) {
        final boolean video = Ltx.SHOWN;   // the Video screen is hidden while the engine is reworked (LTX quality too low, Wan in progress)
        final List<String> items = new ArrayList<>(); final List<Runnable> acts = new ArrayList<>();
        items.add(L.s("Модели", "Models")); acts.add(this::openModels);
        items.add("Krea 2 Turbo (experimental)"); acts.add(() -> startActivity(new Intent(this, KreaActivity.class)));
        if (video) { items.add(L.s("Видео (экспериментально)", "Video (experimental)")); acts.add(() -> { if (busy) Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); else startActivity(new Intent(this, VideoActivity.class)); }); }
        items.add("Civitai"); acts.add(() -> startActivityForResult(new Intent(this, CivitaiActivity.class), 14));
        items.add(L.s("Plantillas de configuración", "Configuration templates")); acts.add(this::presets);
        items.add(L.s("Ampliar una imagen de la galería", "Enlarge a picture from the gallery")); acts.add(() -> pickImage(REQ_UPSCALE_PICK));
        if (Tagger.supported()) { items.add(L.s("Prompt desde una imagen", "Prompt from a picture")); acts.add(this::promptFromPicture); }
        items.add(L.s("Открыть настройки из PNG", "Load settings from a PNG")); acts.add(() -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/png").addCategory(Intent.CATEGORY_OPENABLE), 8));
        items.add(L.s("Настройки", "Settings")); acts.add(() -> startActivity(new Intent(this, Settings.class)));
        items.add("Info"); acts.add(() -> startActivity(new Intent(this, Info.class)));
        popup(anchor, items.toArray(new String[0]), i -> acts.get(i).run());
    }
    /** "What's new" once after an update: assets/whatsnew_<lang>.txt, one item per line ("#" lines are headings) */
    void whatsNew() {
        String ver = "?"; try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        String seen = prefs().getString("seen_version", "");
        prefs().edit().putString("seen_version", ver).apply();
        if (seen.equals(ver) || isFinishing()) return;
        String txt;
        try (java.io.InputStream in = getAssets().open("whatsnew_" + (L.lang.equals("es") ? "es" : "en") + ".txt")) { java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r); txt = bo.toString("UTF-8"); } catch (Exception e) { return; }
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(16), dp(20), dp(8));
        v.addView(text(L.f("Novedades de la %s", "What's new in %s", ver), 21, HEAD, true));
        for (String line : txt.split("\n")) {
            line = line.trim(); if (line.isEmpty()) continue;
            TextView t = line.startsWith("#") ? text(line.substring(1).trim(), 15, ACC, true) : text("•  " + line, 14, TXT, false);
            t.setPadding(0, dp(line.startsWith("#") ? 12 : 5), 0, 0); v.addView(t);
        }
        ScrollView sv = new ScrollView(this); sv.addView(v);
        builder().setView(sv).setPositiveButton("OK", null).show();
    }
    /** the system photo picker (the app sees only the picture the user picks) */
    void pickImage(int req) {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        Intent i = android.os.Build.VERSION.SDK_INT >= 33 ? new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*") : new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, req);
    }
    /** a picked picture as a PNG file in the cache (decoded and upright; at most 4096 px on the long side) */
    File importPicked(android.net.Uri u, String name) throws Exception {
        Bitmap b;
        try (java.io.InputStream in = getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in); }
        if (b == null) throw new java.io.IOException(L.s("No se pudo abrir la imagen", "Could not open the picture"));
        try (java.io.InputStream in = getContentResolver().openInputStream(u)) {   // the photo's EXIF rotation
            int o = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1), deg = o == 6 ? 90 : o == 3 ? 180 : o == 8 ? 270 : 0;
            if (deg != 0) { android.graphics.Matrix m = new android.graphics.Matrix(); m.postRotate(deg); Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); if (r != b) { b.recycle(); b = r; } }
        } catch (Exception ignored) { }
        int lg = Math.max(b.getWidth(), b.getHeight()); if (lg > 4096) { Bitmap r = Bitmap.createScaledBitmap(b, b.getWidth() * 4096 / lg, b.getHeight() * 4096 / lg, true); b.recycle(); b = r; }
        File f = new File(getCacheDir(), name); try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } b.recycle();
        return f;
    }
    /** saved and recent prompts: tap to use; saved ones can be removed with ✕ */
    void templates() {
        final LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(14), dp(14), dp(10));
        final AlertDialog[] dlg = new AlertDialog[1];
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            box.removeAllViews();
            Button save = button(L.s("Сохранить текущее описание", "Save the current prompt"), ACC, ON_ACC); save.setTextSize(14);
            save.setOnClickListener(v -> {
                String t = prompt.getText().toString().trim();
                if (t.isEmpty()) { Toast.makeText(this, L.s("Введите описание", "Enter a prompt"), Toast.LENGTH_SHORT).show(); return; }
                List<String> l = savedPrompts(); l.remove(t); l.add(0, t); while (l.size() > 200) l.remove(l.size() - 1);
                prefs().edit().putString("saved", new JSONArray(l).toString()).apply(); fill[0].run();
            });
            box.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));
            List<String> sv = savedPrompts();
            if (!sv.isEmpty()) { box.addView(sect(L.s("Сохранённые", "Saved"))); for (String t : sv) box.addView(promptRow(t, true, dlg, fill)); }
            List<String> rc = recentPrompts();
            if (!rc.isEmpty()) {
                LinearLayout rh = new LinearLayout(this); rh.setGravity(Gravity.CENTER_VERTICAL);
                rh.addView(sect(L.s("Недавние", "Recent")), new LinearLayout.LayoutParams(0, -2, 1));
                TextView clr = link(L.s("Borrar recientes", "Clear recent")); rh.addView(clr);
                clr.setOnClickListener(v -> { prefs().edit().putLong("recent_cleared", System.currentTimeMillis()).apply(); fill[0].run(); });
                box.addView(rh);
                for (String t : rc) box.addView(promptRow(t, false, dlg, fill));
            }
        };
        fill[0].run();
        ScrollView s = new ScrollView(this); s.addView(box);
        dlg[0] = dialog(s);
    }
    TextView sect(String s) { TextView t = text(s, 12, DIM, true); t.setPadding(dp(4), dp(14), 0, dp(6)); return t; }
    View promptRow(String t, boolean removable, AlertDialog[] dlg, Runnable[] fill) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setBackground(bg(FIELD, 12)); r.setPadding(dp(12), dp(10), dp(8), dp(10));
        TextView tv = text(t, 13, TXT, false); tv.setMaxLines(2); tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        if (!removable) {   // a star saves a recent prompt (filled = already saved; tapping again removes it from Saved)
            final boolean isSaved = savedPrompts().contains(t);
            TextView st = text(isSaved ? "★" : "☆", 18, isSaved ? ACC : DIM, false); st.setPadding(dp(12), dp(4), dp(8), dp(4));
            st.setOnClickListener(v -> { List<String> l = savedPrompts(); if (l.contains(t)) l.remove(t); else l.add(0, t); while (l.size() > 200) l.remove(l.size() - 1); prefs().edit().putString("saved", new JSONArray(l).toString()).apply(); fill[0].run(); });
            r.addView(st);
        }
        if (removable) {
            TextView x = text("✕", 16, DIM, false); x.setPadding(dp(12), dp(4), dp(8), dp(4));
            x.setOnClickListener(v -> { List<String> l = savedPrompts(); l.remove(t); prefs().edit().putString("saved", new JSONArray(l).toString()).apply(); fill[0].run(); });
            r.addView(x);
        }
        r.setOnClickListener(v -> { prompt.setText(t); prompt.setSelection(t.length()); dlg[0].dismiss(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4);
        r.setLayoutParams(lp);
        return r;
    }
    List<String> savedPrompts() {
        List<String> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs().getString("saved", "[]")); for (int i = 0; i < a.length(); i++) l.add(a.getString(i)); } catch (Exception ignored) { }
        return l;
    }
    List<String> recentPrompts() {
        List<String> l = new ArrayList<>();
        File[] fs = Gallery.list(this, Gallery.ALL);
        long since = prefs().getLong("recent_cleared", 0);   // "Clear recent" hides older prompts; the pictures stay in the History
        for (int i = 0; i < fs.length && l.size() < 200; i++) {
            long t = 0; try { t = Long.parseLong(fs[i].getName().replaceAll("\\.png$", "")); } catch (NumberFormatException ignored) { }
            if (t <= since) break;
            String p = Gallery.meta(fs[i]).optString("prompt").trim(); if (!p.isEmpty() && !l.contains(p)) l.add(p);
        }
        return l;
    }
    /** one-tap tags by category; a lit chip is already in the prompt, tapping it again takes it out */
    void tagPalette() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(10));
        v.addView(text(L.s("Теги", "Tags"), 20, TXT, true));
        ScrollView sv = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, dp(420)));
        for (String[] cat : PALETTE) {
            String[] nm = cat[0].split("\\|");
            TextView h = text(L.lang.equals("es") ? nm[0] : nm[1], 11, DIM, true); h.setLetterSpacing(.12f); h.setPadding(dp(2), dp(12), 0, dp(6)); list.addView(h);
            HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this); hs.addView(row); list.addView(hs);
            for (int i = 1; i < cat.length; i++) {
                final String t = cat[i];
                TextView c = text(t, 14, TXT, false); c.setPadding(dp(12), dp(7), dp(12), dp(8));
                Runnable paint = () -> { boolean on = hasTag(t); GradientDrawable g = bg(on ? SEL : FIELD, 16); if (on) g.setStroke(dp(1.5f), ACC); c.setBackground(g); c.setTextColor(on ? ACC : TXT); };
                paint.run();
                c.setOnClickListener(x -> { toggleTag(t); paint.run(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(6); row.addView(c, lp);
            }
        }
        Button done = button(L.s("Готово", "Done"), ACC, ON_ACC);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(44)); dl.topMargin = dp(10); v.addView(done, dl);
        AlertDialog d = dialog(v); done.setOnClickListener(x -> d.dismiss());
    }
    /** − / +: the selection, or the comma-separated tag at the cursor, becomes (tag:w) with w ± 0.1; back at 1.0 the brackets go away */
    void weigh(float delta) {
        String s = prompt.getText().toString();
        int a = Math.max(0, prompt.getSelectionStart()), b = Math.max(0, prompt.getSelectionEnd()); if (a > b) { int t = a; a = b; b = t; }
        if (a == b) { a = s.lastIndexOf(',', Math.max(0, a - 1)) + 1; int e = s.indexOf(',', b); b = e < 0 ? s.length() : e; }
        while (a < b && Character.isWhitespace(s.charAt(a))) a++;
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
        if (a >= b) return;
        String t = s.substring(a, b); float w = 1f;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\((.*):([0-9.]+)\\)$").matcher(t);
        if (m.find()) { t = m.group(1); try { w = Float.parseFloat(m.group(2)); } catch (NumberFormatException ignored) { } }
        w = Math.round((w + delta) * 10) / 10f; w = Math.max(0.1f, Math.min(2f, w));
        String r = Math.abs(w - 1f) < .01f ? t : "(" + t + ":" + String.format(java.util.Locale.US, "%.1f", w) + ")";
        prompt.getText().replace(a, b, r); prompt.setSelection(a, a + r.length());
    }
    boolean hasTag(String t) { for (String x : tokens(prompt.getText().toString())) if (x.equalsIgnoreCase(t) || x.toLowerCase(java.util.Locale.ROOT).matches("\\(" + java.util.regex.Pattern.quote(t.toLowerCase(java.util.Locale.ROOT)) + ":[0-9.]+\\)")) return true; return false; }
    void toggleTag(String t) {
        List<String> cur = tokens(prompt.getText().toString()), keep = new ArrayList<>();
        boolean had = false;
        for (String x : cur) { if (x.equalsIgnoreCase(t) || x.toLowerCase(java.util.Locale.ROOT).matches("\\(" + java.util.regex.Pattern.quote(t.toLowerCase(java.util.Locale.ROOT)) + ":[0-9.]+\\)")) had = true; else keep.add(x); }
        if (!had) keep.add(t);
        prompt.setText(android.text.TextUtils.join(", ", keep)); prompt.setSelection(prompt.getText().length());
    }
    void freeMemoryDialog() {
        if (busy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        builder().setTitle(L.s("Liberar memoria de HexAnima", "Free HexAnima memory"))
            .setMessage(L.s("Descarga el modelo Anima de la NPU y libera las imágenes temporales de esta pantalla. Android administra la RAM de las demás aplicaciones; una app normal no puede cerrarlas a la fuerza.",
                "Unloads Anima from the NPU and releases temporary images from this screen. Android manages other apps' RAM; a regular app cannot force-close them."))
            .setNegativeButton(L.s("Cancelar", "Cancel"), null)
            .setPositiveButton(L.s("Liberar", "Free"), (d, w) -> ex2.submit(() -> {
                long before = RamWidget.avail(this); releaseAppMemory();
                try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                long freed = Math.max(0, RamWidget.avail(this) - before) / 1048576;
                ui.post(() -> Toast.makeText(this, L.f("Memoria disponible: +~%d MB", "Available memory: +~%d MB", freed), Toast.LENGTH_LONG).show());
            })).show();
    }
    /** Release only memory owned by HexAnima; Android reclaims other apps when the system needs it. */
    void releaseAppMemory() {
        RamWidget.unload(this); loaded = false;
        previewFile().delete(); System.gc();
        ui.post(() -> {
            if (img != null) { img.setImageDrawable(null); showImg(false); placeholder.setVisibility(View.VISIBLE); }
            if (state != null) setModelUi(false, false, "");
            RamWidget.updateAll(this);
            ui.post(ramTick);
        });
    }
    /** the last picture becomes the init image (to inpaint or vary it) */
    /** long-press on ↻: four new seeds, or (Anima) four pictures close to the shown one, drawn from it at denoise 0.45 */
    void variations() {
        if (busy || lastFile == null || !lastFile.isFile()) return;
        final boolean anima = !isMoss() && !isQwen() && !isMage() && !model.isEmpty();
        final int[] n = {Math.max(1, Math.min(16, prefs().getInt("var_count", 4)))};
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(8));
        v.addView(text(L.s("Variaciones", "Variations"), 20, TXT, true));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(10), 0, dp(4));
        row.addView(text(L.s("Cuántas", "How many"), 14, TXT, false), new LinearLayout.LayoutParams(0, -2, 1));
        final TextView val = text(String.valueOf(n[0]), 18, ACC, true); val.setGravity(Gravity.CENTER);
        Button minus = button("−", FIELD, ACC), plus = button("+", FIELD, ACC);
        minus.setOnClickListener(x -> { n[0] = Math.max(1, n[0] - 1); val.setText(String.valueOf(n[0])); });
        plus.setOnClickListener(x -> { n[0] = Math.min(16, n[0] + 1); val.setText(String.valueOf(n[0])); });
        row.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(44))); row.addView(val, new LinearLayout.LayoutParams(dp(56), -2)); row.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(44)));
        v.addView(row);
        Button seeds = button(L.s("Con semillas nuevas", "With new seeds"), FIELD, ACC); seeds.setAllCaps(false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(46)); bl.topMargin = dp(8); v.addView(seeds, bl);
        Button close = button(L.s("Parecidas a esta imagen", "Close to this picture"), ACC, ON_ACC); close.setAllCaps(false);
        if (anima) { LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, dp(46)); cl.topMargin = dp(8); v.addView(close, cl); }
        TextView note = text(anima ? L.s("«Parecidas» parte de esta imagen y cambia poco (img2img); «semillas nuevas» repite el prompt desde cero.", "\"Close\" starts from this picture and changes it a little (img2img); \"new seeds\" runs the prompt again from scratch.")
            : L.s("Repite el prompt con semillas nuevas.", "Runs the prompt again with new seeds."), 11, DIM, false);
        note.setPadding(dp(2), dp(8), 0, 0); v.addView(note);
        final AlertDialog dlg = dialog(v);
        seeds.setOnClickListener(x -> { dlg.dismiss(); prefs().edit().putInt("var_count", n[0]).apply(); countOv = n[0]; generate(true); });
        close.setOnClickListener(x -> {
            dlg.dismiss(); prefs().edit().putInt("var_count", n[0]).apply();
            File f = new File(getFilesDir(), "variation_src.png"); try { java.nio.file.Files.copy(lastFile.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception e) { return; }
            varInit = f; countOv = n[0]; generate(true);
        });
    }
    /** {red|blue|green} in the prompt: one option per picture, chosen from the seed (the same seed gives the same choice); the saved prompt holds the chosen words */
    static String wildcards(String pr, String seed) {
        if (pr.indexOf('{') < 0) return pr;
        Random r = new Random(seed.hashCode() * 31L + pr.hashCode());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^{}]*\\|[^{}]*)\\}").matcher(pr); StringBuffer sb = new StringBuffer();
        while (m.find()) { String[] o = m.group(1).split("\\|", -1); m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(o[r.nextInt(o.length)].trim())); }
        m.appendTail(sb); return sb.toString();
    }
    void compare() {
        if (busy || isMoss() || model.isEmpty()) return;
        String[] k = {L.s("4 сида", "4 seeds"), "CFG  1 · 3 · 5 · 7", L.s("Шаги  4 · 8 · 12 · 20", "Steps  4 · 8 · 12 · 20"), L.s("CFG на 100 · 80 · 70 · 60 % шагов", "CFG on 100 · 80 · 70 · 60 % of the steps")};
        builder().setTitle(L.s("Сравнить (сетка 2×2)", "Compare (2×2 grid)")).setItems(k, (d, i) -> { gridKind = i + 1; generate(false); }).show();
    }
    /** a local Anima checkpoint: copied into the app, converted on the phone (AnimaImport), then selectable like the catalog models */
    void importCheckpoint(Uri uri) {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        String nm = "Anima";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) { if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) nm = c.getString(i); } } catch (Exception ignored) { }
        final String name = nm.replaceAll("(?i)\\.safetensors$", "");
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(16), dp(20), dp(8));
        v.addView(text(name, 14, TXT, true));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000); v.addView(pb);
        TextView pt = text(L.s("Копирование…", "Copying…"), 12, DIM, false); v.addView(pt);
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        AlertDialog dl = builder().setTitle(L.s("Импорт модели", "Importing a model")).setView(v).setCancelable(false).setNegativeButton(L.s("Отмена", "Cancel"), (d, i) -> stop.set(true)).show();
        if (loaded) ex.submit(() -> { su("sh " + D + "/anima.sh stop < /dev/null >/dev/null 2>&1", false); loaded = false; setModelUi(false, false, ""); });   // the conversion needs the RAM
        ex2.submit(() -> {
            File dir = AnimaImport.importDir(getFilesDir()); dir.mkdirs(); File src = new File(dir, AnimaLora.fileName(name));
            String err = null;
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream o = new FileOutputStream(src)) {
                long total = -1; try (android.content.res.AssetFileDescriptor a = getContentResolver().openAssetFileDescriptor(uri, "r")) { if (a != null) total = a.getLength(); } catch (Exception ignored) { }
                byte[] b = new byte[1 << 16]; long done = 0, last = 0; final long tt = total;
                for (int n; (n = in.read(b)) > 0; ) { if (stop.get()) throw new IOException(L.s("Отменено", "Cancelled")); o.write(b, 0, n); done += n; if (done - last > (32 << 20)) { last = done; final long dd = done; ui.post(() -> { if (tt > 0) pb.setProgress((int) (1000 * dd / tt)); pt.setText(String.format(java.util.Locale.US, "%.1f GB", dd / 1e9)); }); } }
            } catch (Exception e) { err = e.getMessage(); }
            if (err == null) err = AnimaImport.convert(getFilesDir(), BIN, DEFAULT_BASE, src, "cv" + Long.toHexString(System.currentTimeMillis()), name,
                (d, t, what) -> ui.post(() -> { pb.setProgress(1000 * d / Math.max(1, t)); pt.setText(what + " " + d + "/" + t); }), stop);
            if (err != null) src.delete();
            final String e2 = err;
            ui.post(() -> {
                dl.dismiss();
                if (e2 != null) builder().setTitle(L.s("Не удалось импортировать", "Import failed")).setMessage(e2).setPositiveButton("OK", null).show();
                else { refreshModels(); builder().setTitle(name).setMessage("✓ " + L.s("Модель установлена: выберите её в списке моделей. Если это не «turbo»-модель, используйте 20–30 шагов и CFG 4–5 (или LoRA turbo для 2B).",
                    "Model installed: pick it in the model list. If it is not a \"turbo\" model, use 20–30 steps and CFG 4–5 (or a turbo LoRA on the 2B ones).")).setPositiveButton("OK", null).show(); }
            });
        });
    }
    void importAd(Uri uri) {
        ex2.submit(() -> {
            try { adDir().mkdirs(); File[] old = adDir().listFiles(); if (old != null) for (File f : old) f.delete();
                try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream o = new FileOutputStream(new File(adDir(), "detector.safetensors"))) { byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()); }
            ui.post(this::refreshExtras);
        });
    }
    void importInit(Uri uri) {
        ex2.submit(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Bitmap b = BitmapFactory.decodeStream(in); if (b == null) throw new IOException("not an image");
                try (FileOutputStream o = new FileOutputStream(initSrc())) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
                maskSrc().delete();   // a mask belongs to its picture
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()); }
            ui.post(this::refreshExtras);
        });
    }
    long availMb() { ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi); return mi.availMem / 1048576; }
    /** battery temperature in °C (Xiaomi phones report thermal status 0 even with the CPU at 80+ °C; the battery sensor is what an app can read) */
    float batteryC() { Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); return b == null ? 0 : b.getIntExtra("temperature", 0) / 10f; }
    /** plugged in: charging warms the battery by itself (~42 °C at rest over USB), so the battery limits are 3 °C higher */
    boolean charging() { Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); return b != null && b.getIntExtra("plugged", 0) != 0; }
    float hotC() { return charging() ? 45f : 42f; }
    boolean tooHot() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) return true;
        if (android.os.Build.VERSION.SDK_INT >= 30) { float hr = pm.getThermalHeadroom(0); if (!Float.isNaN(hr) && hr >= 0.95f) return true; }
        return batteryC() >= hotC();
    }
    /** "  ·  37.5 °C" (battery) for the RAM strip; the battery sensor is the one an app can read on every phone */
    String tempLabel() { float c = batteryC(); return c > 0 ? String.format(java.util.Locale.US, "  ·  %.1f °C", c) : ""; }
    /** notification when a batch pauses to let the phone cool down (the user may have left the app) */
    void notifyCooling(float c) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("heat", L.s("Pausa por calor", "Heat pause"), NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder nb = new Notification.Builder(this, "heat").setSmallIcon(android.R.drawable.stat_sys_warning).setAutoCancel(true)
                .setContentTitle(L.s("Tanda en pausa: el teléfono está caliente", "Batch paused: the phone is hot"))
                .setContentText(L.f("Batería a %.1f °C. Seguirá sola cuando se enfríe.", "Battery at %.1f °C. It goes on by itself once it cools down.", c))
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, Main.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(3, nb.build());
        } catch (Exception ignored) { }
    }
    /** Between pictures, keep the batch paused until the phone has cooled or the user presses Stop. */
    boolean waitCool(long t0) {
        long since = System.currentTimeMillis(); notifyCooling(batteryC());
        while (!cancelled) {
            float c = batteryC(); if (c < hotC() - 3f && !tooHot()) { try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(3); } catch (Exception ignored) { } return true; }
            long mins = (System.currentTimeMillis() - since) / 60000;
            final String m = L.f("Охлаждение телефона… %.1f °C", "Letting the phone cool down… %.1f °C", c);
            ui.post(() -> status.setText(m + "  ·  " + L.f("пауза %d мин", "paused %d min", mins)));
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
    void coolTick(boolean force) {
        long t = System.currentTimeMillis(); if (!force && t - coolCheck < 3000) return; coolCheck = t;
        String mode = Settings.coolMode(this); boolean on;
        if (mode.equals("on")) on = true;
        else if (mode.equals("off")) on = false;
        else {
            float c = batteryC(), lim = charging() ? 43f : 40f, hr = Float.NaN;
            if (android.os.Build.VERSION.SDK_INT >= 30) hr = ((PowerManager) getSystemService(POWER_SERVICE)).getThermalHeadroom(0);
            boolean hot = c >= lim || (!Float.isNaN(hr) && hr >= 0.75f), cold = c < lim - 2f && (Float.isNaN(hr) || hr < 0.65f);
            on = coolOn ? !cold : hot;
        }
        coolOn = on;
        File cf = new File(D, "cool");
        try { if (on) { if (!cf.exists()) new java.io.FileOutputStream(cf).close(); } else if (cf.exists()) cf.delete(); } catch (Exception ignored) { }
    }
    boolean tooHotStatus() { return ((PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE; }
}
