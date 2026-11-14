#!/usr/bin/env python3
"""Verify an exported overlay pack against its base and fused Qwen blocks."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path

from make_qwen_overlays import block_path, glue_header

HEADER = struct.Struct("<I")
RECORD = struct.Struct("<QI")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apply_overlay(base: Path, overlay: Path, expected_records: int) -> str:
    data = bytearray(base.read_bytes())
    encoded = overlay.read_bytes()
    if len(encoded) < HEADER.size:
        raise ValueError(f"{overlay}: missing record count")
    (count,) = HEADER.unpack_from(encoded)
    if count != expected_records:
        raise ValueError(f"{overlay}: manifest says {expected_records} records, file says {count}")
    cursor, previous_end = HEADER.size, 0
    for _ in range(count):
        if cursor + RECORD.size > len(encoded):
            raise ValueError(f"{overlay}: truncated record header")
        offset, length = RECORD.unpack_from(encoded, cursor)
        cursor += RECORD.size
        end = offset + length
        if length == 0 or offset < previous_end or end > len(data) or cursor + length > len(encoded):
            raise ValueError(f"{overlay}: invalid record at offset {offset} length {length}")
        data[offset:end] = encoded[cursor:cursor + length]
        cursor += length
        previous_end = end
    if cursor != len(encoded):
        raise ValueError(f"{overlay}: trailing bytes")
    return hashlib.sha256(data).hexdigest()


def check_file(path: Path, expected: str) -> None:
    actual = sha256(path)
    if actual != expected:
        raise ValueError(f"{path}: SHA-256 {actual} != manifest {expected}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_pack", type=Path)
    parser.add_argument("fused_pack", type=Path)
    parser.add_argument("overlay_dir", type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.overlay_dir / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("format") != "hexanima-qwen-overlay-v1" or manifest.get("chunk_bytes") != 4096:
        raise ValueError("unsupported overlay manifest format")
    blocks = manifest.get("blocks", [])
    if (manifest.get("first"), manifest.get("last")) != (0, 31) or len(blocks) != 32:
        raise ValueError("a complete Qwen overlay must contain blocks 0 through 31")
    for index, entry in enumerate(blocks):
        if entry.get("block") != index:
            raise ValueError(f"manifest block order is invalid at {index}")
        name = f"blk{index:02d}"
        base = block_path(args.base_pack, name + ".bin")
        fused = block_path(args.fused_pack, name + ".bin")
        overlay = args.overlay_dir / (name + ".ovl")
        for path, key in ((base, "base_sha256"), (fused, "lora_pack_sha256"), (overlay, "overlay_sha256")):
            check_file(path, entry[key])
        if base.stat().st_size != fused.stat().st_size:
            raise ValueError(f"{name}: base and fused block sizes differ")
        if overlay.stat().st_size != entry.get("bytes"):
            raise ValueError(f"{name}: overlay byte size differs from manifest")
        result = apply_overlay(base, overlay, int(entry["records"]))
        if result != entry["lora_pack_sha256"]:
            raise ValueError(f"{name}: overlay does not reconstruct the fused block")
        print(f"{name}: verified ({overlay.stat().st_size} overlay bytes)", flush=True)

    base_glue, fused_glue = args.base_pack / "glue.bin", args.fused_pack / "glue.bin"
    glue_entry = manifest.get("glue")
    if not glue_entry:
        raise ValueError("complete overlay manifest is missing glue metadata")
    glue_overlay = args.overlay_dir / "glue.ovl"
    if glue_header(base_glue) != glue_header(fused_glue):
        raise ValueError("base and fused glue headers differ")
    for path, key in ((base_glue, "base_sha256"), (fused_glue, "lora_pack_sha256"), (glue_overlay, "overlay_sha256")):
        check_file(path, glue_entry[key])
    if apply_overlay(base_glue, glue_overlay, int(glue_entry["records"])) != glue_entry["lora_pack_sha256"]:
        raise ValueError("glue overlay does not reconstruct the fused glue")
    print("glue.bin: verified")
    print("complete Qwen overlay pack: OK")


if __name__ == "__main__":
    main()
