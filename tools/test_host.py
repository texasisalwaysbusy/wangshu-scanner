#!/usr/bin/env python3
"""Run dependency-free Linux host regressions (Python 3.10+, JDK 17+; tested JDK 21).

No network, downloads, installations or cleanup. Output is retained under build/.
Does not build or run Android, JNI, Camera2, SAF, or real OCR models.
"""
import hashlib,json,os,subprocess,sys,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=os.environ.get('JAVA','java')
out=ROOT/'build'/('host-tests-'+str(uuid.uuid4()));out.mkdir(parents=True)
def hashes():
 return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'app/src/main/java').rglob('*.java'))}
before=hashes();results=[]
def run(name,command):
 start=time.monotonic();folder=out/name;folder.mkdir()
 env=os.environ.copy();env['SCANNER_TEST_OUTPUT']=str(folder);env['PYTHONDONTWRITEBYTECODE']='1'
 try:
  p=subprocess.run(command,cwd=ROOT,env=env,capture_output=True,text=True,timeout=180)
  output=p.stdout+p.stderr;code=p.returncode
 except (OSError,subprocess.TimeoutExpired) as error:output=str(error);code=1
 (folder/'output.txt').write_text(output,encoding='utf-8')
 passed=code==0
 # A script reporting a nonzero compiler/runtime receipt cannot be hidden by its exit status.
 for path in folder.rglob('result.json'):
  data=json.loads(path.read_text())
  for key in ('compile_exit','run_exit','compile_returncode','run_returncode'):
   if key in data and data[key]!=0:passed=False
 item={'name':name,'pass':passed,'exit':code,'seconds':round(time.monotonic()-start,3)};results.append(item)
 print(name, 'PASS' if passed else 'FAIL',flush=True)
 if not passed:print(output,flush=True)
 return passed
scripts=['geometry_small_crop_host','page_detector_shadow_host','ocr_key_host','ocr_null_host','pdf_revision_host','jpeg_import_actual_host','zip_verified_restore_host','storage_progress','draft_ui','edit_draft_store','edit_draft_gates','edit_draft_save','capture_lock','helper_files']
for name in scripts:run(name,[sys.executable,str(ROOT/'tests/host'/f'{name}.py')])
classes=out/'core-classes'
sources=list((ROOT/'app/src/main/java/org/wangshu/scan/core').glob('*.java'))+[ROOT/'tests'/f'{name}.java' for name in ['EnhanceReferenceTests','RegionRenderPlanTests','SurfaceLifetimeTests']]
if run('core-compile',[JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-d',str(classes),*map(str,sources)]):
 for name in ['EnhanceReferenceTests','RegionRenderPlanTests','SurfaceLifetimeTests']:run(name,[JAVA,'-cp',str(classes),name])
unchanged=before==hashes();all_pass=unchanged and all(r['pass'] for r in results)
receipt={'source_sha256':before,'sources_unchanged':unchanged,'groups':results,'all_pass':all_pass,'scope':'Host Java/filesystem and extracted-method synthetic tests only. Does NOT establish Android API compatibility, native OCR, resource/DEX/linkage, lifecycle/camera/SAF, photo quality or power-loss safety.'}
(out/'summary.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
print('Retained receipt:',out/'summary.json');print('Known limitation: background-equal shadow can still truncate the suggested page; manual crop review is required.')
raise SystemExit(0 if all_pass else 1)
