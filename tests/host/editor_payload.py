#!/usr/bin/env python3
"""Unmodified production byte budget, recovery/lifecycle codec and files. No Android/JSON runtime."""
from support import PROJECT,OUTPUT,JAVA
import subprocess,json,hashlib
base=PROJECT/'app/src/main/java/org/wangshu/scan'
sources=[base/(name+'.java') for name in ['SerializedBudget','EditDraftStore','EditorLifecycleState']]+[PROJECT/'tests/EditorPayloadTests.java']
commands=[]
def run(label,args):
 p=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',timeout=120)
 (OUTPUT/(label+'.txt')).write_text(p.stdout+p.stderr,encoding='utf-8');commands.append({'label':label,'exit':p.returncode});print(p.stdout+p.stderr)
 if p.returncode:raise RuntimeError(label+' failed')
try:
 run('compile',[JAVA,'-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-d',OUTPUT/'classes',*sources])
 run('tests',[JAVA,'-Xmx256m','-cp',OUTPUT/'classes','org.wangshu.scan.EditorPayloadTests',OUTPUT/'fixtures'])
finally:
 (OUTPUT/'payload-result.json').write_text(json.dumps({'sources':{str(p.relative_to(PROJECT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'commands':commands,'scope':__doc__,'compiler':'source/target17 on installed JDK; Java17 API fence NOT_RUN'},indent=2),encoding='utf-8')
