"""Compile/run unmodified production classes. No Android stubs or device execution.
The document writer/persisted-order fixture is a host seam, not Android Store/AtomicFile.
"""
from pathlib import Path
from support import PROJECT, OUTPUT, JAVA
import hashlib,json,subprocess,sys
GROUPS={
 'coordinator':(['EditorSaveFlow'],['EditorSaveFlowTests','EditorSaveFlowRaceTests']),
 'recovery':(['EditorSaveFlow','EditDraftStore','EditRecoveryPolicy','EditRecoveryState'],['Vc4RecoveryTests']),
 'catalog':(['EditDraftStore','EditRecoveryPolicy','EditRecoveryCatalog'],['Vc5RecoveryCatalogTests']),
 'source':(['EditRecoveryPolicy','RecoverySourceGate'],['Vc6RecoverySourceTests']),
}
group=sys.argv[1];production,tests=GROUPS[group];base=PROJECT/'app/src/main/java/org/wangshu/scan'
sources=[base/(n+'.java') for n in production]+[PROJECT/'tests'/(n+'.java') for n in tests]
classes=OUTPUT/'classes';records=[]
def run(label,args):
 p=subprocess.run(list(map(str,args)),cwd=PROJECT,capture_output=True,text=True,encoding='utf-8',timeout=300)
 (OUTPUT/(label+'.stdout.txt')).write_text(p.stdout,encoding='utf-8');(OUTPUT/(label+'.stderr.txt')).write_text(p.stderr,encoding='utf-8')
 records.append({'label':label,'exit_code':p.returncode});print(p.stdout,end='');print(p.stderr,end='')
 if p.returncode:raise RuntimeError(label+' failed')
try:
 run('compile',[JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-d',classes,*sources])
 for name in tests:
  args=[JAVA,'-Xmx256m','-cp',classes,'org.wangshu.scan.'+name]
  if name.startswith('Vc'):args.append(OUTPUT/('fixtures-'+name))
  run(name,args)
finally:
 (OUTPUT/'production-result.json').write_text(json.dumps({'group':group,'sources':{str(p.relative_to(PROJECT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'commands':records,'scope':'Unmodified production pure Java classes, real host files where applicable; injected document writer/persisted-order seams; no Android Store/AtomicFile/UI execution.'},indent=2),encoding='utf-8')

