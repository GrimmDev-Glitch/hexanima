package com.hexanima.npu;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Corrections after generation (docs/IMAGE_CORRECTIONS_FLOW.md): pick a category, make alternatives from the accepted base, compare, keep one or drop it,
 *  undo / redo, save the result as a new picture. The original file is never touched; work files live in cache/corrections.
 *  Categories with a real processor on the NPU: Resolution (Real-ESRGAN x2 / x4) and Noise (Real-ESRGAN x4 and back to the same size, blended with the base).
 *  Face, hands, details and other edits are listed as coming later until they pass a test on V73. */
public class Corrections extends Base {
    static final int REQ = 15;
    File src, work;
    final List<File> chain = new ArrayList<>();          // chain[0] = the original; then every accepted version
    final List<JSONObject> chainSteps = new ArrayList<>();   // the step that made chain[i] (null for the original)
    int pos;                                              // the accepted version in use (undo / redo move it)
    final List<File> alts = new ArrayList<>(); final List<JSONObject> altSteps = new ArrayList<>();
    int sel = -1;                                         // selected alternative, -1 = the base
    String cat = "noise";
    CompareView cmp; TextView status; LinearLayout strip, panel, cats; Button useB, dropB, undoB, redoB, saveB;
    int noiseStr = 60, upFactor = 2; boolean running;
    final Handler ui = new Handler(Looper.getMainLooper());

    static void open(Activity a, File f) { a.startActivityForResult(new Intent(a, Corrections.class).putExtra("f", f.getPath()), REQ); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        src = new File(getIntent().getStringExtra("f") == null ? "" : getIntent().getStringExtra("f"));
        if (!src.isFile()) { finish(); return; }
        work = new File(getCacheDir(), "corrections"); deleteTree(work); work.mkdirs();
        chain.add(src); chainSteps.add(null); pos = 0;
        LinearLayout col = screen(L.s("Correcciones", "Corrections"), null);

        cmp = new CompareView(); col.addView(cmp, new LinearLayout.LayoutParams(-1, dp(430)));
        status = text("", 13, DIM, false); status.setPadding(dp(2), dp(6), 0, dp(4)); col.addView(status);
        TextView zh = text(L.s("Desliza para comparar · doble toque para acercar", "Slide to compare · double-tap to zoom"), 11, DIM, false); zh.setPadding(dp(2), 0, 0, dp(6)); col.addView(zh);
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        strip = new LinearLayout(this); hs.addView(strip); col.addView(hs);

        LinearLayout r1 = new LinearLayout(this); r1.setPadding(0, dp(8), 0, 0);
        useB = button(L.s("Usar esta", "Use this one"), ACC, ON_ACC); useB.setOnClickListener(v -> accept());
        dropB = button(L.s("Descartar", "Discard"), CARD, TXT); dropB.setOnClickListener(v -> discard());
        r1.addView(useB, new LinearLayout.LayoutParams(0, dp(44), 1)); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(44), 1); dl.leftMargin = dp(8); r1.addView(dropB, dl);
        col.addView(r1);
        LinearLayout r2 = new LinearLayout(this); r2.setPadding(0, dp(8), 0, 0);
        undoB = button(L.s("↶ Deshacer", "↶ Undo"), CARD, ACC); undoB.setOnClickListener(v -> { if (pos > 0) { pos--; clearAlts(); refresh(); } });
        redoB = button(L.s("Rehacer ↷", "Redo ↷"), CARD, ACC); redoB.setOnClickListener(v -> { if (pos + 1 < chain.size()) { pos++; clearAlts(); refresh(); } });
        r2.addView(undoB, new LinearLayout.LayoutParams(0, dp(42), 1)); LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, dp(42), 1); rl.leftMargin = dp(8); r2.addView(redoB, rl);
        col.addView(r2);

        col.addView(label(L.s("Corrección", "Correction")));
        HorizontalScrollView hc = new HorizontalScrollView(this); hc.setHorizontalScrollBarEnabled(false);
        cats = new LinearLayout(this); hc.addView(cats); col.addView(hc);
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(0, dp(8), 0, 0); col.addView(panel);

        saveB = button(L.s("Guardar en el Historial", "Save to the History"), ACC, ON_ACC); saveB.setOnClickListener(v -> save());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(48)); sl.topMargin = dp(16); col.addView(saveB, sl);
        TextView keep = text(L.s("La imagen original no se modifica: se guarda una nueva con las correcciones aceptadas.", "The original picture is not changed: a new one is saved with the accepted corrections."), 11, DIM, false);
        keep.setPadding(dp(2), dp(6), 0, 0); col.addView(keep);
        buildCats(); buildPanel(); refresh();
    }

    @Override protected void onDestroy() { super.onDestroy(); if (isFinishing() && work != null) deleteTree(work); cmp.release(); }
    static void deleteTree(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); f.delete(); }

    File base() { return chain.get(pos); }
    void clearAlts() { for (File f : alts) f.delete(); alts.clear(); altSteps.clear(); sel = -1; }

    // ---- categories: only the ones with a processor that ran on V73 can be used ----
    String[][] CATS() { return new String[][]{
        {"noise", L.s("Ruido", "Noise"), "1"}, {"resolution", L.s("Resolución", "Resolution"), "1"},
        {"face", L.s("Cara", "Face"), "0"}, {"hands", L.s("Manos", "Hands"), "0"}, {"details", L.s("Detalles", "Details"), "0"}, {"other", L.s("Otros", "Other"), "0"}}; }
    void buildCats() {
        cats.removeAllViews();
        for (String[] c : CATS()) {
            boolean on = c[2].equals("1"), cur = c[0].equals(cat);
            TextView t = text(on ? c[1] : c[1] + " · " + L.s("pronto", "soon"), 13, cur ? ON_ACC : on ? TXT : DIM, true);
            t.setGravity(Gravity.CENTER); t.setPadding(dp(14), 0, dp(14), 0); t.setBackground(cur ? grad(18) : bg(CARD, 18));
            t.setOnClickListener(v -> {
                if (!on) { Toast.makeText(this, L.s("Todavía no: necesita un procesador probado en la NPU V73", "Not yet: it needs a processor tested on the V73 NPU"), Toast.LENGTH_SHORT).show(); return; }
                if (running) return; cat = c[0]; buildCats(); buildPanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38)); lp.rightMargin = dp(6); cats.addView(t, lp);
        }
    }
    void buildPanel() {
        panel.removeAllViews();
        if (cat.equals("noise")) {
            panel.addView(text(L.s("Limpia ruido, grano y artefactos sin cambiar el tamaño (Real-ESRGAN en la NPU, no inventa caras). Demasiada fuerza borra textura fina.",
                "Cleans noise, grain and artefacts without changing the size (Real-ESRGAN on the NPU, it does not invent faces). Too much strength wipes fine texture."), 12, DIM, false));
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(8), 0, 0);
            r.addView(text(L.s("Fuerza", "Strength"), 14, TXT, true));
            final TextView v = text(noiseStr + " %", 13, DIM, false);
            SeekBar sb = new SeekBar(this); sb.setMax(100); sb.setProgress(noiseStr); sb.setProgressTintList(android.content.res.ColorStateList.valueOf(ACC)); sb.setThumbTintList(android.content.res.ColorStateList.valueOf(ACC));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean u) { noiseStr = Math.max(10, p); v.setText(noiseStr + " %"); }
                public void onStartTrackingTouch(SeekBar s) { } public void onStopTrackingTouch(SeekBar s) { }
            });
            r.addView(sb, new LinearLayout.LayoutParams(0, dp(36), 1)); r.addView(v); panel.addView(r);
        } else if (cat.equals("resolution")) {
            panel.addView(text(L.s("Aumenta la resolución y la nitidez (Real-ESRGAN en la NPU). Puede inventar textura; no arregla dedos ni rasgos.",
                "Raises the resolution and sharpness (Real-ESRGAN on the NPU). It may invent texture; it does not fix fingers or features."), 12, DIM, false));
            LinearLayout r = new LinearLayout(this); r.setPadding(0, dp(8), 0, 0);
            for (int f : new int[]{2, 4}) {
                Button bt = button("×" + f, f == upFactor ? ACC : CARD, f == upFactor ? ON_ACC : TXT);
                bt.setOnClickListener(x -> { upFactor = f; buildPanel(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1); if (f == 4) lp.leftMargin = dp(8); r.addView(bt, lp);
            }
            panel.addView(r);
        }
        Button gen = button(L.s("Generar alternativa", "Make an alternative"), ACC, ON_ACC); gen.setOnClickListener(v -> generate());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(46)); gl.topMargin = dp(10); panel.addView(gen, gl);
    }

    // ---- making an alternative: always from the accepted base, never from the previous alternative ----
    void generate() {
        if (running) return;
        if (Upscaler.npuBusy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_LONG).show(); return; }
        final File b = base(); final String c = cat; final int str = noiseStr, fac = upFactor;
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(b.getPath(), o);
        if (c.equals("resolution") && !Upscaler.fits(o.outWidth, o.outHeight, fac)) { Toast.makeText(this, L.s("La imagen es demasiado grande para ese aumento", "The picture is too large for that factor"), Toast.LENGTH_LONG).show(); return; }
        running = true; final boolean[] stop = {false}; final long t0 = System.currentTimeMillis();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(22), dp(8), dp(22), 0);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACC)); box.addView(bar);
        final TextView tv = text(L.s("Preparando…", "Preparing…"), 13, DIM, false); tv.setPadding(0, dp(6), 0, 0); box.addView(tv);
        final android.app.AlertDialog dlg = builder().setTitle(L.f("Alternativa %d", "Alternative %d", alts.size() + 1)).setView(box).setCancelable(false)
            .setNegativeButton(L.s("Отмена", "Cancel"), (d, i) -> stop[0] = true).show();
        new Thread(() -> {
            try {
                Bitmap in = BitmapFactory.decodeFile(b.getPath()); if (in == null) throw new java.io.IOException(L.s("No se pudo abrir la imagen", "Could not open the picture"));
                Upscaler.Progress pr = (k, n) -> ui.post(() -> { bar.setProgress(1000 * k / n); tv.setText(L.f("Trozo %d de %d", "Tile %d of %d", k, n)); });
                Bitmap out; JSONObject step = new JSONObject().put("type", c);
                if (c.equals("noise")) {
                    Bitmap den = Upscaler.run(this, in, 1, pr, () -> stop[0]);
                    out = blend(in, den, str / 100f); den.recycle(); step.put("strength", str / 100.0);
                } else { out = Upscaler.run(this, in, fac, pr, () -> stop[0]); step.put("factor", fac); }
                in.recycle();
                ui.post(() -> tv.setText(L.s("Guardando…", "Saving…")));
                File f = new File(work, "alt_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream fo = new FileOutputStream(f)) { out.compress(Bitmap.CompressFormat.PNG, 100, fo); }
                out.recycle(); step.put("sec", Math.round((System.currentTimeMillis() - t0) / 100.0) / 10.0);
                ui.post(() -> { dlg.dismiss(); running = false; alts.add(f); altSteps.add(step); sel = alts.size() - 1; refresh(); });
            } catch (InterruptedException e) { ui.post(() -> { dlg.dismiss(); running = false; });
            } catch (Throwable e) { ui.post(() -> { dlg.dismiss(); running = false; builder().setMessage(L.f("No se pudo corregir: %s", "Could not correct: %s", String.valueOf(e.getMessage()))).setPositiveButton("OK", null).show(); }); }
        }).start();
    }
    /** base * (1 - s) + processed * s, pixel by pixel */
    static Bitmap blend(Bitmap a, Bitmap b, float s) {
        int w = a.getWidth(), h = a.getHeight(); int[] pa = new int[w * h], pb = new int[w * h];
        a.getPixels(pa, 0, w, 0, 0, w, h); b.getPixels(pb, 0, w, 0, 0, w, h);
        for (int i = 0; i < pa.length; i++) {
            int x = pa[i], y = pb[i];
            int r = Math.round(((x >> 16) & 255) * (1 - s) + ((y >> 16) & 255) * s), g = Math.round(((x >> 8) & 255) * (1 - s) + ((y >> 8) & 255) * s), bl = Math.round((x & 255) * (1 - s) + (y & 255) * s);
            pa[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        return Bitmap.createBitmap(pa, w, h, Bitmap.Config.ARGB_8888);
    }

    void accept() {
        if (sel < 0) return;
        File keep = alts.remove(sel); JSONObject st = altSteps.remove(sel);
        while (chain.size() > pos + 1) { File f = chain.remove(chain.size() - 1); chainSteps.remove(chainSteps.size() - 1); if (!f.equals(src)) f.delete(); }   // a new branch drops the redo tail
        chain.add(keep); chainSteps.add(st); pos = chain.size() - 1;
        clearAlts(); refresh();
        Toast.makeText(this, L.s("Aceptada: la siguiente corrección parte de esta versión", "Accepted: the next correction starts from this version"), Toast.LENGTH_SHORT).show();
    }
    void discard() { if (sel < 0) return; alts.remove(sel).delete(); altSteps.remove(sel); sel = alts.isEmpty() ? -1 : Math.min(sel, alts.size() - 1); refresh(); }

    String stepText(JSONObject s) {
        if (s == null) return L.s("Original", "Original");
        String t = s.optString("type");
        String d = t.equals("noise") ? L.f("Ruido %d %%", "Noise %d %%", Math.round(s.optDouble("strength") * 100)) : L.f("Resolución ×%d", "Resolution ×%d", s.optInt("factor"));
        return s.has("sec") ? d + L.f(" · %.1f s", " · %.1f s", s.optDouble("sec")) : d;
    }
    static int[] size(File f) { BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), o); return new int[]{o.outWidth, o.outHeight}; }

    void refresh() {
        File b = base(); int[] bs = size(b);
        String baseName = pos == 0 ? L.s("Original", "Original") : L.s("Base aceptada", "Accepted base");
        if (sel >= 0) {
            File a = alts.get(sel); int[] as = size(a);
            cmp.set(b, a, baseName, L.f("Alternativa %d", "Alternative %d", sel + 1));
            status.setText(L.f("Alternativa %d · %s · %d×%d", "Alternative %d · %s · %d×%d", sel + 1, stepText(altSteps.get(sel)), as[0], as[1]));
        } else if (pos > 0) {
            cmp.set(src, b, L.s("Original", "Original"), baseName);
            status.setText(baseName + " · " + stepText(chainSteps.get(pos)) + " · " + bs[0] + "×" + bs[1]);
        } else { cmp.set(src, src, L.s("Original", "Original"), L.s("Original", "Original")); status.setText(L.s("Original", "Original") + " · " + bs[0] + "×" + bs[1]); }
        // the strip: original, accepted base, alternatives
        strip.removeAllViews();
        addThumb(src, L.s("Original", "Original"), sel < 0 && pos == 0, () -> { if (pos == 0) { sel = -1; refresh(); } else builder().setMessage(L.s("¿Volver al original? Las correcciones aceptadas quedan en «Rehacer».", "Back to the original? The accepted corrections stay under Redo.")).setPositiveButton("OK", (d, i) -> { pos = 0; clearAlts(); refresh(); }).setNegativeButton(L.s("Отмена", "Cancel"), null).show(); });
        if (pos > 0) addThumb(b, L.s("Base", "Base"), sel < 0, () -> { sel = -1; refresh(); });
        for (int i = 0; i < alts.size(); i++) { final int k = i; addThumb(alts.get(i), L.f("Alt. %d", "Alt. %d", i + 1), sel == i, () -> { sel = k; refresh(); }); }
        useB.setEnabled(sel >= 0); dropB.setEnabled(sel >= 0); useB.setAlpha(sel >= 0 ? 1f : .4f); dropB.setAlpha(sel >= 0 ? 1f : .4f);
        undoB.setEnabled(pos > 0); undoB.setAlpha(pos > 0 ? 1f : .4f); boolean redo = pos + 1 < chain.size(); redoB.setEnabled(redo); redoB.setAlpha(redo ? 1f : .4f);
        saveB.setEnabled(pos > 0); saveB.setAlpha(pos > 0 ? 1f : .4f);
    }
    void addThumb(File f, String name, boolean cur, Runnable r) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER_HORIZONTAL); c.setPadding(dp(4), dp(4), dp(4), dp(4));
        c.setBackground(cur ? grad(12) : bg(CARD, 12));
        ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = Math.max(1, size(f)[0] / 160); iv.setImageBitmap(BitmapFactory.decodeFile(f.getPath(), o));
        c.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(80)));
        TextView t = text(name, 11, cur ? ON_ACC : TXT, true); t.setGravity(Gravity.CENTER); c.addView(t);
        c.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(6); strip.addView(c, lp);
    }

    void save() {
        if (pos == 0) return;
        try {
            File f = base();
            JSONObject m = new JSONObject(Gallery.meta(src).toString()); int[] s = size(f);
            JSONArray steps = new JSONArray(); for (int i = 1; i <= pos; i++) steps.put(chainSteps.get(i));
            m.put("w", s[0]).put("h", s[1]).put("corrections", steps).put("corrected_from", src.getName()); m.remove("grid"); m.remove("compare");
            byte[] png = java.nio.file.Files.readAllBytes(f.toPath());
            File out = Gallery.save(this, png, m);
            Toast.makeText(this, L.s("Guardada en el Historial", "Saved to the History"), Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK, new Intent().putExtra("f", out.getPath())); finish();
        } catch (Exception e) { builder().setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK", null).show(); }
    }

    @Override public void onBackPressed() {
        if (pos > 0 || !alts.isEmpty()) {
            builder().setMessage(L.s("¿Salir sin guardar? Se pierden las correcciones de esta sesión.", "Leave without saving? The corrections of this session are lost."))
                .setPositiveButton(L.s("Salir", "Leave"), (d, i) -> super.onBackPressed()).setNegativeButton(L.s("Отмена", "Cancel"), null).show();
        } else super.onBackPressed();
    }

    /** before | after with a slider; the labels say which versions are compared; double-tap zooms 3x around the tap */
    final class CompareView extends View {
        Bitmap before, after; String lb = "", la = ""; float split = .5f, zoom = 1, zx = .5f, zy = .5f;
        final Rect dst = new Rect(); final Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG), lp = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.view.GestureDetector gd;
        CompareView() {
            super(Corrections.this); setBackground(bg(CARD, 16));
            lp.setColor(0xFFFFFFFF); lp.setStrokeWidth(dp(2)); tp.setColor(0xFFFFFFFF); tp.setTextSize(dp(12)); tp.setTypeface(F_SEMI); tp.setShadowLayer(dp(2), 0, 0, 0xFF000000);
            gd = new android.view.GestureDetector(Corrections.this, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (zoom > 1) zoom = 1; else if (dst.width() > 0) { zoom = 3; zx = (e.getX() - dst.left) / dst.width(); zy = (e.getY() - dst.top) / dst.height(); }
                    invalidate(); return true; }
            });
        }
        Bitmap load(File f) { int[] s = size(f); BitmapFactory.Options o = new BitmapFactory.Options(); int m = Math.max(s[0], s[1]); o.inSampleSize = 1; while (m / o.inSampleSize > 2560) o.inSampleSize *= 2; return BitmapFactory.decodeFile(f.getPath(), o); }
        void set(File b, File a, String lbl, String lal) {
            release(); before = load(b); after = b.equals(a) ? before : load(a); lb = lbl; la = lal; if (b.equals(a)) split = 1f; else if (split >= 1f) split = .5f; invalidate();
        }
        void release() { if (after != null && after != before) after.recycle(); if (before != null) before.recycle(); before = after = null; }
        Rect srcRect(Bitmap bm) {   // the zoomed window of a picture (same relative place in both)
            int w = bm.getWidth(), h = bm.getHeight(); if (zoom <= 1) return new Rect(0, 0, w, h);
            int vw = Math.round(w / zoom), vh = Math.round(h / zoom); int l = Math.max(0, Math.min(w - vw, Math.round(zx * w - vw / 2f))), t = Math.max(0, Math.min(h - vh, Math.round(zy * h - vh / 2f)));
            return new Rect(l, t, l + vw, t + vh);
        }
        @Override protected void onDraw(Canvas c) {
            if (after == null || before == null) return;
            float s = Math.min(getWidth() / (float) after.getWidth(), getHeight() / (float) after.getHeight());
            int w = Math.round(after.getWidth() * s), h = Math.round(after.getHeight() * s), l = (getWidth() - w) / 2, t = (getHeight() - h) / 2;
            dst.set(l, t, l + w, t + h); int sx = l + Math.round(w * split);
            c.save(); c.clipRect(l, t, sx, t + h); c.drawBitmap(before, srcRect(before), dst, fp); c.restore();
            c.save(); c.clipRect(sx, t, l + w, t + h); c.drawBitmap(after, srcRect(after), dst, fp); c.restore();
            if (split < 1f) { c.drawLine(sx, t, sx, t + h, lp); c.drawCircle(sx, t + h / 2f, dp(10), lp); }
            tp.setTextAlign(Paint.Align.LEFT); c.drawText(lb, l + dp(8), t + dp(18), tp);
            if (split < 1f) { tp.setTextAlign(Paint.Align.RIGHT); c.drawText(la, l + w - dp(8), t + dp(18), tp); }
            if (zoom > 1) { tp.setTextAlign(Paint.Align.LEFT); c.drawText("3×", l + dp(8), t + h - dp(10), tp); }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            gd.onTouchEvent(e);
            if (before != after && dst.width() > 0 && e.getActionMasked() == MotionEvent.ACTION_MOVE) { split = Math.max(0f, Math.min(1f, (e.getX() - dst.left) / dst.width())); invalidate(); }
            return true;
        }
    }
}
