package com.hexanima.npu;

import android.content.res.ColorStateList;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Settings: language, theme, auto-load / idle unload of the model, screen on, quality-tag prefix. Everything is stored in the "anima" prefs and applied at once. */
public class Settings extends Base {
    static final String DEFAULT_PREFIX = "best quality, score_7, score_9, very aesthetic, ultra detailed, high contrast, anime coloring, ";
    /** the default up to 1.3.0 had "fair skin", which fights prompts asking for dark skin; a saved copy of that old default is upgraded */
    static final String OLD_DEFAULT_PREFIX = "best quality, score_7, score_9, very aesthetic, ultra detailed, fair skin, high contrast, anime coloring, ";
    static final int[] IDLE = {0, 3, 5, 10, 30};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("anima", 0); }
    static boolean autoload(Context c) { return prefs(c).getBoolean("autoload", false); }
    static int idleMin(Context c) { return prefs(c).getInt("idle", 10); }
    static boolean keepOn(Context c) { return prefs(c).getBoolean("keepon", true); }
    static String rawPrefix(Context c) { String p = prefs(c).getString("prefix", DEFAULT_PREFIX); return p.equals(OLD_DEFAULT_PREFIX) ? DEFAULT_PREFIX : p; }
    /** what goes before the prompt: trimmed, ending with a comma and a space; a single space when the user cleared it (anima.sh treats an empty PREFIX as "use the default") */
    static String prefix(Context c) { String p = rawPrefix(c).trim(); return p.isEmpty() ? " " : (p.endsWith(",") ? p : p + ",") + " "; }

    /* Sampling for the Anima models (sd.cpp names). The models are distilled for 4 steps, Euler, CFG 1 (the defaults); CFG > 1 runs the DiT twice per step
       and uses the negative prompt; 2nd-order samplers (heun, dpm2, dpm++2s_a, res_2s) also call it twice per step. Clip skip has no meaning here: the text encoder is Qwen3, not CLIP. */
    static final String[] SAMPLERS = {"euler", "euler_a", "dpm++2m", "dpm++2mv2", "dpm++2m_sde", "dpm++2m_sde_bt", "er_sde", "res_multistep", "ipndm", "ipndm_v", "lcm", "ddim_trailing", "tcd", "lms",
            "euler_cfg_pp", "euler_a_cfg_pp", "euler_ge", "heun", "dpm2", "dpm++2s_a", "res_2s"};
    static final String[] SAMPLER_NAMES = {"Euler", "Euler a", "DPM++ 2M", "DPM++ 2M v2", "DPM++ 2M SDE", "DPM++ 2M SDE (Brownian tree)", "ER-SDE", "RES multistep", "iPNDM", "iPNDM-v", "LCM", "DDIM trailing", "TCD", "LMS",
            "Euler CFG++", "Euler a CFG++", "Euler GE", "Heun (×2)", "DPM2 (×2)", "DPM++ 2S a (×2)", "RES 2S (×2)"};
    static final String[] SCHEDULERS = {"discrete", "sgm_uniform", "simple", "karras", "exponential", "beta", "kl_optimal", "smoothstep", "bong_tangent"};
    static final String[] SCHEDULER_NAMES = {"Normal (discrete)", "SGM Uniform", "Simple", "Karras", "Exponential", "Beta", "KL optimal", "Smoothstep", "Bong tangent"};
    static String samplerName(String s) { int i = java.util.Arrays.asList(SAMPLERS).indexOf(s); return i < 0 ? s : SAMPLER_NAMES[i]; }
    static String schedulerName(String s) { int i = java.util.Arrays.asList(SCHEDULERS).indexOf(s); return i < 0 ? s : SCHEDULER_NAMES[i]; }
    static int steps(Context c) { return prefs(c).getInt("steps", 4); }
    static String cfg(Context c) { return prefs(c).getString("cfg", "1"); }
    static boolean cfgOn(Context c) { try { return Float.parseFloat(cfg(c)) > 1.001f; } catch (Exception e) { return false; } }
    static String sampler(Context c) { return prefs(c).getString("sampler", "euler"); }
    static String scheduler(Context c) { return prefs(c).getString("sched", "discrete"); }
    static String negative(Context c) { return prefs(c).getString("neg", ""); }
    static boolean twoCalls(String sampler) { return sampler.equals("heun") || sampler.equals("dpm2") || sampler.equals("dpm++2s_a") || sampler.equals("res_2s"); }
    /** DiT calls per picture (progress estimate): CFG and 2nd-order samplers double them */
    static int calls(Context c) { return steps(c) * (cfgOn(c) ? 2 : 1) * (twoCalls(sampler(c)) ? 2 : 1); }
    static boolean defaultSampling(Context c) { return steps(c) == 4 && !cfgOn(c) && sampler(c).equals("euler") && scheduler(c).equals("discrete"); }

    EditText prefixEdit, keyEdit;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = screen(L.s("Настройки", "Settings"), null);
        SharedPreferences p = prefs(this);

        LinearLayout c1 = card(col);
        c1.addView(choice(L.s("Язык", "Language"), L.NAMES[L.index()], v -> {
            builder().setTitle(L.s("Язык", "Language")).setSingleChoiceItems(L.NAMES, L.index(), (d, i) -> { d.dismiss(); L.set(this, L.CODES[i]); recreate(); }).show();
        }));
        String[] tn = {L.s("Как в системе", "Follow system"), L.s("Светлая", "Light"), L.s("Тёмная", "Dark")}, tv = {"system", "light", "dark"};
        int ti = java.util.Arrays.asList(tv).indexOf(p.getString("theme", "system"));
        c1.addView(choice(L.s("Тема", "Theme"), tn[Math.max(ti, 0)], v -> {
            builder().setTitle(L.s("Тема", "Theme")).setSingleChoiceItems(tn, Math.max(ti, 0), (d, i) -> { d.dismiss(); p.edit().putString("theme", tv[i]).apply(); recreate(); }).show();
        }));

        LinearLayout c2 = card(col);
        c2.addView(toggle(L.s("Загружать модель при запуске", "Load the model at start"), L.s("Первая картинка появится быстрее, но модель занимает ОЗУ", "The first picture is faster, but the model holds RAM"), autoload(this), on -> p.edit().putBoolean("autoload", on).apply()));
        String[] in = new String[IDLE.length];
        for (int i = 0; i < IDLE.length; i++) in[i] = IDLE[i] == 0 ? L.s("Никогда", "Never") : L.f("%d мин", "%d min", IDLE[i]);
        int ii = Math.max(0, java.util.Arrays.binarySearch(IDLE, idleMin(this)));
        c2.addView(choice(L.s("Выгружать при простое", "Unload when idle"), in[ii], v -> {
            builder().setTitle(L.s("Выгружать при простое", "Unload when idle")).setSingleChoiceItems(in, ii, (d, i) -> { d.dismiss(); p.edit().putInt("idle", IDLE[i]).apply(); recreate(); }).show();
        }));
        TextView idleNote = text(L.s("Освобождает ОЗУ, если вы долго ничего не генерируете", "Frees RAM when you have not generated anything for a while"), 12, DIM, false); idleNote.setPadding(dp(2), 0, 0, dp(4));
        c2.addView(idleNote);

        LinearLayout c3 = card(col);
        c3.addView(toggle(L.s("Не гасить экран при генерации", "Keep the screen on while generating"), "", keepOn(this), on -> p.edit().putBoolean("keepon", on).apply()));

        LinearLayout cc = card(col);
        TextView ct = text(L.s("Ключ API Civitai", "Civitai API key"), 15, TXT, true); ct.setPadding(dp(2), dp(6), 0, dp(2)); cc.addView(ct);
        TextView cn = text(L.s("Для +18 и файлов «только после входа». civitai.com → аккаунт → API Keys. Хранится только на телефоне и отправляется только на civitai.com.",
                "For +18 and \"login required\" files. civitai.com → Account settings → API Keys. It stays on this phone and is only sent to civitai.com."), 12, DIM, false);
        cn.setPadding(dp(2), 0, 0, dp(6)); cc.addView(cn);
        keyEdit = field("…"); keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); keyEdit.setText(p.getString("civitai_key", "")); keyEdit.setTextSize(14);
        cc.addView(keyEdit);
        if (Moss.is(p.getString("model", ""))) return;   // MOSS-Image takes plain sentences: the quality-tag prefix is for the Anima models only, so the setting is hidden while MOSS is selected
        TextView pl = text(L.s("Теги качества перед описанием (модели Anima)", "Quality tags before the prompt (Anima models)"), 15, TXT, true); pl.setPadding(dp(2), dp(14), 0, dp(6)); c3.addView(pl);
        prefixEdit = field("");
        prefixEdit.setText(rawPrefix(this)); prefixEdit.setTextSize(13); prefixEdit.setMinLines(2); prefixEdit.setGravity(Gravity.TOP);
        prefixEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        c3.addView(prefixEdit);
        Button reset = button(L.s("Сбросить по умолчанию", "Reset to default"), FIELD, ACC); reset.setTextSize(13);
        reset.setOnClickListener(v -> prefixEdit.setText(DEFAULT_PREFIX));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, dp(38)); rl.topMargin = dp(8);
        c3.addView(reset, rl);
    }

    @Override protected void onPause() {
        super.onPause();
        if (prefixEdit != null) prefs(this).edit().putString("prefix", prefixEdit.getText().toString()).apply();
        if (keyEdit != null) prefs(this).edit().putString("civitai_key", keyEdit.getText().toString().trim()).apply();
    }

    View choice(String title, String value, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(2), dp(12), dp(2), dp(12));
        r.addView(text(title, 15, TXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(text(value + "  ▾", 14, ACC, false));
        r.setOnClickListener(l);
        return r;
    }

    interface OnToggle { void on(boolean v); }

    View toggle(String title, String sub, boolean on, OnToggle cb) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(2), dp(10), dp(2), dp(10));
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 15, TXT, true));
        if (!sub.isEmpty()) { TextView s = text(sub, 12, DIM, false); s.setPadding(0, dp(2), 0, 0); t.addView(s); }
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this); sw.setChecked(on);
        sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACC, DIM}));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{(ACC & 0x00FFFFFF) | 0x66000000, (DIM & 0x00FFFFFF) | 0x44000000}));
        sw.setOnCheckedChangeListener((v, c) -> cb.on(c));
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }
}
