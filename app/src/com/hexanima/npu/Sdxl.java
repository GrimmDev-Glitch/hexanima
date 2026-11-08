package com.hexanima.npu;

import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** SDXL-family checkpoints (Illustrious, NoobAI, Pony, SDXL 1.0) on the CPU with the bundled sd.cpp (libsdcli.so); no NPU kernels exist for the SDXL UNet.
 *  files/sdxl/models: checkpoints from Civitai (<name>.safetensors + <name>.json); on first use a checkpoint is converted on the phone to <name>.q8.gguf
 *  (q8_0, ~4.2 GB, ~20 s) and the .safetensors is deleted. files/sdxl/lora: SDXL LoRAs, called from the prompt as <lora:name:weight> (sd.cpp reads them).
 *  files/sdxl/taesdxl.safetensors: the tiny decoder (the full SDXL VAE takes minutes on the CPU and shows fp16 artifacts). Ids: "sdxl:<name>". */
final class Sdxl {
    static final String PREFIX = "sdxl:";
    static final String TAESDXL_URL = "https://huggingface.co/madebyollin/taesdxl/resolve/main/diffusion_pytorch_model.safetensors";
    static final int THREADS = 4;   // the 4 big cores; more threads land on the small cores and are slower
    /** SDXL sizes (about 1 Mpx is what the models are trained for; smaller ones are faster) */
    static final int[][] PRESETS = {{832, 1216}, {896, 1152}, {768, 1344}, {1024, 1024}, {1216, 832}, {1152, 896}, {768, 1152}, {768, 768}, {640, 960}};

    static boolean is(String id) { return id != null && id.startsWith(PREFIX); }
    static String base(String id) { return id.substring(PREFIX.length()); }
    static File dir(File home) { return new File(home, "sdxl"); }
    static File models(File home) { return Civitai.sdxlDir(home, "models"); }
    static File loras(File home) { return Civitai.sdxlDir(home, "lora"); }
    static File taesd(File home) { return new File(dir(home), "taesdxl.safetensors"); }
    static File gguf(File home, String id) { return new File(models(home), base(id) + ".q8.gguf"); }
    static File src(File home, String id) { return new File(models(home), base(id) + ".safetensors"); }

    static List<String> installed(File home) {
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER); File[] fs = models(home).listFiles();
        if (fs != null) for (File f : fs) { String n = f.getName(); if (n.endsWith(".q8.gguf")) s.add(n.substring(0, n.length() - 8)); else if (n.endsWith(".safetensors")) s.add(n.substring(0, n.length() - 12)); }
        List<String> out = new ArrayList<>(); for (String n : s) out.add(PREFIX + n); return out;
    }
    static String name(File home, String id) {
        JSONObject m = Civitai.meta(src(home, id)); return m != null ? m.optString("name") + " · " + m.optString("version") : base(id);
    }
    static boolean ready(File home, String id) { return gguf(home, id).exists(); }
    static void delete(File home, String id) { gguf(home, id).delete(); src(home, id).delete(); Civitai.metaFile(src(home, id)).delete(); }

    /** seconds per sampler call at 768x768 measured on a Poco F6 Pro (4 big cores, cold); scaled by pixels */
    static double secPerCall(int w, int h) { return 5.8 * (w * (double) h) / (768 * 768); }
}
