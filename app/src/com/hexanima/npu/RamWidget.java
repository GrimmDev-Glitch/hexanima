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
 *  Unload model (anima.sh stop, the same as Unload in the app), Free RAM (the model, then ActivityManager.killBackgroundProcesses for every other app:
 *  Android only stops processes that are safe to stop, never the visible one; the freed MB are shown) and refresh. (Xiaomi exposes no public action
 *  for its own memory cleaner: its "garbage cleanup" one cleans storage.) */
public class RamWidget extends AppWidgetProvider {
    static final String UNLOAD = "com.hexanima.npu.widget.UNLOAD", REFRESH = "com.hexanima.npu.widget.REFRESH", FREE = "com.hexanima.npu.widget.FREE";
    static String last = "";   // result line of the last Free RAM

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) update(c, m, id); }

    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (UNLOAD.equals(i.getAction())) {
            final PendingResult pr = goAsync();
            new Thread(() -> { try { last = ""; unload(c); } finally { updateAll(c); pr.finish(); } }).start();
        } else if (FREE.equals(i.getAction())) {
            final PendingResult pr = goAsync();
            new Thread(() -> { try { freeRam(c); } finally { updateAll(c); pr.finish(); } }).start();
        } else if (REFRESH.equals(i.getAction())) { last = ""; updateAll(c); }
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

    static long avail(Context c) { ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi); return mi.availMem; }
    /** the model, then the background processes of the other apps (not the launcher, keyboard, or anything Android considers in use) */
    static void freeRam(Context c) {
        long before = avail(c);
        if (modelLoaded()) unload(c);
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        android.content.pm.PackageManager pm = c.getPackageManager();
        int n = 0;
        for (android.content.pm.ApplicationInfo a : pm.getInstalledApplications(0)) {
            if (a.packageName.equals(c.getPackageName())) continue;
            try { am.killBackgroundProcesses(a.packageName); n++; } catch (Exception ignored) { }
        }
        try { Thread.sleep(1500); } catch (InterruptedException ignored) { }   // the system needs a moment to reclaim the memory
        long freed = Math.max(0, avail(c) - before);
        L.init(c);
        last = L.f("Освобождено ~%d МБ", "Freed ~%d MB", freed / 1048576);
    }

    static PendingIntent self(Context c, String action, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, RamWidget.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
        v.setTextViewText(r.getIdentifier("w_model", "id", pkg), (loaded ? L.s("Модель HexAnima в памяти (~4–5 ГБ)", "HexAnima model in memory (~4–5 GB)") : L.s("Модель HexAnima выгружена", "HexAnima model unloaded")) + (last.isEmpty() ? "" : "  ·  " + last));
        int ub = r.getIdentifier("w_unload", "id", pkg), cb = r.getIdentifier("w_clean", "id", pkg), rb = r.getIdentifier("w_refresh", "id", pkg);
        v.setTextViewText(ub, L.s("Выгрузить модель", "Unload model")); v.setTextViewText(cb, L.s("Освободить ОЗУ", "Free RAM"));
        v.setOnClickPendingIntent(ub, self(c, UNLOAD, 1));
        v.setOnClickPendingIntent(rb, self(c, REFRESH, 2));
        v.setOnClickPendingIntent(cb, self(c, FREE, 3));
        m.updateAppWidget(id, v);
    }
}
