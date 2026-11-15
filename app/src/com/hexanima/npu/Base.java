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
}
