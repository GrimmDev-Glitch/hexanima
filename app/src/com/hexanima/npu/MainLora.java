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

/** part of the main screen (split out of Main.java): MainLora */
abstract class MainLora extends Base {
    static class Fold { LinearLayout body; TextView sum, chev; }
    static java.util.regex.Pattern loraTag(String n) { return java.util.regex.Pattern.compile("<lora:" + java.util.regex.Pattern.quote(n) + "(?::([^>]*))?>", java.util.regex.Pattern.CASE_INSENSITIVE); }
    interface OnText { void on(String s); }
    String D, BIN;   // work dir (files/anima) and the native lib dir with the bundled executables
    static final String GPT_LORA = "gpt-image-2", GPT_TRIGGER = "@gpt-image-2", GPT_URL = "https://civitai.com/api/download/models/2998634?fileId=2878107",
        GPT_SHA = "6ecff93984c1b084950f4535ff71293299dc1ee2225d267b79e5186fc7d38a99";
    static final long GPT_SIZE = 23037256L;
    volatile boolean loaded, busy, cancelled;
    final ExecutorService ex2 = Executors.newSingleThreadExecutor();   // the Stop button
    MultiAutoCompleteTextView prompt;
    Fold imgFold, qwenFold;
    LinearLayout qwenLoraBox, qwenStrRow; TextView qwenLoraName, qwenStrVal; Button qwenLoraPick, qwenLoraDrop; SeekBar qwenStr; volatile boolean qwenFolding;
    final Handler ui = new Handler(Looper.getMainLooper());
    abstract String alias(boolean lora, String key);
    abstract void appendPromptWords(String words);
    abstract boolean isMoss();
    abstract TextView link(String s);
    abstract void loraAdd(String n, String w, String words);
    abstract void loraRemove(String n, String words);
    abstract void loraSetWeight(String n, String w);
    abstract String loraWeight(String n);
    abstract void pickLora();
    abstract SharedPreferences prefs();
    abstract void refreshLora();
    abstract void setAlias(boolean lora, String key, String name);
    static List<String> tokens(String s) { List<String> r = new ArrayList<>(); for (String t : s.split(",")) { t = t.trim(); if (!t.isEmpty()) r.add(t); } return r; }
    static android.text.TextWatcher watch(OnText cb) {
        return new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) { cb.on(s.toString()); }
        };
    }
    /** LoRA library: [✓] name · trigger words · weight, a filter on top; long tap offers trigger copying or deletion; Civitai finds more */
    void loraLibrary() {
        if (isMoss()) { mossLoraLibrary(); return; }
        final File dir = AnimaLora.dir(getFilesDir());
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(10));
        LinearLayout th = new LinearLayout(this); th.setGravity(Gravity.CENTER_VERTICAL);
        th.addView(text("LoRAs", 20, TXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        final TextView cnt = text("", 12, ACC, true); th.addView(cnt); v.addView(th);
        final EditText filter = field(L.s("Фильтр: имя или слово…", "Filter: name or word…")); filter.setSingleLine(true); filter.setTextSize(15);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2); fl.topMargin = dp(10); fl.bottomMargin = dp(8); v.addView(filter, fl);
        final HorizontalScrollView catScroll = new HorizontalScrollView(this); catScroll.setHorizontalScrollBarEnabled(false); final LinearLayout catRow = new LinearLayout(this); catScroll.addView(catRow);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(-1, -2); crl.bottomMargin = dp(8); v.addView(catScroll, crl);
        final List<String> rowCats = new ArrayList<>(); final String[] catSel = {""};
        ScrollView sv = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, dp(340)));
        LinearLayout bot = new LinearLayout(this); bot.setPadding(0, dp(10), 0, 0);
        Button civ = button("Civitai", FIELD, ACC); bot.addView(civ, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button imp = button(L.s("Импорт", "Import"), FIELD, ACC); LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, dp(44), 1); il.leftMargin = dp(8); bot.addView(imp, il);
        Button done = button(L.s("Готово", "Done"), ACC, ON_ACC); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(44), 1); dl.leftMargin = dp(8); bot.addView(done, dl);
        v.addView(bot);
        final AlertDialog dlg = dialog(v);
        civ.setOnClickListener(x -> { dlg.dismiss(); startActivityForResult(new Intent(this, CivitaiActivity.class), 14); });
        done.setOnClickListener(x -> dlg.dismiss());
        imp.setOnClickListener(x -> { dlg.dismiss(); pickLora(); });   // a local .safetensors from the phone's storage, checked like a Civitai download

        List<String> names = new ArrayList<>(); File[] lf = dir.listFiles();
        if (lf != null) for (File f : lf) if (f.getName().endsWith(".safetensors") && !f.getName().startsWith("new.")) names.add(f.getName().substring(0, f.getName().length() - 12));
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) list.addView(text(L.s("Пусто: скачайте LoRA с Civitai или импортируйте файл.", "Empty: download LoRAs from Civitai or import a file."), 14, DIM, false));
        final List<View> rows = new ArrayList<>(); final List<String> keys = new ArrayList<>(); final List<CheckBox> boxes = new ArrayList<>();
        final Runnable count = () -> { int k = 0; for (CheckBox b : boxes) if (b.isChecked()) k++; cnt.setText(k == 0 ? "" : L.f("активно: %d", "active: %d", k)); };
        for (String n : names) {
            File f = new File(dir, n + ".safetensors"); JSONObject md = Civitai.meta(f); final String words = Civitai.words(f);
            final String title0 = md != null && !md.optString("name").isEmpty() ? md.optString("name") : n, al = alias(true, "anima/" + n);
            String title = al.isEmpty() ? title0 : al;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(4), dp(8), dp(10), dp(8));
            String w0 = loraWeight(n);
            row.setBackground(bg(w0 != null ? SEL : FIELD, 12));
            CheckBox cb = new CheckBox(this); cb.setButtonTintList(ColorStateList.valueOf(ACC)); cb.setChecked(w0 != null);
            row.addView(cb);
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            TextView tn = text(title, 15, TXT, true); tn.setSingleLine(true); tn.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(tn);
            TextView sub = text(words.isEmpty() ? n : words, 12, DIM, false); sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView pen = text("✎", 16, DIM, false); pen.setPadding(dp(6), dp(4), dp(4), dp(4));
            pen.setOnClickListener(x -> renameDialog(true, "anima/" + n, title0, () -> { dlg.dismiss(); loraLibrary(); }));
            row.addView(pen);
            EditText we = field("1.0"); we.setText(w0 != null ? w0 : "1.0"); we.setTextSize(14); we.setGravity(Gravity.CENTER); we.setSingleLine(true);
            we.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); we.setPadding(dp(4), dp(6), dp(4), dp(6)); we.setBackground(bg(CARD, 10));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(56), -2); wl.leftMargin = dp(8); row.addView(we, wl);
            cb.setOnCheckedChangeListener((b, on) -> {
                String w = we.getText().toString().trim().replace(',', '.'); if (w.isEmpty()) w = "1.0";
                if (on) { if (loraWeight(n) == null) loraAdd(n, w, words); } else loraRemove(n, words);
                row.setBackground(bg(on ? SEL : FIELD, 12)); count.run();
            });
            we.addTextChangedListener(watch(s -> { String w = s.trim().replace(',', '.'); try { Float.parseFloat(w); if (cb.isChecked()) loraSetWeight(n, w); } catch (NumberFormatException ignored) { } }));
            row.setOnClickListener(x -> cb.toggle());
            row.setOnLongClickListener(x -> { loraMenu(title, f, words, () -> builder().setMessage(L.f("Удалить %s?", "Delete %s?", n)).setPositiveButton(L.s("Удалить", "Delete"), (d, i) -> { if (cb.isChecked()) loraRemove(n, words); f.delete(); Civitai.metaFile(f).delete(); dlg.dismiss(); loraLibrary(); }).setNegativeButton(L.s("Отмена", "Cancel"), null).show(), () -> { dlg.dismiss(); loraLibrary(); }); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); list.addView(row, lp);
            rows.add(row); keys.add((n + " " + title + " " + words).toLowerCase(java.util.Locale.ROOT)); boxes.add(cb); rowCats.add(Civitai.category(f));
        }
        count.run();
        // the folded blocks of the last LoRA set (megarun MEGA_LORA_CACHE): makes the next load as fast as without a LoRA, costs a model's size on storage
        final File lc = new File(D, "lcache"); long lb = 0; File[] ks = lc.listFiles();
        if (ks != null) for (File k : ks) { File[] fs = k.listFiles(); if (fs != null) for (File f : fs) lb += f.length(); }
        final File[] tes = new File(D, "tecache").listFiles();   // the text encoder with LoRA text-encoder weights merged in (animad)
        if (tes != null) for (File f : tes) lb += f.length();
        if (lb > 0) {
            TextView cl = link(L.f("Кэш LoRA: %.1f ГБ · очистить", "LoRA cache: %.1f GB · clear", lb / 1e9)); cl.setPadding(dp(2), dp(8), 0, 0);
            cl.setOnClickListener(x -> { if (ks != null) for (File k : ks) { File[] fs = k.listFiles(); if (fs != null) for (File f : fs) f.delete(); k.delete(); } if (tes != null) for (File f : tes) f.delete(); cl.setVisibility(View.GONE); });
            v.addView(cl, v.getChildCount() - 1);
        }
        final Runnable applyF = () -> { String q = filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT); for (int i = 0; i < rows.size(); i++) rows.get(i).setVisibility((q.isEmpty() || keys.get(i).contains(q)) && catMatch(rowCats.get(i), catSel[0]) ? View.VISIBLE : View.GONE); };
        fillCatChips(catRow, rowCats, catSel, applyF);
        filter.addTextChangedListener(watch(s -> applyF.run()));
        addDetectLink(v, dir, () -> { dlg.dismiss(); loraLibrary(); });
    }
    /** MOSS has one A/B adapter slot: choose a validated file from the Civitai library, or clear the active slot. */
    void mossLoraLibrary() {
        final File dir = MossLora.civitaiDir(getFilesDir()); dir.mkdirs();
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(10));
        v.addView(text(L.s("Библиотека MOSS", "MOSS-Image LoRA library"), 20, TXT, true));
        v.addView(text(L.s("Выберите одну совместимую LoRA A/B. Сила настраивается в блоке LoRA.", "Choose one compatible A/B LoRA or LoKr. Set its strength in the LoRA section."), 12, DIM, false));
        EditText filter = field(L.s("Фильтр: имя или слово…", "Filter: name or word…")); filter.setSingleLine(true); filter.setTextSize(15);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2); fl.topMargin = dp(10); fl.bottomMargin = dp(8); v.addView(filter, fl);
        final HorizontalScrollView catScroll = new HorizontalScrollView(this); catScroll.setHorizontalScrollBarEnabled(false); final LinearLayout catRow = new LinearLayout(this); catScroll.addView(catRow);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(-1, -2); crl.bottomMargin = dp(8); v.addView(catScroll, crl);
        final List<String> rowCats = new ArrayList<>(); final String[] catSel = {""};
        ScrollView sv = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, dp(340)));
        LinearLayout bot = new LinearLayout(this); bot.setPadding(0, dp(10), 0, 0);
        Button civ = button("Civitai", FIELD, ACC); bot.addView(civ, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button imp = button(L.s("Импорт", "Import"), FIELD, ACC); LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, dp(44), 1); il.leftMargin = dp(8); bot.addView(imp, il);
        Button done = button(L.s("Готово", "Done"), ACC, ON_ACC); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(44), 1); dl.leftMargin = dp(8); bot.addView(done, dl); v.addView(bot);
        final AlertDialog dlg = dialog(v);
        civ.setOnClickListener(x -> { dlg.dismiss(); startActivityForResult(new Intent(this, CivitaiActivity.class), 14); });
        done.setOnClickListener(x -> dlg.dismiss());
        imp.setOnClickListener(x -> { dlg.dismiss(); pickLora(); });   // a local .safetensors from the phone's storage, checked like a Civitai download

        List<View> rows = new ArrayList<>(); List<String> keys = new ArrayList<>();
        File[] fs = dir.listFiles(); if (fs != null) Arrays.sort(fs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        if (fs == null || fs.length == 0) list.addView(text(L.s("Пусто: скачайте LoRA с Civitai или импортируйте файл.", "Empty: download LoRAs from Civitai or import a file."), 14, DIM, false));
        for (File f : fs == null ? new File[0] : fs) {
            if (!f.isFile() || !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".safetensors")) continue;
            JSONObject md = Civitai.meta(f); String words = Civitai.words(f);
            final String name0 = md != null && !md.optString("name").isEmpty() ? md.optString("name") : f.getName().replaceFirst("(?i)\\.safetensors$", ""), mal = alias(true, "moss/" + f.getName());
            String name = mal.isEmpty() ? name0 : mal;
            String vid = md == null ? "" : Long.toString(md.optLong("version_id"));
            String currentId = prefs().getString("moss_lora_id", "");
            boolean active = prefs().getString("moss_lora_file", "").equals(f.getName()) || (!vid.equals("0") && currentId.equals("civitai-" + vid));
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(bg(active ? SEL : FIELD, 12));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            TextView title = text((active ? "✓  " : "") + name, 15, TXT, true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(title);
            String sub = words.isEmpty() ? L.s("Sin palabras activadoras declaradas", "No trigger words declared") : words;
            TextView detail = text(sub, 12, DIM, false); detail.setSingleLine(true); detail.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(detail);
            TextView size = text(String.format(java.util.Locale.US, "%.0f MB", f.length() / 1048576.0), 11, DIM, false); col.addView(size);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView mpen = text("✎", 16, DIM, false); mpen.setPadding(dp(6), dp(4), dp(8), dp(4));
            mpen.setOnClickListener(x -> renameDialog(true, "moss/" + f.getName(), name0, () -> { dlg.dismiss(); mossLoraLibrary(); }));
            row.addView(mpen);
            Button choose = button(active ? L.s("Quitar selección", "Clear selection") : L.s("Elegir", "Select"), CARD, active ? WARN : ACC); choose.setTextSize(12); row.addView(choose, new LinearLayout.LayoutParams(dp(112), dp(40)));
            View.OnClickListener select = z -> {
                if (active) {
                    MossLora.clearSelection(getFilesDir()); prefs().edit().remove("moss_lora_id").remove("moss_lora_name").remove("moss_lora_info").remove("moss_lora_file").apply();
                    refreshLora(); dlg.dismiss(); return;
                }
                MossLora.Check c = MossLora.check(BIN, f);
                if (!c.ok) { builder().setMessage(c.message()).setPositiveButton("OK", null).show(); return; }
                try {
                    MossLora.select(getFilesDir(), f);
                    String id = vid.isEmpty() || vid.equals("0") ? "file-" + f.getName() : "civitai-" + vid;
                    String info = "✓ " + c.message() + (words.isEmpty() ? "" : "\n" + L.s("Activadores: ", "Triggers: ") + words);
                    prefs().edit().putString("moss_lora_id", id).putString("moss_lora_name", name).putString("moss_lora_info", info).putString("moss_lora_file", f.getName()).apply();
                    appendPromptWords(words); refreshLora(); dlg.dismiss();
                } catch (Exception e) { builder().setMessage(L.f("No se pudo seleccionar el LoRA: %s", "Could not select the LoRA: %s", e.getMessage())).setPositiveButton("OK", null).show(); }
            };
            row.setOnClickListener(select); choose.setOnClickListener(select);
            row.setOnLongClickListener(z -> { loraMenu(name, f, words, null, () -> { dlg.dismiss(); mossLoraLibrary(); }); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); list.addView(row, lp); rows.add(row); keys.add((name + " " + words + " " + f.getName()).toLowerCase(java.util.Locale.ROOT)); rowCats.add(Civitai.category(f));
        }
        final Runnable applyF = () -> { String q = filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT); for (int i = 0; i < rows.size(); i++) rows.get(i).setVisibility((q.isEmpty() || keys.get(i).contains(q)) && catMatch(rowCats.get(i), catSel[0]) ? View.VISIBLE : View.GONE); };
        fillCatChips(catRow, rowCats, catSel, applyF);
        filter.addTextChangedListener(watch(s -> applyF.run()));
        addDetectLink(v, dir, () -> { dlg.dismiss(); mossLoraLibrary(); });
    }
    /** Qwen LoRA library, laid out like the Anima and MOSS ones: filter, categories, one row per converted LoRA (name, trigger words, size), ✎ to rename,
     *  Select / Clear selection, long-press for the trigger-word and delete menu; Civitai, Import and Done at the bottom */
    void pickQwenLora() {
        final File root = Qwen.lorasDir(getFilesDir());
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(10));
        v.addView(text(L.s("Biblioteca de LoRAs de Qwen", "Qwen-Image LoRA library"), 20, TXT, true));
        v.addView(text(L.s("Elige una LoRA convertida para Qwen-Image 2.1. Su fuerza se ajusta en la sección LoRA.", "Choose a LoRA converted for Qwen-Image 2.1. Set its strength in the LoRA section."), 12, DIM, false));
        EditText filter = field(L.s("Фильтр: имя или слово…", "Filter: name or word…")); filter.setSingleLine(true); filter.setTextSize(15);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2); fl.topMargin = dp(10); fl.bottomMargin = dp(8); v.addView(filter, fl);
        final HorizontalScrollView catScroll = new HorizontalScrollView(this); catScroll.setHorizontalScrollBarEnabled(false); final LinearLayout catRow = new LinearLayout(this); catScroll.addView(catRow);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(-1, -2); crl.bottomMargin = dp(8); v.addView(catScroll, crl);
        final List<String> rowCats = new ArrayList<>(); final String[] catSel = {""};
        ScrollView sv = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, dp(340)));
        LinearLayout bot = new LinearLayout(this); bot.setPadding(0, dp(10), 0, 0);
        Button civ = button("Civitai", FIELD, ACC); bot.addView(civ, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button imp = button(L.s("Импорт", "Import"), FIELD, ACC); LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, dp(44), 1); il.leftMargin = dp(8); bot.addView(imp, il);
        Button done = button(L.s("Готово", "Done"), ACC, ON_ACC); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(44), 1); dl.leftMargin = dp(8); bot.addView(done, dl); v.addView(bot);
        final AlertDialog dlg = dialog(v);
        civ.setOnClickListener(x -> { dlg.dismiss(); startActivityForResult(new Intent(this, CivitaiActivity.class), 14); });
        done.setOnClickListener(x -> dlg.dismiss());
        imp.setOnClickListener(x -> { dlg.dismiss(); try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 16); } catch (Exception e) { Toast.makeText(this, L.s("No se pudo abrir el selector de archivos", "Could not open the file picker"), Toast.LENGTH_LONG).show(); } });
        final Runnable reopen = () -> { dlg.dismiss(); refreshQwenLora(); pickQwenLora(); };
        List<View> rows = new ArrayList<>(); List<String> keys = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory); List<File> ovs = new ArrayList<>();
        if (dirs != null) for (File f : dirs) if (Qwen.validOverlay(f)) ovs.add(f);
        ovs.sort((a, b) -> qwenOverlayTitle(a).compareToIgnoreCase(qwenOverlayTitle(b)));
        if (ovs.isEmpty()) list.addView(text(L.s("Vacía: descarga LoRAs de Qwen-Image 2.1 desde Civitai o importa un archivo .safetensors.", "Empty: download Qwen-Image 2.1 LoRAs from Civitai or import a .safetensors file."), 14, DIM, false));
        final String cur = prefs().getString("qwen_lora_dir", "");
        for (final File ov : ovs) {
            final String nm = ov.getName(), name = qwenOverlayTitle(ov), words = Qwen.overlayTriggers(ov);
            final boolean active = nm.equals(cur);
            File src = Qwen.srcFile(getFilesDir(), nm); long bytes = src.isFile() ? src.length() : 0;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(bg(active ? SEL : FIELD, 12));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            TextView title = text((active ? "✓  " : "") + name, 15, TXT, true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(title);
            TextView detail = text(words.isEmpty() ? L.s("Sin palabras activadoras declaradas", "No trigger words declared") : words, 12, DIM, false); detail.setSingleLine(true); detail.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(detail);
            if (bytes > 0) col.addView(text(String.format(java.util.Locale.US, "%.0f MB", bytes / 1048576.0), 11, DIM, false));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView pen = text("✎", 16, DIM, false); pen.setPadding(dp(6), dp(4), dp(8), dp(4));
            pen.setOnClickListener(x -> renameDialog(true, "qwen/" + nm, Qwen.overlayName(ov), reopen));
            row.addView(pen);
            Button choose = button(active ? L.s("Quitar selección", "Clear selection") : L.s("Elegir", "Select"), CARD, active ? WARN : ACC); choose.setTextSize(12); row.addView(choose, new LinearLayout.LayoutParams(dp(112), dp(40)));
            View.OnClickListener select = z -> {
                SharedPreferences.Editor edit = prefs().edit();
                if (active) edit.remove("qwen_lora_dir"); else edit.putString("qwen_lora_dir", nm);
                edit.apply(); refreshQwenLora(); dlg.dismiss();
            };
            row.setOnClickListener(select); choose.setOnClickListener(select);
            row.setOnLongClickListener(z -> { qwenLoraMenu(nm, ov, reopen); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); list.addView(row, lp); rows.add(row);
            keys.add((name + " " + words + " " + nm).toLowerCase(java.util.Locale.ROOT)); rowCats.add(src.isFile() ? Civitai.category(src) : "");
        }
        final Runnable applyF = () -> { String q = filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT); for (int i = 0; i < rows.size(); i++) rows.get(i).setVisibility((q.isEmpty() || keys.get(i).contains(q)) && catMatch(rowCats.get(i), catSel[0]) ? View.VISIBLE : View.GONE); };
        fillCatChips(catRow, rowCats, catSel, applyF);
        filter.addTextChangedListener(watch(s -> applyF.run()));
    }
    /** long-press menu of a Qwen LoRA: trigger words, rename, delete */
    void qwenLoraMenu(final String nm, final File ov, final Runnable reopen) {
            builder().setTitle(qwenOverlayTitle(ov)).setItems(new String[]{L.s("Copiar palabras activadoras", "Copy trigger words"), L.s("Editar palabras activadoras…", "Edit trigger words…"), L.s("Añadir activadoras al prompt", "Add trigger words to the prompt"), L.s("Cambiar nombre", "Rename"), L.s("Eliminar", "Delete")}, (d2, k) -> {
                if (k == 3) renameDialog(true, "qwen/" + nm, Qwen.overlayName(ov), () -> { reopen.run(); });
                else if (k == 1) editTriggers(Qwen.overlayName(ov), Qwen.overlayTriggers(ov), s -> Qwen.setOverlayTriggers(ov, s));
                else if (k == 2) { String t = Qwen.overlayTriggers(ov); if (t.isEmpty()) Toast.makeText(this, L.s("Sin palabras activadoras: usa «Editar palabras activadoras…»", "No trigger words: use \"Edit trigger words…\""), Toast.LENGTH_LONG).show(); else { appendPromptWords(t); reopen.run(); } }
                else if (k == 0) {
                    String trigger = Qwen.overlayTriggers(ov);
                    if (trigger.isEmpty()) Toast.makeText(this, L.s("Este overlay no tiene palabras activadoras guardadas", "This overlay has no saved trigger words"), Toast.LENGTH_LONG).show();
                    else { android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); if (clipboard != null) { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Qwen trigger words", trigger)); Toast.makeText(this, L.s("Palabras activadoras copiadas", "Trigger words copied"), Toast.LENGTH_SHORT).show(); } }
                } else builder().setMessage(L.f("¿Eliminar «%s»? Se borran el overlay y el archivo original.", "Delete \"%s\"? The overlay and the original file are removed.", Qwen.overlayName(ov))).setNegativeButton(L.s("Cancelar", "Cancel"), null)
                    .setPositiveButton(L.s("Eliminar", "Delete"), (d3, k3) -> {
                        MossLora.deleteTree(ov); Qwen.srcFile(getFilesDir(), nm).delete(); Civitai.metaFile(Qwen.srcFile(getFilesDir(), nm)).delete();
                        if (nm.equals(prefs().getString("qwen_lora_dir", ""))) prefs().edit().remove("qwen_lora_dir").apply();
                        setAlias(true, "qwen/" + nm, "");   // a later overlay with the same folder name starts without the old name
                        reopen.run();
                    }).show();
            }).show();
    }
    void importQwenOverlay() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try { startActivityForResult(i, 15); }
        catch (Exception e) { Toast.makeText(this, L.s("No se pudo abrir el selector de archivos", "Could not open the file picker"), Toast.LENGTH_LONG).show(); }
    }
    /** long tap on a library LoRA: category, trigger words, and (Anima) delete */
    void loraMenu(String title, File f, String words, Runnable delete, Runnable reopen) {
        List<String> items = new ArrayList<>(); items.add(L.f("Categoría: %s…", "Category: %s…", Civitai.catName(Civitai.category(f)))); items.add(L.s("Copiar palabras activadoras", "Copy trigger words"));
        items.add(L.s("Editar palabras activadoras…", "Edit trigger words…")); items.add(L.s("Añadir activadoras al prompt", "Add trigger words to the prompt")); if (delete != null) items.add(L.s("Удалить", "Delete"));
        builder().setTitle(title).setItems(items.toArray(new String[0]), (d, i) -> {
            if (i == 0) {
                String[] names = new String[Civitai.CATS.length + 1]; names[0] = Civitai.catName("");
                for (int k = 0; k < Civitai.CATS.length; k++) names[k + 1] = Civitai.catName(Civitai.CATS[k]);
                builder().setTitle(L.s("Categoría", "Category")).setItems(names, (d2, k) -> { Civitai.setCategory(f, k == 0 ? "none" : Civitai.CATS[k - 1]); reopen.run(); }).show();
            } else if (i == 1) {
                if (words.isEmpty()) { Toast.makeText(this, L.s("Sin palabras activadoras declaradas", "No trigger words declared"), Toast.LENGTH_SHORT).show(); return; }
                android.content.ClipboardManager clip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText("LoRA triggers", words));
            } else if (i == 2) editTriggers(title, words, s -> { Civitai.setWords(f, s); reopen.run(); });
            else if (i == 3) { if (words.isEmpty()) Toast.makeText(this, L.s("Sin palabras activadoras: usa «Editar palabras activadoras…»", "No trigger words: use \"Edit trigger words…\""), Toast.LENGTH_LONG).show(); else appendPromptWords(words); }
            else if (delete != null) delete.run();
        }).show();
    }
    /** All + the categories present in this library (+ Uncategorised when some have none); hidden when nothing is categorised */
    void fillCatChips(LinearLayout row, List<String> cats, String[] sel, Runnable apply) {
        row.removeAllViews();
        List<String> vals = new ArrayList<>(); vals.add("");
        for (String c : Civitai.CATS) if (cats.contains(c)) vals.add(c);
        if (cats.contains("")) vals.add("-");
        ((View) row.getParent()).setVisibility(vals.size() > 2 || (vals.size() == 2 && !vals.get(1).equals("-")) ? View.VISIBLE : View.GONE);
        for (String v : vals) {
            int k = 0; for (String c : cats) if (catMatch(c, v)) k++;
            Button b = button((v.isEmpty() ? L.s("Todas", "All") : Civitai.catName(v.equals("-") ? "" : v)) + "  " + k, v.equals(sel[0]) ? SEL : FIELD, v.equals(sel[0]) ? ACC : TXT); b.setTextSize(12);
            b.setOnClickListener(x -> { sel[0] = v; fillCatChips(row, cats, sel, apply); apply.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34)); if (row.getChildCount() > 0) lp.leftMargin = dp(6); row.addView(b, lp);
        }
    }
    // ---- LoRA categories (character, clothing, pose, style…): chips over a library, from the Civitai tags or the user's own choice ----
    static boolean catMatch(String cat, String sel) { return sel.isEmpty() || (sel.equals("-") ? cat.isEmpty() : sel.equals(cat)); }
    /** files downloaded before the tags were stored: one tap fetches their Civitai tags */
    void addDetectLink(LinearLayout v, File dir, Runnable reopen) {
        final List<File> todo = new ArrayList<>(); File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) { if (!f.getName().endsWith(".safetensors")) continue; JSONObject m = Civitai.meta(f); if (m != null && !m.has("tags") && m.optLong("model_id") > 0) todo.add(f); }
        if (todo.isEmpty()) return;
        TextView l = link(L.f("Detectar categorías en Civitai (%d)", "Detect categories on Civitai (%d)", todo.size())); l.setPadding(dp(2), dp(8), 0, 0);
        l.setOnClickListener(x -> { l.setText(L.s("Consultando Civitai…", "Asking Civitai…")); l.setEnabled(false); ex2.submit(() -> { for (File f : todo) Civitai.fillTags(this, f); ui.post(reopen); }); });
        v.addView(l, v.getChildCount() - 1);
    }
    /** text box for the trigger words / trigger prompt of a LoRA (comma separated) */
    void editTriggers(String title, String words, java.util.function.Consumer<String> save) {
        EditText e = new EditText(this); e.setText(words); e.setHint(L.s("p. ej. estilo3d, personaje x", "e.g. 3dstyle, character x")); e.setMinLines(2); e.setTextColor(TXT); e.setSelection(e.getText().length());
        FrameLayout fl = new FrameLayout(this); fl.setPadding(dp(20), dp(8), dp(20), 0); fl.addView(e);
        builder().setTitle(L.f("Activadoras: %s", "Trigger words: %s", title)).setMessage(L.s("Palabras o frase que activan la LoRA, separadas por comas. Se añaden al prompt con «Añadir activadoras al prompt».", "Words or a phrase that activate the LoRA, comma separated. \"Add trigger words to the prompt\" puts them in the prompt."))
            .setView(fl).setNegativeButton(L.s("Cancelar", "Cancel"), null).setPositiveButton(L.s("Guardar", "Save"), (d, k) -> save.accept(e.getText().toString().trim())).show();
    }
    /** rename dialog: an empty name or Reset brings the original back */
    void renameDialog(boolean lora, String key, String original, Runnable after) {
        final EditText e = field(original); e.setSingleLine(true); String cur = alias(lora, key); e.setText(cur.isEmpty() ? original : cur); e.setSelection(e.getText().length());
        LinearLayout box = new LinearLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        builder().setTitle(L.s("Cambiar nombre", "Rename")).setView(box)
            .setPositiveButton("OK", (d, w) -> { String n = e.getText().toString().trim(); setAlias(lora, key, n.equals(original) ? "" : n); after.run(); })
            .setNeutralButton(L.s("Restablecer", "Reset"), (d, w) -> { setAlias(lora, key, ""); after.run(); })
            .setNegativeButton(L.s("Отмена", "Cancel"), null).show();
    }
    void gptStyle() {
        if (loraWeight(GPT_LORA) != null) {   // on -> off
            String p = loraTag(GPT_LORA).matcher(prompt.getText().toString()).replaceAll("");
            List<String> keep = new ArrayList<>(); for (String t : tokens(p)) if (!t.equalsIgnoreCase(GPT_TRIGGER)) keep.add(t);
            prompt.setText(android.text.TextUtils.join(", ", keep)); prompt.setSelection(prompt.getText().length()); return;
        }
        final File f = new File(AnimaLora.dir(getFilesDir()), GPT_LORA + ".safetensors");
        if (f.isFile() && f.length() == GPT_SIZE) { gptMeta(f); gptApply(); return; }
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(12), dp(20), dp(4));
        v.addView(text(L.s("Descarga la LoRA «Style > gpt-image-2» de AgileNumbers desde Civitai (22 MB) y la añade al prompt. Da el acabado de ilustración del ChatGPT actual: líneas finas, ojos detallados y luz suave.",
            "Downloads the LoRA \"Style > gpt-image-2\" by AgileNumbers from Civitai (22 MB) and adds it to the prompt. It gives the illustration finish of today's ChatGPT: thin lines, detailed eyes and soft light."), 13, DIM, false));
        final ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000); pb.setProgressTintList(ColorStateList.valueOf(ACC)); v.addView(pb);
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        final android.app.AlertDialog dl = builder().setTitle(L.s("Estilo ChatGPT", "ChatGPT style")).setView(v).setCancelable(false).setNegativeButton(L.s("Отмена", "Cancel"), (d, i) -> stop.set(true)).show();
        ex2.submit(() -> {
            try {
                Civitai.download(this, GPT_URL, f, GPT_SIZE, (d, t) -> ui.post(() -> { if (t > 0) pb.setProgress((int) (1000 * d / t)); }), stop);
                if (!GPT_SHA.equalsIgnoreCase(Models.sha256(f))) { f.delete(); throw new IOException(L.s("el archivo descargado no coincide (SHA-256)", "the downloaded file does not match (SHA-256)")); }
                AnimaLora.Check c = AnimaLora.check(f); if (!c.ok) { f.delete(); throw new IOException(c.message()); }
                ui.post(() -> { dl.dismiss(); gptMeta(f); gptApply(); });
            } catch (Exception e) { f.delete(); ui.post(() -> { dl.dismiss(); if (!stop.get()) builder().setMessage(L.f("No se pudo descargar: %s", "Could not download: %s", String.valueOf(e.getMessage()))).setPositiveButton("OK", null).show(); }); }
        });
    }
    /** the Civitai card of the style LoRA (model 2564225, version 2998634) next to the file: Civitai shows it as downloaded, the library shows its name and trigger */
    static void gptMeta(File f) {
        File mf = Civitai.metaFile(f); if (mf.isFile()) return;
        try (java.io.FileWriter w = new java.io.FileWriter(mf)) {
            w.write(new JSONObject().put("name", "Style > gpt-image-2").put("version", "Anima-base1 v1.1").put("model_id", 2564225L).put("version_id", 2998634L).put("type", "LORA").put("base", "Anima")
                .put("words", new org.json.JSONArray().put(GPT_TRIGGER)).put("tags", new org.json.JSONArray().put("style")).toString());
        } catch (Exception ignored) { }
    }
    void gptApply() {
        List<String> cur = new ArrayList<>(); cur.add(GPT_TRIGGER); for (String t : tokens(prompt.getText().toString())) if (!t.equalsIgnoreCase(GPT_TRIGGER)) cur.add(t);
        cur.add("<lora:" + GPT_LORA + ":0.8>");
        prompt.setText(android.text.TextUtils.join(", ", cur)); prompt.setSelection(prompt.getText().length());
        Toast.makeText(this, L.s("Estilo ChatGPT añadido al prompt (fuerza 0.8). Tócalo otra vez para quitarlo.", "ChatGPT style added to the prompt (strength 0.8). Tap it again to remove it."), Toast.LENGTH_LONG).show();
    }
    /** convert loras_src/<id>.safetensors into the overlay loras/<id>/ on the phone, with a progress dialog; select it when done */
    void foldQwen(String id, float strength, String name, String triggers, boolean select) {
        if (busy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        qwenFolding = true;
        final ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(32);
        LinearLayout pv = new LinearLayout(this); pv.setPadding(dp(20), dp(12), dp(20), 0); pv.addView(pb, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog dlg = builder().setTitle(L.f("Convirtiendo LoRA de Qwen · %.2f", "Converting the Qwen LoRA · %.2f", strength)).setView(pv).setCancelable(false).show();
        ex2.submit(() -> {
            String err;
            try { err = Qwen.fold(BIN, getFilesDir(), id, strength, name, triggers, (d, t) -> ui.post(() -> pb.setProgress(d))); } catch (Exception e) { err = e.getMessage(); }
            final String fe = err;
            ui.post(() -> {
                dlg.dismiss(); qwenFolding = false;
                if (fe != null) { builder().setTitle(L.s("LoRA de Qwen", "Qwen LoRA")).setMessage(fe).setPositiveButton("OK", null).show(); refreshQwenLora(); return; }
                if (select) prefs().edit().putString("qwen_lora_dir", id).apply();
                refreshQwenLora(); appendPromptWords(triggers);
                Toast.makeText(this, L.f("LoRA de Qwen lista (intensidad %.2f)", "Qwen LoRA ready (strength %.2f)", strength), Toast.LENGTH_SHORT).show();
            });
        });
    }
    void refreshQwenLora() {
        if (qwenLoraName == null) return;
        String name = prefs().getString("qwen_lora_dir", "");
        File selected = Qwen.selectedOverlay(getFilesDir(), name);
        if (selected == null && !name.isEmpty()) { prefs().edit().remove("qwen_lora_dir").apply(); name = ""; }
        qwenLoraName.setText(name.isEmpty() ? L.s("Ninguna", "None") : qwenOverlayTitle(selected));
        if (qwenFold != null) { qwenFold.sum.setText(name.isEmpty() ? L.s("Ninguna", "None") : qwenOverlayTitle(selected) + (Qwen.hasSource(getFilesDir(), name) ? String.format(java.util.Locale.US, " · %.2f", Qwen.overlayStrength(selected)) : "")); qwenFold.sum.setTextColor(name.isEmpty() ? DIM : ACC); }
        qwenLoraDrop.setEnabled(!name.isEmpty()); qwenLoraDrop.setAlpha(name.isEmpty() ? .45f : 1f);
        boolean src = selected != null && Qwen.hasSource(getFilesDir(), name);
        if (qwenStrRow != null) { qwenStrRow.setVisibility(src ? View.VISIBLE : View.GONE); if (src && !qwenFolding) { float s = Qwen.overlayStrength(selected); qwenStr.setProgress(Math.round(s / 0.05f)); qwenStrVal.setText(String.format(java.util.Locale.US, "%.2f", s)); } }
    }
    /** a Qwen overlay as shown to the user: the name given with Rename, else the adapter name from its manifest */
    String qwenOverlayTitle(File ov) { String a = alias(true, "qwen/" + ov.getName()); return a.isEmpty() ? Qwen.overlayName(ov) : a; }
}
