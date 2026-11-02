"""Host-only compiler support. Does not install/download or remove retained test output."""
import os
from pathlib import Path
import re
import subprocess
import uuid
PROJECT = Path(__file__).resolve().parents[2]
OUTPUT = Path(os.environ.get("SCANNER_TEST_OUTPUT", PROJECT / "build" / ("host-" + str(uuid.uuid4())))).resolve()
OUTPUT.mkdir(parents=True, exist_ok=True)
JAVA = os.environ.get("JAVA", "java")
def run_java(source, name=None, args=(), input=None):
    name = name or re.search(r"public class (\w+)", source).group(1)
    work = OUTPUT / (name + "-" + str(uuid.uuid4()))
    work.mkdir()
    path = work / (name + ".java")
    path.write_text(source, encoding="utf-8")
    classes = work / "classes"
    compile_result = subprocess.run([JAVA, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-d", str(classes), str(path)], capture_output=True, text=True, timeout=60)
    (work / "compile.txt").write_text(compile_result.stdout + compile_result.stderr, encoding="utf-8")
    if compile_result.returncode:
        return compile_result
    return subprocess.run([JAVA, "-cp", str(classes), name, *map(str,args)], input=input, capture_output=True, text=True, timeout=60)
