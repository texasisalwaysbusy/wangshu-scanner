#!/usr/bin/env python3
"""Execute extracted unmodified importStream with in-memory fake Android/filesystem."""
from pathlib import Path
from support import PROJECT, OUTPUT, run_java
import hashlib, os, subprocess, sys
root=PROJECT
src=(root/'app/src/main/java/org/wangshu/scan/Store.java').read_text()
method=src[src.index('    synchronized Models.Page importStream('):src.index('    static final class CaptureImport {')]
java=r'''import java.io.*;import java.util.*;import java.security.*;
public class ImportMemoryProbe {
 static Map<String,byte[]> fs=new HashMap<>(); static boolean stale=false, uncertain=false;
 static class File { String p; File(String p){this.p=p;} File(File parent,String child){p=parent.p+"/"+child;} boolean mkdirs(){return true;} String getPath(){return p;} String getName(){return p.substring(p.lastIndexOf('/')+1);} boolean exists(){return fs.containsKey(p);} boolean renameTo(File b){if(!exists())return false;fs.put(b.p,fs.remove(p));return true;} }
 static class FD {void sync() {}}
 static class FileOutputStream extends OutputStream {File f;FileOutputStream(File f){this.f=f;fs.put(f.p,new byte[0]);} public void write(int b){write(new byte[]{(byte)b},0,1);} public void write(byte[] b,int o,int n){byte[] old=fs.get(f.p),v=Arrays.copyOf(old,old.length+n);System.arraycopy(b,o,v,old.length,n);fs.put(f.p,v);} FD getFD(){return new FD();}}
 static class Models {static class Page {String id="page",file,sha256;} static class Document {String id="doc",savedSnapshot="s";ArrayList<Page> pages=new ArrayList<>();}}
 static class ManifestFence {static class Uncertain extends IOException {Uncertain(){super("uncertain");}}}
 static class CameraInbox {static void requireCompleteIfJpeg(File f)throws IOException {byte[] b=fs.get(f.p);if(b.length>=2&&(b[0]&255)==255&&(b[1]&255)==216&&(b.length<4||(b[b.length-2]&255)!=255||(b[b.length-1]&255)!=217))throw new IOException("JPEG truncated");}}
 static class BitmapFactory {static class Options {boolean inJustDecodeBounds;int outWidth=10,outHeight=10;} static void decodeFile(String p,Options o) {}}
 static final Object FILE_LOCK=new Object(); void requireCertain(Models.Document d){} File dir(Models.Document d){return new File("doc");} Models.Document load(String id){Models.Document d=new Models.Document();if(stale)d.savedSnapshot="different";return d;} void save(Models.Document d)throws Exception{if(uncertain)throw new ManifestFence.Uncertain();} static String hex(byte[] b){return HexFormat.of().formatHex(b);}
''' + method + r'''
 static void ok(boolean c,String n){if(!c)throw new AssertionError(n);System.out.println("PASS "+n);}
 public static void main(String[] args)throws Exception {ImportMemoryProbe s=new ImportMemoryProbe();byte[] valid={(byte)255,(byte)216,1,(byte)255,(byte)217}; byte[] bad={(byte)255,(byte)216,1};
 Models.Document d=new Models.Document();s.importStream(d,new ByteArrayInputStream(valid));ok(d.pages.size()==1&&Arrays.equals(fs.get("doc/originals/page.image"),valid),"valid bytes published unchanged");
 fs.clear();d=new Models.Document();try{s.importStream(d,new ByteArrayInputStream(bad));throw new AssertionError();}catch(IOException e){ok(e.getMessage().contains("JPEG truncated")&&e.getMessage().contains("不能在界面找回")&&Arrays.equals(fs.get("doc/originals/page.part"),bad)&&d.pages.isEmpty(),"truncated rejected with retained partial and reason");}
 fs.clear();d=new Models.Document();InputStream fail=new InputStream(){int i=0;public int read()throws IOException{if(i++<2)return 42;throw new IOException("one-shot read failed");}};try{s.importStream(d,fail);throw new AssertionError();}catch(IOException e){ok(e.getMessage().contains("one-shot read failed")&&fs.get("doc/originals/page.part").length==2&&d.pages.isEmpty(),"one-shot read failure retains prefix and reason");}
 fs.clear();d=new Models.Document();stale=true;try{s.importStream(d,new ByteArrayInputStream(valid));throw new AssertionError();}catch(IOException e){ok(e.getMessage().contains("文稿在导入期间已变化")&&e.getMessage().contains("更多")&&fs.containsKey("doc/originals/page.image"),"stale document retains recoverable image and guidance");}stale=false;
 fs.clear();d=new Models.Document();uncertain=true;try{s.importStream(d,new ByteArrayInputStream(valid));throw new AssertionError();}catch(ManifestFence.Uncertain e){ok(fs.containsKey("doc/originals/page.image")&&d.pages.size()==1,"uncertain manifest exception propagated with image retained");}
 }}'''

p=run_java(java)
print(p.stdout,end="");print(p.stderr,end="",file=sys.stderr)
raise SystemExit(p.returncode)
