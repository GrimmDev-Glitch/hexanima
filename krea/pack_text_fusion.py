"""Extract Krea text-fusion weights into a compact Android-friendly pack."""
from __future__ import annotations

import argparse
import struct
from pathlib import Path

import torch
from safetensors import safe_open

MAGIC = b"KTF1"
ENTRY = struct.Struct("<128sQQII")  # name, elements, offset, dtype, reserved
PREFIXES = ("txtfusion.layerwise_blocks.0.", "txtfusion.layerwise_blocks.1.",
            "txtfusion.refiner_blocks.0.", "txtfusion.refiner_blocks.1.")


def selected(name: str) -> bool:
    return name == "txtfusion.projector.weight" or name.startswith("txtmlp.") or any(name.startswith(p) for p in PREFIXES)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoint", type=Path)
    ap.add_argument("output", type=Path)
    args = ap.parse_args()

    with safe_open(str(args.checkpoint), framework="pt", device="cpu") as src:
        names = [n for n in src.keys() if selected(n)]
        entries: list[bytes] = []
        specs: list[tuple[str, int, int]] = []
        offset = 8 + len(names) * ENTRY.size
        for name in names:
            sl = src.get_slice(name)
            count = 1
            for dim in sl.get_shape():
                count *= dim
            dtype = 0 if sl.get_dtype() == "BF16" else 1
            nbytes = count * (2 if dtype == 0 else 4)
            encoded = name.encode("utf-8")
            if len(encoded) >= 128:
                raise ValueError(f"weight name too long: {name}")
            entries.append(ENTRY.pack(encoded + bytes(128 - len(encoded)), count, offset, dtype, 0))
            specs.append((name, count, dtype))
            offset += nbytes

        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("wb") as out:
            out.write(struct.pack("<4sI", MAGIC, len(entries)))
            out.writelines(entries)
            for name, count, dtype in specs:
                t = src.get_tensor(name).to(device="cpu")
                if dtype == 0:
                    # Every source BF16 value is exactly representable in FP16.
                    a = t.to(torch.float16).contiguous().numpy().astype("<f2", copy=False)
                else:
                    a = t.to(torch.float32).contiguous().numpy().astype("<f4", copy=False)
                if a.size != count:
                    raise RuntimeError(f"tensor size changed while packing {name}")
                a.tofile(out)
    print(f"packed {len(names)} tensors, {offset:,} bytes -> {args.output}")


if __name__ == "__main__":
    main()
