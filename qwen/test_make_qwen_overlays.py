#!/usr/bin/env python3
"""Small format check for make_qwen_overlays.py; run with python3 qwen/test_make_qwen_overlays.py."""
import struct
import tempfile
from pathlib import Path

from make_qwen_overlays import glue_header, make_one


def apply_overlay(base: bytes, overlay: bytes) -> bytes:
    (count,) = struct.unpack_from("<I", overlay)
    pos = 4
    out = bytearray(base)
    for _ in range(count):
        offset, size = struct.unpack_from("<QI", overlay, pos)
        pos += 12
        out[offset:offset + size] = overlay[pos:pos + size]
        pos += size
    assert pos == len(overlay), "overlay has trailing or truncated data"
    return bytes(out)


with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    base = bytes((i * 17) & 255 for i in range(10000))
    tuned = bytearray(base)
    tuned[4090:4110] = bytes(range(20))
    tuned[-1] ^= 0x55
    bp, tp, op = root / "base.bin", root / "tuned.bin", root / "test.ovl"
    bp.write_bytes(base)
    tp.write_bytes(tuned)
    records, size = make_one(bp, tp, op)
    assert records == 3 and size == op.stat().st_size
    assert apply_overlay(base, op.read_bytes()) == bytes(tuned)

    # A glue overlay may alter tensor bytes while preserving the MGL1 index.
    header = b"MGL1" + struct.pack("<I", 1) + bytes(76)
    glue_base = header + bytes(5000 - len(header))
    glue_tuned = bytearray(glue_base)
    glue_tuned[3000] = 42
    bp.write_bytes(glue_base)
    tp.write_bytes(glue_tuned)
    assert glue_header(bp) == glue_header(tp)
    make_one(bp, tp, op)
    assert apply_overlay(glue_base, op.read_bytes()) == bytes(glue_tuned)
    glue_tuned[20] = 1
    tp.write_bytes(glue_tuned)
    assert glue_header(bp) != glue_header(tp)

print("Qwen overlay format round-trip: OK")
