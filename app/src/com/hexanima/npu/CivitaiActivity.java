package com.hexanima.npu;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Civitai browser: Anima models / LoRAs and compatible MOSS A/B LoRAs; search by name or paste a civitai.com / civitai.red link or id, filter by family, type, sort and +18,
 *  open a model, pick a version and download it into the app's libraries (see Civitai). +18 content and "login required" files need the user's own
 *  Civitai API key, entered in Settings; without a key +18 results are not requested at all. */
public class CivitaiActivity extends Base {
    static final String[] TYPES = {"LORA", "Checkpoint"}, SORTS = {"Most Downloaded", "Highest Rated", "Newest"};
    EditText query; LinearLayout results; TextView status; Button more;
    String type = "LORA", base = Civitai.BASE, sort = "Most Downloaded", cursor = null, tag = ""; boolean nsfw = false;
    final ExecutorService net = Executors.newFixedThreadPool(3);
    final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    final AtomicBoolean cancel = new AtomicBoolean();
    LinearLayout familyRow, typeRow, sortRow;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        nsfw = getSharedPreferences("anima", 0).getBoolean("civitai_nsfw", false) && !Civitai.key(this).isEmpty();
        if ("Checkpoint".equals(getIntent().getStringExtra("type"))) type = "Checkpoint";   // opened from the model picker
        LinearLayout col = screen("Civitai", null);
        LinearLayout sr = new LinearLayout(this); sr.setGravity(Gravity.CENTER_VERTICAL);
        query = field(L.s("Название или ссылка civitai.com / civitai.red", "Name or a civitai.com / civitai.red link"));
        query.setSingleLine(true); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH); query.setBackground(bg(CARD, 14));
        query.setOnEditorActionListener((v, a, e) -> { search(true); return true; });
        sr.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        Button go = button("🔍", ACC, ON_ACC); go.setOnClickListener(v -> search(true));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(52), dp(46)); gl.leftMargin = dp(8); sr.addView(go, gl);
        col.addView(sr);
        familyRow = chips(col, L.s("FAMILIA", "FAMILY"), new String[]{"Anima", "MOSS-Image", "Qwen 2.1"}, new String[]{Civitai.BASE, Civitai.MOSS_BASE, Civitai.QWEN_BASE}, base, v -> { base = v; search(true); });
        String[] catNames = new String[Civitai.CATS.length + 1], catVals = new String[Civitai.CATS.length + 1]; catNames[0] = L.s("Todas", "All"); catVals[0] = "";
        for (int i = 0; i < Civitai.CATS.length; i++) { catNames[i + 1] = Civitai.catName(Civitai.CATS[i]); catVals[i + 1] = Civitai.CATS[i]; }
        chips(col, L.s("CATEGORÍA", "CATEGORY"), catNames, catVals, tag, v -> { tag = v; search(true); });
        typeRow = chips(col, L.s("ТИП", "TYPE"), new String[]{"LoRA", L.s("Модели", "Checkpoints")}, TYPES, type, v -> { type = v; search(true); });
        sortRow = chips(col, L.s("СОРТИРОВКА", "SORT"), new String[]{L.s("Популярные", "Most downloaded"), L.s("Лучшие", "Highest rated"), L.s("Новые", "Newest")}, SORTS, sort, v -> { sort = v; search(true); });
        LinearLayout nr = new LinearLayout(this); nr.setGravity(Gravity.CENTER_VERTICAL); nr.setPadding(dp(2), dp(10), 0, dp(4));
        TextView nl = text(L.s("Показывать +18", "Show +18"), 15, TXT, true); nr.addView(nl, new LinearLayout.LayoutParams(0, -2, 1));
        Switch ns = new Switch(this); ns.setChecked(nsfw); nr.addView(ns);
        ns.setOnCheckedChangeListener((v, on) -> {
            if (on && Civitai.key(this).isEmpty()) { v.setChecked(false); askKey(); return; }
            nsfw = on; getSharedPreferences("anima", 0).edit().putBoolean("civitai_nsfw", on).apply(); search(true);
        });
        col.addView(nr);
        status = text("", 13, DIM, false); status.setPadding(dp(2), dp(6), 0, dp(6)); col.addView(status);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); col.addView(results);
        more = button(L.s("Ещё", "More"), CARD, ACC); more.setVisibility(View.GONE); more.setOnClickListener(v -> search(false));
        col.addView(more, new LinearLayout.LayoutParams(-1, dp(46)));
        String link = getIntent().getStringExtra("q"); if (link != null) query.setText(link);
        search(true);
    }

    @Override protected void onDestroy() { super.onDestroy(); cancel.set(true); net.shutdownNow(); }

    interface OnPick { void on(String v); }
    LinearLayout chips(LinearLayout col, String title, String[] names, String[] values, String cur, OnPick cb) {
        col.addView(label(title));
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < names.length; i++) {
            final String v = values[i];
            Button c = button(names[i], v.equals(cur) ? ACC : CARD, v.equals(cur) ? ON_ACC : TXT); c.setTag(v); c.setTextSize(13);
            c.setOnClickListener(x -> { for (int k = 0; k < row.getChildCount(); k++) { Button o = (Button) row.getChildAt(k); boolean on = o.getTag().equals(v); o.setBackground(bg(on ? ACC : CARD, 14)); o.setTextColor(on ? ON_ACC : TXT); } cb.on(v); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38)); if (i > 0) lp.leftMargin = dp(6); row.addView(c, lp);
        }
        hs.addView(row); col.addView(hs);
        return row;
    }

    void askKey() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(16), dp(20), dp(8));
        v.addView(text(L.s("Ключ API Civitai", "Civitai API key"), 18, TXT, true));
        TextView t = text(L.s("Нужен для +18 и файлов «только после входа». Создайте его на civitai.com → аккаунт → API Keys и вставьте сюда. Ключ хранится только на телефоне и отправляется только на civitai.com.",
                "Needed for +18 and \"login required\" files. Create it on civitai.com → Account settings → API Keys and paste it here. It stays on this phone and is only sent to civitai.com."), 13, DIM, false);
        t.setPadding(0, dp(8), 0, dp(10)); v.addView(t);
        EditText k = field("…"); k.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); k.setText(Civitai.key(this)); v.addView(k);
        Button open = button(L.s("Открыть civitai.red/user/account", "Open civitai.red/user/account"), FIELD, ACC); open.setTextSize(13);
        open.setOnClickListener(x -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://civitai.red/user/account"))));
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, dp(40)); ol.topMargin = dp(8); v.addView(open, ol);
        builder().setView(v).setPositiveButton(L.s("Сохранить", "Save"), (d, i) -> { getSharedPreferences("anima", 0).edit().putString("civitai_key", k.getText().toString().trim()).apply(); })
            .setNegativeButton(L.s("Отмена", "Cancel"), null).show();
    }

    void search(boolean fresh) {
        if (fresh) { cursor = null; results.removeAllViews(); }
        final String q = query.getText().toString().trim(); final long[] link = Civitai.parseLink(q);
        status.setText(L.s("Ищу…", "Searching…")); more.setVisibility(View.GONE);
        final String fType = type, fBase = base, fSort = sort, fCursor = cursor, fTag = tag; final boolean fNsfw = nsfw;
        net.submit(() -> {
            try {
                List<JSONObject> items = new ArrayList<>(); String next = null;
                if (link != null) items.add(Civitai.model(this, link[0]));
                else {
                    JSONObject r = Civitai.search(this, q, fType, fBase, fSort, fNsfw, fCursor, fTag);
                    JSONArray a = r.optJSONArray("items"); if (a != null) for (int i = 0; i < a.length(); i++) items.add(a.getJSONObject(i));
                    JSONObject md = r.optJSONObject("metadata"); if (md != null && md.has("nextCursor")) next = md.optString("nextCursor", null);
                }
                final String nx = next;
                ui.post(() -> {
                    if (isFinishing()) return;
                    cursor = nx; more.setVisibility(nx != null && link == null ? View.VISIBLE : View.GONE);
                    for (JSONObject m : items) results.addView(card(m, link != null ? link[1] : 0));
                    status.setText(results.getChildCount() == 0 ? L.s("Ничего не найдено", "Nothing found") : L.f("Найдено: %d", "Found: %d", results.getChildCount()));
                    if (link != null && !items.isEmpty()) open(items.get(0), link[1]);
                });
            } catch (Exception e) { ui.post(() -> status.setText(L.f("Ошибка: %s", "Error: %s", e.getMessage()))); }
        });
    }

    View card(JSONObject m, long wantVersion) {
        LinearLayout c = new LinearLayout(this); c.setBackground(bg(CARD, 18)); c.setPadding(dp(10), dp(10), dp(12), dp(10)); c.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackground(bg(FIELD, 12)); iv.setClipToOutline(true);
        c.addView(iv, new LinearLayout.LayoutParams(dp(84), dp(112)));
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(12), 0, 0, 0);
        JSONArray vs = m.optJSONArray("modelVersions"); JSONObject v0 = vs != null && vs.length() > 0 ? vs.optJSONObject(0) : new JSONObject();
        TextView name = text(m.optString("name"), 15, TXT, true); name.setMaxLines(2); t.addView(name);
        JSONObject cr = m.optJSONObject("creator"); JSONObject st = m.optJSONObject("stats");
        t.addView(text(m.optString("type") + " · " + v0.optString("baseModel") + (m.optBoolean("nsfw") ? " · 18+" : ""), 12, Civitai.anima(v0.optString("baseModel")) ? ACC : DIM, false));
        t.addView(text((cr != null ? cr.optString("username") : "") + (st != null ? "  ·  ⬇ " + st.optInt("downloadCount") : ""), 12, DIM, false));
        JSONArray w = v0.optJSONArray("trainedWords");
        if (w != null && w.length() > 0) { TextView tw = text(L.s("Слова: ", "Trigger: ") + w.optString(0) + (w.length() > 1 ? " …" : ""), 12, DIM, false); tw.setMaxLines(1); t.addView(tw); }
        c.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        c.setOnClickListener(x -> open(m, wantVersion));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8); c.setLayoutParams(lp);
        final String pu = Civitai.preview(v0); final boolean hide = !nsfw && Civitai.nsfwLevel(v0) > 1;
        if (!hide) net.submit(() -> { Bitmap bm = Civitai.thumb(pu, 256); if (bm != null) ui.post(() -> iv.setImageBitmap(bm)); });
        return c;
    }

    /** model sheet: versions, files, trigger words, download */
    void open(JSONObject m, long wantVersion) {
        JSONArray vs = m.optJSONArray("modelVersions"); if (vs == null || vs.length() == 0) return;
        int sel = 0; for (int i = 0; i < vs.length(); i++) if (vs.optJSONObject(i).optLong("id") == wantVersion) sel = i;
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(16), dp(20), dp(8));
        v.addView(text(m.optString("name"), 18, TXT, true));
        TextView info = text("", 13, DIM, false); info.setPadding(0, dp(8), 0, dp(8)); v.addView(info);
        String[] names = new String[vs.length()];
        for (int i = 0; i < vs.length(); i++) { JSONObject x = vs.optJSONObject(i); names[i] = x.optString("name") + "  ·  " + x.optString("baseModel"); }
        final int[] cur = {sel};
        Button pick = button(names[sel] + "  ▾", FIELD, TXT); pick.setTextSize(13);
        v.addView(pick, new LinearLayout.LayoutParams(-1, dp(42)));
        Runnable show = () -> {
            JSONObject x = vs.optJSONObject(cur[0]); JSONObject f = Civitai.mainFile(x);
            JSONArray w = x.optJSONArray("trainedWords"); List<String> ws = new ArrayList<>(); if (w != null) for (int i = 0; i < w.length(); i++) ws.add(w.optString(i));
            info.setText(m.optString("type") + " · " + x.optString("baseModel") + (f != null ? String.format(Locale.US, " · %.0f MB", f.optDouble("sizeKB") / 1024) : "")
                + (ws.isEmpty() ? "" : "\n" + L.s("Слова-активаторы: ", "Trigger words: ") + String.join(", ", ws)));
            pick.setText(names[cur[0]] + "  ▾");
        };
        show.run();
        pick.setOnClickListener(x -> builder().setSingleChoiceItems(names, cur[0], (d, i) -> { d.dismiss(); cur[0] = i; show.run(); }).show());
        Button web = button(L.s("Открыть на сайте", "Open on the website"), FIELD, ACC); web.setTextSize(13);
        web.setOnClickListener(x -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://civitai.red/models/" + m.optLong("id") + "?modelVersionId=" + vs.optJSONObject(cur[0]).optLong("id")))));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, dp(40)); wl.topMargin = dp(8); v.addView(web, wl);
        builder().setView(v).setPositiveButton(L.s("Скачать", "Download"), (d, i) -> download(m, vs.optJSONObject(cur[0]))).setNegativeButton(L.s("Закрыть", "Close"), null).show();
    }

    void download(JSONObject m, JSONObject ver) {
        JSONObject f = Civitai.mainFile(ver);
        if (f == null) { Toast.makeText(this, L.s("У версии нет файла", "This version has no file"), Toast.LENGTH_LONG).show(); return; }
        final boolean moss = Civitai.moss(ver.optString("baseModel"));
        final boolean qwen = Civitai.qwen(ver.optString("baseModel"));
        long fileBytes = (long) (f.optDouble("sizeKB") * 1024);
        if (moss && fileBytes > (1L << 30)) { builder().setMessage(L.s("Este LoRA supera 1 GB; el importador MOSS no lo admite.", "This LoRA is larger than 1 GB; the MOSS importer does not support it.")).setPositiveButton("OK", null).show(); return; }
        if (moss && getFilesDir().getUsableSpace() < fileBytes * 2 + 300_000_000L) { builder().setMessage(L.f("Falta espacio para descargar y seleccionar este LoRA: se necesitan unos %s GB libres.", "Not enough free space to download and select this LoRA: about %s GB is needed.", String.format(Locale.US, "%.1f", fileBytes * 2.0 / 1e9))).setPositiveButton("OK", null).show(); return; }
        if (qwen && getFilesDir().getUsableSpace() < fileBytes * 2 + 300_000_000L) { builder().setMessage(L.f("Falta espacio para descargar y exportar esta LoRA: se necesitan unos %s GB libres.", "Not enough free space to download and export this LoRA: about %s GB is needed.", String.format(Locale.US, "%.1f", fileBytes * 2.0 / 1e9))).setPositiveButton("OK", null).show(); return; }
        String[] why = {""};
        String fileName = f.optString("name");
        if (qwen) fileName = "civitai_" + m.optLong("id") + "_" + ver.optLong("id") + "_" + fileName;
        File out = Civitai.target(getFilesDir(), m.optString("type"), ver.optString("baseModel"), fileName, why);
        if (out == null) { builder().setMessage(why[0]).setPositiveButton("OK", null).show(); return; }
        if (m.optBoolean("nsfw") && Civitai.key(this).isEmpty()) { askKey(); return; }
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(16), dp(20), dp(8));
        v.addView(text(f.optString("name"), 14, TXT, true));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000); v.addView(pb);
        TextView pt = text("", 12, DIM, false); v.addView(pt);
        AtomicBoolean stop = new AtomicBoolean();
        android.app.AlertDialog dl = builder().setTitle(L.s("Загрузка", "Downloading")).setView(v).setCancelable(false).setNegativeButton(L.s("Отмена", "Cancel"), (d, i) -> stop.set(true)).show();
        net.submit(() -> {
            try {
                Civitai.download(this, f.optString("downloadUrl"), out, (long) (f.optDouble("sizeKB") * 1024), (d, t) -> ui.post(() -> { if (t > 0) pb.setProgress((int) (1000 * d / t)); pt.setText(String.format(Locale.US, "%.0f / %.0f MB", d / 1048576.0, t / 1048576.0)); }), stop);
                String msg;
                if (out.getParentFile().equals(AnimaImport.importDir(getFilesDir()))) {   // Anima checkpoint: convert it into an installed model on the phone
                    String id = "cv" + ver.optLong("id"), nm = m.optString("name") + " · " + ver.optString("name");
                    ui.post(() -> { pt.setText(L.s("Конвертация для NPU…", "Converting for the NPU…")); pb.setProgress(0); });
                    String err = AnimaImport.convert(getFilesDir(), getApplicationInfo().nativeLibraryDir, Main.DEFAULT_BASE, out, id, nm,
                        (d, t, what) -> ui.post(() -> { pb.setProgress(1000 * d / Math.max(1, t)); pt.setText(what + " " + d + "/" + t); }), stop);
                    if (err != null) throw new Exception(err);
                    ui.post(() -> { dl.dismiss(); builder().setTitle(nm).setMessage("✓ " + L.s("Модель установлена: выберите её в списке моделей. Если это не «turbo»-модель, используйте 20–30 шагов и CFG 4–5 (или LoRA turbo для 2B).",
                        "Model installed: pick it in the model list. If it is not a \"turbo\" model, use 20–30 steps and CFG 4–5 (or a turbo LoRA on the 2B ones).")).setPositiveButton("OK", null).show(); });
                    return;
                }
                if (out.getParentFile().equals(AnimaLora.dir(getFilesDir()))) {
                    AnimaLora.Check c = AnimaLora.check(out);
                    if (!c.ok) { out.delete(); throw new Exception(c.message()); }
                    msg = c.message();
                } else if (moss && out.getParentFile().equals(MossLora.civitaiDir(getFilesDir()))) {
                    MossLora.Check c = MossLora.check(getApplicationInfo().nativeLibraryDir, out);
                    if (!c.ok) throw new Exception(c.message()); // keep the downloaded file in the Civitai library for inspection
                    Civitai.writeMeta(out, m, ver);
                    MossLora.select(getFilesDir(), out);
                    String words = Civitai.words(out);
                    String info = "✓ " + c.message() + (words.isEmpty() ? "" : "\n" + L.s("Activadores: ", "Triggers: ") + words);
                    getSharedPreferences("anima", 0).edit().putString("moss_lora_id", "civitai-" + ver.optLong("id"))
                        .putString("moss_lora_name", m.optString("name")).putString("moss_lora_info", info).putString("moss_lora_file", out.getName()).apply();
                    msg = L.s("LoRA compatible; seleccionada para MOSS.", "Compatible LoRA; selected for MOSS.") + "\n" + c.message();
                    if (!words.isEmpty()) msg += "\n" + L.s("Activadores: ", "Triggers: ") + words;
                } else if (qwen && out.getParentFile().equals(Civitai.qwenRawDir(getFilesDir()))) {
                    Civitai.writeMeta(out, m, ver);
                    String words = Civitai.words(out);
                    String path = Civitai.exportQwenToDownloads(this, out);
                    String metaPath = Civitai.exportQwenToDownloads(this, Civitai.metaFile(out));
                    out.delete(); Civitai.metaFile(out).delete();
                    msg = L.f("Safetensors original y metadatos guardados en %s y %s. Transfiere ambos al PC y ejecuta qwen/convert_civitai_lora.sh; después importa el ZIP V73 desde el selector Qwen.",
                        "Original Safetensors and metadata saved to %s and %s. Transfer both to the PC and run qwen/convert_civitai_lora.sh, then import its V73 ZIP from the Qwen picker.", path, metaPath);
                    if (!words.isEmpty()) msg += "\n" + L.s("Activadores para el conversor", "Trigger words for the converter") + ": " + words;
                } else msg = "";
                if (!qwen) Civitai.writeMeta(out, m, ver);
                String tag = out.getName().replaceAll("\\.safetensors$", "");
                final String done = qwen || moss ? "✓ " + msg : "✓ " + msg + "\n" + L.f("В промпте: <lora:%s:1.0>", "In the prompt: <lora:%s:1.0>", tag) + (Civitai.words(out).isEmpty() ? "" : "\n" + L.s("Слова-активаторы: ", "Trigger words: ") + Civitai.words(out));
                ui.post(() -> {
                    if (moss) setResult(RESULT_OK, new Intent().putExtra("moss_triggers", Civitai.words(out)));
                    dl.dismiss(); builder().setTitle(m.optString("name")).setMessage(m.optString("type").equals("Checkpoint") ? "✓ " + out.getName() : done).setPositiveButton("OK", null).show();
                });
            } catch (Exception e) { ui.post(() -> { dl.dismiss(); if (!stop.get()) builder().setTitle(L.s("Не удалось скачать", "Download failed")).setMessage(e.getMessage()).setPositiveButton("OK", null).show(); }); }
        });
    }
}
