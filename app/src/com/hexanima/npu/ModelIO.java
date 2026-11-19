package com.hexanima.npu;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** SAF backup/restore for Anima packs. V69 and V73 share weights but not their common QNN files, so the archive is bound to its Hexagon architecture. */
final class ModelIO {
    static final String MANIFEST = "hexanima-model.json";
    private static final long MIN_FREE = 300_000_000L;

    static boolean supported(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]+") && !Moss.is(id) && !Qwen.is(id) && !Mage.is(id) && !Ltx.is(id);
    }

    private static boolean skip(File f) {
        String n = f.getName();
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return true;
        if (f.isDirectory()) return n.equals("dsp") || n.startsWith("cache") || n.equals("lcache") || n.equals("tmp");
        return n.equals(".installed") || n.endsWith(".part") || n.endsWith(".log");
    }

    private static void walk(File home, File f, List<String> out) throws IOException {
        if (skip(f)) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles(); if (children == null) throw new IOException("Cannot read " + f.getName());
            Arrays.sort(children); for (File child : children) walk(home, child, out);
        } else out.add(relative(home, f));
    }

    private static String relative(File home, File f) throws IOException {
        String root = home.getCanonicalPath() + File.separator, path = f.getCanonicalPath();
        if (!path.startsWith(root)) throw new IOException("Path outside app storage");
        return path.substring(root.length()).replace(File.separatorChar, '/');
    }

    private static List<String> files(Models st, String id) throws Exception {
        if (!supported(id) || !st.modelInstalled(id) || !st.commonInstalled()) throw new IOException(L.s("Solo se pueden exportar modelos Anima instalados con sus archivos comunes.", "Only installed Anima models with their shared files can be exported."));
        List<String> out = new ArrayList<>();
        walk(st.home, st.modelDir(id), out);
        // Shared encoder, VAE and the current chip's NPU contexts.
        File[] root = st.anima.listFiles();
        if (root == null) throw new IOException("Cannot read shared model files");
        Arrays.sort(root);
        for (File f : root) if (!f.getName().startsWith("m_")) {
            if (f.isDirectory() && !f.getName().equals(Models.commonDir())) continue;
            walk(st.home, f, out);
        }
        File[] shared = st.sd.listFiles();
        if (shared == null) throw new IOException("Cannot read shared runtime files");
        File llmHeader = new File(new File(st.anima, Models.commonDir()), "llm_hdr.bin");
        File llmStub = commonTextStub(shared, llmHeader);
        Arrays.sort(shared);
        for (File f : shared) {
            if (f.getName().endsWith(".safetensors") && !f.getName().equals("taew2_1.safetensors")) continue; // model and text-encoder stubs are rebuilt below; keep the real shared VAE file
            walk(st.home, f, out);
        }
        Collections.sort(out);
        return out;
    }

    static void export(Context c, Models st, String id, String name, Uri tree, Models.Progress pg, AtomicBoolean cancel) throws Exception {
        ContentResolver cr = c.getContentResolver(); List<String> paths = files(st, id);
        if (paths.isEmpty()) throw new IOException(L.s("El modelo no contiene archivos para exportar", "The model has no files to export"));
        long total = 0; for (String p : paths) total = Math.addExact(total, new File(st.home, p).length());
        File[] shared = st.sd.listFiles(); if (shared == null) throw new IOException("Cannot read shared runtime files");
        File commonHeader = new File(new File(st.anima, Models.commonDir()), "llm_hdr.bin");
        File llmStub = commonTextStub(shared, commonHeader);
        Uri top = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri folder = DocumentsContract.createDocument(cr, top, DocumentsContract.Document.MIME_TYPE_DIR, "HexAnima-" + id);
        if (folder == null) throw new IOException(L.s("No se pudo crear la carpeta de exportación", "Could not create the export folder"));
        try {
            Map<String, Uri> dirs = new HashMap<>(); dirs.put("", folder);
            JSONArray entries = new JSONArray(); byte[] buffer = new byte[1 << 20]; long done = 0;
            for (String p : paths) {
                if (cancel.get()) throw new InterruptedIOException();
                File src = new File(st.home, p); Uri parent = dir(cr, dirs, folder, parentPath(p));
                Uri dst = DocumentsContract.createDocument(cr, parent, "application/octet-stream", leaf(p));
                if (dst == null) throw new IOException(L.f("No se pudo crear el archivo %s", "Could not create file %s", p));
                MessageDigest md = MessageDigest.getInstance("SHA-256"); long copied = 0;
                try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(dst)) {
                    if (out == null) throw new IOException("Cannot open destination " + p);
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        if (cancel.get()) throw new InterruptedIOException();
                        out.write(buffer, 0, n); md.update(buffer, 0, n); copied += n; done += n; pg.on(done, total, p);
                    }
                }
                if (copied != src.length()) throw new IOException(L.f("Copia incompleta: %s", "Incomplete copy: %s", p));
                entries.put(new JSONObject().put("p", p).put("size", copied).put("sha256", hex(md.digest())));
            }
            JSONArray stubs = new JSONArray();
            File modelStub = new File(st.sd, id + ".safetensors"), modelHeader = new File(st.modelDir(id), "hdr.bin");
            if (modelStub.isFile() && modelHeader.isFile()) stubs.put(stubManifest(relative(st.home, modelHeader), relative(st.home, modelStub), modelStub.length()));
            stubs.put(stubManifest(relative(st.home, commonHeader), relative(st.home, llmStub), llmStub.length()));
            JSONObject manifest = new JSONObject().put("format", 1).put("arch", Compat.dspArch()).put("id", id).put("name", name).put("files", entries).put("stubs", stubs);
            Uri mf = DocumentsContract.createDocument(cr, folder, "application/json", MANIFEST);
            if (mf == null) throw new IOException(L.s("No se pudo escribir el manifiesto", "Could not write the manifest"));
            try (OutputStream out = cr.openOutputStream(mf)) {
                if (out == null) throw new IOException("Cannot open model manifest");
                out.write(manifest.toString(2).getBytes("UTF-8"));
            }
        } catch (Exception e) {
            try { DocumentsContract.deleteDocument(cr, folder); } catch (Exception ignored) { }
            throw e;
        }
    }

    private static JSONObject stubManifest(String hdr, String out, long size) throws Exception {
        return new JSONObject().put("hdr", hdr).put("out", out).put("size", size);
    }

    private static File commonTextStub(File[] candidates, File header) throws IOException {
        if (!header.isFile()) throw new IOException(L.s("Falta el encabezado del codificador de texto compartido.", "The shared text-encoder header is missing."));
        File found = null;
        for (File f : candidates) if (f.isFile() && f.getName().endsWith(".safetensors") && !f.getName().equals("taew2_1.safetensors")
            && f.length() > 1_000_000_000L && prefixEquals(f, header)) {
            if (found != null) throw new IOException(L.s("Hay más de un archivo de texto compartido compatible.", "More than one matching shared text file was found."));
            found = f;
        }
        if (found == null) throw new IOException(L.s("No se encontró el archivo disperso del codificador de texto compartido.", "The sparse shared text-encoder file was not found."));
        return found;
    }

    private static boolean prefixEquals(File file, File prefix) throws IOException {
        if (file.length() < prefix.length()) return false;
        try (InputStream a = new FileInputStream(file); InputStream b = new FileInputStream(prefix)) {
            byte[] x = new byte[8192], y = new byte[8192];
            for (;;) {
                int nx = a.read(x), ny = b.read(y);
                if (nx != ny) return false;
                if (nx < 0) return true;
                for (int i = 0; i < nx; i++) if (x[i] != y[i]) return false;
            }
        }
    }

    static String[] importFrom(Context c, Models st, Uri tree, Models.Progress pg, AtomicBoolean cancel) throws Exception {
        ContentResolver cr = c.getContentResolver(); Map<String, Uri> docs = new HashMap<>();
        list(cr, tree, DocumentsContract.getTreeDocumentId(tree), "", docs);
        Uri mf = docs.get(MANIFEST);
        if (mf == null) throw new IOException(L.s("No se encontró hexanima-model.json en esa carpeta.", "hexanima-model.json was not found in that folder."));
        JSONObject manifest;
        try (InputStream in = cr.openInputStream(mf)) { if (in == null) throw new IOException("Cannot read manifest"); manifest = new JSONObject(new String(readAll(in), "UTF-8")); }
        if (manifest.optInt("format") != 1) throw new IOException(L.s("Formato de modelo desconocido", "Unknown model format"));
        String id = manifest.getString("id"), name = manifest.optString("name", id), arch = manifest.optString("arch");
        if (!supported(id)) throw new IOException(L.s("Este paquete no es un modelo Anima compatible con este importador.", "This pack is not an Anima model supported by this importer."));
        if (!Compat.dspArch().equals(arch)) throw new IOException(L.f("El paquete requiere Hexagon %s; este teléfono usa %s.", "This pack requires Hexagon %s; this phone uses %s.", arch, Compat.dspArch().isEmpty() ? "unsupported" : Compat.dspArch()));
        JSONArray entries = manifest.getJSONArray("files"); long total = 0; Set<String> paths = new HashSet<>();
        String modelRoot = "anima/m_" + id + "/";
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i); String p = e.getString("p"); validatePath(p, id);
            long size = e.getLong("size"); String sha = e.optString("sha256");
            if (size < 0 || !sha.matches("(?i)[0-9a-f]{64}") || !paths.add(p)) throw new IOException(L.s("El manifiesto contiene archivos duplicados o datos inválidos.", "The manifest contains duplicate files or invalid file data."));
            total = Math.addExact(total, size);
        }
        if (!paths.contains(modelRoot + "small.bin") || !paths.contains(modelRoot + "hdr.bin"))
            throw new IOException(L.s("El paquete no contiene los archivos esenciales del modelo Anima.", "The pack is missing essential Anima model files."));
        int blocks = 0;
        while (paths.contains(modelRoot + String.format(java.util.Locale.ROOT, "blk%02d.bin", blocks))) blocks++;
        if (blocks == 0) throw new IOException(L.s("El paquete no contiene los bloques del modelo Anima.", "The pack is missing Anima model blocks."));
        for (String p : paths) if (p.startsWith(modelRoot + "blk") && p.endsWith(".bin") && p.substring((modelRoot + "blk").length(), p.length() - 4).matches("\\d+")
            && Integer.parseInt(p.substring((modelRoot + "blk").length(), p.length() - 4)) >= blocks)
            throw new IOException(L.s("Los bloques del modelo no están completos o consecutivos.", "The model blocks are incomplete or not consecutive."));
        for (String p : new String[]{"anima/te0.bin", "anima/te1.bin", "anima/te2.bin", "anima/te3.bin", "anima/te_embed.f16", "anima/vae.bin", "sd/taew2_1.safetensors", "anima/" + Models.commonDir() + "/llm_hdr.bin"})
            if (!paths.contains(p)) throw new IOException(L.s("El paquete está incompleto: falta un componente compartido de Anima.", "The pack is incomplete: a shared Anima component is missing."));
        JSONArray stubs = manifest.optJSONArray("stubs"); boolean modelStub = false, commonStub = false; Set<String> stubOutputs = new HashSet<>();
        if (stubs != null) for (int i = 0; i < stubs.length(); i++) {
            JSONObject s = stubs.getJSONObject(i); String hdr = s.getString("hdr"), out = s.getString("out"); long size = s.getLong("size");
            validatePath(hdr, id); validateStub(hdr, out, id);
            if (size <= 0 || !paths.contains(hdr) || !stubOutputs.add(out)) throw new IOException(L.s("El manifiesto de archivos dispersos es inválido.", "The sparse-file manifest is invalid."));
            if (hdr.equals(modelRoot + "hdr.bin")) modelStub = true;
            if (hdr.equals("anima/" + Models.commonDir() + "/llm_hdr.bin")) commonStub = true;
        }
        if (!modelStub || !commonStub) throw new IOException(L.s("El paquete no contiene los encabezados necesarios para reconstruir los modelos.", "The pack is missing headers needed to rebuild its model files."));
        if (st.home.getUsableSpace() < total + MIN_FREE) throw new IOException(L.f("No hay espacio suficiente: se requieren %s GB más margen.", "Not enough space: %s GB plus free-space margin required.", String.format(java.util.Locale.US, "%.1f", total / 1e9)));
        byte[] buffer = new byte[1 << 20]; long done = 0; List<File> created = new ArrayList<>();
        File modelMark = null, commonMark = null; boolean madeModelMark = false, madeCommonMark = false;
        try {
            for (int i = 0; i < entries.length(); i++) {
                if (cancel.get()) throw new InterruptedIOException();
                JSONObject e = entries.getJSONObject(i); String p = e.getString("p"); long size = e.getLong("size"); String sha = e.getString("sha256");
                Uri src = docs.get(p); if (src == null) throw new IOException(L.f("Falta el archivo %s", "Missing file %s", p));
                File dst = new File(st.home, p); validateNoSymlinkAncestors(st.home, p);
                if (!dst.getCanonicalPath().startsWith(st.home.getCanonicalPath() + File.separator)) throw new IOException("Invalid destination path");
                if (dst.isFile() && dst.length() == size && sha.equalsIgnoreCase(Models.sha256(dst))) { done += size; pg.on(done, total, p); continue; }
                if (dst.exists() && (st.modelInstalled(id) || st.commonInstalled())) throw new IOException(L.s("Ya existe un modelo o componente con otros archivos. Elimínalo antes de restaurar este paquete.", "A model or shared component already exists with different files. Delete it before restoring this pack."));
                dst.getParentFile().mkdirs(); File part = new File(dst.getPath() + ".part"); MessageDigest md = MessageDigest.getInstance("SHA-256"); long copied = 0;
                try (InputStream in = cr.openInputStream(src); OutputStream out = new FileOutputStream(part)) {
                    if (in == null) throw new IOException("Cannot open " + p);
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        if (cancel.get()) throw new InterruptedIOException();
                        out.write(buffer, 0, n); md.update(buffer, 0, n); copied += n; done += n; pg.on(done, total, p);
                    }
                } catch (Exception e2) { part.delete(); throw e2; }
                if (copied != size || !hex(md.digest()).equalsIgnoreCase(sha)) { part.delete(); throw new IOException(L.f("El tamaño o hash no coincide: %s", "File size or hash mismatch: %s", p)); }
                if (dst.exists() && !dst.delete()) { part.delete(); throw new IOException("Cannot replace " + p); }
                if (!part.renameTo(dst)) { part.delete(); throw new IOException("Cannot finish " + p); }
                created.add(dst);
            }
            if (stubs != null) for (int i = 0; i < stubs.length(); i++) {
                JSONObject s = stubs.getJSONObject(i); String hdr = s.getString("hdr"), out = s.getString("out"); long size = s.getLong("size");
                File source = new File(st.home, hdr), dest = new File(st.home, out);
                if (!source.isFile()) throw new IOException("Missing sparse-file header: " + hdr);
                if (dest.exists()) { if (dest.length() != size) throw new IOException("Sparse-file destination has a different size: " + out); }
                else { dest.getParentFile().mkdirs(); created.add(dest); Models.stub(source, dest, size); }
            }
            File modelDir = st.modelDir(id); modelDir.mkdirs(); modelMark = st.doneMark(modelDir); madeModelMark = modelMark.createNewFile();
            File commonDir = new File(st.anima, Models.commonDir()); commonDir.mkdirs(); commonMark = st.doneMark(commonDir); madeCommonMark = commonMark.createNewFile();
            return new String[]{id, name};
        } catch (Exception e) {
            for (File f : created) f.delete();
            if (madeModelMark && modelMark != null) modelMark.delete();
            if (madeCommonMark && commonMark != null) commonMark.delete();
            throw e;
        }
    }

    private static void validatePath(String path, String id) throws IOException {
        if (path == null || path.contains("..") || path.startsWith("/") || path.contains("\\")) throw new IOException("Invalid path in manifest");
        boolean model = path.startsWith("anima/m_" + id + "/");
        boolean sharedRoot = path.startsWith("anima/") && path.indexOf('/', 6) < 0;
        boolean common = path.startsWith("anima/" + Models.commonDir() + "/");
        boolean sd = path.startsWith("sd/") && path.indexOf('/', 3) < 0 && (!path.endsWith(".safetensors") || path.equals("sd/taew2_1.safetensors"));
        boolean stub = path.equals("sd/" + id + ".safetensors");
        if (!(model || sharedRoot || common || sd || stub)) throw new IOException("Manifest path is outside this model: " + path);
    }

    private static void validateStub(String header, String output, String id) throws IOException {
        boolean model = header.equals("anima/m_" + id + "/hdr.bin") && output.equals("sd/" + id + ".safetensors");
        boolean common = header.equals("anima/" + Models.commonDir() + "/llm_hdr.bin") && output.startsWith("sd/") && output.indexOf('/', 3) < 0
            && output.endsWith(".safetensors") && !output.equals("sd/" + id + ".safetensors") && !output.equals("sd/taew2_1.safetensors");
        if (!model && !common) throw new IOException("Invalid sparse-file description");
    }

    private static void validateNoSymlinkAncestors(File root, String rel) throws IOException {
        File at = root; String[] parts = rel.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            at = new File(at, parts[i]);
            if (java.nio.file.Files.isSymbolicLink(at.toPath())) throw new IOException("Symbolic-link destination is not allowed");
        }
    }

    private static Uri dir(ContentResolver cr, Map<String, Uri> dirs, Uri root, String rel) throws IOException {
        Uri found = dirs.get(rel); if (found != null) return found;
        Uri parent = dir(cr, dirs, root, parentPath(rel));
        Uri made = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, leaf(rel));
        if (made == null) throw new IOException(L.f("No se pudo crear la carpeta %s", "Could not create folder %s", rel));
        dirs.put(rel, made); return made;
    }

    private static void list(ContentResolver cr, Uri tree, String docId, String rel, Map<String, Uri> out) throws IOException {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor q = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (q == null) throw new IOException("Cannot list selected folder");
            while (q.moveToNext()) {
                String childId = q.getString(0), name = q.getString(1), path = rel.isEmpty() ? name : rel + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(2))) list(cr, tree, childId, path, out);
                else out.put(path, DocumentsContract.buildDocumentUriUsingTree(tree, childId));
            }
        }
    }

    private static String parentPath(String p) { int i = p.lastIndexOf('/'); return i < 0 ? "" : p.substring(0, i); }
    private static String leaf(String p) { int i = p.lastIndexOf('/'); return p.substring(i + 1); }
    private static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n); return out.toByteArray(); }
    private static String hex(byte[] data) { StringBuilder out = new StringBuilder(); for (byte b : data) out.append(String.format(java.util.Locale.ROOT, "%02x", b)); return out.toString(); }
}
