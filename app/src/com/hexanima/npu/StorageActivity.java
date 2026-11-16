package com.hexanima.npu;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Storage manager: what the app keeps on the phone, by model, cache and pictures, with a button per item.
 *  Caches rebuild themselves when needed (LoRA q8 / text caches, Mage text cache, edit inputs, temporary files), so they can be cleared at any time;
 *  models are managed in Models (the hidden LTX video model, which Models no longer lists, can be deleted here); the trash and the before/after
 *  originals are pictures and ask first. */
public class StorageActivity extends Base {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout col;
    private volatile boolean busy;

    static final class Item {
        final String title, sub; final File[] files; final int kind;   // kind: 0 info only, 1 cache (clear), 2 model (manage in Models), 3 hidden video model (delete), 4 pictures (ask, then clear)
        long bytes;
        Item(String title, String sub, int kind, File... files) { this.title = title; this.sub = sub; this.kind = kind; this.files = files; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        col = screen(L.s("Almacenamiento", "Storage"), null);
        refresh();
    }

    private void refresh() {
        col.removeAllViews();
        TextView wait = text(L.s("Calculando…", "Calculating…"), 14, DIM, false); wait.setPadding(dp(4), dp(12), dp(4), dp(12)); col.addView(wait);
        final List<Item> models = new ArrayList<>(), caches = new ArrayList<>(), pics = new ArrayList<>();
        build(models, caches, pics);   // on the UI thread: the translated texts are looked up here; only the sizes are measured in the background
        new Thread(() -> {
            for (List<Item> l : java.util.Arrays.asList(models, caches, pics)) for (Item it : l) for (File f : it.files) it.bytes += size(f);
            final long free = getFilesDir().getUsableSpace(), total = getFilesDir().getTotalSpace();
            ui.post(() -> show(models, caches, pics, free, total));
        }).start();
    }

    private void build(List<Item> models, List<Item> caches, List<Item> pics) {
        final File home = getFilesDir(), anima = new File(home, "anima");
        File[] ms = anima.listFiles(f -> f.isDirectory() && f.getName().startsWith("m_"));
        if (ms != null) for (File d : ms) models.add(new Item(metaName(d, d.getName().substring(2)), "Anima", 2, d));
        List<File> common = new ArrayList<>();
        File[] af = anima.listFiles(f -> f.getName().startsWith("te") && !f.getName().equals("tecache") || f.getName().equals("vae.bin") || f.getName().startsWith("common") || f.getName().startsWith("dsp"));
        if (af != null) for (File f : af) common.add(f);
        if (!common.isEmpty()) models.add(new Item(L.s("Archivos comunes de Anima", "Anima shared files"), L.s("Codificador de texto y VAE que usan todos los modelos Anima", "Text encoder and VAE used by every Anima model"), 0, common.toArray(new File[0])));
        File moss = Moss.dir(home), qwen = Qwen.dir(home), mage = Mage.dir(home), ltx = Ltx.dir(home);
        if (moss.isDirectory()) models.add(new Item(metaName(moss, "MOSS-Image"), "MOSS-Image", 2, moss));
        if (Qwen.installed(home)) models.add(new Item(metaName(qwen, "Qwen-Image"), "Qwen-Image", 2, filesExcept(qwen, "before", "edit", "loras", "loras_src", "civitai", "civitai_raw", "edit_ref", "edit_src.png", "edit_mask.png")));
        if (Mage.installed(home)) models.add(new Item(metaName(mage, "Mage-Flow"), "Mage-Flow", 2, filesExcept(mage, "cache", "edit")));
        if (ltx.isDirectory()) models.add(new Item(L.s("Vídeo LTX (oculto)", "LTX video (hidden)"), L.s("El modelo de vídeo ya no aparece en Modelos; bórralo aquí si no lo usas", "The video model no longer shows in Models; delete it here if you do not use it"), 3, ltx));

        caches.add(new Item(L.s("Caché de LoRAs de Anima", "Anima LoRA cache"), L.s("Se rehace la próxima vez que uses cada LoRA (unos segundos más)", "Rebuilt the next time each LoRA is used (a few seconds more)"), 1, new File(anima, "lcache"), new File(anima, "tecache")));
        File[] ovl = moss.listFiles(f -> f.getName().startsWith("ovl_"));
        if (ovl != null && ovl.length > 0) caches.add(new Item(L.s("Caché de LoRAs de MOSS", "MOSS LoRA cache"), L.s("Se rehace al volver a elegir la LoRA (unos 20 s)", "Rebuilt when the LoRA is picked again (about 20 s)"), 1, ovl));
        caches.add(new Item(L.s("Caché de Mage", "Mage cache"), L.s("Textos ya codificados y datos de la última edición", "Already-encoded prompts and the last edit's inputs"), 1, new File(mage, "cache"), new File(mage, "edit")));
        caches.add(new Item(L.s("Datos de la última edición de Qwen", "Last Qwen edit inputs"), L.s("Se vuelven a preparar al editar", "Prepared again when editing"), 1, new File(qwen, "edit")));
        caches.add(new Item(L.s("Archivos temporales", "Temporary files"), L.s("Ampliar, correcciones y trabajo del vídeo", "Enlarge, corrections and video work files"), 1, getCacheDir(), new File(ltx, "work")));

        File gal = Gallery.dir(this);
        pics.add(new Item(L.s("Historial", "History"), L.s("Tus imágenes; se gestionan en el Historial", "Your pictures; managed in the History"), 0, filesExcept(gal, "trash")));
        pics.add(new Item(L.s("Papelera", "Trash"), L.s("Imágenes borradas; se vacía sola a los 30 días", "Deleted pictures; it empties itself after 30 days"), 4, Gallery.trashDir(this)));
        pics.add(new Item(L.s("Originales de antes/después", "Before/after originals"), L.s("La foto de partida de cada edición; sin ella el botón ⇆ desaparece", "The starting photo of each edit; without it the ⇆ button goes away"), 4, new File(qwen, "before")));
    }

    private void show(List<Item> models, List<Item> caches, List<Item> pics, long free, long total) {
        col.removeAllViews();
        LinearLayout top = card(col);
        TextView ft = text(L.f("Libre: %s de %s", "Free: %s of %s", gb(free), gb(total)), 16, TXT, true); ft.setPadding(dp(2), dp(6), dp(2), dp(6)); top.addView(ft);
        section(L.s("MODELOS", "MODELS"), models);
        section(L.s("CACHÉS", "CACHES"), caches);
        section(L.s("IMÁGENES", "PICTURES"), pics);
    }

    private void section(String title, List<Item> items) {
        long sum = 0; for (Item it : items) sum += it.bytes;
        TextView h = text(title + "  ·  " + gb(sum), 13, DIM, true); h.setPadding(dp(6), dp(16), dp(6), dp(4)); col.addView(h);
        LinearLayout c = card(col);
        for (Item it : items) {
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(2), dp(10), dp(2), dp(10));
            LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL);
            t.addView(text(it.title + "  ·  " + gb(it.bytes), 15, TXT, true));
            TextView s = text(it.sub, 12, DIM, false); s.setPadding(0, dp(2), 0, 0); t.addView(s);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            String act = it.kind == 1 ? L.s("Liberar", "Free up") : it.kind == 2 ? L.s("Modelos", "Models") : it.kind == 3 ? L.s("Eliminar", "Delete") : it.kind == 4 ? L.s("Vaciar", "Empty") : null;
            if (act != null) {
                Button bt = button(act, CARD, it.kind == 3 || it.kind == 4 ? WARN : ACC); bt.setTextSize(13); bt.setAllCaps(false);
                bt.setEnabled(it.kind == 2 || it.bytes > 0); bt.setAlpha(bt.isEnabled() ? 1f : .45f);
                bt.setOnClickListener(v -> act(it));
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, dp(40)); bl.leftMargin = dp(8); r.addView(bt, bl);
            }
            c.addView(r);
        }
    }

    private void act(Item it) {
        if (busy) return;
        if (it.kind == 2) { if (Upscaler.npuBusy) Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); else startActivity(new Intent(this, ModelsActivity.class)); return; }
        if (Upscaler.npuBusy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        if (it.kind == 1) { clear(it); return; }
        String msg = it.kind == 3 ? L.f("¿Eliminar el modelo de vídeo LTX (%s)? Para volver a usarlo habría que descargarlo de nuevo.", "Delete the LTX video model (%s)? To use it again it would have to be downloaded again.", gb(it.bytes))
            : L.f("¿Borrar «%s» (%s)? No se puede deshacer.", "Delete \"%s\" (%s)? This cannot be undone.", it.title, gb(it.bytes));
        builder().setMessage(msg).setNegativeButton(L.s("Cancelar", "Cancel"), null).setPositiveButton(L.s("Borrar", "Delete"), (d, w) -> clear(it)).show();
    }

    private void clear(Item it) {
        busy = true;
        new Thread(() -> {
            for (File f : it.files) {
                if (f.equals(getCacheDir()) || f.equals(Gallery.trashDir(this))) { File[] k = f.listFiles(); if (k != null) for (File c : k) Models.deleteTree(c); }   // keep the folder itself
                else Models.deleteTree(f);
            }
            final long freed = it.bytes;
            ui.post(() -> { busy = false; Toast.makeText(this, L.f("Liberado: %s", "Freed: %s", gb(freed)), Toast.LENGTH_SHORT).show(); refresh(); });
        }).start();
    }

    private static File[] filesExcept(File dir, String... skip) {
        File[] k = dir.listFiles(); if (k == null) return new File[0];
        List<File> out = new ArrayList<>();
        outer: for (File f : k) { for (String s : skip) if (f.getName().equals(s)) continue outer; out.add(f); }
        return out.toArray(new File[0]);
    }
    private static String metaName(File dir, String fallback) {
        try { return new JSONObject(Models.read(new File(dir, "meta.json"))).optString("name", fallback); } catch (Exception e) { return fallback; }
    }
    static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return 0;
        if (f.isFile()) return f.length();
        long s = 0; File[] k = f.listFiles(); if (k != null) for (File c : k) s += size(c); return s;
    }
    static String gb(long b) {
        return b >= 1L << 30 ? String.format(Locale.US, "%.1f GB", b / (double) (1L << 30)) : b >= 1L << 20 ? String.format(Locale.US, "%.0f MB", b / (double) (1L << 20)) : b > 0 ? String.format(Locale.US, "%.0f KB", Math.max(1, b / 1024.0)) : "0";
    }
}
