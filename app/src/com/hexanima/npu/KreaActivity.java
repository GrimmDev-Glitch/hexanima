package com.hexanima.npu;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Local, isolated Krea 2 Turbo route. Model packs are user supplied and never bundled in the APK. */
public final class KreaActivity extends Base {
    private static final int REQ_PACK = 91;
    private static final String LICENSE = "https://www.krea.ai/krea-2-licensing";
    private static final String AUP = "https://www.krea.ai/acceptable-use-policy";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private EditText prompt;
    private TextView state;
    private Button generate, install;
    private AlertDialog progress;
    private boolean running;
    private volatile Process activeProcess;
    private File home() { return new File(getFilesDir(), "krea"); }
    private File active() { return new File(home(), "active"); }
    private boolean ready() { return new File(active(), "READY").isFile(); }
    private boolean nativeReady() { File d=new File(getApplicationInfo().nativeLibraryDir); for(String n:new String[]{"libkrea_phone_sampler.so","libkrea_text_fuse_cpu.so","libkrea_vae_cpu.so","libkrea_blk_smoke.so","libkrea_skel_v73.so"}) if(!new File(d,n).isFile()) return false; return true; }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b); L.init(this); applyTheme(this); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout col = screen("Krea 2 Turbo", null);
        TextView intro = text(L.s("Experimental · 256 × 256 · 8 pasos", "Experimental · 256 × 256 · 8 steps"), 14, ACC, true); col.addView(intro);
        TextView detail = text(L.s("La generación completa corre en el teléfono y puede tardar varios minutos. Krea guarda sus archivos por separado; no modifica Anima, MOSS, Qwen ni Mage.", "Full generation runs on-device and may take several minutes. Krea keeps separate files and does not alter Anima, MOSS, Qwen or Mage."), 14, DIM, false); detail.setPadding(0, dp(6), 0, dp(12)); col.addView(detail);
        state = text("", 13, WARN, false); state.setPadding(0, 0, 0, dp(10)); col.addView(state);
        install = button(L.s("Importar pack Krea desde carpeta…", "Import Krea pack from folder…"), CARD, TXT); install.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQ_PACK)); col.addView(install, new LinearLayout.LayoutParams(-1, dp(46)));
        prompt = field(L.s("Describe la imagen…", "Describe the image…")); prompt.setMinLines(3); prompt.setGravity(android.view.Gravity.TOP | android.view.Gravity.START); prompt.setTextSize(16); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(120)); pp.topMargin = dp(14); col.addView(prompt, pp);
        generate = button(L.s("Generar · experimental", "Generate · experimental"), ACC, ON_ACC); LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(50)); gp.topMargin = dp(10); col.addView(generate, gp); generate.setOnClickListener(v -> generate());
        TextView legal = text(L.s("Al usar Krea, acepta la licencia de Krea 2 y su política de uso. HexAnima aplica filtros al prompt y al resultado; el modelo y el filtro local no detectan todos los usos prohibidos.", "Using Krea means accepting the Krea 2 license and acceptable-use policy. HexAnima filters prompts and results; the model and local filter cannot detect every prohibited use."), 12, DIM, false); legal.setPadding(0, dp(12), 0, 0); col.addView(legal);
        legal.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Krea 2 · terms").setMessage(L.s("Licencia: https://www.krea.ai/krea-2-licensing\nPolítica de uso: https://www.krea.ai/acceptable-use-policy\n\nImportar un pack significa que usted obtuvo el modelo conforme a la licencia y acepta estos términos.", "License: https://www.krea.ai/krea-2-licensing\nAcceptable use: https://www.krea.ai/acceptable-use-policy\n\nImporting a pack means you obtained the model under its license and accept these terms." )).setPositiveButton("OK", null).show());
        refresh();
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_PACK && result == RESULT_OK && data != null && data.getData() != null) importPack(data.getData());
    }
    private void refresh() {
        boolean supported = Compat.dspArch().equals("v73");
        boolean installed = ready();
        state.setText(!supported ? L.s("Este teléfono todavía no tiene una ruta Krea validada (se requiere Snapdragon 8 Gen 2 / V73).", "This phone does not have a validated Krea route yet (Snapdragon 8 Gen 2 / V73 required).") : !nativeReady() ? L.s("Esta APK no incluye los ejecutables Krea.", "This APK does not include the Krea runners.") : installed ? L.s("Pack instalado. Se requiere Mage instalado y el detector de seguridad.", "Pack installed. Mage and the safety detector are also required.") : L.s("Falta instalar el pack local (~15 GB).", "The local model pack (~15 GB) is not installed."));
        generate.setEnabled(supported && nativeReady() && installed && Mage.installed(getFilesDir()) && Tagger.installed(this) && !running);
        generate.setAlpha(generate.isEnabled() ? 1f : .5f);
        install.setEnabled(!running);
    }
    private void importPack(Uri tree) {
        try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        if (running) return;
        running = true; refresh(); cancel.set(false); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout box = new LinearLayout(this); box.setPadding(dp(18), dp(12), dp(18), 0); box.setOrientation(LinearLayout.VERTICAL);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); box.addView(bar);
        TextView line = text(L.s("Copiando a almacenamiento privado de Krea…", "Copying to Krea's private storage…"), 13, DIM, false); box.addView(line);
        progress = builder().setTitle(L.s("Importar Krea", "Import Krea")).setView(box).setCancelable(false).setNegativeButton(L.s("Cancelar", "Cancel"), null).show();
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { cancel.set(true); line.setText(L.s("Cancelando…", "Cancelling…")); });
        worker.submit(() -> {
            String error = null; File stage = new File(home(), "install-partial");
            try {
                deleteTree(stage); if (!stage.mkdirs()) throw new IOException("Cannot create staging directory");
                copyTree(tree, stage, bar, line);
                normalizeAndValidate(stage);
                if (cancel.get()) throw new InterruptedIOException("cancelled");
                File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
                File vaeDir = new File(stage, "vae"); vaeDir.mkdirs();
                copyFile(new File(nativeDir, "libkrea_vae_cpu.so"), new File(vaeDir, "krea_vae_cpu"));
                File dsp = new File(stage, "dsp"); dsp.mkdirs();
                copyFile(new File(nativeDir, "libkrea_skel_v73.so"), new File(dsp, "libmega_skel.so"));
                new File(stage, "READY").createNewFile();
                File old = new File(home(), "previous"); deleteTree(old);
                File target = active(); if (target.exists() && !target.renameTo(old)) throw new IOException("Cannot preserve previous Krea pack");
                if (!stage.renameTo(target)) { if (old.exists()) old.renameTo(target); throw new IOException("Cannot activate imported pack"); }
                deleteTree(old);
            } catch (Exception e) { deleteTree(stage); error = e instanceof InterruptedIOException ? L.s("Importación cancelada; los modelos anteriores siguen intactos.", "Import cancelled; existing models remain untouched.") : String.valueOf(e.getMessage()); }
            final String msg = error; ui.post(() -> { if (progress != null) progress.dismiss(); progress = null; running = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); refresh(); if (msg != null) new AlertDialog.Builder(this).setMessage(L.f("No se pudo importar el pack: %s", "Could not import pack: %s", msg)).setPositiveButton("OK", null).show(); });
        });
    }
    private void copyTree(Uri tree, File dst, ProgressBar bar, TextView line) throws Exception {
        String id = DocumentsContract.getTreeDocumentId(tree); Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, id); copyDir(tree, root, dst, bar, line);
    }
    private void copyDir(Uri tree, Uri dir, File dst, ProgressBar bar, TextView line) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir));
        try (android.database.Cursor c = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) throw new IOException("Cannot list selected folder");
            while (c.moveToNext()) {
                if (cancel.get()) throw new InterruptedIOException("cancelled");
                String id = c.getString(0), name = new File(c.getString(1)).getName(); if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
                Uri item = DocumentsContract.buildDocumentUriUsingTree(tree, id); File out = new File(dst, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) { if (out.exists() || !out.mkdirs()) throw new IOException("Duplicate or invalid folder: " + name); copyDir(tree, item, out, bar, line); }
                else { try (InputStream in = getContentResolver().openInputStream(item); OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) { if (in == null) throw new IOException("Cannot open " + name); byte[] b = new byte[1<<20]; long n=0; for (int r; (r=in.read(b))!=-1; ) { if (cancel.get()) throw new InterruptedIOException("cancelled"); os.write(b,0,r); n+=r; if ((n & ((16L<<20)-1)) < b.length) { final String text = L.f("Copiando %s · %.1f GB", "Copying %s · %.1f GB", name, out.length()/1e9); ui.post(() -> line.setText(text)); } } } }
            }
        }
    }
    private void normalizeAndValidate(File root) throws Exception {
        File dit = new File(root, "dit"); for (int i=0;i<28;i++) { File f = new File(dit, String.format(Locale.US,"blk%02d.bin",i)); if (!f.isFile() || f.length()<100_000_000L) throw new IOException("Missing or invalid dit/"+f.getName()); }
        File sampler = locate(root, "sampler.kft"), fusion = locate(root, "text-fusion.kft"), vae = locate(root, "krea-vae.kva");
        if (sampler == null || sampler.length()<1_000_000L) throw new IOException("Missing or invalid sampler.kft");
        if (fusion == null || fusion.length()<1_000_000L) throw new IOException("Missing or invalid text-fusion.kft");
        if (vae == null || vae.length()<1_000_000L) throw new IOException("Missing or invalid krea-vae.kva");
        File vd = new File(root,"vae"); vd.mkdirs();
        moveTo(sampler, new File(root,"sampler.kft")); moveTo(fusion,new File(root,"text-fusion.kft")); moveTo(vae,new File(vd,"krea-vae.kva"));
    }
    private static void moveTo(File from, File to) throws IOException { if (from.equals(to)) return; if (to.exists()) throw new IOException("Duplicate model file: " + to.getName()); if (!from.renameTo(to)) throw new IOException("Cannot arrange model file: " + from.getName()); }
    private File locate(File root, String name) { if (new File(root,name).isFile()) return new File(root,name); File[] ch=root.listFiles(); if(ch!=null) for(File f:ch){ if(f.isDirectory()){File x=locate(f,name);if(x!=null)return x;} } return null; }
    private static void copyFile(File src, File dst) throws IOException { if (!src.isFile()) throw new IOException("Missing runtime " + src.getName()); try(InputStream i=new FileInputStream(src);OutputStream o=new FileOutputStream(dst)){byte[]b=new byte[65536];for(int n;(n=i.read(b))>0;)o.write(b,0,n);} if(!dst.setExecutable(true,false)) throw new IOException("Cannot prepare executable"); }
    private static void deleteTree(File f) { if(f.isDirectory()){File[]a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);} f.delete(); }

    private void generate() {
        final String text = prompt.getText().toString().trim(); if (text.isEmpty()) return;
        String preflight=preflight(); if(preflight!=null){new AlertDialog.Builder(this).setMessage(preflight).setPositiveButton("OK",null).show();return;}
        String bad = policyBlock(text); if (bad != null) { new AlertDialog.Builder(this).setMessage(bad).setPositiveButton("OK",null).show(); return; }
        if (!getSharedPreferences("krea",0).getBoolean("terms",false)) {
            new AlertDialog.Builder(this).setTitle("Krea 2").setMessage(L.s("Confirma que leíste y aceptas la licencia de Krea 2 y su política de uso. No se permite contenido sexual con menores, imágenes íntimas sin consentimiento, suplantación, daño o desarrollo de armas.", "Confirm that you have read and accept the Krea 2 license and acceptable-use policy. Sexual content involving minors, non-consensual intimate imagery, impersonation, harm, and weapons development are prohibited."))
                .setNegativeButton(L.s("Cancelar","Cancel"),null).setPositiveButton(L.s("Acepto","Accept"),(d,w)->{getSharedPreferences("krea",0).edit().putBoolean("terms",true).apply(); startGeneration(text);}).show(); return;
        }
        startGeneration(text);
    }
    private String policyBlock(String p) {
        String s=p.toLowerCase(Locale.ROOT);
        boolean sex=s.matches("(?s).*(nude|naked|sex|sexual|erotic|porn|nsfw|desnuda|desnudo|sexo|sexual|erótico|porno|fetish|fetiche).*" );
        boolean minor=s.matches("(?s).*(child|children|kid|minor|underage|teen|loli|shota|menor|niñ[oa]s?|infante|adolescente|colegial[oa]).*");
        if(sex&&minor) return L.s("Prompt bloqueado: no se permite contenido sexual que incluya menores o personas de edad ambigua.","Prompt blocked: sexual content involving minors or age-ambiguous people is not allowed.");
        if(s.matches("(?s).*(deepfake|non.consensual|revenge porn|intimate image|desnudo de [una|un]|sin consentimiento|porno de venganza|imagen íntima).*")) return L.s("Prompt bloqueado: no se permite crear imágenes íntimas de personas sin consentimiento.","Prompt blocked: creating intimate images of people without consent is not allowed.");
        if(s.matches("(?s).*(build|make|construct|instructions|fabricat|construir|fabricar).*(bomb|explosive|weapon|firearm|explosivo|bomba|arma).*")) return L.s("Prompt bloqueado: no se permite ayudar a fabricar armas o explosivos.","Prompt blocked: assistance to make weapons or explosives is not allowed.");
        return null;
    }
    private void startGeneration(String text) {
        if (!Tagger.supported() || !Tagger.installed(this) || !Mage.installed(getFilesDir()) || !ready()) { refresh(); return; }
        running = true; cancel.set(false); refresh(); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18),dp(12),dp(18),0);
        ProgressBar pb = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); pb.setMax(1000); box.addView(pb);
        TextView line=text(L.s("Preparando texto…","Preparing text…"),13,DIM,false); box.addView(line);
        progress=builder().setTitle("Krea 2 Turbo · 256×256").setView(box).setCancelable(false).setNegativeButton(L.s("Detener","Stop"),null).show();
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{cancel.set(true);Process p=activeProcess;if(p!=null)p.destroy();line.setText(L.s("Deteniendo…","Stopping…"));});
        worker.submit(()->{
            File run=new File(home(),"run"); deleteTree(run); run.mkdirs(); File hidden=new File(run,"hidden");hidden.mkdirs();
            File mage=Mage.dir(getFilesDir()), ids=new File(run,"ids.i32"), context=new File(run,"context.f32"), mask=new File(run,"mask.bin"), latent=new File(run,"latent.f32"), rgb=new File(run,"image.f32"), log=new File(run,"krea.log");
            File png=null; String error=null; long started=System.currentTimeMillis(); final String seed=String.valueOf(System.currentTimeMillis()&0x7fffffff);
            try {
                ui.post(()->line.setText(L.s("Tokenizando…","Tokenising…")));
                int drop=Mage.writeIds(getFilesDir(),text,ids), seq=(int)(ids.length()/4), promptTokens=seq-drop-39;
                if(promptTokens<1||promptTokens>27)throw new IOException(L.s("El prompt supera el límite experimental de 27 tokens; acórtalo.","Prompt exceeds the experimental 27-token limit; shorten it."));
                File nativeDir=new File(getApplicationInfo().nativeLibraryDir), mageDsp=new File(active(),"text-dsp");mageDsp.mkdirs();copyFile(new File(nativeDir,"libmageskel_v73.so"),new File(mageDsp,"libmega_skel.so"));
                String enc="loadte "+mage.getPath()+"\nenc "+ids.getPath()+" "+drop+"\nquit\n";
                runInteractive(new File(nativeDir,"libmagerun_mage.so"),mage,enc,log,hidden,300);
                ui.post(()->line.setText(L.s("Fusionando el contexto…","Fusing text context…")));
                runCommand(new File(nativeDir,"libkrea_text_fuse_cpu.so"),new String[]{new File(active(),"text-fusion.kft").getPath(),hidden.getPath(),context.getPath(),mask.getPath(),String.valueOf(promptTokens),String.valueOf(seq)},run,log,Collections.emptyMap(),180);
                if(cancel.get())throw new InterruptedIOException();
                ui.post(()->line.setText(L.s("Generando en la NPU · 8 pasos…","Generating on the NPU · 8 steps…")));
                File vae=new File(active(),"vae"), vaeWeights=new File(vae,"krea-vae.kva");
                Map<String,String> env=new HashMap<>();env.put("KREA_LIB_DIR",nativeDir.getPath());env.put("KREA_DSP_DIR",new File(active(),"dsp").getPath());env.put("KREA_RELEASE_EACH","1");
                runCommand(new File(nativeDir,"libkrea_phone_sampler.so"),new String[]{new File(active(),"sampler.kft").getPath(),context.getPath(),"seed:"+seed,latent.getPath(),new File(active(),"dit").getPath(),vae.getPath(),vaeWeights.getPath(),rgb.getPath(),new File(nativeDir,"libkrea_blk_smoke.so").getPath(),String.valueOf(promptTokens)},run,log,env,900);
                if(cancel.get())throw new InterruptedIOException();
                File generated=new File(run,"image.png"); if(!generated.isFile()||generated.length()<1024)throw new IOException("Krea runner did not write a PNG; see files/krea/run/krea.log");
                ui.post(()->line.setText(L.s("Revisando el resultado…","Checking the result…")));
                Bitmap bm=BitmapFactory.decodeFile(generated.getPath()); if(bm==null)throw new IOException("Invalid output image");
                float[] ratings=new float[4];Tagger.tag(this,bm,0.35f,ratings);
                if(ratings[3]>=0.10f||ratings[2]>=0.25f||ratings[1]>=0.65f) { bm.recycle();throw new IOException(L.s("El filtro local marcó la imagen; no se guardó. Puedes consultar el registro files/krea/run/krea.log.","The local filter flagged this image; it was not saved. Check files/krea/run/krea.log.")); }
                bm.recycle();
                byte[] bytes=Models.readBytes(generated);JSONObject meta=new JSONObject().put("prompt",text).put("seed",seed).put("w",256).put("h",256).put("model","Krea 2 Turbo").put("sec",(System.currentTimeMillis()-started)/1000.0).put("safety","wd-tagger-v3");
                png=Gallery.save(this,bytes,meta);
            }catch(Exception e){error=cancel.get()?L.s("Generación cancelada; no se guardó una imagen parcial.","Generation cancelled; no partial image was saved."):String.valueOf(e.getMessage());}
            final String message=error;final File output=png;
            ui.post(()->{if(progress!=null)progress.dismiss();progress=null;running=false;activeProcess=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);refresh();if(output!=null){Bitmap bm=BitmapFactory.decodeFile(output.getPath());ImageView iv=new ImageView(this);iv.setImageBitmap(bm);iv.setAdjustViewBounds(true);new AlertDialog.Builder(this).setTitle(L.s("Generación guardada en el historial","Image saved to History")).setView(iv).setPositiveButton("OK",null).setNeutralButton(L.s("Historial","History"),(d,w)->startActivity(new Intent(this,History.class))).show();}else new AlertDialog.Builder(this).setMessage(L.f("Error de generación: %s (registro files/krea/run/krea.log)","Generation error: %s (log files/krea/run/krea.log)",message)).setPositiveButton("OK",null).show();});
        });
    }
    private void runInteractive(File bin,File cwd,String commands,File log,File hidden,long timeout)throws Exception{
        ProcessBuilder b=new ProcessBuilder(bin.getPath()).directory(cwd).redirectErrorStream(true).redirectOutput(log);Map<String,String>e=b.environment();String lib=getApplicationInfo().nativeLibraryDir;e.put("LD_LIBRARY_PATH",lib+":/system/lib64:/vendor/lib64");e.put("ADSP_LIBRARY_PATH",new File(active(),"text-dsp").getPath()+";"+lib);e.put("MAGE_CACHE",new File(active(),"text-cache").getPath());e.put("MAGE_DUMP_HIDDEN",hidden.getPath());e.put("MAGE_POWER_EACH","1");e.put("MAGE_RELEASE_EACH","1");
        Process p=b.start();activeProcess=p;try(OutputStream o=p.getOutputStream()){o.write(commands.getBytes("UTF-8"));o.flush();}waitProcess(p,timeout,"Qwen encoder timed out");activeProcess=null;if(p.exitValue()!=0)throw new IOException("Qwen encoder failed; see files/krea/run/krea.log");for(int layer:new int[]{2,5,8,11,14,17,20,23,26,29,32,35})if(!new File(hidden,String.format(Locale.US,"hidden_%02d.f32",layer)).isFile())throw new IOException("Qwen hidden state missing");
    }
    private void runCommand(File bin,String[]args,File cwd,File log,Map<String,String>extra,long timeout)throws Exception{
        ProcessBuilder b=new ProcessBuilder();List<String>cmd=new ArrayList<>();cmd.add(bin.getPath());cmd.addAll(Arrays.asList(args));b.command(cmd).directory(cwd).redirectErrorStream(true).redirectOutput(log);String lib=getApplicationInfo().nativeLibraryDir;b.environment().put("LD_LIBRARY_PATH",lib+":/system/lib64:/vendor/lib64");b.environment().put("ADSP_LIBRARY_PATH",new File(active(),"dsp").getPath()+";"+lib);b.environment().putAll(extra);Process p=b.start();activeProcess=p;waitProcess(p,timeout,"Krea stage timed out");activeProcess=null;if(p.exitValue()!=0)throw new IOException("Krea stage failed ("+p.exitValue()+"); see files/krea/run/krea.log");
    }
    private String preflight(){android.app.ActivityManager.MemoryInfo mi=new android.app.ActivityManager.MemoryInfo();((android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);if(mi.availMem<5L*1024*1024*1024)return L.s("Krea necesita al menos 5 GB de RAM disponible para iniciar. Cierra otras apps y vuelve a probar.","Krea needs at least 5 GB of available RAM to start. Close other apps and try again.");if(home().getUsableSpace()<8L*1024*1024*1024)return L.s("Krea necesita al menos 8 GB libres para los archivos temporales.","Krea needs at least 8 GB free for temporary files.");int t=batteryTemp();if(t>=430)return L.s("El teléfono está demasiado caliente para empezar. Espera a que se enfríe.","The phone is too warm to start. Let it cool down first.");return null;}
    private int batteryTemp(){try{android.content.Intent i=registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));return i==null?0:i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,0);}catch(Exception e){return 0;}}
    private void waitProcess(Process p,long seconds,String timeout)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){if(p.waitFor(2,TimeUnit.SECONDS))return;if(cancel.get()){p.destroy();throw new InterruptedIOException("cancelled");}if(batteryTemp()>=450){p.destroy();throw new IOException(L.s("La generación se detuvo porque el teléfono alcanzó 45 °C.","Generation stopped because the phone reached 45 °C."));}}p.destroyForcibly();throw new IOException(timeout);}
    @Override protected void onDestroy() { super.onDestroy(); cancel.set(true); worker.shutdownNow(); }
}
