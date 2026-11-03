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
MAGIC_V2 = 0x57454432


def decode(data):
    if not 48 <= len(data) <= MAX_BYTES:
        raise ValueError("invalid checkpoint length")
    payload, expected = data[:-32], data[-32:]
    if hashlib.sha256(payload).digest() != expected:
        raise ValueError("checkpoint checksum mismatch; input is not modified")
    magic, created = struct.unpack_from(">Iq", payload)
    if magic not in (MAGIC, MAGIC_V2) or created < 0:
        raise ValueError("unsupported checkpoint version or timestamp")
    offset = 12
    def text():
        nonlocal offset
        if offset + 4 > len(payload):
            raise ValueError("truncated field length")
        length = struct.unpack_from(">i", payload, offset)[0]
        offset += 4
        if length < 0 or offset + length > len(payload):
            raise ValueError("invalid field length")
        value = payload[offset:offset + length].decode("utf-8", errors="strict")
        offset += length
        return value
    document = text()
    document_version = text() if magic == MAGIC_V2 else ""
    submitted = False
    if magic == MAGIC_V2:
        if offset >= len(payload) or payload[offset] not in (0, 1):
            raise ValueError("invalid submitted flag")
        submitted = bool(payload[offset])
        offset += 1
    baseline, edited = text(), text()
    if offset != len(payload) or not baseline or not edited:
        raise ValueError("extra data or empty checkpoint fields")
    import re
    if not re.fullmatch(r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", document):
        raise ValueError("invalid document identifier")
    if document_version and not re.fullmatch(r"[a-f0-9]{64}", document_version):
        raise ValueError("invalid baseline document version")
    return {"version": 2 if magic == MAGIC_V2 else 1, "created_epoch_ms": created, "document": document,
            "baseline_document_version": document_version, "save_submitted": submitted,
            "baseline_json": baseline, "edited_json": edited,
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
