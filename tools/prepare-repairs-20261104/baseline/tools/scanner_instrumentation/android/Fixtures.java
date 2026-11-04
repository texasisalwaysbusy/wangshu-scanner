package org.wangshu.scan.synthetic;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.content.res.AssetFileDescriptor;import java.io.*;
/** Exposes only three packaged, synthetic PNGs. No user files or arbitrary paths. */
public final class Fixtures extends ContentProvider {
 public boolean onCreate(){return true;}public String getType(Uri u){return "image/png";}
 public AssetFileDescriptor openAssetFile(Uri u,String mode)throws FileNotFoundException{String n=u.getLastPathSegment();if(!"r".equals(mode)||n==null||!n.matches("[ABC]"))throw new FileNotFoundException("Synthetic fixture only");try{return getContext().getAssets().openFd("fixtures/"+n+".png");}catch(IOException e){throw new FileNotFoundException(e.toString());}}
 public Cursor query(Uri u,String[] p,String s,String[] a,String sort){return null;}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
