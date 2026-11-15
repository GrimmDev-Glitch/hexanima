package com.hexanima.npu;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.File;
import java.util.Arrays;
import org.json.JSONObject;

/** Video (experimental): a prompt -> a 1 s clip with LTX-Video 2B on the NPU. The clips stay in files/ltx/videos; "Save to gallery" copies one to Movies/HexAnima. */
public class VideoActivity extends Base {
    final Handler ui = new Handler(Looper.getMainLooper());
    LinearLayout col, recent;
    EditText prompt, seed;
    VideoView player;
    TextView info, status;
    ProgressBar bar;
    Button gen;
    File shown;
    volatile boolean running, stop;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        col = screen(L.s("Видео", "Video"), null);
        if (!Ltx.installed(getFilesDir())) {
            LinearLayout c = card(col);
            c.addView(text(L.s("Модель LTX-Video 2B ещё не скачана (около 5,5 ГБ).", "The LTX-Video 2B model is not downloaded yet (about 5.5 GB)."), 15, TXT, false));
            Button m = button(L.s("Открыть «Модели»", "Open Models"), ACC, ON_ACC);
            m.setOnClickListener(v -> startActivity(new Intent(this, ModelsActivity.class)));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, dp(46)); ml.topMargin = dp(12); c.addView(m, ml);
            return;
        }
        info = text(L.s("Экспериментально: LTX-Video 2B, 512×320, 25 кадров (~1 с). Около 30 секунд на клип: текст на процессоре, кадры на NPU.",
                "Experimental: LTX-Video 2B, 512×320, 25 frames (~1 s). About 30 seconds per clip: the text on the CPU, the frames on the NPU."), 12, DIM, false);
        col.addView(info);

        player = new VideoView(this);
        FrameLayout pf = new FrameLayout(this); pf.setBackground(bg(CARD, 16)); pf.setPadding(dp(6), dp(6), dp(6), dp(6));
        pf.addView(player, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, w * Ltx.H / Ltx.W + dp(12)); pl.topMargin = dp(10);
        col.addView(pf, pl);
        player.setOnPreparedListener(mp -> { mp.setLooping(true); mp.setVolume(0, 0); });
        status = text("", 12, DIM, false); status.setPadding(dp(2), dp(6), 0, 0); col.addView(status);

        LinearLayout acts = new LinearLayout(this);
        Button save = button(L.s("В галерею", "Save to gallery"), CARD, TXT), del = button(L.s("Удалить", "Delete"), CARD, BAD);
        save.setOnClickListener(v -> { if (shown == null) return; try { Ltx.export(this, shown); Toast.makeText(this, L.s("Сохранено: Movies/HexAnima", "Saved: Movies/HexAnima"), Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); } });
        del.setOnClickListener(v -> { if (shown == null) return; shown.delete(); new File(shown.getPath().replace(".mp4", ".json")).delete(); shown = null; showLatest(); });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, dp(42), 1); al.rightMargin = dp(8); acts.addView(save, al);
        acts.addView(del, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams al2 = new LinearLayout.LayoutParams(-1, -2); al2.topMargin = dp(8); col.addView(acts, al2);

        col.addView(label(L.s("Описание", "Prompt")));
        prompt = field(L.s("Что происходит в клипе: кто, что делает, движение камеры…", "What happens in the clip: who, what they do, the camera movement…"));
        prompt.setMinLines(3); prompt.setGravity(android.view.Gravity.TOP); prompt.setText(getSharedPreferences("anima", 0).getString("ltx_prompt", ""));
        col.addView(prompt);
        TextView tip = text(L.s("Совет: описывайте движение простыми фразами на английском, например «A girl with white hair smiles and waves, cherry blossom petals falling, soft daylight».",
                "Tip: describe the motion in plain English sentences, for example \"A girl with white hair smiles and waves, cherry blossom petals falling, soft daylight\"."), 12, DIM, false);
        tip.setPadding(dp(2), dp(6), 0, 0); col.addView(tip);
        LinearLayout sr = new LinearLayout(this); sr.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sr.addView(text(L.s("Сид", "Seed"), 14, TXT, false), new LinearLayout.LayoutParams(dp(60), -2));
        seed = field(L.s("случайный", "random")); seed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); seed.setTextSize(15);
        sr.addView(seed, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(10); col.addView(sr, sl);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); bar.setVisibility(View.GONE);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACC));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(5)); bl.topMargin = dp(12); col.addView(bar, bl);
        gen = button(L.s("Создать видео", "Make a video"), ACC, ON_ACC);
        gen.setOnClickListener(v -> { if (running) stop = true; else generate(); });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(48)); gl.topMargin = dp(10); col.addView(gen, gl);

        col.addView(label(L.s("Клипы", "Clips")));
        recent = new LinearLayout(this); recent.setOrientation(LinearLayout.VERTICAL); col.addView(recent);
        TextView lic = text(L.s("LTX-Video © Lightricks, лицензия LTXV Open Weights (Инфо → Лицензии). Контексты NPU: timmyboy26.", "LTX-Video © Lightricks, LTXV Open Weights License (Info → Licenses). NPU contexts: timmyboy26."), 11, DIM, false);
        lic.setPadding(dp(2), dp(14), 0, 0); col.addView(lic);
        showLatest();
    }

    File[] clips() { File[] fs = Ltx.outDir(getFilesDir()).listFiles((d, n) -> n.endsWith(".mp4")); if (fs == null) fs = new File[0]; Arrays.sort(fs, (a, c) -> c.getName().compareTo(a.getName())); return fs; }

    void showLatest() { File[] fs = clips(); show(fs.length > 0 ? fs[0] : null); }

    void show(File f) {
        shown = f;
        if (f == null) { player.setVisibility(View.INVISIBLE); status.setText(L.s("Здесь появится видео", "Your video will appear here")); }
        else {
            player.setVisibility(View.VISIBLE); player.setVideoURI(Uri.fromFile(f)); player.start();
            String s = f.getName();
            try { JSONObject j = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(f.getPath().replace(".mp4", ".json")).toPath()), "UTF-8"));
                s = L.f("сид %d · %.1f с", "seed %d · %.1f s", j.optLong("seed"), j.optLong("ms") / 1000f) + "\n" + j.optString("prompt"); } catch (Exception ignored) { }
            status.setText(s);
        }
        recent.removeAllViews();
        File[] fs = clips();
        for (int i = 0; i < Math.min(12, fs.length); i++) {
            final File c = fs[i]; String p = c.getName();
            try { p = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(c.getPath().replace(".mp4", ".json")).toPath()), "UTF-8")).optString("prompt", p); } catch (Exception ignored) { }
            TextView t = text((c.equals(shown) ? "▶ " : "") + (p.length() > 70 ? p.substring(0, 70) + "…" : p), 13, c.equals(shown) ? ACC : TXT, false);
            t.setPadding(dp(12), dp(10), dp(12), dp(10)); t.setBackground(bg(CARD, 12)); t.setOnClickListener(v -> show(c));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.bottomMargin = dp(6); recent.addView(t, tl);
        }
        if (fs.length == 0) recent.addView(text(L.s("Пока нет клипов", "No clips yet"), 13, DIM, false));
    }

    void generate() {
        final String pr = prompt.getText().toString().trim();
        if (pr.isEmpty()) { Toast.makeText(this, L.s("Напишите описание", "Write a prompt"), Toast.LENGTH_SHORT).show(); return; }
        if (Upscaler.npuBusy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_LONG).show(); return; }
        getSharedPreferences("anima", 0).edit().putString("ltx_prompt", pr).apply();
        long sd; try { sd = Long.parseLong(seed.getText().toString().trim()); } catch (Exception e) { sd = new java.util.Random().nextInt(Integer.MAX_VALUE); }
        final long s = sd;
        running = true; stop = false; Upscaler.npuBusy = true;
        gen.setText(L.s("Отмена", "Cancel")); bar.setVisibility(View.VISIBLE); bar.setProgress(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        player.stopPlayback();
        new Thread(() -> {
            String err = null; File out = null;
            try {
                // the image server holds RAM and the NPU: stop it (the main screen shows the model as unloaded and loads it again on the next picture)
                File ad = new File(getFilesDir(), "anima");
                new ProcessBuilder("/system/bin/sh", "-c", "sh " + ad + "/anima.sh stop < /dev/null > /dev/null 2>&1").directory(ad).start().waitFor();
                Corrections.freedAnima = true;
                out = Ltx.generate(this, pr, s, (what, pm) -> ui.post(() -> { status.setText(what); bar.setProgress(pm); }), () -> stop);
            } catch (java.io.InterruptedIOException e) { err = L.s("Отменено", "Cancelled"); }
            catch (Exception e) { err = L.f("Ошибка: %s", "Error: %s", e.getMessage()); }
            final String msg = err; final File f = out;
            ui.post(() -> {
                running = false; Upscaler.npuBusy = false;
                gen.setText(L.s("Создать видео", "Make a video")); bar.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (msg != null) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); showLatest(); }
                else show(f);
            });
        }).start();
    }

    @Override public void onBackPressed() { if (running) { Toast.makeText(this, L.s("Сначала отмените создание видео", "Cancel the video first"), Toast.LENGTH_SHORT).show(); return; } super.onBackPressed(); }
}
