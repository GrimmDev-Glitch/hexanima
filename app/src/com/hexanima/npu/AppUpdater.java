package com.hexanima.npu;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Downloads and verifies the latest GitHub release APK before handing it to Android's installer. */
final class AppUpdater {
    private static final String API = "https://api.github.com/repos/GrimmDev-Glitch/hexanima/releases/latest";
    private AppUpdater() { }
    static void check(Activity a) {
        Toast.makeText(a,L.s("Buscando una versión…","Checking for an update…"),Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File out=new File(a.getCacheDir(),"update.apk"),tmp=new File(a.getCacheDir(),"update.tmp"); String msg; boolean ready=false;
            try {
                JSONObject rel=new JSONObject(get(API)); String tag=rel.optString("tag_name",""); JSONObject asset=null; org.json.JSONArray as=rel.optJSONArray("assets");
                if(as!=null)for(int i=0;i<as.length();i++){JSONObject x=as.getJSONObject(i);if(x.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")){asset=x;break;}}
                if(asset==null)throw new IOException(L.s("El Release no contiene un APK","The release has no APK asset"));
                android.content.pm.PackageInfo installed=a.getPackageManager().getPackageInfo(a.getPackageName(),0); File installedApk=new File(installed.applicationInfo.sourceDir); long size=asset.optLong("size",0); if(size<=0)throw new IOException("Invalid APK size");
                download(asset.getString("browser_download_url"),tmp,size); String digest=asset.optString("digest","");
                if(!digest.matches("sha256:[0-9a-fA-F]{64}")||!digest.substring(7).equalsIgnoreCase(sha256(tmp)))throw new IOException(L.s("No coincide el SHA-256 del APK","APK SHA-256 does not match the release"));
                android.content.pm.PackageInfo pi=a.getPackageManager().getPackageArchiveInfo(tmp.getPath(),0);
                if(pi==null||!a.getPackageName().equals(pi.packageName))throw new IOException(L.s("El APK no corresponde a HexAnima","The APK is not HexAnima"));
                if(pi.versionCode<installed.versionCode || (pi.versionCode==installed.versionCode && installedApk.isFile() && digest.substring(7).equalsIgnoreCase(sha256(installedApk)))){tmp.delete();show(a,L.s("Ya tienes la última versión","You already have the latest version"));return;}
                if(!tmp.renameTo(out)){try(InputStream in=new FileInputStream(tmp);OutputStream o=new FileOutputStream(out)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>0;)o.write(b,0,n);}tmp.delete();}
                msg=L.f("Versión %s verificada","Version %s verified",tag);ready=true;
            }catch(Exception e){tmp.delete();msg=L.f("No se pudo actualizar: %s","Update failed: %s",String.valueOf(e.getMessage()));}
            String m=msg;boolean r=ready;a.runOnUiThread(()->{Toast.makeText(a,m,Toast.LENGTH_LONG).show();if(r)install(a);});
        },"hexanima-updater").start();
    }
    private static void show(Activity a,String s){a.runOnUiThread(()->Toast.makeText(a,s,Toast.LENGTH_LONG).show());}
    private static String get(String u)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","HexAnima-Android-Updater");c.setRequestProperty("Accept","application/vnd.github+json");try(InputStream in=c.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] x=new byte[8192];for(int n;(n=in.read(x))>0;)b.write(x,0,n);return b.toString("UTF-8");}finally{c.disconnect();}}
    private static void download(String u,File f,long expected)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(20000);c.setReadTimeout(60000);c.setRequestProperty("User-Agent","HexAnima-Android-Updater");if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());long count=0;try(InputStream in=c.getInputStream();OutputStream o=new BufferedOutputStream(new FileOutputStream(f))){byte[] b=new byte[65536];for(int n;(n=in.read(b))>0;){count+=n;if(count>expected)throw new IOException("APK exceeds expected size");o.write(b,0,n);}}finally{c.disconnect();}if(count!=expected)throw new IOException("Incomplete APK download");}
    private static String sha256(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>0;)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    static void install(Activity a){File f=new File(a.getCacheDir(),"update.apk");if(!f.isFile())return;if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){Settings.prefs(a).edit().putBoolean("update_permission_pending",true).apply();Toast.makeText(a,L.s("Permite instalar actualizaciones de HexAnima y vuelve aquí","Allow HexAnima to install updates, then return here"),Toast.LENGTH_LONG).show();a.startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",Uri.parse("package:"+a.getPackageName())));return;}Settings.prefs(a).edit().putBoolean("update_permission_pending",false).apply();Uri u=Uri.parse("content://"+a.getPackageName()+".updates/update.apk");Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(u,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);a.startActivity(i);}
    static void resumeInstall(Activity a){if(Settings.prefs(a).getBoolean("update_permission_pending",false)&&Build.VERSION.SDK_INT>=26&&a.getPackageManager().canRequestPackageInstalls())install(a);}
}
