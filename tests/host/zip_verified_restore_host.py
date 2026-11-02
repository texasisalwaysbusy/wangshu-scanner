#!/usr/bin/env python3
"""Production restore against real ZIP/files and small retained synthetic fixtures.

The temporary fixture directory is printed and intentionally NOT deleted. JSON,
page model, save, bitmap and JPEG are stubs; all archive reads, ZipFile,
ZipInputStream, checksum and file copies execute the extracted production method.
"""
from pathlib import Path
from support import PROJECT, OUTPUT, run_java
import base64, hashlib, io, os, struct, subprocess, sys, tempfile, warnings, zipfile
root=PROJECT
src=(root/'app/src/main/java/org/wangshu/scan/Store.java').read_text()
method=src[src.index('    synchronized Models.Document restore('):src.index('    private static void removeTemporary(')]
validator=(root/'app/src/main/java/org/wangshu/scan/StrictJsonValidator.java').read_text()
validator='\n'.join(line for line in validator.splitlines() if not line.startswith(('package ','import ')))
validator=validator.replace('final class StrictJsonValidator {','static final class StrictJsonValidator {',1)
page='11111111-1111-1111-1111-111111111111';name='originals/'+page+'.image'
image=bytes.fromhex('ffd801ffd9')
def make(extra=None,picture=image,manifest=b'{}',compression=zipfile.ZIP_STORED):
 b=io.BytesIO()
 with warnings.catch_warnings():
  warnings.simplefilter('ignore',UserWarning)
  with zipfile.ZipFile(b,'w',compression=compression) as z:
   z.writestr('manifest.json',manifest);z.writestr(name,picture)
   if extra:z.writestr(extra,b'x')
 return b.getvalue()
good=make();pos=good.index(b'PK\x01\x02');secondlocal=good.index(b'PK\x03\x04',4)
missing=good[:pos];partial=good[:good.index(image)+2]
mut=bytearray(good);mut[pos+16]^=1;badcrc=bytes(mut)
mut=bytearray(good);compressed=struct.unpack('<I',mut[pos+20:pos+24])[0];mut[pos+20:pos+24]=struct.pack('<I',compressed+1);badcompressed=bytes(mut)
deflated=make(compression=zipfile.ZIP_DEFLATED);dpos=deflated.index(b'PK\x01\x02');mut=bytearray(deflated);compressed=struct.unpack('<I',mut[dpos+20:dpos+24])[0];mut[dpos+20:dpos+24]=struct.pack('<I',compressed+1);deflate_mismatch=bytes(mut)
class NonSeekable(io.RawIOBase):
 def __init__(self): self.data=bytearray()
 def writable(self): return True
 def write(self,chunk): self.data.extend(chunk);return len(chunk)
stream=NonSeekable()
with zipfile.ZipFile(stream,'w',compression=zipfile.ZIP_DEFLATED) as z:
 z.writestr('manifest.json',b'{}');z.writestr(name,image)
descriptor_zip=bytes(stream.data)
mut=bytearray(good);mut[30+7]=ord('x');badlocal=bytes(mut)
mut=bytearray(good);mut[pos+42:pos+46]=struct.pack('<I',secondlocal);badoffset=bytes(mut)
encoded=lambda b:base64.b64encode(b).decode()
java=r'''import java.io.*;import java.nio.*;import java.nio.charset.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;import java.security.*;
public class ZipVerifiedProbe {
 static File base;static boolean uncertain=false;static int saved=0;static String expected="";
 static class Context {File getFilesDir(){return base;}}final Context context=new Context();
 static class JSONObject{JSONObject(String s){}}
 static class Models {static class Page {String id="11111111-1111-1111-1111-111111111111",file="originals/"+id+".image",sha256=expected;}static class Document {String id,title="doc";boolean archived;List<Page> pages=new ArrayList<>();static Document from(JSONObject x){Document d=new Document();d.pages.add(new Page());return d;}}}
 static class ManifestFence {static class Uncertain extends IOException {Uncertain(){super("uncertain");}}}
 static class CameraInbox {static void requireCompleteIfJpeg(File f)throws IOException{byte[] b=Files.readAllBytes(f.toPath());if(b.length>=2&&(b[0]&255)==255&&(b[1]&255)==216&&(b.length<4||(b[b.length-2]&255)!=255||(b[b.length-1]&255)!=217))throw new IOException("JPEG truncated");}}
 static class BitmapFactory {static class Options{boolean inJustDecodeBounds;int outWidth=10,outHeight=10;}static void decodeFile(String p,Options o){}}
 static byte[] readLimited(InputStream in,int max)throws IOException{byte[] a=in.readNBytes(max+1);if(a.length>max)throw new IOException("large");return a;}
 static void safeId(String s)throws IOException {if(!s.matches("[a-f0-9-]{36}"))throw new IOException("id");}
 static String hex(byte[] b){return HexFormat.of().formatHex(b);}File dir(Models.Document d){return new File(base,"documents/"+d.id);}void save(Models.Document d)throws Exception{if(uncertain)throw new ManifestFence.Uncertain();saved++;}
''' + validator + method + r'''
 static void ok(boolean yes,String label){if(!yes)throw new AssertionError(label);System.out.println("PASS "+label);}static byte[] data(String s){return Base64.getDecoder().decode(s);}static void reset(byte[] image)throws Exception{base=new File(ZipVerifiedProbe.temp,UUID.randomUUID().toString());if(!base.mkdirs())throw new IOException("mkdir");saved=0;uncertain=false;expected=hex(MessageDigest.getInstance("SHA-256").digest(image));}static File temp;
 static File[] stages(){File[] x=new File(base,"restore-intake").listFiles();return x==null?new File[0]:x;}static boolean rawPresent(){File[] x=stages();return x.length==1&&new File(x[0],"received.zip").isFile();}static boolean copied(){File x=new File(base,"documents");return x.exists()&&x.list()!=null&&x.list().length>0;}
 public static void main(String[] x)throws Exception{temp=new File(x[0]);ZipVerifiedProbe s=new ZipVerifiedProbe();byte[] good=data("@@GOOD@@"),img=data("@@IMG@@");
 reset(img);Models.Document d=s.restore(new ByteArrayInputStream(good));ok(saved==1&&Arrays.equals(Files.readAllBytes(new File(s.dir(d),d.pages.get(0).file).toPath()),img)&&rawPresent(),"success publishes exact bytes and retains synthetic raw");
 reset(img);s.restore(new ByteArrayInputStream(data("@@DEFLATED@@")));ok(saved==1,"valid deflated ZIP accepted");
 reset(img);s.restore(new ByteArrayInputStream(data("@@DESCRIPTOR@@")));ok(saved==1,"valid streaming data-descriptor ZIP accepted");
 String[] fixtures={"@@MISSING@@","@@PARTIAL@@","@@BADCRC@@","@@BADLOCAL@@","@@BADOFFSET@@","@@DUP@@","@@PATH@@","@@BADJPEG@@","@@UNREFERENCED@@","@@JSONDUP@@","@@JSONTAIL@@","@@BADUTF8@@","@@BADCOMPRESSED@@"};String[] labels={"missing central directory","truncated archive","central CRC mismatch","local name mismatch","central offset mismatch","duplicate entry","invalid path","truncated JPEG","unreferenced original","duplicate manifest key","trailing manifest content","invalid UTF-8 manifest","central compressed length mismatch"};
 for(int i=0;i<fixtures.length;i++){reset(i==7?data("@@CUT@@"):img);try{s.restore(new ByteArrayInputStream(data(fixtures[i])));throw new AssertionError(labels[i]+" accepted");}catch(IOException e){ok(saved==0&&!copied()&&rawPresent(),labels[i]+" rejected; raw retained");}}
 reset(img);try{s.restore(new ByteArrayInputStream(data("@@DEFLATEMISMATCH@@")));System.out.println("LIMITATION central deflate compressed length mismatch accepted");}catch(IOException e){ok(saved==0&&!copied()&&rawPresent(),"central deflate compressed length mismatch rejected; raw retained");}
 reset(img);try{s.restore(new FilterInputStream(new ByteArrayInputStream(good)){int n;public int read(byte[] b,int o,int l)throws IOException{if(n>=40)throw new InterruptedIOException("cancel");int r=super.read(b,o,Math.min(l,40-n));if(r>0)n+=r;return r;}public int read()throws IOException{if(n>=40)throw new InterruptedIOException("cancel");int r=super.read();if(r>=0)n++;return r;}});throw new AssertionError();}catch(InterruptedIOException e){File raw=new File(stages()[0],"received.zip");ok(saved==0&&!copied()&&raw.isFile()&&raw.length()==40,"interrupted one-shot stream retains received prefix, not full archive");}
 reset(img);uncertain=true;try{s.restore(new ByteArrayInputStream(good));throw new AssertionError();}catch(ManifestFence.Uncertain e){ok(saved==0&&copied()&&rawPresent(),"uncertain save retains raw and copied image");}
 }}'''
fixtures={'GOOD':good,'IMG':image,'MISSING':missing,'PARTIAL':partial,'BADCRC':badcrc,'BADLOCAL':badlocal,'BADOFFSET':badoffset,'DUP':make(name),'PATH':make('../outside'),'BADJPEG':make(picture=bytes.fromhex('ffd801')),'CUT':bytes.fromhex('ffd801'),'UNREFERENCED':make('originals/22222222-2222-2222-2222-222222222222.image'),'JSONDUP':make(manifest=b'{"a":1,"a":2}'),'JSONTAIL':make(manifest=b'{}junk'),'BADUTF8':make(manifest=b'{"x":"\xff"}'),'BADCOMPRESSED':badcompressed,'DEFLATEMISMATCH':deflate_mismatch,'DEFLATED':deflated,'DESCRIPTOR':descriptor_zip}
for k,v in fixtures.items():java=java.replace('@@'+k+'@@',encoded(v))
work=OUTPUT/'zip-fixtures';work.mkdir()

p=run_java(java,args=[work])
print(p.stdout,end="");print(p.stderr,end="",file=sys.stderr)
raise SystemExit(p.returncode)
