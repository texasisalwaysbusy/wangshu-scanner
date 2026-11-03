#!/usr/bin/env python3
"""Run the real revision methods against fake pages; host only, no PDF or Android."""
from pathlib import Path
from support import PROJECT, OUTPUT, run_java
import hashlib, os, subprocess, sys
project=PROJECT
source=project/'app/src/main/java/org/wangshu/scan/MainActivity.java'
s=source.read_text(encoding='utf-8')
method=s[s.index('    private String pdfRevision('):s.index('    private void previewPdf(',s.index('    private String pdfRevision('))]
java=r'''
import java.util.*;
public class PdfRevisionHost {
__METHOD__
 static String old(Models.Document d){StringBuilder s=new StringBuilder(d.id).append(d.title);for(Models.Page p:d.active())s.append(p.id).append(OcrEngine.key(p));return s.toString();}
 static int count;static void check(boolean x,String s){if(!x)throw new AssertionError(s);count++;}
 public static void main(String[]args){PdfRevisionHost h=new PdfRevisionHost();Models.Document original=new Models.Document();original.title="notes";Models.Page p=new Models.Page();p.id="a".repeat(36);p.key="ocr-key-v2|64:"+"b".repeat(64);original.pages.add(p);
 Models.Document changed=new Models.Document();changed.title=original.title+p.id+OcrEngine.key(p);
 check(old(original).equals(old(changed)),"old title absorbs removed page");check(!h.pdfRevision(original).equals(h.pdfRevision(changed)),"title and page framed");
 String base=h.pdfRevision(original);check(base.equals(h.pdfRevision(original)),"stable");p.key+="x";check(!base.equals(h.pdfRevision(original)),"image edit invalidates");p.key=p.key.substring(0,p.key.length()-1);p.id="c".repeat(36);check(!base.equals(h.pdfRevision(original)),"page identity invalidates");
 p.id="a".repeat(36);Models.Page second=new Models.Page();second.id="d".repeat(36);second.key="other";original.pages.add(second);String two=h.pdfRevision(original);Collections.reverse(original.pages);check(!two.equals(h.pdfRevision(original)),"page order invalidates");original.pages.remove(0);check(base.equals(h.pdfRevision(original)),"removing second restores original revision");p.cropReview="confirmed";check(!base.equals(h.pdfRevision(original)),"crop review invalidates cached PDF");p.cropReview="";check(base.equals(h.pdfRevision(original)),"restored crop review stabilizes revision");System.out.println("PASS "+count+" checks; actual extracted revision methods and fake model; no Android/PDF run");}
}
class OcrEngine {static String key(Models.Page p){return p.key;}}
class Models {static class Page{String id,key,cropReview="";} static class Document{String id="1".repeat(36),title="";ArrayList<Page> pages=new ArrayList<>();ArrayList<Page> active(){return pages;}}}
'''.replace('__METHOD__',method)

p=run_java(java)
print(p.stdout,end="");print(p.stderr,end="",file=sys.stderr)
raise SystemExit(p.returncode)
