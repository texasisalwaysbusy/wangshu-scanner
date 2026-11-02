package org.wangshu.scan;
import java.io.*;import java.nio.file.*;import java.util.*;
public class OcrAssetsHost {
 static int checks;interface Op{void run()throws Exception;}static void ok(boolean v,String m){if(!v)throw new AssertionError(m);checks++;}
 static void reject(Op o)throws Exception{try{o.run();throw new AssertionError("accepted invalid asset");}catch(IOException expected){checks++;}}
 public static void main(String[]args)throws Exception{
  Path base=Files.createDirectories(Path.of(args[0]));File directory=OcrAssetIntegrity.prepareDirectory(base.toFile());ok(directory.toPath().equals(base.resolve("ocr/tessdata")),"directory layout");
  int[] opened={0};OcrAssetIntegrity.Source source=n->{opened[0]++;return new ByteArrayInputStream(new byte[]{1,2,3});};
  reject(()->OcrAssetIntegrity.prepare(directory,"unknown",source));ok(opened[0]==0,"unknown name opened source");
  Path eng=directory.toPath().resolve("eng.traineddata");Files.write(eng,new byte[0]);reject(()->OcrAssetIntegrity.prepare(directory,"eng.traineddata",source));ok(opened[0]==0&&Files.size(eng)==0,"existing empty cache was replaced");
  reject(()->OcrAssetIntegrity.prepare(directory,"chi_sim.traineddata",source));ok(opened[0]==1&&!Files.exists(directory.toPath().resolve("chi_sim.traineddata")),"bad source published");
  try(var files=Files.list(directory.toPath())){ok(files.anyMatch(p->p.getFileName().toString().startsWith("chi_sim.traineddata-")&&p.getFileName().toString().endsWith(".part")),"failed prefix not retained");}
  for(int i=0;i<4;i++)Files.write(directory.toPath().resolve("pdf.ttf-"+i+".part"),new byte[]{0});
  reject(()->OcrAssetIntegrity.prepare(directory,"pdf.ttf",source));ok(opened[0]==1,"fragment cap opened new source");
  Thread.currentThread().interrupt();try{reject(()->OcrAssetIntegrity.prepare(directory,"eng.traineddata",source));ok(Thread.currentThread().isInterrupted(),"interrupt flag lost");}finally{Thread.interrupted();}
  Path link=base.resolve("model-link");Files.createSymbolicLink(link,eng);reject(()->OcrAssetIntegrity.verify(link.toFile(),"eng.traineddata"));
  System.out.println("PASS "+checks+" actual OCR helper negative/cancellation/retention checks; synthetic invalid assets only, valid pinned model and JNI NOT RUN");
 }
}
