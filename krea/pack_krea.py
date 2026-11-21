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
import gc
import json
import os
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
HEADER_MAGIC = {"q4a": b"K2B1", "q8": b"K2B2"}
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


def q8_pack(weight: np.ndarray) -> bytes:
    """Symmetric int8 tiles with one fp16 scale per output row and 32 inputs."""
    if weight.ndim != 2:
        raise ValueError(f"expected matrix, got {weight.shape}")
    out, inn = weight.shape
    if out % 32 or inn % 32:
        raise ValueError(f"q8 dimensions must be multiples of 32: {weight.shape}")
    groups = np.asarray(weight, dtype=np.float32).reshape(out, inn // 32, 32)
    scales = np.abs(groups).max(axis=2) / 127.0
    scales = np.where(scales == 0, 1.0, scales).astype(np.float16).astype(np.float32)
    q = np.clip(np.rint(groups / scales[:, :, None]), -127, 127).astype(np.int8)
    q = (q.astype(np.int16) + 128).astype(np.uint8).reshape(out, inn)
    nb, kb = out // 32, inn // 32
    # Tile order must match q4a and the HMX GEMM: [N block][K block][K/2][N][2].
    t = q.reshape(nb, 32, kb, 32).transpose(0, 2, 3, 1)
    t = t.reshape(nb, kb, 16, 2, 32).transpose(0, 1, 2, 4, 3).reshape(nb, kb, 1024)
    s = scales.reshape(nb, 32, kb).transpose(0, 2, 1).astype(np.float16)
    scale_bytes = np.repeat(s, 2, axis=2).view(np.uint8).reshape(nb, kb, 128)
    records = np.concatenate((scale_bytes, t), axis=2)
    return np.ascontiguousarray(records).tobytes()


def _tensor(f, name: str, shape: tuple[int, ...]) -> np.ndarray:
    try:
        t = f.get_tensor(name)
    except Exception as exc:
        raise ValueError(f"checkpoint is missing {name}") from exc
    if tuple(t.shape) != shape:
        raise ValueError(f"{name}: expected {shape}, got {tuple(t.shape)}")
    return t.float().numpy()


def _tensor_rows(f, name: str, shape: tuple[int, ...], first: int, last: int) -> np.ndarray:
    """Read only a row range so the two 16k-wide FFN matrices need not coexist in RAM."""
    try:
        view = f.get_slice(name)
        actual = tuple(view.get_shape())
        if actual != shape:
            raise ValueError(f"{name}: expected {shape}, got {actual}")
        return view[first:last, :].float().numpy()
    except ValueError:
        raise
    except Exception as exc:
        raise ValueError(f"checkpoint is missing or cannot slice {name}") from exc


def pack_block(f, layer: int, dest: Path, weight_format: str) -> dict:
    """Pack one block to a temporary file, releasing each source matrix as soon as possible."""
    p = f"blocks.{layer}."
    rows_cols = ((9216, FEATURES), (FEATURES, FEATURES), (FEATURES, FEATURES), (2 * FFN, FEATURES), (FEATURES, FFN))
    offsets = [0] * 8
    formats = [0] * 8
    nblocks = [0] * 8
    kblocks = [0] * 8
    for i, (n, kdim) in enumerate(rows_cols):
        formats[i] = 2 if weight_format == "q4a" else 1
        nblocks[i], kblocks[i] = n // 32, kdim // 32
    pack_matrix = q4a_pack if weight_format == "q4a" else q8_pack
    tmp = dest.with_name(dest.name + ".part")
    try:
        with tmp.open("wb") as out:
            out.write(bytes(HEADER_BYTES))
            for i in range(5):
                offsets[i] = out.tell()
                if i == 0:
                    q = _tensor(f, p + "attn.wq.weight", (FEATURES, FEATURES))
                    k = _tensor(f, p + "attn.wk.weight", (KV_HEADS * HEAD_DIM, FEATURES))
                    v = _tensor(f, p + "attn.wv.weight", (KV_HEADS * HEAD_DIM, FEATURES))
                    matrix = np.concatenate((q, k, v), axis=0)
                    del q, k, v
                    out.write(pack_matrix(matrix))
                    del matrix
                elif i == 1:
                    out.write(pack_matrix(_tensor(f, p + "attn.gate.weight", (FEATURES, FEATURES))))
                elif i == 2:
                    out.write(pack_matrix(_tensor(f, p + "attn.wo.weight", (FEATURES, FEATURES))))
                elif i == 3:
                    gate_name, up_name = p + "mlp.gate.weight", p + "mlp.up.weight"
                    # Preserve the current 32-row gate/up tile interleave without building
                    # the 1.6 GB combined projection in host RAM.
                    for first in range(0, FFN, 32):
                        gate = _tensor_rows(f, gate_name, (FFN, FEATURES), first, first + 32)
                        up = _tensor_rows(f, up_name, (FFN, FEATURES), first, first + 32)
                        rows = np.concatenate((gate, up), axis=0)
                        out.write(pack_matrix(rows))
                        del gate, up, rows
                else:
                    out.write(pack_matrix(_tensor(f, p + "mlp.down.weight", (FEATURES, FFN))))
                gc.collect()

            offsets[5] = out.tell()
            gains = []
            for name, size in (("attn.qknorm.qnorm.scale", HEAD_DIM), ("attn.qknorm.knorm.scale", HEAD_DIM),
                               ("prenorm.scale", FEATURES), ("postnorm.scale", FEATURES)):
                # Krea's RMSNorm stores a zero-centered scale parameter; forward uses scale + 1.
                gains.append(_tensor(f, p + name, (size,)) + 1.0)
            # HMX consumes unscaled QK products: fold PyTorch's 1/sqrt(head_dim) into Q gain.
            gains[0] /= np.sqrt(np.float32(HEAD_DIM))
            out.write(np.concatenate(gains).astype(np.float32).tobytes())
            del gains
            offsets[6] = out.tell()
            out.write(_tensor(f, p + "mod.lin", (6 * FEATURES,)).astype(np.float32).tobytes())
            header = struct.pack("<4s8I8I8I8I", HEADER_MAGIC[weight_format], *offsets, *formats, *nblocks, *kblocks)
            out.seek(0)
            out.write(header + bytes(HEADER_BYTES - len(header)))
            out.flush()
            os.fsync(out.fileno())
        tmp.replace(dest)
    except Exception:
        tmp.unlink(missing_ok=True)
        raise

    info = {
        "layer": layer,
        "format": f"{HEADER_MAGIC[weight_format].decode()}/{weight_format}",
        "matrices": {name: {"offset": offsets[i], "rows": rows_cols[i][0], "cols": rows_cols[i][1], "format": weight_format} for i, name in enumerate(MATRICES)},
        "gains_offset": offsets[5],
        "modulation_offset": offsets[6],
        "bytes": dest.stat().st_size,
    }
    return info


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("checkpoint", type=Path, help="Krea 2 Turbo safetensors checkpoint")
    ap.add_argument("outdir", type=Path)
    ap.add_argument("--first", type=int, default=0)
    ap.add_argument("--last", type=int, default=BLOCKS - 1)
    ap.add_argument("--weight-format", choices=("q4a", "q8"), default="q4a")
    args = ap.parse_args()
    if args.first < 0 or args.last >= BLOCKS or args.first > args.last:
        ap.error(f"layer range must be within 0..{BLOCKS - 1}")
    if not args.checkpoint.is_file():
        ap.error(f"checkpoint not found: {args.checkpoint}")

    args.outdir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "format": HEADER_MAGIC[args.weight_format].decode(),
        "checkpoint": args.checkpoint.name,
        "architecture": {"features": FEATURES, "heads": HEADS, "kv_heads": KV_HEADS, "head_dim": HEAD_DIM, "ffn": FFN, "blocks": BLOCKS},
        "quantization": f"{'asymmetric q4a' if args.weight_format == 'q4a' else 'symmetric int8 q8'}, groups of 32 input values per output channel",
        "layers": [],
    }
    with safe_open(str(args.checkpoint), framework="pt", device="cpu") as f:
        keys = set(f.keys())
        required = {f"blocks.{i}.attn.wq.weight" for i in range(args.first, args.last + 1)}
        missing = sorted(required - keys)
        if missing:
            raise ValueError(f"checkpoint does not look like the official Krea 2 Turbo layout; missing {missing[0]}")
        for i in range(args.first, args.last + 1):
            dest = args.outdir / f"blk{i:02d}.bin"
            info = pack_block(f, i, dest, args.weight_format)
            manifest["layers"].append(info)
            print(f"layer {i}: {info['bytes'] / 1e6:.1f} MB -> {dest}", flush=True)
    (args.outdir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"packed {len(manifest['layers'])} blocks; runtime support is still experimental", flush=True)


if __name__ == "__main__":
    main()
