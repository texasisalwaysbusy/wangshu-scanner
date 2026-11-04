"""Synthetic scanner instrumentation evidence. Importing this module never runs a command.

No device discovery, UI commands, cleanup, installation, network or automatic retry.
The Android phase producer is a separate, not-yet-integrated component.
"""
from __future__ import annotations

import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

PROTOCOL = "wangshu.scan.instrumentation.v1"
PREFIX = "WANGSHU_EVENT "
STATUS_PREFIX = "INSTRUMENTATION_STATUS: wangshu_event="
IDENTIFIER = re.compile(r"[A-Z][A-Z0-9_]{0,79}\Z")


class EvidenceWriteError(OSError):
    """The in-memory terminal record is retained even if durable evidence cannot be written."""
    def __init__(self, record):
        super().__init__("Instrumentation evidence was not fully persisted; acceptance is forbidden")
        self.record = record


def _run_id(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError("run_id must be a canonical UUID")
    return value


def _cases(values):
    if isinstance(values, (str, bytes)):
        raise ValueError("expected cases must be an explicit nonempty collection")
    values = list(values)
    if not values or any(not isinstance(x, str) or not IDENTIFIER.fullmatch(x) for x in values) or len(set(values)) != len(values):
        raise ValueError("expected cases must be nonempty, distinct case IDs")
    return sorted(values)


def scanner_command(prefix, run_id):
    """Caller supplies an already-authorized transport prefix; this does not discover a device."""
    return [*prefix, "shell", "am", "instrument", "-w", "-r", "-e", "suite", "scan",
            "-e", "scope", "prepare", "-e", "runId", _run_id(run_id), "org.wangshu.scan.test/org.wangshu.scan.ScanInstrumentation"]


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _constant(value):
    raise ValueError("non-standard JSON number: " + value)


def _decode(data):
    return json.loads(data.decode("utf-8"), object_pairs_hook=_object, parse_constant=_constant)


def analyze(stdout, stderr, *, run_id, expected_cases, expected_terminal="PREPARED"):
    """Validate stdout's ordered protocol; stderr is retained, never merged into that order."""
    run_id, expected = _run_id(run_id), _cases(expected_cases)
    if expected_terminal not in {"PREPARED", "PASS"}:
        raise ValueError("expected terminal must be explicit PREPARED or PASS")
    errors, events, stack, completed, assertions = [], [], [], {}, {}
    terminal = owner = None
    footer = {}
    startup = prepared = False
    previous_ns = -1
    try:
        text, error_text = stdout.decode("utf-8"), stderr.decode("utf-8")
    except UnicodeError:
        return {"accepted": False, "errors": ["INVALID_UTF8"], "assertions": 0, "cases": [], "last_open_phase": None}
    if any(line.startswith((PREFIX, STATUS_PREFIX)) for line in error_text.splitlines()):
        errors.append("PROTOCOL_ON_STDERR_HAS_NO_ORDER")
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_RESULT: "):
            item = line[len("INSTRUMENTATION_RESULT: "):]
            if "=" in item:
                key, value = item.split("=", 1)
                if key in {"status", "runId", "preparation_assertions", "case_assertions", "product_acceptance"}:
                    if key in footer:
                        errors.append("DUPLICATE_FRAMEWORK_RECEIPT:" + key)
                    footer[key] = value.strip()
                    if key == "status" and value.strip() != expected_terminal:
                        errors.append("RUNNER_REPORTED_FAILURE")
        prefix = next((p for p in (PREFIX, STATUS_PREFIX) if line.startswith(p)), None)
        if prefix is None:
            continue
        try:
            if len(line) > 65536 or len(events) >= 10000:
                raise ValueError("protocol size limit")
            e = json.loads(line[len(prefix):], object_pairs_hook=_object, parse_constant=_constant)
            if not isinstance(e, dict) or e.get("protocol") != PROTOCOL or e.get("suite") != "scan" or e.get("run_id") != run_id:
                raise ValueError("protocol/suite/run identity mismatch")
            if type(e.get("seq")) is not int or e["seq"] != len(events) + 1:
                raise ValueError("non-contiguous sequence")
            if type(e.get("mono_ns")) is not int or (e["mono_ns"] < 0 or e["mono_ns"] < previous_ns):
                raise ValueError("invalid monotonic clock")
            if not isinstance(e.get("thread"), str) or not e["thread"] or len(e["thread"]) > 120:
                raise ValueError("missing thread")
            if not isinstance(e.get("id"), str) or not IDENTIFIER.fullmatch(e["id"]):
                raise ValueError("invalid step identity")
            if terminal is not None:
                raise ValueError("event after terminal")
            kind, stage, identity = e.get("kind"), e.get("stage"), e["id"]
            if kind == "BEGIN":
                if stage not in {"startup", "preparation", "case"} or type(e.get("budget_ms")) is not int or not 0 < e["budget_ms"] <= 420000:
                    raise ValueError("invalid phase or phase budget")
                if stage == "startup" and (startup or prepared or stack):
                    raise ValueError("startup order")
                if stage == "preparation" and (not startup or prepared or stack and stack[-1]["stage"] != "preparation"):
                    raise ValueError("preparation order")
                if stage == "case" and (not prepared or stack or identity in completed or identity in assertions or e.get("owner") != owner):
                    raise ValueError("case order/owner/duplicate")
                if stage == "case":
                    assertions[identity] = {}
                stack.append(e)
            elif kind == "END":
                if not stack or (stage, identity) != (stack[-1]["stage"], stack[-1]["id"]):
                    raise ValueError("unmatched END")
                begin = stack.pop()
                if e["mono_ns"] - begin["mono_ns"] > begin["budget_ms"] * 1000000:
                    errors.append("PHASE_BUDGET_EXCEEDED:" + identity)
                if stage == "case":
                    if e.get("owner") != owner or type(e.get("assertions")) is not int or e["assertions"] != len(assertions[identity]) or (e.get("status") == "PASS" and e["assertions"] <= 0):
                        raise ValueError("case assertion count/owner mismatch")
                    completed[identity] = e.get("status")
                    if e.get("status") != "PASS" or not all(assertions[identity].values()):
                        errors.append("CASE_FAILED:" + identity)
                else:
                    if e.get("status") != "OK":
                        errors.append("PHASE_FAILED:" + identity)
                    if stage == "startup":
                        startup = e.get("status") == "OK"
                    if stage == "preparation" and not stack:
                        if owner is None and e.get("status") == "OK":
                            raise ValueError("successful preparation lacks synthetic owner")
                        prepared = owner is not None and e.get("status") == "OK"
            elif kind == "OWNER":
                if stage != "preparation" or not stack or stack[-1]["stage"] != "preparation" or owner is not None:
                    raise ValueError("owner declaration order")
                owner = _run_id(e.get("owner"))
                if e.get("title") != "SCAN_SYNTH_" + run_id:
                    raise ValueError("owner title is not bound to run")
            elif kind == "ASSERT":
                if stage != "case" or not stack or stack[-1]["stage"] != "case" or stack[-1]["id"] != identity or e.get("owner") != owner:
                    raise ValueError("assertion outside owned case")
                key = e.get("assertion_id")
                if not isinstance(key, str) or not IDENTIFIER.fullmatch(key) or key in assertions[identity] or type(e.get("passed")) is not bool:
                    raise ValueError("invalid/duplicate assertion")
                assertions[identity][key] = e["passed"]
            elif kind == "TERMINAL":
                if stage != "terminal" or identity != "RUNNER" or e.get("status") not in {"PREPARED", "PASS", "FAIL", "STOPPED"}:
                    raise ValueError("invalid terminal")
                terminal = e
            else:
                raise ValueError("unknown event kind")
            previous_ns = e["mono_ns"]
            events.append(e)
        except (ValueError, TypeError, KeyError, AttributeError) as error:
            errors.append("INVALID_PROTOCOL:" + str(error))
            break
    count = sum(len(x) for x in assertions.values())
    if not events:
        errors.append("NO_PROTOCOL")
    if terminal is None:
        errors.append("MISSING_TERMINAL")
    else:
        declared = terminal.get("cases")
        if terminal.get("evidence_failed") is True:
            errors.append("RUNNER_EVIDENCE_FAILED")
        if terminal.get("status") != expected_terminal:
            errors.append("TERMINAL_NOT_PASS")
        if not startup or not prepared or stack:
            errors.append("INCOMPLETE_PHASES")
        if not isinstance(declared, list) or any(not isinstance(x, str) for x in declared) or len(set(declared)) != len(declared) or set(declared) != set(completed):
            errors.append("TERMINAL_CASE_SET_MISMATCH")
        if type(terminal.get("assertions")) is not int or terminal["assertions"] != count or count <= 0:
            errors.append("TERMINAL_ASSERTION_COUNT")
        if owner is None or terminal.get("owner") != owner:
            errors.append("TERMINAL_OWNER_MISMATCH")
    expected_footer = {"status": expected_terminal, "runId": run_id,
                       "preparation_assertions": str(count if expected_terminal == "PREPARED" else 0),
                       "case_assertions": str(count if expected_terminal == "PASS" else 0), "product_acceptance": "false"}
    if footer != expected_footer:
        errors.append("FRAMEWORK_RECEIPT_INCOMPLETE_OR_MISMATCHED")
    if not set(expected).issubset(completed) or any(completed.get(x) != "PASS" or not assertions.get(x) for x in expected):
        errors.append("EXPECTED_CASES_INCOMPLETE")
    return {"accepted": not errors, "errors": errors, "assertions": count, "cases": sorted(completed),
            "owner": owner, "event_count": len(events), "last_open_phase": stack[-1] if stack else None,
            "last_event": events[-1] if events else None}


def _bytes(value):
    if value is None:
        return b""
    if isinstance(value, str):
        return value.encode("utf-8", errors="surrogatepass")
    if isinstance(value, bytes):
        return value
    raise TypeError("capture stream is not bytes/text/None")


def _json(value):
    return (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + "\n").encode("utf-8")


def _write_new(path, data):
    with path.open("xb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def capture_run(command, out, *, expected_cases, invoke=subprocess.run, timeout=420, expected_terminal="PREPARED",
                run_id=None, now=None, monotonic_ns=time.monotonic_ns, write_new=_write_new):
    """One local attempt. command(run_id) must return argv; no shell/retry/cancel is added.

    Fresh output directory only. A failed write raises EvidenceWriteError with the
    full in-memory terminal record. KeyboardInterrupt/SystemExit finalize then re-raise.
    subprocess timeout ends the local wait, not necessarily the remote operation.
    """
    expected, identity = _cases(expected_cases), _run_id(run_id or str(uuid.uuid4()))
    if expected_terminal not in {"PREPARED", "PASS"}:
        raise ValueError("expected terminal must be PREPARED or PASS")
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or not 0 < timeout <= 420:
        raise ValueError("timeout must be finite, positive and at most the existing 420s bound")
    if not callable(command):
        raise ValueError("command must explicitly bind the generated run_id")
    now = now or (lambda: datetime.datetime.now(datetime.timezone.utc).isoformat())
    out = Path(out)
    out.mkdir(exist_ok=False)
    record = {"protocol": PROTOCOL, "suite": "scan", "run_id": identity, "expected_cases": expected,
              "status": "RUNNING", "timeout_seconds": timeout, "expected_terminal": expected_terminal,
              "scope": "PREPARATION_DIAGNOSTIC" if expected_terminal == "PREPARED" else "REPORTED_ASSERTIONS", "product_acceptance": False, "remote_cancellation_confirmed": False,
              "remote_state": "UNKNOWN", "evidence_errors": [], "clock_errors": [], "artifacts": {}}
    def clock(name, callback):
        try:
            value = callback()
            if name.endswith("_utc") and (not isinstance(value, str) or not value):
                raise ValueError("invalid UTC clock")
            if name.endswith("_ns") and (type(value) is not int or value < 0):
                raise ValueError("invalid monotonic clock")
            return value
        except Exception as error:
            record["clock_errors"].append({"field": name, "error_type": type(error).__name__})
            return None
    def persist(name, data):
        try:
            write_new(out / name, data)
            if (out / name).read_bytes() != data:
                raise OSError("evidence readback mismatch")
            record["artifacts"][name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            return True
        except Exception as error:
            record["evidence_errors"].append({"file": name, "error_type": type(error).__name__})
            return False
    stdout = stderr = b""
    interrupted = None
    record["started_utc"] = record["started_monotonic_ns"] = None
    try:
        record["started_utc"] = clock("started_utc", now)
        record["started_monotonic_ns"] = clock("started_monotonic_ns", monotonic_ns)
        guard_ready = persist("capture.pending.json", _json({"protocol": PROTOCOL, "suite": "scan", "run_id": identity}))
        initial = dict(record)
        started_ready = persist("run.started.json", _json(initial))
        ready = guard_ready and started_ready
        if not ready or record["clock_errors"]:
            record["status"] = "NOT_STARTED"
        else:
            argv = command(identity)
            if not isinstance(argv, (list, tuple)) or not argv or any(not isinstance(x, str) or not x for x in argv):
                raise ValueError("command did not return an argv sequence")
            child = invoke(list(argv), capture_output=True, timeout=timeout)
            stdout, stderr = _bytes(child.stdout), _bytes(child.stderr)
            if type(child.returncode) is not int:
                raise ValueError("invalid process exit code")
            record.update(exit_code=child.returncode, status="FINISHED_UNVERIFIED" if child.returncode == 0 else "PROCESS_FAILED")
    except subprocess.TimeoutExpired as error:
        stdout, stderr = _bytes(error.stdout), _bytes(error.stderr)
        record.update(status="TIMEOUT", error_type="TimeoutExpired")
    except BaseException as error:
        record.update(status="HOST_ERROR", error_type=type(error).__name__)
        if not isinstance(error, Exception):
            interrupted = error
    finally:
        record["ended_utc"] = clock("ended_utc", now)
        record["ended_monotonic_ns"] = clock("ended_monotonic_ns", monotonic_ns)
        begin, end = record["started_monotonic_ns"], record["ended_monotonic_ns"]
        if type(begin) is not int or type(end) is not int or end < begin:
            record["clock_errors"].append({"field": "host_monotonic_clock", "error_type": "InvalidClock"})
        record["capture_status"] = record["status"]
        record["validation"] = analyze(stdout, stderr, run_id=identity, expected_cases=expected, expected_terminal=expected_terminal)
        record["preparation_assertions"] = record["validation"]["assertions"] if expected_terminal == "PREPARED" else 0
        record["case_assertions"] = record["validation"]["assertions"] if expected_terminal == "PASS" else 0
        for name, data in (("instrumentation.stdout.txt", stdout), ("instrumentation.stderr.txt", stderr),
                           ("instrumentation.txt", stdout + stderr)):
            persist(name, data)
        if record["evidence_errors"]:
            record["status"] = "EVIDENCE_WRITE_FAILED"
        elif record["clock_errors"]:
            record["status"] = "CLOCK_ERROR"
        elif record["capture_status"] == "FINISHED_UNVERIFIED":
            record["status"] = ("PREPARED_VERIFIED" if expected_terminal == "PREPARED" else "ASSERTIONS_VERIFIED") if record["validation"]["accepted"] else "REJECTED"
        if not persist("run.json", _json(record)):
            record["status"] = "EVIDENCE_WRITE_FAILED"
        if not record["evidence_errors"]:
            try:
                if (out / "capture.complete.json").exists():
                    raise FileExistsError("completion guard already exists")
                (out / "capture.pending.json").rename(out / "capture.complete.json")
            except OSError as error:
                record["evidence_errors"].append({"file": "capture.complete.json", "error_type": type(error).__name__})
                record["status"] = "EVIDENCE_WRITE_FAILED"
    if interrupted is not None:
        interrupted.capture_record = record
        raise interrupted
    if record["evidence_errors"]:
        raise EvidenceWriteError(record)
    return record


def verify_evidence(out, *, run_id, expected_cases, expected_terminal="PREPARED"):
    """Read-only independent acceptance. Recompute raw protocol; never trust a PASS label."""
    identity, expected = _run_id(run_id), _cases(expected_cases)
    if expected_terminal not in {"PREPARED", "PASS"}:
        raise ValueError("invalid expected terminal")
    errors, result = [], None
    try:
        out = Path(out)
        record, started = _decode((out / "run.json").read_bytes()), _decode((out / "run.started.json").read_bytes())
        for item in (record, started):
            if item.get("protocol") != PROTOCOL or item.get("suite") != "scan" or item.get("run_id") != identity or item.get("expected_cases") != expected or item.get("expected_terminal") != expected_terminal:
                errors.append("RECEIPT_IDENTITY_MISMATCH")
            if item.get("scope") != ("PREPARATION_DIAGNOSTIC" if expected_terminal == "PREPARED" else "REPORTED_ASSERTIONS"):
                errors.append("RECEIPT_SCOPE_MISMATCH")
            if not isinstance(item.get("started_utc"), str) or not item["started_utc"]:
                errors.append("INVALID_RECEIPT_UTC")
        if record.get("started_utc") != started.get("started_utc") or not isinstance(record.get("ended_utc"), str) or not record["ended_utc"]:
            errors.append("RECEIPT_UTC_MISMATCH")
        if record.get("product_acceptance") is not False or started.get("product_acceptance") is not False or started.get("status") != "RUNNING":
            errors.append("INVALID_ACCEPTANCE_SCOPE")
        if record.get("status") != ("PREPARED_VERIFIED" if expected_terminal == "PREPARED" else "ASSERTIONS_VERIFIED") or record.get("capture_status") != "FINISHED_UNVERIFIED" or type(record.get("exit_code")) is not int or record["exit_code"] != 0:
            errors.append("CAPTURE_NOT_SUCCESSFUL")
        if not record.get("ended_utc") or record.get("evidence_errors") or record.get("clock_errors"):
            errors.append("INCOMPLETE_EVIDENCE")
        begin, end = record.get("started_monotonic_ns"), record.get("ended_monotonic_ns")
        if type(begin) is not int or type(end) is not int or begin < 0 or end < begin or started.get("started_monotonic_ns") != begin:
            errors.append("RECEIPT_CLOCK_MISMATCH")
        if (out / "capture.pending.json").exists():
            errors.append("CAPTURE_NOT_FINALIZED")
        guard = (out / "capture.complete.json").read_bytes()
        if _decode(guard) != {"protocol": PROTOCOL, "suite": "scan", "run_id": identity} or record.get("artifacts", {}).get("capture.pending.json") != {"bytes": len(guard), "sha256": hashlib.sha256(guard).hexdigest()}:
            errors.append("COMPLETION_GUARD_MISMATCH")
        contents = {}
        for name in ("run.started.json", "instrumentation.stdout.txt", "instrumentation.stderr.txt", "instrumentation.txt"):
            data = (out / name).read_bytes()
            if record.get("artifacts", {}).get(name) != {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}:
                errors.append("ARTIFACT_MISMATCH:" + name)
            contents[name] = data
        if contents["instrumentation.txt"] != contents["instrumentation.stdout.txt"] + contents["instrumentation.stderr.txt"]:
            errors.append("COMBINED_STREAM_MISMATCH")
        result = analyze(contents["instrumentation.stdout.txt"], contents["instrumentation.stderr.txt"], run_id=identity, expected_cases=expected, expected_terminal=expected_terminal)
        errors.extend(result["errors"])
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        errors.append("UNREADABLE_EVIDENCE:" + type(error).__name__)
    return {"accepted": not errors, "errors": errors, "validation": result, "product_acceptance": False}
