"""Pack the official Qwen Image VAE weights for the local Android CPU decoder."""
from __future__ import annotations

import argparse
import struct
from pathlib import Path

import numpy as np
import torch
from safetensors import safe_open

MAGIC = b"KVA1"
ENTRY = struct.Struct("<128sQQ")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoint", type=Path)
    ap.add_argument("output", type=Path)
    args = ap.parse_args()
    with safe_open(str(args.checkpoint), framework="pt") as src:
        names = list(src.keys())
        payload_start = 8 + len(names) * ENTRY.size
        entries = []
        offset = payload_start
        arrays = []
        for name in names:
            arr = src.get_tensor(name).to(dtype=torch.float32, device="cpu").contiguous().numpy().astype("<f4", copy=False)
            data = arr.tobytes(order="C")
            encoded = name.encode("utf-8")
            if len(encoded) >= 128:
                raise ValueError(f"weight name too long for pack format: {name}")
            entries.append(ENTRY.pack(encoded + b"\0" * (128 - len(encoded)), arr.size, offset))
            arrays.append(data)
            offset += len(data)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as out:
        out.write(struct.pack("<4sI", MAGIC, len(entries)))
        out.writelines(entries)
        out.writelines(arrays)
    print(f"packed {len(entries)} tensors, {offset:,} bytes -> {args.output}")


if __name__ == "__main__":
    main()
