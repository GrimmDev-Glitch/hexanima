"""Create a sparse all-zero K2B1 pack for a V73 runner smoke test.

The pack contains no Krea model data and must never be used for generation.
"""
from __future__ import annotations

import json
import os
import struct
import sys
from pathlib import Path

D, FFN, HEAD = 6144, 16384, 128
HEADER = 4096
SHAPES = ((9216, D), (D, D), (D, D), (2 * FFN, D), (D, FFN))


def main() -> None:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "krea-synthetic")
    out.mkdir(parents=True, exist_ok=True)
    offs, fmts, nbs, kbs = [0] * 8, [0] * 8, [0] * 8, [0] * 8
    at = HEADER
    for i, (n, k) in enumerate(SHAPES):
        offs[i] = at
        fmts[i], nbs[i], kbs[i] = 2, n // 32, k // 32
        at += nbs[i] * kbs[i] * 768
    offs[5] = at
    at += (2 * HEAD + 2 * D) * 4
    offs[6] = at
    at += 6 * D * 4
    header = struct.pack("<4s8I8I8I8I", b"K2B1", *offs, *fmts, *nbs, *kbs)
    header += bytes(HEADER - len(header))
    pack = out / "blk00.bin"
    with pack.open("wb") as f:
        f.write(header)
        f.truncate(at)
        f.seek(offs[5])
        gains = [1.0 / (HEAD**0.5)] * HEAD + [1.0] * HEAD + [1.0] * D + [1.0] * D
        f.write(struct.pack(f"<{len(gains)}f", *gains))
        f.seek(offs[6])
        f.write(bytes(6 * D * 4))
    (out / "README.txt").write_text(
        "Synthetic zero-weight K2B1 runner fixture. Not Krea weights; output should equal input.\n",
        encoding="utf-8",
    )
    print(f"wrote sparse fixture: {pack} ({at:,} bytes)")


if __name__ == "__main__":
    main()
