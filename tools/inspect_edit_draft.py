#!/usr/bin/env python3
"""Read-only inspection of an explicitly exported Wangshu edit checkpoint (.bin; internal .draft).

Default output excludes OCR/parameter contents. --json explicitly prints all stored
fields (including potentially private historic OCR text) to stdout. No imports,
network, file writes, deletions, or automatic application to a document.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

MAX_BYTES = 4 * 1024 * 1024
MAGIC = 0x57454431


def decode(data):
    if not 48 <= len(data) <= MAX_BYTES:
        raise ValueError("invalid checkpoint length")
    payload, expected = data[:-32], data[-32:]
    if hashlib.sha256(payload).digest() != expected:
        raise ValueError("checkpoint checksum mismatch; input is not modified")
    magic, created = struct.unpack_from(">Iq", payload)
    if magic != MAGIC or created < 0:
        raise ValueError("unsupported checkpoint version or timestamp")
    offset = 12
    fields = []
    for _ in range(3):
        if offset + 4 > len(payload):
            raise ValueError("truncated field length")
        length = struct.unpack_from(">i", payload, offset)[0]
        offset += 4
        if length < 0 or offset + length > len(payload):
            raise ValueError("invalid field length")
        fields.append(payload[offset:offset + length].decode("utf-8", errors="strict"))
        offset += length
    if offset != len(payload) or not fields[1] or not fields[2]:
        raise ValueError("extra data or empty checkpoint fields")
    import re
    if not re.fullmatch(r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", fields[0]):
        raise ValueError("invalid document identifier")
    return {"version": 1, "created_epoch_ms": created, "document": fields[0],
            "baseline_json": fields[1], "edited_json": fields[2],
            "file_sha256": hashlib.sha256(data).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("--json", action="store_true", help="print full stored strings, INCLUDING private historical OCR, to stdout")
    args = parser.parse_args()
    try:
        if args.checkpoint.is_symlink() or not args.checkpoint.is_file():
            raise ValueError("choose a regular checkpoint file, not a symbolic link")
        with args.checkpoint.open("rb") as stream:
            data = stream.read(MAX_BYTES + 1)
        record = decode(data)
        if not args.json:
            record = {k: v for k, v in record.items() if k not in ("baseline_json", "edited_json")}
            record["notice"] = "Metadata only. This is not an image/ZIP backup or an import operation. --json discloses stored private text."
        print(json.dumps(record, ensure_ascii=False, indent=2))
    except (OSError, ValueError, struct.error) as error:
        parser.exit(2, "Checkpoint not accepted: " + str(error) + "\n")


if __name__ == "__main__":
    main()
