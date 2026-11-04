"""Standalone tooling source contracts, not Android compilation or acceptance."""
from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools/scanner_instrumentation"
spec = importlib.util.spec_from_file_location("java_source_helpers", ROOT / "tests/java_source_helpers.py")
legacy = importlib.util.module_from_spec(spec);spec.loader.exec_module(legacy)


class SourceContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runner = (TOOL / "android/ScanInstrumentation.java").read_text()
        cls.control = (TOOL / "java/org/wangshu/scan/BoundedStage.java").read_text()
        cls.writer = (TOOL / "java/org/wangshu/scan/DiagnosticFiles.java").read_text()

    def test_capture_constructor_only_takes_metadata(self):
        body = legacy.java_body(self.control, r"private Timeout\(Phase p\)")
        self.assertNotIn("getStackTrace", legacy.java_mask(body, literals=False))
        self.assertIn("Collections.emptyList()", body)

    def test_published_snapshot_precedes_listener_and_uses_one_budget(self):
        body = legacy.java_body(self.control, r"private StageTimeoutException timedOut\(Phase p\)")
        self.assertLess(body.index("diagnosticDeadline"), body.index("stackCapture.read"))
        self.assertLess(body.index("System.nanoTime()-diagnosticDeadline"), body.index("capturedTimeout.set(captured)"))
        self.assertLess(body.index("capturedTimeout.set(captured)"), body.index("listener.onTimeout(captured)"))
        self.assertIn("timeout=capturedTimeout.get();", body)
        self.assertEqual(body.count("diagnostic.get(evidenceBudgetMs,TimeUnit.MILLISECONDS)"), 1)

    def test_runner_uses_unique_writer_and_preserves_identity(self):
        body = legacy.method(self.runner, "programStacks")
        self.assertIn('DiagnosticFiles.write(evidence.toPath(),timeout.stage,timeout.id,', body)
        for field in ['.put("format",DiagnosticFiles.FORMAT)', '.put("runId",runId)', '.put("stage",timeout.stage)', '.put("id",timeout.id)', '.put("contains_document_text",false)']:
            self.assertIn(field, body)
        self.assertNotIn('saveJson("program-stacks.json"', body)
        self.assertIn('catch(IOException failure){evidenceFailed=true;throw failure;}', body)

    def test_target_frames_and_privacy_contract_remain(self):
        body = legacy.method(self.runner, "programStacks")
        self.assertIn("timeout.stackFrames", body)
        self.assertIn('.put("role","timed-out-operation")', body)
        self.assertNotRegex(legacy.java_mask(body), r"\b(?:getMessage|getLocalizedMessage|getDeclaredField|getLocalVariables)\s*\(")

    def test_writer_exclusive_creation_and_force(self):
        self.assertIn("StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE", self.writer)
        self.assertIn("output.force(true)", self.writer)
        self.assertNotIn("TRUNCATE_EXISTING", self.writer)
        self.assertNotIn("createDirectories", self.writer)
        self.assertIn("Locale.ROOT", self.writer)

    def test_monitor_change_not_silently_included(self):
        body = legacy.method(self.runner, "setup")
        self.assertIn('waitForMonitorWithTimeout(monitor,20000)', body)
        self.assertIn('control.run("preparation","ACTIVITY_ARRIVAL",21000', body)

    def test_entry_scope_and_acceptance_unchanged(self):
        body = legacy.method(self.runner, "onStart")
        self.assertIn('control.run("preparation","SETUP",300000', body)
        self.assertIn('result.putBoolean("product_acceptance",false)', body)
        self.assertIn('result.putInt("case_assertions",0)', body)
        self.assertEqual(legacy.preparation_helper_calls(self.runner), set())

    def test_fixture_contract_explicit_and_unchanged(self):
        source = (TOOL / "android/Fixtures.java").read_text()
        self.assertIn('getContext().getAssets().openFd("fixtures/"+n+".png")', source)
        self.assertIn('!n.matches("[ABC]")', source)
        self.assertNotIn("uses-permission", (TOOL / "android/AndroidManifest.xml").read_text())

    def test_collector_reads_all_matching_names_and_legacy(self):
        source = (ROOT / "tools/collect_prepare_stacks.py").read_text()
        self.assertIn('directory.iterdir()', source)
        self.assertIn('p.name.startswith("program-stacks")', source)
        self.assertIn('path.name == "program-stacks.json"', source)
        self.assertIn('EXPECTED_STACK_MISSING', source)


if __name__ == "__main__":
    unittest.main()
