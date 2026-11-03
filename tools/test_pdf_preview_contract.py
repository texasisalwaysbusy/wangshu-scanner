"""Host/structural checks only. No Android stubs, device execution, or PDF rendering claims.
The Java host wrapper uses the exact production resolveExport body and sizing statements.
All generated fixtures remain under the candidate's evidence directory; nothing is deleted.
"""
from pathlib import Path
import hashlib,json,os,re,subprocess,unittest,xml.etree.ElementTree as ET

PROJECT=Path(__file__).resolve().parents[1]
OUTPUT=Path(os.environ.get('SCANNER_TEST_OUTPUT', PROJECT/'build'/('pdf-contract-'+__import__('uuid').uuid4().hex))).resolve()
SOURCE=PROJECT/'app/src/main/java/org/wangshu/scan/PdfPreviewActivity.java'
TEXT=SOURCE.read_text(encoding='utf-8')

class PdfPreviewStaticChecks(unittest.TestCase):
    def test_open_and_render_both_catch_out_of_memory(self):
        self.assertEqual(TEXT.count('catch(Exception|OutOfMemoryError e)'),2)
        self.assertIn('failure("无法打开 PDF，请返回重新生成。",e)',TEXT)
    def test_directory_symlink_guard_precedes_child_open(self):
        self.assertIn('File rawBase=new File(cache,"exports");File base=rawBase.getCanonicalFile();',TEXT)
        a=TEXT.index('java.nio.file.Files.isSymbolicLink(rawBase.toPath())')
        b=TEXT.index('!base.getParentFile().equals(cache.getCanonicalFile())')
        c=TEXT.index('File file=new File(base,name);')
        self.assertLess(a,c);self.assertLess(b,c)
        self.assertIn('java.nio.file.Files.isSymbolicLink(file.toPath())',TEXT)
    def test_descriptor_is_read_only_and_transferred_once(self):
        self.assertEqual(TEXT.count('ParcelFileDescriptor.open('),1)
        self.assertIn('ParcelFileDescriptor.MODE_READ_ONLY',TEXT)
        self.assertIn('renderer=new PdfRenderer(fd);fd=null;',TEXT)
        self.assertIn('finally{if(fd!=null)try{fd.close();}',TEXT)
    def test_page_is_worker_owned_and_auto_closed(self):
        body=TEXT.split('private void render(int target)',1)[1]
        self.assertLess(body.index('worker.execute('),body.index('renderer.openPage(target)'))
        self.assertIn('try(PdfRenderer.Page page=renderer.openPage(target))',body)
    def test_destroy_closes_on_worker_before_shutdown(self):
        body=TEXT.split('@Override protected void onDestroy()',1)[1]
        self.assertLess(body.index('closed=true;'),body.index('worker.execute('))
        self.assertLess(body.index('worker.execute('),body.index('renderer.close()'))
        self.assertLess(body.index('renderer.close()'),body.index('worker.shutdown();'))
        self.assertNotIn('shutdownNow(',body)
    def test_only_unpublished_or_failed_bitmaps_recycled(self):
        self.assertIn('if(closed){ready.recycle();return;}shown=ready;image.setImageBitmap(ready)',TEXT)
        self.assertIn('finally{if(bitmap!=null)bitmap.recycle();}',TEXT)
        self.assertNotIn('shown.recycle()',TEXT)
        self.assertEqual(TEXT.count('.recycle()'),2)
        self.assertIn('private volatile boolean closed;',TEXT)
    def test_late_callbacks_check_closed(self):
        self.assertIn('runOnUiThread(()->{if(closed)return;count=pages;',TEXT)
        self.assertIn('runOnUiThread(()->{if(closed)return;busy=false;',TEXT)
    def test_bounds_and_saved_page(self):
        self.assertIn('if(closed||busy||target<0||target>=count)return;',TEXT)
        self.assertIn('Math.max(0,Math.min(requested,pages-1))',TEXT)
        self.assertIn('out.putInt("page",index)',TEXT)
    def test_activity_private_no_new_permission(self):
        m=ET.parse(PROJECT/'app/src/main/AndroidManifest.xml').getroot();ns='{http://schemas.android.com/apk/res/android}'
        a=[a for a in m.find('application').findall('activity') if a.get(ns+'name')=='.PdfPreviewActivity'];self.assertEqual(len(a),1);self.assertEqual(a[0].get(ns+'exported'),'false')
        self.assertEqual([p.get(ns+'name') for p in m.findall('uses-permission')],['android.permission.CAMERA'])
    def test_preview_checks_revision_before_launch(self):
        main=(PROJECT/'app/src/main/java/org/wangshu/scan/MainActivity.java').read_text(encoding='utf-8');body=main.split('private void previewPdf()',1)[1].split('private void reusePdf(',1)[0]
        self.assertIn('store.snapshotForExport(owner)',body);self.assertIn('requireReviewedCrops(checked)',body)
        self.assertIn('document!=owner||!revision.equals(pdfRevision(owner))',body)
        self.assertIn('((File)value).getName()',body)

JAVA=r'''import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class PdfPreviewHostChecks {
__RESOLVER__
static int[] size(int w,int h)throws IOException{__SIZING__ return new int[]{width,height};}
static int passed=0;static ArrayList<String> skips=new ArrayList<>();
static void check(boolean v,String label){if(!v)throw new AssertionError(label);passed++;}
static void reject(File cache,String name)throws Exception{try{resolveExport(cache,name);throw new AssertionError("Accepted invalid name/path: "+name);}catch(IOException expected){passed++;}}
public static void main(String[] args)throws Exception{
 File root=new File(args[0]);check(root.mkdir(),"new retained fixture root");File cache=new File(root,"cache");File exports=new File(cache,"exports");check(exports.mkdirs(),"exports fixture");
 String id="12345678-1234-5678-9abc-123456789abc";
 for(String name:new String[]{"scan-"+id+".pdf","searchable-"+id+".pdf","scan-12345678-ABCD-5678-9ABC-123456789ABC.pdf"}){
  File f=new File(exports,name);Files.write(f.toPath(),new byte[]{37,80,68,70});check(resolveExport(cache,name).getCanonicalFile().equals(f.getCanonicalFile()),"valid completed export");
 }
 for(String bad:new String[]{null,"","../scan-"+id+".pdf","..\\scan-"+id+".pdf","/scan-"+id+".pdf","C:\\scan-"+id+".pdf","scan-"+id+".part","scan-"+id+".PDF","scan-no-uuid.pdf","scan-"+id+".pdf ","searchable-export-"+id+".pdf","SCAN-"+id+".pdf","scan-"+id+".pdf/child","scan-"+id+".pdf\\child","scan-"+id+".pdf\u0000"})reject(cache,bad);
 reject(cache,"scan-00000000-0000-0000-0000-000000000000.pdf");
 String directoryName="scan-11111111-1111-1111-1111-111111111111.pdf";check(new File(exports,directoryName).mkdir(),"pdf-named directory fixture");reject(cache,directoryName);
 File outside=new File(root,"outside");check(outside.mkdir(),"own outside fixture");File target=new File(outside,"target.pdf");Files.write(target.toPath(),new byte[]{37,80,68,70});
 String linkName="scan-22222222-2222-2222-2222-222222222222.pdf";
 try{Files.createSymbolicLink(new File(exports,linkName).toPath(),target.toPath());reject(cache,linkName);}catch(UnsupportedOperationException|FileSystemException|SecurityException denied){skips.add("file symlink creation unsupported/denied: "+denied.getClass().getSimpleName());}
 File linkCache=new File(root,"directory-link-cache");check(linkCache.mkdir(),"directory-link cache fixture");File outsideExport=new File(outside,"exports");check(outsideExport.mkdir(),"outside exports fixture");Files.write(new File(outsideExport,"scan-"+id+".pdf").toPath(),new byte[]{37,80,68,70});
 try{Files.createSymbolicLink(new File(linkCache,"exports").toPath(),outsideExport.toPath());reject(linkCache,"scan-"+id+".pdf");}catch(UnsupportedOperationException|FileSystemException|SecurityException denied){skips.add("directory symlink creation unsupported/denied: "+denied.getClass().getSimpleName());}
 for(int[] pair:new int[][]{{0,1},{1,0},{-1,1},{1,-1},{Integer.MIN_VALUE,1},{Integer.MAX_VALUE,0}}){try{size(pair[0],pair[1]);throw new AssertionError("accepted nonpositive dimension");}catch(IOException expected){passed++;}}
 ArrayList<int[]> dimensions=new ArrayList<>();Collections.addAll(dimensions,new int[]{1,1},new int[]{1,Integer.MAX_VALUE},new int[]{Integer.MAX_VALUE,1},new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE},new int[]{1600,1600},new int[]{1000000,2},new int[]{2,1000000},new int[]{595,842},new int[]{842,595});Random random=new Random(17);
 for(int i=0;i<1000;i++)dimensions.add(new int[]{1+random.nextInt(Integer.MAX_VALUE-1),1+random.nextInt(Integer.MAX_VALUE-1)});
 for(int[] pair:dimensions){int[] v=size(pair[0],pair[1]);check(v[0]>=1&&v[1]>=1&&v[0]<=1600&&v[1]<=1600&&(long)v[0]*v[1]<=2560000,"bounded bitmap dimensions");}
 System.out.println("HOST_CHECKS_PASSED="+passed);for(String skip:skips)System.out.println("SKIP="+skip);System.out.println("HOST_SYMLINK_SKIPS="+skips.size());
}
}
'''

def main():
    n=1
    while (OUTPUT/f'host-contract-run-{n:03d}').exists():n+=1
    out=OUTPUT/f'host-contract-run-{n:03d}';out.mkdir(parents=True)
    resolver=TEXT.split('    static File resolveExport(',1)[1].split('    private void buttons()',1)[0]
    resolver='    static File resolveExport('+resolver
    sizing=TEXT.split('int w=page.getWidth(),h=page.getHeight();',1)[1].split('bitmap=Bitmap.createBitmap(',1)[0]
    wrapper=JAVA.replace('__RESOLVER__',resolver).replace('__SIZING__',sizing)
    java=out/'PdfPreviewHostChecks.java';java.write_bytes(wrapper.encode('utf-8'))
    record={'scope':'pure JVM production-policy statements plus structural Android ownership assertions; no Android runtime/API behavior claimed','activity_sha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'extracted_resolver_sha256':hashlib.sha256(resolver.encode()).hexdigest(),'extracted_sizing_sha256':hashlib.sha256(sizing.encode()).hexdigest(),'commands':[]}
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(PdfPreviewStaticChecks)
    with (out/'static-results.txt').open('w',encoding='utf-8') as stream:result=unittest.TextTestRunner(stream=stream,verbosity=2).run(suite)
    record['static']={'run':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'passed':result.wasSuccessful()}
    executable=os.environ.get('JAVA','java')
    for label,args in [('javac',[executable,'-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','--release','17','-d',out,java]),('java',[executable,'-XX:-UsePerfData','-cp',out,'PdfPreviewHostChecks',out/'fixtures'])]:
        with (out/(label+'.stdout.txt')).open('xb') as stdout,(out/(label+'.stderr.txt')).open('xb') as stderr:p=subprocess.run([str(a) for a in args],stdout=stdout,stderr=stderr,timeout=60)
        record['commands'].append({'label':label,'args':[str(a) for a in args],'exit_code':p.returncode})
        if p.returncode:break
    (out/'RESULT.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record));print((out/'java.stdout.txt').read_text() if (out/'java.stdout.txt').exists() else 'Java host run not reached')
    if not result.wasSuccessful() or any(c['exit_code'] for c in record['commands']):raise SystemExit(1)
if __name__=='__main__':main()
