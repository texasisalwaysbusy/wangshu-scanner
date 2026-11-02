#!/usr/bin/env python3
"""Run production detector in a JVM against small synthetic RGB fixtures."""
from pathlib import Path
from support import PROJECT, OUTPUT, run_java
import base64, hashlib, os, subprocess, sys
root=PROJECT
names=['Geometry','Enhance','PageDetector']
sources={}
for name in names:
 path=root/('app/src/main/java/org/wangshu/scan/core/'+name+'.java')
 raw=path.read_text()
 sources[name]=raw
code='\n'.join(sources[n].replace('package org.wangshu.scan.core;','').replace('public final class '+n,'final class '+n,1) for n in names)
java='''import java.io.*;import java.util.*;public class PageDetectProbe {
 public static void main(String[] args)throws Exception {BufferedReader r=new BufferedReader(new InputStreamReader(System.in));String line;
 while((line=r.readLine())!=null){byte[] b=Base64.getDecoder().decode(line);int[] p=new int[b.length/3];for(int i=0;i<p.length;i++)p[i]=0xff000000|((b[3*i]&255)<<16)|((b[3*i+1]&255)<<8)|(b[3*i+2]&255);System.out.println(Arrays.toString(PageDetector.detect(p,160,160)));}}}
''' + code
page=(25,20,135,140)
def make(bg=65,paper=230,shadow=None,text=False):
 pixels=bytearray([bg]*(160*160*3))
 def rectangle(box,value):
  x0,y0,x1,y1=box
  for y in range(y0,y1+1):
   for x in range(x0,x1+1):pixels[(y*160+x)*3:(y*160+x)*3+3]=bytes([value]*3)
 rectangle(page,paper)
 if shadow is not None:rectangle((25,91,135,140),shadow)
 if text:
  for y in range(35,130,12):
   for x in range(36,120,12):rectangle((x,y,x+5,y+2),10)
 return pixels
images={'clear':make(),'dark_text':make(text=True),'shadow_lowcontrast':make(shadow=90),'dark_bg_shadow':make(bg=40,shadow=62),'very_low_contrast':make(shadow=70),'indistinguishable_shadow':make(shadow=65)}
original=images['dark_text']; rotated=bytearray(len(original))
for y in range(160):
 for x in range(160):rotated[((159-x)*160+y)*3:((159-x)*160+y)*3+3]=original[(y*160+x)*3:(y*160+x)*3+3]
images['rotated_text']=rotated
inp=''.join(base64.b64encode(im).decode()+'\n' for im in images.values())
p=run_java(java,input=inp)
if p.returncode:print(p.stderr,file=sys.stderr);raise SystemExit(p.returncode)
lines=p.stdout.strip().splitlines();assert len(lines)==len(images)
results={k:[float(x) for x in line.strip('[]').split(',')] for k,line in zip(images,lines)}
for name,q in results.items():print(name, q)
full=[0,0,1,0,1,1,0,1]
target=[25/159,20/159,135/159,20/159,135/159,140/159,25/159,140/159]
rotated=[20/159,24/159,140/159,24/159,140/159,134/159,20/159,134/159]
for n,expected in [('clear',target),('dark_text',target),('rotated_text',rotated)]:
 error=max(abs(x-y) for x,y in zip(results[n],expected))
 if error>.015:print('FAIL known paper geometry',n,'max error',error);raise SystemExit(1)
 print('PASS known paper geometry',n,'max normalized error',error)
for n in ['shadow_lowcontrast','dark_bg_shadow','very_low_contrast']:
 q=results[n]
 if q!=full and max(q[5],q[7])<.85:
  print('FAIL unsafe high-confidence crop excludes lower sheet:',n);raise SystemExit(1)
 print('PASS shadow retains full sheet',n,'fallback',q==full)
q=results['indistinguishable_shadow']
if q!=full and max(q[5],q[7])<.85:
 print('LIMITATION background-equal shadow still cut; detector cannot guarantee safety')
else:print('OBSERVED background-equal shadow retained')
print('Detector SHA-256:',hashlib.sha256(sources['PageDetector'].encode()).hexdigest())
print('Synthetic generated images only; not real-paper/phone/Android validation.')
