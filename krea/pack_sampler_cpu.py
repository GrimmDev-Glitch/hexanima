"""Pack only the Krea sampling, patch and final-layer tensors for the phone runner."""
from __future__ import annotations

import argparse
import struct
from pathlib import Path

import torch
from safetensors import safe_open

MAGIC = b"KTF1"
ENTRY = struct.Struct("<128sQQII")
NAMES = (
    "tmlp.0.weight", "tmlp.0.bias", "tmlp.2.weight", "tmlp.2.bias",
    "tproj.1.weight", "tproj.1.bias",
    "first.weight", "first.bias",
    "last.modulation.lin", "last.norm.scale", "last.linear.weight", "last.linear.bias",
)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoint", type=Path)
    ap.add_argument("output", type=Path)
    args = ap.parse_args()
    with safe_open(str(args.checkpoint), framework="pt", device="cpu") as src:
        offset = 8 + len(NAMES) * ENTRY.size
        entries: list[bytes] = []
        counts: dict[str, int] = {}
        for name in NAMES:
            shape = src.get_slice(name).get_shape()
            count = 1
            for d in shape:
                count *= d
            counts[name] = count
            encoded = name.encode()
            entries.append(ENTRY.pack(encoded + bytes(128 - len(encoded)), count, offset, 1, 0))
            offset += count * 4
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("wb") as out:
            out.write(struct.pack("<4sI", MAGIC, len(NAMES)))
            out.writelines(entries)
            for name in NAMES:
                a = src.get_tensor(name).to(dtype=torch.float32, device="cpu").contiguous().numpy().astype("<f4", copy=False)
                if a.size != counts[name]:
                    raise RuntimeError(f"unexpected tensor shape for {name}")
                a.tofile(out)
    print(f"packed {len(NAMES)} sampler tensors, {offset:,} bytes -> {args.output}")


if __name__ == "__main__":
    main()
