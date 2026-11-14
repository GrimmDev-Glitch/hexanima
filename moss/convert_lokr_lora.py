#!/usr/bin/env python3
"""Convert this MOSS LyCORIS LoKr into the rank-limited A/B format used by HexAnima.

LoKr is represented exactly as kron(w1, w2). The HexAnima MOSS overlay has
128 ranks, so each product is approximated by the best 128 singular components
of that Kronecker product. This conversion is intentionally offline: the full
SVDs are expensive and should not run on the phone.

Usage: python convert_lokr_lora.py INPUT.safetensors OUTPUT.safetensors [RANK]
Requires torch and safetensors. Output tensors use BF16 to preserve the source
format and keep the converted file reasonably small.
"""
import json
import math
import os
import sys

import torch
from safetensors import safe_open
from safetensors.torch import save_file


PROJECTIONS = {
    "qkv": ("qkv",),
    "o": ("o",),
    "ffn_up_gate": ("ffn_up_gate",),
    "ffn_down": ("ffn_down",),
}
MAX_RANK = 128


def top_kronecker_svd(w1, w2, rank):
    """Return A[rank,in], B[out,rank] for the best rank approximation to kron(w1,w2)."""
    u1, s1, vh1 = torch.linalg.svd(w1.float(), full_matrices=False)
    u2, s2, vh2 = torch.linalg.svd(w2.float(), full_matrices=False)
    products = (s1[:, None] * s2[None, :]).reshape(-1)
    take = min(rank, products.numel())
    values, indices = torch.topk(products, take, sorted=True)
    b = torch.empty((w1.shape[0] * w2.shape[0], take), dtype=torch.float32)
    a = torch.empty((take, w1.shape[1] * w2.shape[1]), dtype=torch.float32)
    n2 = s2.numel()
    for k, flat in enumerate(indices.tolist()):
        i, j = divmod(flat, n2)
        root = math.sqrt(max(0.0, float(values[k])))
        b[:, k] = torch.kron(u1[:, i], u2[:, j]) * root
        a[k, :] = torch.kron(vh1[i, :], vh2[j, :]) * root
    total_sq = float(s1.square().sum() * s2.square().sum())
    kept_sq = float(values.square().sum())
    rel_error = math.sqrt(max(0.0, 1.0 - kept_sq / max(total_sq, 1e-30)))
    return a, b, rel_error


def main():
    if len(sys.argv) not in (3, 4):
        raise SystemExit(__doc__)
    src, dst = sys.argv[1:3]
    rank = int(sys.argv[3]) if len(sys.argv) == 4 else MAX_RANK
    if rank < 1 or rank > MAX_RANK:
        raise SystemExit(f"rank must be 1..{MAX_RANK} (HexAnima overlay limit)")
    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    tensors = {}
    stats = []
    with safe_open(src, framework="pt", device="cpu") as sf:
        keys = set(sf.keys())
        for layer in range(42):
            for proj, _ in PROJECTIONS.items():
                prefix = f"diffusion_model.layers.{layer}.proj.{proj}."
                k1, k2 = prefix + "lokr_w1", prefix + "lokr_w2"
                if k1 not in keys and k2 not in keys:
                    continue
                if k1 not in keys or k2 not in keys:
                    raise ValueError(f"incomplete LoKr pair at layer {layer} {proj}")
                w1, w2 = sf.get_tensor(k1), sf.get_tensor(k2)
                if w1.ndim != 2 or w2.ndim != 2 or w1.dtype not in (torch.float16, torch.bfloat16, torch.float32) or w2.dtype not in (torch.float16, torch.bfloat16, torch.float32):
                    raise ValueError(f"unsupported LoKr tensors at layer {layer} {proj}: {w1.shape}, {w2.shape}")
                a, b, error = top_kronecker_svd(w1, w2, rank)
                tensors[prefix + "lora_A.weight"] = a.to(torch.bfloat16).contiguous()
                tensors[prefix + "lora_B.weight"] = b.to(torch.bfloat16).contiguous()
                tensors[prefix + "alpha"] = torch.tensor(float(rank), dtype=torch.bfloat16)
                stats.append({"layer": layer, "projection": proj, "rank": rank, "relative_frobenius_error": error})
                print(f"layer {layer:02d} {proj:12s} rank {rank} LoKr truncation {error:.5%}", flush=True)
                del w1, w2, a, b
    if not stats:
        raise ValueError("no direct LoKr pairs found")
    metadata = {
        "ss_network_module": "lycoris.kohya",
        "ss_network_dim": str(rank),
        "ss_network_alpha": str(rank),
        "ss_base_model_version": "moss_image",
        "modelspec.architecture": "MOSS-Image",
        "hexanima.conversion": "direct LoKr Kronecker SVD, truncated to rank 128",
    }
    save_file(tensors, dst, metadata=metadata)
    report = {"source": os.path.basename(src), "output": os.path.basename(dst), "rank": rank, "pairs": len(stats), "max_relative_frobenius_error": max(x["relative_frobenius_error"] for x in stats), "mean_relative_frobenius_error": sum(x["relative_frobenius_error"] for x in stats) / len(stats), "modules": stats}
    with open(dst + ".conversion.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    print(json.dumps({k: report[k] for k in ("pairs", "rank", "max_relative_frobenius_error", "mean_relative_frobenius_error")}, indent=2))


if __name__ == "__main__":
    torch.set_num_threads(max(1, min(8, torch.get_num_threads())))
    main()
