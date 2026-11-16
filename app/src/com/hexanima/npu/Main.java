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
public class Main extends MainEdit {
    /** popular sizes, all valid for the NPU pipeline (multiples of 16, patch count divisible by 32, at most 6144); grouped by aspect ratio, a portrait and its landscape twin next to each other */
    static final int[][] SIZES = {{512, 512}, {640, 640}, {768, 768}, {1024, 1024}, {512, 768}, {768, 512}, {768, 1152}, {1152, 768}, {1024, 1536}, {1536, 1024}, {768, 1024}, {1024, 768}, {960, 1280}, {1280, 960},
        {576, 1024}, {1024, 576}, {640, 1152}, {1152, 640}, {960, 1536}, {1536, 960}};
    static volatile boolean visible;   // the main screen is in front (otherwise a finished picture is announced by a notification)

    final Object batchLock = new Object();
    volatile boolean batchAdjustable;
    boolean statusSeen;

    LinearLayout customRow, imgBox;
    Button loadBtn, genBtn, countBtn;
    LinearLayout loraBox; TextView loraName, loraInfo; EditText strText; SeekBar strBar; Button loraPick, loraDrop; Button gptStyleBtn;
    LinearLayout sampBox, negBox, mossStepsBox; EditText negEdit, stepsEdit, cfgEdit; Button samplerBtn, schedBtn, cutBtn, mossStepsBtn; TextView sampNote;
    LinearLayout pageBar, imgTools;   // ‹ 2 / 3 › over a batch picture (swiping needs a quick fling; the buttons always work)
    TextView pageTag;   // "2 / 3" over a batch picture: the caption under a tall picture is off screen
    TextView clearX;   // small ✕ over the shown picture: back to the placeholder (the picture stays in the History)
    int batchDisplayIndex;
    android.view.GestureDetector batchGestures;

    /** LoRAs called from the current prompt with <lora:name:weight> (file*weight items), set by runOne */
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
        pageBg(frame);
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
        ramTxt = text("", 12, DIM, false); ramTxt.setOnClickListener(v -> freeMemoryDialog());   // tap: free memory (was the RAM button)
        Button more = button("⋯", CARD, TXT);
        more.setTextSize(20);
        more.setOnClickListener(v -> menu(v));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(48), dp(48)); ml.leftMargin = dp(6);
        tr.addView(more, ml);
        root.addView(tr);
        ramTxt.setPadding(dp(4), dp(6), 0, dp(4));
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
        // load / unload lives in the ⋯ menu now (the button keeps its text and state for that menu entry)
        root.addView(head);
        state = text(L.s("Проверяю…", "Checking…"), 12, DIM, false);
        state.setPadding(dp(4), dp(6), 0, dp(12));
        root.addView(state);

        qwenLoraBox = new LinearLayout(this); qwenLoraBox.setOrientation(LinearLayout.VERTICAL);
        qwenFold = fold(qwenLoraBox, "LORA", "qlora"); final LinearLayout qb = qwenFold.body;
        LinearLayout qlr = new LinearLayout(this); qlr.setGravity(Gravity.CENTER_VERTICAL);
        qwenLoraName = text(L.s("Ninguna", "None"), 13, TXT, false); qwenLoraName.setSingleLine(true); qwenLoraName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        qwenLoraName.setBackground(bg(CARD, 14)); qwenLoraName.setPadding(dp(12), dp(11), dp(12), dp(11));
        qlr.addView(qwenLoraName, new LinearLayout.LayoutParams(0, -2, 1));
        qwenLoraPick = button(L.s("Elegir", "Choose"), CARD, ACC); qwenLoraPick.setOnClickListener(v -> pickQwenLora());
        qwenLoraPick.setText(L.s("Biblioteca", "Library")); qwenLoraPick.setTextSize(13); qwenLoraPick.setAllCaps(false);
        qwenLoraDrop = button("✕", CARD, TXT); qwenLoraDrop.setOnClickListener(v -> { prefs().edit().remove("qwen_lora_dir").apply(); refreshQwenLora(); });
        LinearLayout.LayoutParams qld = new LinearLayout.LayoutParams(dp(48), dp(42)); qld.leftMargin = dp(6); qlr.addView(qwenLoraDrop, qld);
        qb.addView(qlr);
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
        qwenStrRow.setVisibility(View.GONE);
        LinearLayout qir = new LinearLayout(this); qir.setPadding(0, dp(7), 0, 0);
        Button qwenStImport = button(L.s("Importar", "Import"), CARD, ACC); qwenStImport.setAllCaps(false); qwenStImport.setTextSize(13);
        qwenStImport.setOnClickListener(v -> { try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 16); } catch (Exception e) { Toast.makeText(this, L.s("No se pudo abrir el selector de archivos", "Could not open the file picker"), Toast.LENGTH_LONG).show(); } });
        loraActions(qir, qwenLoraPick, qwenStImport);
        TextView qwenLoraHint = text(L.s("Una LoRA de Qwen-Image 2.1 (.safetensors, también desde Civitai) se convierte en el teléfono en unos segundos.", "A Qwen-Image 2.1 LoRA (.safetensors, also from Civitai) is converted on the phone in a few seconds."), 11, DIM, false);
        qwenLoraHint.setPadding(dp(4), dp(6), dp(4), 0); qb.addView(qwenLoraHint);
        TextView zipLink = link(L.s("Importar overlay (.zip)", "Import overlay (.zip)")); zipLink.setTextSize(13); zipLink.setPadding(dp(4), dp(4), 0, 0); zipLink.setOnClickListener(v -> importQwenOverlay()); qb.addView(zipLink);
        qb.addView(qwenStrRow); qb.addView(qir);
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
        imgTools = new LinearLayout(this) {   // the icon buttons shrink together when they do not fit, so the ✕ at the end never leaves the screen
            @Override protected void onMeasure(int ws, int hs) {
                int avail = MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight(), n = 0, fixed = 0;
                for (int i = 0; i < getChildCount(); i++) { View c = getChildAt(i); if (c.getVisibility() == View.GONE) continue;
                    if (c == pageBar) { c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(dp(40), MeasureSpec.EXACTLY)); fixed += c.getMeasuredWidth(); }
                    else if (c instanceof ImageButton || c == clearX) n++; }
                if (n > 0) { int w = Math.max(dp(30), Math.min(dp(48), (avail - fixed - n * dp(6)) / n)), gap = Math.max(dp(3), Math.min(dp(6), (avail - fixed - n * w) / n));
                    for (int i = 0; i < getChildCount(); i++) { View c = getChildAt(i); if (c instanceof ImageButton || c == clearX) { LayoutParams lp = (LayoutParams) c.getLayoutParams(); lp.width = w; lp.leftMargin = gap; } } }
                super.onMeasure(ws, hs);
            }
        };
        imgTools.setGravity(Gravity.CENTER_VERTICAL); imgTools.setPadding(0, 0, 0, dp(8)); imgTools.setVisibility(View.GONE);
        pageBar = new LinearLayout(this); pageBar.setGravity(Gravity.CENTER_VERTICAL); pageBar.setBackground(bg(CARD, 14)); pageBar.setVisibility(View.GONE);
        TextView prevB = text("‹", 22, ACC, true), nextB = text("›", 22, ACC, true);
        for (TextView pb : new TextView[]{prevB, nextB}) { pb.setGravity(Gravity.CENTER); pb.setPadding(dp(11), 0, dp(11), dp(3)); }
        prevB.setOnClickListener(v -> showBatchPage(batchDisplayIndex - 1)); nextB.setOnClickListener(v -> showBatchPage(batchDisplayIndex + 1));
        pageTag = text("", 13, TXT, true); pageTag.setGravity(Gravity.CENTER); pageTag.setMinWidth(dp(64)); pageTag.setSingleLine(true);   // fixed width: the arrows stay put while the text changes
        pageBar.addView(prevB, new LinearLayout.LayoutParams(-2, dp(40))); pageBar.addView(pageTag); pageBar.addView(nextB, new LinearLayout.LayoutParams(-2, dp(40)));
        imgTools.addView(pageBar, new LinearLayout.LayoutParams(-2, dp(40)));
        imgTools.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        clearX = text("✕", 16, TXT, true); clearX.setGravity(Gravity.CENTER); clearX.setBackground(bg(CARD, 14)); clearX.setVisibility(View.GONE); clearX.setContentDescription(L.s("Quitar la imagen de la pantalla", "Hide the picture"));
        clearX.setOnClickListener(v -> clearShown());
        root.addView(imgTools);
        imgBox.addView(img, new LinearLayout.LayoutParams(-1, -2));
        root.addView(imgBox, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout resRow = new LinearLayout(this);
        resRow.setGravity(Gravity.CENTER_VERTICAL);
        saved = text("", 12, DIM, false);
        saved.setPadding(dp(4), dp(8), dp(8), 0);
        resRow.addView(saved, new LinearLayout.LayoutParams(0, -2, 1));
        editGeneratedBtn = button("✎", CARD, ACC); editGeneratedBtn.setContentDescription(L.s("Editar esta imagen", "Edit this picture"));
        editGeneratedBtn.setTextSize(18); editGeneratedBtn.setPadding(0, 0, 0, 0); editGeneratedBtn.setVisibility(View.GONE);
        editGeneratedBtn.setOnClickListener(v -> useShownImageAsQwenSource());
        editRow = new LinearLayout(this); editRow.setGravity(Gravity.CENTER_VERTICAL); editRow.setPadding(0, dp(6), 0, 0);   // own row: the seed text and the icons keep their width
        editGeneratedBtn.setOnLongClickListener(v -> { Toast.makeText(this, isMage() ? L.s("Editar con Mage", "Edit with Mage") : L.s("Editar con Qwen", "Edit with Qwen"), Toast.LENGTH_SHORT).show(); return true; });
        compareBtn = button("⇆", CARD, ACC); compareBtn.setContentDescription(L.s("Antes/después", "Before/after"));
        compareBtn.setTextSize(18); compareBtn.setPadding(0, 0, 0, 0); compareBtn.setVisibility(View.GONE);
        compareBtn.setOnLongClickListener(v -> { Toast.makeText(this, L.s("Antes/después", "Before/after"), Toast.LENGTH_SHORT).show(); return true; });
        compareBtn.setOnClickListener(v -> showBeforeAfter(lastFile));
        LinearLayout.LayoutParams cmpl = new LinearLayout.LayoutParams(dp(48), dp(48)); cmpl.leftMargin = dp(6); resRow.addView(compareBtn, cmpl);
        LinearLayout.LayoutParams edl = new LinearLayout.LayoutParams(dp(48), dp(48)); edl.leftMargin = dp(6); resRow.addView(editGeneratedBtn, edl);
        againBtn = icon("ic_refresh", CARD);
        againBtn.setOnClickListener(v -> generate(true));   // the same prompt with a new random seed
        againBtn.setOnLongClickListener(v -> { variations(); return true; });
        againBtn.setContentDescription(L.s("Otra vez (mantén pulsado: variaciones)", "Again (long-press: variations)"));
        favBtn = icon("ic_star_off", CARD);
        favBtn.setOnClickListener(v -> { if (lastFile == null) return; boolean nv = !Gallery.fav(lastFile); Gallery.setFav(lastFile, nv); setFavIcon(nv); });
        dlBtn = icon("ic_download", CARD);
        dlBtn.setOnClickListener(v -> { if (lastFile == null) return; try { Gallery.export(this, lastFile); Toast.makeText(this, L.s("Сохранено в галерею: Pictures/Anima", "Saved to gallery: Pictures/Anima"), Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); } });
        upBtn = icon("ic_upscale", CARD); upBtn.setContentDescription(L.s("Ampliar en la NPU", "Enlarge on the NPU")); upBtn.setImageTintList(ColorStateList.valueOf(TXT));
        upBtn.setOnClickListener(v -> { if (lastFile == null) return; if (busy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_LONG).show(); return; }
            upscaleDialog(lastFile, out -> { prefs().edit().remove("hidden_last").apply(); lastFile = null; restoreLatestGenerated(); }); });
        upBtn.setOnLongClickListener(v -> { Toast.makeText(this, L.s("Ampliar en la NPU", "Enlarge on the NPU"), Toast.LENGTH_SHORT).show(); return true; });
        fixBtn = icon("ic_tune", CARD); fixBtn.setContentDescription(L.s("Correcciones", "Corrections")); fixBtn.setImageTintList(ColorStateList.valueOf(TXT));
        fixBtn.setOnClickListener(v -> { if (lastFile == null) return; if (busy) { Toast.makeText(this, L.s("Espera a que termine la generación", "Wait for the generation to finish"), Toast.LENGTH_LONG).show(); return; } Corrections.open(this, lastFile); });
        fixBtn.setOnLongClickListener(v -> { Toast.makeText(this, L.s("Correcciones", "Corrections"), Toast.LENGTH_SHORT).show(); return true; });
        shareBtn = icon("ic_share", CARD);
        shareBtn.setOnClickListener(v -> { if (lastFile != null) Gallery.share(this, lastFile); });
        for (ImageButton x : new ImageButton[]{againBtn, favBtn, dlBtn, upBtn, fixBtn, shareBtn}) {
            x.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48)); lp.leftMargin = dp(6);
            if (x == againBtn) resRow.addView(x, lp); else imgTools.addView(x, lp);   // "again" stays by the seed; the file actions go to the toolbar
        }
        LinearLayout.LayoutParams cxl = new LinearLayout.LayoutParams(dp(48), dp(48)); cxl.leftMargin = dp(6); imgTools.addView(clearX, cxl);
        editRow.setVisibility(View.GONE); root.addView(resRow);

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
        negBox = new LinearLayout(this); negBox.setOrientation(LinearLayout.VERTICAL);   // the negative prompt sits right under the prompt (models that use it)
        LinearLayout negHead = new LinearLayout(this); negHead.setGravity(Gravity.CENTER_VERTICAL);
        negHead.addView(label(L.s("НЕГАТИВНЫЙ ПРОМПТ", "NEGATIVE PROMPT")), new LinearLayout.LayoutParams(0, -2, 1));
        TextView negDef = link(L.s("По умолчанию", "Default")); negDef.setOnClickListener(v -> negEdit.setText(Settings.turbo(this) ? "" : Settings.NEG_DEFAULT)); negHead.addView(negDef);
        TextView negTpl = link(L.s("Plantillas", "Templates")); negTpl.setOnClickListener(v -> negTemplates()); negHead.addView(negTpl);
        TextView negClr = link(L.s("Очистить", "Clear")); negClr.setOnClickListener(v -> negEdit.setText("")); negHead.addView(negClr);
        negBox.addView(negHead);
        negEdit = field("lowres, worst quality, bad anatomy…");
        negEdit.setText(Settings.negative(this)); negEdit.setMinLines(2); negEdit.setGravity(Gravity.TOP); negEdit.setBackground(bg(CARD, 14));
        negEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        negEdit.addTextChangedListener(watch(s -> { prefs().edit().putString(Settings.key(this, "neg"), s).apply(); countTokens(); }));
        cleanWhileTyping(negEdit);
        negBox.addView(negEdit);
        negTokText = text("", 12, DIM, false); negTokText.setGravity(Gravity.END); negTokText.setPadding(0, dp(4), dp(6), 0); negBox.addView(negTokText);   // encoded separately from the prompt: its own count
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
        TextView sizeLabel = label(L.s("РАЗМЕР", "SIZE")); root.addView(sizeLabel);
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
        ImageButton dice = icon("ic_dice", CARD); dice.setContentDescription(L.s("Semilla aleatoria", "Random seed")); dice.setImageTintList(ColorStateList.valueOf(TXT));
        dice.setOnClickListener(v -> seed.setText(String.valueOf(new Random().nextInt(1 << 30))));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(52), dp(44)); dl.leftMargin = dp(8);
        sr.addView(dice, dl);
        root.addView(sr);
        seed.addTextChangedListener(watch(x -> updImgSum()));

        // MOSS-Image: user LoRA (picked file is checked at once; strength slider; folded with the turbo adapter on first use)
        loraBox = new LinearLayout(this); loraBox.setOrientation(LinearLayout.VERTICAL);
        loraFold = fold(loraBox, "LORA", "lora");
        LinearLayout lr = new LinearLayout(this); lr.setGravity(Gravity.CENTER_VERTICAL);
        loraName = text(L.s("не выбрана", "none"), 14, TXT, false); loraName.setSingleLine(true); loraName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        loraName.setBackground(bg(CARD, 16)); loraName.setPadding(dp(16), dp(12), dp(16), dp(12));
        lr.addView(loraName, new LinearLayout.LayoutParams(0, -2, 1));
        loraPick = button(L.s("Importar", "Import"), CARD, ACC); loraPick.setTextSize(13); loraPick.setAllCaps(false); loraPick.setOnClickListener(v -> pickLora());
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
        Button loraLib = button(L.s("Biblioteca", "Library"), CARD, ACC); loraLib.setTextSize(13); loraLib.setAllCaps(false); loraLib.setOnClickListener(v -> loraLibrary());
        LinearLayout lar = new LinearLayout(this); lar.setPadding(0, dp(8), 0, 0); loraActions(lar, loraLib, loraPick); loraFold.body.addView(lar);
        gptStyleBtn = button(L.s("✦ Estilo ChatGPT", "✦ ChatGPT style"), CARD, ACC); gptStyleBtn.setTextSize(13); gptStyleBtn.setAllCaps(false); gptStyleBtn.setOnClickListener(v -> gptStyle());
        LinearLayout.LayoutParams gpl = new LinearLayout.LayoutParams(-1, dp(42)); gpl.topMargin = dp(6); loraFold.body.addView(gptStyleBtn, gpl);
        root.addView(loraBox);
        refreshLora();

        // Qwen-Image: a photo to edit (with it the picture is an edit of the photo, guided by the prompt)
        buildAnimaInit();
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
        // more reference pictures (Qwen): image 2 and 3 next to the photo, e.g. "the character of image 1 wearing the outfit of image 2"
        refsRow = new LinearLayout(this); refsRow.setGravity(Gravity.CENTER_VERTICAL); refsRow.setPadding(0, dp(8), 0, 0);
        editBox.addView(refsRow);
        editAddRef = button(L.s("+ Otra imagen de referencia", "+ Another reference picture"), FIELD, ACC); editAddRef.setTextSize(12); editAddRef.setAllCaps(false);
        editAddRef.setOnClickListener(v -> { if (busy) return; startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 17); });
        LinearLayout.LayoutParams arl = new LinearLayout.LayoutParams(-1, dp(38)); arl.topMargin = dp(6); editBox.addView(editAddRef, arl);
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

        // picture first and the prompt right under it; then the photo to edit, the LoRA (one place for every family) and the extras;
        // size, seed and sampling go together in the "Image" fold (closed: its summary shows size and seed)
        LinearLayout imageSet = new LinearLayout(this); imageSet.setOrientation(LinearLayout.VERTICAL);
        imgFold = fold(imageSet, L.s("IMAGEN", "IMAGE"), "imgset");
        for (View v : new View[]{sizeLabel, sizeRow, customRow, seedLabel, sr}) { root.removeView(v); imgFold.body.addView(v); }
        LinearLayout ramRow = new LinearLayout(this); ramRow.setGravity(Gravity.CENTER_VERTICAL); ramRow.setPadding(0, dp(6), 0, 0);
        root.removeView(ramTxt); if (loadBtn.getParent() != null) ((ViewGroup) loadBtn.getParent()).removeView(loadBtn);
        ramRow.addView(ramTxt, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lbl = new LinearLayout.LayoutParams(-2, dp(40)); lbl.leftMargin = dp(8); ramRow.addView(loadBtn, lbl);   // load / free stays in sight under the model
        View[] order = {tr, warnTxt, head, ramRow, ramBar, state, imgTools, imgBox, resRow, pl, prompt, tb, negBox, sampBox, mossStepsBox, editBox, animaInitBox, qwenLoraBox, loraBox, extrasBox, imageSet};
        java.util.List<View> rest = new java.util.ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) { View v = root.getChildAt(i); if (!java.util.Arrays.asList(order).contains(v)) rest.add(v); }
        root.removeAllViews(); for (View v : order) root.addView(v); for (View v : rest) root.addView(v);
        updImgSum();

        setContentView(frame);
        SharedPreferences p = prefs();   // restore the last session's inputs
        prompt.setText(p.getString("prompt", "")); seed.setText(p.getString("seed", "-1")); model = p.getString("model", "");
        refreshModels();
        setRes(p.getInt("w", 512), p.getInt("h", 768));
        refreshState();
        if (!p.getBoolean("welcomed", false)) { startActivity(new Intent(this, Welcome.class)); try { p.edit().putString("seen_version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName).apply(); } catch (Exception ignored) { } }   // a new install has nothing "new"
        else ui.postDelayed(this::whatsNew, 600);   // an update: what changed (once per version)
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
        if (Corrections.freedAnima) { Corrections.freedAnima = false; if (loaded) { loaded = false; setModelUi(false, false, ""); } }   // Corrections stopped the Anima server for Qwen
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
        lastFile = f; batchFiles.clear(); batchGridFile = null; extraGrids.clear(); batchDisplayIndex = 0;
        img.setImageBitmap(b); showImg(true); placeholder.setVisibility(View.GONE);
        setFavIcon(Gallery.fav(f));
        for (ImageButton x : new ImageButton[]{againBtn, favBtn, dlBtn, upBtn, fixBtn, shareBtn}) x.setVisibility(View.VISIBLE);
        refreshGeneratedEditAction();
        JSONObject m = Gallery.meta(f);
        saved.setText(m.has("grid") ? L.f("Última cuadrícula · %d imágenes", "Last grid · %d pictures", m.optInt("count", 0)) : m.optBoolean("compare") ? L.s("Última comparación", "Last comparison") : L.f("Última imagen · seed %s", "Last image · seed %s", m.optString("seed", "?")));
    }

    /** every 2 s: RAM strip, thermal / battery / chip warnings; every 10 s the model state (the idle watchdog may have unloaded it) */

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == REQ_TAG_PICK) {
            if (res != RESULT_OK || d == null || d.getData() == null) return;
            final android.net.Uri u = d.getData();
            ex2.submit(() -> { try { final File f = importPicked(u, "picked_tag.png"); ui.post(() -> showPicturePrompt(f)); } catch (Exception e) { ui.post(() -> Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show()); } });
            return;
        }
        if (req == REQ_UPSCALE_PICK) {
            if (res != RESULT_OK || d == null || d.getData() == null) return;
            final android.net.Uri u = d.getData();
            ex2.submit(() -> {
                try { final File f = importPicked(u, "picked_upscale.png"); ui.post(() -> upscaleDialog(f, out -> { f.delete(); prefs().edit().remove("hidden_last").apply(); lastFile = null; restoreLatestGenerated(); })); }
                catch (Exception e) { ui.post(() -> Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show()); }
            });
            return;
        }
        if (req == Corrections.REQ && res == RESULT_OK) { prefs().edit().remove("hidden_last").apply(); lastFile = null; restoreLatestGenerated(); return; }   // the corrected picture is the newest one
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
        if (req == 17 && res == RESULT_OK && d != null && d.getData() != null) { final Uri u = d.getData(); ex2.submit(() -> { try { Qwen.importRef(getContentResolver(), u, getFilesDir()); } catch (Exception e) { ui.post(() -> Toast.makeText(this, L.f("Не удалось открыть фото: %s", "Cannot open the photo: %s", e.getMessage()), Toast.LENGTH_LONG).show()); } ui.post(this::refreshEdit); }); return; }
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
        lastFile = null; batchFiles.clear(); batchGridFile = null; extraGrids.clear(); batchDisplayIndex = 0;
        img.setImageDrawable(null); showImg(false); placeholder.setVisibility(View.VISIBLE);
        for (ImageButton x : new ImageButton[]{againBtn, favBtn, dlBtn, upBtn, fixBtn, shareBtn}) x.setVisibility(View.GONE);
        saved.setText(""); refreshGeneratedEditAction();
    }
    void refreshGeneratedEditAction() {
        if (pageTag != null) {
            int n = batchFiles.size(); boolean grid = batchGridFile != null && batchGridFile.isFile();
            boolean show = lastFile != null && img.getVisibility() == View.VISIBLE && (n > 1 || (n == 1 && grid));
            pageBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) pageTag.setText(batchDisplayIndex >= n ? (n == 1 ? L.s("Original | editada", "Original | edited") : extraGrids.isEmpty() ? L.f("Cuadrícula · %d", "Grid · %d", n) : L.f("Cuadrícula %d/%d", "Grid %d/%d", batchDisplayIndex - n + 1, extraGrids.size() + 1)) : (batchDisplayIndex + 1) + " / " + n);
        }
        boolean sheet = lastFile != null && (lastFile == batchGridFile || Gallery.meta(lastFile).has("grid") || Gallery.meta(lastFile).optBoolean("compare"));   // a contact sheet or a comparison is not a picture to edit
        if (editGeneratedBtn != null) editGeneratedBtn.setVisibility(lastFile != null && lastFile.isFile() && !sheet ? View.VISIBLE : View.GONE);
        if (compareBtn != null) compareBtn.setVisibility(lastFile != null && lastFile != batchGridFile && originalOf(lastFile) != null ? View.VISIBLE : View.GONE);
        if (editRow != null) editRow.setVisibility(editGeneratedBtn.getVisibility() == View.VISIBLE || compareBtn.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
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
        return c.ok;   // A/B: turbo-slot overlay; LoKr: baked DiT blocks
    }
    boolean loraOn() { return !loraId().isEmpty() && loraSrc().exists() && loraStr() > 0 && loraFitsModel(); }
    boolean canUseRsDmd() {
        if (!Moss.isRs(model) || !Moss.installed8(getFilesDir())) return false;
        if (!loraOn()) return true;
        MossLora.Check c = MossLora.check(BIN, loraSrc());
        return c.ok && c.lokr;
    }
    /** the "✓ Compatible: …" line in the current language: rebuilt from the check instead of the text saved at import (that one keeps the language of that moment) */
    String liveLoraInfo() {
        String saved = prefs().getString(lp() + "lora_info", "");
        try {
            if (isMoss()) {
                MossLora.Check c = MossLora.check(BIN, loraSrc()); if (!c.ok) return saved;
                File f = new File(MossLora.civitaiDir(getFilesDir()), prefs().getString("moss_lora_file", ""));
                String w = f.isFile() ? Civitai.words(f) : "";
                if (w.isEmpty()) for (String line : saved.split("\n")) { int k = line.indexOf(": "); if (k > 0 && (line.startsWith("Activadores") || line.startsWith("Triggers") || line.startsWith("Триггеры"))) w = line.substring(k + 2).trim(); }
                return "✓ " + c.message() + (w.isEmpty() ? "" : "\n" + L.s("Activadores: ", "Triggers: ") + w);
            }
            AnimaLora.Check c = AnimaLora.check(loraSrc()); if (!c.ok) return saved;
            String tag = loraSrc().getName().replaceAll("\\.safetensors$", "");
            return "✓ " + c.message() + "\n" + L.f("В промпте: <lora:%s:1.0>", "In the prompt: <lora:%s:1.0>", tag);
        } catch (Exception e) { return saved; }
    }
    void refreshLora() {
        boolean has = !loraId().isEmpty() && loraSrc().exists();
        loraName.setText(has ? prefs().getString(lp() + "lora_name", "LoRA") : L.s("не выбрана", "none"));
        String hint = L.s("Файл .safetensors, проверяется при выборе. Библиотека: «LoRAs» над промптом.", "A .safetensors file, checked when imported. Long-press a LoRA in the Library for its trigger words, category or to delete it.");
        boolean fits = !has || loraFitsModel();
        String info = has ? liveLoraInfo() : hint;
        if (has && fits && MossLora.check(BIN, loraSrc()).lokr) info += "\n" + L.s("LoKr: la primera imagen con cada intensidad prepara una copia de los pesos (unos 20 s y 5,7 GB, se guarda solo la última).", "LoKr: the first picture at each strength prepares a copy of the weights (about 20 s and 5.7 GB, only the last one is kept).");
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
    /** the same three buttons under every family's LoRA card: Library, Import, Civitai */
    void loraActions(LinearLayout row, Button library, Button importB) {
        Button civ = button("Civitai", CARD, ACC); civ.setTextSize(13); civ.setAllCaps(false);
        civ.setOnClickListener(v -> startActivityForResult(new Intent(this, CivitaiActivity.class), 14));
        Button[] bs = {library, importB, civ};
        for (int i = 0; i < bs.length; i++) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1); if (i > 0) lp.leftMargin = dp(6); row.addView(bs[i], lp); }
    }
    /** "1024×768 · seed 4242" next to the closed Image fold */
    void updImgSum() { if (imgFold == null || seed == null) return; String sd = seed.getText().toString().trim(); imgFold.sum.setText(resW + "×" + resH + "  ·  " + (sd.isEmpty() || sd.equals("-1") ? L.s("semilla aleatoria", "random seed") : L.f("semilla %s", "seed %s", sd))); }
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
    /** the weight the prompt gives this LoRA, or null when the prompt does not call it */
    String loraWeight(String n) {
        java.util.regex.Matcher m = loraTag(n).matcher(prompt.getText()); if (!m.find()) return null;
        return m.group(1) == null || m.group(1).trim().isEmpty() ? "1.0" : m.group(1).trim();
    }
    void loraAdd(String n, String w, String words) {
        List<String> cur = tokens(prompt.getText().toString()), low = new ArrayList<>();
        for (String t : cur) low.add(t.toLowerCase(java.util.Locale.ROOT));
        cur.add("<lora:" + n + ":" + w + ">");
        prompt.setText(android.text.TextUtils.join(", ", cur)); prompt.setSelection(prompt.getText().length());
    }
    // ---- "ChatGPT style": the Anima LoRA "Style > gpt-image-2" by AgileNumbers (Civitai model 2564225, version 2998634; commercial use and derivatives allowed).
    // One tap downloads it into the LoRA library if needed (pinned file, SHA-256 checked) and adds its trigger word and <lora:gpt-image-2:0.8>; a second tap takes them out.
    void loraRemove(String n, String words) {
        String p = loraTag(n).matcher(prompt.getText().toString()).replaceAll("");
        List<String> keep = tokens(p);
        prompt.setText(android.text.TextUtils.join(", ", keep)); prompt.setSelection(prompt.getText().length());
    }
    void loraSetWeight(String n, String w) {
        String p = loraTag(n).matcher(prompt.getText().toString()).replaceAll(java.util.regex.Matcher.quoteReplacement("<lora:" + n + ":" + w + ">"));
        prompt.setText(p); prompt.setSelection(prompt.getText().length());
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



    // ---- tag palette and the (tag:weight) buttons ----
    // ---- sampling fields under the prompt ----
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

    /** face_yolov8n (Bingsu/adetailer) converted for sd.cpp, hosted with the V73 packs; downloaded on first use unless the user picked another detector */
    // ---- Anima "edit a photo" (img2img / inpainting), laid out like the Qwen and Mage photo box ----

    /** anima.sh environment for the extras that are on */
    boolean memOk, hotStop;   // the memory warning was answered / a batch stopped because the phone got too hot
    /** automatic cool mode: the NPU runs at full clock while the phone is cool and switches to the lower clock from 40 °C battery (43 °C while charging)
     *  or a thermal headroom of 0.75, back to full speed 2 °C lower. The NPU processes read the file anima/cool before every step. */
    // presets: named snapshots of the sampling fields, the negative prompt, the quality tags and the extras (per model family)

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
        sampBox.setVisibility(!isMoss() && !isQwen() && !isMage() && !model.isEmpty() ? View.VISIBLE : View.GONE); negBox.setVisibility(sampBox.getVisibility());
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


    void openModels() { if (busy) { Toast.makeText(this, L.s("Дождитесь окончания генерации", "Wait for the generation to finish"), Toast.LENGTH_SHORT).show(); return; } startActivity(new Intent(this, ModelsActivity.class)); }






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


    void setRes(int w, int h) { setRes0(w, h); updImgSum(); }
    void setRes0(int w, int h) {
        resW = w; resH = h;
        boolean preset = false;
        for (int[] z : sizeList()) if (z[0] == w && z[1] == h) preset = true;
        if (sizeText != null) sizeText.setText(sizeLabel(w, h) + (preset && !customOpen ? "" : "  ·  " + L.s("Свой", "Custom")));
        if (customRow != null) { customRow.setVisibility(customOpen || !preset ? View.VISIBLE : View.GONE); if (!wEdit.getText().toString().equals(String.valueOf(w)) || !hEdit.getText().toString().equals(String.valueOf(h))) { wEdit.setText(String.valueOf(w)); hEdit.setText(String.valueOf(h)); } }
        if (chipName != null) chipName.setText(model.isEmpty() ? modelName() : shownModel(model));
        if (genBtn != null && !busy) genBtn.setText(genLabel());
    }

    /** seconds one picture takes with the current model and size: the last measured run if there is one, else the catalog speed at 512x768 scaled by the patch count */
    long estSec() {
        int learned = prefs().getInt(timeKey(), 0); if (learned > 0) return learned;
        double sm = isMoss() || isQwen() || isMage() || Settings.defaultSampling(this) ? 1 : Settings.calls(this) / 2.6;
        if (Moss.isRs(model) && prefs().getString("rs_mode", "4").equals("8") && Moss.installed8(getFilesDir()) && !loraOn()) sm = 1.65;
        return Math.max(3, Math.round(store.speed(model) * sm * ((resW / 16) * (resH / 16)) / 1536.0 * ((isQwen() || isMage()) && editBm != null ? 1.3 : 1)));
    }
    /** key of the measured time per model, size, edit / text-to-image and (Anima) step count */
    String timeKey() { return "t_" + model + "_" + resW + "x" + resH + (editBm != null && (isQwen() || isMage()) ? "_e" : "") + (isMoss() || isQwen() || isMage() ? "" : "_" + Settings.calls(this)); }
    /** rough time to a picture: the catalog speed at 512x768 scaled by the patch count, times the number of pictures */
    String genLabel() {
        if (model.isEmpty()) return L.s("Скачать модель", "Get a model");
        long est = estSec() * count;
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


    void setModelUi(boolean isLoaded, boolean working, String msg) {
        ui.post(() -> {
            int c = working ? WARN : isLoaded ? OK : DIM;
            chipDot.setTextColor(c);
            if (loraBox != null) { loraBox.setVisibility(model.isEmpty() || isQwen() || isMage() ? View.GONE : View.VISIBLE); refreshLora(); }
            if (gptStyleBtn != null) gptStyleBtn.setVisibility(isMoss() ? View.GONE : View.VISIBLE);   // the gpt-image-2 style LoRA is for Anima
            if (qwenLoraBox != null) { qwenLoraBox.setVisibility(isQwen() ? View.VISIBLE : View.GONE); refreshQwenLora(); }
            if (editBox != null) editBox.setVisibility(isQwen() || isMage() ? View.VISIBLE : View.GONE);
            if (editOpts != null) refreshEdit();   // the family decides the mask, the options and the extra reference pictures
            refreshExtras();
            refreshSampling();
            state.setText(working ? msg : isQwen() ? L.s("Qwen-Image на NPU · веса с накопителя", "Qwen-Image on the NPU · weights streamed from storage") : isMage() ? L.s("Mage-Flow en NPU · pesos desde almacenamiento", "Mage-Flow on the NPU · weights streamed from storage") : isMoss() ? L.s("MOSS-Image на NPU", "MOSS-Image on the NPU") : isLoaded ? L.s("В NPU · готово", "On the NPU · ready")
                : model.isEmpty() ? L.s("Нет моделей: скачайте одну кнопкой ниже", "No model yet: get one with the button below")
                : L.s("Выгружена · загрузится при генерации", "Unloaded · loads when you generate"));
            loadBtn.setText(isLoaded ? L.s("Liberar modelo", "Free model") : L.s("Cargar modelo", "Load model"));
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
        prefs().edit().putInt("w@" + model, resW).putInt("h@" + model, resH).apply();   // the size is remembered per model
        model = m;
        prefs().edit().putString("model", model).apply();   // the sampling fields, the negative prompt and the quality prefix are kept per model
        SharedPreferences pm0 = prefs(); boolean known = pm0.contains("w@" + m);
        setRes(known ? pm0.getInt("w@" + m, resW) : resW, known ? pm0.getInt("h@" + m, resH) : resH);
        sampFill = false; refreshSampling();   // show this model's values
        if (loaded) {   // the loaded server holds the other model's weights
            setModelUi(true, true, L.s("Меняю модель…", "Switching model…"));
            ex.submit(() -> { su("sh " + D + "/anima.sh stop < /dev/null >/dev/null 2>&1", false); loaded = false; setModelUi(false, false, ""); });
        } else setModelUi(false, false, "");
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

    /** templates for the negative prompt: save the current one, pick a saved or a recently used one (from the History), remove saved ones */
    void negTemplates() {
        final LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(14), dp(14), dp(10));
        final AlertDialog[] dlg = new AlertDialog[1]; final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            box.removeAllViews();
            box.addView(text(L.s("Plantillas del prompt negativo", "Negative prompt templates"), 18, TXT, true));
            Button save = button(L.s("Guardar el negativo actual", "Save the current negative"), ACC, ON_ACC); save.setTextSize(14);
            save.setOnClickListener(v -> {
                String t = negEdit.getText().toString().trim();
                if (t.isEmpty()) { Toast.makeText(this, L.s("El prompt negativo está vacío", "The negative prompt is empty"), Toast.LENGTH_SHORT).show(); return; }
                List<String> l = savedNegs(); l.remove(t); l.add(0, t); while (l.size() > 100) l.remove(l.size() - 1);
                prefs().edit().putString("saved_neg", new JSONArray(l).toString()).apply(); fill[0].run();
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(44)); sl.topMargin = dp(8); box.addView(save, sl);
            List<String> sv = savedNegs();
            if (!sv.isEmpty()) { box.addView(sect(L.s("Сохранённые", "Saved"))); for (String t : sv) box.addView(negRow(t, true, dlg, fill)); }
            List<String> rc = new ArrayList<>();
            for (File f : Gallery.list(this, Gallery.ALL)) { if (rc.size() >= 30) break; String n = Gallery.meta(f).optString("negative").trim(); if (!n.isEmpty() && !rc.contains(n) && !sv.contains(n)) rc.add(n); }
            if (!rc.isEmpty()) { box.addView(sect(L.s("Недавние", "Recent"))); for (String t : rc) box.addView(negRow(t, false, dlg, fill)); }
            if (sv.isEmpty() && rc.isEmpty()) { TextView e = text(L.s("Aún no hay negativos guardados ni usados.", "No saved or used negatives yet."), 13, DIM, false); e.setPadding(dp(2), dp(10), 0, 0); box.addView(e); }
        };
        fill[0].run();
        ScrollView sv = new ScrollView(this); sv.addView(box);
        dlg[0] = builder().setView(sv).setNegativeButton(L.s("Закрыть", "Close"), null).show();
    }
    List<String> savedNegs() {
        List<String> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs().getString("saved_neg", "[]")); for (int i = 0; i < a.length(); i++) l.add(a.getString(i)); } catch (Exception ignored) { }
        return l;
    }
    View negRow(String t, boolean saved, AlertDialog[] dlg, Runnable[] fill) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setBackground(bg(FIELD, 12)); r.setPadding(dp(12), dp(10), dp(8), dp(10));
        TextView tv = text(t, 13, TXT, false); tv.setMaxLines(2); tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        TextView act = text(saved ? "✕" : "☆", saved ? 16 : 18, DIM, false); act.setPadding(dp(12), dp(4), dp(8), dp(4));
        act.setOnClickListener(v -> { List<String> l = savedNegs(); if (saved) l.remove(t); else { l.remove(t); l.add(0, t); } prefs().edit().putString("saved_neg", new JSONArray(l).toString()).apply(); fill[0].run(); });
        r.addView(act);
        r.setOnClickListener(v -> { negEdit.setText(t); dlg[0].dismiss(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4); r.setLayoutParams(lp);
        return r;
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
        final int n = gk != 0 ? 4 : countOv > 0 ? countOv : count, w = resW, h = resH; countOv = 0;
        final long gridSeed = randomSeed ? new Random().nextInt(1 << 30) : baseSeed;
        final List<Bitmap> gridBms = new ArrayList<>(); final List<String> gridLabels = new ArrayList<>();
        batchFiles.clear(); batchGridFile = null; extraGrids.clear(); batchDisplayIndex = 0; lastFile = null; pendingCompare = null; refreshGeneratedEditAction();
        busy = true; Upscaler.npuBusy = true; cancelled = false; curIdx = 0; curN = n; batchAdjustable = gk == 0;
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
                picStart = System.currentTimeMillis(); picExpect = estSec();
                final File f = runOne(wildcards(pr, seedS), seedS, w, h, t0);
                if (f != null && !cancelled && gk == 0 && varInit == null) { int took = (int) Math.round((System.currentTimeMillis() - picStart) / 1000.0), old = prefs().getInt(timeKey(), 0); prefs().edit().putInt(timeKey(), old > 0 ? (old + 2 * took) / 3 : took).apply(); }   // learn the real time (weighted to the last runs)
                cfgOv = null; stepsOv = 0; cutOv = 0;
                if (f == null) break;
                made++;
                madeFiles.add(f);
                final Bitmap bm = BitmapFactory.decodeFile(f.getPath());
                if (gk != 0 && bm != null) { gridBms.add(bm); gridLabels.add(gk == 1 ? "seed " + seedS : gk == 2 ? "CFG " + GRID_CFG[i] : gk == 4 ? L.f("CFG на %d %% шагов", "CFG on %d %% of the steps", Settings.CFG_CUTS[i]) : L.f("%d шагов", "%d steps", GRID_STEPS[i])); }
                lastBm = bm;
                final List<File> fileSnapshot = new ArrayList<>(madeFiles);
                ui.post(() -> {
                    batchFiles = fileSnapshot; batchGridFile = null; extraGrids.clear(); batchDisplayIndex = batchFiles.size() - 1;
                    lastFile = f; img.setImageBitmap(bm); showImg(true); placeholder.setVisibility(View.GONE);
                    refreshGeneratedEditAction();
                    setFavIcon(false);
                    for (ImageButton x : new ImageButton[]{againBtn, favBtn, dlBtn, upBtn, fixBtn, shareBtn}) x.setVisibility(View.VISIBLE);
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
                final List<File> gs = saveBatchGrids(madeFiles, pr, w, h);
                final File gf = gs.isEmpty() ? null : gs.get(0);
                final Bitmap gb = gf != null ? BitmapFactory.decodeFile(gf.getPath()) : null;
                if (gb != null) { lastBm = gb; ui.post(() -> { batchGridFile = gf; extraGrids.clear(); extraGrids.addAll(gs.subList(1, gs.size())); batchDisplayIndex = batchFiles.size(); lastFile = gf; img.setImageBitmap(gb); showImg(true); placeholder.setVisibility(View.GONE); saved.setText(gs.size() > 1 ? L.f("Cuadrícula 1/%d · %d imágenes · ‹ › para ver más", "Grid 1/%d · %d pictures · ‹ › for more", gs.size(), madeFiles.size()) : L.f("Cuadrícula · %d imágenes", "Grid · %d pictures", madeFiles.size())); refreshGeneratedEditAction(); }); }
            }
            picStart = 0; varInit = null;
            final double sec = (System.currentTimeMillis() - t0) / 1000.0;
            final boolean wasCancelled = cancelled, okAny = made > 0, hot = hotStop || tooHot();
            hotStop = false;
            final int total = made;
            if (wasCancelled) loaded = false;   // Stop shut the server down
            synchronized (batchLock) { busy = false; Upscaler.npuBusy = false; cancelled = false; batchAdjustable = false; }
            app.stopService(new Intent(app, DownloadService.class));
            final Bitmap nb = lastBm;
            ui.post(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                bar.setVisibility(View.GONE);
                if (wasCancelled) status.setText(L.s("Остановлено", "Stopped"));
                else if (!okAny) status.setText(L.f("Ошибка генерации (лог: %s)", "Generation failed (log: %s)", isMoss() ? "files/moss/moss.log" : isQwen() ? "files/qwen/qwen.log" : isMage() ? "files/mage/mage.log" : "files/anima/animad.log"));
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
    int cutNow() { return cutOv > 0 ? cutOv : Settings.cfgCut(this); }
    String cfgNow() { return cfgOv != null ? cfgOv : Settings.cfg(this); }
    boolean cfgOnNow() { try { return Float.parseFloat(cfgNow()) > 1.001f; } catch (NumberFormatException e) { return false; } }

    void showBatchPage(int page) {
        List<File> pages = new ArrayList<>(batchFiles);
        if (batchGridFile != null && batchGridFile.isFile()) pages.add(batchGridFile);
        for (File g : extraGrids) if (g.isFile()) pages.add(g);
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









    /** pct is the progress of the current picture; with several pictures the bar and the text cover the whole batch */
    File previewFile() { return new File(D, "preview.png"); }
}
