package org.wangshu.scan;
import java.io.*;import java.nio.file.*;import java.util.*;
public class IntakeHost{
 static int count;static void ok(boolean v,String m){if(!v)throw new AssertionError(m);count++;System.out.println("PASS "+m);}interface Op{void run()throws Exception;}static void fail(Op p,String m)throws Exception{try{p.run();throw new AssertionError("unexpected success "+m);}catch(IOException expected){count++;System.out.println("PASS "+m);}}
 static void failContains(Op p,String word)throws Exception{try{p.run();throw new AssertionError("unexpected success");}catch(IOException e){ok(e.getMessage().contains(word),"partial copy warning names retained cache");}}
 public static void main(String[] args)throws Exception{
  Path base=Path.of(args[0]),files=base.resolve("files"),cache=base.resolve("cache"),root=files.resolve("restore-intake");Files.createDirectories(root);Files.createDirectories(cache);String name="restore-"+UUID.randomUUID();Path stage=root.resolve(name);Files.createDirectory(stage);Path received=stage.resolve("received.zip");byte[] bytes="partial ZIP bytes".getBytes();Files.write(received,bytes);
  RestoreIntake intake=new RestoreIntake(files,cache);List<RestoreIntake.Entry> entries=intake.list();ok(entries.size()==1&&entries.get(0).selectable()&&entries.get(0).bytes==bytes.length,"read-only list identifies received fragment");var snap=intake.inspect(entries.get(0));File copy=intake.copy(snap);ok(Arrays.equals(Files.readAllBytes(copy.toPath()),bytes)&&Arrays.equals(Files.readAllBytes(received),bytes),"explicit copy preserves source bytes");ok(copy.getName().endsWith(".bin"),"fragment output does not claim complete ZIP");
  var snap2=intake.inspect(entries.get(0));var oldTime=Files.getLastModifiedTime(received);byte[] altered=bytes.clone();altered[0]^=1;Files.write(received,altered);Files.setLastModifiedTime(received,oldTime);failContains(()->intake.copy(snap2),"缓存");ok(Arrays.equals(Files.readAllBytes(received),altered),"hash failure preserves changed source");
  Files.write(received,"different contents".getBytes());fail(()->intake.copy(snap),"changed source after inspection rejected");ok(Files.exists(received),"source preserved after failure");
  Path other=base.resolve("other");Files.createDirectory(other);Files.write(other.resolve("received.zip"),bytes);Path link=root.resolve("restore-"+UUID.randomUUID());Files.createSymbolicLink(link,other);var all=intake.list();ok(all.stream().anyMatch(e->e.name.equals(link.getFileName().toString())&&!e.selectable()),"symlink stage listed unsafe, not followed");
  Path bad=root.resolve("restore-"+UUID.randomUUID());Files.createDirectory(bad);Files.createSymbolicLink(bad.resolve("received.zip"),received);all=intake.list();ok(all.stream().anyMatch(e->e.name.equals(bad.getFileName().toString())&&!e.selectable()),"symlink received file listed unsafe");
  Thread.currentThread().interrupt();try{fail(()->intake.list(),"cancelled listing stops");}finally{Thread.interrupted();}ok(Files.exists(received),"cancel never deletes source");
  System.out.println("PASS "+count+" real RestoreIntake host checks; Android NOT_RUN");
 }
}
