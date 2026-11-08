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
        uiKey = L.lang + dark;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    /** language or theme changed on another screen: rebuild this one */
    @Override protected void onResume() {
        super.onResume();
        L.init(this); boolean d = isDark(this);
        if (!(L.lang + d).equals(uiKey)) recreate();
    }

    /** dialogs follow the theme */
    AlertDialog.Builder builder() { return new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert); }

    /** a dialog around a custom view, with the card background */
    AlertDialog dialog(View v) { AlertDialog d = builder().setView(v).create(); d.show(); d.getWindow().setBackgroundDrawable(bg(CARD, 22)); return d; }

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
        LinearLayout frame = new LinearLayout(this); frame.setOrientation(LinearLayout.VERTICAL); frame.setBackgroundColor(BG); frame.setFitsSystemWindows(true);
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
