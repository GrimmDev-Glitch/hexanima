package com.hexanima.npu;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Anima on the NPU: load/unload the model (animad server via anima.sh) and generate. No root: the bundled native binaries run from the app's own uid. */
public class Main extends Base {
    static final String DEFAULT_BASE = "https://huggingface.co/ayanoby/anima-npu-packs/resolve/main/";
    static final int STEPS = 4;   // the models are distilled for 4 steps (see Info)
    /** popular sizes, all valid for the NPU pipeline (multiples of 16, patch count divisible by 32, at most 6144); grouped by aspect ratio, a portrait and its landscape twin next to each other */
    static final int[][] SIZES = {{512, 512}, {640, 640}, {768, 768}, {1024, 1024}, {512, 768}, {768, 512}, {768, 1152}, {1152, 768}, {1024, 1536}, {1536, 1024}, {768, 1024}, {1024, 768}, {960, 1280}, {1280, 960},
        {576, 1024}, {1024, 576}, {640, 1152}, {1152, 640}, {960, 1536}, {1536, 960}};
    static volatile boolean visible;   // the main screen is in front (otherwise a finished picture is announced by a notification)

    String D, BIN;   // work dir (files/anima) and the native lib dir with the bundled executables
    Models store;
    java.util.List<String> ids = new java.util.ArrayList<>();   // installed model ids
    volatile int resW = 512, resH = 768;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    final ExecutorService ex2 = Executors.newSingleThreadExecutor();   // the Stop button
    volatile boolean loaded, busy, cancelled;
    String model = "";   // id of the selected installed model; anima.sh gets MID/MDIR
    int count = 1;       // pictures per press of Generate
    volatile int curIdx, curN = 1;
    boolean statusSeen;
    int tick;

    TextView chipName, chipDot, state, status, saved, placeholder, ramTxt, warnTxt, sizeText;
    LinearLayout customRow, imgBox;
    Button loadBtn, genBtn, countBtn;
    ImageButton dlBtn, shareBtn, favBtn, againBtn;
    MultiAutoCompleteTextView prompt;
    EditText seed, wEdit, hEdit;
    LinearLayout loraBox; TextView loraName, loraInfo; EditText strText; SeekBar strBar; Button loraPick, loraDrop;
    LinearLayout sampBox; EditText negEdit, stepsEdit, cfgEdit; Button samplerBtn, schedBtn; TextView sampNote;
    ProgressBar bar, ramBar;
    ImageView img;
    File lastFile;
    boolean customOpen;

    /** LoRAs called from the current prompt with <lora:name:weight> (file*weight items), set by runOne */
    List<String> tagLoras = new ArrayList<>();
    String mp() {
        // the selected LoRA and the prompt's <lora:...> ones are part of the loaded model: anima.sh reloads it when the list changes
        List<String> ls = new ArrayList<>();
        if (!isMoss() && loraFitsModel()) {
            if (loraOn()) ls.add(loraSrc().getPath() + "*" + String.format(java.util.Locale.US, "%.2f", loraStr()));
            for (String t : tagLoras) if (!ls.contains(t)) ls.add(t);
        }
        String lora = ls.isEmpty() ? "" : "LORA=" + q(String.join("|", ls)) + " ";
        return "MID=" + model + " MDIR=m_" + model + " IDLE=" + Settings.idleMin(this) * 60 + " " + lora;
    }

    GradientDrawable gradient(boolean enabled) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{enabled ? ACC : 0xFF3A4158, enabled ? ACC2 : 0xFF3A4158});
        g.setCornerRadius(dp(18)); return g;
    }
    SharedPreferences prefs() { return getSharedPreferences("anima", 0); }
    void setFavIcon(boolean on) { favBtn.setImageResource(getResources().getIdentifier(on ? "ic_star" : "ic_star_off", "drawable", getPackageName())); favBtn.setImageTintList(on ? null : ColorStateList.valueOf(TXT)); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        D = new File(getFilesDir(), "anima").getPath(); BIN = getApplicationInfo().nativeLibraryDir;
        store = new Models(getFilesDir());
        main.env = pollSh.env = ctlSh.env = new String[]{D, new File(getFilesDir(), "sd").getPath(), BIN};
        installScript();
        getWindow().setNavigationBarColor(CARD);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(20));
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        frame.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // header: title, history, menu
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.addView(text("HexAnima", 28, TXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button hist = button(L.s("История", "History"), CARD, TXT);
        hist.setTextSize(13);
        hist.setOnClickListener(v -> startActivityForResult(new Intent(this, History.class), 7));
        tr.addView(hist, new LinearLayout.LayoutParams(-2, dp(40)));
        Button more = button("⋯", CARD, TXT);
        more.setTextSize(20);
        more.setOnClickListener(v -> menu(v));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(48), dp(40)); ml.leftMargin = dp(6);
        tr.addView(more, ml);
        root.addView(tr);
        ramTxt = text("", 12, DIM, false);
        ramTxt.setPadding(dp(2), dp(10), 0, dp(4));
        root.addView(ramTxt);
        ramBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        ramBar.setMax(1000);
        ramBar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD));
        root.addView(ramBar, new LinearLayout.LayoutParams(-1, dp(5)));
        warnTxt = text("", 12, WARN, false);   // thermal / battery / unsupported chip
        warnTxt.setPadding(dp(2), dp(6), 0, 0);
        warnTxt.setVisibility(View.GONE);
        root.addView(warnTxt);

        // model chip + load/unload, then the state line
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(12), 0, 0);
        LinearLayout mc = new LinearLayout(this);
        mc.setGravity(Gravity.CENTER_VERTICAL);
        mc.setBackground(bg(CARD, 20));
        mc.setPadding(dp(14), dp(10), dp(14), dp(10));
        chipDot = text("●", 12, DIM, false);
        chipName = text("…", 14, TXT, true);
        chipName.setPadding(dp(8), 0, dp(6), 0); chipName.setMaxLines(2); chipName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mc.addView(chipDot); mc.addView(chipName, new LinearLayout.LayoutParams(0, -2, 1)); mc.addView(text("▾", 12, DIM, false));
        mc.setOnClickListener(v -> pickModel());
        head.addView(mc, new LinearLayout.LayoutParams(0, -2, 1));
        loadBtn = button(L.s("Загрузить", "Load"), CARD, TXT);
        loadBtn.setTextSize(13);
        loadBtn.setOnClickListener(v -> toggleModel());
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-2, dp(42)); lb.leftMargin = dp(8);
        head.addView(loadBtn, lb);
        root.addView(head);
        state = text(L.s("Проверяю…", "Checking…"), 12, DIM, false);
        state.setPadding(dp(4), dp(6), 0, dp(12));
        root.addView(state);

        // result card
        imgBox = new LinearLayout(this);
        imgBox.setOrientation(LinearLayout.VERTICAL);
        imgBox.setGravity(Gravity.CENTER);
        imgBox.setBackground(bg(CARD, 24));
        imgBox.setClipToOutline(true);
        imgBox.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(24)); }
        });
        placeholder = text(L.s("Здесь появится картинка", "Your picture will appear here"), 14, DIM, false);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(dp(24), dp(64), dp(24), dp(64));
        imgBox.addView(placeholder, new LinearLayout.LayoutParams(-1, -2));
        img = new ImageView(this);
        img.setAdjustViewBounds(true);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setVisibility(View.GONE);
        img.setOnClickListener(v -> { if (lastFile != null) Gallery.view(this, lastFile); });
        imgBox.addView(img, new LinearLayout.LayoutParams(-1, -2));
        root.addView(imgBox, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout resRow = new LinearLayout(this);
        resRow.setGravity(Gravity.CENTER_VERTICAL);
        saved = text("", 12, DIM, false);
        saved.setPadding(dp(4), dp(8), dp(8), 0);
        resRow.addView(saved, new LinearLayout.LayoutParams(0, -2, 1));
        againBtn = icon("ic_refresh", CARD);
        againBtn.setOnClickListener(v -> generate(true));   // the same prompt with a new random seed
        favBtn = icon("ic_star_off", CARD);
        favBtn.setOnClickListener(v -> { if (lastFile == null) return; boolean nv = !Gallery.fav(lastFile); Gallery.setFav(lastFile, nv); setFavIcon(nv); });
        dlBtn = icon("ic_download", CARD);
        dlBtn.setOnClickListener(v -> { if (lastFile == null) return; try { Gallery.export(this, lastFile); Toast.makeText(this, L.s("Сохранено в галерею: Pictures/Anima", "Saved to gallery: Pictures/Anima"), Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); } });
        shareBtn = icon("ic_share", CARD);
        shareBtn.setOnClickListener(v -> { if (lastFile != null) Gallery.share(this, lastFile); });
        for (ImageButton x : new ImageButton[]{againBtn, favBtn, dlBtn, shareBtn}) {
            x.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(40)); lp.leftMargin = dp(6);
            resRow.addView(x, lp);
        }
        root.addView(resRow);

        // prompt
        LinearLayout pl = new LinearLayout(this);
        pl.setGravity(Gravity.CENTER_VERTICAL);
        pl.addView(label(L.s("ОПИСАНИЕ", "PROMPT")), new LinearLayout.LayoutParams(0, -2, 1));
        TextView lib = text("LoRAs", 12, ACC, true);
        lib.setPadding(dp(8), dp(14), dp(8), dp(6));
        lib.setOnClickListener(v -> loraLibrary());
        pl.addView(lib);
        TextView tpl = text(L.s("Шаблоны", "Templates"), 12, ACC, true);
        tpl.setPadding(dp(8), dp(14), dp(8), dp(6));
        tpl.setOnClickListener(v -> templates());
        pl.addView(tpl);
        TextView clr = text(L.s("Очистить", "Clear"), 12, ACC, true);
        clr.setPadding(dp(8), dp(14), dp(2), dp(6));
        clr.setOnClickListener(v -> prompt.setText(""));
        pl.addView(clr);
        root.addView(pl);
        prompt = new MultiAutoCompleteTextView(this);   // Danbooru tag suggestions while typing, comma separated
        prompt.setHint("1girl, smile, outdoors, cherry blossoms…");
        prompt.setHintTextColor(DIM); prompt.setTextColor(TXT); prompt.setTextSize(16);
        prompt.setMinLines(3);
        prompt.setGravity(Gravity.TOP);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        prompt.setBackground(bg(CARD, 18));
        prompt.setPadding(dp(16), dp(14), dp(16), dp(14));
        prompt.setThreshold(2);
        prompt.setTokenizer(new MultiAutoCompleteTextView.CommaTokenizer());
        prompt.setDropDownBackgroundDrawable(bg(CARD, 12));
        prompt.setDropDownHeight(dp(240));
        root.addView(prompt, new LinearLayout.LayoutParams(-1, -2));
        loadTags();
        tokText = text("", 12, DIM, false); tokText.setGravity(Gravity.END); tokText.setPadding(0, dp(4), dp(6), 0);
        root.addView(tokText, new LinearLayout.LayoutParams(-1, -2));
        prompt.addTextChangedListener(watch(s -> countTokens()));
        countTokens();

        // sampling, right under the prompt (Anima models; stored in the same prefs as before): negative prompt, steps, CFG, clip skip, sampler, scheduler
        sampBox = new LinearLayout(this); sampBox.setOrientation(LinearLayout.VERTICAL);
        sampBox.addView(label(L.s("НЕГАТИВНЫЙ ПРОМПТ  ·  при CFG больше 1", "NEGATIVE PROMPT  ·  with CFG above 1")));
        negEdit = field("lowres, worst quality, bad anatomy…");
        negEdit.setText(Settings.negative(this)); negEdit.setMinLines(2); negEdit.setGravity(Gravity.TOP); negEdit.setBackground(bg(CARD, 14));
        negEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        negEdit.addTextChangedListener(watch(s -> prefs().edit().putString(Settings.key(this, "neg"), s).apply()));
        sampBox.addView(negEdit);
        LinearLayout nr = new LinearLayout(this); nr.setGravity(Gravity.CENTER_VERTICAL);
        stepsEdit = numField(nr, L.s("ШАГИ", "STEPS"), String.valueOf(Settings.steps(this)), false, s -> { try { int v = Integer.parseInt(s.trim()); if (v >= 1 && v <= 60) prefs().edit().putInt(Settings.key(this, "steps"), v).apply(); } catch (NumberFormatException e) { } });
        cfgEdit = numField(nr, "CFG", Settings.cfg(this), true, s -> { try { float v = Float.parseFloat(s.trim().replace(',', '.')); if (v >= 1 && v <= 30) prefs().edit().putString(Settings.key(this, "cfg"), s.trim().replace(',', '.')).apply(); } catch (NumberFormatException e) { } });
        sampBox.addView(nr);
        LinearLayout ar = new LinearLayout(this); ar.setGravity(Gravity.CENTER_VERTICAL); ar.setPadding(0, dp(8), 0, 0);
        samplerBtn = button("", CARD, TXT); samplerBtn.setAllCaps(false);
        samplerBtn.setOnClickListener(v -> pickFrom(L.s("Сэмплер", "Sampler"), Settings.SAMPLERS, Settings.SAMPLER_NAMES, Settings.sampler(this), x -> { prefs().edit().putString(Settings.key(this, "sampler"), x).apply(); refreshSampling(); }));
        ar.addView(samplerBtn, new LinearLayout.LayoutParams(0, dp(44), 1));
        schedBtn = button("", CARD, TXT); schedBtn.setAllCaps(false);
        schedBtn.setOnClickListener(v -> pickFrom(L.s("Планировщик", "Scheduler"), Settings.SCHEDULERS, Settings.SCHEDULER_NAMES, Settings.scheduler(this), x -> { prefs().edit().putString(Settings.key(this, "sched"), x).apply(); refreshSampling(); }));
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(0, dp(44), 1); scl.leftMargin = dp(8); ar.addView(schedBtn, scl);
        Button sreset = button("↺", CARD, TXT); sreset.setOnClickListener(v -> {
            prefs().edit().remove(Settings.key(this, "steps")).remove(Settings.key(this, "cfg")).remove(Settings.key(this, "sampler")).remove(Settings.key(this, "sched")).apply();
            sampFill = false; refreshSampling();
        });
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(dp(48), dp(44)); srl.leftMargin = dp(8); ar.addView(sreset, srl);
        sampBox.addView(ar);
        sampNote = text("", 12, DIM, false); sampNote.setPadding(dp(4), dp(6), dp(4), 0); sampBox.addView(sampNote);
        root.addView(sampBox);
        buildExtras(root);
        refreshSampling();

        // size: a drop-down button that opens a two-column picker, and the portrait <-> landscape swap
        root.addView(label(L.s("РАЗМЕР", "SIZE")));
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout sizeBtn = new LinearLayout(this);
        sizeBtn.setGravity(Gravity.CENTER_VERTICAL); sizeBtn.setBackground(bg(CARD, 16)); sizeBtn.setPadding(dp(16), 0, dp(16), 0);
        sizeText = text("", 15, TXT, true);
        sizeBtn.addView(sizeText, new LinearLayout.LayoutParams(0, -2, 1)); sizeBtn.addView(text("▾", 14, DIM, false));
        sizeBtn.setOnClickListener(v -> pickSize());
        sizeRow.addView(sizeBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button swap = button("⇄", CARD, TXT);
        swap.setTextSize(18);
        swap.setOnClickListener(v -> {
            int w = resW, h = resH;
            if (customRow.getVisibility() == View.VISIBLE) try { w = Integer.parseInt(wEdit.getText().toString().trim()); h = Integer.parseInt(hEdit.getText().toString().trim()); } catch (NumberFormatException e) { }
            setRes(h, w);
        });
        LinearLayout.LayoutParams sw = new LinearLayout.LayoutParams(dp(56), dp(48)); sw.leftMargin = dp(8);
        sizeRow.addView(swap, sw);
        root.addView(sizeRow);
        customRow = new LinearLayout(this);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setPadding(0, dp(10), 0, 0);
        wEdit = field(L.s("ширина", "width")); hEdit = field(L.s("высота", "height"));
        wEdit.setInputType(InputType.TYPE_CLASS_NUMBER); hEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        wEdit.setBackground(bg(CARD, 14)); hEdit.setBackground(bg(CARD, 14));
        customRow.addView(wEdit, new LinearLayout.LayoutParams(0, -2, 1));
        TextView times = text("×", 18, DIM, false); times.setGravity(Gravity.CENTER);
        customRow.addView(times, new LinearLayout.LayoutParams(dp(36), -2));
        customRow.addView(hEdit, new LinearLayout.LayoutParams(0, -2, 1));
        customRow.setVisibility(View.GONE);
        root.addView(customRow);

        // seed
        root.addView(label(L.s("SEED  ·  -1 = случайный", "SEED  ·  -1 = random")));
        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        seed = field("-1");
        seed.setText("-1");
        seed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        seed.setBackground(bg(CARD, 14));
        sr.addView(seed, new LinearLayout.LayoutParams(0, -2, 1));
        Button dice = button("🎲", CARD, TXT);
        dice.setOnClickListener(v -> seed.setText(String.valueOf(new Random().nextInt(1 << 30))));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(52), dp(44)); dl.leftMargin = dp(8);
        sr.addView(dice, dl);
        root.addView(sr);

        // MOSS-Image: user LoRA (picked file is checked at once; strength slider; folded with the turbo adapter on first use)
        loraBox = new LinearLayout(this); loraBox.setOrientation(LinearLayout.VERTICAL);
        loraBox.addView(label(L.s("LORA  ·  необязательно", "LORA  ·  optional")));
        LinearLayout lr = new LinearLayout(this); lr.setGravity(Gravity.CENTER_VERTICAL);
        loraName = text(L.s("не выбрана", "none"), 14, TXT, false); loraName.setSingleLine(true); loraName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        loraName.setBackground(bg(CARD, 16)); loraName.setPadding(dp(16), dp(12), dp(16), dp(12));
        lr.addView(loraName, new LinearLayout.LayoutParams(0, -2, 1));
        loraPick = button(L.s("Выбрать", "Choose"), CARD, TXT); loraPick.setOnClickListener(v -> pickLora());
        LinearLayout.LayoutParams pl2 = new LinearLayout.LayoutParams(-2, dp(44)); pl2.leftMargin = dp(8); lr.addView(loraPick, pl2);
        loraDrop = button("✕", CARD, TXT); loraDrop.setOnClickListener(v -> dropLora());
        LinearLayout.LayoutParams dl2 = new LinearLayout.LayoutParams(dp(48), dp(44)); dl2.leftMargin = dp(8); lr.addView(loraDrop, dl2);
        loraBox.addView(lr);
        loraInfo = text("", 12, DIM, false); loraInfo.setPadding(dp(4), dp(6), dp(4), 0); loraBox.addView(loraInfo);
        LinearLayout sr2 = new LinearLayout(this); sr2.setGravity(Gravity.CENTER_VERTICAL); sr2.setPadding(0, dp(6), 0, 0);
        sr2.addView(text(L.s("Сила", "Strength"), 14, TXT, false));
        strBar = new SeekBar(this); strBar.setMax(40); strBar.setProgress(Math.round(prefs().getFloat(lp() + "lora_str", 1f) * 20));
        sr2.addView(strBar, new LinearLayout.LayoutParams(0, -2, 1));
        strText = field("1.00"); strText.setTextSize(14); strText.setMinWidth(dp(64)); strText.setGravity(Gravity.CENTER); strText.setBackground(bg(CARD, 12));
        strText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        strText.setOnEditorActionListener((v, a, e) -> { applyStrText(); return false; });
        strText.setOnFocusChangeListener((v, f) -> { if (!f) applyStrText(); });
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-2, -2); stl.leftMargin = dp(8); sr2.addView(strText, stl);
        strBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { if (u) { strText.setText(String.format(java.util.Locale.US, "%.2f", p * 0.05f)); prefs().edit().putFloat(lp() + "lora_str", p * 0.05f).apply(); } }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        strText.setText(String.format(java.util.Locale.US, "%.2f", loraStr()));
        loraBox.addView(sr2);
        root.addView(loraBox);
        refreshLora();

        // sticky bottom bar: progress + status + [pictures count] [Generate / Stop]
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(CARD);
        bottom.setPadding(dp(16), dp(10), dp(16), dp(10));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(ACC));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(FIELD));
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(5)); bl.bottomMargin = dp(6);
        bottom.addView(bar, bl);
        status = text("", 13, DIM, false);
        status.setVisibility(View.GONE);
        status.setPadding(dp(2), 0, 0, dp(6));
        bottom.addView(status);
        LinearLayout brow = new LinearLayout(this);
        count = Math.max(1, Math.min(4, prefs().getInt("count", 1)));
        countBtn = button("×" + count, FIELD, TXT);
        countBtn.setTextSize(16);
        countBtn.setOnClickListener(v -> { count = count % 4 + 1; prefs().edit().putInt("count", count).apply(); countBtn.setText("×" + count); genBtn.setText(genLabel()); });
        LinearLayout.LayoutParams cb = new LinearLayout.LayoutParams(dp(64), dp(54)); cb.rightMargin = dp(8);
        brow.addView(countBtn, cb);
        genBtn = button("", ACC, ON_ACC);
        genBtn.setTextSize(17);
        genBtn.setOnClickListener(v -> { if (busy) cancel(); else generate(false); });
        brow.addView(genBtn, new LinearLayout.LayoutParams(0, dp(54), 1));
        bottom.addView(brow);
        frame.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        setContentView(frame);
        SharedPreferences p = prefs();   // restore the last session's inputs
        prompt.setText(p.getString("prompt", "")); seed.setText(p.getString("seed", "-1")); model = p.getString("model", "");
        refreshModels();
        setRes(p.getInt("w", 512), p.getInt("h", 768));
        refreshState();
        if (!p.getBoolean("welcomed", false)) startActivity(new Intent(this, Welcome.class));
    }

    /** the tag list for the suggestions is read in the background (8000 lines in assets/tags.txt) */
    void loadTags() {
        new Thread(() -> {
            final ArrayList<String> tags = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("tags.txt"), "UTF-8"))) { for (String l; (l = r.readLine()) != null; ) if (!l.isEmpty()) tags.add(l); } catch (IOException ignored) { }
            ui.post(() -> prompt.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, tags) {
                @Override public View getView(int i, View v, ViewGroup p) { View r = super.getView(i, v, p); ((TextView) r).setTextColor(TXT); ((TextView) r).setTextSize(15); r.setBackgroundColor(CARD); return r; }
            }));
        }).start();
    }

    @Override protected void onPause() {
        super.onPause();
        visible = false;
        ui.removeCallbacks(ramTick);
        prefs().edit().putString("prompt", prompt.getText().toString()).putString("seed", seed.getText().toString()).putString("model", model).putInt("w", resW).putInt("h", resH).apply();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(2);
        String del = ModelsActivity.deleted; ModelsActivity.deleted = "";
        if (!del.isEmpty() && del.equals(model) && loaded && !busy) { loaded = false; ex.submit(() -> su("sh " + D + "/anima.sh stop < /dev/null >/dev/null 2>&1", false)); }
        if (genBtn != null) { refreshModels(); ui.post(ramTick); }
        ex2.submit(() -> RamWidget.updateAll(getApplicationContext()));   // a widget placed before an update keeps its old buttons until it is redrawn
        if (android.os.Build.VERSION.SDK_INT >= 33 && prefs().getBoolean("welcomed", false) && !prefs().getBoolean("asked_notif", false)) {   // once, after the welcome screen
            prefs().edit().putBoolean("asked_notif", true).apply();
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5);
        }
    }

    /** every 2 s: RAM strip, thermal / battery / chip warnings; every 10 s the model state (the idle watchdog may have unloaded it) */
    final Runnable ramTick = new Runnable() {
        @Override public void run() {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            double g = 1024.0 * 1024 * 1024, tot = mi.totalMem / g, free = mi.availMem / g, used = tot - free;
            ramTxt.setText(L.f("ОЗУ  всего %.1f  ·  занято %.1f  ·  свободно %.1f ГБ", "RAM  total %.1f  ·  used %.1f  ·  free %.1f GB", tot, used, free));
            ramBar.setProgress((int) (1000 * used / tot));
            ramBar.setProgressTintList(ColorStateList.valueOf(free < 0.8 ? BAD : free < 1.5 ? WARN : ACC));
            List<String> w = new ArrayList<>();
            if (!Compat.chipAllowed(Main.this)) w.add(L.f("Чип %s не поддерживается", "Chip %s is not supported", Compat.soc()));
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) w.add(L.s("Телефон горячий: скорость снижена", "The phone is hot: speed is reduced"));
            if (pm.isPowerSaveMode()) w.add(L.s("Экономия заряда замедляет NPU", "Battery saver slows the NPU"));
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) { int lvl = bat.getIntExtra("level", -1) * 100 / Math.max(1, bat.getIntExtra("scale", 100)), st = bat.getIntExtra("status", 0); if (lvl >= 0 && lvl < 15 && st != 2 && st != 5) w.add(L.f("Низкий заряд батареи (%d%%)", "Low battery (%d%%)", lvl)); }
            warnTxt.setVisibility(w.isEmpty() ? View.GONE : View.VISIBLE);
            warnTxt.setText("⚠  " + android.text.TextUtils.join("  ·  ", w));
            if (++tick % 5 == 0 && !busy) refreshState();
            ui.postDelayed(this, 2000);
        }
    };

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == 9 && res == RESULT_OK && d != null && d.getData() != null) { importLora(d.getData()); return; }
        if (req == 11 && res == RESULT_OK && d != null && d.getData() != null) { importInit(d.getData()); return; }
        if (req == 12 && res == RESULT_OK && d != null && d.getData() != null) { importAd(d.getData()); return; }
        if (req == 13 && res == RESULT_OK && d != null && d.getData() != null) { importCheckpoint(d.getData()); return; }
        if (req == 7 && res == RESULT_OK && d != null) {
            prompt.setText(d.getStringExtra("prompt")); seed.setText(d.getStringExtra("seed"));
            customOpen = false;
            setRes(d.getIntExtra("w", 512), d.getIntExtra("h", 768));
        } else if (req == 8 && res == RESULT_OK && d != null && d.getData() != null) {
            try (InputStream in = getContentResolver().openInputStream(d.getData()); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] b = new byte[1 << 16]; int total = 0;
                for (int n; (n = in.read(b)) > 0 && total < (40 << 20); ) { bo.write(b, 0, n); total += n; }
                JSONObject m = PngMeta.settings(bo.toByteArray());
                if (m == null || m.optString("prompt").isEmpty()) { Toast.makeText(this, L.s("В этом файле нет настроек генерации", "This file has no generation settings"), Toast.LENGTH_LONG).show(); return; }
                prompt.setText(m.optString("prompt")); seed.setText(m.optString("seed", "-1"));
                customOpen = false; if (m.optInt("w") > 0) setRes(m.optInt("w"), m.optInt("h"));
                Toast.makeText(this, L.s("Настройки загружены из файла", "Settings loaded from the file"), Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); }
        }
    }


    // ---- user LoRA: MOSS-Image (MossLora, folded with its turbo adapter) or Anima 2B (AnimaLora, folded into the block weights at load); one LoRA per family ----
    /** prefs prefix of the selected model's family */
    String lp() { return isMoss() ? "moss_" : "anima_"; }
    File loraSrc() { return isMoss() ? MossLora.src(getFilesDir()) : new File(AnimaLora.dir(getFilesDir()), prefs().getString("anima_lora_file", "user.safetensors")); }
    File loraDir() { return isMoss() ? MossLora.dir(getFilesDir()) : AnimaLora.dir(getFilesDir()); }
    String loraId() { return prefs().getString(lp() + "lora_id", ""); }
    /** Anima LoRAs fit the 2B models only; the 2.9B ones hold q8 weights (and another base) */
    /** every Anima model takes Anima LoRAs: 2B (28-block) LoRAs on the 2.9B models and 2.9B (40-block) ones on the 2B models are remapped block by block
     *  in megarun (the 2.9B models are the 2B ones with 12 blocks doubled, see lora_fold.h) */
    boolean loraFitsModel() { return true; }
    boolean loraOn() { return !loraId().isEmpty() && loraSrc().exists() && loraStr() > 0 && loraFitsModel(); }
    void refreshLora() {
        boolean has = !loraId().isEmpty() && loraSrc().exists();
        loraName.setText(has ? prefs().getString(lp() + "lora_name", "LoRA") : L.s("не выбрана", "none"));
        String hint = isMoss() ? L.s("Файл .safetensors для MOSS-Image: проверяется сразу после выбора", "A .safetensors file for MOSS-Image: it is checked as soon as you pick it")
                : L.s("LoRA для Anima 2B (.safetensors): проверяется сразу после выбора; новая LoRA или сила перезагружает модель", "A LoRA for Anima 2B (.safetensors): checked as soon as you pick it; a new LoRA or strength reloads the model");
        String info = has ? prefs().getString(lp() + "lora_info", "") : hint;
        if (has && !isMoss() && store.is29(model)) info += "\n" + L.s("Модель 2.9B: LoRA для 2B переносится по блокам (загрузка модели дольше)", "2.9B model: a 2B LoRA is remapped block by block (the model loads a bit longer)");
        loraInfo.setText(info);
        loraInfo.setTextColor(has ? (loraFitsModel() ? OK : WARN) : DIM);
        loraDrop.setVisibility(has ? View.VISIBLE : View.GONE); strBar.setEnabled(has); strBar.setAlpha(has ? 1f : .4f); strText.setEnabled(has);
        strBar.setProgress(Math.round(Math.min(loraStr(), 2f) * 20));
        if (!strText.hasFocus()) strText.setText(String.format(java.util.Locale.US, "%.2f", loraStr()));
    }
    float loraStr() { return prefs().getFloat(lp() + "lora_str", 1f); }
    /** typed strength (0..3; the slider shows 0..2) */
    void applyStrText() {
        try { float v = Float.parseFloat(strText.getText().toString().trim().replace(',', '.')); v = Math.max(0f, Math.min(3f, v)); prefs().edit().putFloat(lp() + "lora_str", v).apply(); } catch (NumberFormatException ignored) { }
        strBar.setProgress(Math.round(Math.min(loraStr(), 2f) * 20)); strText.setText(String.format(java.util.Locale.US, "%.2f", loraStr()));
    }

    /** LoRA library of the selected family: tap inserts <lora:name:1.0> and its trigger words into the prompt; long tap removes the file; Civitai to find more */
    void loraLibrary() {
        final File dir = AnimaLora.dir(getFilesDir());
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(8));
        v.addView(text(L.s("Библиотека LoRA (Anima 2B)", "LoRA library (Anima 2B)"), 18, TXT, true));
        TextView hint = text(L.s("Нажмите: вставить в промпт. Долгое нажатие: удалить.", "Tap: insert into the prompt. Long tap: delete."), 12, DIM, false); hint.setPadding(0, dp(6), 0, dp(8)); v.addView(hint);
        ScrollView sv = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, dp(320)));
        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        List<String> names = new ArrayList<>(); File[] lf = dir.listFiles();
        if (lf != null) for (File f : lf) if (f.getName().endsWith(".safetensors") && !f.getName().startsWith("new.")) names.add(f.getName().substring(0, f.getName().length() - 12));
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) list.addView(text(L.s("Пусто: скачайте LoRA с Civitai или выберите файл ниже на главном экране.", "Empty: download LoRAs from Civitai or pick a file on the main screen."), 14, DIM, false));
        for (String n : names) {
            File f = new File(dir, n + ".safetensors"); JSONObject md = Civitai.meta(f); String words = Civitai.words(f);
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setBackground(bg(FIELD, 12)); row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.addView(text(md != null ? md.optString("name") + " · " + md.optString("version") : n, 14, TXT, true));
            TextView sub = text("<lora:" + n + ":1.0>" + (words.isEmpty() ? "" : "\n" + words), 12, DIM, false); sub.setMaxLines(3); row.addView(sub);
            row.setOnClickListener(x -> {
                String add = "<lora:" + n + ":1.0>" + (words.isEmpty() ? "" : ", " + words);
                String cur = prompt.getText().toString().trim();
                prompt.setText(cur.isEmpty() ? add : cur + (cur.endsWith(",") ? " " : ", ") + add); prompt.setSelection(prompt.getText().length());
                if (dlg[0] != null) dlg[0].dismiss();
            });
            row.setOnLongClickListener(x -> { builder().setMessage(L.f("Удалить %s?", "Delete %s?", n)).setPositiveButton(L.s("Удалить", "Delete"), (d, i) -> { f.delete(); Civitai.metaFile(f).delete(); if (dlg[0] != null) dlg[0].dismiss(); loraLibrary(); }).setNegativeButton(L.s("Отмена", "Cancel"), null).show(); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); list.addView(row, lp);
        }
        dlg[0] = builder().setView(v).setPositiveButton(L.s("Найти на Civitai", "Find on Civitai"), (d, i) -> startActivity(new Intent(this, CivitaiActivity.class))).setNegativeButton(L.s("Закрыть", "Close"), null).show();
    }

    // ---- sampling fields under the prompt ----
    interface OnText { void on(String s); }
    static android.text.TextWatcher watch(OnText cb) {
        return new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) { cb.on(s.toString()); }
        };
    }
    /** a labelled number field added to row: [LABEL / field] */
    EditText numField(LinearLayout row, String title, String value, boolean decimal, OnText cb) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.addView(label(title));
        EditText e = field(""); e.setText(value); e.setBackground(bg(CARD, 14)); e.setGravity(Gravity.CENTER);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.addTextChangedListener(watch(s -> { cb.on(s); refreshSampling(); }));
        c.addView(e);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); if (row.getChildCount() > 0) lp.leftMargin = dp(8);
        row.addView(c, lp);
        return e;
    }
    void pickFrom(String title, String[] values, String[] names, String cur, OnText cb) {
        int i = Math.max(0, java.util.Arrays.asList(values).indexOf(cur));
        builder().setTitle(title).setSingleChoiceItems(names, i, (d, k) -> { d.dismiss(); cb.on(values[k]); }).show();
    }
    // ---- extras: hires fix, AI upscale, ADetailer, init image (img2img), presets, queue ----
    static final String UPSCALER_URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth";
    static final String UPSCALER_SHA = "f872d837d3c90ed2e05227bed711af5671a6fd1c9f7d7e91c911a61f155e99da";
    File upscaler() { return new File(getFilesDir(), "sd/upscalers/RealESRGAN_x4plus_anime_6B.pth"); }
    File initSrc() { return new File(getFilesDir(), "init_src.png"); }
    File adDir() { return new File(getFilesDir(), "adetailer"); }
    LinearLayout extrasBox; TextView initText, adText, queueText; EditText strEdit, hsEdit, hdEdit, hnEdit;
    final List<JSONObject> queue = new ArrayList<>(); Runnable afterGen;

    Switch sw(LinearLayout parent, String title, String sub, String key, boolean def) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(2), dp(8), dp(2), dp(4));
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 15, TXT, true)); if (!sub.isEmpty()) { TextView s = text(sub, 12, DIM, false); t.addView(s); }
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Switch s = new Switch(this); s.setChecked(prefs().getBoolean(key, def)); s.setOnCheckedChangeListener((v, on) -> { prefs().edit().putBoolean(key, on).apply(); refreshExtras(); });
        r.addView(s); r.setOnClickListener(v -> s.toggle()); parent.addView(r);
        return s;
    }
    EditText smallField(LinearLayout row, String title, String key, String def, boolean decimal) {
        return numField(row, title, prefs().getString(key, def), decimal, s -> { String v = s.trim().replace(',', '.'); try { Float.parseFloat(v); prefs().edit().putString(key, v).apply(); } catch (NumberFormatException ignored) { } });
    }
    void buildExtras(LinearLayout root) {
        extrasBox = new LinearLayout(this); extrasBox.setOrientation(LinearLayout.VERTICAL);
        extrasBox.addView(label(L.s("ДОПОЛНИТЕЛЬНО", "EXTRAS")));
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(bg(CARD, 18)); c.setPadding(dp(14), dp(6), dp(14), dp(12));
        sw(c, "Hires fix", L.s("Второй проход в большем размере (дольше)", "A second pass at a larger size (slower)"), "x_hires", false);
        LinearLayout hr = new LinearLayout(this);
        hsEdit = smallField(hr, L.s("МАСШТАБ", "SCALE"), "x_hires_scale", "1.5", true);
        hdEdit = smallField(hr, "DENOISE", "x_hires_den", "0.45", true);
        hnEdit = smallField(hr, L.s("ШАГИ", "STEPS"), "x_hires_steps", "4", false);
        c.addView(hr);
        sw(c, L.s("Увеличить ×2 (ИИ)", "Upscale ×2 (AI)"), L.s("RealESRGAN anime на процессоре, ~80 с, до 512×768; 18 МБ при первом использовании. Быстрее: hires fix (NPU)",
            "RealESRGAN anime on the CPU, ~80 s, up to 512×768; 18 MB on first use. Faster: the hires fix (NPU)"), "x_up", false);
        sw(c, L.s("ADetailer (лица)", "ADetailer (faces)"), L.s("Находит лица и перерисовывает их детальнее", "Finds faces and redraws them in more detail"), "x_ad", false);
        LinearLayout ar = new LinearLayout(this); ar.setGravity(Gravity.CENTER_VERTICAL);
        adText = text("", 12, DIM, false); ar.addView(adText, new LinearLayout.LayoutParams(0, -2, 1));
        Button adPick = button(L.s("Детектор…", "Detector…"), FIELD, ACC); adPick.setTextSize(12);
        adPick.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 12));
        ar.addView(adPick, new LinearLayout.LayoutParams(-2, dp(36))); c.addView(ar);
        c.addView(label(L.s("ИСХОДНАЯ КАРТИНКА (img2img)", "INIT IMAGE (img2img)")));
        LinearLayout ir = new LinearLayout(this); ir.setGravity(Gravity.CENTER_VERTICAL);
        initText = text("", 13, TXT, false); ir.addView(initText, new LinearLayout.LayoutParams(0, -2, 1));
        Button ip = button(L.s("Выбрать", "Choose"), FIELD, ACC); ip.setTextSize(12);
        ip.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 11));
        ir.addView(ip, new LinearLayout.LayoutParams(-2, dp(36)));
        Button ix = button("✕", FIELD, TXT); ix.setOnClickListener(v -> { initSrc().delete(); refreshExtras(); });
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(dp(40), dp(36)); xl.leftMargin = dp(6); ir.addView(ix, xl);
        c.addView(ir);
        LinearLayout sr = new LinearLayout(this); strEdit = smallField(sr, L.s("СИЛА ИЗМЕНЕНИЯ (0..1)", "DENOISE STRENGTH (0..1)"), "x_strength", "0.6", true); c.addView(sr);
        LinearLayout pr = new LinearLayout(this); pr.setPadding(0, dp(10), 0, 0);
        Button presets = button(L.s("Пресеты", "Presets"), FIELD, ACC); presets.setTextSize(13); presets.setOnClickListener(v -> presets());
        pr.addView(presets, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button qa = button(L.s("+ В очередь", "+ Queue"), FIELD, ACC); qa.setTextSize(13); qa.setOnClickListener(v -> enqueue());
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, dp(40), 1); ql.leftMargin = dp(8); pr.addView(qa, ql);
        Button qr = button("▶", FIELD, ACC); qr.setOnClickListener(v -> runQueue());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(48), dp(40)); rl.leftMargin = dp(8); pr.addView(qr, rl);
        c.addView(pr);
        queueText = text("", 12, DIM, false); queueText.setPadding(dp(2), dp(6), 0, 0); queueText.setOnClickListener(v -> showQueue()); c.addView(queueText);
        extrasBox.addView(c);
        root.addView(extrasBox);
        try { JSONArray a = new JSONArray(prefs().getString("queue", "[]")); for (int i = 0; i < a.length(); i++) queue.add(a.getJSONObject(i)); } catch (Exception ignored) { }
        refreshExtras();
    }
    File adModel() { File[] fs = adDir().listFiles(); if (fs != null) for (File f : fs) if (f.getName().endsWith(".safetensors")) return f; return null; }
    /** face_yolov8n (Bingsu/adetailer) converted for sd.cpp, hosted with the V73 packs; downloaded on first use unless the user picked another detector */
    static final String AD_URL = "https://huggingface.co/GrimmDev-Glitch/hexanima-packs/resolve/main/adetailer/face_yolov8n.safetensors";
    static final String AD_SHA = "aa44f31aed57284eec9fe6c8152c03e7625424f78ef276868baae7efc952c3d0";
    File ensureAd(long t0) {
        File f = adModel(); if (f != null) return f;
        setProgressUi(1, L.s("Загрузка детектора лиц (6 МБ)…", "Downloading the face detector (6 MB)…"), t0);
        try { adDir().mkdirs(); File out = new File(adDir(), "face_yolov8n.safetensors"); Models.fetch(AD_URL, out, -1, AD_SHA, null, new java.util.concurrent.atomic.AtomicBoolean()); ui.post(this::refreshExtras); return out; }
        catch (Exception e) { ui.post(() -> Toast.makeText(this, "ADetailer: " + e.getMessage(), Toast.LENGTH_LONG).show()); return null; }
    }
    void refreshExtras() {
        if (extrasBox == null) return;
        extrasBox.setVisibility(isMoss() || model.isEmpty() ? View.GONE : View.VISIBLE);
        initText.setText(initSrc().exists() ? L.s("✓ выбрана (подгоняется под размер)", "✓ chosen (fitted to the size)") : L.s("нет: обычная генерация", "none: normal generation"));
        File ad = adModel();
        adText.setText(ad != null ? "✓ " + ad.getName() : L.s("Детектор лиц face_yolov8n загрузится при первом использовании (6 МБ)", "The face detector face_yolov8n downloads on first use (6 MB)"));
        queueText.setText(queue.isEmpty() ? L.s("Очередь пуста", "Queue is empty") : L.f("В очереди: %d (нажмите, чтобы посмотреть)", "Queued: %d (tap to see)", queue.size()));
    }
    float pf(String key, float def) { try { return Float.parseFloat(prefs().getString(key, String.valueOf(def))); } catch (Exception e) { return def; } }
    /** hires target: about scale × the size, sides multiples of 16 with a patch count the NPU blocks run (multiple of 32, at most 6144) */
    int[] hiresSize(int w, int h) {
        double s = Math.max(1.0, Math.min(3.0, pf("x_hires_scale", 1.5f)));
        for (; s > 1.0; s -= 0.05) {
            int w2 = (int) Math.round(w * s / 16) * 16, h2 = (int) Math.round(h * s / 16) * 16;
            if (sizeError(w2, h2) == null) return new int[]{w2, h2};
        }
        return null;
    }
    /** writes the init image fitted (scale + center crop) to w x h; null if none */
    File initFor(int w, int h) {
        if (!initSrc().exists()) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(initSrc().getPath()); if (b == null) return null;
            float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            Bitmap sc = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            Bitmap cr = Bitmap.createBitmap(sc, (sc.getWidth() - w) / 2, (sc.getHeight() - h) / 2, w, h);
            File out = new File(getFilesDir(), "init_" + w + "x" + h + ".png");
            try (FileOutputStream o = new FileOutputStream(out)) { cr.compress(Bitmap.CompressFormat.PNG, 100, o); }
            return out;
        } catch (Exception e) { return null; }
    }
    /** the upscaler model, downloaded once (null and a toast on failure) */
    File ensureUpscaler(long t0) {
        File u = upscaler(); if (u.exists()) return u;
        setProgressUi(1, L.s("Загрузка модели увеличения (18 МБ)…", "Downloading the upscaler (18 MB)…"), t0);
        try { Models.fetch(UPSCALER_URL, u, -1, UPSCALER_SHA, null, new java.util.concurrent.atomic.AtomicBoolean()); return u; }
        catch (Exception e) { ui.post(() -> Toast.makeText(this, "RealESRGAN: " + e.getMessage(), Toast.LENGTH_LONG).show()); return null; }
    }
    /** AI upscale ×2 on the CPU: RealESRGAN anime is a ×4 model, so the picture is halved first (a quarter of the work: ~80 s for 512×768 on the
     *  Poco F6 Pro; ×4 of the full picture took ~5 min). Only up to 512×768: larger pictures would pass 2 minutes (the hires fix on the NPU is the fast way). */
    byte[] upscale2x(byte[] png, int w, int h, long t0) {
        if ((long) w * h > 512L * 768) { ui.post(() -> Toast.makeText(this, L.s("Увеличение пропущено: только до 512×768 (используйте hires fix)", "Upscale skipped: up to 512×768 only (use the hires fix)"), Toast.LENGTH_LONG).show()); return null; }
        File u = ensureUpscaler(t0); if (u == null) return null;
        setProgressUi(96, L.s("Увеличение ×2 (процессор, ~80 с)…", "Upscaling ×2 (CPU, ~80 s)…"), t0);
        try {
            Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length), half = Bitmap.createScaledBitmap(b, w / 2, h / 2, true);
            File in = new File(D, "up_in.png"), out = new File(D, "up_out.png"); out.delete();
            try (FileOutputStream o = new FileOutputStream(in)) { half.compress(Bitmap.CompressFormat.PNG, 100, o); }
            su("cd " + D + " && LD_LIBRARY_PATH=\"$BIN:/system/lib64\" \"$BIN/libsdcli.so\" -M upscale --upscale-model " + q(u.getPath()) + " -i up_in.png -o up_out.png -t 5 --upscale-tile-size 256 < /dev/null > upscale.log 2>&1", false);
            in.delete();
            if (cancelled || !out.exists()) return null;
            byte[] r = Models.readBytes(out); out.delete();
            return BitmapFactory.decodeByteArray(r, 0, r.length) != null ? r : null;
        } catch (Exception e) { return null; }
    }
    /** anima.sh environment for the extras that are on */
    boolean memOk, hotStop;   // the memory warning was answered / a batch stopped because the phone got too hot
    long availMb() { ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi); return mi.availMem / 1048576; }
    boolean tooHot() { return ((PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE; }
    String extrasEnv(int w, int h, long t0) {
        StringBuilder e = new StringBuilder();
        if (availMb() < 3000 && (prefs().getBoolean("x_hires", false) || prefs().getBoolean("x_up", false))) {   // the larger passes need room: skip them rather than swap
            ui.post(() -> Toast.makeText(this, L.s("Мало памяти: hires fix и увеличение пропущены для этой картинки", "Low memory: hires fix and upscale skipped for this picture"), Toast.LENGTH_LONG).show());
            File init = initFor(w, h); if (init != null) e.append("INIT=").append(q(init.getPath())).append(" STRENGTH=").append(pf("x_strength", .6f)).append(" ");
            return e.toString();
        }
        if (prefs().getBoolean("x_hires", false)) {
            int[] hs = hiresSize(w, h);
            if (hs != null) e.append("HIRES=1 HIRES_W=").append(hs[0]).append(" HIRES_H=").append(hs[1]).append(" HIRES_DENOISE=").append(pf("x_hires_den", .45f)).append(" HIRES_STEPS=").append((int) pf("x_hires_steps", 4)).append(" ");
            else ui.post(() -> Toast.makeText(this, L.s("Hires fix: этот размер уже максимальный для NPU", "Hires fix: this size is already the NPU's largest"), Toast.LENGTH_LONG).show());
        }
        if (prefs().getBoolean("x_ad", false)) { File ad = ensureAd(t0); if (ad != null) e.append("AD=").append(q(ad.getPath())).append(" "); }
        File init = initFor(w, h); if (init != null) e.append("INIT=").append(q(init.getPath())).append(" STRENGTH=").append(pf("x_strength", .6f)).append(" ");
        return e.toString();
    }
    /** a local Anima checkpoint: copied into the app, converted on the phone (AnimaImport), then selectable like the catalog models */
    void importCheckpoint(Uri uri) {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        String nm = "Anima";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) { if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) nm = c.getString(i); } } catch (Exception ignored) { }
        final String name = nm.replaceAll("(?i)\\.safetensors$", "");
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(16), dp(20), dp(8));
        v.addView(text(name, 14, TXT, true));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000); v.addView(pb);
        TextView pt = text(L.s("Копирование…", "Copying…"), 12, DIM, false); v.addView(pt);
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        AlertDialog dl = builder().setTitle(L.s("Импорт модели", "Importing a model")).setView(v).setCancelable(false).setNegativeButton(L.s("Отмена", "Cancel"), (d, i) -> stop.set(true)).show();
        if (loaded) ex.submit(() -> { su("sh " + D + "/anima.sh stop < /dev/null >/dev/null 2>&1", false); loaded = false; setModelUi(false, false, ""); });   // the conversion needs the RAM
        ex2.submit(() -> {
            File dir = AnimaImport.importDir(getFilesDir()); dir.mkdirs(); File src = new File(dir, AnimaLora.fileName(name));
            String err = null;
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream o = new FileOutputStream(src)) {
                long total = -1; try (android.content.res.AssetFileDescriptor a = getContentResolver().openAssetFileDescriptor(uri, "r")) { if (a != null) total = a.getLength(); } catch (Exception ignored) { }
                byte[] b = new byte[1 << 16]; long done = 0, last = 0; final long tt = total;
                for (int n; (n = in.read(b)) > 0; ) { if (stop.get()) throw new IOException(L.s("Отменено", "Cancelled")); o.write(b, 0, n); done += n; if (done - last > (32 << 20)) { last = done; final long dd = done; ui.post(() -> { if (tt > 0) pb.setProgress((int) (1000 * dd / tt)); pt.setText(String.format(java.util.Locale.US, "%.1f GB", dd / 1e9)); }); } }
            } catch (Exception e) { err = e.getMessage(); }
            if (err == null) err = AnimaImport.convert(getFilesDir(), BIN, DEFAULT_BASE, src, "cv" + Long.toHexString(System.currentTimeMillis()), name,
                (d, t, what) -> ui.post(() -> { pb.setProgress(1000 * d / Math.max(1, t)); pt.setText(what + " " + d + "/" + t); }), stop);
            if (err != null) src.delete();
            final String e2 = err;
            ui.post(() -> {
                dl.dismiss();
                if (e2 != null) builder().setTitle(L.s("Не удалось импортировать", "Import failed")).setMessage(e2).setPositiveButton("OK", null).show();
                else { refreshModels(); builder().setTitle(name).setMessage("✓ " + L.s("Модель установлена: выберите её в списке моделей. Если это не «turbo»-модель, используйте 20–30 шагов и CFG 4–5 (или LoRA turbo для 2B).",
                    "Model installed: pick it in the model list. If it is not a \"turbo\" model, use 20–30 steps and CFG 4–5 (or a turbo LoRA on the 2B ones).")).setPositiveButton("OK", null).show(); }
            });
        });
    }
    void importInit(Uri uri) {
        ex2.submit(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Bitmap b = BitmapFactory.decodeStream(in); if (b == null) throw new IOException("not an image");
                try (FileOutputStream o = new FileOutputStream(initSrc())) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()); }
            ui.post(this::refreshExtras);
        });
    }
    void importAd(Uri uri) {
        ex2.submit(() -> {
            try { adDir().mkdirs(); File[] old = adDir().listFiles(); if (old != null) for (File f : old) f.delete();
                try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream o = new FileOutputStream(new File(adDir(), "detector.safetensors"))) { byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()); }
            ui.post(this::refreshExtras);
        });
    }
    // presets: named snapshots of the sampling fields, the negative prompt, the quality tags and the extras (per model family)
    static final String[] PRESET_KEYS = {"steps", "cfg", "sampler", "sched", "neg"};
    void presets() {
        JSONObject all; try { all = new JSONObject(prefs().getString("presets_anima", "{}")); } catch (Exception e) { all = new JSONObject(); }
        final JSONObject ps = all;
        List<String> names = new ArrayList<>(); for (Iterator<String> it = ps.keys(); it.hasNext(); ) names.add(it.next()); java.util.Collections.sort(names);
        String[] items = new String[names.size() + 1]; items[0] = "＋  " + L.s("Сохранить текущие настройки…", "Save the current settings…");
        for (int i = 0; i < names.size(); i++) items[i + 1] = names.get(i);
        builder().setTitle(L.s("Пресеты", "Presets")).setItems(items, (d, i) -> {
            if (i == 0) {
                EditText nm = field(L.s("Название", "Name"));
                builder().setTitle(L.s("Сохранить пресет", "Save a preset")).setView(nm).setPositiveButton("OK", (d2, k) -> {
                    try {
                        JSONObject p = new JSONObject();
                        for (String key : PRESET_KEYS) { String fk = Settings.key(this, key); if (prefs().getAll().containsKey(fk)) p.put(key, String.valueOf(prefs().getAll().get(fk))); }
                        p.put("prefix", Settings.rawPrefix(this));
                        for (String x : new String[]{"x_hires", "x_up", "x_ad"}) p.put(x, prefs().getBoolean(x, false));
                        for (String x : new String[]{"x_hires_scale", "x_hires_den", "x_hires_steps", "x_strength"}) p.put(x, prefs().getString(x, ""));
                        String n = nm.getText().toString().trim(); if (n.isEmpty()) n = "preset " + (ps.length() + 1);
                        ps.put(n, p); prefs().edit().putString("presets_anima", ps.toString()).apply();
                    } catch (Exception ignored) { }
                }).setNegativeButton(L.s("Отмена", "Cancel"), null).show();
            } else {
                String n = names.get(i - 1);
                builder().setTitle(n).setItems(new String[]{L.s("Применить", "Apply"), L.s("Удалить", "Delete")}, (d3, k) -> {
                    if (k == 1) { ps.remove(n); prefs().edit().putString("presets_anima", ps.toString()).apply(); return; }
                    JSONObject p = ps.optJSONObject(n); if (p == null) return;
                    SharedPreferences.Editor e = prefs().edit();
                    for (String key : PRESET_KEYS) if (p.has(key)) { String fk = Settings.key(this, key); if (key.equals("steps")) e.putInt(fk, p.optInt(key)); else e.putString(fk, p.optString(key)); }
                    if (p.has("prefix")) e.putString("prefix", p.optString("prefix"));
                    for (String x : new String[]{"x_hires", "x_up", "x_ad"}) if (p.has(x)) e.putBoolean(x, p.optBoolean(x));
                    for (String x : new String[]{"x_hires_scale", "x_hires_den", "x_hires_steps", "x_strength"}) if (p.has(x) && !p.optString(x).isEmpty()) e.putString(x, p.optString(x));
                    e.apply();
                    recreate();   // every field shows the preset
                }).show();
            }
        }).show();
    }
    // queue: prompt + seed + size + count are captured now; the settings are the ones in effect when the queue runs
    void enqueue() {
        String pr = prompt.getText().toString().trim(); if (pr.isEmpty()) { Toast.makeText(this, L.s("Введите описание", "Enter a prompt"), Toast.LENGTH_SHORT).show(); return; }
        try { queue.add(new JSONObject().put("p", pr).put("s", seed.getText().toString().trim()).put("w", resW).put("h", resH).put("n", count).put("m", model)); } catch (Exception ignored) { }
        saveQueue(); Toast.makeText(this, L.f("В очереди: %d", "Queued: %d", queue.size()), Toast.LENGTH_SHORT).show();
    }
    void saveQueue() { prefs().edit().putString("queue", new JSONArray(queue).toString()).apply(); refreshExtras(); }
    void showQueue() {
        if (queue.isEmpty()) return;
        String[] it = new String[queue.size()]; for (int i = 0; i < it.length; i++) { JSONObject o = queue.get(i); it[i] = (i + 1) + ". " + o.optString("p").replace('\n', ' ') + "  (" + o.optInt("w") + "×" + o.optInt("h") + " ×" + o.optInt("n") + ")"; }
        builder().setTitle(L.s("Очередь (нажмите, чтобы убрать)", "Queue (tap to remove)")).setItems(it, (d, i) -> { queue.remove(i); saveQueue(); })
            .setPositiveButton(L.s("Очистить", "Clear"), (d, i) -> { queue.clear(); saveQueue(); }).setNegativeButton(L.s("Закрыть", "Close"), null).show();
    }
    /** runs the queued items one after another through the normal Generate path */
    void runQueue() {
        if (busy || queue.isEmpty()) { if (queue.isEmpty()) Toast.makeText(this, L.s("Очередь пуста", "Queue is empty"), Toast.LENGTH_SHORT).show(); return; }
        JSONObject o = queue.remove(0); saveQueue();
        String m = o.optString("m"); if (!m.isEmpty() && ids.contains(m) && !m.equals(model)) setModel(m);
        prompt.setText(o.optString("p")); seed.setText(o.optString("s", "-1")); count = Math.max(1, o.optInt("n", 1)); setRes(o.optInt("w", resW), o.optInt("h", resH));
        afterGen = () -> { if (!queue.isEmpty() && !cancelled) ui.postDelayed(this::runQueue, 500); };
        generate(false);
    }

    TextView tokText;
    /** rough token count of prompt + quality tags (Danbooru-style text: ~3.6 characters per token; <lora:...> tags are removed before encoding).
     *  Anima: the NPU text encoder and Anima itself take 512 tokens; MOSS-Image 480. */
    void countTokens() {
        if (tokText == null || prompt == null) return;
        String p = prompt.getText().toString().replaceAll("(?i)<lora:[^>]*>", "");
        int n = (int) Math.ceil((p.length() + (isMoss() ? 0 : Settings.prefix(this).length())) / 3.6);
        { int lim = isMoss() ? 480 : 512; tokText.setText(L.f("≈%d / %d токенов", "≈%d / %d tokens", n, lim)); tokText.setTextColor(n > lim ? BAD : n > lim * 0.85 ? WARN : DIM); }
    }
    boolean sampFill;   // the fields were filled from the prefs (again after a reset)
    void refreshSampling() {
        if (sampBox == null || samplerBtn == null) return;
        if (!sampFill) { sampFill = true; stepsEdit.setText(String.valueOf(Settings.steps(this))); cfgEdit.setText(Settings.cfg(this)); negEdit.setText(Settings.negative(this)); }   // setText re-enters here with sampFill set
        samplerBtn.setText(Settings.samplerName(Settings.sampler(this)) + "  ▾");
        schedBtn.setText(Settings.schedulerName(Settings.scheduler(this)) + "  ▾");
        sampBox.setVisibility(!isMoss() && !model.isEmpty() ? View.VISIBLE : View.GONE);
        String n = Settings.defaultSampling(this) ? L.s("По умолчанию: модели обучены на 4 шага, Euler, CFG 1.", "Defaults: the models are distilled for 4 steps, Euler, CFG 1.")
            : L.f("Вызовов DiT: %d (по умолчанию 4, из них 2 в половинном размере)", "DiT calls: %d (default 4, two of them at half size)", Settings.calls(this));
        if (!Settings.turbo(this)) n = L.f("Модель не turbo: по умолчанию 20 шагов, DPM++ 2M + SGM Uniform, CFG 4 (вызовов DiT: %d). Значения хранятся для каждой модели.",
            "Not a turbo model: 20 steps, DPM++ 2M + SGM Uniform, CFG 4 by default (DiT calls: %d). The values are kept per model.", Settings.calls(this));
        if (Settings.cfgOn(this)) n += "\n" + L.s("CFG больше 1: вдвое медленнее, используется негативный промпт.", "CFG above 1: twice as slow, the negative prompt is used.");
        if (Settings.twoCalls(Settings.sampler(this))) n += "\n" + L.s("Сэмплер 2-го порядка: вдвое медленнее и с 4 шагами даёт зернистость.", "2nd-order sampler: twice as slow, grainy with 4 steps.");
        sampNote.setText(n);
        countTokens();
        if (genBtn != null && !busy) genBtn.setText(genLabel());
    }
    void pickLora() {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 9);
    }
    void dropLora() {
        if (busy) return;
        final File ld = isMoss() ? loraDir() : loraSrc();   // Anima: only the selected file leaves the library
        prefs().edit().remove(lp() + "lora_id").remove(lp() + "lora_name").remove(lp() + "lora_info").apply();
        ex2.submit(() -> { MossLora.deleteTree(ld); ui.post(this::refreshLora); });
    }
    void importLora(Uri uri) {
        String nm = "LoRA";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) { if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) nm = c.getString(i); } } catch (Exception ignored) { }
        final String name = nm;
        final boolean moss = isMoss(); final String pre = lp(); final File src = moss ? loraSrc() : new File(AnimaLora.dir(getFilesDir()), AnimaLora.fileName(name));
        loraInfo.setTextColor(WARN); loraInfo.setText(L.s("Проверяю файл…", "Checking the file…"));
        ex2.submit(() -> {
            final File dir = loraDir(); dir.mkdirs(); final File tmp = new File(dir, "new.safetensors");
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16]; long total = 0;
                for (int n; (n = in.read(b)) > 0; ) { o.write(b, 0, n); total += n; if (total > (1L << 30)) throw new IOException(L.s("файл больше 1 ГБ", "the file is larger than 1 GB")); }
            } catch (Exception e) { tmp.delete(); ui.post(() -> { refreshLora(); loraFail(name, L.f("Не удалось прочитать файл: %s", "Cannot read the file: %s", e.getMessage())); }); return; }
            final boolean ok; final String msg;
            if (moss) { MossLora.Check c = MossLora.check(BIN, tmp); ok = c.ok; msg = c.message(); } else { AnimaLora.Check c = AnimaLora.check(tmp); ok = c.ok; msg = c.message(); }
            if (!ok) { tmp.delete(); ui.post(() -> { refreshLora(); loraFail(name, msg); }); return; }
            if (moss) { File[] old = dir.listFiles(); if (old != null) for (File f : old) if (!f.equals(tmp)) MossLora.deleteTree(f); }   // MOSS: the old LoRA and its folded patches go; Anima keeps a library
            src.delete(); tmp.renameTo(src);
            final String tag = src.getName().replaceAll("\\.safetensors$", "");
            prefs().edit().putString(pre + "lora_id", Long.toHexString(System.currentTimeMillis())).putString(pre + "lora_name", name).putString("anima_lora_file", moss ? prefs().getString("anima_lora_file", "user.safetensors") : src.getName())
                .putString(pre + "lora_info", "✓ " + msg + (moss ? "" : "\n" + L.f("В промпте: <lora:%s:1.0>", "In the prompt: <lora:%s:1.0>", tag))).apply();
            ui.post(this::refreshLora);
        });
    }
    void loraFail(String name, String why) {
        builder().setTitle(L.f("LoRA «%s» не подходит", "LoRA \"%s\" does not fit", name)).setMessage(why).setPositiveButton("OK", null).show();
    }

    // ---- menu, dialogs ------------------------------------------------------------------------
    void menu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, L.s("Модели", "Models"));
        pm.getMenu().add(0, 5, 1, "Civitai");
        pm.getMenu().add(0, 2, 2, L.s("Открыть настройки из PNG", "Load settings from a PNG"));
        pm.getMenu().add(0, 3, 3, L.s("Настройки", "Settings"));
        pm.getMenu().add(0, 4, 4, L.s("Инфо", "Info"));
        pm.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 1) openModels();
            else if (i.getItemId() == 5) startActivity(new Intent(this, CivitaiActivity.class));
            else if (i.getItemId() == 2) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/png").addCategory(Intent.CATEGORY_OPENABLE), 8);
            else if (i.getItemId() == 3) startActivity(new Intent(this, Settings.class));
            else startActivity(new Intent(this, Info.class));
            return true;
        });
        pm.show();
    }

    void openModels() { if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; } startActivity(new Intent(this, ModelsActivity.class)); }

    /** saved and recent prompts: tap to use; saved ones can be removed with ✕ */
    void templates() {
        final LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(14), dp(14), dp(10));
        final AlertDialog[] dlg = new AlertDialog[1];
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            box.removeAllViews();
            Button save = button(L.s("Сохранить текущее описание", "Save the current prompt"), ACC, ON_ACC); save.setTextSize(14);
            save.setOnClickListener(v -> {
                String t = prompt.getText().toString().trim();
                if (t.isEmpty()) { Toast.makeText(this, L.s("Введите описание", "Enter a prompt"), Toast.LENGTH_SHORT).show(); return; }
                List<String> l = savedPrompts(); l.remove(t); l.add(0, t); while (l.size() > 30) l.remove(l.size() - 1);
                prefs().edit().putString("saved", new JSONArray(l).toString()).apply(); fill[0].run();
            });
            box.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));
            List<String> sv = savedPrompts();
            if (!sv.isEmpty()) { box.addView(sect(L.s("Сохранённые", "Saved"))); for (String t : sv) box.addView(promptRow(t, true, dlg, fill)); }
            List<String> rc = recentPrompts();
            if (!rc.isEmpty()) { box.addView(sect(L.s("Недавние", "Recent"))); for (String t : rc) box.addView(promptRow(t, false, dlg, fill)); }
        };
        fill[0].run();
        ScrollView s = new ScrollView(this); s.addView(box);
        dlg[0] = dialog(s);
    }

    TextView sect(String s) { TextView t = text(s, 12, DIM, true); t.setPadding(dp(4), dp(14), 0, dp(6)); return t; }

    View promptRow(String t, boolean removable, AlertDialog[] dlg, Runnable[] fill) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setBackground(bg(FIELD, 12)); r.setPadding(dp(12), dp(10), dp(8), dp(10));
        TextView tv = text(t, 13, TXT, false); tv.setMaxLines(2); tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        if (removable) {
            TextView x = text("✕", 16, DIM, false); x.setPadding(dp(12), dp(4), dp(8), dp(4));
            x.setOnClickListener(v -> { List<String> l = savedPrompts(); l.remove(t); prefs().edit().putString("saved", new JSONArray(l).toString()).apply(); fill[0].run(); });
            r.addView(x);
        }
        r.setOnClickListener(v -> { prompt.setText(t); prompt.setSelection(t.length()); dlg[0].dismiss(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4);
        r.setLayoutParams(lp);
        return r;
    }

    List<String> savedPrompts() {
        List<String> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs().getString("saved", "[]")); for (int i = 0; i < a.length(); i++) l.add(a.getString(i)); } catch (Exception ignored) { }
        return l;
    }

    List<String> recentPrompts() {
        List<String> l = new ArrayList<>();
        File[] fs = Gallery.list(this, Gallery.ALL);
        for (int i = 0; i < fs.length && l.size() < 10 && i < 60; i++) { String p = Gallery.meta(fs[i]).optString("prompt").trim(); if (!p.isEmpty() && !l.contains(p)) l.add(p); }
        return l;
    }

    static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }
    static String sizeLabel(int w, int h) { int g = gcd(w, h); return w + "×" + h + "  ·  " + w / g + ":" + h / g; }

    /** Size picker: the portrait sizes in the left column, their landscape twins in the right one, squares below, then "Custom". */
    void pickSize() {
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(14), dp(14), dp(10));
        java.util.List<int[]> por = new java.util.ArrayList<>(), lan = new java.util.ArrayList<>(), sq = new java.util.ArrayList<>();
        for (int[] z : sizeList()) (z[1] > z[0] ? por : z[0] > z[1] ? lan : sq).add(z);
        box.addView(cols(text(L.s("Портрет", "Portrait"), 12, DIM, true), text(L.s("Альбом", "Landscape"), 12, DIM, true), true));
        for (int i = 0; i < Math.max(por.size(), lan.size()); i++) box.addView(cols(i < por.size() ? cell(por.get(i), dlg) : new View(this), i < lan.size() ? cell(lan.get(i), dlg) : new View(this), false));
        TextView sqh = text(L.s("Квадрат", "Square"), 12, DIM, true); sqh.setPadding(dp(4), dp(14), 0, dp(6)); box.addView(sqh);
        for (int i = 0; i < sq.size(); i += 2) box.addView(cols(cell(sq.get(i), dlg), i + 1 < sq.size() ? cell(sq.get(i + 1), dlg) : new View(this), false));
        Button custom = button(L.s("Свой", "Custom"), FIELD, customOpen ? ACC : TXT);
        custom.setOnClickListener(v -> { customOpen = true; setRes(resW, resH); dlg[0].dismiss(); });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, dp(44)); cl.topMargin = dp(12);
        box.addView(custom, cl);
        ScrollView sv = new ScrollView(this); sv.addView(box);
        dlg[0] = dialog(sv);
    }

    LinearLayout cols(View l, View r, boolean header) {
        LinearLayout row = new LinearLayout(this); row.setPadding(0, dp(3), 0, dp(3));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1); a.rightMargin = dp(4);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, -2, 1); b.leftMargin = dp(4);
        if (header) { l.setPadding(dp(4), 0, 0, dp(2)); r.setPadding(dp(4), 0, 0, dp(2)); }
        row.addView(l, a); row.addView(r, b);
        return row;
    }

    TextView cell(int[] z, AlertDialog[] dlg) {
        boolean on = !customOpen && z[0] == resW && z[1] == resH;
        TextView t = text(sizeLabel(z[0], z[1]), 14, on ? ON_ACC : TXT, on);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(6), dp(12), dp(6), dp(12)); t.setBackground(bg(on ? ACC : FIELD, 14));
        t.setOnClickListener(v -> { customOpen = false; setRes(z[0], z[1]); dlg[0].dismiss(); });
        return t;
    }

    boolean isMoss() { return Moss.is(model); }
    /** the size presets of the selected model: Anima's list, or MOSS-Image's own presets (any size with sides multiple of 16 works) */
    java.util.List<int[]> sizeList() { return java.util.Arrays.asList(isMoss() ? Moss.PRESETS : SIZES); }
    String mossSizeError(int w, int h) { return Moss.sizeError(w, h); }

    // ---- model state --------------------------------------------------------------------------
    String modelName() { return model.isEmpty() ? L.s("нет модели", "no model") : store.name(model); }

    void pickModel() {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        if (ids.isEmpty()) { openModels(); return; }
        String[] names = new String[ids.size() + 3];
        for (int i = 0; i < ids.size(); i++) names[i] = (ids.get(i).equals(model) ? "✓  " : "      ") + store.name(ids.get(i));
        names[ids.size()] = "＋  " + L.s("Каталог моделей…", "Model catalog…");
        names[ids.size() + 1] = "＋  Civitai…";
        names[ids.size() + 2] = "＋  " + L.s("Импорт чекпойнта Anima (.safetensors)…", "Import an Anima checkpoint (.safetensors)…");
        builder().setTitle(L.s("Модель", "Model")).setItems(names, (d, i) -> {
            if (i < ids.size()) setModel(ids.get(i));
            else if (i == ids.size()) openModels();
            else if (i == ids.size() + 1) startActivity(new Intent(this, CivitaiActivity.class).putExtra("type", "Checkpoint"));
            else startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 13);
        }).show();
    }

    /** The NPU pipeline needs width/height multiples of 16, patch count divisible by 32 and at most 6144 patches (megarun TMAX). */
    static String sizeError(int w, int h) {
        if (w < 256 || h < 256) return L.s("Размер: минимум 256 по каждой стороне", "Size: at least 256 on each side");
        if (w % 16 != 0 || h % 16 != 0) return L.s("Ширина и высота должны быть кратны 16", "Width and height must be multiples of 16");
        int t = (w / 16) * (h / 16);
        if (t % 32 != 0) return L.f("Число патчей (%d) должно делиться на 32, например 640×640, 1024×1024, 704×832", "Patch count (%d) must be divisible by 32, e.g. 640×640, 1024×1024, 704×832", t);
        if (t > 6144) return L.s("Слишком большой размер: максимум 6144 патча (например 1536×960)", "Too large: at most 6144 patches (e.g. 1536×960)");
        return null;
    }

    void setRes(int w, int h) {
        resW = w; resH = h;
        boolean preset = false;
        for (int[] z : sizeList()) if (z[0] == w && z[1] == h) preset = true;
        if (sizeText != null) sizeText.setText(sizeLabel(w, h) + (preset && !customOpen ? "" : "  ·  " + L.s("Свой", "Custom")));
        if (customRow != null) { customRow.setVisibility(customOpen || !preset ? View.VISIBLE : View.GONE); if (!wEdit.getText().toString().equals(String.valueOf(w)) || !hEdit.getText().toString().equals(String.valueOf(h))) { wEdit.setText(String.valueOf(w)); hEdit.setText(String.valueOf(h)); } }
        if (chipName != null) chipName.setText(modelName());
        if (genBtn != null && !busy) genBtn.setText(genLabel());
    }

    /** rough time to a picture: the catalog speed at 512x768 scaled by the patch count, times the number of pictures */
    String genLabel() {
        if (model.isEmpty()) return L.s("Скачать модель", "Get a model");
        double sm = isMoss() || Settings.defaultSampling(this) ? 1 : Settings.calls(this) / 2.6;   // the default 4 steps cost ~2.6 full DiT calls (two run at half size)
        long est = Math.max(3, Math.round(store.speed(model) * sm * ((resW / 16) * (resH / 16)) / 1536.0)) * count;
        return count > 1 ? L.f("Генерировать ×%d  ·  ~%d с", "Generate ×%d  ·  ~%d s", count, est) : L.f("Генерировать  ·  ~%d с", "Generate  ·  ~%d s", est);
    }

    /** Share of the DiT time spent after `st` steps: the first two of the 4 steps use a half-size latent and cost ~0.3 (512x768) / ~0.22 (960x1536) of a full step. */
    double stepShare(int st) {
        if (!Settings.defaultSampling(this)) return Math.min(1.0, st / (double) Settings.calls(this));   // other sampling: no half-size steps, every DiT call costs the same
        double lo = resW * resH < 500000 ? 0.3 : 0.22;
        double done = 0, total = 0;
        for (int i = 0; i < STEPS; i++) { double c = i < 2 ? lo : 1.0; total += c; if (i < st) done += c; }
        return done / total;
    }

    void setModelUi(boolean isLoaded, boolean working, String msg) {
        ui.post(() -> {
            int c = working ? WARN : isLoaded ? OK : DIM;
            chipDot.setTextColor(c);
            if (loraBox != null) { loraBox.setVisibility(model.isEmpty() ? View.GONE : View.VISIBLE); refreshLora(); }
            refreshExtras();
            refreshSampling();
            state.setText(working ? msg : isMoss() ? L.s("MOSS-Image: текст, DiT и VAE работают на NPU; веса читаются с накопителя при каждой картинке", "MOSS-Image: text encoder, DiT and VAE run on the NPU; the weights are streamed from storage for every picture") : isLoaded ? L.s("Модель загружена в NPU, генерация стартует сразу", "Model is loaded on the NPU, generation starts at once")
                : L.s("Модель выгружена: при генерации загрузится сама (~5 с)", "Model is unloaded: it loads itself when you generate (~5 s)"));
            loadBtn.setText(isLoaded ? L.s("Выгрузить", "Unload") : L.s("Загрузить", "Load"));
            loadBtn.setEnabled(!working && !busy && !model.isEmpty() && !isMoss());
            loadBtn.setAlpha(loadBtn.isEnabled() ? 1f : .5f);
            countBtn.setEnabled(!busy); countBtn.setAlpha(busy ? .5f : 1f);
            boolean can = busy ? !cancelled : !working;   // while a picture is made the main button is "Stop"
            genBtn.setEnabled(can);
            genBtn.setBackground(busy ? bg(BAD, 18) : gradient(can));
            genBtn.setTextColor(busy ? 0xFFFFFFFF : can ? ON_ACC : DIM);
            genBtn.setText(busy ? (cancelled ? L.s("Останавливаю…", "Stopping…") : L.s("Остановить", "Stop")) : genLabel());
        });
    }

    void refreshState() {
        if (isMoss()) { setModelUi(false, false, ""); return; }
        ex.submit(() -> {
            String s = su("sh " + D + "/anima.sh status", true);
            if (s.startsWith("ERR") || s.isEmpty()) {
                ui.post(() -> { state.setText(L.f("Не запускается оболочка: %s", "Shell does not start: %s", s)); chipDot.setTextColor(BAD); });
                return;
            }
            if (busy) return;
            loaded = s.contains("unloaded") ? false : s.contains("loaded");
            setModelUi(loaded, false, "");
            if (!statusSeen) { statusSeen = true; if (!loaded && Settings.autoload(this) && !model.isEmpty() && Compat.chipAllowed(this)) ui.post(this::toggleModel); }   // "load at start"
        });
    }

    void setModel(String m) {
        if (m.equals(model) || busy) return;   // no model switch mid-generation
        model = m;
        prefs().edit().putString("model", model).apply();   // the sampling fields are kept per model
        setRes(resW, resH);
        sampFill = false; refreshSampling();   // show this model's values
        if (loaded) {   // the loaded server holds the other model's weights
            setModelUi(true, true, L.s("Меняю модель…", "Switching model…"));
            ex.submit(() -> { su("sh " + D + "/anima.sh stop < /dev/null >/dev/null 2>&1", false); loaded = false; setModelUi(false, false, ""); });
        } else setModelUi(false, false, "");
    }

    void toggleModel() {
        if (isMoss()) return;   // nothing to preload
        final boolean want = !loaded;
        setModelUi(loaded, true, want ? L.s("Загружаю модель…", "Loading the model…") : L.s("Выгружаю модель…", "Unloading the model…"));
        ex.submit(() -> {
            su("rm -f " + D + "/cancel; " + mp() + "sh " + D + "/anima.sh " + (want ? "load" : "stop") + " < /dev/null >/dev/null 2>&1", false);
            loaded = want;
            setModelUi(loaded, false, "");
        });
    }

    /** anima.sh ships in assets and is rewritten at every start (it is a script, so it may live in the app's data dir) */
    void installScript() {
        for (String nm : new String[]{"anima.sh", "moss.sh"})
            try (InputStream in = getAssets().open(nm); FileOutputStream o = new FileOutputStream(new File(D, nm))) {
                byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            } catch (IOException e) { Toast.makeText(this, nm + ": " + e, Toast.LENGTH_LONG).show(); }
    }

    void refreshModels() {
        ids = store.installed();
        String was = prefs().getString("model", "");
        model = ids.isEmpty() ? "" : ids.contains(model) ? model : ids.get(0);
        prefs().edit().putString("model", model).apply();
        if (!model.equals(was)) sampFill = false;
        setRes(resW, resH);
        setModelUi(loaded, false, "");
    }

    // ---- shell --------------------------------------------------------------------------------
    /** One long-lived /system/bin/sh with A, S, BIN in its environment: the helper binaries are started from it with the app's own uid. */
    static class Sh {
        String[] env; Process p; BufferedWriter w; BufferedReader r;
        synchronized String run(String cmd) {
            try {
                if (p == null) {
                    ProcessBuilder pb = new ProcessBuilder("/system/bin/sh").redirectErrorStream(true);
                    pb.environment().put("A", env[0]); pb.environment().put("S", env[1]); pb.environment().put("BIN", env[2]); pb.directory(new File(env[0]));
                    p = pb.start();
                    w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
                    r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                }
                w.write(cmd + "\necho __END__\n");
                w.flush();
                StringBuilder sb = new StringBuilder();
                for (String l; (l = r.readLine()) != null && !l.equals("__END__"); ) sb.append(l).append('\n');
                return sb.toString().trim();
            } catch (Exception e) {
                p = null;
                return "ERR " + e;
            }
        }
    }

    /** main: model load / generation; pollSh: progress and status polling; ctlSh: the Stop button (it must not wait for a running generation) */
    final Sh main = new Sh(), pollSh = new Sh(), ctlSh = new Sh();

    /** Runs a command in the main shell (blocking) and returns its stdout; `poll` uses the second shell so progress polling never waits for a running generation. */
    String su(String cmd, boolean poll) { return (poll ? pollSh : main).run(cmd); }

    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    // ---- generation ---------------------------------------------------------------------------
    /** Stop: the server is stopped (the model unloads; the next picture reloads it in ~5 s), anima.sh sees the cancel marker and leaves its waiting loops */
    void cancel() {
        if (!busy || cancelled) return;
        cancelled = true;
        setModelUi(loaded, false, "");
        if (isMoss()) ex2.submit(() -> ctlSh.run("sh " + D + "/moss.sh stop < /dev/null >/dev/null 2>&1"));
        else ex2.submit(() -> ctlSh.run("touch " + D + "/cancel; sh " + D + "/anima.sh stop < /dev/null >/dev/null 2>&1"));
    }

    void generate(boolean newSeed) {
        if (model.isEmpty()) { openModels(); return; }
        int cw = 0, ch = 0;
        try { cw = Integer.parseInt(wEdit.getText().toString().trim()); ch = Integer.parseInt(hEdit.getText().toString().trim()); } catch (NumberFormatException e) { }
        String bad = isMoss() ? mossSizeError(cw, ch) : sizeError(cw, ch);
        if (bad != null) { Toast.makeText(this, bad, Toast.LENGTH_LONG).show(); return; }
        if (cw != resW || ch != resH) setRes(cw, ch);
        final String pr0 = prompt.getText().toString().trim();
        if (pr0.isEmpty()) { Toast.makeText(this, L.s("Введите описание", "Enter a prompt"), Toast.LENGTH_SHORT).show(); return; }
        // memory guard: the model needs ~4.5 GB when it is not loaded yet, and a generation ~1.5 GB more; with less the phone swaps, heats up and can hang
        final long need = (loaded || isMoss() ? 0 : 4600) + 1500, have = availMb();
        if (have < need && !memOk) {
            builder().setTitle(L.s("Мало свободной памяти", "Low free memory"))
                .setMessage(L.f("Свободно %d МБ, нужно около %d МБ. Без этого телефон начнёт тормозить и сильно греться.", "%d MB free, about %d MB are needed. Without it the phone slows down and gets very hot.", have, need))
                .setPositiveButton(L.s("Освободить и продолжить", "Free RAM and go on"), (d, k) -> ex2.submit(() -> { RamWidget.freeRamOthers(this); ui.post(() -> { memOk = true; generate(newSeed); memOk = false; }); }))
                .setNeutralButton(L.s("Всё равно", "Anyway"), (d, k) -> { memOk = true; generate(newSeed); memOk = false; })
                .setNegativeButton(L.s("Отмена", "Cancel"), null).show();
            return;
        }
        final String pr = pr0;
        String sd = seed.getText().toString().trim();
        final boolean randomSeed = newSeed || sd.isEmpty() || sd.equals("-1");   // -1 = random; the seed used is shown under the picture
        long base0 = 0; try { base0 = Long.parseLong(sd); } catch (NumberFormatException ignored) { }
        final long baseSeed = base0;
        final int n = count, w = resW, h = resH;
        busy = true; cancelled = false; curIdx = 0; curN = n;
        setModelUi(loaded, false, "");
        View f0 = getCurrentFocus(); if (f0 != null) ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(f0.getWindowToken(), 0);
        bar.setProgress(0);
        bar.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText(L.s("Запуск…", "Starting…"));
        if (Settings.keepOn(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (((PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) Toast.makeText(this, L.s("Телефон сильно нагрелся: генерация будет медленной", "The phone is very hot: generation will be slow"), Toast.LENGTH_LONG).show();
        final android.content.Context app = getApplicationContext();
        app.startForegroundService(new Intent(app, DownloadService.class).putExtra("mode", "gen"));   // keeps the process alive when the app goes to the background
        final long t0 = System.currentTimeMillis();
        ex.submit(() -> {
            int made = 0; Bitmap lastBm = null;
            for (int i = 0; i < n && !cancelled; i++) {
                curIdx = i;
                if (i > 0 && tooHot()) { hotStop = true; break; }   // batch: the next picture waits for a cooler phone
                final String seedS = String.valueOf(randomSeed ? new Random().nextInt(1 << 30) : baseSeed + i);
                final File f = runOne(pr, seedS, w, h, t0);
                if (f == null) break;
                made++;
                final Bitmap bm = BitmapFactory.decodeFile(f.getPath());
                lastBm = bm;
                ui.post(() -> {
                    lastFile = f; img.setImageBitmap(bm); img.setVisibility(View.VISIBLE); placeholder.setVisibility(View.GONE);
                    setFavIcon(false);
                    for (ImageButton x : new ImageButton[]{againBtn, favBtn, dlBtn, shareBtn}) x.setVisibility(View.VISIBLE);
                    final ScrollView svv = (ScrollView) imgBox.getParent().getParent(); svv.post(() -> svv.smoothScrollTo(0, 0));   // after the image has been laid out
                    saved.setText("seed " + seedS);
                });
            }
            final double sec = (System.currentTimeMillis() - t0) / 1000.0;
            final boolean wasCancelled = cancelled, okAny = made > 0, hot = hotStop || tooHot();
            hotStop = false;
            final int total = made;
            if (wasCancelled) loaded = false;   // Stop shut the server down
            busy = false; cancelled = false;
            app.stopService(new Intent(app, DownloadService.class));
            final Bitmap nb = lastBm;
            ui.post(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                bar.setVisibility(View.GONE);
                if (wasCancelled) status.setText(L.s("Остановлено", "Stopped"));
                else if (!okAny) status.setText(L.s("Ошибка генерации (лог: files/anima/animad.log)", "Generation failed (log: files/anima/animad.log)"));
                else status.setText(L.f("Готово за %.1f с", "Done in %.1f s", sec));
                if (hot) status.setText(status.getText() + "  ·  " + L.s("Пауза: телефон сильно нагрелся, дайте ему остыть", "Paused: the phone is very hot, let it cool down"));
                setModelUi(loaded, false, "");
                if (okAny && !visible) notifyDone(app, nb, pr, total);
                Runnable next = afterGen; afterGen = null;
                if (next != null && okAny && !wasCancelled && !hot) next.run();   // the queue goes on, unless the phone is too hot (it stays queued)
                else if (next != null && hot) Toast.makeText(this, L.s("Очередь на паузе: телефон слишком горячий", "Queue paused: the phone is too hot"), Toast.LENGTH_LONG).show();
            });
        });
    }

    /** one picture: loads the model if needed, runs anima.sh, stores the PNG (with the settings inside) in the history; null on failure or Stop */
    File runOne(String pr, String seedS, int w, int h, long t0) {
        if (isMoss()) return runOneMoss(pr, seedS, w, h, t0);
        final String fullPrompt = pr;   // kept with its <lora:...> tags in the PNG
        AnimaLora.Tags tg = AnimaLora.tags(getFilesDir(), pr);
        pr = tg.prompt; tagLoras = tg.items;
        if (!tg.missing.isEmpty()) { final String miss = String.join(", ", tg.missing); ui.post(() -> Toast.makeText(this, L.f("LoRA не найдена: %s", "LoRA not found: %s", miss), Toast.LENGTH_LONG).show()); }
        if (!loaded) {
            setProgressUi(0, L.s("Загружаю модель…", "Loading the model…"), t0);
            su("rm -f " + D + "/cancel; " + mp() + "sh " + D + "/anima.sh load < /dev/null >/dev/null 2>&1", false);
            if (cancelled) return null;
            loaded = true;
        }
        final String f = D + "/last.png";
        final boolean[] done = {false};
        final int[] base = count();
        final int steps = Settings.steps(this), calls = Settings.calls(this);   // animad logs one "step" line per DiT call: CFG / 2nd-order samplers make several per step
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                int[] c = count();
                int st = c[0] - base[0];
                if (c[1] > base[1]) setProgressUi(97, L.s("Сохраняю…", "Saving…"), t0);
                else if (st >= calls) setProgressUi(92, L.s("Декодирование…", "Decoding…"), t0);
                else if (c[2] > base[2]) setProgressUi(15 + (int) (76 * stepShare(st)), L.f("Шаг %d/%d", "Step %d/%d", st * steps / calls + 1, steps), t0);  // adapter line = step 1 started
                else setProgressUi(8, L.s("Кодирование текста…", "Encoding the text…"), t0);
            }
        });
        poll.start();
        final String samp = "CFG=" + q(Settings.cfg(this)) + " SAMPLER=" + q(Settings.sampler(this)) + " SCHED=" + q(Settings.scheduler(this)) + (Settings.cfgOn(this) ? " NEG=" + q(Settings.negative(this)) : "");
        su("cd " + D + " && rm -f last.png && " + mp() + "PREFIX=" + q(Settings.prefix(this)) + " STEPS=" + steps + " " + samp + " " + extrasEnv(w, h, t0) + "W=" + w + " H=" + h + " LOGRE='learned_condition|completed in' sh ./anima.sh " + q(pr) + " " + seedS + " " + f + " < /dev/null >> gen.log 2>&1", false);
        done[0] = true;
        try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) return null;
        byte[] png = new byte[0];
        try { png = Models.readBytes(new File(f)); } catch (Exception ignored) { }
        if (png.length == 0 || BitmapFactory.decodeByteArray(png, 0, png.length) == null) return null;
        boolean upscaled = false;
        if (prefs().getBoolean("x_up", false) && !cancelled) { byte[] up = upscale2x(png, w, h, t0); if (up != null) { png = up; upscaled = true; } }
        try {
            JSONObject meta = new JSONObject().put("prompt", fullPrompt).put("seed", seedS).put("w", w).put("h", h).put("model", modelName()).put("id", model).put("sec", (System.currentTimeMillis() - t0) / 1000.0)
                .put("steps", steps).put("cfg", Settings.cfg(this)).put("sampler", Settings.sampler(this)).put("scheduler", Settings.scheduler(this));
            if (Settings.cfgOn(this) && !Settings.negative(this).isEmpty()) meta.put("negative", Settings.negative(this));
            if (loraOn() && loraFitsModel()) meta.put("lora", prefs().getString("anima_lora_name", "LoRA")).put("lora_strength", loraStr());
            if (!tg.used.isEmpty() && loraFitsModel()) meta.put("prompt_loras", String.join(", ", tg.used));
            if (upscaled) meta.put("upscale", "RealESRGAN x2");
            return Gallery.save(this, PngMeta.embed(png, meta), meta);
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()); return null; }
    }

    /** one MOSS-Image picture: tokenise here, text encoder + DiT + VAE on the NPU through moss.sh, PNG with the settings inside */
    File runOneMoss(String pr, String seedS, int w, int h, long t0) {
        final File home = getFilesDir(), M = Moss.dir(home), ids = new File(M, "ids.i32"), raw = new File(M, "img.f32");
        setProgressUi(3, L.s("Токенизация…", "Tokenising…"), t0);
        try { Moss.writeIds(home, pr, ids); } catch (Exception e) { ui.post(() -> Toast.makeText(this, "tokenizer: " + e, Toast.LENGTH_LONG).show()); return null; }
        String loraEnv = ""; final boolean useLora = loraOn(); final float lstr = loraStr();
        if (useLora) {
            final String[] msg = {""};
            File ovl = MossLora.ensure(home, BIN, loraId(), lstr, (d, t) -> setProgressUi(1 + 5 * d / t, L.f("Подготовка LoRA… %d/%d", "Preparing the LoRA… %d/%d", d, t), t0), () -> cancelled, msg);
            if (ovl == null) { if (!cancelled) ui.post(() -> Toast.makeText(this, L.f("Не удалось подготовить LoRA: %s", "Could not prepare the LoRA: %s", msg[0]), Toast.LENGTH_LONG).show()); return null; }
            loraEnv = "MOSS_LORA=" + q(ovl.getPath()) + " ";
        }
        final boolean[] done = {false};
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                String s = su("echo $(grep -c '^te:' " + M + "/moss.log) $(grep -c '^refiner:' " + M + "/moss.log) $(grep -c '^step' " + M + "/moss.log) $(grep -c '^done' " + M + "/moss.log)", true);
                try {
                    String[] a = s.split("\\s+"); int te = Integer.parseInt(a[0]), rf = Integer.parseInt(a[1]), st = Integer.parseInt(a[2]), dn = Integer.parseInt(a[3]);
                    if (dn > 0) setProgressUi(94, L.s("Декодирование…", "Decoding…"), t0);
                    else if (rf > 0) setProgressUi(22 + 70 * st / STEPS, L.f("Шаг %d/%d", "Step %d/%d", Math.min(st + 1, STEPS), STEPS), t0);
                    else if (te > 0) setProgressUi(20, L.s("Подготовка текста…", "Preparing the text…"), t0);
                    else setProgressUi(8, L.s("Кодирование текста на NPU…", "Encoding the text on the NPU…"), t0);
                } catch (Exception ignored) { }
            }
        });
        poll.start();
        su("rm -f " + q(raw.getPath()) + "; " + loraEnv + "M=" + q(M.getPath()) + " sh " + D + "/moss.sh " + q(ids.getPath()) + " " + w + " " + h + " " + seedS + " " + STEPS + " " + q(raw.getPath()) + " < /dev/null >> " + D + "/gen.log 2>&1", false);
        done[0] = true;
        try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) return null;
        try {
            byte[] png = Moss.png(raw, w, h); raw.delete();
            JSONObject meta = new JSONObject().put("prompt", pr).put("seed", seedS).put("w", w).put("h", h).put("model", modelName()).put("id", model).put("sec", (System.currentTimeMillis() - t0) / 1000.0);
            if (useLora) meta.put("lora", prefs().getString("moss_lora_name", "LoRA")).put("lora_strength", lstr);
            return Gallery.save(this, PngMeta.embed(png, meta), meta);
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Ошибка генерации: %s (лог files/moss/moss.log)", "Generation failed: %s (log files/moss/moss.log)", e.getMessage()), Toast.LENGTH_LONG).show()); return null; }
    }

    /** "Done" notification when the app is not on screen; tapping it opens the app */
    void notifyDone(android.content.Context app, Bitmap bm, String pr, int made) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("done", L.s("Готовые картинки", "Finished pictures"), NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder nb = new Notification.Builder(app, "done").setSmallIcon(android.R.drawable.ic_menu_gallery).setAutoCancel(true)
                .setContentTitle(made > 1 ? L.f("Готово: %d картинки", "Done: %d pictures", made) : L.s("Картинка готова", "Picture is ready")).setContentText(pr)
                .setContentIntent(PendingIntent.getActivity(app, 0, new Intent(app, Main.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            if (bm != null) nb.setLargeIcon(bm).setStyle(new Notification.BigPictureStyle().bigPicture(bm));
            nm.notify(2, nb.build());
        } catch (Exception ignored) { }
    }

    /** [steps done, vae runs, adapter runs (text encoder finished)] from the animad log. */
    int[] count() {
        String s = su("echo $(grep -c 'animad: step' " + D + "/animad.log) $(grep -c 'animad: vae' " + D + "/animad.log) $(grep -c 'animad: adapter' " + D + "/animad.log)", true);
        try {
            String[] a = s.split("\\s+");
            return new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])};
        } catch (Exception e) {
            return new int[]{0, 0, 0};
        }
    }

    /** pct is the progress of the current picture; with several pictures the bar and the text cover the whole batch */
    void setProgressUi(int pct, String msg, long t0) {
        final int all = (curIdx * 100 + pct) / curN;
        final String pre = curN > 1 ? "(" + (curIdx + 1) + "/" + curN + ")  " : "";
        final String t = L.f("%s  ·  %.0f с", "%s  ·  %.0f s", pre + msg, (System.currentTimeMillis() - t0) / 1000.0);
        ui.post(() -> { bar.setProgress(all); status.setText(t); });
    }
}
