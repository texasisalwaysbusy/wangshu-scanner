package org.wangshu.scan;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Only camera inbox and completed exports are grantable, never documents or arbitrary files. */
public final class ScanProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File resolve(Uri uri) throws FileNotFoundException {
        if(uri.getPathSegments().size()!=2)throw new FileNotFoundException("Invalid URI");
        String area=uri.getPathSegments().get(0),name=uri.getLastPathSegment();
        if((!area.equals("camera")&&!area.equals("exports"))||name==null||!name.matches("[a-zA-Z0-9_.-]+")||name.contains(".."))throw new FileNotFoundException("Invalid URI");
        File f=area.equals("camera")?CameraInbox.file(getContext(),name):new File(new File(getContext().getCacheDir(),area),name);File base=f.getParentFile();base.mkdirs();
        try{if(!f.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator))throw new FileNotFoundException("Invalid URI");}catch(IOException e){throw new FileNotFoundException("Invalid URI");}
        return f;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        File file=resolve(uri);
        if(uri.getPathSegments().get(0).equals("exports")&&!mode.equals("r"))throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri){String n=uri.getLastPathSegment();return n!=null&&n.endsWith(".pdf")?"application/pdf":n!=null&&n.endsWith(".zip")?"application/zip":n!=null&&n.endsWith(".png")?"image/png":n!=null&&n.endsWith(".webp")?"image/webp":n!=null&&n.endsWith(".gif")?"image/gif":n!=null&&n.endsWith(".heic")?"image/heic":"image/jpeg";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        try{File f=resolve(uri);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{f.getName(),f.length()});return c;}catch(FileNotFoundException e){return null;}
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
