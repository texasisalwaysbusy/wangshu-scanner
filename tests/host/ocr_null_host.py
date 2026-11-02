#!/usr/bin/env python3
"""Run actual OCR recognition method against synthetic fakes; no Android runtime."""
from pathlib import Path
from support import PROJECT, OUTPUT, run_java
import hashlib
import os
import subprocess
import sys

project = PROJECT
source = project / 'app/src/main/java/org/wangshu/scan/OcrEngine.java'
text = source.read_text(encoding='utf-8')
start = text.index('    static void recognize(')
end = text.index('    static File searchablePdf(', start)
actual = text[start:end]
java = r'''
import java.util.*;
import java.io.*;
public class OcrNullMemoryHost {
  static TessBaseAPI current;
  static TessBaseAPI open(Object c,String l){return current;}
  static String key(Models.Page p){return "new-key";}
__ACTUAL__
  static void check(boolean b,String s){if(!b)throw new AssertionError(s);System.out.println("PASS "+s);}
  static Models.Document doc(String... texts){Models.Document d=new Models.Document();for(String t:texts){Models.Page p=new Models.Page();p.text=t;p.key="old-key";d.pages.add(p);}return d;}
  static void run(Models.Document d,String... outputs)throws Exception{
    current=new TessBaseAPI(outputs);Store.writes=0;Bitmap.recycled=0;DocumentMutations.calls=0;Exporter.updates=0;
    recognize(new Store(),d,"eng",(a,b)->Exporter.updates++);
  }
  public static void main(String[] args)throws Exception {
    Models.Document one=doc("old text");boolean failed=false;
    try{run(one,(String)null);}catch(IOException e){failed=true;}
    check(failed,"null recognition returns IOException");
    check(one.pages.get(0).text.equals("old text")&&one.pages.get(0).key.equals("old-key")&&Store.writes==0&&DocumentMutations.calls==0,"null preserves old text/key without writer call");
    check(Bitmap.recycled==1&&current.recycled&&Exporter.updates==0,"null recycles resources and does not report page completion");
    one=doc("old text");run(one,"");
    check(one.pages.get(0).text.equals("")&&one.pages.get(0).key.equals("new-key")&&Store.writes==1,"legitimate empty string is saved after explicit recognition");
    Models.Document two=doc("first old","second old");failed=false;
    try{run(two,"first new",null);}catch(IOException e){failed=true;}
    check(failed&&two.pages.get(0).text.equals("first new")&&two.pages.get(1).text.equals("second old")&&two.pages.get(1).key.equals("old-key"),"later null preserves failed page and earlier completed page");
    check(Store.writes==1&&Exporter.updates==1&&Bitmap.recycled==2&&current.recycled,"partial run persists only completed page and recycles");
    System.out.println("HOST ONLY: extracted recognition code with fakes; no actual Android JNI or OCR runtime");
  }
}
class Models { static class Page {String text,key;} static class Document {ArrayList<Page> pages=new ArrayList<>();ArrayList<Page> active(){return pages;}} }
class Store {Object context;static int writes;File original(Models.Document d,Models.Page p){return new File("unused");}void saveIfCurrent(Models.Document d){writes++;}}
class Exporter {static int updates;interface Progress {void update(int n,int total);}}
class Bitmap {static int recycled;void recycle(){recycled++;}}
class Renderer {static Bitmap render(File f,Models.Page p,int n){return new Bitmap();}}
class TessBaseAPI {final String[] values;int i;boolean recycled;TessBaseAPI(String[] a){values=a;}void setImage(Bitmap b){}String getUTF8Text(){return values[i++];}void clear(){}void recycle(){recycled=true;}}
class DocumentMutations {interface Writer{void save(Models.Document d)throws Exception;}static int calls;static void ocr(Models.Document d,Models.Page p,String t,String k,String l,Writer w)throws Exception{calls++;p.text=t;p.key=k;w.save(d);}}
'''.replace('__ACTUAL__', actual)

p=run_java(java)
print(p.stdout,end="");print(p.stderr,end="",file=sys.stderr)
raise SystemExit(p.returncode)
