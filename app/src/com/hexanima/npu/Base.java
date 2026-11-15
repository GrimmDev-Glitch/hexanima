package com.hexanima.npu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.view.View;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import android.view.MotionEvent;
import android.graphics.Color;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Shared look and view builders for all screens; the UI is built in code, there are no layout files.
 *  Lavender palettes (light / dark), violet -> pink gradient for primary and selected controls, cards with a violet corner bracket, letter-spaced
 *  section titles and Barlow Semi Condensed (SIL OFL, a free DIN-style face close to Bahnschrift, which may not be redistributed) in assets/fonts. */
abstract class Base extends Activity {
    /** palette: set by applyTheme() (light, dark, or follow the system) before any view is built. ACC violet, ACC2 pink, ACC3 blue, SEL selected row, LINE borders, HEAD section titles */
    static int BG, CARD, FIELD, ACC, ACC2, ACC3, SEL, LINE, HEAD, ON_ACC, TXT, DIM, OK, BAD, WARN;
    static boolean dark = true;
    static Typeface F_REG, F_MED, F_SEMI, F_BOLD;
    String uiKey = "";

    // ---- own wallpaper (from upstream 1.6.0): files/wallpaper.jpg behind every screen; the blocks (CARD / FIELD) get an alpha so it shows through ----
    static java.io.File wallFile(Context c) { return new java.io.File(c.getFilesDir(), "wallpaper.jpg"); }
    static boolean hasWall(Context c) { return wallFile(c).exists(); }
    static int cardTrans(Context c) { return c.getSharedPreferences("anima", 0).getInt("card_trans", 25); }   // 0..90 % see-through
    static int wallDim(Context c) { return c.getSharedPreferences("anima", 0).getInt("wall_dim", 55); }      // 0..80 % towards the page colour
    static android.graphics.Bitmap wallBm; static long wallBmTs = -1;
    static float wZoom = 1f, wX = .5f, wY = .5f;   // scale (1 = just covers the screen) and the picture point at the screen centre (0..1)
    static int dimNow = -1;                        // dimming previewed while its slider moves (-1: the saved one)
    static void loadWallParams(Context c) { android.content.SharedPreferences p = c.getSharedPreferences("anima", 0); wZoom = p.getFloat("wall_zoom", 1f); wX = p.getFloat("wall_x", .5f); wY = p.getFloat("wall_y", .5f); }
    static void saveWallParams(Context c) { c.getSharedPreferences("anima", 0).edit().putFloat("wall_zoom", wZoom).putFloat("wall_x", wX).putFloat("wall_y", wY).apply(); }
    static float wallScale(int bw, int bh) { return Math.max((float) bw / wallBm.getWidth(), (float) bh / wallBm.getHeight()) * wZoom; }
    static void wallClamp(int bw, int bh) {
        if (wallBm == null) return; float sc = wallScale(bw, bh), hx = bw / sc / 2 / wallBm.getWidth(), hy = bh / sc / 2 / wallBm.getHeight();
        wX = Math.max(hx, Math.min(1 - hx, wX)); wY = Math.max(hy, Math.min(1 - hy, wY));
    }
    /** the wallpaper at the chosen scale and position, with the dimming layer on top */
    static final class WallDrawable extends android.graphics.drawable.Drawable {
        final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG); final int dimPref, bg;
        WallDrawable(Context c, int bg) {
            this.bg = bg; long ts = c.getSharedPreferences("anima", 0).getLong("wall_ts", 0);
            if (wallBm == null || wallBmTs != ts) { wallBm = android.graphics.BitmapFactory.decodeFile(wallFile(c).getPath()); wallBmTs = ts; }
            loadWallParams(c); dimPref = wallDim(c);
        }
        @Override public void draw(android.graphics.Canvas cv) {
            android.graphics.Rect b = getBounds(); if (b.isEmpty()) return;
            cv.drawColor(bg | 0xFF000000);
            if (wallBm != null) {
                wallClamp(b.width(), b.height()); float sc = wallScale(b.width(), b.height()), vw = b.width() / sc, vh = b.height() / sc, cx = wX * wallBm.getWidth(), cy = wY * wallBm.getHeight();
                cv.drawBitmap(wallBm, new android.graphics.Rect((int) (cx - vw / 2), (int) (cy - vh / 2), (int) (cx + vw / 2), (int) (cy + vh / 2)), b, pt);
            }
            cv.drawColor(((int) (255 * (dimNow >= 0 ? dimNow : dimPref) / 100f) << 24) | (bg & 0xFFFFFF));   // dim towards the theme's page colour
        }
        @Override public void setAlpha(int a) { } @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
    /** the page background: the wallpaper when one is set, else the plain page colour */
    void pageBg(View v) { if (hasWall(this) && !isFinishing()) v.setBackground(new WallDrawable(this, BG)); else v.setBackgroundColor(BG); }
    /** what decides whether an open screen must be rebuilt: language, theme and the wallpaper settings */
    String themeKey() { android.content.SharedPreferences p = getSharedPreferences("anima", 0); return L.lang + isDark(this) + "w" + hasWall(this) + p.getLong("wall_ts", 0) + "," + cardTrans(this) + "," + wallDim(this) + "," + p.getFloat("wall_zoom", 1f) + "," + p.getFloat("wall_x", .5f) + "," + p.getFloat("wall_y", .5f); }

    static boolean isDark(Context c) {
        String m = c.getSharedPreferences("anima", 0).getString("theme", "system");
        return m.equals("dark") || (m.equals("system") && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }

    static void applyTheme(Context c) {
        dark = isDark(c);
        if (dark) { BG = 0xFF1B172F; CARD = 0xFF262344; FIELD = 0xFF1F1B36; ACC = 0xFFA38FFD; ACC2 = 0xFFE3A2E1; ACC3 = 0xFF9FC0EE; SEL = 0xFF312A53; LINE = 0xFF353060; HEAD = 0xFFE9E6F5;
                    ON_ACC = 0xFFFFFFFF; TXT = 0xFFE9E6F5; DIM = 0xFF928DB2; OK = 0xFF7EE0B0; BAD = 0xFFF58AA8; WARN = 0xFFF5C77E; }
        else { BG = 0xFFE9E5F1; CARD = 0xFFFCFBFE; FIELD = 0xFFF1EEF7; ACC = 0xFF8672ED; ACC2 = 0xFFD593D7; ACC3 = 0xFF9DC1F0; SEL = 0xFFECE7F6; LINE = 0xFFE2DCEE; HEAD = 0xFF2E2A45;
               ON_ACC = 0xFFFFFFFF; TXT = 0xFF2E2A45; DIM = 0xFF8C899C; OK = 0xFF2E9E6E; BAD = 0xFFD6487A; WARN = 0xFFB7791F; }
        dimNow = -1;
        if (hasWall(c)) { int a = (255 - 255 * cardTrans(c) / 100) << 24; CARD = (CARD & 0xFFFFFF) | a; FIELD = (FIELD & 0xFFFFFF) | a; SEL = (SEL & 0xFFFFFF) | a; }   // see-through blocks over the wallpaper
        if (F_REG == null) {
            try {
                F_REG = Typeface.createFromAsset(c.getAssets(), "fonts/BarlowSemiCondensed-Regular.ttf");
                F_MED = Typeface.createFromAsset(c.getAssets(), "fonts/BarlowSemiCondensed-Medium.ttf");
                F_SEMI = Typeface.createFromAsset(c.getAssets(), "fonts/BarlowSemiCondensed-SemiBold.ttf");
                F_BOLD = Typeface.createFromAsset(c.getAssets(), "fonts/BarlowSemiCondensed-Bold.ttf");
            } catch (Exception e) { F_REG = Typeface.DEFAULT; F_MED = Typeface.DEFAULT; F_SEMI = Typeface.DEFAULT_BOLD; F_BOLD = Typeface.DEFAULT_BOLD; }
        }
    }

    /** violet -> pink, the primary / selected look */
    GradientDrawable grad(float radius) { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACC, ACC2}); g.setCornerRadius(dp(radius)); return g; }

    /** card background: rounded fill, hairline border, violet L bracket on the top-left corner (and a faint pink one bottom-right) */
    class BracketCard extends android.graphics.drawable.Drawable {
        final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), br = new android.graphics.Paint();
        final float r;
        BracketCard(int color, float radius) { r = dp(radius); fill.setColor(color); line.setColor(LINE); line.setStyle(android.graphics.Paint.Style.STROKE); line.setStrokeWidth(dp(1)); br.setStrokeWidth(dp(3)); }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds(); android.graphics.RectF f = new android.graphics.RectF(b); f.inset(dp(1), dp(1));
            c.drawRoundRect(f, r, r, fill); c.drawRoundRect(f, r, r, line);
            float t = dp(3) / 2f, L = dp(16);
            br.setColor(ACC); c.drawLine(b.left + t, b.top + t, b.left + L, b.top + t, br); c.drawLine(b.left + t, b.top + t, b.left + t, b.top + L, br);
            br.setColor((ACC2 & 0x00FFFFFF) | 0x99000000); c.drawLine(b.right - t, b.bottom - t, b.right - L, b.bottom - t, br); c.drawLine(b.right - t, b.bottom - t, b.right - t, b.bottom - L, br);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        applyTheme(this);
        uiKey = themeKey();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    /** language or theme changed on another screen: rebuild this one */
    @Override protected void onResume() {
        super.onResume();
        L.init(this);
        if (!themeKey().equals(uiKey)) recreate();
    }

    /** dialogs follow the theme: card background with the violet bracket, Barlow everywhere, accent buttons in sentence case (not Android's grey box with CAPITALS) */
    AlertDialog.Builder builder() { return new Themed(this); }
    class Themed extends AlertDialog.Builder {
        Themed(Context c) { super(c, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert); }
        @Override public AlertDialog create() { AlertDialog d = super.create(); d.setOnShowListener(x -> styleDialog(d)); return d; }
    }
    void styleDialog(AlertDialog d) {
        android.view.Window w = d.getWindow(); if (w == null) return;
        w.setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(new BracketCard(CARD | 0xFF000000, 22), dp(14), dp(10), dp(14), dp(10)));
        int tid = getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = tid != 0 ? w.findViewById(tid) : null; if (title != null) { title.setTypeface(F_SEMI); title.setTextColor(HEAD); title.setTextSize(20); }
        TextView msg = w.findViewById(android.R.id.message); if (msg != null) { msg.setTypeface(F_REG); msg.setTextColor(TXT); msg.setTextSize(16); msg.setLineSpacing(0, 1.1f); }
        int[] ids = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int id : ids) { Button bt = d.getButton(id); if (bt == null) continue; bt.setAllCaps(false); bt.setTypeface(F_SEMI); bt.setTextSize(16);
            bt.setTextColor(id == AlertDialog.BUTTON_POSITIVE ? ACC : id == AlertDialog.BUTTON_NEGATIVE ? DIM : ACC3); }
        android.widget.ListView lv = d.getListView();
        if (lv != null) { lv.setDivider(new android.graphics.drawable.ColorDrawable(LINE)); lv.setDividerHeight(1);
            lv.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
                public void onChildViewAdded(View parent, View child) { fontTree(child); }
                public void onChildViewRemoved(View parent, View child) { } });
            for (int i = 0; i < lv.getChildCount(); i++) fontTree(lv.getChildAt(i)); }
        fontTree(w.getDecorView());
    }
    /** Barlow for every text view of a tree that still has the system face (keeps the weights we set ourselves) */
    void fontTree(View v) {
        if (v instanceof TextView) { TextView t = (TextView) v; Typeface f = t.getTypeface();
            if (f != F_REG && f != F_MED && f != F_SEMI && f != F_BOLD) t.setTypeface(f != null && f.isBold() ? F_SEMI : F_REG);
            if (v instanceof android.widget.CheckedTextView || t.getId() == android.R.id.text1) { t.setTextColor(TXT); t.setTextSize(17); } }
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) fontTree(g.getChildAt(i)); }
    }

    /** themed drop-down menu under `anchor` (replaces PopupMenu, which keeps the system look): card with the bracket, Barlow rows */
    void popup(View anchor, String[] items, java.util.function.IntConsumer pick) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(6), dp(8), dp(6), dp(8));
        box.setBackground(new BracketCard(CARD | 0xFF000000, 18));
        android.widget.PopupWindow pw = new android.widget.PopupWindow(box, dp(250), -2, true);
        for (int i = 0; i < items.length; i++) {
            final int k = i; TextView t = text(items[i], 16, TXT, false); t.setPadding(dp(16), dp(13), dp(16), dp(13));
            android.util.TypedValue tv = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true); t.setBackgroundResource(tv.resourceId);
            t.setOnClickListener(x -> { pw.dismiss(); pick.accept(k); }); box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); pw.setElevation(dp(10)); pw.setOutsideTouchable(true);
        pw.showAsDropDown(anchor, anchor.getWidth() - dp(250), dp(4));
    }

    /** the edit over the original: drag the divider to reveal one or the other; "Save side by side" stores a comparison picture */
    class BeforeAfterView extends View {
        final android.graphics.Bitmap before, after; float split = .5f; final android.graphics.Rect dst = new android.graphics.Rect(); final android.graphics.Rect bsrc;
        final android.graphics.Paint fp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG), lp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        BeforeAfterView(android.graphics.Bitmap before, android.graphics.Bitmap after) {
            super(Base.this); this.before = before; this.after = after; bsrc = Qwen.coverRect(before, after.getWidth(), after.getHeight());
            lp.setColor(0xFFFFFFFF); lp.setStrokeWidth(dp(2)); tp.setColor(0xFFFFFFFF); tp.setTextSize(dp(12)); tp.setTypeface(F_SEMI); tp.setShadowLayer(dp(2), 0, 0, 0xFF000000);
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float s = Math.min(getWidth() / (float) after.getWidth(), getHeight() / (float) after.getHeight());
            int w = Math.round(after.getWidth() * s), h = Math.round(after.getHeight() * s), l = (getWidth() - w) / 2, t = (getHeight() - h) / 2;
            dst.set(l, t, l + w, t + h); int sx = l + Math.round(w * split);
            c.save(); c.clipRect(l, t, sx, t + h); c.drawBitmap(before, bsrc, dst, fp); c.restore();
            c.save(); c.clipRect(sx, t, l + w, t + h); c.drawBitmap(after, null, dst, fp); c.restore();
            c.drawLine(sx, t, sx, t + h, lp); c.drawCircle(sx, t + h / 2f, dp(10), lp);
            tp.setTextAlign(android.graphics.Paint.Align.LEFT); c.drawText(L.s("Original", "Original"), l + dp(8), t + dp(18), tp);
            tp.setTextAlign(android.graphics.Paint.Align.RIGHT); c.drawText(L.s("Editada", "Edited"), l + w - dp(8), t + dp(18), tp);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            if (dst.width() > 0) { split = Math.max(0f, Math.min(1f, (e.getX() - dst.left) / dst.width())); invalidate(); }
            return true;
        }
    }
    /** "Enlarge": x2 or x4 on the NPU (Upscaler), saved as a new picture in the History; done gets the new file on the UI thread */
    void upscaleDialog(final java.io.File src, final java.util.function.Consumer<java.io.File> done) {
        if (!Upscaler.available(this)) { Toast.makeText(this, L.s("Este chip no tiene el escalador", "This chip has no upscaler"), Toast.LENGTH_LONG).show(); return; }
        if (Upscaler.npuBusy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_LONG).show(); return; }
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeFile(src.getPath(), o);
        final int w = o.outWidth, h = o.outHeight; if (w <= 0) return;
        final boolean x4ok = Upscaler.fits(w, h, 4), x2ok = Upscaler.fits(w, h, 2);
        if (!x2ok) { Toast.makeText(this, L.s("La imagen ya es demasiado grande para ampliarla", "The picture is already too large to enlarge"), Toast.LENGTH_LONG).show(); return; }
        String[] items = {L.f("×2  ·  %d×%d", "×2  ·  %d×%d", w * 2, h * 2), x4ok ? L.f("×4  ·  %d×%d", "×4  ·  %d×%d", w * 4, h * 4) : L.s("×4  ·  demasiado grande", "×4  ·  too large")};
        builder().setTitle(L.s("Ampliar en la NPU", "Enlarge on the NPU"))
            .setMessage(L.s("Real-ESRGAN aumenta la resolución y la nitidez en unos segundos. Puede inventar detalles pequeños (pelo, texto, texturas); la imagen original no se toca.",
                "Real-ESRGAN raises the resolution and sharpness in a few seconds. It may invent small details (hair, text, textures); the original picture is kept."))
            .setPositiveButton(items[0], (d, i) -> runUpscale(src, 2, done))
            .setNeutralButton(items[1], (d, i) -> { if (x4ok) runUpscale(src, 4, done); else Toast.makeText(this, L.s("Usa ×2 para esta imagen", "Use ×2 for this picture"), Toast.LENGTH_LONG).show(); })
            .setNegativeButton(L.s("Отмена", "Cancel"), null).show();
    }
    void runUpscale(final java.io.File src, final int factor, final java.util.function.Consumer<java.io.File> done) {
        final boolean[] stop = {false};
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(22), dp(8), dp(22), 0);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); bar.setProgressTintList(ColorStateList.valueOf(ACC)); box.addView(bar);
        final TextView tv = text(L.s("Preparando…", "Preparing…"), 13, DIM, false); tv.setPadding(0, dp(6), 0, 0); box.addView(tv);
        final AlertDialog dlg = builder().setTitle(L.f("Ampliando ×%d", "Enlarging ×%d", factor)).setView(box).setCancelable(false)
            .setNegativeButton(L.s("Отмена", "Cancel"), (d, i) -> stop[0] = true).show();
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper()); final long t0 = System.currentTimeMillis();
        new Thread(() -> {
            try {
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(src.getPath()); if (b == null) throw new java.io.IOException(L.s("No se pudo abrir la imagen", "Could not open the picture"));
                android.graphics.Bitmap up = Upscaler.run(this, b, factor, (k, n) -> ui.post(() -> { bar.setProgress(1000 * k / n); tv.setText(L.f("Trozo %d de %d", "Tile %d of %d", k, n)); }), () -> stop[0]);
                b.recycle();
                ui.post(() -> tv.setText(L.s("Guardando…", "Saving…")));
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); up.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo); up.recycle();
                org.json.JSONObject m = new org.json.JSONObject(Gallery.meta(src).toString());
                m.put("w", w(src) * factor).put("h", h(src) * factor).put("upscaled", factor).put("upscaled_from", src.getName()).put("upscaler", "Real-ESRGAN General x4v3")
                 .put("sec", (System.currentTimeMillis() - t0) / 1000.0); m.remove("grid"); m.remove("compare");
                final java.io.File out = Gallery.save(this, bo.toByteArray(), m);
                ui.post(() -> { dlg.dismiss(); Toast.makeText(this, L.f("Ampliada ×%d en %.1f s · guardada en el Historial", "Enlarged ×%d in %.1f s · saved in the History", factor, (System.currentTimeMillis() - t0) / 1000.0), Toast.LENGTH_LONG).show(); if (done != null) done.accept(out); });
            } catch (InterruptedException e) { ui.post(dlg::dismiss);
            } catch (Throwable e) { ui.post(() -> { dlg.dismiss(); builder().setMessage(L.f("No se pudo ampliar: %s", "Could not enlarge: %s", String.valueOf(e.getMessage()))).setPositiveButton("OK", null).show(); }); }
        }).start();
    }
    private static int w(java.io.File f) { android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeFile(f.getPath(), o); return o.outWidth; }
    private static int h(java.io.File f) { android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeFile(f.getPath(), o); return o.outHeight; }

    void showBeforeAfter(final java.io.File after) {
        if (after == null) return;
        final java.io.File bf = Qwen.beforeFile(getFilesDir(), after); if (!bf.isFile()) return;
        final android.graphics.Bitmap a = android.graphics.BitmapFactory.decodeFile(after.getPath()), b = android.graphics.BitmapFactory.decodeFile(bf.getPath()); if (a == null || b == null) return;
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(16), dp(14), dp(16), dp(10));
        v.addView(text(L.s("Antes / después", "Before / after"), 20, TXT, true));
        TextView hint = text(L.s("Desliza el dedo sobre la imagen para mover la línea.", "Slide a finger over the picture to move the line."), 12, DIM, false); hint.setPadding(0, dp(2), 0, dp(8)); v.addView(hint);
        v.addView(new BeforeAfterView(b, a), new LinearLayout.LayoutParams(-1, dp(440)));
        LinearLayout bot = new LinearLayout(this); bot.setPadding(0, dp(10), 0, 0);
        Button saveSbs = button(L.s("Guardar lado a lado", "Save side by side"), FIELD, ACC); bot.addView(saveSbs, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button close = button(L.s("Готово", "Done"), ACC, ON_ACC); LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(44), 1); cl.leftMargin = dp(8); bot.addView(close, cl);
        v.addView(bot);
        final AlertDialog d = dialog(v);
        d.setOnDismissListener(x -> { a.recycle(); b.recycle(); });
        close.setOnClickListener(x -> d.dismiss());
        saveSbs.setOnClickListener(x -> {
            java.io.File f = saveSideBySide(b, a, Gallery.meta(after));
            Toast.makeText(this, f != null ? L.s("Comparación guardada en el historial", "Comparison saved to the history") : L.s("No se pudo guardar la comparación", "Could not save the comparison"), Toast.LENGTH_SHORT).show();
        });
    }
    /** stores "original | edit" in the history (the prompt and seed of the edit go into its metadata) */
    java.io.File saveSideBySide(android.graphics.Bitmap before, android.graphics.Bitmap after, org.json.JSONObject editMeta) {
        android.graphics.Bitmap g = Qwen.sideBySide(before, after, L.s("Original", "Original"), L.s("Editada", "Edited"), F_SEMI);
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); g.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes);
            org.json.JSONObject meta = new org.json.JSONObject().put("prompt", editMeta.optString("prompt")).put("seed", editMeta.optString("seed")).put("w", g.getWidth()).put("h", g.getHeight())
                .put("model", editMeta.optString("model")).put("id", editMeta.optString("id")).put("compare", true);
            return Gallery.save(this, PngMeta.embed(bytes.toByteArray(), meta), meta);
        } catch (Exception e) { return null; }
        finally { g.recycle(); }
    }

    /** a dialog around a custom view, with the card background */
    AlertDialog dialog(View v) { AlertDialog d = builder().setView(v).create(); d.show(); return d; }

    int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    GradientDrawable bg(int color, float radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp + 1); t.setTextColor(color);   // +1: the condensed face reads smaller at the same size
        t.setTypeface(bold ? F_SEMI : F_REG);
        return t;
    }

    /** a button; the accent colour means the primary action and gets the violet -> pink gradient */
    Button button(String s, int color, int textColor) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(textColor);
        b.setTypeface(F_SEMI); b.setStateListAnimator(null); b.setBackground(color == ACC ? grad(14) : bg(color, 14));
        if (color == ACC) b.setTextColor(ON_ACC);
        return b;
    }

    /** square icon button from res/drawable (looked up by name: the build has no R class) */
    ImageButton icon(String name, int color) {
        ImageButton b = new ImageButton(this); b.setImageResource(getResources().getIdentifier(name, "drawable", getPackageName()));
        b.setBackground(bg(color, 14)); b.setPadding(dp(10), dp(10), dp(10), dp(10)); b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (!name.equals("ic_star") && !name.equals("ic_delete")) b.setImageTintList(ColorStateList.valueOf(TXT));   // star / trash keep their own colours
        return b;
    }

    EditText field(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(DIM); e.setTextColor(TXT); e.setTextSize(17); e.setTypeface(F_REG);
        e.setBackground(bg(FIELD, 12)); e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    /** section title: upper case, letter-spaced, semibold, with a small violet marker */
    TextView label(String s) {
        TextView t = new TextView(this); t.setTextSize(12.5f); t.setTypeface(F_BOLD); t.setLetterSpacing(.16f); t.setTextColor(HEAD);
        android.text.SpannableString sp = new android.text.SpannableString("▸ " + s.toUpperCase(java.util.Locale.ROOT));
        sp.setSpan(new android.text.style.ForegroundColorSpan(ACC), 0, 1, 0); t.setText(sp);
        t.setPadding(dp(2), dp(16), 0, dp(7)); return t;
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(new BracketCard(CARD, 16)); c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(10);
        parent.addView(c, lp);
        return c;
    }

    /** screen skeleton: [←] title [extra view] on top, a scrolling column below; returns the column */
    LinearLayout screen(String title, android.view.View extra) {
        LinearLayout frame = new LinearLayout(this); frame.setOrientation(LinearLayout.VERTICAL); pageBg(frame); frame.setFitsSystemWindows(true);
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(16), dp(12), dp(16), dp(8));
        Button back = button("←", CARD, TXT); back.setTextSize(18); back.setOnClickListener(v -> onBackPressed());
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(42)));
        TextView t = text(title, 24, TXT, true); t.setPadding(dp(14), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (extra != null) head.addView(extra, new LinearLayout.LayoutParams(-2, dp(40)));
        frame.addView(head);
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(16), dp(4), dp(16), dp(20));
        sv.addView(col, new android.view.ViewGroup.LayoutParams(-1, -2));
        frame.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
        return col;
    }

// ---- moved from Main so Corrections can paint masks too ----
    /** finger painting on the init image: brush / eraser, size, clear; the mask is saved at the init image's size (at most 1024 px on the long side) */
    class MaskView extends View {
        Bitmap base, mask; android.graphics.Canvas mc; final android.graphics.Paint br = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), ov = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        final android.graphics.RectF dst = new android.graphics.RectF(); float lx, ly, size = .02f, zoom = 1f, panX, panY, prevSpan, prevMidX, prevMidY, downX, downY, lastX, lastY; boolean multiTouch, drawing, pending;
        static final int BRUSH = 0, ERASER = 1, MOVE = 2; int tool = BRUSH;
        final float slop = android.view.ViewConfiguration.get(Base.this).getScaledTouchSlop();
        final ArrayDeque<Bitmap> undo = new ArrayDeque<>(), redo = new ArrayDeque<>(); final int historyLimit = 8; Runnable historyChanged, zoomChanged;
        MaskView(Bitmap base, Bitmap old) {
            super(Base.this); this.base = base;
            mask = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888); mc = new android.graphics.Canvas(mask);
            if (old != null) {   // stored as white on black: back to white on transparent
                Bitmap o = Bitmap.createScaledBitmap(old, base.getWidth(), base.getHeight(), true); int[] px = new int[o.getWidth() * o.getHeight()];
                o.getPixels(px, 0, o.getWidth(), 0, 0, o.getWidth(), o.getHeight()); for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xFF) > 127 ? 0xFFFFFFFF : 0;
                mask.setPixels(px, 0, o.getWidth(), 0, 0, o.getWidth(), o.getHeight());
            }
            br.setStyle(android.graphics.Paint.Style.STROKE); br.setStrokeCap(android.graphics.Paint.Cap.ROUND); br.setStrokeJoin(android.graphics.Paint.Join.ROUND); br.setColor(0xFFFFFFFF);
            ov.setColorFilter(new android.graphics.PorterDuffColorFilter(ACC2, android.graphics.PorterDuff.Mode.SRC_IN)); ov.setAlpha(140);
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float s = Math.min(getWidth() / (float) base.getWidth(), getHeight() / (float) base.getHeight()) * zoom;
            float w = base.getWidth() * s, h = base.getHeight() * s;
            clampPan(); float cx = getWidth() / 2f + panX, cy = getHeight() / 2f + panY;
            dst.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            c.drawBitmap(base, null, dst, null); c.drawBitmap(mask, null, dst, ov);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            // A stroke only starts once one finger has moved past the touch slop (or lifts as a tap), so the first finger of a
            // pinch never paints; if a second finger still lands after a stroke started, that stroke is rolled back.
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                if (drawing) rollbackStroke();
                multiTouch = true; drawing = false; pending = false; prevSpan = span(e); prevMidX = midX(e); prevMidY = midY(e); return true;
            }
            if (multiTouch && action == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2) {
                float ns = span(e), mx = midX(e), my = midY(e);
                if (prevSpan > 0) zoom = Math.max(1f, Math.min(4f, zoom * ns / prevSpan));
                panX += mx - prevMidX; panY += my - prevMidY; prevSpan = ns; prevMidX = mx; prevMidY = my; clampPan(); invalidate(); if (zoomChanged != null) zoomChanged.run(); return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) { multiTouch = true; return true; }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_UP && pending && !multiTouch && tool != MOVE) { startStroke(downX, downY); strokeTo(e.getX(), e.getY()); }   // a tap leaves a dot
                drawing = false; pending = false; multiTouch = false; if (historyChanged != null) historyChanged.run(); return true;
            }
            if (multiTouch) return true;
            if (action == MotionEvent.ACTION_DOWN) {
                downX = lastX = e.getX(); downY = lastY = e.getY(); drawing = false;
                pending = tool == MOVE || dst.contains(downX, downY); return true;
            }
            if (action != MotionEvent.ACTION_MOVE || !pending && !drawing) return true;
            if (tool == MOVE) {   // one finger pans the zoomed picture
                panX += e.getX() - lastX; panY += e.getY() - lastY; lastX = e.getX(); lastY = e.getY(); clampPan(); invalidate(); return true;
            }
            if (pending) {
                if (Math.hypot(e.getX() - downX, e.getY() - downY) < slop) return true;
                pending = false; startStroke(downX, downY);
            }
            strokeTo(e.getX(), e.getY());
            return true;
        }
        float bx(float x) { return Math.max(0, Math.min(base.getWidth() - 1, (x - dst.left) / dst.width() * base.getWidth())); }
        float by(float y) { return Math.max(0, Math.min(base.getHeight() - 1, (y - dst.top) / dst.height() * base.getHeight())); }
        void startStroke(float x, float y) {
            push(undo, mask); clearStack(redo); lx = bx(x); ly = by(y); drawing = true;
            br.setStrokeWidth(size * Math.max(base.getWidth(), base.getHeight()));
            br.setXfermode(tool == ERASER ? new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) : null);
        }
        void strokeTo(float x, float y) { float nx = bx(x), ny = by(y); mc.drawLine(lx, ly, nx + .01f, ny, br); lx = nx; ly = ny; invalidate(); }
        /** undo the stroke this gesture started, without leaving a redo entry */
        void rollbackStroke() {
            if (undo.isEmpty()) return; Bitmap old = undo.removeLast();
            mc.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR); mc.drawBitmap(old, 0, 0, null); old.recycle(); invalidate();
        }
        float span(MotionEvent e) { float x = e.getX(0) - e.getX(1), y = e.getY(0) - e.getY(1); return (float)Math.hypot(x, y); }
        float midX(MotionEvent e) { return (e.getX(0) + e.getX(1)) * .5f; }
        float midY(MotionEvent e) { return (e.getY(0) + e.getY(1)) * .5f; }
        void clampPan() { float fw = Math.min(getWidth() / (float)base.getWidth(), getHeight() / (float)base.getHeight()) * zoom * base.getWidth(), fh = Math.min(getWidth() / (float)base.getWidth(), getHeight() / (float)base.getHeight()) * zoom * base.getHeight(); panX = fw <= getWidth() ? 0 : Math.max((getWidth() - fw) / 2, Math.min((fw - getWidth()) / 2, panX)); panY = fh <= getHeight() ? 0 : Math.max((getHeight() - fh) / 2, Math.min((fh - getHeight()) / 2, panY)); }
        void push(ArrayDeque<Bitmap> stack, Bitmap current) { stack.addLast(current.copy(Bitmap.Config.ARGB_8888, false)); while (stack.size() > historyLimit) stack.removeFirst().recycle(); }
        void clearStack(ArrayDeque<Bitmap> stack) { while (!stack.isEmpty()) stack.removeLast().recycle(); }
        void undo() { if (undo.isEmpty()) return; push(redo, mask); Bitmap old = undo.removeLast(); mc.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR); mc.drawBitmap(old, 0, 0, null); old.recycle(); invalidate(); }
        void redo() { if (redo.isEmpty()) return; push(undo, mask); Bitmap next = redo.removeLast(); mc.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR); mc.drawBitmap(next, 0, 0, null); next.recycle(); invalidate(); }
        void setZoom(float z) { zoom = Math.max(1f, Math.min(4f, z)); clampPan(); invalidate(); }
        void clear() { push(undo, mask); clearStack(redo); mask.eraseColor(Color.TRANSPARENT); invalidate(); }
        void dispose() { clearStack(undo); clearStack(redo); if (mask != null && !mask.isRecycled()) mask.recycle(); }
        boolean empty() { int[] px = new int[mask.getWidth()]; for (int y = 0; y < mask.getHeight(); y += 4) { mask.getPixels(px, 0, mask.getWidth(), 0, y, mask.getWidth(), 1); for (int p : px) if ((p >>> 24) > 0) return false; } return true; }
        void save(File f) throws IOException {
            Bitmap out = Bitmap.createBitmap(mask.getWidth(), mask.getHeight(), Bitmap.Config.ARGB_8888); android.graphics.Canvas c = new android.graphics.Canvas(out);
            c.drawColor(0xFF000000); c.drawBitmap(mask, 0, 0, null);
            try (FileOutputStream o = new FileOutputStream(f)) { out.compress(Bitmap.CompressFormat.PNG, 100, o); } finally { out.recycle(); }
        }
    }
    void showMaskEditor(Bitmap base, File file, String title, String hintText, Runnable afterSave, Bitmap keep) {
        Bitmap old = file.exists() ? BitmapFactory.decodeFile(file.getPath()) : null;
        final MaskView mv = new MaskView(base, old);
        if (old != null) old.recycle();
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(16), dp(14), dp(16), dp(10));
        v.addView(text(title, 20, TXT, true));
        TextView hint = text(hintText, 12, DIM, false); hint.setPadding(0, dp(2), 0, dp(8)); v.addView(hint);
        v.addView(mv, new LinearLayout.LayoutParams(-1, dp(400)));
        LinearLayout modes = new LinearLayout(this); modes.setPadding(0, dp(8), 0, 0);
        final Button[] tb = { button(L.s("Кисть", "Brush"), FIELD, TXT), button(L.s("Ластик", "Eraser"), FIELD, TXT), button(L.s("Двигать", "Move"), FIELD, TXT) };
        final Runnable paintTools = () -> { for (int i = 0; i < tb.length; i++) { boolean on = mv.tool == i; tb[i].setBackground(bg(on ? SEL : FIELD, 14)); tb[i].setTextColor(on ? ACC : TXT); } };
        for (int i = 0; i < tb.length; i++) {
            final int t = i; tb[i].setTextSize(13); tb[i].setOnClickListener(x -> { mv.tool = t; paintTools.run(); });
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, dp(38), 1); if (i > 0) ml.leftMargin = dp(4); modes.addView(tb[i], ml);
        }
        paintTools.run(); v.addView(modes);
        LinearLayout tools = new LinearLayout(this); tools.setGravity(Gravity.CENTER_VERTICAL); tools.setPadding(0, dp(6), 0, 0);
        SeekBar sz = new SeekBar(this); sz.setMax(40); sz.setProgress(3); sz.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { mv.size = .005f + p * .005f; }
            public void onStartTrackingTouch(SeekBar b) { } public void onStopTrackingTouch(SeekBar b) { } });
        tools.addView(sz, new LinearLayout.LayoutParams(0, -2, 1));
        Button clr = button(L.s("Очистить", "Clear"), FIELD, TXT); clr.setTextSize(13); clr.setOnClickListener(x -> mv.clear());
        tools.addView(clr, new LinearLayout.LayoutParams(-2, dp(38)));
        v.addView(tools);
        LinearLayout editTools = new LinearLayout(this); editTools.setGravity(Gravity.CENTER_VERTICAL); editTools.setPadding(0, dp(6), 0, 0);
        Button undo = button(L.s("Deshacer", "Undo"), FIELD, TXT); undo.setTextSize(12); undo.setEnabled(false);
        Button redo = button(L.s("Rehacer", "Redo"), FIELD, TXT); redo.setTextSize(12); redo.setEnabled(false);
        TextView zoomLabel = text("1×", 12, TXT, true); zoomLabel.setGravity(Gravity.CENTER);
        Button zoomLess = button("−", FIELD, TXT); zoomLess.setTextSize(18);
        Button zoomMore = button("+", FIELD, TXT); zoomMore.setTextSize(18);
        LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(0, dp(38), 1); editTools.addView(undo, ctl);
        LinearLayout.LayoutParams ctl2 = new LinearLayout.LayoutParams(0, dp(38), 1); ctl2.leftMargin = dp(4); editTools.addView(redo, ctl2);
        LinearLayout.LayoutParams ctl3 = new LinearLayout.LayoutParams(dp(38), dp(38)); ctl3.leftMargin = dp(4); editTools.addView(zoomLess, ctl3);
        LinearLayout.LayoutParams ctl4 = new LinearLayout.LayoutParams(dp(42), dp(38)); editTools.addView(zoomLabel, ctl4);
        LinearLayout.LayoutParams ctl5 = new LinearLayout.LayoutParams(dp(38), dp(38)); editTools.addView(zoomMore, ctl5);
        undo.setOnClickListener(x -> { mv.undo(); undo.setEnabled(!mv.undo.isEmpty()); redo.setEnabled(!mv.redo.isEmpty()); });
        redo.setOnClickListener(x -> { mv.redo(); undo.setEnabled(!mv.undo.isEmpty()); redo.setEnabled(!mv.redo.isEmpty()); });
        clr.setOnClickListener(x -> { mv.clear(); undo.setEnabled(!mv.undo.isEmpty()); redo.setEnabled(!mv.redo.isEmpty()); });
        mv.historyChanged = () -> { undo.setEnabled(!mv.undo.isEmpty()); redo.setEnabled(!mv.redo.isEmpty()); };
        mv.zoomChanged = () -> zoomLabel.setText(String.format(java.util.Locale.US, "%.1f×", mv.zoom));
        zoomLess.setOnClickListener(x -> { mv.setZoom(mv.zoom / 1.25f); zoomLabel.setText(String.format(java.util.Locale.US, "%.1f×", mv.zoom)); });
        zoomMore.setOnClickListener(x -> { mv.setZoom(mv.zoom * 1.25f); zoomLabel.setText(String.format(java.util.Locale.US, "%.1f×", mv.zoom)); });
        zoomLabel.setOnClickListener(x -> { mv.setZoom(1f); zoomLabel.setText("1×"); });
        v.addView(editTools);
        LinearLayout bot = new LinearLayout(this); bot.setPadding(0, dp(10), 0, 0);
        Button cancel = button(L.s("Отмена", "Cancel"), FIELD, TXT); bot.addView(cancel, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button save = button(L.s("Сохранить", "Save"), ACC, ON_ACC); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(44), 1); sl.leftMargin = dp(8); bot.addView(save, sl);
        v.addView(bot);
        AlertDialog d = dialog(v);
        d.setOnDismissListener(x -> { mv.dispose(); if (base != keep && !base.isRecycled()) base.recycle(); });
        cancel.setOnClickListener(x -> d.dismiss());
        save.setOnClickListener(x -> {
            try { if (mv.empty()) file.delete(); else mv.save(file); } catch (IOException e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); }
            d.dismiss(); afterSave.run();
        });
    }
}
