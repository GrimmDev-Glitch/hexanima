package com.hexanima.npu;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.io.File;
import java.io.FileInputStream;

/** Home-screen widget: free RAM, whether HexAnima's NPU model is loaded (its 4-5 GB are by far the biggest thing an app can free on this phone),
 *  a button that unloads it (anima.sh stop, the same as Unload in the app), the system cleaner (Xiaomi's own, which has the system permissions a
 *  normal app lacks to stop other apps) and refresh. An app without root cannot free other apps' memory itself, so the widget does not pretend to. */
public class RamWidget extends AppWidgetProvider {
    static final String UNLOAD = "com.hexanima.npu.widget.UNLOAD", REFRESH = "com.hexanima.npu.widget.REFRESH";

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) update(c, m, id); }

    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (UNLOAD.equals(i.getAction())) {
            final PendingResult pr = goAsync();
            new Thread(() -> { try { unload(c); } finally { updateAll(c); pr.finish(); } }).start();
        } else if (REFRESH.equals(i.getAction())) updateAll(c);
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, RamWidget.class))) update(c, m, id);
    }

    /** HexAnima's own server processes (same uid, so their /proc entries are readable) */
    static boolean modelLoaded() {
        File[] ps = new File("/proc").listFiles(); if (ps == null) return false;
        for (File p : ps) {
            if (!p.getName().matches("\\d+")) continue;
            try (FileInputStream in = new FileInputStream(new File(p, "cmdline"))) {
                byte[] b = new byte[256]; int n = in.read(b); if (n <= 0) continue;
                String s = new String(b, 0, n);
                if (s.contains("libanimad.so") || s.contains("libmegarun.so")) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    static void unload(Context c) {
        try {
            File a = new File(c.getFilesDir(), "anima");
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", new File(a, "anima.sh").getPath(), "stop").redirectErrorStream(true);
            pb.environment().put("A", a.getPath()); pb.environment().put("S", new File(c.getFilesDir(), "sd").getPath()); pb.environment().put("BIN", c.getApplicationInfo().nativeLibraryDir);
            pb.directory(a); Process p = pb.start(); p.getInputStream().close(); p.waitFor();
        } catch (Exception ignored) { }
    }

    static PendingIntent self(Context c, String action, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, RamWidget.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    /** Xiaomi's cleaner (HyperOS / MIUI), else the Security app, else HexAnima itself */
    static Intent cleaner(Context c) {
        Intent i = new Intent("miui.intent.action.GARBAGE_CLEANUP");
        if (i.resolveActivity(c.getPackageManager()) != null) return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent s = c.getPackageManager().getLaunchIntentForPackage("com.miui.securitycenter");
        return (s != null ? s : new Intent(c, Main.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static void update(Context c, AppWidgetManager m, int id) {
        L.init(c);
        String pkg = c.getPackageName(); android.content.res.Resources r = c.getResources();
        RemoteViews v = new RemoteViews(pkg, r.getIdentifier("widget_ram", "layout", pkg));
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        double free = mi.availMem / 1073741824.0, total = mi.totalMem / 1073741824.0;
        boolean loaded = modelLoaded();
        v.setTextViewText(r.getIdentifier("w_ram", "id", pkg), L.f("ОЗУ свободно: %.1f из %.1f ГБ", "RAM free: %.1f of %.1f GB", free, total));
        v.setProgressBar(r.getIdentifier("w_bar", "id", pkg), 100, (int) Math.round(100 * (1 - free / total)), false);
        v.setTextViewText(r.getIdentifier("w_model", "id", pkg), loaded ? L.s("Модель HexAnima в памяти (~4–5 ГБ)", "HexAnima model in memory (~4–5 GB)") : L.s("Модель HexAnima выгружена", "HexAnima model unloaded"));
        int ub = r.getIdentifier("w_unload", "id", pkg), cb = r.getIdentifier("w_clean", "id", pkg), rb = r.getIdentifier("w_refresh", "id", pkg);
        v.setTextViewText(ub, L.s("Выгрузить модель", "Unload model")); v.setTextViewText(cb, L.s("Очистка", "Cleaner"));
        v.setOnClickPendingIntent(ub, self(c, UNLOAD, 1));
        v.setOnClickPendingIntent(rb, self(c, REFRESH, 2));
        v.setOnClickPendingIntent(cb, PendingIntent.getActivity(c, 3, cleaner(c), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(id, v);
    }
}
