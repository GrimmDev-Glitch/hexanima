package com.hexanima.npu;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.os.ParcelFileDescriptor;import java.io.File;
/** Grants the system installer access to the single verified cached APK. */
public final class UpdateApkProvider extends ContentProvider {
 @Override public boolean onCreate(){return true;}
 @Override public String getType(Uri u){return "application/vnd.android.package-archive";}
 @Override public ParcelFileDescriptor openFile(Uri u,String mode)throws java.io.FileNotFoundException{if(!"update.apk".equals(u.getLastPathSegment())||!"r".equals(mode))throw new SecurityException("Invalid update URI");return ParcelFileDescriptor.open(new File(getContext().getCacheDir(),"update.apk"),ParcelFileDescriptor.MODE_READ_ONLY);}
 @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
 @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
