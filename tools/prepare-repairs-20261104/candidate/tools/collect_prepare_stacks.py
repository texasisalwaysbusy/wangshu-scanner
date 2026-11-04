"""Inspect all already-collected stack files from one explicit preparation run.

Read-only. No device access, commands, network, copying, cleanup or acceptance.
Supports legacy program-stacks.json and per-phase v2 files. A collector must copy
the entire run evidence directory, not only the legacy fixed-name file.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import uuid

FORMAT = "wangshu.scan.program-stacks.v2"
ID = re.compile(r"[A-Z][A-Z0-9_]{0,79}\Z")
MAX_BYTES = 2 * 1024 * 1024
MAX_FILES = 128


def filename(stage, identity):
    if stage not in {"startup", "preparation", "case"} or not isinstance(identity, str) or not ID.fullmatch(identity):
        raise ValueError("INVALID_PHASE_IDENTITY")
    return "program-stacks-" + stage + "-" + identity.lower().replace("_", "-") + ".json"


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def invalid_constant(value):
    raise ValueError("NONSTANDARD_JSON_NUMBER")


def inspect(directory: Path, run_id: str, expected_ids=()):
    if not isinstance(run_id, str) or str(uuid.UUID(run_id)) != run_id:
        raise ValueError("CANONICAL_RUN_ID_REQUIRED")
    expected = list(expected_ids)
    if len(set(expected)) != len(expected) or any(not isinstance(x, str) or not ID.fullmatch(x) for x in expected):
        raise ValueError("INVALID_EXPECTED_IDS")
    report = {"run_id": run_id, "records": [], "errors": [], "warnings": [],
              "directory_snapshot_only": True, "product_acceptance": False,
              "durability_confirmed": False, "expected_ids": expected}
    if directory.is_symlink() or not directory.is_dir():
        report["errors"].append({"code": "RUN_DIRECTORY_REQUIRED"})
        return report
    candidates = sorted(p for p in directory.iterdir() if p.name.startswith("program-stacks"))
    if not candidates:
        report["errors"].append({"code": "NO_STACK_FILES"})
    if len(candidates) > MAX_FILES:
        report["errors"].append({"code": "TOO_MANY_STACK_FILES", "count": len(candidates)})
        return report
    seen = {}
    for path in candidates:
        try:
            if path.is_symlink() or not path.is_file():
                raise ValueError("REGULAR_FILE_REQUIRED")
            with path.open("rb") as source:
                raw = source.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                raise ValueError("STACK_FILE_TOO_LARGE")
            record = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object, parse_constant=invalid_constant)
            if not isinstance(record, dict) or record.get("runId") != run_id or record.get("contains_document_text") is not False:
                raise ValueError("RUN_OR_PRIVACY_MARKER_MISMATCH")
            rows = record.get("stacks")
            if not isinstance(rows, list) or not 1 <= len(rows) <= 256:
                raise ValueError("INVALID_STACK_ROWS")
            for row in rows:
                if not isinstance(row, dict) or not isinstance(row.get("role"), str):
                    raise ValueError("INVALID_STACK_ROW")
                frames = row.get("frames")
                if not isinstance(frames, list) or len(frames) > 64 or any(not isinstance(f, str) or len(f) > 4096 for f in frames):
                    raise ValueError("INVALID_STACK_FRAMES")
            targets = [row for row in rows if row["role"] == "timed-out-operation"]
            if len(targets) != 1:
                raise ValueError("ONE_TIMEOUT_TARGET_REQUIRED")
            stage, identity = targets[0].get("stage"), targets[0].get("id")
            expected_name = filename(stage, identity)
            if path.name == "program-stacks.json" and "format" not in record:
                version = "legacy"
            elif record.get("format") == FORMAT and path.name == expected_name:
                if record.get("stage") != stage or record.get("id") != identity:
                    raise ValueError("FILE_TARGET_IDENTITY_MISMATCH")
                version = "v2"
            else:
                raise ValueError("UNSUPPORTED_NAME_OR_FORMAT")
            if identity in seen:
                report["warnings"].append({"code": "MULTIPLE_RECORDS_FOR_PHASE", "id": identity})
            seen[identity] = True
            report["records"].append({"file": path.name, "format": version, "stage": stage, "id": identity,
                                      "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw), "stack_rows": len(rows)})
        except (OSError, ValueError, UnicodeError, KeyError, TypeError):
            # No exception messages or stack contents are copied into the report.
            report["errors"].append({"file": path.name, "code": "INVALID_OR_UNREADABLE_STACK_FILE"})
    for identity in expected:
        if identity not in seen:
            report["errors"].append({"code": "EXPECTED_STACK_MISSING", "id": identity})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--expected-id", action="append", default=[])
    args = parser.parse_args()
    result = inspect(args.directory, args.run_id, args.expected_id)
    print(json.dumps(result, indent=2))
    raise SystemExit(2 if result["errors"] else 0)


if __name__ == "__main__":
    main()
