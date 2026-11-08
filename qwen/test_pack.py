"""Round-trip check of blkNN.bin: block computed from unpacked q8 tiles (+LoRA tiles) vs qwen_ref.block on dequantized weights+LoRA. python test_pack.py PACKDIR BLOCK"""
import sys, numpy as np, torch, torch.nn.functional as F
import qref, qwen_ref as R
d, bi = sys.argv[1], int(sys.argv[2])
wb = open(f"{d}/blk{bi:02d}.bin", "rb").read()
from packio import read_pack
Wm, A, qg, kg = read_pack(wb)
torch.manual_seed(1)
T, hh, ww = 12, 4, 4; N = hh * ww; S = T + N
x = torch.randn(S, 4096) * 3; mod = torch.randn(S, 16384) * 0.3
m = qref.load(bi + 1, lora=False)
fr = m.pos_embed([(1, hh, ww)], torch.cat((torch.zeros(T, dtype=torch.bool), torch.ones(N, dtype=torch.bool))), device="cpu"); cos, sin = R.rope_halfsplit(fr)
with torch.no_grad():
    ref = R.block(m, bi, x, mod, cos, sin, T)
    mm = lambda xin, Wk, L: torch.cat((xin, L), 1) @ Wk
    sc1, g1, sc2, g2 = mod.chunk(4, -1)
    h = R.ln(x) * (1 + sc1); L = h @ A["qkv"]
    q = mm(h, Wm["q"], L[:, :128]).view(S, 32, 128); k = mm(h, Wm["k"], L[:, 128:256]).view(S, 32, 128); v = mm(h, Wm["v"], L[:, 256:]).view(S, 32, 128)
    q = R.apply_rope(R.rms(q, qg), cos, sin); k = R.apply_rope(R.rms(k, kg), cos, sin)
    mask = torch.ones(S, S, dtype=torch.bool); mask[:T, :T] = torch.tril(torch.ones(T, T, dtype=torch.bool)); mask[:T, T:] = False
    a = F.scaled_dot_product_attention(q.transpose(0, 1)[None], k.transpose(0, 1)[None], v.transpose(0, 1)[None], attn_mask=mask)[0].transpose(0, 1).reshape(S, -1)
    xa = x + mm(a, Wm["o"], a @ A["o"]) * torch.tanh(g1)
    h2 = R.ln(xa) * (1 + sc2); L2 = h2 @ A["gp"]
    w = torch.cat((h2, L2), 1) @ Wm["gp"]; w = w.view(S, 768 // 2, 2, 32); gate = w[:, :, 0].reshape(S, 12288); pr = w[:, :, 1].reshape(S, 12288)
    mid = F.silu(gate) * pr
    out = xa + mm(mid, Wm["down"], mid @ A["dn"]) * torch.tanh(g2)
e = (out - ref); print("rel rms err %.4f%%  max %.3f (ref rms %.2f)" % (100 * float(e.pow(2).mean().sqrt() / ref.pow(2).mean().sqrt()), float(e.abs().max()), float(ref.pow(2).mean().sqrt())))
