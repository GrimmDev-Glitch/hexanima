#!/usr/bin/env python3
"""Create compact runtime overlays from two already packed Qwen DiT trees.

Usage: python make_qwen_overlays.py BASE_PACK LORA_PACK OUT_DIR [--first 0] [--last 31]

Each output blkNN.ovl (and glue.ovl when both packs contain glue.bin) uses
the format consumed by qwenrun: little-endian u32
record count, followed by records of little-endian u64 byte offset, u32 length,
and replacement bytes. Only changed 4 KiB chunks are stored. The LORA_PACK
blocks must have been produced by export_qwen_lora.py for the same base pack.
This tool does not convert a raw Civitai safetensors file by itself.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
from pathlib import Path

CHUNK = 4096
READ_BATCH = 1 << 20
HEADER = struct.Struct("<I")
RECORD = struct.Struct("<QI")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def block_path(root: Path, name: str) -> Path:
    nested = root / "dit" / name
    return nested if nested.is_file() else root / name


def glue_header(path: Path) -> bytes:
    with path.open("rb") as file:
        prefix = file.read(8)
        if len(prefix) != 8 or prefix[:4] != b"MGL1":
            raise ValueError(f"{path}: invalid glue header")
        count = struct.unpack_from("<I", prefix, 4)[0]
        length = 8 + count * 76
        if count == 0 or length > path.stat().st_size:
            raise ValueError(f"{path}: invalid glue entry count")
        header = prefix + file.read(length - 8)
        if len(header) != length:
            raise ValueError(f"{path}: truncated glue header")
        return header


def make_one(base: Path, tuned: Path, out: Path) -> tuple[int, int]:
    a_size, b_size = base.stat().st_size, tuned.stat().st_size
    if a_size != b_size:
        raise ValueError(f"{base.name}: block sizes differ ({a_size} vs {b_size})")
    tmp = out.with_name(out.name + ".tmp")
    count = 0
    try:
        with base.open("rb") as a, tuned.open("rb") as b, tmp.open("wb") as f:
            f.write(HEADER.pack(0))
            off = 0
            while off < a_size:
                aa = a.read(min(READ_BATCH, a_size - off))
                bb = b.read(len(aa))
                if not aa or len(aa) != len(bb):
                    raise OSError(f"short read while comparing {base.name}")
                for pos in range(0, len(aa), CHUNK):
                    base_chunk, tuned_chunk = aa[pos:pos + CHUNK], bb[pos:pos + CHUNK]
                    if base_chunk != tuned_chunk:
                        f.write(RECORD.pack(off + pos, len(tuned_chunk)))
                        f.write(tuned_chunk)
                        count += 1
                off += len(aa)
            f.seek(0)
            f.write(HEADER.pack(count))
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, out)
    except Exception:
        tmp.unlink(missing_ok=True)
        raise
    return count, out.stat().st_size


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("base_pack", type=Path)
    p.add_argument("lora_pack", type=Path)
    p.add_argument("out_dir", type=Path)
    p.add_argument("--first", type=int, default=0)
    p.add_argument("--last", type=int, default=31)
    args = p.parse_args()
    if args.first < 0 or args.last < args.first or args.last > 31:
        p.error("block range must satisfy 0 <= first <= last <= 31")
    args.out_dir.mkdir(parents=True, exist_ok=True)
    entries = []
    for i in range(args.first, args.last + 1):
        name = f"blk{i:02d}.bin"
        base, tuned = block_path(args.base_pack, name), block_path(args.lora_pack, name)
        if not base.is_file() or not tuned.is_file():
            raise FileNotFoundError(f"missing {name} in base or LoRA pack")
        out = args.out_dir / f"blk{i:02d}.ovl"
        count, size = make_one(base, tuned, out)
        entries.append({"block": i, "base_sha256": sha256(base), "lora_pack_sha256": sha256(tuned),
                        "overlay_sha256": sha256(out), "records": count, "bytes": size})
        print(f"{name}: {count} changed chunks, {size} overlay bytes", flush=True)
    base_glue, tuned_glue = args.base_pack / "glue.bin", args.lora_pack / "glue.bin"
    if base_glue.is_file() != tuned_glue.is_file():
        raise FileNotFoundError("glue.bin must exist in both packs when a LoRA glue overlay is requested")
    glue_entry = None
    if base_glue.is_file():
        if glue_header(base_glue) != glue_header(tuned_glue):
            raise ValueError("base and LoRA glue headers differ")
        glue_out = args.out_dir / "glue.ovl"
        count, size = make_one(base_glue, tuned_glue, glue_out)
        glue_entry = {"base_sha256": sha256(base_glue), "lora_pack_sha256": sha256(tuned_glue),
                      "overlay_sha256": sha256(glue_out), "records": count, "bytes": size}
        print(f"glue.bin: {count} changed chunks, {size} overlay bytes", flush=True)
    (args.out_dir / "manifest.json").write_text(json.dumps({
        "format": "hexanima-qwen-overlay-v1", "chunk_bytes": CHUNK,
        "first": args.first, "last": args.last, "blocks": entries, "glue": glue_entry,
    }, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
