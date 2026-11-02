package org.wangshu.scan;
import java.io.*;
import java.nio.file.*;
public class Utf8Green {
 public static void main(String[] args)throws Exception {
  Path files=Files.createDirectory(Path.of(args[0]));
  EditDraftStore store=new EditDraftStore(files.toFile());
  String owner="11111111-1111-4111-8111-111111111111";
  int rejected=0;
  for(String invalid:new String[]{"bad-\uD800-end", "bad-\uDC00-end"}){
   for(boolean baseline:new boolean[]{true,false}){
    try{store.save(owner,baseline?invalid:"valid",baseline?"valid":invalid);throw new AssertionError("invalid surrogate accepted");}
    catch(IOException expected){rejected++;}
    if(Files.exists(files.resolve("edit-drafts")))throw new AssertionError("rejected encode created drafts directory");
   }
  }
  System.out.println("PASS "+rejected+" lone-surrogate baseline/edited cases rejected before directory creation");
  String[] valid={"ASCII 123", "中文识别文字", "supplementary-\uD83D\uDE00-end", "combining-e\u0301-a\u0323\u0301", "mixed-中文-\uD83D\uDE00-e\u0301"};
  for(String value:valid){
   EditDraftStore.Entry entry=store.save(owner,value,"edited-"+value);
   EditDraftStore.Draft loaded=store.load(owner,entry);
   if(!entry.valid||!value.equals(loaded.baseline)||!("edited-"+value).equals(loaded.edited))throw new AssertionError("valid Unicode changed");
  }
  if(store.list(owner).size()!=valid.length)throw new AssertionError("unexpected retained records");
  System.out.println("PASS "+valid.length+" valid ASCII/Chinese/supplementary/combining/mixed exact roundtrips");
 }
}
