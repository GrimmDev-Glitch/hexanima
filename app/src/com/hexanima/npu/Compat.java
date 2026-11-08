package com.hexanima.npu;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/** Can this phone run the models? Hexagon V69 (Snapdragon 8 Gen 1 / 8+ Gen 1) and, experimentally, V73 (Snapdragon 8 Gen 2); V79 (Snapdragon 8 Elite) support is switched off, see V79_ENABLED. */
final class Compat {
    static String soc() { return Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE; }
    /** Hexagon generation of the NPU by SoC model ("" = unknown): the QNN contexts (text encoder, VAE) are compiled per generation */
    /** Snapdragon 8 Elite (V79) was withdrawn in 1.2.2: the HMX is silently unavailable after an interrupted run and large sizes come out as noise there (see PITFALLS.md); the code stays for a later port */
    static final boolean V79_ENABLED = false;
    static String dspArch() {
        String s = soc();
        if (s.equals("SM8475") || s.equals("SM8450")) return "v69";
        if (s.equals("SM8550")) return "v73";   /* the DiT kernels need their V73 build (libmega_skel_v73.so, picked by anima.sh): V73 stores HMX results through the convert unit */
        if (V79_ENABLED && s.equals("SM8750")) return "v79";
        return "";
    }
    static boolean chipOk() { return !dspArch().isEmpty(); }
    static String archLabel() { return "Hexagon " + dspArch().toUpperCase() + (dspArch().equals("v69") ? "" : L.s(", экспериментально", ", experimental")); }
    static boolean chipAllowed(Context c) { return chipOk() || c.getSharedPreferences("anima", 0).getBoolean("chip_override", false); }
    static void allowChip(Context c) { c.getSharedPreferences("anima", 0).edit().putBoolean("chip_override", true).apply(); }

    static double ramGb(Context c) { ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi); return mi.totalMem / 1073741824.0; }
    static double freeGb(Context c) { return c.getFilesDir().getUsableSpace() / 1e9; }

    /** [chip, ram, storage] each as {ok?"1":"0", text} */
    static List<String[]> report(Context c) {
        List<String[]> r = new ArrayList<>();
        r.add(new String[]{chipOk() ? "1" : "0", chipOk() ? L.f("Чип %s: подходит (%s)", "Chip %s: supported (%s)", soc(), archLabel()) : L.f("Чип %s: не поддерживается, нужен Snapdragon 8 Gen 1 / 8+ Gen 1 / 8 Gen 2", "Chip %s: not supported, a Snapdragon 8 Gen 1 / 8+ Gen 1 / 8 Gen 2 is required", soc())});
        double ram = ramGb(c);
        r.add(new String[]{ram >= 7.5 ? "1" : "0", ram >= 7.5 ? L.f("ОЗУ %.1f ГБ: достаточно", "RAM %.1f GB: enough", ram) : L.f("ОЗУ %.1f ГБ: мало, нужно от 8 ГБ", "RAM %.1f GB: low, 8 GB or more is needed", ram)});
        double fr = freeGb(c);
        r.add(new String[]{fr >= 6 ? "1" : "0", fr >= 6 ? L.f("Свободно %.1f ГБ: достаточно", "Free space %.1f GB: enough", fr) : L.f("Свободно %.1f ГБ: мало, модель занимает до 5,4 ГБ", "Free space %.1f GB: low, a model needs up to 5.4 GB", fr)});
        return r;
    }
}
