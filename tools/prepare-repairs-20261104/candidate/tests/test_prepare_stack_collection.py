"""Read-only collector regression; fixtures are synthetic and never removed."""
import argparse
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("stack_collector", ROOT / "tools/collect_prepare_stacks.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
RUN = "12345678-1234-1234-1234-123456789abc"
OUTPUT = None
JAVA_OUTPUT = None


def record(identity="ACTIVITY_ARRIVAL", legacy=False):
    result = {"runId": RUN, "contains_document_text": False,
              "stacks": [{"role": "timed-out-operation", "stage": "preparation", "id": identity, "frames": ["Host.method(Host.java:1)"]}]}
    if not legacy:
        result.update(format=collector.FORMAT, stage="preparation", id=identity)
    return result


class CollectionTests(unittest.TestCase):
    def setUp(self):
        self.directory = OUTPUT / self._testMethodName
        self.directory.mkdir()

    def write(self, value, name=None):
        name = name or collector.filename(value.get("stage", "preparation"), value.get("id", "ACTIVITY_ARRIVAL"))
        (self.directory / name).write_text(json.dumps(value), encoding="utf-8")

    def test_legacy_read_compatibility(self):
        self.write(record(legacy=True), "program-stacks.json")
        result = collector.inspect(self.directory, RUN)
        self.assertFalse(result["errors"])
        self.assertEqual(result["records"][0]["format"], "legacy")

    def test_new_multiple_files_all_collected(self):
        for identity in ("ACTIVITY_ARRIVAL", "SETUP"):
            self.write(record(identity))
        result = collector.inspect(self.directory, RUN, ["ACTIVITY_ARRIVAL", "SETUP"])
        self.assertFalse(result["errors"])
        self.assertEqual({r["id"] for r in result["records"]}, {"ACTIVITY_ARRIVAL", "SETUP"})

    def test_mixed_legacy_new_preserves_both(self):
        self.write(record(legacy=True), "program-stacks.json")
        self.write(record())
        result = collector.inspect(self.directory, RUN)
        self.assertEqual(len(result["records"]), 2)
        self.assertFalse(result["errors"])
        self.assertEqual(result["warnings"][0]["code"], "MULTIPLE_RECORDS_FOR_PHASE")

    def test_unknown_stack_filename_is_not_silently_omitted(self):
        self.write(record(), "program-stacks-unrecognized.json")
        result = collector.inspect(self.directory, RUN)
        self.assertTrue(result["errors"])
        self.assertEqual(result["errors"][0]["file"], "program-stacks-unrecognized.json")

    def test_empty_and_expected_missing_are_explicit(self):
        result = collector.inspect(self.directory, RUN, ["SETUP"])
        self.assertEqual({e["code"] for e in result["errors"]}, {"NO_STACK_FILES", "EXPECTED_STACK_MISSING"})

    def test_missing_one_of_two_is_not_complete(self):
        self.write(record())
        result = collector.inspect(self.directory, RUN, ["ACTIVITY_ARRIVAL", "SETUP"])
        self.assertEqual(result["errors"], [{"code": "EXPECTED_STACK_MISSING", "id": "SETUP"}])

    def test_run_and_filename_identity_mismatch_rejected(self):
        value = record();value["runId"] = "abcdef12-1234-1234-1234-123456789abc"
        self.write(value)
        self.assertTrue(collector.inspect(self.directory, RUN)["errors"])
        self.write(record("SETUP"), collector.filename("preparation", "OTHER"))
        self.assertEqual(len(collector.inspect(self.directory, RUN)["errors"]), 2)

    def test_partial_duplicate_and_nonstandard_json_rejected(self):
        names = ["program-stacks.json", collector.filename("preparation", "A"), collector.filename("preparation", "B")]
        for name, body in zip(names, ['{"runId":', '{"runId":"a","runId":"b"}', '{"value":NaN}']):
            (self.directory / name).write_text(body)
        self.assertEqual(len(collector.inspect(self.directory, RUN)["errors"]), 3)

    def test_nonfile_candidate_rejected(self):
        (self.directory / "program-stacks-folder").mkdir()
        self.assertTrue(collector.inspect(self.directory, RUN)["errors"])

    def test_oversize_not_read_without_bound(self):
        (self.directory / "program-stacks.json").write_bytes(b"x" * (collector.MAX_BYTES + 1))
        self.assertTrue(collector.inspect(self.directory, RUN)["errors"])

    def test_actual_java_writer_records_are_compatible(self):
        result = collector.inspect(JAVA_OUTPUT / "concurrent", RUN, ["ACTIVITY_ARRIVAL", "SETUP"])
        self.assertFalse(result["errors"])
        self.assertEqual(len(result["records"]), 2)
        self.assertFalse(result["product_acceptance"])
        self.assertFalse(result["durability_confirmed"])
        (self.directory / "summary.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser();parser.add_argument("output", type=Path);parser.add_argument("java_output", type=Path)
    args = parser.parse_args();OUTPUT = args.output;JAVA_OUTPUT = args.java_output
    OUTPUT.mkdir(parents=True, exist_ok=False)
    unittest.main(argv=[__file__])
