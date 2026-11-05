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

/** Shared look (dark theme, rounded cards) and view builders for all screens; the UI is built in code, there are no layout files. */
abstract class Base extends Activity {
    /** palette: set by applyTheme() (dark by default, light, or follow the system) before any view is built */
    static int BG, CARD, FIELD, ACC, ACC2, ON_ACC, TXT, DIM, OK, BAD, WARN;
    static boolean dark = true;
    String uiKey = "";

    static boolean isDark(Context c) {
        String m = c.getSharedPreferences("anima", 0).getString("theme", "system");
        return m.equals("dark") || (m.equals("system") && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }

    static void applyTheme(Context c) {
        dark = isDark(c);
        if (dark) { BG = 0xFF0E1116; CARD = 0xFF171B22; FIELD = 0xFF0E1116; ACC = 0xFF7C8CFF; ACC2 = 0xFFA78BFA; ON_ACC = 0xFF0E1116; TXT = 0xFFE6EAF2; DIM = 0xFF8B94A7; OK = 0xFF4ADE80; BAD = 0xFFF87171; WARN = 0xFFFACC15; }
        else { BG = 0xFFF1F3F8; CARD = 0xFFFFFFFF; FIELD = 0xFFE8EBF2; ACC = 0xFF5B6CFF; ACC2 = 0xFF8B5CF6; ON_ACC = 0xFFFFFFFF; TXT = 0xFF171B22; DIM = 0xFF667085; OK = 0xFF16A34A; BAD = 0xFFDC2626; WARN = 0xFFB45309; }
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
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button button(String s, int color, int textColor) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(textColor);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setStateListAnimator(null); b.setBackground(bg(color, 14));
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
        EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(DIM); e.setTextColor(TXT); e.setTextSize(16);
        e.setBackground(bg(FIELD, 12)); e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    TextView label(String s) { TextView t = text(s, 12, DIM, true); t.setPadding(dp(2), dp(14), 0, dp(6)); t.setLetterSpacing(.06f); return t; }

    LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(bg(CARD, 20)); c.setPadding(dp(16), dp(14), dp(16), dp(14));
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
