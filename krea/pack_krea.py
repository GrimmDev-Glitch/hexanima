"""Pack Krea 2 Turbo transformer blocks for HexAnima's tiled HMX path.

The first format is an experimental, versioned q4a pack. It is deliberately
kept separate from the shipping model catalog until the matching V73 runner
and numerical checks exist.

Usage:
    python krea/pack_krea.py turbo.safetensors OUTDIR [--first 0 --last 27]

Each blkNN.bin contains a 4096-byte K2B1 header, five q4a matrices, and the
block's RMSNorm/QKNorm gains plus six-way timestep modulation vector.
"""
from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

import numpy as np
from safetensors import safe_open


FEATURES = 6144
HEADS = 48
KV_HEADS = 12
HEAD_DIM = FEATURES // HEADS
FFN = 16384
BLOCKS = 28
HEADER_BYTES = 4096
HEADER_MAGIC = b"K2B1"
MATRICES = ("qkv", "attn_gate", "attn_out", "ffn_gate_up", "ffn_down")


def interleave_rows(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Interleave 32-row output groups: gate[0:32], up[0:32], ..."""
    if a.shape != b.shape or a.shape[0] % 32:
        raise ValueError(f"incompatible gated projections: {a.shape} vs {b.shape}")
    return np.stack((a.reshape(-1, 32, a.shape[1]), b.reshape(-1, 32, b.shape[1])), axis=1).reshape(-1, a.shape[1])


def q4a_pack(weight: np.ndarray) -> bytes:
    """Quantize [out, in] by groups of 32 inputs and emit DSP q4a tiles.

    Each tile stores 128 B fp16 scales, 128 B fp16 minima, and 512 B of
    packed nibbles. Dequantization is q * scale + minimum.
    """
    if weight.ndim != 2:
        raise ValueError(f"expected matrix, got {weight.shape}")
    out, inn = weight.shape
    if out % 32 or inn % 32:
        raise ValueError(f"q4a dimensions must be multiples of 32: {weight.shape}")

    groups = np.asarray(weight, dtype=np.float32).reshape(out, inn // 32, 32)
    mins = groups.min(axis=2)
    maxs = groups.max(axis=2)
    scales = (maxs - mins) / 15.0
    scales = np.maximum(scales, np.finfo(np.float16).tiny).astype(np.float16).astype(np.float32)
    q = np.clip(np.rint((groups - mins[:, :, None]) / scales[:, :, None]), 0, 15).astype(np.uint8)
    q = q.reshape(out, inn)
    mins = mins.reshape(out, inn // 32)
    scales = scales.reshape(out, inn // 32)

    nb, kb = out // 32, inn // 32
    t = q.reshape(nb, 32, kb, 32).transpose(0, 2, 3, 1)
    t = t.reshape(nb, kb, 16, 2, 32).transpose(0, 1, 2, 4, 3).reshape(nb, kb, 1024)
    nibbles = t.reshape(nb, kb, 4, 2, 128)
    packed_q = (nibbles[:, :, :, 0] | (nibbles[:, :, :, 1] << 4)).astype(np.uint8)

    s = scales.reshape(nb, 32, kb).transpose(0, 2, 1).astype(np.float16)
    m = mins.reshape(nb, 32, kb).transpose(0, 2, 1).astype(np.float16)
    s_bytes = np.repeat(s, 2, axis=2).view(np.uint8).reshape(nb, kb, 128)
    m_bytes = np.repeat(m, 2, axis=2).view(np.uint8).reshape(nb, kb, 128)
    records = np.concatenate((s_bytes, m_bytes, packed_q.reshape(nb, kb, 512)), axis=2)
    return np.ascontiguousarray(records).tobytes()


def _tensor(f, name: str, shape: tuple[int, ...]) -> np.ndarray:
    try:
        t = f.get_tensor(name)
    except Exception as exc:
        raise ValueError(f"checkpoint is missing {name}") from exc
    if tuple(t.shape) != shape:
        raise ValueError(f"{name}: expected {shape}, got {tuple(t.shape)}")
    return t.float().numpy()


def pack_block(f, layer: int) -> tuple[bytes, dict]:
    p = f"blocks.{layer}."
    matrix_data = []
    rows_cols = []

    q = _tensor(f, p + "attn.wq.weight", (FEATURES, FEATURES))
    k = _tensor(f, p + "attn.wk.weight", (KV_HEADS * HEAD_DIM, FEATURES))
    v = _tensor(f, p + "attn.wv.weight", (KV_HEADS * HEAD_DIM, FEATURES))
    matrix_data.append(np.concatenate((q, k, v), axis=0))
    rows_cols.append(matrix_data[-1].shape)

    for name, shape in (
        ("attn.gate.weight", (FEATURES, FEATURES)),
        ("attn.wo.weight", (FEATURES, FEATURES)),
    ):
        matrix_data.append(_tensor(f, p + name, shape))
        rows_cols.append(shape)

    gate = _tensor(f, p + "mlp.gate.weight", (FFN, FEATURES))
    up = _tensor(f, p + "mlp.up.weight", (FFN, FEATURES))
    matrix_data.append(interleave_rows(gate, up))
    rows_cols.append(matrix_data[-1].shape)
    matrix_data.append(_tensor(f, p + "mlp.down.weight", (FEATURES, FFN)))
    rows_cols.append((FEATURES, FFN))

    offsets = [0] * 8
    formats = [0] * 8
    nblocks = [0] * 8
    kblocks = [0] * 8
    payload = bytearray()
    for i, (name, matrix, (n, kdim)) in enumerate(zip(MATRICES, matrix_data, rows_cols)):
        offsets[i] = HEADER_BYTES + len(payload)
        formats[i] = 2  # q4a, same record format as existing HMX GEMM kernels
        nblocks[i], kblocks[i] = n // 32, kdim // 32
        payload.extend(q4a_pack(matrix))

    gains = []
    for name, size in (
        ("attn.qknorm.qnorm.scale", HEAD_DIM),
        ("attn.qknorm.knorm.scale", HEAD_DIM),
        ("prenorm.scale", FEATURES),
        ("postnorm.scale", FEATURES),
    ):
        # Krea's RMSNorm stores a zero-centered scale parameter; forward uses scale + 1.
        gains.append(_tensor(f, p + name, (size,)) + 1.0)
    # The shared HMX attention path consumes unscaled QK dot products. Fold
    # PyTorch's 1/sqrt(head_dim) attention factor into Q's RMSNorm gain.
    gains[0] /= np.sqrt(np.float32(HEAD_DIM))
    gain_blob = np.concatenate(gains).astype(np.float32).tobytes()
    offsets[5] = HEADER_BYTES + len(payload)
    payload.extend(gain_blob)

    modulation = _tensor(f, p + "mod.lin", (6 * FEATURES,)).astype(np.float32).tobytes()
    offsets[6] = HEADER_BYTES + len(payload)
    payload.extend(modulation)

    header = struct.pack("<4s8I8I8I8I", HEADER_MAGIC, *offsets, *formats, *nblocks, *kblocks)
    header += bytes(HEADER_BYTES - len(header))
    info = {
        "layer": layer,
        "format": "K2B1/q4a",
        "matrices": {name: {"offset": offsets[i], "rows": rows_cols[i][0], "cols": rows_cols[i][1], "format": "q4a"} for i, name in enumerate(MATRICES)},
        "gains_offset": offsets[5],
        "modulation_offset": offsets[6],
        "bytes": HEADER_BYTES + len(payload),
    }
    return header + bytes(payload), info


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("checkpoint", type=Path, help="Krea 2 Turbo safetensors checkpoint")
    ap.add_argument("outdir", type=Path)
    ap.add_argument("--first", type=int, default=0)
    ap.add_argument("--last", type=int, default=BLOCKS - 1)
    args = ap.parse_args()
    if args.first < 0 or args.last >= BLOCKS or args.first > args.last:
        ap.error(f"layer range must be within 0..{BLOCKS - 1}")
    if not args.checkpoint.is_file():
        ap.error(f"checkpoint not found: {args.checkpoint}")

    args.outdir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "format": "K2B1",
        "checkpoint": args.checkpoint.name,
        "architecture": {"features": FEATURES, "heads": HEADS, "kv_heads": KV_HEADS, "head_dim": HEAD_DIM, "ffn": FFN, "blocks": BLOCKS},
        "quantization": "asymmetric q4a, groups of 32 input values per output channel",
        "layers": [],
    }
    with safe_open(str(args.checkpoint), framework="pt", device="cpu") as f:
        keys = set(f.keys())
        required = {f"blocks.{i}.attn.wq.weight" for i in range(args.first, args.last + 1)}
        missing = sorted(required - keys)
        if missing:
            raise ValueError(f"checkpoint does not look like the official Krea 2 Turbo layout; missing {missing[0]}")
        for i in range(args.first, args.last + 1):
            data, info = pack_block(f, i)
            dest = args.outdir / f"blk{i:02d}.bin"
            dest.write_bytes(data)
            manifest["layers"].append(info)
            print(f"layer {i}: {len(data) / 1e6:.1f} MB -> {dest}", flush=True)
    (args.outdir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"packed {len(manifest['layers'])} blocks; runtime support is still experimental", flush=True)


if __name__ == "__main__":
    main()
