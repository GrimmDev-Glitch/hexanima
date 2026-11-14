#!/usr/bin/env python3
"""Check the exact factorized LoKr forward against its dense Kronecker matrix.

This is a numerical design check for a future native MOSS path; it does not
convert the weights or make LoKr usable by the Android runner.

Usage: python verify_lokr_factorization.py INPUT.safetensors [TENSOR_PREFIX]
"""
import sys

import torch
from safetensors import safe_open


def main():
    if len(sys.argv) not in (2, 3):
        raise SystemExit(__doc__)
    path = sys.argv[1]
    prefix = sys.argv[2] if len(sys.argv) == 3 else ""
    with safe_open(path, framework="pt", device="cpu") as st:
        keys = sorted(k for k in st.keys() if k.endswith("lokr_w1") and k.startswith(prefix))
        if not keys:
            raise SystemExit(f"no lokr_w1 tensor matches prefix {prefix!r}")
        key = keys[0]
        root = key[:-len("lokr_w1")]
        other = root + "lokr_w2"
        if other not in st.keys():
            raise SystemExit(f"missing paired tensor {other}")
        w1 = st.get_tensor(key).float().contiguous()
        w2 = st.get_tensor(other).float().contiguous()

    if w1.ndim != 2 or w2.ndim != 2:
        raise SystemExit(f"expected matrix factors, got {tuple(w1.shape)} and {tuple(w2.shape)}")
    out1, in1 = w1.shape
    out2, in2 = w2.shape
    torch.manual_seed(7302)
    x = torch.randn(1, in1 * in2, dtype=torch.float32)

    # ΔW = W1 ⊗ W2; reshape input into its W1 input groups, apply W2 to
    # each group, then mix those results by W1. This is the form suited to
    # four grouped HMX GEMMs plus a small 4x4 combine when W1 is 4x4.
    xg = x.reshape(1, in1, in2)
    z = torch.matmul(xg, w2.T)
    factored = torch.einsum("tbo,ab->tao", z, w1)
    dense = x @ torch.kron(w1, w2).T
    diff = (factored.reshape_as(dense) - dense).abs()
    max_abs = float(diff.max())
    rel = float(torch.linalg.vector_norm(diff) / torch.linalg.vector_norm(dense).clamp_min(1e-30))
    print(f"tensor={root[:-1]}")
    print(f"w1={tuple(w1.shape)} w2={tuple(w2.shape)} dense={tuple(dense.shape)}")
    print(f"max_abs_error={max_abs:.8g} relative_error={rel:.8g}")
    if not torch.allclose(factored.reshape_as(dense), dense, rtol=2e-5, atol=2e-5):
        raise SystemExit("factorized and dense LoKr outputs differ")


if __name__ == "__main__":
    main()
