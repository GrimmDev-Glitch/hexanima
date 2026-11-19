package com.hexanima.npu;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Model catalog: a card per model (size, speed at the base resolution, installed state) with download / delete. The download itself runs on a thread with a foreground service,
 *  its progress lives in statics so that leaving this screen does not lose it. The shared files (text encoder, VAE) come with the first model. */
public class ModelsActivity extends Base {
    static final int REQ_IMPORT = 41, REQ_EXPORT = 42;
    static volatile boolean downloading;
    static volatile String dlId = "", dlMsg = "";
    static volatile long dlDone, dlTotal;
    static final AtomicBoolean cancel = new AtomicBoolean();
    static volatile String deleted = "";   // last deleted model id: Main unloads the server if it holds it

    Models store;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    LinearLayout list;
    TextView info;
    EditText url;
    List<Models.Item> items = new ArrayList<>();
    Models.Item pendingExport;
    final java.util.Map<String, ProgressBar> bars = new java.util.HashMap<>();
    final java.util.Map<String, TextView> msgs = new java.util.HashMap<>();
    boolean wasDownloading, ioBusy;
    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (downloading) {
                ProgressBar pb = bars.get(dlId); TextView tv = msgs.get(dlId);
                if (pb != null && dlTotal > 0) pb.setProgress((int) (1000 * dlDone / dlTotal));
                if (tv != null) tv.setText(dlMsg);
            } else if (wasDownloading) { wasDownloading = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); render(); }
            if (downloading) wasDownloading = true;
            ui.postDelayed(this, 400);
        }
    };

    String prefBase() { return getSharedPreferences("anima", 0).getString("base", Main.DEFAULT_BASE); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Models(getFilesDir());
        Button src = button(L.s("Источник", "Source"), CARD, DIM); src.setTextSize(12);
        LinearLayout col = screen(L.s("Модели", "Models"), src);
        info = text("", 12, DIM, false); info.setPadding(dp(2), 0, 0, dp(10));
        col.addView(info);
        url = field(L.s("Адрес каталога (…/resolve/main/)", "Catalog URL (…/resolve/main/)")); url.setText(prefBase()); url.setTextSize(12); url.setBackground(bg(CARD, 14)); url.setVisibility(View.GONE);
        col.addView(url);
        Button imp = button(L.s("Importar modelo desde carpeta…", "Import model from folder…"), CARD, TXT); imp.setTextSize(13);
        imp.setOnClickListener(v -> { if (!downloading && !ioBusy) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_IMPORT); });
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, dp(42)); il.bottomMargin = dp(10); col.addView(imp, il);
        src.setOnClickListener(v -> { boolean show = url.getVisibility() != View.VISIBLE; url.setVisibility(show ? View.VISIBLE : View.GONE); if (!show) load(); });
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        String soc = android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MODEL : "?";
        boolean ok = Compat.chipOk();
        info.setText((ok ? L.f("Чип %s · подходит (%s)", "Chip %s · supported (%s)", soc, Compat.archLabel()) : L.f("Чип %s · не поддерживается", "Chip %s · not supported", soc))
            + "\n" + L.s("Проверено на Snapdragon 8 Gen 1 / 8+ Gen 1 и 8 Gen 2 (Poco F6 Pro)", "Tested on Snapdragon 8 Gen 1 / 8+ Gen 1 and 8 Gen 2 (Poco F6 Pro)")
            + "\n" + L.f("Свободно %.1f ГБ", "Free %.1f GB", getFilesDir().getUsableSpace() / 1e9));
        if (downloading) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        load();
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }
    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_IMPORT) runModelIO(false, data.getData(), null);
        else if (req == REQ_EXPORT && pendingExport != null) { Models.Item it = pendingExport; pendingExport = null; runModelIO(true, data.getData(), it); }
    }

    void runModelIO(boolean exporting, android.net.Uri tree, Models.Item item) {
        if (downloading || ioBusy) return;
        ioBusy = true;
        AtomicBoolean stop = new AtomicBoolean();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACC)); box.addView(bar);
        TextView line = text(L.s("Preparando…", "Preparing…"), 12, DIM, false); line.setPadding(0, dp(6), 0, 0); box.addView(line);
        AlertDialog dlg = builder().setTitle(exporting ? L.f("Exportar: %s", "Export: %s", item.name) : L.s("Importar modelo", "Import model"))
            .setView(box).setCancelable(false).setNegativeButton(L.s("Cancelar", "Cancel"), null).show();
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { stop.set(true); line.setText(L.s("Cancelando…", "Cancelling…")); dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setEnabled(false); });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        io.submit(() -> {
            String error = null; String[] imported = null;
            Models.Progress progress = (done, total, path) -> ui.post(() -> {
                if (total > 0) bar.setProgress((int) Math.min(1000, done * 1000 / total));
                line.setText(L.f("%s / %s GB · %s", "%s / %s GB · %s", gb(done), gb(total), path));
            });
            try {
                if (exporting) ModelIO.export(this, store, item.id, item.name, tree, progress, stop);
                else imported = ModelIO.importFrom(this, store, tree, progress, stop);
                if (stop.get()) throw new java.io.InterruptedIOException();
            } catch (java.io.InterruptedIOException e) { error = L.s("Operación cancelada", "Operation cancelled"); }
            catch (Exception e) { error = L.f("Error: %s", "Error: %s", String.valueOf(e.getMessage())); }
            final String msg = error; final String[] model = imported;
            ui.post(() -> {
                dlg.dismiss(); ioBusy = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (model != null) {
                    boolean found = false; for (Models.Item existing : items) if (existing.id.equals(model[0])) { found = true; break; }
                    if (!found) { Models.Item added = new Models.Item(); added.id = model[0]; added.name = model[1]; added.desc = L.s("Importado desde una carpeta", "Imported from a folder"); added.descEn = added.desc; items.add(0, added); }
                    Toast.makeText(this, L.f("Modelo importado: %s", "Imported model: %s", model[1]), Toast.LENGTH_LONG).show(); render();
                } else if (msg == null) Toast.makeText(this, L.s("Modelo exportado a la carpeta elegida", "Model exported to the chosen folder"), Toast.LENGTH_LONG).show();
                else Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        });
    }

    void load() {
        list.removeAllViews();
        list.addView(text(L.s("Загружаю каталог…", "Loading catalog…"), 13, DIM, false));
        final String base = url.getText().toString().trim().replaceAll("/*$", "") + "/";
        getSharedPreferences("anima", 0).edit().putString("base", base).apply();
        io.submit(() -> {
            try { final List<Models.Item> cat = store.catalog(this, base); final boolean off = store.offline; Models.ensureMage(cat); for (Models.Item it : cat) store.refreshMeta(it); ui.post(() -> { items = cat; render(); if (off) Toast.makeText(this, L.s("Sin internet: catálogo guardado. Puedes borrar modelos, pero no descargar.", "Offline: showing the saved catalog. You can delete models, but cannot download."), Toast.LENGTH_LONG).show(); }); }
            catch (Exception e) { ui.post(() -> { list.removeAllViews(); list.addView(text(L.f("Каталог недоступен: %s", "Catalog unavailable: %s", e.getMessage()), 13, BAD, false)); }); }
        });
    }

    static String gb(long b) { return String.format("%.1f", b / 1e9); }

    void render() {
        list.removeAllViews(); bars.clear(); msgs.clear();
        final String base = prefBase();
        final boolean needCommonAnima = !store.commonInstalled();
        for (final Models.Item it : items) {
            final boolean needCommon = needCommonAnima && !it.moss && !it.qwen && !it.mage && !it.video;
            final boolean have = store.modelInstalled(it.id);
            final boolean busyThis = downloading && it.id.equals(dlId);
            if (Mage.is(it.id) && !Mage.V73_RUNNER_VALIDATED && !have && !busyThis) continue;   // doesn't run on V73 yet: no 8.7 GB download; an installed pack stays listed so it can be deleted
            LinearLayout c = card(list);
            LinearLayout top = new LinearLayout(this); top.setGravity(android.view.Gravity.CENTER_VERTICAL);
            if (have) { TextView ok = text("✓", 18, OK, true); ok.setPadding(0, 0, dp(8), 0); top.addView(ok); }
            top.addView(text(it.name, 17, TXT, true), new LinearLayout.LayoutParams(0, -2, 1));
            if (have && ModelIO.supported(it.id)) {
                TextView ex = text(L.s("Exportar", "Export"), 13, ACC, true); ex.setPadding(dp(10), dp(6), 0, dp(6));
                ex.setAlpha(ioBusy ? .5f : 1f); ex.setOnClickListener(v -> { if (!downloading && !ioBusy) { pendingExport = it; startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_EXPORT); } }); top.addView(ex);
            }
            c.addView(top);
            String meta = (it.params.isEmpty() ? "" : it.params + (it.quant.isEmpty() ? "" : " " + it.quant) + "  ·  ") + (it.video ? L.s("~30 с на клип 512×320", "~30 s per 512×320 clip") + "  ·  " : it.speed > 0 ? L.f("~%d с на 512×768", "~%d s at 512×768", it.speed) + "  ·  " : "") + L.f("%s ГБ", "%s GB", gb(it.size));
            TextView m = text(meta, 13, ACC, false); m.setPadding(0, dp(4), 0, 0); c.addView(m);
            String d = L.s(it.desc, it.descEn);
            if (it.speed >= 60 && !it.moss && !it.qwen && !it.mage && !it.video) {   // the catalog copies "Fast, ..." from the turbo sibling onto the full (base) model
                d = d.replaceFirst("^(Fast|Rápido|Rápida|Быстро|Быстрая|Быстрый),\\s*", "");
                if (!d.isEmpty()) d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
                d = (d.isEmpty() ? "" : d.replaceAll("[.\\s]+$", "") + ". ") + L.s("Полная модель: 20+ шагов, медленнее, но точнее следует промпту.", "Full model: 20+ steps, slower but follows the prompt more closely.");
            }
            if (!d.isEmpty()) { TextView dv = text(d, 13, DIM, false); dv.setPadding(0, dp(4), 0, 0); c.addView(dv); }
            if (!it.author.isEmpty()) { TextView a = text(L.f("Автор: %s", "By %s", it.author), 12, DIM, false); a.setPadding(0, dp(4), 0, 0); c.addView(a); }
            if (it.qwen && !it.runtimeAvailable && !have) c.addView(text(L.s("Los contextos V73 de Qwen todavía no están publicados o no se pudieron consultar.", "The Qwen V73 contexts are not published yet or could not be checked."), 12, WARN, false));
            if (busyThis) {
                ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000);
                pb.setProgressTintList(android.content.res.ColorStateList.valueOf(ACC)); pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(FIELD));
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(5)); pl.topMargin = dp(10); c.addView(pb, pl); bars.put(it.id, pb);
                TextView tv = text(dlMsg, 12, DIM, false); tv.setPadding(0, dp(6), 0, 0); c.addView(tv); msgs.put(it.id, tv);
                Button x = button(L.s("Отменить", "Cancel"), FIELD, TXT); x.setOnClickListener(v -> cancel.set(true));
                LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(-1, dp(42)); xl.topMargin = dp(8); c.addView(x, xl);
            } else {
                String label = have ? L.s("Удалить", "Delete") : store.offline ? L.s("Sin conexión", "Offline") : !it.runtimeAvailable ? L.s("Ожидание контекстов V73", "Waiting for V73 contexts") : L.f("Скачать  %s ГБ", "Download  %s GB", gb(it.size + (needCommon ? store.commonSize : 0)));
                Button bt = button(label, have ? FIELD : ACC, have ? BAD : ON_ACC); bt.setEnabled(!downloading && !ioBusy && (have || (!store.offline && it.runtimeAvailable))); bt.setAlpha(downloading || ioBusy || !have && (store.offline || !it.runtimeAvailable) ? .5f : 1f);
                bt.setOnClickListener(v -> {
                    if (have) confirmDelete(it, () -> { store.delete(it.id); deleted = it.id; render(); });
                    else start(base, it, needCommon);
                });
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(44)); bl.topMargin = dp(10);
                c.addView(bt, bl);
            }
        }
        if (items.isEmpty()) list.addView(text(L.s("Каталог пуст", "The catalog is empty"), 13, DIM, false));
        else if (!store.commonInstalled() && hasAnima()) { TextView n = text(L.f("К первой модели добавятся общие файлы (%s ГБ): текстовый энкодер и VAE. Они качаются один раз.", "The first model also fetches the shared files (%s GB): text encoder and VAE, once.", gb(store.commonSize)), 12, DIM, false); n.setPadding(dp(2), 0, 0, 0); list.addView(n); }
    }

    boolean hasAnima() { for (Models.Item it : items) if (!it.moss && !it.qwen && !it.mage && !it.video) return true; return false; }

    void confirmDelete(Models.Item it, Runnable action) {
        builder().setMessage(L.f("¿Eliminar «%s»? Tendrás que descargarlo otra vez para usarlo.", "Delete “%s”? You will need to download it again to use it.", it.name))
            .setNegativeButton(L.s("Cancelar", "Cancel"), null)
            .setPositiveButton(L.s("Eliminar", "Delete"), (d, w) -> action.run()).show();
    }

    void start(String base, Models.Item it, boolean needCommon) {
        if (!Compat.chipAllowed(this)) {   // an unsupported chip: the models will not run, so ask before 4 GB are downloaded
            builder().setMessage(L.f("Чип %s не поддерживается: модели рассчитаны на Snapdragon 8 Gen 1 / 8+ Gen 1 / 8 Gen 2. Всё равно скачать?", "Chip %s is not supported: the models are built for Snapdragon 8 Gen 1 / 8+ Gen 1 / 8 Gen 2. Download anyway?", Compat.soc()))
                .setPositiveButton(L.s("Скачать", "Download"), (d, w) -> { Compat.allowChip(this); begin(base, it, needCommon); }).setNegativeButton(L.s("Отмена", "Cancel"), null).show();
            return;
        }
        if ((it.moss || it.qwen || it.video) && Compat.ramGb(this) < 10.5) {   // text encoder 6 GB and the DiT stream through RAM: 12 GB phones only
            builder().setMessage(L.f("В телефоне %.1f ГБ ОЗУ: для этой модели рекомендуется 12 ГБ. Всё равно скачать?", "This phone has %.1f GB of RAM: 12 GB is recommended for this model. Download anyway?", Compat.ramGb(this)))
                .setPositiveButton(L.s("Скачать", "Download"), (d, w) -> begin(base, it, needCommon)).setNegativeButton(L.s("Отмена", "Cancel"), null).show();
            return;
        }
        begin(base, it, needCommon);
    }

    void begin(String base, Models.Item it, boolean needCommon) {
        long need = it.size + (needCommon ? store.commonSize : 0);
        if (getFilesDir().getUsableSpace() < need + 300_000_000L) { Toast.makeText(this, L.f("Не хватает места: нужно %s ГБ", "Not enough space: need %s GB", gb(need)), Toast.LENGTH_LONG).show(); return; }
        if (downloading || ioBusy) return;
        downloading = true; dlId = it.id; dlDone = 0; dlTotal = need; dlMsg = L.s("Подключаюсь…", "Connecting…"); cancel.set(false);
        final android.content.Context app = getApplicationContext();
        app.startForegroundService(new Intent(app, DownloadService.class));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        render();
        final long[] last = {0};
        new Thread(() -> {
            Models.Progress pg = (d, tot, what) -> {
                long now = System.currentTimeMillis(); if (now - last[0] < 300) return; last[0] = now;
                dlDone = d; dlTotal = tot; dlMsg = L.f("%s / %s ГБ  ·  %s", "%s / %s GB  ·  %s", gb(d), gb(tot), what);
            };
            String err = null;
            try { if (!it.moss && !it.qwen && !it.video) store.installCommon(base, pg, cancel); store.installModel(base, it, pg, cancel); }
            catch (java.io.InterruptedIOException e) { err = L.s("Загрузка отменена: файлы сохранены, можно продолжить", "Cancelled: the files are kept, you can resume"); }
            catch (Exception e) { err = L.f("Ошибка: %s", "Error: %s", e.getMessage()); }
            final String msg = err;
            downloading = false;
            app.stopService(new Intent(app, DownloadService.class));
            if (msg != null) ui.post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
        }).start();
    }
}
