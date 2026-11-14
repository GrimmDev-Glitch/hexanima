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
import android.graphics.Color;
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
import android.view.MotionEvent;
import android.widget.*;
import java.io.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
    final Object batchLock = new Object();
    volatile boolean batchAdjustable;
    boolean statusSeen;
    int tick;

    TextView chipName, chipDot, state, status, saved, placeholder, ramTxt, warnTxt, sizeText;
    LinearLayout customRow, imgBox;
    Button loadBtn, genBtn, countBtn;
    ImageButton dlBtn, shareBtn, favBtn, againBtn; Button editGeneratedBtn;
    MultiAutoCompleteTextView prompt;
    EditText seed, wEdit, hEdit;
    LinearLayout loraBox; TextView loraName, loraInfo; EditText strText; SeekBar strBar; Button loraPick, loraDrop;
    LinearLayout qwenLoraBox, qwenStrRow; TextView qwenLoraName, qwenStrVal; Button qwenLoraPick, qwenLoraDrop; SeekBar qwenStr; volatile boolean qwenFolding;
    Fold sampFold, loraFold, extFold;
    LinearLayout sampBox, mossStepsBox; EditText negEdit, stepsEdit, cfgEdit; Button samplerBtn, schedBtn, cutBtn, mossStepsBtn; TextView sampNote;
    LinearLayout pageBar, imgTools;   // ‹ 2 / 3 › over a batch picture (swiping needs a quick fling; the buttons always work)
    TextView pageTag;   // "2 / 3" over a batch picture: the caption under a tall picture is off screen
    TextView clearX;   // small ✕ over the shown picture: back to the placeholder (the picture stays in the History)
    LinearLayout editOpts, editRow; Button compareBtn; volatile File pendingCompare;   // Qwen edit options, before/after view, the side-by-side of the last single edit
    LinearLayout editBox; ImageView editThumb; TextView editInfo; Button editPick, editDrop, editMaskBtn, editMaskClear; volatile Bitmap editBm;   // Qwen-Image: the photo to edit
    ProgressBar bar, ramBar;
    ImageView img;
    File lastFile;
    List<File> batchFiles = new ArrayList<>();
    File batchGridFile;
    int batchDisplayIndex;
    android.view.GestureDetector batchGestures;
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
        return "MID=" + model + " MDIR=m_" + model + " IDLE=" + Settings.idleMin(this) * 60 + " " + lora + (Settings.lowRam(this) ? "MEGA_STREAM=1 " : "") + (Settings.coolMode(this).equals("on") ? "MEGA_COOL=1 " : "");
    }

    GradientDrawable gradient(boolean enabled) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{enabled ? ACC : LINE, enabled ? ACC2 : LINE});
        g.setCornerRadius(dp(18)); return g;
    }
    SharedPreferences prefs() { return getSharedPreferences("anima", 0); }
    void setFavIcon(boolean on) { favBtn.setImageResource(getResources().getIdentifier(on ? "ic_star" : "ic_star_off", "drawable", getPackageName())); favBtn.setImageTintList(on ? null : ColorStateList.valueOf(TXT)); }

    void freeMemoryDialog() {
        if (busy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        builder().setTitle(L.s("Liberar memoria de HexAnima", "Free HexAnima memory"))
            .setMessage(L.s("Descarga el modelo Anima de la NPU y libera las imágenes temporales de esta pantalla. Android administra la RAM de las demás aplicaciones; una app normal no puede cerrarlas a la fuerza.",
                "Unloads Anima from the NPU and releases temporary images from this screen. Android manages other apps' RAM; a regular app cannot force-close them."))
            .setNegativeButton(L.s("Cancelar", "Cancel"), null)
            .setPositiveButton(L.s("Liberar", "Free"), (d, w) -> ex2.submit(() -> {
                long before = RamWidget.avail(this); releaseAppMemory();
                try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                long freed = Math.max(0, RamWidget.avail(this) - before) / 1048576;
                ui.post(() -> Toast.makeText(this, L.f("Memoria disponible: +~%d MB", "Available memory: +~%d MB", freed), Toast.LENGTH_LONG).show());
            })).show();
    }

    /** Release only memory owned by HexAnima; Android reclaims other apps when the system needs it. */
    void releaseAppMemory() {
        RamWidget.unload(this); loaded = false;
        previewFile().delete(); System.gc();
        ui.post(() -> {
            if (img != null) { img.setImageDrawable(null); showImg(false); placeholder.setVisibility(View.VISIBLE); }
            if (state != null) setModelUi(false, false, "");
            RamWidget.updateAll(this);
            ui.post(ramTick);
        });
    }

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
        Button ram = button("RAM", CARD, ACC);
        ram.setTextSize(12);
        ram.setOnClickListener(v -> freeMemoryDialog());
        LinearLayout.LayoutParams ramlp = new LinearLayout.LayoutParams(-2, dp(40)); ramlp.leftMargin = dp(6);
        tr.addView(ram, ramlp);
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

        qwenLoraBox = new LinearLayout(this); qwenLoraBox.setOrientation(LinearLayout.VERTICAL);
        qwenLoraBox.addView(label(L.s("LORA DE QWEN", "QWEN LORA")));
        LinearLayout qlr = new LinearLayout(this); qlr.setGravity(Gravity.CENTER_VERTICAL);
        qwenLoraName = text(L.s("Ninguna", "None"), 13, TXT, false); qwenLoraName.setSingleLine(true); qwenLoraName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        qwenLoraName.setBackground(bg(CARD, 14)); qwenLoraName.setPadding(dp(12), dp(11), dp(12), dp(11));
        qlr.addView(qwenLoraName, new LinearLayout.LayoutParams(0, -2, 1));
        qwenLoraPick = button(L.s("Elegir", "Choose"), CARD, ACC); qwenLoraPick.setOnClickListener(v -> pickQwenLora());
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, dp(42)); qlp.leftMargin = dp(8); qlr.addView(qwenLoraPick, qlp);
        qwenLoraDrop = button("✕", CARD, TXT); qwenLoraDrop.setOnClickListener(v -> { prefs().edit().remove("qwen_lora_dir").apply(); refreshQwenLora(); });
        LinearLayout.LayoutParams qld = new LinearLayout.LayoutParams(dp(48), dp(42)); qld.leftMargin = dp(6); qlr.addView(qwenLoraDrop, qld);
        qwenLoraBox.addView(qlr);
        // strength of a LoRA folded on the phone: changing it re-folds the overlay from the original file (a few seconds)
        qwenStrRow = new LinearLayout(this); qwenStrRow.setGravity(Gravity.CENTER_VERTICAL); qwenStrRow.setPadding(0, dp(6), 0, 0);
        qwenStrRow.addView(text(L.s("Intensidad", "Strength"), 14, TXT, false));
        qwenStr = new SeekBar(this); qwenStr.setMax(40); qwenStrRow.addView(qwenStr, new LinearLayout.LayoutParams(0, -2, 1));
        qwenStrVal = text("1.00", 14, TXT, true); qwenStrVal.setGravity(Gravity.CENTER); qwenStrVal.setBackground(bg(CARD, 12)); qwenStrRow.addView(qwenStrVal, new LinearLayout.LayoutParams(dp(64), dp(38)));
        qwenStrVal.setOnClickListener(x -> { if (!qwenFolding) typeQwenStrength(); });
        qwenStr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { qwenStrVal.setText(String.format(java.util.Locale.US, "%.2f", p * 0.05f)); }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { refoldQwen(b.getProgress() * 0.05f); }
        });
        qwenStrRow.setVisibility(View.GONE); qwenLoraBox.addView(qwenStrRow);
        LinearLayout qir = new LinearLayout(this); qir.setPadding(0, dp(7), 0, 0);
        Button qwenStImport = button(L.s("Importar .safetensors", "Import .safetensors"), CARD, ACC); qwenStImport.setAllCaps(false); qwenStImport.setTextSize(12);
        qwenStImport.setOnClickListener(v -> { try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 16); } catch (Exception e) { Toast.makeText(this, L.s("No se pudo abrir el selector de archivos", "Could not open the file picker"), Toast.LENGTH_LONG).show(); } });
        qir.addView(qwenStImport, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button qwenLoraImport = button(L.s("Importar overlay (.zip)", "Import overlay (.zip)"), CARD, ACC);
        qwenLoraImport.setAllCaps(false); qwenLoraImport.setTextSize(12);
        qwenLoraImport.setOnClickListener(v -> importQwenOverlay());
        LinearLayout.LayoutParams qil = new LinearLayout.LayoutParams(0, dp(42), 1); qil.leftMargin = dp(6); qir.addView(qwenLoraImport, qil);
        qwenLoraBox.addView(qir);
        TextView qwenLoraHint = text(L.s("Una LoRA de Qwen-Image 2.1 (.safetensors, también desde Civitai) se convierte en el teléfono en unos segundos.", "A Qwen-Image 2.1 LoRA (.safetensors, also from Civitai) is converted on the phone in a few seconds."), 11, DIM, false);
        qwenLoraHint.setPadding(dp(4), dp(5), 0, 0); qwenLoraBox.addView(qwenLoraHint);
        qwenLoraBox.setVisibility(View.GONE);
        root.addView(qwenLoraBox);
        refreshQwenLora();

        // result card
        imgBox = new LinearLayout(this);
        imgBox.setOrientation(LinearLayout.VERTICAL);
        imgBox.setGravity(Gravity.CENTER);
        imgBox.setBackground(new BracketCard(CARD, 24));
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
        showImg(false);
        img.setOnClickListener(v -> { if (lastFile != null) { File[] all = Gallery.list(this, Gallery.ALL); int k = java.util.Arrays.asList(all).indexOf(lastFile); Viewer.open(this, all, Math.max(0, k), Gallery.ALL, false); } });
        batchGestures = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { return img.performClick(); }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy) * 1.25f && Math.abs(vx) > dp(120)) {
                    showBatchPage(batchDisplayIndex + (dx < 0 ? 1 : -1)); return true;
                }
                return false;
            }
        });
        img.setOnTouchListener((v, e) -> batchGestures.onTouchEvent(e));
        // toolbar between the model state and the picture: ‹ 1/3 › on the left (batches only), favourite / download / share / ✕ on the right
        imgTools = new LinearLayout(this); imgTools.setGravity(Gravity.CENTER_VERTICAL); imgTools.setPadding(0, 0, 0, dp(8)); imgTools.setVisibility(View.GONE);
        pageBar = new LinearLayout(this); pageBar.setGravity(Gravity.CENTER_VERTICAL); pageBar.setBackground(bg(CARD, 14)); pageBar.setVisibility(View.GONE);
        TextView prevB = text("‹", 22, ACC, true), nextB = text("›", 22, ACC, true);
        for (TextView pb : new TextView[]{prevB, nextB}) { pb.setGravity(Gravity.CENTER); pb.setPadding(dp(14), 0, dp(14), dp(3)); }
        prevB.setOnClickListener(v -> showBatchPage(batchDisplayIndex - 1)); nextB.setOnClickListener(v -> showBatchPage(batchDisplayIndex + 1));
        pageTag = text("", 13, TXT, true); pageTag.setGravity(Gravity.CENTER); pageTag.setMinWidth(dp(92)); pageTag.setSingleLine(true);   // fixed width: the arrows stay put while the text changes
        pageBar.addView(prevB, new LinearLayout.LayoutParams(-2, dp(40))); pageBar.addView(pageTag); pageBar.addView(nextB, new LinearLayout.LayoutParams(-2, dp(40)));
        imgTools.addView(pageBar, new LinearLayout.LayoutParams(-2, dp(40)));
        imgTools.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        clearX = text("✕", 16, TXT, true); clearX.setGravity(Gravity.CENTER); clearX.setBackground(bg(CARD, 14)); clearX.setVisibility(View.GONE);
        clearX.setOnClickListener(v -> clearShown());
        root.addView(imgTools);
        imgBox.addView(img, new LinearLayout.LayoutParams(-1, -2));
        root.addView(imgBox, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout resRow = new LinearLayout(this);
        resRow.setGravity(Gravity.CENTER_VERTICAL);
        saved = text("", 12, DIM, false);
        saved.setPadding(dp(4), dp(8), dp(8), 0);
        resRow.addView(saved, new LinearLayout.LayoutParams(0, -2, 1));
        editGeneratedBtn = button(L.s("Editar con Qwen", "Edit with Qwen"), CARD, ACC);
        editGeneratedBtn.setTextSize(11); editGeneratedBtn.setVisibility(View.GONE);
        editGeneratedBtn.setOnClickListener(v -> useShownImageAsQwenSource());
        editRow = new LinearLayout(this); editRow.setGravity(Gravity.CENTER_VERTICAL); editRow.setPadding(0, dp(6), 0, 0);   // own row: the seed text and the icons keep their width
        editRow.addView(editGeneratedBtn, new LinearLayout.LayoutParams(0, dp(40), 1));
        compareBtn = button(L.s("Antes/después", "Before/after"), CARD, ACC);
        compareBtn.setTextSize(11); compareBtn.setVisibility(View.GONE);
        compareBtn.setOnClickListener(v -> showBeforeAfter());
        LinearLayout.LayoutParams cmpl = new LinearLayout.LayoutParams(0, dp(40), 1); cmpl.leftMargin = dp(6); editRow.addView(compareBtn, cmpl);
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
            if (x == againBtn) resRow.addView(x, lp); else imgTools.addView(x, lp);   // "again" stays by the seed; the file actions go to the toolbar
        }
        LinearLayout.LayoutParams cxl = new LinearLayout.LayoutParams(dp(46), dp(40)); cxl.leftMargin = dp(6); imgTools.addView(clearX, cxl);
        editRow.setVisibility(View.GONE); root.addView(resRow); root.addView(editRow);

        // prompt
        LinearLayout pl = new LinearLayout(this);
        pl.setGravity(Gravity.CENTER_VERTICAL);
        pl.addView(label(L.s("ОПИСАНИЕ", "PROMPT")), new LinearLayout.LayoutParams(0, -2, 1));
        TextView lib = link("LoRAs");
        lib.setOnClickListener(v -> loraLibrary());
        pl.addView(lib);
        TextView tpl = link(L.s("Шаблоны", "Templates"));
        tpl.setOnClickListener(v -> templates());
        pl.addView(tpl);
        TextView clr = link(L.s("Очистить", "Clear"));
        clr.setOnClickListener(v -> prompt.setText(""));
        pl.addView(clr);
        root.addView(pl);
        prompt = new MultiAutoCompleteTextView(this);   // Danbooru tag suggestions while typing, comma separated
        prompt.setHint("1girl, smile, outdoors, cherry blossoms…");
        prompt.setHintTextColor(DIM); prompt.setTextColor(TXT); prompt.setTextSize(17); prompt.setTypeface(F_REG);
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
        cleanWhileTyping(prompt);
        loadTags();
        // under the prompt: [# Tags] [(−)] [(+)] ........ token count
        LinearLayout tb = new LinearLayout(this); tb.setGravity(Gravity.CENTER_VERTICAL); tb.setPadding(0, dp(6), 0, 0);
        Button tagsB = button(L.s("# Теги", "# Tags"), FIELD, ACC); tagsB.setTextSize(13); tagsB.setOnClickListener(v -> tagPalette());
        tb.addView(tagsB, new LinearLayout.LayoutParams(-2, dp(36)));
        Button wm = button("( − )", FIELD, TXT); wm.setTextSize(13); wm.setOnClickListener(v -> weigh(-.1f));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, dp(36)); wl.leftMargin = dp(6); tb.addView(wm, wl);
        Button wp = button("( + )", FIELD, TXT); wp.setTextSize(13); wp.setOnClickListener(v -> weigh(.1f));
        LinearLayout.LayoutParams wl2 = new LinearLayout.LayoutParams(-2, dp(36)); wl2.leftMargin = dp(6); tb.addView(wp, wl2);
        tokText = text("", 12, DIM, false); tokText.setGravity(Gravity.END); tokText.setPadding(0, 0, dp(6), 0);
        tb.addView(tokText, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(tb, new LinearLayout.LayoutParams(-1, -2));
        prompt.addTextChangedListener(watch(s -> countTokens()));
        countTokens();

        // sampling, right under the prompt (Anima models; stored in the same prefs as before): negative prompt, steps, CFG, clip skip, sampler, scheduler
        sampBox = new LinearLayout(this); sampBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout negHead = new LinearLayout(this); negHead.setGravity(Gravity.CENTER_VERTICAL);
        negHead.addView(label(L.s("НЕГАТИВНЫЙ ПРОМПТ", "NEGATIVE PROMPT")), new LinearLayout.LayoutParams(0, -2, 1));
        TextView negDef = link(L.s("По умолчанию", "Default")); negDef.setOnClickListener(v -> negEdit.setText(Settings.turbo(this) ? "" : Settings.NEG_DEFAULT)); negHead.addView(negDef);
        TextView negClr = link(L.s("Очистить", "Clear")); negClr.setOnClickListener(v -> negEdit.setText("")); negHead.addView(negClr);
        sampBox.addView(negHead);
        negEdit = field("lowres, worst quality, bad anatomy…");
        negEdit.setText(Settings.negative(this)); negEdit.setMinLines(2); negEdit.setGravity(Gravity.TOP); negEdit.setBackground(bg(CARD, 14));
        negEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        negEdit.addTextChangedListener(watch(s -> { prefs().edit().putString(Settings.key(this, "neg"), s).apply(); countTokens(); }));
        cleanWhileTyping(negEdit);
        sampBox.addView(negEdit);
        negTokText = text("", 12, DIM, false); negTokText.setGravity(Gravity.END); negTokText.setPadding(0, dp(4), dp(6), 0); sampBox.addView(negTokText);   // encoded separately from the prompt: its own count
        sampFold = fold(sampBox, L.s("СЭМПЛИНГ", "SAMPLING"), "samp");
        LinearLayout nr = new LinearLayout(this); nr.setGravity(Gravity.CENTER_VERTICAL);
        stepsEdit = numField(nr, L.s("ШАГИ", "STEPS"), String.valueOf(Settings.steps(this)), false, s -> { try { int v = Integer.parseInt(s.trim()); if (v >= 1 && v <= 60) prefs().edit().putInt(Settings.key(this, "steps"), v).apply(); } catch (NumberFormatException e) { } });
        cfgEdit = numField(nr, "CFG", Settings.cfg(this), true, s -> { try { float v = Float.parseFloat(s.trim().replace(',', '.')); if (v >= 1 && v <= 30) prefs().edit().putString(Settings.key(this, "cfg"), s.trim().replace(',', '.')).apply(); } catch (NumberFormatException e) { } });
        sampFold.body.addView(nr);
        infoOn((TextView) ((LinearLayout) stepsEdit.getParent()).getChildAt(0), L.s("Pasos", "Steps"), L.s("Cuántas veces el modelo refina la imagen. Los modelos turbo están hechos para 4 pasos; los modelos base suelen necesitar 20–30. Más pasos tardan más y no siempre mejoran el detalle.", "How many times the model refines the picture. Turbo models are made for 4 steps; base models usually need 20–30. More steps take longer and do not always add detail."));
        infoOn((TextView) ((LinearLayout) cfgEdit.getParent()).getChildAt(0), "CFG", L.s("Cuánto sigue el modelo al prompt frente al prompt negativo. Con 1 el negativo no se usa y cada paso cuesta la mitad. Los modelos y LoRAs turbo están hechos para CFG 1; los modelos base suelen ir bien con 3–5.", "How strongly the model follows the prompt against the negative prompt. At 1 the negative is not used and each step costs half. Turbo models and LoRAs are made for CFG 1; base models usually work well at 3–5."));
        TextView smCap = text(L.s("SAMPLER · PLANIFICADOR", "SAMPLER · SCHEDULER"), 11, DIM, true); smCap.setLetterSpacing(.12f); smCap.setPadding(dp(4), dp(10), 0, 0);
        infoOn(smCap, L.s("Sampler y planificador", "Sampler and scheduler"), L.s("El sampler decide cómo se quita el ruido en cada paso y el planificador cómo se reparten los pasos entre mucho y poco ruido. Euler con «Normal» es lo más seguro. Los samplers de 2.º orden duplican el tiempo. Si no estás seguro, deja los valores por defecto (↺).", "The sampler decides how the noise is removed at each step and the scheduler how the steps are spread from heavy to light noise. Euler with Normal is the safest. Second-order samplers double the time. If unsure, keep the defaults (↺)."));
        sampFold.body.addView(smCap);
        LinearLayout ar = new LinearLayout(this); ar.setGravity(Gravity.CENTER_VERTICAL); ar.setPadding(0, dp(4), 0, 0);
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
        sampFold.body.addView(ar);
        cutBtn = button("", CARD, TXT); cutBtn.setAllCaps(false);
        cutBtn.setOnClickListener(v -> {
            String[] names = new String[Settings.CFG_CUT_CHOICES.length];
            for (int i = 0; i < names.length; i++) names[i] = Settings.CFG_CUT_CHOICES[i] >= 100 ? L.s("На всех шагах (обычно)", "On every step (normal)") : L.f("На первых %d %% шагов", "On the first %d %% of the steps", Settings.CFG_CUT_CHOICES[i]);
            int cur = 0; for (int i = 0; i < Settings.CFG_CUT_CHOICES.length; i++) if (Settings.CFG_CUT_CHOICES[i] == Settings.cfgCut(this)) cur = i;
            builder().setTitle(L.s("CFG только в начале", "CFG only at the start")).setSingleChoiceItems(names, cur, (d, i) -> { d.dismiss(); prefs().edit().putInt("cfg_cut", Settings.CFG_CUT_CHOICES[i]).apply(); refreshSampling(); }).show();
        });
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(-1, dp(44)); cbl.topMargin = dp(8); sampFold.body.addView(cutBtn, cbl);
        sampNote = text("", 12, DIM, false); sampNote.setPadding(dp(4), dp(6), dp(4), 0); sampFold.body.addView(sampNote);
        root.addView(sampBox);
        mossStepsBox = new LinearLayout(this); mossStepsBox.setOrientation(LinearLayout.VERTICAL);
        mossStepsBtn = button("", CARD, TXT); mossStepsBtn.setAllCaps(false);
        mossStepsBtn.setOnClickListener(v -> {
            {
                java.util.ArrayList<String> choices = new java.util.ArrayList<>(); choices.add("4 step · rapid-moss");
                if (canUseRsDmd()) choices.add("8 step · DMD");
                int cur = prefs().getString("rs_mode", "4").equals("8") && choices.size() > 1 ? 1 : 0;
                builder().setTitle(L.s("Modo RealSkin", "RealSkin mode")).setSingleChoiceItems(choices.toArray(new String[0]), cur, (d, i) -> { d.dismiss(); prefs().edit().putString("rs_mode", i == 1 ? "8" : "4").apply(); refreshSampling(); }).show();
            }
        });
        mossStepsBox.addView(mossStepsBtn);
        TextView mossSamplingNote = text(L.s("RealSkin: rapid-moss de 4 pasos o DMD de 8 pasos (más detalle, ~1,6× más lento).", "RealSkin: 4-step rapid-moss or 8-step DMD (more detail, about 1.6× slower)."), 12, DIM, false);
        mossSamplingNote.setPadding(dp(4), dp(6), dp(4), dp(10)); mossStepsBox.addView(mossSamplingNote);
        root.addView(mossStepsBox);
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
        TextView seedLabel = label(L.s("SEED  ·  -1 = случайный", "SEED  ·  -1 = random")); root.addView(seedLabel);
        infoOn(seedLabel, "Seed", L.s("El número que fija el ruido inicial. La misma semilla con el mismo prompt, modelo y ajustes da la misma imagen; -1 elige una al azar en cada imagen. El dado pone una nueva.", "The number that fixes the starting noise. The same seed with the same prompt, model and settings gives the same picture; -1 picks a random one for every picture. The die sets a new one."));
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
        loraFold = fold(loraBox, L.s("ФАЙЛ LORA", "LORA FILE"), "lora");
        LinearLayout lr = new LinearLayout(this); lr.setGravity(Gravity.CENTER_VERTICAL);
        loraName = text(L.s("не выбрана", "none"), 14, TXT, false); loraName.setSingleLine(true); loraName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        loraName.setBackground(bg(CARD, 16)); loraName.setPadding(dp(16), dp(12), dp(16), dp(12));
        lr.addView(loraName, new LinearLayout.LayoutParams(0, -2, 1));
        loraPick = button(L.s("Выбрать", "Choose"), CARD, TXT); loraPick.setOnClickListener(v -> pickLora());
        LinearLayout.LayoutParams pl2 = new LinearLayout.LayoutParams(-2, dp(44)); pl2.leftMargin = dp(8); lr.addView(loraPick, pl2);
        loraDrop = button("✕", CARD, TXT); loraDrop.setOnClickListener(v -> dropLora());
        LinearLayout.LayoutParams dl2 = new LinearLayout.LayoutParams(dp(48), dp(44)); dl2.leftMargin = dp(8); lr.addView(loraDrop, dl2);
        loraFold.body.addView(lr);
        loraInfo = text("", 12, DIM, false); loraInfo.setPadding(dp(4), dp(6), dp(4), 0); loraFold.body.addView(loraInfo);
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
        loraFold.body.addView(sr2);
        root.addView(loraBox);
        refreshLora();

        // Qwen-Image: a photo to edit (with it the picture is an edit of the photo, guided by the prompt)
        editBox = new LinearLayout(this); editBox.setOrientation(LinearLayout.VERTICAL);
        editBox.addView(label(L.s("РЕДАКТИРОВАНИЕ ФОТО  ·  необязательно", "EDIT A PHOTO  ·  optional")));
        LinearLayout er = new LinearLayout(this); er.setGravity(Gravity.CENTER_VERTICAL);
        editThumb = new ImageView(this); editThumb.setScaleType(ImageView.ScaleType.CENTER_CROP); editThumb.setBackground(bg(CARD, 16)); editThumb.setClipToOutline(true);
        er.addView(editThumb, new LinearLayout.LayoutParams(dp(64), dp(64)));
        editInfo = text("", 13, DIM, false); editInfo.setPadding(dp(12), 0, dp(8), 0); er.addView(editInfo, new LinearLayout.LayoutParams(0, -2, 1));
        editPick = button(L.s("Выбрать", "Choose"), CARD, TXT); editPick.setOnClickListener(v -> pickEdit());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, dp(44)); ep.leftMargin = dp(8); er.addView(editPick, ep);
        editDrop = button("✕", CARD, TXT); editDrop.setOnClickListener(v -> dropEdit());
        LinearLayout.LayoutParams ed2 = new LinearLayout.LayoutParams(dp(48), dp(44)); ed2.leftMargin = dp(8); er.addView(editDrop, ed2);
        editBox.addView(er);
        LinearLayout em = new LinearLayout(this); em.setPadding(0, dp(6), 0, 0);
        editMaskBtn = button(L.s("✎ Область изменения", "✎ Area to edit"), FIELD, ACC); editMaskBtn.setTextSize(12);
        editMaskBtn.setOnClickListener(v -> paintQwenMask()); em.addView(editMaskBtn, new LinearLayout.LayoutParams(0, dp(38), 1));
        editMaskClear = button(L.s("Очистить область", "Clear area"), FIELD, TXT); editMaskClear.setTextSize(12);
        editMaskClear.setOnClickListener(v -> { Qwen.editMask(getFilesDir()).delete(); refreshEdit(); });
        LinearLayout.LayoutParams emc = new LinearLayout.LayoutParams(0, dp(38), 1); emc.leftMargin = dp(8); em.addView(editMaskClear, emc);
        editBox.addView(em);
        editOpts = new LinearLayout(this); editOpts.setOrientation(LinearLayout.VERTICAL);
        sw(editOpts, L.s("Forma de la foto", "Photo's shape"), L.s("La edición sale vertical, horizontal o cuadrada como la foto, con un tamaño parecido al elegido. Desactívalo para usar exactamente el tamaño elegido.", "The edit comes out portrait, landscape or square like the photo, at about the size you picked. Turn it off to use exactly the picked size."), "qwen_keep_aspect", true);
        sw(editOpts, L.s("Alta fidelidad", "High fidelity"), L.s("La foto de referencia sigue el tamaño de salida (hasta 1 Mpx) en vez de 0,4 Mpx: conserva más detalle, tarda más y usa más memoria.", "The reference photo follows the output size (up to 1 Mpx) instead of 0.4 Mpx: keeps more detail, takes longer and uses more memory."), "qwen_hifi", false);
        sw(editOpts, L.s("Guardar comparación", "Save a comparison"), L.s("Además de la edición, guarda la original y la editada lado a lado; usa ‹ › sobre la imagen para verla.", "Besides the edit, saves the original and the edit side by side; use ‹ › above the picture to see it."), "qwen_compare", false);
        editBox.addView(editOpts);
        root.addView(editBox);
        refreshEdit();

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
        count = Math.max(1, Math.min(999, prefs().getInt("count", 1)));
        countBtn = button("×" + count, FIELD, TXT);
        countBtn.setTextSize(16);
        countBtn.setOnClickListener(v -> pickCount());
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
        PngMeta.keep = Settings.keepMeta(this);
        super.onResume();
        visible = true;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(2);
        String del = ModelsActivity.deleted; ModelsActivity.deleted = "";
        if (!del.isEmpty() && del.equals(model) && loaded && !busy) { loaded = false; ex.submit(() -> su("sh " + D + "/anima.sh stop < /dev/null >/dev/null 2>&1", false)); }
        if (genBtn != null) { refreshModels(); restoreLatestGenerated(); ui.post(ramTick); }
        ex2.submit(() -> RamWidget.updateAll(getApplicationContext()));   // a widget placed before an update keeps its old buttons until it is redrawn
        if (android.os.Build.VERSION.SDK_INT >= 33 && prefs().getBoolean("welcomed", false) && !prefs().getBoolean("asked_notif", false)) {   // once, after the welcome screen
            prefs().edit().putBoolean("asked_notif", true).apply();
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5);
        }
    }

    /** Restore the last saved picture after Android recreates the activity or process. */
    void restoreLatestGenerated() {
        if (busy || lastFile != null || img == null) return;
        File[] recent = Gallery.list(this, Gallery.ALL);
        if (recent.length == 0) return;
        File f = recent[0];
        if (f.getName().equals(prefs().getString("hidden_last", ""))) return;   // cleared with ✕ and nothing new since
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
        int sample = 1, maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxDim / sample > 2048 && sample < (1 << 20)) sample *= 2;
        BitmapFactory.Options opt = new BitmapFactory.Options(); opt.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opt);
        if (b == null) return;
        lastFile = f; batchFiles.clear(); batchGridFile = null; batchDisplayIndex = 0;
        img.setImageBitmap(b); showImg(true); placeholder.setVisibility(View.GONE);
        setFavIcon(Gallery.fav(f));
        for (ImageButton x : new ImageButton[]{againBtn, favBtn, dlBtn, shareBtn}) x.setVisibility(View.VISIBLE);
        refreshGeneratedEditAction();
        JSONObject m = Gallery.meta(f);
        saved.setText(m.has("grid") ? L.f("Última cuadrícula · %d imágenes", "Last grid · %d pictures", m.optInt("count", 0)) : m.optBoolean("compare") ? L.s("Última comparación", "Last comparison") : L.f("Última imagen · seed %s", "Last image · seed %s", m.optString("seed", "?")));
    }

    /** every 2 s: RAM strip, thermal / battery / chip warnings; every 10 s the model state (the idle watchdog may have unloaded it) */
    volatile double appGb; volatile boolean appBusy;
    final Runnable ramTick = new Runnable() {
        @Override public void run() {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
            double g = 1024.0 * 1024 * 1024, tot = mi.totalMem / g, free = mi.availMem / g, used = tot - free;
            if (!appBusy) { appBusy = true; new Thread(() -> { appGb = Compat.appRamGb(); appBusy = false; }).start(); }   // /proc scan off the UI thread
            ramTxt.setText(L.f("ОЗУ  всего %.1f  ·  занято %.1f  ·  свободно %.1f ГБ", "RAM  total %.1f  ·  used %.1f  ·  free %.1f GB", tot, used, free) + (appGb > 0.05 ? L.f("  ·  приложение %.1f ГБ", "  ·  app %.1f GB", appGb) : ""));
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
        if (req == 14 && res == RESULT_OK && d != null) {
            appendPromptWords(d.getStringExtra("moss_triggers"));
            String qid = d.getStringExtra("qwen_fold");   // a Qwen LoRA downloaded from Civitai: convert it here, on the phone
            if (qid != null) foldQwen(qid, 1f, d.getStringExtra("qwen_name"), d.getStringExtra("qwen_triggers"), true);
            refreshModels(); return;
        }
        if (req == 15 && res == RESULT_OK && d != null && d.getData() != null) {
            final Uri uri = d.getData(); String displayName = "qwen_lora.zip";
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) { int k = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (k >= 0) displayName = c.getString(k); }
            } catch (Exception ignored) { }
            final String pickedName = displayName;
            final AlertDialog importing = builder().setMessage(L.s("Importando y verificando el pack LoRA…", "Importing and verifying the LoRA pack…")).setCancelable(false).create(); importing.show();
            ex2.submit(() -> {
                try {
                    String name = Qwen.importOverlayZip(this, getFilesDir(), uri, pickedName);
                    prefs().edit().putString("qwen_lora_dir", name).apply();
                    ui.post(() -> { importing.dismiss(); refreshQwenLora(); Toast.makeText(this, L.f("Overlay Qwen importado y seleccionado: %s", "Qwen overlay imported and selected: %s", name), Toast.LENGTH_LONG).show(); });
                } catch (Exception e) { ui.post(() -> { importing.dismiss(); Toast.makeText(this, L.f("No se pudo importar el overlay Qwen: %s", "Could not import the Qwen overlay: %s", e.getMessage()), Toast.LENGTH_LONG).show(); }); }
            });
            return;
        }
        if (req == 9 && res == RESULT_OK && d != null && d.getData() != null) { importLora(d.getData()); return; }
        if (req == 16 && res == RESULT_OK && d != null && d.getData() != null) { importQwenSafetensors(d.getData()); return; }
        if (req == 11 && res == RESULT_OK && d != null && d.getData() != null) { importInit(d.getData()); return; }
        if (req == 12 && res == RESULT_OK && d != null && d.getData() != null) { importAd(d.getData()); return; }
        if (req == 13 && res == RESULT_OK && d != null && d.getData() != null) { importCheckpoint(d.getData()); return; }
        if (req == 10 && res == RESULT_OK && d != null && d.getData() != null) { importEdit(d.getData()); return; }
        if (req == 7 && res == RESULT_OK && d != null) {
            prompt.setText(d.getStringExtra("prompt")); seed.setText(d.getStringExtra("seed"));
            customOpen = false;
            setRes(d.getIntExtra("w", 512), d.getIntExtra("h", 768));
            // the sampling of that picture too (stored for the current model), and its model when it is still installed
            String mid = d.getStringExtra("id"); if (mid != null && ids.contains(mid) && !mid.equals(model) && !busy) setModel(mid);
            SharedPreferences.Editor pe = prefs().edit();
            try { if (d.hasExtra("steps")) pe.putInt(Settings.key(this, "steps"), Integer.parseInt(d.getStringExtra("steps"))); } catch (NumberFormatException ignored) { }
            if (d.hasExtra("cfg")) pe.putString(Settings.key(this, "cfg"), d.getStringExtra("cfg"));
            if (d.hasExtra("sampler")) pe.putString(Settings.key(this, "sampler"), d.getStringExtra("sampler"));
            if (d.hasExtra("scheduler")) pe.putString(Settings.key(this, "sched"), d.getStringExtra("scheduler"));
            if (d.hasExtra("negative")) pe.putString(Settings.key(this, "neg"), d.getStringExtra("negative"));
            pe.apply(); sampFill = false; refreshSampling();
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

    /** Add downloaded MOSS trigger words to the prompt once, without writing an unsupported <lora:...> tag. */
    void appendPromptWords(String words) {
        if (words == null || words.trim().isEmpty() || prompt == null) return;
        List<String> cur = tokens(prompt.getText().toString());
        Set<String> have = new HashSet<>(); for (String t : cur) have.add(t.toLowerCase(java.util.Locale.ROOT));
        for (String t : tokens(words)) if (have.add(t.toLowerCase(java.util.Locale.ROOT))) cur.add(t);
        prompt.setText(android.text.TextUtils.join(", ", cur)); prompt.setSelection(prompt.getText().length());
        Toast.makeText(this, L.s("Palabras activadoras de la LoRA añadidas al prompt", "LoRA trigger words added to the prompt"), Toast.LENGTH_SHORT).show();
    }


    // ---- Qwen-Image: photo to edit ------------------------------------------------------------------
    boolean isQwen() { return Qwen.is(model); }
    boolean isMage() { return Mage.is(model); }
    void refreshEdit() {
        if (editBm == null && prefs().getBoolean("qwen_edit_on", false) && Qwen.editSrc(getFilesDir()).exists()) editBm = BitmapFactory.decodeFile(Qwen.editSrc(getFilesDir()).getPath());
        final Bitmap b = editBm;
        editThumb.setImageBitmap(b); editThumb.setVisibility(b != null ? View.VISIBLE : View.GONE);
        boolean masked = Qwen.editMask(getFilesDir()).isFile();
        editInfo.setText(b != null ? isMage() ? L.f("Foto %d×%d. Describe cómo transformarla. Mage reconstruye la imagen completa y puede cambiar detalles y composición.", "Photo %d×%d. Describe how to transform it. Mage redraws the whole image and may change details and composition.", b.getWidth(), b.getHeight()) : L.f("Фото %d×%d. Describe qué cambiar, por ejemplo «haz que el cielo parezca de atardecer». Pinta solo la zona que quieras editar.", "Photo %d×%d. The prompt says what to change, e.g. \"make the sky a sunset\". Paint only the area to edit.", b.getWidth(), b.getHeight()) + (masked ? L.s("\nMáscara: Qwen cambia la zona pintada; el resto de la foto y su resolución se conservan.", "\nMask: Qwen changes the painted area; the rest of the photo and its resolution are preserved.") : L.s("\nSin máscara, Qwen puede redibujar toda la foto y alterar detalles finos. Usa «Máscara» para conservar el resto.", "\nWithout a mask, Qwen can redraw the whole photo and alter fine details. Use Mask to preserve the rest."))
            : L.s("Выберите фото, чтобы изменить его по описанию. Без фото модель просто рисует картинку.", "Pick a photo to change it by the prompt. Without one the model just draws a picture."));
        editDrop.setVisibility(b != null ? View.VISIBLE : View.GONE);
        editMaskBtn.setVisibility(b != null && !isMage() ? View.VISIBLE : View.GONE);
        editMaskClear.setVisibility(b != null && masked && !isMage() ? View.VISIBLE : View.GONE);
        if (editOpts != null) editOpts.setVisibility(b != null && isQwen() ? View.VISIBLE : View.GONE);
    }
    void showImg(boolean on) {
        img.setVisibility(on ? View.VISIBLE : View.GONE);
        if (clearX != null) clearX.setVisibility(on ? View.VISIBLE : View.GONE);
        if (imgTools != null) imgTools.setVisibility(on ? View.VISIBLE : View.GONE);
    }
    /** take the shown picture off the main screen; it stays in the History and does not come back on the next start */
    void clearShown() {
        if (busy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        File[] recent = Gallery.list(this, Gallery.ALL);
        prefs().edit().putString("hidden_last", recent.length > 0 ? recent[0].getName() : "").apply();
        lastFile = null; batchFiles.clear(); batchGridFile = null; batchDisplayIndex = 0;
        img.setImageDrawable(null); showImg(false); placeholder.setVisibility(View.VISIBLE);
        for (ImageButton x : new ImageButton[]{againBtn, favBtn, dlBtn, shareBtn}) x.setVisibility(View.GONE);
        saved.setText(""); refreshGeneratedEditAction();
    }
    void refreshGeneratedEditAction() {
        if (pageTag != null) {
            int n = batchFiles.size(); boolean grid = batchGridFile != null && batchGridFile.isFile();
            boolean show = lastFile != null && img.getVisibility() == View.VISIBLE && (n > 1 || (n == 1 && grid));
            pageBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) pageTag.setText(batchDisplayIndex >= n ? (n == 1 ? L.s("Original | editada", "Original | edited") : L.f("Cuadrícula · %d", "Grid · %d", n)) : (batchDisplayIndex + 1) + " / " + n);
        }
        boolean sheet = lastFile != null && (lastFile == batchGridFile || Gallery.meta(lastFile).has("grid") || Gallery.meta(lastFile).optBoolean("compare"));   // a contact sheet or a comparison is not a picture to edit
        if (editGeneratedBtn != null) editGeneratedBtn.setVisibility(lastFile != null && lastFile.isFile() && !sheet ? View.VISIBLE : View.GONE);
        if (compareBtn != null) compareBtn.setVisibility(lastFile != null && lastFile != batchGridFile && Qwen.beforeFile(getFilesDir(), lastFile).isFile() ? View.VISIBLE : View.GONE);
        if (editRow != null) editRow.setVisibility(editGeneratedBtn.getVisibility() == View.VISIBLE || compareBtn.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    /** the edit over the original: drag the divider to reveal one or the other; "Save side by side" stores a comparison picture */
    class BeforeAfterView extends View {
        final Bitmap before, after; float split = .5f; final android.graphics.Rect dst = new android.graphics.Rect(); final android.graphics.Rect bsrc;
        final android.graphics.Paint fp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG), lp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        BeforeAfterView(Bitmap before, Bitmap after) {
            super(Main.this); this.before = before; this.after = after; bsrc = Qwen.coverRect(before, after.getWidth(), after.getHeight());
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
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            if (dst.width() > 0) { split = Math.max(0f, Math.min(1f, (e.getX() - dst.left) / dst.width())); invalidate(); }
            return true;
        }
    }
    void showBeforeAfter() {
        final File after = lastFile; if (after == null) return;
        final File bf = Qwen.beforeFile(getFilesDir(), after); if (!bf.isFile()) return;
        final Bitmap a = BitmapFactory.decodeFile(after.getPath()), b = BitmapFactory.decodeFile(bf.getPath()); if (a == null || b == null) return;
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
            File f = saveSideBySide(b, a, Gallery.meta(after));
            Toast.makeText(this, f != null ? L.s("Comparación guardada en el historial", "Comparison saved to the history") : L.s("No se pudo guardar la comparación", "Could not save the comparison"), Toast.LENGTH_SHORT).show();
        });
    }
    /** stores "original | edit" in the history (the prompt and seed of the edit go into its metadata) */
    File saveSideBySide(Bitmap before, Bitmap after, JSONObject editMeta) {
        Bitmap g = Qwen.sideBySide(before, after, L.s("Original", "Original"), L.s("Editada", "Edited"), F_SEMI);
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); g.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            JSONObject meta = new JSONObject().put("prompt", editMeta.optString("prompt")).put("seed", editMeta.optString("seed")).put("w", g.getWidth()).put("h", g.getHeight())
                .put("model", editMeta.optString("model")).put("id", editMeta.optString("id")).put("compare", true);
            return Gallery.save(this, PngMeta.embed(bytes.toByteArray(), meta), meta);
        } catch (Exception e) { return null; }
        finally { g.recycle(); }
    }
    void useShownImageAsQwenSource() {
        if (busy) { Toast.makeText(this, L.s("Espera a que termine la tanda", "Wait for the batch to finish"), Toast.LENGTH_SHORT).show(); return; }
        final File chosen = lastFile;
        if (chosen == null || !chosen.isFile() || chosen == batchGridFile) return;
        String qwenId = "";
        for (String id : store.installed()) if (Qwen.is(id)) { qwenId = id; break; }
        if (qwenId.isEmpty()) { Toast.makeText(this, L.s("Instala Qwen-Image antes de editar con él", "Install Qwen-Image before editing with it"), Toast.LENGTH_LONG).show(); return; }
        final String targetModel = qwenId;
        editGeneratedBtn.setEnabled(false);
        ex2.submit(() -> {
            Bitmap input = BitmapFactory.decodeFile(chosen.getPath());
            if (input == null) { ui.post(() -> { editGeneratedBtn.setEnabled(true); Toast.makeText(this, L.s("No se pudo abrir la imagen", "Could not open this image"), Toast.LENGTH_LONG).show(); }); return; }
            try {
                Bitmap next = Qwen.storePhoto(input, getFilesDir());
                if (next != input) input.recycle();
                Models.deleteTree(Qwen.editDir(getFilesDir()));
                prefs().edit().putBoolean("qwen_edit_on", true).apply();
                ui.post(() -> {
                    editGeneratedBtn.setEnabled(true); editBm = next; setModel(targetModel); refreshEdit();
                    double aspect = (double) next.getWidth() / next.getHeight(), best = 1e9; int[] pick = null;
                    for (int[] z : Qwen.PRESETS) { if (z[0] * z[1] > 800000) continue; double d = Math.abs(Math.log((double) z[0] / z[1] / aspect)) + Math.abs(z[0] * z[1] - 393216) / 5e7; if (d < best) { best = d; pick = z; } }
                    if (pick != null) { customOpen = false; setRes(pick[0], pick[1]); }
                    paintQwenMask();
                });
            } catch (Exception e) {
                if (!input.isRecycled()) input.recycle(); ui.post(() -> { editGeneratedBtn.setEnabled(true); Toast.makeText(this, L.f("No se pudo preparar la imagen: %s", "Could not prepare the image: %s", e.getMessage()), Toast.LENGTH_LONG).show(); });
            }
        });
    }
    void pickEdit() {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 10);
    }
    void dropEdit() {
        if (busy) return;
        editBm = null; prefs().edit().putBoolean("qwen_edit_on", false).apply();
        ex2.submit(() -> { Qwen.editSrc(getFilesDir()).delete(); Qwen.editMask(getFilesDir()).delete(); Models.deleteTree(Qwen.editDir(getFilesDir())); ui.post(this::refreshEdit); });
        setRes(resW, resH);
    }
    void importEdit(Uri uri) {
        ex2.submit(() -> {
            try {
                final Bitmap bm = Qwen.importPhoto(getContentResolver(), uri, getFilesDir());
                prefs().edit().putBoolean("qwen_edit_on", true).apply();
                ui.post(() -> {
                    editBm = bm; refreshEdit();
                    // the output size follows the photo's shape: the preset with the closest aspect ratio, at most 0.8 Mpx
                    double r = (double) bm.getWidth() / bm.getHeight(), best = 1e9; int[] pick = null;
                    for (int[] z : Qwen.PRESETS) { if (z[0] * z[1] > 800000) continue; double d = Math.abs(Math.log((double) z[0] / z[1] / r)) + Math.abs(z[0] * z[1] - 393216) / 5e7; if (d < best) { best = d; pick = z; } }
                    if (pick != null) { customOpen = false; setRes(pick[0], pick[1]); }
                });
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Не удалось открыть фото: %s", "Cannot open the photo: %s", e.getMessage()), Toast.LENGTH_LONG).show()); }
        });
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
    boolean loraFitsModel() {
        if (!isMoss() || loraId().isEmpty() || !loraSrc().exists()) return true;
        MossLora.Check c = MossLora.check(BIN, loraSrc());
        // LoKr reached the V73 DSP path but stalled at the first DiT step; only validated A/B adapters are enabled.
        return c.ok && !c.lokr;
    }
    boolean loraOn() { return !loraId().isEmpty() && loraSrc().exists() && loraStr() > 0 && loraFitsModel(); }
    boolean canUseRsDmd() {
        if (!Moss.isRs(model) || !Moss.installed8(getFilesDir())) return false;
        if (!loraOn()) return true;
        MossLora.Check c = MossLora.check(BIN, loraSrc());
        return c.ok && c.lokr;
    }
    void refreshLora() {
        boolean has = !loraId().isEmpty() && loraSrc().exists();
        loraName.setText(has ? prefs().getString(lp() + "lora_name", "LoRA") : L.s("не выбрана", "none"));
        String hint = L.s("Файл .safetensors, проверяется при выборе. Библиотека: «LoRAs» над промптом.", "A .safetensors file, checked when picked. Library: “LoRAs” above the prompt.");
        boolean fits = !has || loraFitsModel();
        String info = has ? prefs().getString(lp() + "lora_info", "") : hint;
        if (has && !fits) info = L.s("LoKr reconocido, pero desactivado hasta validar el kernel DSP V73. Usa una LoRA MOSS A/B compatible o genera sin LoKr.", "LoKr detected, but disabled until the V73 DSP kernel is validated. Use a compatible MOSS A/B LoRA or generate without LoKr.");
        loraFold.sum.setText(has ? prefs().getString(lp() + "lora_name", "LoRA") + String.format(java.util.Locale.US, " · %.2f", loraStr()) : L.s("нет", "none"));
        loraFold.sum.setTextColor(has ? ACC : DIM);
        loraInfo.setText(info);
        loraInfo.setTextColor(has ? (fits ? OK : WARN) : DIM);
        loraDrop.setVisibility(has ? View.VISIBLE : View.GONE); strBar.setEnabled(has && fits); strBar.setAlpha(has && fits ? 1f : .4f); strText.setEnabled(has && fits);
        strBar.setProgress(Math.round(Math.min(loraStr(), 2f) * 20));
        if (!strText.hasFocus()) strText.setText(String.format(java.util.Locale.US, "%.2f", loraStr()));
    }
    float loraStr() { return prefs().getFloat(lp() + "lora_str", 1f); }
    /** typed strength (0..3; the slider shows 0..2) */
    void applyStrText() {
        try { float v = Float.parseFloat(strText.getText().toString().trim().replace(',', '.')); v = Math.max(0f, Math.min(3f, v)); prefs().edit().putFloat(lp() + "lora_str", v).apply(); } catch (NumberFormatException ignored) { }
        strBar.setProgress(Math.round(Math.min(loraStr(), 2f) * 20)); strText.setText(String.format(java.util.Locale.US, "%.2f", loraStr()));
    }

    /** a section whose body folds away; the header keeps a one-line summary; the open state is kept in prefs (fold_<key>) */
    static class Fold { LinearLayout body; TextView sum, chev; }
    Fold fold(LinearLayout parent, String title, String key) {
        Fold f = new Fold();
        LinearLayout h = new LinearLayout(this); h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(label(title));
        f.sum = text("", 12, DIM, false); f.sum.setSingleLine(true); f.sum.setEllipsize(android.text.TextUtils.TruncateAt.END); f.sum.setGravity(Gravity.END);
        f.sum.setPadding(dp(10), dp(16), dp(6), dp(7));
        h.addView(f.sum, new LinearLayout.LayoutParams(0, -2, 1));
        f.chev = text("", 13, ACC, true); f.chev.setPadding(dp(4), dp(16), dp(2), dp(7)); h.addView(f.chev);
        parent.addView(h);
        f.body = new LinearLayout(this); f.body.setOrientation(LinearLayout.VERTICAL); parent.addView(f.body);
        Runnable apply = () -> { boolean o = prefs().getBoolean("fold_" + key, false); f.body.setVisibility(o ? View.VISIBLE : View.GONE); f.chev.setText(o ? "▴" : "▾"); };
        h.setOnClickListener(v -> { prefs().edit().putBoolean("fold_" + key, !prefs().getBoolean("fold_" + key, false)).apply(); apply.run(); });
        apply.run();
        return f;
    }
    /** small accent text action in a section header */
    TextView link(String s) { TextView t = text(s, 12, ACC, true); t.setPadding(dp(8), dp(16), dp(6), dp(7)); return t; }
    /** a small coloured badge */
    TextView badge(String s, int color) {
        TextView t = text(s, 11, color, true); t.setBackground(bg((color & 0x00FFFFFF) | 0x26000000, 8)); t.setPadding(dp(8), dp(2), dp(8), dp(3));
        return t;
    }

    /** line breaks, tabs, no-break and zero-width spaces become one space and runs of spaces collapse (a pasted prompt used to break at invisible characters) */
    static String clean(String s) { return s.replaceAll("[\\s\\u00a0\\u200b-\\u200d\\u2028\\u2029\\ufeff]+", " "); }
    /** keeps a prompt field clean while typing or pasting, without moving the cursor more than the removed characters */
    void cleanWhileTyping(EditText f) {
        f.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                String o = e.toString(), n = clean(o); if (n.equals(o)) return;
                int sel = Math.min(f.getSelectionStart(), n.length()); e.replace(0, e.length(), n); f.setSelection(Math.max(0, Math.min(sel, f.getText().length())));
            }
        });
    }

    // ---- LoRA picker: ticking adds only <lora:name:w> to the prompt, unticking takes them out ----
    static java.util.regex.Pattern loraTag(String n) { return java.util.regex.Pattern.compile("<lora:" + java.util.regex.Pattern.quote(n) + "(?::([^>]*))?>", java.util.regex.Pattern.CASE_INSENSITIVE); }
    /** the weight the prompt gives this LoRA, or null when the prompt does not call it */
    String loraWeight(String n) {
        java.util.regex.Matcher m = loraTag(n).matcher(prompt.getText()); if (!m.find()) return null;
        return m.group(1) == null || m.group(1).trim().isEmpty() ? "1.0" : m.group(1).trim();
    }
    static List<String> tokens(String s) { List<String> r = new ArrayList<>(); for (String t : s.split(",")) { t = t.trim(); if (!t.isEmpty()) r.add(t); } return r; }
    void loraAdd(String n, String w, String words) {
        List<String> cur = tokens(prompt.getText().toString()), low = new ArrayList<>();
        for (String t : cur) low.add(t.toLowerCase(java.util.Locale.ROOT));
        cur.add("<lora:" + n + ":" + w + ">");
        prompt.setText(android.text.TextUtils.join(", ", cur)); prompt.setSelection(prompt.getText().length());
    }
    void loraRemove(String n, String words) {
        String p = loraTag(n).matcher(prompt.getText().toString()).replaceAll("");
        List<String> keep = tokens(p);
        prompt.setText(android.text.TextUtils.join(", ", keep)); prompt.setSelection(prompt.getText().length());
    }
    void loraSetWeight(String n, String w) {
        String p = loraTag(n).matcher(prompt.getText().toString()).replaceAll(java.util.regex.Matcher.quoteReplacement("<lora:" + n + ":" + w + ">"));
        prompt.setText(p); prompt.setSelection(prompt.getText().length());
    }

    // ---- LoRA categories (character, clothing, pose, style…): chips over a library, from the Civitai tags or the user's own choice ----
    static boolean catMatch(String cat, String sel) { return sel.isEmpty() || (sel.equals("-") ? cat.isEmpty() : sel.equals(cat)); }
    /** All + the categories present in this library (+ Uncategorised when some have none); hidden when nothing is categorised */
    void fillCatChips(LinearLayout row, List<String> cats, String[] sel, Runnable apply) {
        row.removeAllViews();
        List<String> vals = new ArrayList<>(); vals.add("");
        for (String c : Civitai.CATS) if (cats.contains(c)) vals.add(c);
        if (cats.contains("")) vals.add("-");
        ((View) row.getParent()).setVisibility(vals.size() > 2 || (vals.size() == 2 && !vals.get(1).equals("-")) ? View.VISIBLE : View.GONE);
        for (String v : vals) {
            int k = 0; for (String c : cats) if (catMatch(c, v)) k++;
            Button b = button((v.isEmpty() ? L.s("Todas", "All") : Civitai.catName(v.equals("-") ? "" : v)) + "  " + k, v.equals(sel[0]) ? SEL : FIELD, v.equals(sel[0]) ? ACC : TXT); b.setTextSize(12);
            b.setOnClickListener(x -> { sel[0] = v; fillCatChips(row, cats, sel, apply); apply.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34)); if (row.getChildCount() > 0) lp.leftMargin = dp(6); row.addView(b, lp);
        }
    }
    /** long tap on a library LoRA: category, trigger words, and (Anima) delete */
    void loraMenu(String title, File f, String words, Runnable delete, Runnable reopen) {
        List<String> items = new ArrayList<>(); items.add(L.f("Categoría: %s…", "Category: %s…", Civitai.catName(Civitai.category(f)))); items.add(L.s("Copiar palabras activadoras", "Copy trigger words"));
        items.add(L.s("Editar palabras activadoras…", "Edit trigger words…")); items.add(L.s("Añadir activadoras al prompt", "Add trigger words to the prompt")); if (delete != null) items.add(L.s("Удалить", "Delete"));
        builder().setTitle(title).setItems(items.toArray(new String[0]), (d, i) -> {
            if (i == 0) {
                String[] names = new String[Civitai.CATS.length + 1]; names[0] = Civitai.catName("");
                for (int k = 0; k < Civitai.CATS.length; k++) names[k + 1] = Civitai.catName(Civitai.CATS[k]);
                builder().setTitle(L.s("Categoría", "Category")).setItems(names, (d2, k) -> { Civitai.setCategory(f, k == 0 ? "none" : Civitai.CATS[k - 1]); reopen.run(); }).show();
            } else if (i == 1) {
                if (words.isEmpty()) { Toast.makeText(this, L.s("Sin palabras activadoras declaradas", "No trigger words declared"), Toast.LENGTH_SHORT).show(); return; }
                android.content.ClipboardManager clip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText("LoRA triggers", words));
            } else if (i == 2) editTriggers(title, words, s -> { Civitai.setWords(f, s); reopen.run(); });
            else if (i == 3) { if (words.isEmpty()) Toast.makeText(this, L.s("Sin palabras activadoras: usa «Editar palabras activadoras…»", "No trigger words: use \"Edit trigger words…\""), Toast.LENGTH_LONG).show(); else appendPromptWords(words); }
            else if (delete != null) delete.run();
        }).show();
    }
    /** text box for the trigger words / trigger prompt of a LoRA (comma separated) */
    void editTriggers(String title, String words, java.util.function.Consumer<String> save) {
        EditText e = new EditText(this); e.setText(words); e.setHint(L.s("p. ej. estilo3d, personaje x", "e.g. 3dstyle, character x")); e.setMinLines(2); e.setTextColor(TXT); e.setSelection(e.getText().length());
        FrameLayout fl = new FrameLayout(this); fl.setPadding(dp(20), dp(8), dp(20), 0); fl.addView(e);
        builder().setTitle(L.f("Activadoras: %s", "Trigger words: %s", title)).setMessage(L.s("Palabras o frase que activan la LoRA, separadas por comas. Se añaden al prompt con «Añadir activadoras al prompt».", "Words or a phrase that activate the LoRA, comma separated. \"Add trigger words to the prompt\" puts them in the prompt."))
            .setView(fl).setNegativeButton(L.s("Cancelar", "Cancel"), null).setPositiveButton(L.s("Guardar", "Save"), (d, k) -> save.accept(e.getText().toString().trim())).show();
    }
    /** strength typed with the keyboard (0–2) */
    void typeQwenStrength() {
        EditText e = new EditText(this); e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); e.setText(qwenStrVal.getText()); e.setTextColor(TXT); e.setSelectAllOnFocus(true);
        FrameLayout fl = new FrameLayout(this); fl.setPadding(dp(20), dp(8), dp(20), 0); fl.addView(e);
        builder().setTitle(L.s("Intensidad de la LoRA (0 a 2)", "LoRA strength (0 to 2)")).setView(fl).setNegativeButton(L.s("Cancelar", "Cancel"), null).setPositiveButton("OK", (d, k) -> {
            float v; try { v = Float.parseFloat(e.getText().toString().replace(',', '.')); } catch (Exception x) { return; }
            v = Math.max(0f, Math.min(2f, v)); v = Math.round(v * 100f) / 100f;
            qwenStr.setProgress(Math.round(v / 0.05f)); qwenStrVal.setText(String.format(java.util.Locale.US, "%.2f", v)); refoldQwen(v);
        }).show();
    }
    /** files downloaded before the tags were stored: one tap fetches their Civitai tags */
    void addDetectLink(LinearLayout v, File dir, Runnable reopen) {
        final List<File> todo = new ArrayList<>(); File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) { if (!f.getName().endsWith(".safetensors")) continue; JSONObject m = Civitai.meta(f); if (m != null && !m.has("tags") && m.optLong("model_id") > 0) todo.add(f); }
        if (todo.isEmpty()) return;
        TextView l = link(L.f("Detectar categorías en Civitai (%d)", "Detect categories on Civitai (%d)", todo.size())); l.setPadding(dp(2), dp(8), 0, 0);
        l.setOnClickListener(x -> { l.setText(L.s("Consultando Civitai…", "Asking Civitai…")); l.setEnabled(false); ex2.submit(() -> { for (File f : todo) Civitai.fillTags(this, f); ui.post(reopen); }); });
        v.addView(l, v.getChildCount() - 1);
    }

    /** LoRA library: [✓] name · trigger words · weight, a filter on top; long tap offers trigger copying or deletion; Civitai finds more */
    void loraLibrary() {
        if (isMoss()) { mossLoraLibrary(); return; }
        final File dir = AnimaLora.dir(getFilesDir());
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(10));
        LinearLayout th = new LinearLayout(this); th.setGravity(Gravity.CENTER_VERTICAL);
        th.addView(text("LoRAs", 20, TXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        final TextView cnt = text("", 12, ACC, true); th.addView(cnt); v.addView(th);
        final EditText filter = field(L.s("Фильтр: имя или слово…", "Filter: name or word…")); filter.setSingleLine(true); filter.setTextSize(15);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2); fl.topMargin = dp(10); fl.bottomMargin = dp(8); v.addView(filter, fl);
        final HorizontalScrollView catScroll = new HorizontalScrollView(this); catScroll.setHorizontalScrollBarEnabled(false); final LinearLayout catRow = new LinearLayout(this); catScroll.addView(catRow);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(-1, -2); crl.bottomMargin = dp(8); v.addView(catScroll, crl);
        final List<String> rowCats = new ArrayList<>(); final String[] catSel = {""};
        ScrollView sv = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, dp(340)));
        LinearLayout bot = new LinearLayout(this); bot.setPadding(0, dp(10), 0, 0);
        Button civ = button("Civitai", FIELD, ACC); bot.addView(civ, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button imp = button(L.s("Импорт", "Import"), FIELD, ACC); LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, dp(44), 1); il.leftMargin = dp(8); bot.addView(imp, il);
        Button done = button(L.s("Готово", "Done"), ACC, ON_ACC); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(44), 1); dl.leftMargin = dp(8); bot.addView(done, dl);
        v.addView(bot);
        final AlertDialog dlg = dialog(v);
        civ.setOnClickListener(x -> { dlg.dismiss(); startActivityForResult(new Intent(this, CivitaiActivity.class), 14); });
        done.setOnClickListener(x -> dlg.dismiss());
        imp.setOnClickListener(x -> { dlg.dismiss(); pickLora(); });   // a local .safetensors from the phone's storage, checked like a Civitai download

        List<String> names = new ArrayList<>(); File[] lf = dir.listFiles();
        if (lf != null) for (File f : lf) if (f.getName().endsWith(".safetensors") && !f.getName().startsWith("new.")) names.add(f.getName().substring(0, f.getName().length() - 12));
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) list.addView(text(L.s("Пусто: скачайте LoRA с Civitai или импортируйте файл.", "Empty: download LoRAs from Civitai or import a file."), 14, DIM, false));
        final List<View> rows = new ArrayList<>(); final List<String> keys = new ArrayList<>(); final List<CheckBox> boxes = new ArrayList<>();
        final Runnable count = () -> { int k = 0; for (CheckBox b : boxes) if (b.isChecked()) k++; cnt.setText(k == 0 ? "" : L.f("активно: %d", "active: %d", k)); };
        for (String n : names) {
            File f = new File(dir, n + ".safetensors"); JSONObject md = Civitai.meta(f); final String words = Civitai.words(f);
            final String title0 = md != null && !md.optString("name").isEmpty() ? md.optString("name") : n, al = alias(true, "anima/" + n);
            String title = al.isEmpty() ? title0 : al;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(4), dp(8), dp(10), dp(8));
            String w0 = loraWeight(n);
            row.setBackground(bg(w0 != null ? SEL : FIELD, 12));
            CheckBox cb = new CheckBox(this); cb.setButtonTintList(ColorStateList.valueOf(ACC)); cb.setChecked(w0 != null);
            row.addView(cb);
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            TextView tn = text(title, 15, TXT, true); tn.setSingleLine(true); tn.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(tn);
            TextView sub = text(words.isEmpty() ? n : words, 12, DIM, false); sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView pen = text("✎", 16, DIM, false); pen.setPadding(dp(6), dp(4), dp(4), dp(4));
            pen.setOnClickListener(x -> renameDialog(true, "anima/" + n, title0, () -> { dlg.dismiss(); loraLibrary(); }));
            row.addView(pen);
            EditText we = field("1.0"); we.setText(w0 != null ? w0 : "1.0"); we.setTextSize(14); we.setGravity(Gravity.CENTER); we.setSingleLine(true);
            we.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); we.setPadding(dp(4), dp(6), dp(4), dp(6)); we.setBackground(bg(CARD, 10));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(56), -2); wl.leftMargin = dp(8); row.addView(we, wl);
            cb.setOnCheckedChangeListener((b, on) -> {
                String w = we.getText().toString().trim().replace(',', '.'); if (w.isEmpty()) w = "1.0";
                if (on) { if (loraWeight(n) == null) loraAdd(n, w, words); } else loraRemove(n, words);
                row.setBackground(bg(on ? SEL : FIELD, 12)); count.run();
            });
            we.addTextChangedListener(watch(s -> { String w = s.trim().replace(',', '.'); try { Float.parseFloat(w); if (cb.isChecked()) loraSetWeight(n, w); } catch (NumberFormatException ignored) { } }));
            row.setOnClickListener(x -> cb.toggle());
            row.setOnLongClickListener(x -> { loraMenu(title, f, words, () -> builder().setMessage(L.f("Удалить %s?", "Delete %s?", n)).setPositiveButton(L.s("Удалить", "Delete"), (d, i) -> { if (cb.isChecked()) loraRemove(n, words); f.delete(); Civitai.metaFile(f).delete(); dlg.dismiss(); loraLibrary(); }).setNegativeButton(L.s("Отмена", "Cancel"), null).show(), () -> { dlg.dismiss(); loraLibrary(); }); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); list.addView(row, lp);
            rows.add(row); keys.add((n + " " + title + " " + words).toLowerCase(java.util.Locale.ROOT)); boxes.add(cb); rowCats.add(Civitai.category(f));
        }
        count.run();
        // the folded blocks of the last LoRA set (megarun MEGA_LORA_CACHE): makes the next load as fast as without a LoRA, costs a model's size on storage
        final File lc = new File(D, "lcache"); long lb = 0; File[] ks = lc.listFiles();
        if (ks != null) for (File k : ks) { File[] fs = k.listFiles(); if (fs != null) for (File f : fs) lb += f.length(); }
        final File[] tes = new File(D, "tecache").listFiles();   // the text encoder with LoRA text-encoder weights merged in (animad)
        if (tes != null) for (File f : tes) lb += f.length();
        if (lb > 0) {
            TextView cl = link(L.f("Кэш LoRA: %.1f ГБ · очистить", "LoRA cache: %.1f GB · clear", lb / 1e9)); cl.setPadding(dp(2), dp(8), 0, 0);
            cl.setOnClickListener(x -> { if (ks != null) for (File k : ks) { File[] fs = k.listFiles(); if (fs != null) for (File f : fs) f.delete(); k.delete(); } if (tes != null) for (File f : tes) f.delete(); cl.setVisibility(View.GONE); });
            v.addView(cl, v.getChildCount() - 1);
        }
        final Runnable applyF = () -> { String q = filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT); for (int i = 0; i < rows.size(); i++) rows.get(i).setVisibility((q.isEmpty() || keys.get(i).contains(q)) && catMatch(rowCats.get(i), catSel[0]) ? View.VISIBLE : View.GONE); };
        fillCatChips(catRow, rowCats, catSel, applyF);
        filter.addTextChangedListener(watch(s -> applyF.run()));
        addDetectLink(v, dir, () -> { dlg.dismiss(); loraLibrary(); });
    }

    /** MOSS has one A/B adapter slot: choose a validated file from the Civitai library, or clear the active slot. */
    void mossLoraLibrary() {
        final File dir = MossLora.civitaiDir(getFilesDir()); dir.mkdirs();
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(10));
        v.addView(text(L.s("Библиотека MOSS", "MOSS-Image LoRA library"), 20, TXT, true));
        v.addView(text(L.s("Выберите одну совместимую LoRA A/B. Сила настраивается в блоке LoRA.", "Choose one compatible A/B LoRA. Set its strength in the LoRA section."), 12, DIM, false));
        EditText filter = field(L.s("Фильтр: имя или слово…", "Filter: name or word…")); filter.setSingleLine(true); filter.setTextSize(15);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2); fl.topMargin = dp(10); fl.bottomMargin = dp(8); v.addView(filter, fl);
        final HorizontalScrollView catScroll = new HorizontalScrollView(this); catScroll.setHorizontalScrollBarEnabled(false); final LinearLayout catRow = new LinearLayout(this); catScroll.addView(catRow);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(-1, -2); crl.bottomMargin = dp(8); v.addView(catScroll, crl);
        final List<String> rowCats = new ArrayList<>(); final String[] catSel = {""};
        ScrollView sv = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, dp(340)));
        LinearLayout bot = new LinearLayout(this); bot.setPadding(0, dp(10), 0, 0);
        Button civ = button("Civitai", FIELD, ACC); bot.addView(civ, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button imp = button(L.s("Импорт", "Import"), FIELD, ACC); LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, dp(44), 1); il.leftMargin = dp(8); bot.addView(imp, il);
        Button done = button(L.s("Готово", "Done"), ACC, ON_ACC); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(44), 1); dl.leftMargin = dp(8); bot.addView(done, dl); v.addView(bot);
        final AlertDialog dlg = dialog(v);
        civ.setOnClickListener(x -> { dlg.dismiss(); startActivityForResult(new Intent(this, CivitaiActivity.class), 14); });
        done.setOnClickListener(x -> dlg.dismiss());
        imp.setOnClickListener(x -> { dlg.dismiss(); pickLora(); });   // a local .safetensors from the phone's storage, checked like a Civitai download

        List<View> rows = new ArrayList<>(); List<String> keys = new ArrayList<>();
        File[] fs = dir.listFiles(); if (fs != null) Arrays.sort(fs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        if (fs == null || fs.length == 0) list.addView(text(L.s("Пусто: скачайте LoRA с Civitai или импортируйте файл.", "Empty: download LoRAs from Civitai or import a file."), 14, DIM, false));
        for (File f : fs == null ? new File[0] : fs) {
            if (!f.isFile() || !f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".safetensors")) continue;
            JSONObject md = Civitai.meta(f); String words = Civitai.words(f);
            final String name0 = md != null && !md.optString("name").isEmpty() ? md.optString("name") : f.getName().replaceFirst("(?i)\\.safetensors$", ""), mal = alias(true, "moss/" + f.getName());
            String name = mal.isEmpty() ? name0 : mal;
            String vid = md == null ? "" : Long.toString(md.optLong("version_id"));
            String currentId = prefs().getString("moss_lora_id", "");
            boolean active = prefs().getString("moss_lora_file", "").equals(f.getName()) || (!vid.equals("0") && currentId.equals("civitai-" + vid));
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(bg(active ? SEL : FIELD, 12));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            TextView title = text((active ? "✓  " : "") + name, 15, TXT, true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(title);
            String sub = words.isEmpty() ? L.s("Sin palabras activadoras declaradas", "No trigger words declared") : words;
            TextView detail = text(sub, 12, DIM, false); detail.setSingleLine(true); detail.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(detail);
            TextView size = text(String.format(java.util.Locale.US, "%.0f MB", f.length() / 1048576.0), 11, DIM, false); col.addView(size);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView mpen = text("✎", 16, DIM, false); mpen.setPadding(dp(6), dp(4), dp(8), dp(4));
            mpen.setOnClickListener(x -> renameDialog(true, "moss/" + f.getName(), name0, () -> { dlg.dismiss(); mossLoraLibrary(); }));
            row.addView(mpen);
            Button choose = button(active ? L.s("Quitar selección", "Clear selection") : L.s("Elegir", "Select"), CARD, active ? WARN : ACC); choose.setTextSize(12); row.addView(choose, new LinearLayout.LayoutParams(dp(112), dp(40)));
            View.OnClickListener select = z -> {
                if (active) {
                    MossLora.clearSelection(getFilesDir()); prefs().edit().remove("moss_lora_id").remove("moss_lora_name").remove("moss_lora_info").remove("moss_lora_file").apply();
                    refreshLora(); dlg.dismiss(); return;
                }
                MossLora.Check c = MossLora.check(BIN, f);
                if (!c.ok) { builder().setMessage(c.message()).setPositiveButton("OK", null).show(); return; }
                try {
                    MossLora.select(getFilesDir(), f);
                    String id = vid.isEmpty() || vid.equals("0") ? "file-" + f.getName() : "civitai-" + vid;
                    String info = "✓ " + c.message() + (words.isEmpty() ? "" : "\n" + L.s("Activadores: ", "Triggers: ") + words);
                    prefs().edit().putString("moss_lora_id", id).putString("moss_lora_name", name).putString("moss_lora_info", info).putString("moss_lora_file", f.getName()).apply();
                    appendPromptWords(words); refreshLora(); dlg.dismiss();
                } catch (Exception e) { builder().setMessage(L.f("No se pudo seleccionar el LoRA: %s", "Could not select the LoRA: %s", e.getMessage())).setPositiveButton("OK", null).show(); }
            };
            row.setOnClickListener(select); choose.setOnClickListener(select);
            row.setOnLongClickListener(z -> { loraMenu(name, f, words, null, () -> { dlg.dismiss(); mossLoraLibrary(); }); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); list.addView(row, lp); rows.add(row); keys.add((name + " " + words + " " + f.getName()).toLowerCase(java.util.Locale.ROOT)); rowCats.add(Civitai.category(f));
        }
        final Runnable applyF = () -> { String q = filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT); for (int i = 0; i < rows.size(); i++) rows.get(i).setVisibility((q.isEmpty() || keys.get(i).contains(q)) && catMatch(rowCats.get(i), catSel[0]) ? View.VISIBLE : View.GONE); };
        fillCatChips(catRow, rowCats, catSel, applyF);
        filter.addTextChangedListener(watch(s -> applyF.run()));
        addDetectLink(v, dir, () -> { dlg.dismiss(); mossLoraLibrary(); });
    }

    // ---- tag palette and the (tag:weight) buttons ----
    static final String[][] PALETTE = {
        {"Calidad|Quality", "masterpiece", "best quality", "very aesthetic", "absurdres", "highres", "newest", "detailed", "score_9"},
        {"Encuadre|Framing", "portrait", "upper body", "cowboy shot", "full body", "close-up", "looking at viewer", "from above", "from below", "from side", "dutch angle", "wide shot"},
        {"Luz|Lighting", "soft lighting", "cinematic lighting", "dramatic lighting", "rim light", "backlighting", "sunlight", "golden hour", "volumetric lighting", "neon lights", "night"},
        {"Estilo|Style", "anime coloring", "flat color", "watercolor (medium)", "oil painting (medium)", "sketch", "3d", "realistic", "pixel art", "chibi", "retro artstyle"},
        {"Expresión|Expression", "smile", "blush", "open mouth", "closed eyes", "serious", "smug", "surprised", "angry", "crying", "wink"},
        {"Fondo|Background", "simple background", "white background", "outdoors", "indoors", "city", "forest", "beach", "sky", "bedroom", "cherry blossoms"},
    };
    boolean hasTag(String t) { for (String x : tokens(prompt.getText().toString())) if (x.equalsIgnoreCase(t) || x.toLowerCase(java.util.Locale.ROOT).matches("\\(" + java.util.regex.Pattern.quote(t.toLowerCase(java.util.Locale.ROOT)) + ":[0-9.]+\\)")) return true; return false; }
    void toggleTag(String t) {
        List<String> cur = tokens(prompt.getText().toString()), keep = new ArrayList<>();
        boolean had = false;
        for (String x : cur) { if (x.equalsIgnoreCase(t) || x.toLowerCase(java.util.Locale.ROOT).matches("\\(" + java.util.regex.Pattern.quote(t.toLowerCase(java.util.Locale.ROOT)) + ":[0-9.]+\\)")) had = true; else keep.add(x); }
        if (!had) keep.add(t);
        prompt.setText(android.text.TextUtils.join(", ", keep)); prompt.setSelection(prompt.getText().length());
    }
    /** one-tap tags by category; a lit chip is already in the prompt, tapping it again takes it out */
    void tagPalette() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(10));
        v.addView(text(L.s("Теги", "Tags"), 20, TXT, true));
        ScrollView sv = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, dp(420)));
        for (String[] cat : PALETTE) {
            String[] nm = cat[0].split("\\|");
            TextView h = text(L.lang.equals("es") ? nm[0] : nm[1], 11, DIM, true); h.setLetterSpacing(.12f); h.setPadding(dp(2), dp(12), 0, dp(6)); list.addView(h);
            HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this); hs.addView(row); list.addView(hs);
            for (int i = 1; i < cat.length; i++) {
                final String t = cat[i];
                TextView c = text(t, 14, TXT, false); c.setPadding(dp(12), dp(7), dp(12), dp(8));
                Runnable paint = () -> { boolean on = hasTag(t); GradientDrawable g = bg(on ? SEL : FIELD, 16); if (on) g.setStroke(dp(1.5f), ACC); c.setBackground(g); c.setTextColor(on ? ACC : TXT); };
                paint.run();
                c.setOnClickListener(x -> { toggleTag(t); paint.run(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(6); row.addView(c, lp);
            }
        }
        Button done = button(L.s("Готово", "Done"), ACC, ON_ACC);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(44)); dl.topMargin = dp(10); v.addView(done, dl);
        AlertDialog d = dialog(v); done.setOnClickListener(x -> d.dismiss());
    }
    /** − / +: the selection, or the comma-separated tag at the cursor, becomes (tag:w) with w ± 0.1; back at 1.0 the brackets go away */
    void weigh(float delta) {
        String s = prompt.getText().toString();
        int a = Math.max(0, prompt.getSelectionStart()), b = Math.max(0, prompt.getSelectionEnd()); if (a > b) { int t = a; a = b; b = t; }
        if (a == b) { a = s.lastIndexOf(',', Math.max(0, a - 1)) + 1; int e = s.indexOf(',', b); b = e < 0 ? s.length() : e; }
        while (a < b && Character.isWhitespace(s.charAt(a))) a++;
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
        if (a >= b) return;
        String t = s.substring(a, b); float w = 1f;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\((.*):([0-9.]+)\\)$").matcher(t);
        if (m.find()) { t = m.group(1); try { w = Float.parseFloat(m.group(2)); } catch (NumberFormatException ignored) { } }
        w = Math.round((w + delta) * 10) / 10f; w = Math.max(0.1f, Math.min(2f, w));
        String r = Math.abs(w - 1f) < .01f ? t : "(" + t + ":" + String.format(java.util.Locale.US, "%.1f", w) + ")";
        prompt.getText().replace(a, b, r); prompt.setSelection(a, a + r.length());
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
        TextView lt = text(title, 11, DIM, true); lt.setLetterSpacing(.12f); lt.setPadding(dp(4), dp(10), 0, dp(4)); c.addView(lt);   // field caption: lighter than a section title
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
    // ---- extras: hires fix, ADetailer (the CPU upscaler was removed: it took the CPU to 90 C and the phone hung), init image (img2img), presets, queue ----
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
        extFold = fold(extrasBox, L.s("ДОПОЛНИТЕЛЬНО", "EXTRAS"), "extras");
        LinearLayout c = card(extFold.body); c.setPadding(dp(14), dp(6), dp(14), dp(12));
        sw(c, L.s("Vista previa por pasos de Anima", "Anima live preview"), L.s("Actualiza la imagen mientras se genera con modelos Anima", "Updates the image while Anima models are generating"), "x_preview", true);
        sw(c, "Hires fix", L.s("Второй проход в большем размере (дольше)", "A second pass at a larger size (slower)"), "x_hires", false);
        LinearLayout hr = new LinearLayout(this);
        hsEdit = smallField(hr, L.s("МАСШТАБ", "SCALE"), "x_hires_scale", "1.5", true);
        hdEdit = smallField(hr, "DENOISE", "x_hires_den", "0.45", true);
        hnEdit = smallField(hr, L.s("ШАГИ", "STEPS"), "x_hires_steps", "4", false);
        c.addView(hr);
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
        Button ix = button("✕", FIELD, TXT); ix.setOnClickListener(v -> { initSrc().delete(); maskSrc().delete(); refreshExtras(); });
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(dp(40), dp(36)); xl.leftMargin = dp(6); ir.addView(ix, xl);
        c.addView(ir);
        LinearLayout ir2 = new LinearLayout(this); ir2.setPadding(0, dp(6), 0, 0);
        Button useRes = button(L.s("↳ Последняя картинка", "↳ Last picture"), FIELD, ACC); useRes.setTextSize(12); useRes.setOnClickListener(v -> useResultAsInit());
        ir2.addView(useRes, new LinearLayout.LayoutParams(0, dp(36), 1));
        Button maskB = button(L.s("✎ Маска", "✎ Mask"), FIELD, ACC); maskB.setTextSize(12); maskB.setOnClickListener(v -> paintMask());
        LinearLayout.LayoutParams ml2 = new LinearLayout.LayoutParams(0, dp(36), 1); ml2.leftMargin = dp(6); ir2.addView(maskB, ml2);
        c.addView(ir2);
        LinearLayout sr = new LinearLayout(this); strEdit = smallField(sr, L.s("СИЛА ИЗМЕНЕНИЯ (0..1)", "DENOISE STRENGTH (0..1)"), "x_strength", "0.6", true); c.addView(sr);
        LinearLayout pr = new LinearLayout(this); pr.setPadding(0, dp(10), 0, 0);
        Button presets = button(L.s("Пресеты", "Presets"), FIELD, ACC); presets.setTextSize(13); presets.setOnClickListener(v -> presets());
        pr.addView(presets, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button qa = button(L.s("+ В очередь", "+ Queue"), FIELD, ACC); qa.setTextSize(13); qa.setOnClickListener(v -> enqueue());
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, dp(40), 1); ql.leftMargin = dp(8); pr.addView(qa, ql);
        Button cmp = button("⊞", FIELD, ACC); cmp.setOnClickListener(v -> compare());
        LinearLayout.LayoutParams cml = new LinearLayout.LayoutParams(dp(48), dp(40)); cml.leftMargin = dp(8); pr.addView(cmp, cml);
        Button qr = button("▶", FIELD, ACC); qr.setOnClickListener(v -> runQueue());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(48), dp(40)); rl.leftMargin = dp(8); pr.addView(qr, rl);
        c.addView(pr);
        queueText = text("", 12, DIM, false); queueText.setPadding(dp(2), dp(6), 0, 0); queueText.setOnClickListener(v -> showQueue()); c.addView(queueText);
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
        extrasBox.setVisibility(isMoss() || isQwen() || isMage() || model.isEmpty() ? View.GONE : View.VISIBLE);
        List<String> on = new ArrayList<>();
        if (prefs().getBoolean("x_hires", false)) on.add("Hires"); if (prefs().getBoolean("x_ad", false)) on.add("ADetailer");
        if (initSrc().exists()) on.add(maskSrc().exists() ? "inpaint" : "img2img"); if (!queue.isEmpty()) on.add(L.f("очередь %d", "queue %d", queue.size()));
        extFold.sum.setText(on.isEmpty() ? L.s("выкл.", "off") : android.text.TextUtils.join(" · ", on)); extFold.sum.setTextColor(on.isEmpty() ? DIM : ACC);
        initText.setText(!initSrc().exists() ? L.s("нет: обычная генерация", "none: normal generation") : maskSrc().exists() ? L.s("✓ выбрана · маска: перерисовать закрашенное", "✓ chosen · mask: redraw the painted part") : L.s("✓ выбрана (подгоняется под размер)", "✓ chosen (fitted to the size)"));
        File ad = adModel();
        adText.setText(ad != null ? "✓ " + ad.getName() : L.s("face_yolov8n: загрузится сам (6 МБ)", "face_yolov8n: downloads on first use (6 MB)"));
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
    // ---- inpainting: a mask painted over the init image (white = redraw); kept in the init image's own pixels and fitted like it ----
    File maskSrc() { return new File(getFilesDir(), "mask_src.png"); }
    /** the mask scaled + cropped exactly like initFor(w, h) */
    File maskFor(int w, int h) {
        if (!maskSrc().exists() || !initSrc().exists()) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(maskSrc().getPath()); if (b == null) return null;
            float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            Bitmap sc = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            Bitmap cr = Bitmap.createBitmap(sc, (sc.getWidth() - w) / 2, (sc.getHeight() - h) / 2, w, h);
            File out = new File(getFilesDir(), "mask_" + w + "x" + h + ".png");
            try (FileOutputStream o = new FileOutputStream(out)) { cr.compress(Bitmap.CompressFormat.PNG, 100, o); }
            return out;
        } catch (Exception e) { return null; }
    }
    /** INIT / STRENGTH / MASK for anima.sh */
    String initEnv(int w, int h) {
        File init = initFor(w, h); if (init == null) return "";
        String e = "INIT=" + q(init.getPath()) + " STRENGTH=" + pf("x_strength", .6f) + " ";
        File m = maskFor(w, h); if (m != null) e += "MASK=" + q(m.getPath()) + " ";
        return e;
    }
    /** the last picture becomes the init image (to inpaint or vary it) */
    void useResultAsInit() {
        if (lastFile == null || !lastFile.exists()) { Toast.makeText(this, L.s("Сначала создайте картинку", "Make a picture first"), Toast.LENGTH_SHORT).show(); return; }
        try (InputStream in = new FileInputStream(lastFile); OutputStream o = new FileOutputStream(initSrc())) { byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
        catch (IOException e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); return; }
        maskSrc().delete(); refreshExtras();
    }

    /** finger painting on the init image: brush / eraser, size, clear; the mask is saved at the init image's size (at most 1024 px on the long side) */
    class MaskView extends View {
        Bitmap base, mask; android.graphics.Canvas mc; final android.graphics.Paint br = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), ov = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        final android.graphics.RectF dst = new android.graphics.RectF(); float lx, ly, size = .02f, zoom = 1f, panX, panY, prevSpan, prevMidX, prevMidY, downX, downY, lastX, lastY; boolean multiTouch, drawing, pending;
        static final int BRUSH = 0, ERASER = 1, MOVE = 2; int tool = BRUSH;
        final float slop = android.view.ViewConfiguration.get(Main.this).getScaledTouchSlop();
        final ArrayDeque<Bitmap> undo = new ArrayDeque<>(), redo = new ArrayDeque<>(); final int historyLimit = 8; Runnable historyChanged, zoomChanged;
        MaskView(Bitmap base, Bitmap old) {
            super(Main.this); this.base = base;
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
    void paintMask() {
        if (!initSrc().exists()) { Toast.makeText(this, L.s("Сначала выберите исходную картинку", "Choose an init image first"), Toast.LENGTH_SHORT).show(); return; }
        Bitmap b0 = BitmapFactory.decodeFile(initSrc().getPath()); if (b0 == null) return;
        float s = Math.min(1f, 1024f / Math.max(b0.getWidth(), b0.getHeight()));
        Bitmap base = s < 1 ? Bitmap.createScaledBitmap(b0, Math.round(b0.getWidth() * s), Math.round(b0.getHeight() * s), true) : b0;
        showMaskEditor(base, maskSrc(), L.s("Маска (inpainting)", "Mask (inpainting)"), L.s("Закрасьте то, что нужно перерисовать", "Paint over what should be redrawn"), this::refreshExtras);
    }
    void paintQwenMask() {
        if (editBm == null) { Toast.makeText(this, L.s("Сначала выберите исходную картинку", "Choose a source photo first"), Toast.LENGTH_SHORT).show(); return; }
        float s = Math.min(1f, 1024f / Math.max(editBm.getWidth(), editBm.getHeight()));
        Bitmap base = s < 1 ? Bitmap.createScaledBitmap(editBm, Math.round(editBm.getWidth() * s), Math.round(editBm.getHeight() * s), true) : editBm;
        showMaskEditor(base, Qwen.editMask(getFilesDir()), L.s("Маска Qwen", "Qwen edit mask"),
            L.s("Закрасьте только ту область, которую хотите изменить. Остальная фотография останется исходной.", "Paint only the area you want to change. The rest of the photo will stay original."), this::refreshEdit);
    }
    void showMaskEditor(Bitmap base, File file, String title, String hintText, Runnable afterSave) {
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
        d.setOnDismissListener(x -> { mv.dispose(); if (base != editBm && !base.isRecycled()) base.recycle(); });
        cancel.setOnClickListener(x -> d.dismiss());
        save.setOnClickListener(x -> {
            try { if (mv.empty()) file.delete(); else mv.save(file); } catch (IOException e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); }
            d.dismiss(); afterSave.run();
        });
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
    /** anima.sh environment for the extras that are on */
    boolean memOk, hotStop;   // the memory warning was answered / a batch stopped because the phone got too hot
    long availMb() { ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi); return mi.availMem / 1048576; }
    /** battery temperature in °C (Xiaomi phones report thermal status 0 even with the CPU at 80+ °C; the battery sensor is what an app can read) */
    float batteryC() { Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); return b == null ? 0 : b.getIntExtra("temperature", 0) / 10f; }
    /** plugged in: charging warms the battery by itself (~42 °C at rest over USB), so the battery limits are 3 °C higher */
    boolean charging() { Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); return b != null && b.getIntExtra("plugged", 0) != 0; }
    float hotC() { return charging() ? 45f : 42f; }
    boolean tooHot() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) return true;
        if (android.os.Build.VERSION.SDK_INT >= 30) { float hr = pm.getThermalHeadroom(0); if (!Float.isNaN(hr) && hr >= 0.95f) return true; }
        return batteryC() >= hotC();
    }
    /** Between pictures, keep the batch paused until the phone has cooled or the user presses Stop. */
    boolean waitCool(long t0) {
        long since = System.currentTimeMillis();
        while (!cancelled) {
            float c = batteryC(); if (c < hotC() - 3f && !tooHot()) return true;
            long mins = (System.currentTimeMillis() - since) / 60000;
            final String m = L.f("Охлаждение телефона… %.1f °C", "Letting the phone cool down… %.1f °C", c);
            ui.post(() -> status.setText(m + "  ·  " + L.f("пауза %d мин", "paused %d min", mins)));
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
    /** automatic cool mode: the NPU runs at full clock while the phone is cool and switches to the lower clock from 40 °C battery (43 °C while charging)
     *  or a thermal headroom of 0.75, back to full speed 2 °C lower. The NPU processes read the file anima/cool before every step. */
    long coolCheck; boolean coolOn;
    void coolTick(boolean force) {
        long t = System.currentTimeMillis(); if (!force && t - coolCheck < 3000) return; coolCheck = t;
        String mode = Settings.coolMode(this); boolean on;
        if (mode.equals("on")) on = true;
        else if (mode.equals("off")) on = false;
        else {
            float c = batteryC(), lim = charging() ? 43f : 40f, hr = Float.NaN;
            if (android.os.Build.VERSION.SDK_INT >= 30) hr = ((PowerManager) getSystemService(POWER_SERVICE)).getThermalHeadroom(0);
            boolean hot = c >= lim || (!Float.isNaN(hr) && hr >= 0.75f), cold = c < lim - 2f && (Float.isNaN(hr) || hr < 0.65f);
            on = coolOn ? !cold : hot;
        }
        coolOn = on;
        File cf = new File(D, "cool");
        try { if (on) { if (!cf.exists()) new java.io.FileOutputStream(cf).close(); } else if (cf.exists()) cf.delete(); } catch (Exception ignored) { }
    }
    boolean tooHotStatus() { return ((PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE; }
    String extrasEnv(int w, int h, long t0) {
        StringBuilder e = new StringBuilder();
        if (prefs().getBoolean("x_preview", true)) e.append("PREVIEW=").append(q(previewFile().getPath())).append(" ");
        if (availMb() < 3000 && prefs().getBoolean("x_hires", false)) {   // the larger passes need room: skip them rather than swap
            ui.post(() -> Toast.makeText(this, L.s("Мало памяти: hires fix пропущен для этой картинки", "Low memory: hires fix skipped for this picture"), Toast.LENGTH_LONG).show());
            e.append(initEnv(w, h));
            return e.toString();
        }
        if (prefs().getBoolean("x_hires", false)) {
            int[] hs = hiresSize(w, h);
            if (hs != null) e.append("HIRES=1 HIRES_W=").append(hs[0]).append(" HIRES_H=").append(hs[1]).append(" HIRES_DENOISE=").append(pf("x_hires_den", .45f)).append(" HIRES_STEPS=").append((int) pf("x_hires_steps", 4)).append(" ");
            else ui.post(() -> Toast.makeText(this, L.s("Hires fix: этот размер уже максимальный для NPU", "Hires fix: this size is already the NPU's largest"), Toast.LENGTH_LONG).show());
        }
        if (prefs().getBoolean("x_ad", false)) { File ad = ensureAd(t0); if (ad != null) e.append("AD=").append(q(ad.getPath())).append(" "); }
        e.append(initEnv(w, h));
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
                maskSrc().delete();   // a mask belongs to its picture
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
                        for (String x : new String[]{"x_hires", "x_ad"}) p.put(x, prefs().getBoolean(x, false));
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
                    for (String x : new String[]{"x_hires", "x_ad"}) if (p.has(x)) e.putBoolean(x, p.optBoolean(x));
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
        if (negTokText != null && negEdit != null) {   // the negative prompt is encoded on its own (CFG > 1), so it has its own limit
            int nn = (int) Math.ceil(negEdit.getText().length() / 3.6);
            negTokText.setText(L.f("Negativo: ≈%d / 512 tokens", "Negative: ≈%d / 512 tokens", nn)); negTokText.setTextColor(nn > 512 ? BAD : nn > 435 ? WARN : DIM);
            negTokText.setVisibility(negEdit.getText().length() > 0 ? View.VISIBLE : View.GONE);
        }
    }
    TextView negTokText;
    /** a faint ⓘ after a caption; tapping the caption explains the setting in plain words (the same text for every model) */
    void infoOn(TextView caption, String title, String body) {
        caption.setText(android.text.TextUtils.concat(caption.getText(), "  ⓘ")); caption.setClickable(true);
        caption.setOnClickListener(v -> builder().setTitle(title).setMessage(body).setPositiveButton("OK", null).show());
    }
    boolean sampFill;   // the fields were filled from the prefs (again after a reset)
    void refreshSampling() {
        if (sampBox == null || samplerBtn == null) return;
        if (!sampFill) { sampFill = true; stepsEdit.setText(String.valueOf(Settings.steps(this))); cfgEdit.setText(Settings.cfg(this)); negEdit.setText(Settings.negative(this)); }   // setText re-enters here with sampFill set
        samplerBtn.setText(Settings.samplerName(Settings.sampler(this)) + "  ▾");
        schedBtn.setText(Settings.schedulerName(Settings.scheduler(this)) + "  ▾");
        int cut = Settings.cfgCut(this);
        cutBtn.setText((cut >= 100 ? L.s("CFG: на всех шагах", "CFG: on every step") : L.f("CFG: первые %d %% шагов", "CFG: first %d %% of the steps", cut)) + "  ▾");
        cutBtn.setVisibility(Settings.cfgOn(this) ? View.VISIBLE : View.GONE);
        cfgEdit.setAlpha(Settings.turbo(this) ? .45f : 1f);   // still editable, but a turbo model is made for CFG 1
        sampBox.setVisibility(!isMoss() && !isQwen() && !isMage() && !model.isEmpty() ? View.VISIBLE : View.GONE);
        // MOSS runs its distilled 4 steps; only RealSkin with the DMD add-on installed gets a choice (4 rapid-moss / 8 DMD)
        if (mossStepsBox != null) mossStepsBox.setVisibility(isMoss() && Moss.isRs(model) && canUseRsDmd() ? View.VISIBLE : View.GONE);
        if (mossStepsBtn != null) {
            if (Moss.isRs(model)) { boolean dmd = prefs().getString("rs_mode", "4").equals("8") && canUseRsDmd(); mossStepsBtn.setText(L.f("Modo RealSkin: %s  ▾", "RealSkin mode: %s  ▾", dmd ? "8 step · DMD" : "4 step · rapid-moss")); }
        }
        // one short line; ↺ restores the per-model defaults
        String n = (Settings.turbo(this) ? "Turbo  ·  " : "") + L.f("вызовов DiT: %d", "DiT calls: %d", Settings.calls(this));
        if (Settings.cfgOn(this)) n += "  ·  " + (cut >= 100 ? L.s("CFG > 1: ×2 время", "CFG > 1: ×2 time") : L.f("CFG > 1: ×2 время на %d шагах", "CFG > 1: ×2 time on %d steps", Settings.cfgSteps(Settings.steps(this), cut)));
        if (Settings.twoCalls(Settings.sampler(this))) n += "  ·  " + L.s("2-й порядок: ×2 время", "2nd order: ×2 time");
        sampNote.setText(n);
        sampFold.sum.setText(L.f("%d шагов · CFG %s · %s", "%d steps · CFG %s · %s", Settings.steps(this), Settings.cfg(this), Settings.samplerName(Settings.sampler(this))));
        countTokens();
        if (genBtn != null && !busy) genBtn.setText(genLabel());
    }
    void pickLora() {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 9);
    }
    void dropLora() {
        if (busy) return;
        final boolean moss = isMoss(); final File ld = moss ? loraDir() : loraSrc();   // Anima: only the selected file leaves the library
        prefs().edit().remove(lp() + "lora_id").remove(lp() + "lora_name").remove(lp() + "lora_info").apply();
        ex2.submit(() -> { if (moss) MossLora.clearSelection(getFilesDir()); else MossLora.deleteTree(ld); ui.post(this::refreshLora); });
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
            if (moss) {   // the file joins the MOSS library (same folder as the Civitai downloads) and becomes the active adapter
                File lib = MossLora.civitaiDir(getFilesDir()); lib.mkdirs();
                String fn = name.replaceAll("[^A-Za-z0-9._ ()\\[\\]-]", "_"); if (!fn.toLowerCase(java.util.Locale.ROOT).endsWith(".safetensors")) fn += ".safetensors";
                File keep = new File(lib, fn); keep.delete();
                try { if (!tmp.renameTo(keep)) throw new IOException("cannot store " + fn); MossLora.select(getFilesDir(), keep); }
                catch (Exception e) { tmp.delete(); ui.post(() -> { refreshLora(); loraFail(name, e.getMessage()); }); return; }
                prefs().edit().putString("moss_lora_id", "file-" + keep.getName()).putString("moss_lora_name", name.replaceFirst("(?i)\\.safetensors$", ""))
                    .putString("moss_lora_info", "✓ " + msg).putString("moss_lora_file", keep.getName()).apply();
                ui.post(this::refreshLora); return;
            }
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
            else if (i.getItemId() == 5) startActivityForResult(new Intent(this, CivitaiActivity.class), 14);
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
                List<String> l = savedPrompts(); l.remove(t); l.add(0, t); while (l.size() > 200) l.remove(l.size() - 1);
                prefs().edit().putString("saved", new JSONArray(l).toString()).apply(); fill[0].run();
            });
            box.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));
            List<String> sv = savedPrompts();
            if (!sv.isEmpty()) { box.addView(sect(L.s("Сохранённые", "Saved"))); for (String t : sv) box.addView(promptRow(t, true, dlg, fill)); }
            List<String> rc = recentPrompts();
            if (!rc.isEmpty()) {
                LinearLayout rh = new LinearLayout(this); rh.setGravity(Gravity.CENTER_VERTICAL);
                rh.addView(sect(L.s("Недавние", "Recent")), new LinearLayout.LayoutParams(0, -2, 1));
                TextView clr = link(L.s("Borrar recientes", "Clear recent")); rh.addView(clr);
                clr.setOnClickListener(v -> { prefs().edit().putLong("recent_cleared", System.currentTimeMillis()).apply(); fill[0].run(); });
                box.addView(rh);
                for (String t : rc) box.addView(promptRow(t, false, dlg, fill));
            }
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
        if (!removable) {   // a star saves a recent prompt (filled = already saved; tapping again removes it from Saved)
            final boolean isSaved = savedPrompts().contains(t);
            TextView st = text(isSaved ? "★" : "☆", 18, isSaved ? ACC : DIM, false); st.setPadding(dp(12), dp(4), dp(8), dp(4));
            st.setOnClickListener(v -> { List<String> l = savedPrompts(); if (l.contains(t)) l.remove(t); else l.add(0, t); while (l.size() > 200) l.remove(l.size() - 1); prefs().edit().putString("saved", new JSONArray(l).toString()).apply(); fill[0].run(); });
            r.addView(st);
        }
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
        long since = prefs().getLong("recent_cleared", 0);   // "Clear recent" hides older prompts; the pictures stay in the History
        for (int i = 0; i < fs.length && l.size() < 200; i++) {
            long t = 0; try { t = Long.parseLong(fs[i].getName().replaceAll("\\.png$", "")); } catch (NumberFormatException ignored) { }
            if (t <= since) break;
            String p = Gallery.meta(fs[i]).optString("prompt").trim(); if (!p.isEmpty() && !l.contains(p)) l.add(p);
        }
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
    java.util.List<int[]> sizeList() { return isMage() ? Mage.presets(editBm != null) : java.util.Arrays.asList(isMoss() ? Moss.PRESETS : isQwen() ? Qwen.PRESETS : SIZES); }
    String mossSizeError(int w, int h) { return Moss.sizeError(w, h); }

    // ---- model state --------------------------------------------------------------------------
    String modelName() { return model.isEmpty() ? L.s("нет модели", "no model") : store.name(model); }   // the original name: kept in the picture's metadata

    // ---- own names for models and LoRAs (files/model_names.json, files/lora_names.json): display only, files and IDs stay as they are ----
    File namesFile(boolean lora) { return new File(getFilesDir(), lora ? "lora_names.json" : "model_names.json"); }
    JSONObject names(boolean lora) { try { return new JSONObject(Models.read(namesFile(lora))); } catch (Exception e) { return new JSONObject(); } }
    String alias(boolean lora, String key) { return names(lora).optString(key, ""); }
    void setAlias(boolean lora, String key, String name) {
        JSONObject j = names(lora);
        try { if (name == null || name.trim().isEmpty()) j.remove(key); else j.put(key, name.trim()); try (java.io.FileWriter w = new java.io.FileWriter(namesFile(lora))) { w.write(j.toString()); } } catch (Exception ignored) { }
    }
    String shownModel(String id) { String a = alias(false, id); return a.isEmpty() ? store.name(id) : a; }
    /** rename dialog: an empty name or Reset brings the original back */
    void renameDialog(boolean lora, String key, String original, Runnable after) {
        final EditText e = field(original); e.setSingleLine(true); String cur = alias(lora, key); e.setText(cur.isEmpty() ? original : cur); e.setSelection(e.getText().length());
        LinearLayout box = new LinearLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        builder().setTitle(L.s("Cambiar nombre", "Rename")).setView(box)
            .setPositiveButton("OK", (d, w) -> { String n = e.getText().toString().trim(); setAlias(lora, key, n.equals(original) ? "" : n); after.run(); })
            .setNeutralButton(L.s("Restablecer", "Reset"), (d, w) -> { setAlias(lora, key, ""); after.run(); })
            .setNegativeButton(L.s("Отмена", "Cancel"), null).show();
    }

    /** model picker: one card per installed model (size, turbo, speed badges; the selected one highlighted), then catalog / Civitai / import */
    void pickModel() {
        if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        if (ids.isEmpty()) { openModels(); return; }
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(16), dp(18), dp(10));
        v.addView(text(L.s("Модель", "Model"), 20, TXT, true));
        ScrollView sv = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0, dp(10), 0, 0); sv.addView(list);
        v.addView(sv, new LinearLayout.LayoutParams(-1, -2, 1));
        final AlertDialog[] dlg = new AlertDialog[1];
        for (String id : ids) {
            boolean sel = id.equals(model), moss = Moss.is(id), turbo = !moss && store.turbo(id);
            LinearLayout card = new LinearLayout(this); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable g = bg(sel ? SEL : FIELD, 14); if (sel) g.setStroke(dp(2), ACC); card.setBackground(g);
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            TextView tn = text(shownModel(id), 16, TXT, true); tn.setMaxLines(2); tn.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(tn);
            LinearLayout bs = new LinearLayout(this); bs.setPadding(0, dp(6), 0, 0);
            List<TextView> b = new ArrayList<>();
            b.add(badge(moss ? "MOSS" : Qwen.is(id) ? "Qwen · 7B" : Mage.is(id) ? "Mage" : store.is29(id) ? "2.9B" : "2B", ACC));
            if (!moss) b.add(badge(turbo ? "Turbo · 4" : "CFG · 20", turbo ? ACC3 : ACC2));
            if (turbo) b.add(badge(L.f("~%d с", "~%d s", Math.max(3, store.speed(id))), OK));
            for (TextView t : b) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(6); bs.addView(t, lp); }
            col.addView(bs);
            card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView pen = text("✎", 18, DIM, false); pen.setPadding(dp(10), dp(4), dp(10), dp(4));
            pen.setOnClickListener(x -> renameDialog(false, id, store.name(id), () -> { dlg[0].dismiss(); setModelUi(loaded, false, ""); pickModel(); }));
            card.addView(pen);
            if (sel) card.addView(text("✓", 20, ACC, true));
            card.setOnClickListener(x -> { dlg[0].dismiss(); if (!id.equals(model)) setModel(id); });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.bottomMargin = dp(8); list.addView(card, cl);
        }
        LinearLayout acts = new LinearLayout(this); acts.setPadding(0, dp(8), 0, 0);
        String[][] a = {{L.s("Каталог", "Catalog"), "c"}, {"Civitai", "v"}, {L.s("Импорт", "Import"), "i"}};
        for (String[] x : a) {
            Button bt = button("＋ " + x[0], FIELD, ACC); bt.setTextSize(13);
            bt.setOnClickListener(y -> {
                dlg[0].dismiss();
                if (x[1].equals("c")) openModels();
                else if (x[1].equals("v")) startActivityForResult(new Intent(this, CivitaiActivity.class).putExtra("type", "Checkpoint"), 14);
                else startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 13);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1); if (acts.getChildCount() > 0) lp.leftMargin = dp(6); acts.addView(bt, lp);
        }
        v.addView(acts);
        dlg[0] = dialog(v);
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
        if (chipName != null) chipName.setText(model.isEmpty() ? modelName() : shownModel(model));
        if (genBtn != null && !busy) genBtn.setText(genLabel());
    }

    /** rough time to a picture: the catalog speed at 512x768 scaled by the patch count, times the number of pictures */
    String genLabel() {
        if (model.isEmpty()) return L.s("Скачать модель", "Get a model");
        double sm = isMoss() || isQwen() || isMage() || Settings.defaultSampling(this) ? 1 : Settings.calls(this) / 2.6;   // the default 4 steps cost ~2.6 full DiT calls (two run at half size)
        if (Moss.isRs(model) && prefs().getString("rs_mode", "4").equals("8") && Moss.installed8(getFilesDir()) && !loraOn()) sm = 1.65; // measured 8-step DMD is about 1.65× the 4-step RealSkin run, including final VAE decode
        long est = Math.max(3, Math.round(store.speed(model) * sm * ((resW / 16) * (resH / 16)) / 1536.0 * (isQwen() && editBm != null ? 1.3 : 1))) * count;
        return count > 1 ? L.f("Генерировать ×%d  ·  ~%d с", "Generate ×%d  ·  ~%d s", count, est) : L.f("Генерировать  ·  ~%d с", "Generate  ·  ~%d s", est);
    }

    /** Set the requested batch size (1..999). During generation the active image is retained, and the remaining queue can be changed. */
    void setCount(int v) {
        synchronized (batchLock) {
            if (busy) {
                if (!batchAdjustable) return;   // comparison grids have a fixed four-image layout
                count = Math.max(curIdx + 1, Math.min(999, v));
                curN = count;
            } else count = Math.max(1, Math.min(999, v));
            prefs().edit().putInt("count", count).apply();
        }
        countBtn.setText("×" + count);
        if (busy) Toast.makeText(this, L.f("В очереди: %d", "In the queue: %d", count), Toast.LENGTH_SHORT).show();
        else genBtn.setText(genLabel());
    }

    void pickCount() {
        final EditText e = new EditText(this);
        e.setText(String.valueOf(count)); e.setSelectAllOnFocus(true); e.setInputType(InputType.TYPE_CLASS_NUMBER);
        builder().setTitle(L.s("Картинок за раз", "Pictures per run"))
            .setMessage(L.s("Число от 1 до 999", "Enter a number from 1 to 999"))
            .setView(e).setNegativeButton(L.s("Отмена", "Cancel"), null)
            .setPositiveButton("OK", (d, w) -> {
                try { setCount(Integer.parseInt(e.getText().toString().trim())); }
                catch (NumberFormatException x) { Toast.makeText(this, L.s("Введите число", "Enter a number"), Toast.LENGTH_SHORT).show(); }
            }).show();
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
            if (loraBox != null) { loraBox.setVisibility(model.isEmpty() || isQwen() || isMage() ? View.GONE : View.VISIBLE); refreshLora(); }
            if (qwenLoraBox != null) { qwenLoraBox.setVisibility(isQwen() ? View.VISIBLE : View.GONE); refreshQwenLora(); }
            if (editBox != null) editBox.setVisibility(isQwen() || isMage() ? View.VISIBLE : View.GONE);
            if (editOpts != null) editOpts.setVisibility(editBm != null && isQwen() ? View.VISIBLE : View.GONE);
            refreshExtras();
            refreshSampling();
            state.setText(working ? msg : isQwen() ? L.s("Qwen-Image на NPU · веса с накопителя", "Qwen-Image on the NPU · weights streamed from storage") : isMage() ? L.s("Mage-Flow en NPU · pesos desde almacenamiento", "Mage-Flow on the NPU · weights streamed from storage") : isMoss() ? L.s("MOSS-Image на NPU", "MOSS-Image on the NPU") : isLoaded ? L.s("В NPU · готово", "On the NPU · ready")
                : L.s("Выгружена · загрузится при генерации", "Unloaded · loads when you generate"));
            loadBtn.setText(isLoaded ? L.s("Выгрузить", "Unload") : L.s("Загрузить", "Load"));
            loadBtn.setEnabled(!working && !busy && !model.isEmpty() && !isMoss() && !isQwen() && !isMage());
            loadBtn.setAlpha(loadBtn.isEnabled() ? 1f : .5f);
            boolean canEditBatch = !busy || (batchAdjustable && !cancelled);
            countBtn.setEnabled(canEditBatch); countBtn.setAlpha(canEditBatch ? 1f : .5f);
            boolean can = busy ? !cancelled : !working;   // while a picture is made the main button is "Stop"
            genBtn.setEnabled(can);
            genBtn.setBackground(busy ? bg(BAD, 18) : gradient(can));
            genBtn.setTextColor(busy ? 0xFFFFFFFF : can ? ON_ACC : DIM);
            genBtn.setText(busy ? (cancelled ? L.s("Останавливаю…", "Stopping…") : L.s("Остановить", "Stop")) : genLabel());
        });
    }

    void refreshState() {
        if (isMoss() || isQwen() || isMage()) { setModelUi(false, false, ""); return; }
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

    void refreshQwenLora() {
        if (qwenLoraName == null) return;
        String name = prefs().getString("qwen_lora_dir", "");
        File selected = Qwen.selectedOverlay(getFilesDir(), name);
        if (selected == null && !name.isEmpty()) { prefs().edit().remove("qwen_lora_dir").apply(); name = ""; }
        qwenLoraName.setText(name.isEmpty() ? L.s("Ninguna", "None") : Qwen.overlayName(selected));
        qwenLoraDrop.setEnabled(!name.isEmpty()); qwenLoraDrop.setAlpha(name.isEmpty() ? .45f : 1f);
        boolean src = selected != null && Qwen.hasSource(getFilesDir(), name);
        if (qwenStrRow != null) { qwenStrRow.setVisibility(src ? View.VISIBLE : View.GONE); if (src && !qwenFolding) { float s = Qwen.overlayStrength(selected); qwenStr.setProgress(Math.round(s / 0.05f)); qwenStrVal.setText(String.format(java.util.Locale.US, "%.2f", s)); } }
    }
    /** convert loras_src/<id>.safetensors into the overlay loras/<id>/ on the phone, with a progress dialog; select it when done */
    void foldQwen(String id, float strength, String name, String triggers, boolean select) {
        if (busy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; }
        qwenFolding = true;
        final ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(32);
        LinearLayout pv = new LinearLayout(this); pv.setPadding(dp(20), dp(12), dp(20), 0); pv.addView(pb, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog dlg = builder().setTitle(L.f("Convirtiendo LoRA de Qwen · %.2f", "Converting the Qwen LoRA · %.2f", strength)).setView(pv).setCancelable(false).show();
        ex2.submit(() -> {
            String err;
            try { err = Qwen.fold(BIN, getFilesDir(), id, strength, name, triggers, (d, t) -> ui.post(() -> pb.setProgress(d))); } catch (Exception e) { err = e.getMessage(); }
            final String fe = err;
            ui.post(() -> {
                dlg.dismiss(); qwenFolding = false;
                if (fe != null) { builder().setTitle(L.s("LoRA de Qwen", "Qwen LoRA")).setMessage(fe).setPositiveButton("OK", null).show(); refreshQwenLora(); return; }
                if (select) prefs().edit().putString("qwen_lora_dir", id).apply();
                refreshQwenLora(); appendPromptWords(triggers);
                Toast.makeText(this, L.f("LoRA de Qwen lista (intensidad %.2f)", "Qwen LoRA ready (strength %.2f)", strength), Toast.LENGTH_SHORT).show();
            });
        });
    }
    void refoldQwen(float strength) {
        String id = prefs().getString("qwen_lora_dir", ""); File ov = Qwen.selectedOverlay(getFilesDir(), id);
        if (ov == null || !Qwen.hasSource(getFilesDir(), id)) return;
        if (Math.abs(Qwen.overlayStrength(ov) - strength) < 0.001f) return;
        foldQwen(id, strength, Qwen.overlayName(ov), Qwen.overlayTriggers(ov), false);
    }
    /** a .safetensors picked from the storage: copied to loras_src, checked, folded at strength 1 and selected */
    void importQwenSafetensors(Uri uri) {
        String nm = "qwen_lora.safetensors";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) { if (c != null && c.moveToFirst()) { int k = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (k >= 0) nm = c.getString(k); } } catch (Exception ignored) { }
        final String display = nm.replaceFirst("(?i)\\.safetensors$", ""), id = Qwen.safeId(nm);
        final AlertDialog copying = builder().setMessage(L.s("Copiando y revisando la LoRA…", "Copying and checking the LoRA…")).setCancelable(false).show();
        ex2.submit(() -> {
            File dst = Qwen.srcFile(getFilesDir(), id), tmp = new File(dst.getPath() + ".part"); dst.getParentFile().mkdirs();
            String err = null;
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16]; long tot = 0; for (int n; (n = in.read(b)) > 0; ) { o.write(b, 0, n); tot += n; if (tot > (2L << 30)) throw new IOException(L.s("el archivo supera 2 GB", "the file is larger than 2 GB")); }
            } catch (Exception e) { err = e.getMessage(); }
            if (err == null) { try { err = Qwen.foldError(Qwen.check(BIN, tmp)); } catch (Exception e) { err = e.getMessage(); } }
            if (err != null) tmp.delete(); else { dst.delete(); tmp.renameTo(dst); }
            final String fe = err;
            ui.post(() -> { copying.dismiss(); if (fe != null) { builder().setTitle(display).setMessage(fe).setPositiveButton("OK", null).show(); return; } foldQwen(id, 1f, display, "", true); });
        });
    }

    void pickQwenLora() {
        File root = Qwen.lorasDir(getFilesDir());
        File[] dirs = root.listFiles(File::isDirectory);
        List<String> names = new ArrayList<>();
        if (dirs != null) {
            Arrays.sort(dirs, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File f : dirs) if (Qwen.validOverlay(f)) names.add(f.getName());
        }
        if (names.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(L.s("LoRAs de Qwen", "Qwen LoRAs"))
                .setMessage(L.s("No hay paquetes LoRA Qwen V73 instalados. Los archivos safetensors de Civitai deben convertirse a overlays compatibles antes de usarlos.", "No Qwen V73 LoRA packs are installed. Civitai safetensors must be converted to compatible overlays before use."))
                .setPositiveButton("OK", null).show();
            return;
        }
        List<String> choices = new ArrayList<>(); choices.add(L.s("Ninguna", "None")); choices.addAll(names);
        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, choices));
        LinearLayout picker = new LinearLayout(this); picker.setOrientation(LinearLayout.VERTICAL);
        TextView hint = text(L.s("Mantén pulsada una LoRA para copiar sus activadores o eliminarla", "Long-press a LoRA to copy its trigger words or delete it"), 13, DIM, false);
        hint.setPadding(dp(20), dp(8), dp(20), dp(4)); picker.addView(hint);
        picker.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(L.s("LoRA de Qwen", "Qwen LoRA"))
            .setView(picker).setNegativeButton(L.s("Cancelar", "Cancel"), null).create();
        list.setOnItemClickListener((parent, view, which, id) -> {
            SharedPreferences.Editor edit = prefs().edit();
            if (which == 0) edit.remove("qwen_lora_dir"); else edit.putString("qwen_lora_dir", names.get(which - 1));
            edit.apply(); refreshQwenLora(); dialog.dismiss();
        });
        list.setOnItemLongClickListener((parent, view, which, id) -> {   // copy trigger words, or delete the overlay (and the original file it was converted from)
            if (which == 0) return true;
            final String nm = names.get(which - 1); final File ov = new File(root, nm);
            builder().setTitle(Qwen.overlayName(ov)).setItems(new String[]{L.s("Copiar palabras activadoras", "Copy trigger words"), L.s("Editar palabras activadoras…", "Edit trigger words…"), L.s("Añadir activadoras al prompt", "Add trigger words to the prompt"), L.s("Eliminar", "Delete")}, (d2, k) -> {
                if (k == 1) editTriggers(Qwen.overlayName(ov), Qwen.overlayTriggers(ov), s -> Qwen.setOverlayTriggers(ov, s));
                else if (k == 2) { String t = Qwen.overlayTriggers(ov); if (t.isEmpty()) Toast.makeText(this, L.s("Sin palabras activadoras: usa «Editar palabras activadoras…»", "No trigger words: use \"Edit trigger words…\""), Toast.LENGTH_LONG).show(); else { appendPromptWords(t); dialog.dismiss(); } }
                else if (k == 0) {
                    String trigger = Qwen.overlayTriggers(ov);
                    if (trigger.isEmpty()) Toast.makeText(this, L.s("Este overlay no tiene palabras activadoras guardadas", "This overlay has no saved trigger words"), Toast.LENGTH_LONG).show();
                    else { android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); if (clipboard != null) { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Qwen trigger words", trigger)); Toast.makeText(this, L.s("Palabras activadoras copiadas", "Trigger words copied"), Toast.LENGTH_SHORT).show(); } }
                } else builder().setMessage(L.f("¿Eliminar «%s»? Se borran el overlay y el archivo original.", "Delete \"%s\"? The overlay and the original file are removed.", Qwen.overlayName(ov))).setNegativeButton(L.s("Cancelar", "Cancel"), null)
                    .setPositiveButton(L.s("Eliminar", "Delete"), (d3, k3) -> {
                        MossLora.deleteTree(ov); Qwen.srcFile(getFilesDir(), nm).delete(); Civitai.metaFile(Qwen.srcFile(getFilesDir(), nm)).delete();
                        if (nm.equals(prefs().getString("qwen_lora_dir", ""))) prefs().edit().remove("qwen_lora_dir").apply();
                        dialog.dismiss(); refreshQwenLora();
                    }).show();
            }).show();
            return true;
        });
        dialog.show();
    }

    void importQwenOverlay() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try { startActivityForResult(i, 15); }
        catch (Exception e) { Toast.makeText(this, L.s("No se pudo abrir el selector de archivos", "Could not open the file picker"), Toast.LENGTH_LONG).show(); }
    }

    void toggleModel() {
        if (isMoss() || isQwen() || isMage()) return;   // these runners load per generation
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
        for (String nm : new String[]{"anima.sh", "moss.sh", "qwen.sh", "mage.sh"})
            try (InputStream in = getAssets().open(nm); FileOutputStream o = new FileOutputStream(new File(D, nm))) {
                byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            } catch (IOException e) { Toast.makeText(this, nm + ": " + e, Toast.LENGTH_LONG).show(); }
    }

    void refreshModels() {
        ids = store.installed();
        String was = prefs().getString("model", "");
        model = ids.isEmpty() ? "" : ids.contains(model) ? model : "";
        if (model.isEmpty() && Mage.is(was)) for (String id : ids) if (Qwen.is(id)) { model = id; break; }
        if (model.isEmpty() && !ids.isEmpty()) model = ids.get(0);
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
        if (isMage()) ex2.submit(() -> ctlSh.run("sh " + D + "/mage.sh stop < /dev/null >/dev/null 2>&1"));
        else if (isQwen()) ex2.submit(() -> ctlSh.run("sh " + D + "/qwen.sh stop < /dev/null >/dev/null 2>&1"));
        else if (isMoss()) ex2.submit(() -> ctlSh.run("sh " + D + "/moss.sh stop < /dev/null >/dev/null 2>&1"));
        else ex2.submit(() -> ctlSh.run("touch " + D + "/cancel; sh " + D + "/anima.sh stop < /dev/null >/dev/null 2>&1"));
    }

    void generate(boolean newSeed) {
        if (model.isEmpty()) { openModels(); return; }
        int cw = 0, ch = 0;
        try { cw = Integer.parseInt(wEdit.getText().toString().trim()); ch = Integer.parseInt(hEdit.getText().toString().trim()); } catch (NumberFormatException e) { }
        String bad = isMage() ? Mage.sizeError(cw, ch) : isMoss() ? mossSizeError(cw, ch) : isQwen() ? Qwen.sizeError(cw, ch) : sizeError(cw, ch);
        if (bad != null) { Toast.makeText(this, bad, Toast.LENGTH_LONG).show(); return; }
        if (cw != resW || ch != resH) setRes(cw, ch);
        final String pr0 = prompt.getText().toString().trim();
        if (pr0.isEmpty()) { Toast.makeText(this, L.s("Введите описание", "Enter a prompt"), Toast.LENGTH_SHORT).show(); return; }
        // memory guard: the model needs ~4.5 GB when it is not loaded yet, and a generation ~1.5 GB more; with less the phone swaps, heats up and can hang
        final long need = (loaded || isMoss() ? 0 : 4300) + 700, have = availMb();   // measured: the 2.9B model + a 512x768 picture took ~4.5 GB
        if (have < need && !memOk) {
            builder().setTitle(L.s("Мало свободной памяти", "Low free memory"))
                .setMessage(L.f("Свободно %d МБ, нужно около %d МБ. Без этого телефон начнёт тормозить и сильно греться.", "%d MB free, about %d MB are needed. Without it the phone slows down and gets very hot.", have, need))
                .setPositiveButton(L.s("Liberar RAM y continuar", "Free RAM and go on"), (d, k) -> ex2.submit(() -> { releaseAppMemory(); ui.post(() -> { memOk = true; generate(newSeed); memOk = false; }); }))
                .setNeutralButton(L.s("Всё равно", "Anyway"), (d, k) -> { memOk = true; generate(newSeed); memOk = false; })
                .setNegativeButton(L.s("Отмена", "Cancel"), null).show();
            return;
        }
        final String pr = pr0;
        String sd = seed.getText().toString().trim();
        final boolean randomSeed = newSeed || sd.isEmpty() || sd.equals("-1");   // -1 = random; the seed used is shown under the picture
        long base0 = 0; try { base0 = Long.parseLong(sd); } catch (NumberFormatException ignored) { }
        final long baseSeed = base0;
        final int gk = gridKind; gridKind = 0;
        final int n = gk != 0 ? 4 : count, w = resW, h = resH;
        final long gridSeed = randomSeed ? new Random().nextInt(1 << 30) : baseSeed;
        final List<Bitmap> gridBms = new ArrayList<>(); final List<String> gridLabels = new ArrayList<>();
        batchFiles.clear(); batchGridFile = null; batchDisplayIndex = 0; lastFile = null; pendingCompare = null; refreshGeneratedEditAction();
        busy = true; cancelled = false; curIdx = 0; curN = n; batchAdjustable = gk == 0;
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
        previewFile().delete();
        pvTime = 0;
        img.setImageDrawable(null); showImg(false); placeholder.setVisibility(View.VISIBLE);
        ex.submit(() -> {
            int made = 0; Bitmap lastBm = null; final List<File> madeFiles = new ArrayList<>();
            for (int i = 0; ; i++) {
                synchronized (batchLock) {
                    if (i >= curN || cancelled) { batchAdjustable = false; break; }
                    curIdx = i;
                }
                if (tooHot() && !waitCool(t0)) { hotStop = true; break; }   // pause before every image, including the first; Stop cancels the wait
                previewFile().delete();
                pvTime = 0;
                ui.post(() -> { if (busy) { img.setImageDrawable(null); showImg(false); placeholder.setVisibility(View.VISIBLE); } });
                final String seedS = String.valueOf(gk >= 2 ? gridSeed : randomSeed ? new Random().nextInt(1 << 30) : baseSeed + i);
                if (gk == 2) cfgOv = GRID_CFG[i]; else if (gk == 3) stepsOv = GRID_STEPS[i]; else if (gk == 4) cutOv = Settings.CFG_CUTS[i];
                final File f = runOne(pr, seedS, w, h, t0);
                cfgOv = null; stepsOv = 0; cutOv = 0;
                if (f == null) break;
                made++;
                madeFiles.add(f);
                final Bitmap bm = BitmapFactory.decodeFile(f.getPath());
                if (gk != 0 && bm != null) { gridBms.add(bm); gridLabels.add(gk == 1 ? "seed " + seedS : gk == 2 ? "CFG " + GRID_CFG[i] : gk == 4 ? L.f("CFG на %d %% шагов", "CFG on %d %% of the steps", Settings.CFG_CUTS[i]) : L.f("%d шагов", "%d steps", GRID_STEPS[i])); }
                lastBm = bm;
                final List<File> fileSnapshot = new ArrayList<>(madeFiles);
                ui.post(() -> {
                    batchFiles = fileSnapshot; batchGridFile = null; batchDisplayIndex = batchFiles.size() - 1;
                    lastFile = f; img.setImageBitmap(bm); showImg(true); placeholder.setVisibility(View.GONE);
                    refreshGeneratedEditAction();
                    setFavIcon(false);
                    for (ImageButton x : new ImageButton[]{againBtn, favBtn, dlBtn, shareBtn}) x.setVisibility(View.VISIBLE);
                    final ScrollView svv = (ScrollView) imgBox.getParent().getParent(); svv.post(() -> svv.smoothScrollTo(0, 0));   // after the image has been laid out
                    saved.setText("seed " + seedS);
                });
            }
            if (gk != 0 && gridBms.size() >= 2 && !cancelled) {
                final File gf = saveGrid(gridBms, gridLabels, pr, String.valueOf(gridSeed), w, h);
                final Bitmap gb = gf != null ? BitmapFactory.decodeFile(gf.getPath()) : null;
                if (gb != null) { lastBm = gb; ui.post(() -> { batchGridFile = gf; batchDisplayIndex = batchFiles.size(); lastFile = gf; img.setImageBitmap(gb); showImg(true); placeholder.setVisibility(View.GONE); saved.setText(L.s("Сетка сравнений · desliza para ver imágenes", "Comparison grid · ‹ › to view the pictures")); refreshGeneratedEditAction(); }); }
            } else if (gk == 0 && madeFiles.size() == 1 && pendingCompare != null && pendingCompare.isFile() && !cancelled) {
                final File cf = pendingCompare;
                ui.post(() -> { batchGridFile = cf; batchDisplayIndex = 0; saved.setText(L.s("Edición · usa ‹ › para ver la comparación", "Edit · ‹ › to see the comparison")); refreshGeneratedEditAction(); });
            } else if (gk == 0 && madeFiles.size() > 1) {
                final File gf = saveBatchGrid(madeFiles, pr, w, h);
                final Bitmap gb = gf != null ? BitmapFactory.decodeFile(gf.getPath()) : null;
                if (gb != null) { lastBm = gb; ui.post(() -> { batchGridFile = gf; batchDisplayIndex = batchFiles.size(); lastFile = gf; img.setImageBitmap(gb); showImg(true); placeholder.setVisibility(View.GONE); saved.setText(L.f("Cuadrícula · %d imágenes · desliza para recorrer", "Grid · %d images · ‹ › to browse", madeFiles.size())); refreshGeneratedEditAction(); }); }
            }
            final double sec = (System.currentTimeMillis() - t0) / 1000.0;
            final boolean wasCancelled = cancelled, okAny = made > 0, hot = hotStop || tooHot();
            hotStop = false;
            final int total = made;
            if (wasCancelled) loaded = false;   // Stop shut the server down
            synchronized (batchLock) { busy = false; cancelled = false; batchAdjustable = false; }
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
    // ---- comparison grid ----
    static final String[] GRID_CFG = {"1", "3", "5", "7"};
    static final int[] GRID_STEPS = {4, 8, 12, 20};
    int gridKind;          // 0 off, 1 seeds, 2 CFG, 3 steps, 4 CFG cutoff (set for one generate() call)
    String cfgOv; int stepsOv, cutOv;
    int cutNow() { return cutOv > 0 ? cutOv : Settings.cfgCut(this); }
    String cfgNow() { return cfgOv != null ? cfgOv : Settings.cfg(this); }
    boolean cfgOnNow() { try { return Float.parseFloat(cfgNow()) > 1.001f; } catch (NumberFormatException e) { return false; } }
    void compare() {
        if (busy || isMoss() || model.isEmpty()) return;
        String[] k = {L.s("4 сида", "4 seeds"), "CFG  1 · 3 · 5 · 7", L.s("Шаги  4 · 8 · 12 · 20", "Steps  4 · 8 · 12 · 20"), L.s("CFG на 100 · 80 · 70 · 60 % шагов", "CFG on 100 · 80 · 70 · 60 % of the steps")};
        builder().setTitle(L.s("Сравнить (сетка 2×2)", "Compare (2×2 grid)")).setItems(k, (d, i) -> { gridKind = i + 1; generate(false); }).show();
    }

    void showBatchPage(int page) {
        List<File> pages = new ArrayList<>(batchFiles);
        if (batchGridFile != null && batchGridFile.isFile()) pages.add(batchGridFile);
        if (pages.size() < 2) return;
        batchDisplayIndex = (page % pages.size() + pages.size()) % pages.size();
        File f = pages.get(batchDisplayIndex);
        Bitmap b = BitmapFactory.decodeFile(f.getPath()); if (b == null) return;
        lastFile = f; img.setImageBitmap(b); showImg(true); placeholder.setVisibility(View.GONE);
        refreshGeneratedEditAction();
        setFavIcon(Gallery.fav(f));
        JSONObject m = Gallery.meta(f);
        saved.setText(batchDisplayIndex == batchFiles.size() && batchFiles.size() == 1
            ? L.s("Original | editada", "Original | edited")
            : batchDisplayIndex == batchFiles.size()
            ? L.f("Cuadrícula · %d imágenes", "Grid · %d images", batchFiles.size())
            : L.f("Imagen %d de %d · seed %s", "Image %d of %d · seed %s", batchDisplayIndex + 1, batchFiles.size(), m.optString("seed", "?")));
    }

    /** the four pictures side by side with a label under each, saved like any other picture */
    File saveGrid(List<Bitmap> bms, List<String> labels, String prompt, String seedS, int w, int h) {
        int cw = Math.min(512, bms.get(0).getWidth()), ch = Math.round(cw * bms.get(0).getHeight() / (float) bms.get(0).getWidth()), lh = 44;
        Bitmap g = Bitmap.createBitmap(cw * 2, (ch + lh) * 2, Bitmap.Config.ARGB_8888); android.graphics.Canvas c = new android.graphics.Canvas(g); c.drawColor(0xFF1B172F);
        android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); tp.setColor(0xFFE9E6F5); tp.setTextSize(28); tp.setTypeface(F_SEMI); tp.setTextAlign(android.graphics.Paint.Align.CENTER);
        for (int i = 0; i < bms.size() && i < 4; i++) {
            int x = (i % 2) * cw, y = (i / 2) * (ch + lh);
            c.drawBitmap(bms.get(i), null, new android.graphics.Rect(x, y, x + cw, y + ch), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            c.drawText(labels.get(i), x + cw / 2f, y + ch + 31, tp);
        }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); g.compress(Bitmap.CompressFormat.PNG, 100, bo);
            JSONObject meta = new JSONObject().put("prompt", prompt).put("seed", seedS).put("w", w).put("h", h).put("model", modelName()).put("id", model).put("grid", android.text.TextUtils.join(" | ", labels));
            return Gallery.save(this, PngMeta.embed(bo.toByteArray(), meta), meta);
        } catch (Exception e) { return null; }
    }

    /** Contact sheet of a batch, at most 2048 px on its long side. The layout keeps the pictures as large as possible and avoids empty
     *  cells when that costs little (3 portraits sit in one row instead of 2×2 with a hole); an incomplete last row is centred.
     *  Each picture gets its number on a dark badge, with a thin gap between the cells. */
    File saveBatchGrid(List<File> files, String prompt, int w, int h) {
        if (files == null || files.size() < 2) return null;
        final int n = files.size(), side = 2048, gap = 8;
        BitmapFactory.Options first = new BitmapFactory.Options(); first.inJustDecodeBounds = true; BitmapFactory.decodeFile(files.get(0).getPath(), first);
        float aspect = first.outWidth > 0 && first.outHeight > 0 ? first.outWidth / (float) first.outHeight : 1f;
        int cols = 1, cellW = 1, cellH = 1; double best = -1;
        for (int c = 1; c <= n; c++) {
            int r = (n + c - 1) / c; if (r > 1 && c > 1 && (r - 1) * c >= n) continue;   // no layout with a whole empty row
            int mw = Math.max(1, (side - gap * (c - 1)) / c), mh = Math.max(1, (side - gap * (r - 1)) / r), cw, ch;
            if (aspect >= mw / (float) mh) { cw = mw; ch = Math.max(1, Math.round(mw / aspect)); } else { ch = mh; cw = Math.max(1, Math.round(mh * aspect)); }
            double score = (double) cw * ch * (1 - .12 * (c * r - n));   // picture area, each empty cell costs 12 %
            if (score > best + 1e-6) { best = score; cols = c; cellW = cw; cellH = ch; }
        }
        final int rows = (n + cols - 1) / cols;
        Bitmap grid;
        try { grid = Bitmap.createBitmap(cellW * cols + gap * (cols - 1), cellH * rows + gap * (rows - 1), Bitmap.Config.ARGB_8888); }
        catch (OutOfMemoryError e) { System.gc(); return null; }
        android.graphics.Canvas c = new android.graphics.Canvas(grid); c.drawColor(0xFF181522);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.Paint label = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), pill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        label.setColor(0xFFFFFFFF); label.setTextSize(Math.max(18, Math.min(44, cellW * 0.08f))); label.setTypeface(F_SEMI); label.setTextAlign(android.graphics.Paint.Align.CENTER);
        pill.setColor(0xB0000000);
        for (int i = 0; i < n; i++) {
            File f = files.get(i); BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue;
            int sample = 1; while (bounds.outWidth / (sample * 2) >= cellW && bounds.outHeight / (sample * 2) >= cellH) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options(); opt.inSampleSize = sample;
            Bitmap tile = BitmapFactory.decodeFile(f.getPath(), opt); if (tile == null) continue;
            int row = i / cols, inRow = row == rows - 1 ? n - row * cols : cols;   // the last row may be shorter: centre it
            int rowStart = (grid.getWidth() - (inRow * cellW + gap * (inRow - 1))) / 2;
            int x = rowStart + (i % cols) * (cellW + gap), y = row * (cellH + gap);
            float scale = Math.min(cellW / (float) tile.getWidth(), cellH / (float) tile.getHeight());
            int dw = Math.round(tile.getWidth() * scale), dh = Math.round(tile.getHeight() * scale);
            android.graphics.Rect dst = new android.graphics.Rect(x + (cellW - dw) / 2, y + (cellH - dh) / 2, x + (cellW + dw) / 2, y + (cellH + dh) / 2);
            c.drawBitmap(tile, null, dst, paint); tile.recycle();
            float r = label.getTextSize() * .9f, cx = dst.left + r + 14, cy = dst.top + r + 14;
            c.drawCircle(cx, cy, r, pill); c.drawText(String.valueOf(i + 1), cx, cy + label.getTextSize() * .35f, label);
        }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); grid.compress(Bitmap.CompressFormat.PNG, 100, bo); grid.recycle();
            JSONObject meta = new JSONObject().put("prompt", prompt).put("seed", "batch").put("w", w).put("h", h).put("model", modelName()).put("id", model).put("grid", "batch " + n).put("count", n);
            return Gallery.save(this, PngMeta.embed(bo.toByteArray(), meta), meta);
        } catch (Exception e) { if (!grid.isRecycled()) grid.recycle(); return null; }
    }

    /** One Mage-Flow image. This route intentionally has no step previews. */
    File runOneMage(String pr, String seedS, int w, int h, long t0) {
        final File home = getFilesDir(), m = Mage.dir(home), ids = new File(m, "ids.i32"), raw = new File(m, "img.rgb");
        final int mageSteps = 4; final Bitmap src = editBm; final boolean edit = src != null;
        setProgressUi(2, edit ? L.s("Preparando foto…", "Preparing photo…") : L.s("Tokenizando…", "Tokenising…"), t0);
        int drop, keys; String editArg = "";
        try {
            if (edit) {
                if ((w / 16) * (h / 16) > Mage.MAX_EDIT_PATCHES) { ui.post(() -> Toast.makeText(this, L.s("Para editar, usa un tamaño de hasta 1024×768", "For editing, use a size up to 1024×768"), Toast.LENGTH_LONG).show()); return null; }
                Mage.Edit e = Mage.prepareEdit(home, src, pr, w, h, new File(Mage.editDir(home), "ids.i32"));
                drop = e.drop; editArg = " " + q(Mage.editDir(home).getPath()); keys = 2 * (w / 16) * (h / 16) + e.tokens - e.drop;
            } else { drop = Mage.writeIds(home, pr, ids); keys = (w / 16) * (h / 16) + (int) (ids.length() / 4) - drop; }
            if (keys > Mage.MAX_KEYS) { ui.post(() -> Toast.makeText(this, L.s("El texto es demasiado largo para este tamaño; acórtalo o reduce la imagen", "The prompt is too long for this size; shorten it or reduce the image size"), Toast.LENGTH_LONG).show()); return null; }
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, "Mage: " + e, Toast.LENGTH_LONG).show()); return null; }
        final boolean[] done = {false};
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException ignored) { return; }
                String s = su("echo $(grep -c '^vit:' " + m + "/mage.log) $(grep -c '^te:' " + m + "/mage.log) $(grep -c '^txt_in:' " + m + "/mage.log) $(grep -c '^step' " + m + "/mage.log) $(grep -c '^vae dec total' " + m + "/mage.log)", true);
                try { String[] a = s.split("\\s+"); int vit = Integer.parseInt(a[0]), te = Integer.parseInt(a[1]), ti = Integer.parseInt(a[2]), st = Integer.parseInt(a[3]), dn = Integer.parseInt(a[4]);
                    if (dn > 0 || st >= mageSteps) setProgressUi(94, L.s("Decodificando…", "Decoding…"), t0);
                    else if (ti > 0 || st > 0) setProgressUi(30 + 60 * st / mageSteps, L.f("Paso %d/%d", "Step %d/%d", Math.min(st + 1, mageSteps), mageSteps), t0);
                    else if (te > 0) setProgressUi(28, L.s("Preparando texto…", "Preparing text…"), t0);
                    else if (edit && vit > 0) setProgressUi(15, L.s("Analizando foto en NPU…", "Analyzing photo on NPU…"), t0);
                    else if (edit) setProgressUi(5, L.s("Analizando foto…", "Analyzing photo…"), t0);
                    else setProgressUi(8, L.s("Codificando texto en NPU…", "Encoding text on NPU…"), t0);
                } catch (Exception ignored) { }
            }
        });
        poll.start();
        su("rm -f " + q(raw.getPath()) + "; M=" + q(m.getPath()) + " sh " + D + "/mage.sh " + q(ids.getPath()) + " " + drop + " " + w + " " + h + " " + seedS + " " + mageSteps + " " + q(raw.getPath()) + editArg + " < /dev/null >> " + D + "/gen.log 2>&1", false);
        done[0] = true; try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) return null;
        try {
            byte[] png = Mage.png(raw, w, h); raw.delete();
            JSONObject meta = new JSONObject().put("prompt", pr).put("seed", seedS).put("w", w).put("h", h).put("model", modelName()).put("id", model).put("sec", (System.currentTimeMillis() - t0) / 1000.0);
            if (edit) meta.put("edit", true);
            return Gallery.save(this, PngMeta.embed(png, meta), meta);
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Error de generación: %s (registro files/mage/mage.log)", "Generation error: %s (log files/mage/mage.log)", e.getMessage()), Toast.LENGTH_LONG).show()); return null; }
    }

    File runOne(String pr, String seedS, int w, int h, long t0) {
        if (isMage()) return runOneMage(pr, seedS, w, h, t0);
        if (isQwen()) return runOneQwen(pr, seedS, w, h, t0);
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
        coolTick(true);
        final String f = D + "/last.png";
        final boolean[] done = {false};
        final long[] tStep1 = {0}; remainS = -1;
        final int[] base = count();
        final int steps = stepsOv > 0 ? stepsOv : Settings.steps(this), calls = Math.max(1, (steps + (cfgOnNow() ? Settings.cfgSteps(steps, cutNow()) : 0)) * (Settings.twoCalls(Settings.sampler(this)) ? 2 : 1));   // animad logs one "step" line per DiT call: CFG / 2nd-order samplers make several per step
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                coolTick(false);
                int[] c = count();
                int st = c[0] - base[0];   // DiT calls done (the live preview also decodes after every step, so the VAE lines do not mean "finished")
                long nowMs = System.currentTimeMillis();
                if (st >= 1 && tStep1[0] == 0) tStep1[0] = nowMs;
                remainS = st >= 2 && st < calls ? (int) Math.ceil((nowMs - tStep1[0]) / 1000.0 / (st - 1) * (calls - st) + 0.5) : -1;   // from the pace of the calls after the first
                if (st >= calls) setProgressUi(94, L.s("Декодирование…", "Decoding…"), t0);
                else if (c[2] > base[2] || st > 0) setProgressUi(15 + (int) (76 * stepShare(st)), L.f("Шаг %d/%d", "Step %d/%d", Math.min(steps, st * steps / calls + 1), steps), t0);  // cached text skips the adapter log, but the first DiT call still started
                else setProgressUi(8, L.s("Кодирование текста…", "Encoding the text…"), t0);
            }
        });
        poll.start();
        final String samp = "CFG=" + q(cfgNow()) + " SAMPLER=" + q(Settings.sampler(this)) + " SCHED=" + q(Settings.scheduler(this)) + (cfgOnNow() ? " NEG=" + q(Settings.negative(this)) + " CFG_CUT=" + cutNow() : "");
        su("cd " + D + " && rm -f last.png && " + mp() + "PREFIX=" + q(Settings.prefix(this)) + " STEPS=" + steps + " " + samp + " " + extrasEnv(w, h, t0) + "W=" + w + " H=" + h + " LOGRE='learned_condition|completed in' sh ./anima.sh " + q(pr) + " " + seedS + " " + f + " < /dev/null >> gen.log 2>&1", false);
        done[0] = true; remainS = -1;
        try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) return null;
        byte[] png = new byte[0];
        try { png = Models.readBytes(new File(f)); } catch (Exception ignored) { }
        if (png.length == 0 || BitmapFactory.decodeByteArray(png, 0, png.length) == null) return null;
        try {
            JSONObject meta = new JSONObject().put("prompt", fullPrompt).put("seed", seedS).put("w", w).put("h", h).put("model", modelName()).put("id", model).put("sec", (System.currentTimeMillis() - t0) / 1000.0)
                .put("steps", steps).put("cfg", cfgNow()).put("sampler", Settings.sampler(this)).put("scheduler", Settings.scheduler(this));
            if (cfgOnNow() && !Settings.negative(this).isEmpty()) meta.put("negative", Settings.negative(this));
            if (loraOn() && loraFitsModel()) meta.put("lora", prefs().getString("anima_lora_name", "LoRA")).put("lora_strength", loraStr());
            if (!tg.used.isEmpty() && loraFitsModel()) meta.put("prompt_loras", String.join(", ", tg.used));
            return Gallery.save(this, PngMeta.embed(png, meta), meta);
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()); return null; }
    }

    /** one MOSS-Image picture: tokenise here, text encoder + DiT + VAE on the NPU through moss.sh, PNG with the settings inside */
    File runOneMoss(String pr, String seedS, int w, int h, long t0) {
        final File home = getFilesDir(), M = Moss.dir(home);
        try { Moss.ensureV73Vae(home, getAssets()); } catch (Exception e) { ui.post(() -> Toast.makeText(this, "VAE: " + e.getMessage(), Toast.LENGTH_LONG).show()); return null; }
        final File MR;
        try { MR = Moss.isVariant(model) ? Moss.variantView(home, model) : M; }
        catch (Exception e) { ui.post(() -> Toast.makeText(this, "MOSS variant: " + e.getMessage(), Toast.LENGTH_LONG).show()); return null; }
        final File ids = new File(MR, "ids.i32"), raw = new File(MR, "img.f32");
        final boolean useLora = loraOn(); final MossLora.Check activeLora = useLora ? MossLora.check(BIN, MossLora.src(home)) : null;
        final boolean lokrSelected = activeLora != null && activeLora.ok && activeLora.lokr;
        final boolean rsDmd = Moss.isRs(model) && prefs().getString("rs_mode", "4").equals("8") && Moss.installed8(home) && (!useLora || lokrSelected);
        final int mossSteps = rsDmd ? 8 : 4;   // the distilled MOSS models are made for 4 steps
        setProgressUi(3, L.s("Токенизация…", "Tokenising…"), t0);
        try { Moss.writeIds(home, pr, ids); } catch (Exception e) { ui.post(() -> Toast.makeText(this, "tokenizer: " + e, Toast.LENGTH_LONG).show()); return null; }
        String loraEnv = ""; final float lstr = loraStr();
        if (useLora) {
            if (activeLora == null || !activeLora.ok) { ui.post(() -> Toast.makeText(this, activeLora == null ? L.s("No se pudo validar la LoRA", "Could not validate the LoRA") : activeLora.message(), Toast.LENGTH_LONG).show()); return null; }
            final String[] msg = {""};
            File ovl = MossLora.ensure(home, BIN, loraId(), lstr, (d, t) -> setProgressUi(1 + 5 * d / t, L.f("Подготовка LoRA… %d/%d", "Preparing the LoRA… %d/%d", d, t), t0), () -> cancelled, msg);
            if (ovl == null) { if (!cancelled) ui.post(() -> Toast.makeText(this, L.f("Не удалось подготовить LoRA: %s", "Could not prepare the LoRA: %s", msg[0]), Toast.LENGTH_LONG).show()); return null; }
            loraEnv = (activeLora.lokr ? "MOSS_LOKR=" : "MOSS_LORA=") + q(ovl.getPath()) + " ";
        }
        final boolean[] done = {false};
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                String s = su("echo $(grep -c '^te:' " + MR + "/moss.log) $(grep -c '^refiner:' " + MR + "/moss.log) $(grep -c '^step ' " + MR + "/moss.log) $(grep -c '^done' " + MR + "/moss.log) $(grep -c '^te: cached' " + MR + "/moss.log) $(grep -c '^step_start ' " + MR + "/moss.log)", true);
                try {
                    String[] a = s.split("\\s+"); int te = Integer.parseInt(a[0]), rf = Integer.parseInt(a[1]), st = Integer.parseInt(a[2]), dn = Integer.parseInt(a[3]), tc = Integer.parseInt(a[4]), ss = Integer.parseInt(a[5]);
                    if (dn > 0) setProgressUi(94, L.s("Декодирование…", "Decoding…"), t0);
                    else if (rf > 0 || st > 0 || ss > 0) { int step = Math.min(Math.max(st, ss), mossSteps); setProgressUi(22 + 70 * step / mossSteps, L.f("Paso %d/%d", "Step %d/%d", step, mossSteps), t0); }
                    else if (te > 0 && tc > 0) setProgressUi(20, L.s("Texto reutilizado; preparando el primer paso…", "Text reused; preparing the first step…"), t0);
                    else if (te > 0) setProgressUi(20, L.s("Preparando el texto…", "Preparing the text…"), t0);
                    else setProgressUi(8, L.s("Кодирование текста на NPU…", "Encoding the text on the NPU…"), t0);
                } catch (Exception ignored) { }
            }
        });
        su("rm -f " + q(MR.getPath() + "/moss.log"), true);
        poll.start();
        su("rm -f " + q(raw.getPath()) + "; " + loraEnv + (rsDmd ? "MODE=8 " : "") + "M=" + q(MR.getPath()) + " sh " + D + "/moss.sh " + q(ids.getPath()) + " " + w + " " + h + " " + seedS + " " + mossSteps + " " + q(raw.getPath()) + " < /dev/null >> " + D + "/gen.log 2>&1", false);
        done[0] = true; remainS = -1;
        try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) return null;
        try {
            byte[] png = Moss.png(raw, w, h); raw.delete();
            JSONObject meta = new JSONObject().put("prompt", pr).put("seed", seedS).put("w", w).put("h", h).put("model", modelName()).put("id", model).put("steps", mossSteps).put("sec", (System.currentTimeMillis() - t0) / 1000.0);
            if (useLora) meta.put("lora", prefs().getString("moss_lora_name", "LoRA")).put("lora_strength", lstr);
            return Gallery.save(this, PngMeta.embed(png, meta), meta);
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Ошибка генерации: %s (лог files/moss/moss.log)", "Generation failed: %s (log files/moss/moss.log)", e.getMessage()), Toast.LENGTH_LONG).show()); return null; }
    }

    /** one Qwen-Image picture (or an edit of the picked photo): tokenise / prepare the photo here, everything else on the NPU through qwen.sh */
    File runOneQwen(String pr, String seedS, int w, int h, long t0) {
        final File home = getFilesDir(), Q = Qwen.dir(home), ids = new File(Q, "ids.i32"), raw = new File(Q, "img.f32");
        final Bitmap src = editBm; final boolean edit = src != null;
        final File maskFile = Qwen.editMask(home);
        final Qwen.DetailCrop detail = edit && maskFile.isFile() ? Qwen.detailCrop(src, maskFile) : null;
        final Bitmap condition = detail != null ? detail.image : src;
        final int[] detailDims = detail != null ? Qwen.detailSize(detail.width(), detail.height()) : edit && prefs().getBoolean("qwen_keep_aspect", true) ? Qwen.editSize(src.getWidth(), src.getHeight(), w, h) : new int[]{w, h};
        final int runW = detailDims[0], runH = detailDims[1];
        setProgressUi(2, edit ? L.s("Подготовка фото…", "Preparing the photo…") : L.s("Токенизация…", "Tokenising…"), t0);
        int drop; String edArg = "";
        try {
            if (edit) {
                boolean hifi = prefs().getBoolean("qwen_hifi", false);
                Qwen.Edit e = Qwen.prepareEdit(home, condition, pr, runW, runH, ids, hifi ? Qwen.COND_AREA_HI : Qwen.COND_AREA); drop = e.drop; edArg = " " + q(Qwen.editDir(home).getPath());
                int keys = (runW / 16) * (runH / 16) + e.nc + (e.tokens - e.drop - e.nm);
                if (hifi && keys > Qwen.MAX_KEYS) {   // too many tokens for one attention pass: fall back to the standard reference size
                    e = Qwen.prepareEdit(home, condition, pr, runW, runH, ids, Qwen.COND_AREA); drop = e.drop;
                    keys = (runW / 16) * (runH / 16) + e.nc + (e.tokens - e.drop - e.nm);
                    ui.post(() -> Toast.makeText(this, L.s("Alta fidelidad no cabe con este tamaño: se usa la referencia normal", "High fidelity does not fit this size: using the standard reference"), Toast.LENGTH_LONG).show());
                }
                if (keys > Qwen.MAX_KEYS) { if (detail != null) detail.recycle(); ui.post(() -> Toast.makeText(this, L.s("Foto y resultado juntos son demasiado grandes: elige un tamaño menor", "The photo and result together are too large: pick a smaller size"), Toast.LENGTH_LONG).show()); return null; }
            } else drop = Qwen.writeIds(home, pr, ids);
        } catch (Exception e) { if (detail != null) detail.recycle(); ui.post(() -> Toast.makeText(this, "qwen: " + e, Toast.LENGTH_LONG).show()); return null; }
        final boolean[] done = {false};
        Thread poll = new Thread(() -> {
            while (!done[0]) {
                try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                String s = su("echo $(grep -c '^vit:' " + Q + "/qwen.log) $(grep -c '^te:' " + Q + "/qwen.log) $(grep -c '^txt_in:' " + Q + "/qwen.log) $(grep -c '^step' " + Q + "/qwen.log) $(grep -c '^done' " + Q + "/qwen.log)", true);
                try {
                    String[] a = s.split("\\s+"); int vit = Integer.parseInt(a[0]), te = Integer.parseInt(a[1]), ti = Integer.parseInt(a[2]), st = Integer.parseInt(a[3]), dn = Integer.parseInt(a[4]);
                    if (dn >= (edit ? 2 : 1)) setProgressUi(94, L.s("Декодирование…", "Decoding…"), t0);
                    else if (ti > 0 || st > 0) setProgressUi(30 + 62 * st / STEPS, st == 0 && edit ? L.s("Paso 1/4 (lectura de foto)", "Step 1/4 (reading the photo)") : L.f("Paso %d/%d", "Step %d/%d", Math.min(st, STEPS), STEPS), t0);
                    else if (te > 0) setProgressUi(28, L.s("Подготовка текста…", "Preparing the text…"), t0);
                    else if (edit && vit > 0) setProgressUi(15, L.s("Кодирование текста на NPU…", "Encoding the text on the NPU…"), t0);
                    else if (edit) setProgressUi(5, L.s("Анализ фото на NPU…", "Analysing the photo on the NPU…"), t0);
                    else setProgressUi(8, L.s("Кодирование текста на NPU…", "Encoding the text on the NPU…"), t0);
                } catch (Exception ignored) { }
            }
        });
        File qwenOverlay = Qwen.selectedOverlay(home, prefs().getString("qwen_lora_dir", ""));
        String qwenLoraEnv = qwenOverlay == null ? "" : "QWEN_LORA=" + q(qwenOverlay.getPath()) + " ";
        su("rm -f " + q(Q + "/qwen.log"), true);
        poll.start();
        su("rm -f " + q(raw.getPath()) + "; " + qwenLoraEnv + "Q=" + q(Q.getPath()) + " VAE=" + Qwen.vaeCtx(home, runW, runH) + " sh " + D + "/qwen.sh " + q(ids.getPath()) + " " + drop + " " + runW + " " + runH + " " + seedS + " " + STEPS + " " + q(raw.getPath()) + edArg + " < /dev/null >> " + D + "/gen.log 2>&1", false);
        done[0] = true; remainS = -1;
        try { poll.join(); } catch (InterruptedException ignored) { }
        if (cancelled) { if (detail != null) detail.recycle(); return null; }
        try {
            byte[] png = Qwen.png(raw, runW, runH); raw.delete();
            if (edit && maskFile.isFile()) png = detail != null
                ? Qwen.applyDetailMask(png, src, maskFile, detail, runW, runH, w, h)
                : Qwen.applyEditMask(png, src, maskFile, runW, runH);
            int finalW = edit && maskFile.isFile() ? src.getWidth() : runW, finalH = edit && maskFile.isFile() ? src.getHeight() : runH;
            JSONObject meta = new JSONObject().put("prompt", pr).put("seed", seedS).put("w", finalW).put("h", finalH).put("model", modelName()).put("id", model).put("sec", (System.currentTimeMillis() - t0) / 1000.0);
            if (edit) meta.put("edit", true).put("masked_edit", maskFile.isFile()).put("detail_crop", detail != null).put("hifi", prefs().getBoolean("qwen_hifi", false));
            File out = Gallery.save(this, PngMeta.embed(png, meta), meta);
            if (edit && out != null) {
                Qwen.saveBefore(home, src, out);
                if (prefs().getBoolean("qwen_compare", false)) {
                    Bitmap after = BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (after != null) { pendingCompare = saveSideBySide(src, after, meta); after.recycle(); }
                }
            }
            return out;
        } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Ошибка генерации: %s (лог files/qwen/qwen.log)", "Generation failed: %s (log files/qwen/qwen.log)", e.getMessage()), Toast.LENGTH_LONG).show()); return null; }
        finally { if (detail != null) detail.recycle(); }
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
    File previewFile() { return new File(D, "preview.png"); }
    volatile int remainS = -1;   // seconds left for the current picture, from the pace of the DiT calls (-1 = unknown)
    volatile long pvTime;
    /** live preview: sd-cli renames a fresh preview.png into place after every step; show it while the picture is being made */
    void showPreview(long t0) {
        File pv = previewFile(); long m = pv.lastModified();
        if (!busy || m == 0 || m == pvTime || m < t0) return;
        pvTime = m; final int imageAt = curIdx; final Bitmap b = BitmapFactory.decodeFile(pv.getPath()); if (b == null) return;
        ui.post(() -> { if (!busy || curIdx != imageAt || previewFile().lastModified() != m) return; img.setImageBitmap(b); showImg(true); placeholder.setVisibility(View.GONE); });
    }
    void setProgressUi(int pct, String msg, long t0) {
        showPreview(t0);
        final int all = (curIdx * 100 + pct) / curN;
        final String pre = curN > 1 ? "(" + (curIdx + 1) + "/" + curN + ")  " : "";
        final int rem = remainS;
        final String t = rem > 0 ? L.f("%s  ·  осталось ~%d с", "%s  ·  ~%d s left", pre + msg, rem) : L.f("%s  ·  %.0f с", "%s  ·  %.0f s", pre + msg, (System.currentTimeMillis() - t0) / 1000.0);
        ui.post(() -> { bar.setProgress(all); status.setText(t); });
    }
}
