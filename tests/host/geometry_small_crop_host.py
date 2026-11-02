#!/usr/bin/env python3
"""Execute actual Geometry in JVM memory, with no Android classes or image files."""
from pathlib import Path
from support import PROJECT, OUTPUT, run_java
import hashlib, os, subprocess, sys
root=PROJECT
src=(root/'app/src/main/java/org/wangshu/scan/core/Geometry.java').read_text()
source=src.replace('package org.wangshu.scan.core;','').replace('public final class Geometry','final class Geometry',1)
region=(root/'app/src/main/java/org/wangshu/scan/core/RegionRenderPlan.java').read_text()
region_source=region.replace('package org.wangshu.scan.core;','').replace('public final class RegionRenderPlan','final class RegionRenderPlan',1)
java='''import java.util.*; public class GeometryCropProbe {
 static void ok(boolean b,String s){if(!b)throw new AssertionError(s);System.out.println("PASS "+s);}
 static void near(double a,double b){if(Math.abs(a-b)>0.00001)throw new AssertionError(a+" != "+b);}
 public static void main(String[] x){
 double[] q={.4,.4,.5,.4,.5,.5,.4,.5};
 ok(Geometry.valid(q),"1-percent crop on 10000x10000 remains selectable");
 int[] size=Geometry.outputSize(q,10000,10000,4000);ok(size[0]==1000&&size[1]==1000,"one million source pixels preserved in plan");
 RegionRenderPlan plan=RegionRenderPlan.create(10000,10000,1,q,0,4000);ok(plan.width==1000&&plan.height==1000&&plan.right-plan.left==1000&&plan.bottom-plan.top==1000,"real region plan retains 1000x1000 raw region");
 RegionRenderPlan rotated=RegionRenderPlan.create(10000,10000,1,q,1,4000);ok(rotated.width==1000&&rotated.height==1000&&rotated.tileCount==plan.tileCount,"90-degree output plan preserves small-region dimensions");
 double[] m=Geometry.inverse(q,10000,10000,1000,1000),a=Geometry.map(m,0,0),b=Geometry.map(m,999,999);near(a[0],3999.6);near(a[1],3999.6);near(b[0],4999.5);near(b[1],4999.5);ok(true,"inverse maps opposite corners accurately");
 double[] small={.3,.3,.34,.301,.341,.341,.299,.34};ok(Geometry.valid(small),"small convex perspective quad selectable");
 int[] dims=Geometry.outputSize(small,10000,10000,4000);double[] p=Geometry.inverse(small,10000,10000,dims[0],dims[1]);double[][] corners={{0,0},{dims[0]-1,0},{dims[0]-1,dims[1]-1},{0,dims[1]-1}};
 for(int i=0;i<4;i++){double[] mapped=Geometry.map(p,corners[i][0],corners[i][1]);near(mapped[0],small[2*i]*9999);near(mapped[1],small[2*i+1]*9999);}double[] mid=Geometry.map(p,(dims[0]-1)/2.,(dims[1]-1)/2.);ok(Double.isFinite(mid[0])&&Double.isFinite(mid[1]),"perspective corner maps and center remain finite");
 ok(!Geometry.valid(new double[]{.4,.4,.5,.5,.5,.4,.4,.5}),"crossing rejected");
 ok(!Geometry.valid(new double[]{.4,.4,.4,.4,.5,.5,.4,.5}),"duplicate corner rejected");
 ok(!Geometry.valid(new double[]{.4,.4,.5,.4,.5,.400000001,.4,.400000001}),"nearly collinear crop rejected");
 ok(!Geometry.valid(new double[]{-0.1,0,1,0,1,1,0,1}),"outside coordinates rejected");
 }}
''' + source + region_source

p=run_java(java)
print(p.stdout,end="");print(p.stderr,end="",file=sys.stderr)
raise SystemExit(p.returncode)
