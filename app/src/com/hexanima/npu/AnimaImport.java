package com.hexanima.npu;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Anima checkpoints from Civitai (or a picked .safetensors) become installed models on the phone: libanimaconv.so (phone/animaconv.cpp) writes small.bin and
 *  the blkNN.bin blocks (fp16 for the 28-block 2B models, q8 for the 40-block 2.9B ones, the bytes tools/make_pack.py makes on a PC), the sd.cpp stub is the
 *  header of the catalog model with the same architecture (MiaoMiao 2B / 2.9B), and the source file is deleted. Model id "cv<number>". */
final class AnimaImport {
    interface Progress { void at(int done, int total, String what); }

    static File importDir(File home) { return new File(new File(home, "anima"), "import"); }

    /** converts src into model id (name shown in the picker); returns null on success, else the reason. The source is deleted on success. */
    static String convert(File home, String bin, String base, File src, String id, String name, Progress pg, AtomicBoolean cancel) {
        Models store = new Models(home);
        File dir = store.modelDir(id); dir.mkdirs(); store.doneMark(dir).delete();
        long need = src.length() * 9 / 10;
        if (home.getUsableSpace() < need) return L.f("Мало места: нужно ещё около %.1f ГБ", "Not enough space: about %.1f GB more is needed", need / 1e9);
        int blocks = 0;
        try {
            Process p = new ProcessBuilder(bin + "/libanimaconv.so", src.getPath(), dir.getPath(), "auto").redirectErrorStream(true).start();
            StringBuilder log = new StringBuilder(); String err = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String l; (l = r.readLine()) != null; ) {
                    log.append(l).append('\n');
                    if (cancel != null && cancel.get()) { p.destroy(); break; }
                    if (l.startsWith("blocks ")) blocks = Integer.parseInt(l.split(" ")[1]);
                    else if (l.startsWith("blk ")) { String[] a = l.substring(4).split("/"); if (pg != null) pg.at(Integer.parseInt(a[0]), Integer.parseInt(a[1]), L.s("Конвертация блоков", "Converting blocks")); }
                    else if (l.startsWith("error")) err = l.substring(5).trim();
                }
            }
            p.waitFor();
            if (cancel != null && cancel.get()) { Models.deleteTree(dir); return L.s("Отменено", "Cancelled"); }
            if (err != null || p.exitValue() != 0) { Models.deleteTree(dir); return L.f("Не удалось конвертировать: %s", "Conversion failed: %s", err != null ? err : log.toString().trim()); }
            if (blocks != 28 && blocks != 40) { Models.deleteTree(dir); return L.f("Неизвестная архитектура (%d блоков): поддерживаются Anima 2B (28) и 2.9B (40)", "Unknown architecture (%d blocks): Anima 2B (28) and 2.9B (40) are supported", blocks); }
            // the sd.cpp stub: header of the catalog model with the same architecture
            String ref = blocks == 40 ? "harem29" : "harem2b";
            if (pg != null) pg.at(blocks, blocks, L.s("Заголовок модели…", "Model header…"));
            JSONObject m = new JSONObject(Models.get(base + "models/" + ref + "/manifest.json"));
            JSONObject st = m.getJSONObject("stub");
            File hdr = new File(dir, "hdr.bin");
            Models.fetch(base + "models/" + ref + "/" + st.getString("hdr"), hdr, -1, null, null, new AtomicBoolean());
            Models.stub(hdr, new File(store.sd, id + ".safetensors"), st.getLong("size"));
            try (FileWriter w = new FileWriter(new File(dir, "meta.json"))) { w.write(new JSONObject().put("id", id).put("name", name).put("speed", blocks == 40 ? 12 : 7).put("imported", true).toString()); }
            store.doneMark(dir).createNewFile();
            src.delete(); Civitai.metaFile(src).delete();
            return null;
        } catch (Exception e) { Models.deleteTree(dir); return e.toString(); }
    }
}
