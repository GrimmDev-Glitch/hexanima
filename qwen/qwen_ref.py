"""Kernel-form reference of the Qwen-Image-2.1 DiT (rows = [text | target image], per-row modulation set, half-split RoPE by weight permutation).
Validated against diffusers QwenImage21Transformer2DModel (2 layers, real GGUF weights + Lightning LoRA): python qwen_ref.py"""
import math, torch, torch.nn.functional as F
import qref
PERM = torch.cat((torch.arange(0, 128, 2), torch.arange(1, 128, 2)))     # adjacent pairs -> half-split
H, HD = 32, 128

def lora_w(m, k):  # (weight, dW or None) for state-dict key
    return m.state_dict()[k].float(), qref.lora_delta(k)

def lin(x, w, dw=None):    # x@W^T + (x@A... dense form: x@(W+dW)^T would hide the LoRA path, so keep it explicit)
    y = x @ w.T
    return y if dw is None else y + x @ dw.T

def ln(x): return F.layer_norm(x, (x.shape[-1],), eps=1e-6)
def rms(x, g, eps=1e-6): return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + eps) * g

def rope_halfsplit(freqs):   # complex [S,64] (diffusers rotary_emb) -> cos,sin [S,64]
    return freqs.real.float(), freqs.imag.float()

def apply_rope(x, cos, sin):   # x [S,H,128] already permuted to half-split
    a, b = x[..., :64], x[..., 64:]
    c, s = cos[:, None], sin[:, None]
    return torch.cat((a * c - b * s, a * s + b * c), -1)

def block(m, i, x, mod_rows, cos, sin, T, lora=True):
    """x [S,4096] rows = text(T) + image(N); mod_rows [S,4] indices not needed: mod = (S,16384) per-row."""
    p = f"transformer_blocks.{i}."
    g = lambda n: lora_w(m, p + n)
    if not lora: g = lambda n: (m.state_dict()[p + n].float(), None)
    sc1, g1, sc2, g2 = mod_rows.chunk(4, -1)
    h = ln(x) * (1 + sc1)
    q = lin(h, *g("attn.to_q.weight")); k = lin(h, *g("attn.to_k.weight")); v = lin(h, *g("attn.to_v.weight"))
    S = x.shape[0]
    q = q.view(S, H, HD); k = k.view(S, H, HD); v = v.view(S, H, HD)
    qn = m.state_dict()[p + "attn.norm_q.weight"].float(); kn = m.state_dict()[p + "attn.norm_k.weight"].float()
    q = apply_rope(rms(q, qn)[..., PERM], cos, sin)
    k = apply_rope(rms(k, kn)[..., PERM], cos, sin)
    mask = torch.ones(S, S, dtype=torch.bool); mask[:T, :T] = torch.tril(torch.ones(T, T, dtype=torch.bool))
    mask[:T, T:] = False      # text never sees image; image rows see everything
    a = F.scaled_dot_product_attention(q.transpose(0, 1)[None], k.transpose(0, 1)[None], v.transpose(0, 1)[None], attn_mask=mask)[0].transpose(0, 1).reshape(S, -1)
    x = x + lin(a, *g("attn.to_out.0.weight")) * torch.tanh(g1)
    h = ln(x) * (1 + sc2)
    mid = F.silu(lin(h, *g("img_mlp.gate_layer.weight"))) * lin(h, *g("img_mlp.proj.weight"))
    return x + lin(mid, *g("img_mlp.out.weight")) * torch.tanh(g2)

if __name__ == "__main__":
    import numpy as np
    torch.manual_seed(0)
    NL = 2
    m = qref.load(NL, lora=False)                       # base weights; my block adds the LoRA explicitly
    mL = qref.load(NL, lora=True)                       # diffusers model with merged LoRA = ground truth
    T, hh, ww = 12, 4, 4; N = hh * ww
    txt = torch.randn(1, T, 4096) * 2; lat = torch.randn(1, N, 64); t = torch.tensor([0.7])
    img_mask = torch.cat((torch.zeros(T, dtype=torch.bool), torch.ones(N // 4, dtype=torch.bool)))[None]
    with torch.no_grad():
        ref = mL(hidden_states=lat, encoder_hidden_states=txt, timestep=t, img_shapes=[[(1, hh, ww)]], img_mask=img_mask, return_dict=False)[0][0]
        # ---- kernel-form pipeline ----
        sd = lambda k: m.state_dict()[k].float()
        def L(x, k): return lin(x, sd(k + ".weight"), qref.lora_delta(k + ".weight"))
        te = txt[0]
        te = (te * torch.rsqrt(te.pow(2).mean(-1, keepdim=True) + 1e-6)) * (sd("txt_in.text_norm.weight") + 1)
        te = L(F.gelu(L(te, "txt_in.in_layer"), approximate="tanh"), "txt_in.out_layer")
        ie = L(lat[0], "img_in")
        x = torch.cat((te, ie), 0)
        def temb(tv):
            fr = torch.exp(-math.log(10000) * torch.arange(128).float() / 128); a = 1000 * tv * fr
            e = torch.cat((torch.cos(a), torch.sin(a)))
            return L(F.silu(L(e, "time_text_embedder.timestep_embedder.linear_1".replace("time_text_embedder", "time_text_embed"))), "time_text_embed.timestep_embedder.linear_2"), 
        t1 = temb(t[0])[0]; t0 = temb(torch.tensor(0.0))[0]
        mod1 = L(F.silu(t1), "modulation.1"); mod0 = L(F.silu(t0), "modulation.1")
        mod_rows = torch.cat((mod0[None].expand(T, -1), mod1[None].expand(N, -1)), 0)
        # rope from diffusers
        img_ids_mask = torch.cat((torch.zeros(T, dtype=torch.bool), torch.ones(N, dtype=torch.bool)))
        fr = m.pos_embed([(1, hh, ww)], img_ids_mask, device="cpu")
        cos, sin = rope_halfsplit(fr)
        # permute q/k rows is implicit: we permute the head dims AFTER projection (equivalent), see block()
        for i in range(NL): x = block(m, i, x, mod_rows, cos, sin, T)
        sc = L(F.silu(t1), "norm_out.linear")
        out = ln(x[T:]) * (1 + sc)
        out = L(out, "proj_out")
    ref = ref[T:]; d = (out - ref).abs(); print("max abs diff", float(d.max()), "ref rms", float(ref.pow(2).mean().sqrt()), "rel", float(d.pow(2).mean().sqrt() / ref.pow(2).mean().sqrt()))
