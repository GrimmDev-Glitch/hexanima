"""Prepare inputs for, and compare, the real-weight Krea V73 block runner.

This is a single-block numerical harness. It does not implement the tokenizer,
text fusion, sampler, VAE, or a complete image-generation pipeline.

Prepare on the host:
  python krea/reference_block.py prepare turbo.safetensors work/krea-real --tokens 32
Run krea_blk_smoke on-device against that directory, then pull x32_out.bin back.
Compare:
  python krea/reference_block.py compare work/krea-real
"""
from __future__ import annotations

import argparse
import math
import struct
from pathlib import Path

import torch
import torch.nn.functional as F
from safetensors import safe_open


D = 6144
HEADS = 48
KV_HEADS = 12
HD = 128
TDIM = 256
FFN = 16384
AXES = (32, 48, 48)


def tensor(f, name: str) -> torch.Tensor:
    return f.get_tensor(name).to(dtype=torch.float32, device="cpu")


def write_f32(path: Path, value: torch.Tensor) -> None:
    path.write_bytes(value.detach().cpu().contiguous().numpy().astype("<f4", copy=False).tobytes())


def pack_x_for_dsp(x: torch.Tensor) -> torch.Tensor:
    """Slab-major [slab][feature tile][token][feature], matching M_X in blk.c."""
    return x.reshape(x.shape[0] // 32, 32, D // 32, 32).permute(0, 2, 1, 3).contiguous()


def rotary_tables(coords: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
    cos_parts, sin_parts = [], []
    for axis, dim in enumerate(AXES):
        freq = torch.arange(0, dim, 2, dtype=torch.float64) / dim
        omega = 1.0 / (1000.0 ** freq)
        angle = coords[:, axis].to(torch.float64)[:, None] * omega[None, :]
        cos_parts.append(torch.cos(angle).to(torch.float32))
        sin_parts.append(torch.sin(angle).to(torch.float32))
    return torch.cat(cos_parts, dim=-1), torch.cat(sin_parts, dim=-1)


def rotate(x: torch.Tensor, cos: torch.Tensor, sin: torch.Tensor) -> torch.Tensor:
    # Krea pairs adjacent dimensions, matching mmdit.ropeapply and mode 71.
    pair = x.reshape(x.shape[0], x.shape[1], x.shape[2] // 2, 2)
    even, odd = pair[..., 0], pair[..., 1]
    c, s = cos[None, :, :], sin[None, :, :]
    return torch.stack((even * c - odd * s, even * s + odd * c), dim=-1).reshape_as(x)


def timestep_projection(f, timestep: float) -> torch.Tensor:
    half = TDIM // 2
    freq = torch.exp(-math.log(1e4) * torch.arange(half, dtype=torch.float32) / half)
    args = torch.tensor([timestep], dtype=torch.float32)[:, None] * 1e3 * freq[None, :]
    temb = torch.cat((torch.cos(args), torch.sin(args)), dim=-1)
    t = F.linear(temb, tensor(f, "tmlp.0.weight"), tensor(f, "tmlp.0.bias"))
    t = F.gelu(t, approximate="tanh")
    t = F.linear(t, tensor(f, "tmlp.2.weight"), tensor(f, "tmlp.2.bias"))
    t = F.gelu(t, approximate="tanh")
    return F.linear(t, tensor(f, "tproj.1.weight"), tensor(f, "tproj.1.bias")).reshape(6 * D)


def rms(x: torch.Tensor, scale: torch.Tensor) -> torch.Tensor:
    return F.rms_norm(x, (x.shape[-1],), weight=scale + 1.0, eps=1e-5)


def q4a_reference_weight(weight: torch.Tensor) -> torch.Tensor:
    """Approximate the packed q4a weights after DSP dequantization to fp16."""
    out, inn = weight.shape
    groups = weight.float().reshape(out, inn // 32, 32)
    mins = groups.amin(dim=2, keepdim=True)
    maxs = groups.amax(dim=2, keepdim=True)
    scales = ((maxs - mins) / 15.0).clamp_min(torch.finfo(torch.float16).tiny).half().float()
    q = torch.round((groups - mins) / scales).clamp_(0, 15)
    dequant = q * scales + mins.half().float()
    return dequant.half().float().reshape(out, inn)


def q8_reference_weight(weight: torch.Tensor) -> torch.Tensor:
    """Approximate symmetric per-output/per-32-input-group q8 tile decode."""
    out, inn = weight.shape
    groups = weight.float().reshape(out, inn // 32, 32)
    scales = (groups.abs().amax(dim=2, keepdim=True) / 127.0).clamp_min(torch.finfo(torch.float16).tiny).half().float()
    q = torch.round(groups / scales).clamp_(-127, 127)
    return (q * scales).half().float().reshape(out, inn)


def reference_block(f, layer: int, x: torch.Tensor, mod: torch.Tensor,
                    cos: torch.Tensor, sin: torch.Tensor,
                    weight_format: str = "fp32",
                    key_valid: torch.Tensor | None = None) -> tuple[torch.Tensor, torch.Tensor]:
    p = f"blocks.{layer}."
    def linear(inp: torch.Tensor, name: str) -> torch.Tensor:
        weight = tensor(f, p + name)
        if weight_format == "q4a":
            weight = q4a_reference_weight(weight)
        elif weight_format == "q8":
            weight = q8_reference_weight(weight)
        return F.linear(inp, weight)

    m = mod + tensor(f, p + "mod.lin")
    prescale, preshift, pregate, postscale, postshift, postgate = m.chunk(6)

    h = (1.0 + prescale) * rms(x, tensor(f, p + "prenorm.scale")) + preshift
    q = linear(h, "attn.wq.weight").reshape(-1, HEADS, HD).permute(1, 0, 2)
    k = linear(h, "attn.wk.weight").reshape(-1, KV_HEADS, HD).permute(1, 0, 2)
    v = linear(h, "attn.wv.weight").reshape(-1, KV_HEADS, HD).permute(1, 0, 2)
    gate = torch.sigmoid(linear(h, "attn.gate.weight"))
    q = rotate(rms(q, tensor(f, p + "attn.qknorm.qnorm.scale")), cos, sin)
    k = rotate(rms(k, tensor(f, p + "attn.qknorm.knorm.scale")), cos, sin)
    k = k.repeat_interleave(HEADS // KV_HEADS, dim=0)
    v = v.repeat_interleave(HEADS // KV_HEADS, dim=0)
    scores = torch.matmul(q, k.transpose(-1, -2)) / math.sqrt(HD)
    if key_valid is not None:
        scores = scores.masked_fill(~key_valid.to(device=scores.device, dtype=torch.bool)[None, None, :], -torch.inf)
    probs = torch.softmax(scores, dim=-1)
    attended = torch.matmul(probs, v).permute(1, 0, 2).reshape(-1, D) * gate
    attn = linear(attended, "attn.wo.weight")
    x = x + pregate * attn
    after_attention = x.clone()

    h = (1.0 + postscale) * rms(x, tensor(f, p + "postnorm.scale")) + postshift
    g = linear(h, "mlp.gate.weight")
    u = linear(h, "mlp.up.weight")
    hidden = F.silu(g) * u
    mlp = linear(hidden, "mlp.down.weight")
    return after_attention, x + postgate * mlp


def prepare(args) -> None:
    if args.tokens < 32 or args.tokens % 32:
        raise SystemExit("--tokens must be a multiple of 32 and at least 32")
    out = args.outdir
    out.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(args.threads)
    with safe_open(str(args.checkpoint), framework="pt", device="cpu") as f:
        gen = torch.Generator(device="cpu").manual_seed(args.seed)
        x = torch.randn((args.tokens, D), generator=gen, dtype=torch.float32) * 0.02
        # A compact row-major crop from a 32x32 latent-patch grid.
        coords = torch.zeros((args.tokens, 3), dtype=torch.float32)
        coords[:, 1] = torch.arange(args.tokens) // 8
        coords[:, 2] = torch.arange(args.tokens) % 8
        cos, sin = rotary_tables(coords)
        mod = timestep_projection(f, args.timestep)
        attn_ref, ref = reference_block(f, args.layer, x, mod, cos, sin)
        attn_q4a_ref, q4a_ref = reference_block(f, args.layer, x, mod, cos, sin, weight_format="q4a")
        attn_q8_ref, q8_ref = reference_block(f, args.layer, x, mod, cos, sin, weight_format="q8")
        write_f32(out / "x32.bin", pack_x_for_dsp(x))
        write_f32(out / "cos.bin", cos)
        write_f32(out / "sin.bin", sin)
        write_f32(out / "mod.bin", mod)
        write_f32(out / "x32_attn_ref.bin", attn_ref)
        write_f32(out / "x32_ref.bin", ref)
        write_f32(out / "x32_attn_q4a_ref.bin", attn_q4a_ref)
        write_f32(out / "x32_q4a_ref.bin", q4a_ref)
        write_f32(out / "x32_attn_q8_ref.bin", attn_q8_ref)
        write_f32(out / "x32_q8_ref.bin", q8_ref)
    (out / "REFERENCE.txt").write_text(
        f"Krea official checkpoint, block={args.layer}, tokens={args.tokens}, timestep={args.timestep}, seed={args.seed}.\n"
        "The inputs are synthetic activations/positions; the reference uses real checkpoint weights.\n",
        encoding="utf-8",
    )
    print(f"prepared CPU reference: {out} ({args.tokens} tokens, block {args.layer})")


def compare(args) -> None:
    out = args.outdir
    suffix = "_ref" if args.weight_format == "fp32" else f"_{args.weight_format}_ref"
    ref_name = ("x32_attn" if args.stage == "attention" else "x32") + suffix + ".bin"
    ref = torch.from_file(str(out / ref_name), shared=False, size=args.tokens * D, dtype=torch.float32)
    got_tiled = torch.from_file(str(out / "x32_out.bin"), shared=False, size=args.tokens * D, dtype=torch.float32)
    got = got_tiled.reshape(args.tokens // 32, D // 32, 32, 32).permute(0, 2, 1, 3).reshape(-1)
    diff = got - ref
    refnorm = torch.linalg.vector_norm(ref).item()
    rel = torch.linalg.vector_norm(diff).item() / max(refnorm, 1e-12)
    cosine = F.cosine_similarity(got.reshape(1, -1), ref.reshape(1, -1)).item()
    print(f"finite={bool(torch.isfinite(got).all())}")
    print(f"max_abs={diff.abs().max().item():.7g} rmse={diff.square().mean().sqrt().item():.7g}")
    print(f"relative_l2={rel:.7g} cosine={cosine:.9f}")


def compare_cpu_formats(args) -> None:
    out = args.outdir
    n = args.tokens * D
    for stage, ref_name, prefix in (
        ("attention", "x32_attn_ref.bin", "x32_attn"),
        ("final", "x32_ref.bin", "x32"),
    ):
        ref = torch.from_file(str(out / ref_name), shared=False, size=n, dtype=torch.float32)
        for fmt in ("q4a", "q8"):
            candidate = torch.from_file(str(out / f"{prefix}_{fmt}_ref.bin"), shared=False, size=n, dtype=torch.float32)
            diff = candidate - ref
            rel = torch.linalg.vector_norm(diff).item() / max(torch.linalg.vector_norm(ref).item(), 1e-12)
            cosine = F.cosine_similarity(candidate.reshape(1, -1), ref.reshape(1, -1)).item()
            print(f"{stage} {fmt}: relative_l2={rel:.7g} cosine={cosine:.9f}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("checkpoint", type=Path)
    p.add_argument("outdir", type=Path)
    p.add_argument("--layer", type=int, default=0)
    p.add_argument("--tokens", type=int, default=32)
    p.add_argument("--seed", type=int, default=20261121)
    p.add_argument("--timestep", type=float, default=0.5)
    p.add_argument("--threads", type=int, default=4)
    p.set_defaults(func=prepare)
    c = sub.add_parser("compare")
    c.add_argument("outdir", type=Path)
    c.add_argument("--tokens", type=int, default=32)
    c.add_argument("--stage", choices=("attention", "final"), default="final")
    c.add_argument("--weight-format", choices=("fp32", "q4a", "q8"), default="fp32", help="CPU reference weight precision to compare against")
    c.set_defaults(func=compare)
    q = sub.add_parser("compare-cpu-formats", help="compare the CPU q4a/q8 references against the FP32 reference")
    q.add_argument("outdir", type=Path)
    q.add_argument("--tokens", type=int, default=32)
    q.set_defaults(func=compare_cpu_formats)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
