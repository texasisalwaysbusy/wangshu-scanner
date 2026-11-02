#!/usr/bin/env python3
"""Exercise the actual OCR key method with a host JVM; no Android runtime."""
from pathlib import Path
from support import PROJECT, OUTPUT, run_java
import hashlib
import os
import subprocess
import sys

project = PROJECT
source = project / "app/src/main/java/org/wangshu/scan/OcrEngine.java"
content = source.read_text(encoding="utf-8")
start = content.index("    static String key(Models.Page p)")
end = content.index("    private static TessBaseAPI open(", start)
method = content[start:end]
java = r'''
import java.util.*;
public class OcrKeyMemoryHost {
__METHOD__
  static String legacy(Models.Page p) {
    return p.sha256+Arrays.toString(p.quad)+p.rotation+p.mode+p.brightness+p.bias+p.shadows;
  }
  static void check(boolean ok,String label) {
    if(!ok) throw new AssertionError(label);
    System.out.println("PASS "+label);
  }
  public static void main(String[] args) {
    Models.Page p=new Models.Page();
    p.brightness=1;p.bias=11;String oldFirst=legacy(p),newFirst=key(p);
    p.brightness=11;p.bias=1;
    check(oldFirst.equals(legacy(p)),"reproduce old brightness/bias collision");
    check(!newFirst.equals(key(p)),"framing distinguishes collision pair");
    p.ocrKey=legacy(p);
    check(!p.ocrKey.equals(key(p)),"legacy OCR key is stale for valid SHA-256");
    HashSet<String> keys=new HashSet<>();int count=0;
    for(String mode:new String[]{"COLOR","GRAY","BW"})
      for(int rotation=0;rotation<4;rotation++)
        for(boolean shadows:new boolean[]{false,true})
          for(int brightness=-40;brightness<=40;brightness++)
            for(int bias=1;bias<=35;bias++){
              p.mode=mode;p.rotation=rotation;p.shadows=shadows;
              p.brightness=brightness;p.bias=bias;
              if(!keys.add(key(p))) throw new AssertionError("key collision");
              if(legacy(p).equals(key(p))) throw new AssertionError("legacy key matched");
              count++;
            }
    check(count==68040 && keys.size()==count,"68040 valid mode/rotation/shadow/brightness/bias combinations unique");
    p=new Models.Page();String base=key(p);
    p.sha256="b".repeat(64);check(!base.equals(key(p)),"original hash affects key");
    p=new Models.Page();p.quad[0]=0.1;check(!base.equals(key(p)),"crop coordinates affect key");
    p=new Models.Page();p.rotation=1;check(!base.equals(key(p)),"rotation affects key");
    p=new Models.Page();p.mode="GRAY";check(!base.equals(key(p)),"mode affects key");
    p=new Models.Page();p.brightness=1;check(!base.equals(key(p)),"brightness affects key");
    p=new Models.Page();p.bias=9;check(!base.equals(key(p)),"bias affects key");
    p=new Models.Page();p.shadows=true;check(!base.equals(key(p)),"shadow setting affects key");
    p=new Models.Page();check(base.equals(key(p)),"same inputs yield stable key");
    System.out.println("HOST ONLY: actual extracted key; fake page, no Android/OCR/PDF/device run");
  }
}
class Models {
  static final class Page {
    String sha256="a".repeat(64), mode="COLOR", ocrKey="";
    double[] quad={0,0,1,0,1,1,0,1};
    int rotation=0, brightness=0, bias=8;
    boolean shadows=false;
  }
}
'''.replace("__METHOD__", method)

p=run_java(java)
print(p.stdout,end="");print(p.stderr,end="",file=sys.stderr)
raise SystemExit(p.returncode)
