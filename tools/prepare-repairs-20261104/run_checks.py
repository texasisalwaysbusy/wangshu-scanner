"""Reproduce isolated host-only tooling checks with installed Python/JDK.
Requires a new output directory. Retains generated files; no network or cleanup.
"""
from pathlib import Path
import argparse
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    checks = []

    def run(label, command):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=35)
        (output / (label + ".txt")).write_text(result.stdout)
        checks.append({"label": label, "exit_code": result.returncode})
        print(label, "PASS" if result.returncode == 0 else "FAIL", flush=True)
        if result.returncode:
            raise RuntimeError(label + " failed; see retained log")

    def compile(label, directory, sources):
        run(label, ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "17", "-target", "17",
                    "-encoding", "UTF-8", "-d", str(directory), *map(str, sources)])

    def java(label, classes, main_class, *arguments):
        run(label, ["java", "-Xmx128m", "-cp", str(classes), "org.wangshu.scan." + main_class, *map(str, arguments)])

    try:
        archive = ROOT / "baseline/tools/scanner_instrumentation/java/org/wangshu/scan/BoundedStage.java"
        candidate = ROOT / "candidate"
        capture = ROOT / "tests/RuntimeCaptureProbe.java"
        boundary = ROOT / "tests/EvidenceBoundaryProbe.java"
        baseline_classes = output / "baseline-classes"
        compile("baseline-compile", baseline_classes, [archive, capture, boundary])
        for mode in ("capture_only", "sink_and_capture"):
            java("baseline-" + mode, baseline_classes, "RuntimeCaptureProbe", "archived", mode, output / ("baseline-" + mode))
        prior_classes = output / "before003-classes"
        captured_test = candidate / "tests/CapturedTimeoutTests.java"
        compile("before003-compile", prior_classes, [ROOT / "reference/after001/BoundedStage.java", captured_test])
        java("before003-captured-frames", prior_classes, "CapturedTimeoutTests", "before003")
        classes = output / "candidate-classes"
        compile("candidate-compile", classes, [
            candidate / "tools/scanner_instrumentation/java/org/wangshu/scan/BoundedStage.java",
            candidate / "tools/scanner_instrumentation/java/org/wangshu/scan/DiagnosticFiles.java",
            candidate / "tests/BoundedStageTests.java", candidate / "tests/DiagnosticFilesTests.java",
            captured_test, capture, boundary])
        for mode in ("capture_only", "sink_and_capture"):
            java("candidate-" + mode, classes, "RuntimeCaptureProbe", "candidate_final", mode, output / ("candidate-" + mode))
        java("candidate-bounded-tests", classes, "BoundedStageTests", output / "bounded-fixtures")
        java("candidate-boundary-tests", classes, "EvidenceBoundaryProbe", output / "boundary-fixtures")
        java("candidate-writer-tests", classes, "DiagnosticFilesTests", output / "writer-fixtures")
        java("candidate-captured-frames", classes, "CapturedTimeoutTests", "repaired")
        run("collector-tests", [sys.executable, "-B", str(candidate / "tests/test_prepare_stack_collection.py"),
                                str(output / "collector-fixtures"), str(output / "writer-fixtures")])
        run("source-contracts", [sys.executable, "-B", str(candidate / "tests/test_prepare_source_contract.py")])
    finally:
        (output / "results.json").write_text(json.dumps({
            "checks": checks, "all_started_checks_pass": all(x["exit_code"] == 0 for x in checks),
            "expected_check_entries": 14, "all_expected_checks_ran": len(checks) == 14,
            "android_executed": False, "product_acceptance": False,
            "phone_root_cause_proven": False, "java_api_fence": "NOT_RUN; JDK21 source/target17 only"
        }, indent=2) + "\n")


if __name__ == "__main__":
    main()
