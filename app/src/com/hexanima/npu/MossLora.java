package com.hexanima.npu;

import java.io.*;
import java.util.*;

/** User MOSS adapters. A/B LoRAs are folded into turbo slots; LoKr is baked into a copy of the DiT blocks (no DSP kernel change). */
final class MossLora {
    static File dir(File home) { return new File(Moss.dir(home), "lora"); }
    static File civitaiDir(File home) { return new File(dir(home), "civitai"); }
    static File src(File home) { return new File(dir(home), "user.safetensors"); }

    /** Select a validated Civitai library entry without deleting the downloaded library; MOSS currently has one active adapter slot. */
    static void select(File home, File from) throws IOException {
        File root = dir(home); root.mkdirs(); File dst = src(home), tmp = new File(root, "user.safetensors.part");
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 18]; for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            out.getFD().sync();
        } catch (IOException e) { tmp.delete(); throw e; }
        if (dst.exists() && !dst.delete()) { tmp.delete(); throw new IOException("cannot replace selected MOSS LoRA"); }
        if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("cannot select MOSS LoRA"); }
        clearOverlays(home);
    }

    static void clearSelection(File home) {
        src(home).delete(); clearOverlays(home);
    }
    static void clearOverlays(File home) {
        File[] fs = dir(home).listFiles(); if (fs != null) for (File f : fs) if (f.getName().startsWith("ovl_")) deleteTree(f);
    }

    /** result of lorafold check: ok + facts, or an error code and its detail */
    static final class Check {
        boolean ok, lokr; String code = "", detail = ""; int layers, mods, rank; String alpha = "", warn = "";
        String message() {
            if (ok) {
                StringBuilder s = new StringBuilder(lokr
                    ? L.f("Compatible: LoKr 4×4, %d proyecciones en %d de 42 bloques", "Compatible: 4×4 LoKr, %d projections in %d of 42 blocks", mods, layers)
                    : L.f("Compatible: rango %d, %d de 42 bloques", "Compatible: rank %d, %d of 42 blocks", rank, layers));
                if (warn.contains("rank>64")) s.append("\n").append(L.s("Rango alto: al combinarla con el adaptador turbo podrían perderse algunos detalles finos", "High rank: some fine detail may be lost when it is merged with the turbo adapter"));
                if (warn.contains("text_encoder_ignored")) s.append("\n").append(L.s("La parte del codificador de texto no se aplica", "The text-encoder part is not applied"));
                if (warn.contains("unknown_keys")) s.append("\n").append(L.s("Se omiten algunos tensores adicionales", "Some extra tensors are skipped"));
                return s.toString();
            }
            switch (code) {
                case "LOKR": return L.s("Este LoKR se marcó como conversión con pérdida y no se puede activar. Selecciona el safetensors original.", "This LoKr was marked as a lossy conversion and cannot be enabled. Select the original safetensors file.");
                case "MIXED": return L.s("El archivo mezcla LoKr y LoRA A/B; sepáralos en archivos distintos.", "This file mixes LoKr and A/B LoRA tensors; split them into separate files.");
                case "PROJECTION": return L.s("Esta proyección LoKr todavía no está soportada en V73.", "This LoKr projection is not supported on V73 yet.");
                case "LOHA": return L.s("Este archivo usa LoHa, un formato que el cargador MOSS todavía no admite.", "This file uses LoHa, which the MOSS loader does not support yet.");
                case "OTHERMODEL": return L.s("Esta LoRA es para otro modelo (FLUX, SDXL o similar), no para MOSS-Image.", "This LoRA is for another model (FLUX, SDXL or similar). It does not fit MOSS-Image.");
                case "NOTLORA": return L.s("El archivo no parece una LoRA de MOSS-Image: faltan las capas esperadas (layers.N.proj.*).", "This file does not look like a MOSS-Image LoRA: the expected layers (layers.N.proj.*) are missing.");
                case "TEXTONLY": return L.s("El archivo solo contiene pesos LoRA del codificador de texto; todavía no son compatibles.", "The file only has text-encoder LoRA weights. Those are not supported yet.");
                case "SHAPE": return L.f("Las dimensiones de las capas no coinciden con MOSS-Image (%s). Puede ser una LoRA de otra versión del modelo.", "Layer sizes do not match MOSS-Image (%s). It is probably a LoRA for another version of the model.", detail);
                case "RANK": return L.f("El rango es demasiado grande (%s). Se admite de 1 a 128.", "The rank is too large (%s). Supported: 1 to 128.", detail);
                case "LAYER": return L.f("La LoRA contiene bloques que MOSS-Image no tiene (%s).", "The LoRA has blocks that MOSS-Image does not have (%s).", detail);
                case "DTYPE": return L.s("Formato de pesos no compatible. Se requiere F32, F16 o BF16.", "Unsupported weight format: F32, F16 or BF16 are needed.");
                case "FILE": return L.f("No se pudo leer el archivo (%s). Se necesita un .safetensors.", "Cannot read the file (%s). A .safetensors file is needed.", detail);
                default: return L.f("Falló la validación: %s %s", "Check failed: %s %s", code, detail);
            }
        }
    }

    static Check parse(String line) {
        Check c = new Check(); String[] a = line.trim().split("\t");
        if (a.length > 0 && a[0].equals("OK")) {
            c.ok = true;
            for (String f : a) {
                if (f.equals("kind=lokr")) c.lokr = true;
                else if (f.startsWith("layers=")) c.layers = Integer.parseInt(f.substring(7)); else if (f.startsWith("mods=")) c.mods = Integer.parseInt(f.substring(5)); else if (f.startsWith("rank=")) c.rank = Integer.parseInt(f.substring(5));
                else if (f.startsWith("alpha=")) c.alpha = f.substring(6); else if (f.startsWith("warn=")) c.warn = f.substring(5);
            }
        } else { c.code = a.length > 1 ? a[1] : "?"; c.detail = a.length > 2 ? a[2] : ""; }
        return c;
    }

    static String run(String bin, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(); cmd.add(bin + "/liblorafold.so"); cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) { for (String l; (l = r.readLine()) != null; ) sb.append(l).append('\n'); }
        try { p.waitFor(); } catch (InterruptedException ignored) { }
        return sb.toString();
    }

    static Check check(String bin, File f) {
        try {
            String out = run(bin, "check", f.getPath()); String last = "";
            for (String l : out.split("\n")) if (l.startsWith("OK") || l.startsWith("ERR")) last = l;
            return last.isEmpty() ? parse("ERR\tFILE\t" + out.trim()) : parse(last);
        } catch (IOException e) { return parse("ERR\tFILE\t" + e.getMessage()); }
    }

    interface Progress { void at(int done, int total); }
    /** folded patches for the picked LoRA at this strength; reuses the cache, null on failure/cancel (msg[0] = reason) */
    static File ensure(File home, String bin, String id, float strength, Progress pr, java.util.function.BooleanSupplier cancelled, String[] msg) {
        Check check = check(bin, src(home)); if (!check.ok) { msg[0] = check.message(); return null; }
        File root = dir(home), out = new File(root, "ovl_" + (check.lokr ? "lokr_" : "") + id + "_" + Math.round(strength * 100));
        if (new File(out, ".ok").exists()) return out;
        File[] old = root.listFiles(); if (old != null) for (File f : old) if (f.getName().startsWith("ovl_")) deleteTree(f);
        out.mkdirs();
        try {
            Process p = new ProcessBuilder(bin + "/liblorafold.so", check.lokr ? "fold-lokr" : "fold", Moss.dir(home).getPath(), src(home).getPath(), String.valueOf(strength), out.getPath()).redirectErrorStream(true).start();
            String last = "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String l; (l = r.readLine()) != null; ) {
                    if (cancelled.getAsBoolean()) { p.destroy(); deleteTree(out); return null; }
                    if (l.startsWith("progress ")) { String[] a = l.split(" "); pr.at(Integer.parseInt(a[1]), Integer.parseInt(a[2])); } else last = l;
                }
            }
            if (p.waitFor() != 0 || !last.startsWith("OK")) { msg[0] = last; deleteTree(out); return null; }
            new File(out, ".ok").createNewFile(); return out;
        } catch (Exception e) { msg[0] = String.valueOf(e); deleteTree(out); return null; }
    }

    /** LoKr: bakes W + strength*kron(w1,w2) into the q8 DiT blocks of `model` (base or a variant view) and returns a model folder
     *  (dit/ baked, everything else linked) that moss.sh loads instead; one cache is kept (about 5.7 GB), rebuilt on a new LoKr/strength/model */
    static File ensureLokr(File home, String bin, File model, String key, String id, float strength, Progress pr, java.util.function.BooleanSupplier cancelled, String[] msg) {
        File root = new File(Moss.dir(home), "lokr"), out = new File(root, key + "_" + id + "_" + Math.round(strength * 100));
        if (new File(out, ".ok").exists()) return out;
        File[] old = root.listFiles(); if (old != null) for (File f : old) deleteTree(f);
        root.mkdirs();
        if (root.getUsableSpace() < 6_500_000_000L) { msg[0] = L.s("Hacen falta unos 6 GB libres para preparar el LoKr", "About 6 GB of free space are needed to prepare the LoKr"); return null; }
        try {
            Process p = new ProcessBuilder(bin + "/liblorafold.so", "bake-lokr", model.getPath(), src(home).getPath(), String.valueOf(strength), out.getPath()).redirectErrorStream(true).start();
            String last = "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String l; (l = r.readLine()) != null; ) {
                    if (cancelled.getAsBoolean()) { p.destroy(); deleteTree(out); return null; }
                    if (l.startsWith("progress ")) { String[] a = l.split(" "); pr.at(Integer.parseInt(a[1]), Integer.parseInt(a[2])); } else last = l;
                }
            }
            if (p.waitFor() != 0 || !last.startsWith("OK")) { msg[0] = last; deleteTree(out); return null; }
            new File(out, ".ok").createNewFile(); return out;
        } catch (Exception e) { msg[0] = String.valueOf(e); deleteTree(out); return null; }
    }
    static void deleteLokrCache(File home) { deleteTree(new File(Moss.dir(home), "lokr")); }

    /** removes a folder; a symbolic link is removed itself, never what it points to (variant views and the LoKr cache link to the real model files) */
    static void deleteTree(File f) { if (java.nio.file.Files.isSymbolicLink(f.toPath())) { f.delete(); return; } File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); f.delete(); }
}
