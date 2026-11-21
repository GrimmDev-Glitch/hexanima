"""Run Krea 2's official text-fusion/projector/MLP on captured Qwen states.

This is a CPU reference for validating the small text path; it is not the
phone DSP implementation. Inputs/outputs are private work artifacts.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import torch
import torch.nn.functional as F
from safetensors import safe_open

WIDTH = 2560
LAYERS = (2, 5, 8, 11, 14, 17, 20, 23, 26, 29, 32, 35)
HEADS = 20
HEAD_DIM = WIDTH // HEADS
MAX_TOKENS = 512
PREFIX = 34
SUFFIX = 5


def load_tensor(f, name: str) -> torch.Tensor:
    return f.get_tensor(name).to(dtype=torch.float32, device="cpu")


def rms(x: torch.Tensor, scale: torch.Tensor) -> torch.Tensor:
    return F.rms_norm(x.float(), (x.shape[-1],), weight=scale.float() + 1.0, eps=1e-5)


def fusion_block(x: torch.Tensor, f, prefix: str, mask: torch.Tensor | None) -> torch.Tensor:
    a = prefix + ".attn."
    z = rms(x, load_tensor(f, prefix + ".prenorm.scale"))
    q = F.linear(z, load_tensor(f, a + "wq.weight")).view(*z.shape[:-1], HEADS, HEAD_DIM).transpose(-3, -2)
    k = F.linear(z, load_tensor(f, a + "wk.weight")).view(*z.shape[:-1], HEADS, HEAD_DIM).transpose(-3, -2)
    v = F.linear(z, load_tensor(f, a + "wv.weight")).view(*z.shape[:-1], HEADS, HEAD_DIM).transpose(-3, -2)
    q = rms(q, load_tensor(f, a + "qknorm.qnorm.scale"))
    k = rms(k, load_tensor(f, a + "qknorm.knorm.scale"))
    attn = F.scaled_dot_product_attention(q, k, v, attn_mask=mask)
    attn = attn.transpose(-3, -2).contiguous().view_as(z)
    gate = torch.sigmoid(F.linear(z, load_tensor(f, a + "gate.weight")))
    x = x + F.linear(attn * gate, load_tensor(f, a + "wo.weight"))

    z = rms(x, load_tensor(f, prefix + ".postnorm.scale"))
    m = prefix + ".mlp."
    g = F.linear(z, load_tensor(f, m + "gate.weight"))
    u = F.linear(z, load_tensor(f, m + "up.weight"))
    x = x + F.linear(F.silu(g) * u, load_tensor(f, m + "down.weight"))
    return x


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("weights", type=Path)
    ap.add_argument("hidden_dir", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--sequence-tokens", type=int, default=546)
    ap.add_argument("--prompt-tokens", type=int, default=17)
    args = ap.parse_args()
    torch.set_num_threads(6)

    # Qwen emits [sequence, width] for each selected layer. Drop Krea's
    # 34-token system prefix, retain prompt states, and place the five suffix
    # states at the end of the official 512-token context.
    active_tokens = args.prompt_tokens + SUFFIX
    x = torch.zeros((1, active_tokens, len(LAYERS), WIDTH), dtype=torch.float32)
    for j, layer in enumerate(LAYERS):
        h = torch.from_file(str(args.hidden_dir / f"hidden_{layer:02d}.f32"), shared=False,
                            size=args.sequence_tokens * WIDTH, dtype=torch.float32).view(args.sequence_tokens, WIDTH)
        if not torch.isfinite(h).all():
            raise ValueError(f"hidden layer {layer} contains non-finite values")
        x[0, :args.prompt_tokens, j] = h[PREFIX:PREFIX + args.prompt_tokens]
        x[0, args.prompt_tokens:, j] = h[args.sequence_tokens - SUFFIX:args.sequence_tokens]

    valid = torch.ones((1, active_tokens), dtype=torch.bool)
    # The first two fusion blocks operate across the twelve hidden layers for
    # each token independently. Their attention has length 12, no token mask.
    b, seq, n_layers, dim = x.shape
    y = x.permute(0, 1, 2, 3).reshape(b * seq, n_layers, dim)
    with safe_open(str(args.weights), framework="pt", device="cpu") as f:
        for i in range(2):
            y = fusion_block(y, f, f"txtfusion.layerwise_blocks.{i}", None)
        y = y.view(b, seq, n_layers, dim)
        y = torch.einsum("bsld,ol->bsd", y, load_tensor(f, "txtfusion.projector.weight"))

        attn_mask = valid[:, None, None, :] & valid[:, None, :, None]
        for i in range(2):
            y = fusion_block(y, f, f"txtfusion.refiner_blocks.{i}", attn_mask)

        z = rms(y, load_tensor(f, "txtmlp.0.scale"))
        z = F.linear(z, load_tensor(f, "txtmlp.1.weight"), load_tensor(f, "txtmlp.1.bias"))
        z = F.gelu(z, approximate="tanh")
        z = F.linear(z, load_tensor(f, "txtmlp.3.weight"), load_tensor(f, "txtmlp.3.bias"))

    if not torch.isfinite(z).all():
        raise ValueError("fused context contains non-finite values")
    context = torch.zeros((1, MAX_TOKENS, z.shape[-1]), dtype=z.dtype)
    context[:, :args.prompt_tokens] = z[:, :args.prompt_tokens]
    context[:, MAX_TOKENS - SUFFIX:] = z[:, args.prompt_tokens:]
    full_valid = torch.zeros((1, MAX_TOKENS), dtype=torch.bool)
    full_valid[:, :args.prompt_tokens] = True
    full_valid[:, MAX_TOKENS - SUFFIX:] = True
    args.out.parent.mkdir(parents=True, exist_ok=True)
    context.contiguous().numpy().astype("<f4").tofile(args.out)
    full_valid.numpy().astype("uint8").tofile(args.out.with_suffix(".mask"))
    active = z[valid]
    print(f"context={tuple(context.shape)} valid_tokens={int(full_valid.sum())} finite=True "
          f"active_rms={active.square().mean().sqrt().item():.6g} "
          f"active_range=[{active.min().item():.6g},{active.max().item():.6g}]")


if __name__ == "__main__":
    main()
