#!/usr/bin/env python3
"""Write deterministic, synthetic input tensors for the Krea block smoke test."""
import argparse
import math
import struct
from pathlib import Path

D = 6144
HEAD_DIM = 128


def write_f32(path: Path, values):
    with path.open("wb") as f:
        for start in range(0, len(values), 4096):
            f.write(struct.pack("<%df" % len(values[start:start + 4096]), *values[start:start + 4096]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("directory", type=Path)
    ap.add_argument("--ntok", type=int, default=32)
    args = ap.parse_args()
    n = args.ntok
    if n <= 0 or n % 32:
        raise SystemExit("ntok must be a positive multiple of 32")
    args.directory.mkdir(parents=True, exist_ok=True)

    # Small nonzero, finite signal in the DSP's [feature tile][token][feature] layout.
    x = [0.05 * math.sin((r * D + c) * 0.001)
         for slab in range(n // 32) for cb in range(D // 32)
         for r in range(slab * 32, (slab + 1) * 32)
         for c in range(cb * 32, (cb + 1) * 32)]
    write_f32(args.directory / "x32.bin", x)
    # Position zero is identity RoPE; remaining positions use identity too so
    # the smoke check isolates dispatch, tensor geometry and kernel execution.
    write_f32(args.directory / "cos.bin", [1.0] * (n * (HEAD_DIM // 2)))
    write_f32(args.directory / "sin.bin", [0.0] * (n * (HEAD_DIM // 2)))
    # Six modulation rows (shift/scale/gate for attn and MLP), all neutral.
    write_f32(args.directory / "mod.bin", [0.0] * (6 * D))
    (args.directory / "SMOKE_FIXTURE.txt").write_text(
        "Synthetic tensors only; not Krea checkpoint data.\n"
        f"ntok={n}, dim={D}; zero synthetic K2B1 weights are supplied separately.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
