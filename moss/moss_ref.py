"""Reference MOSS-Image DiT in torch fp32 (CPU), weights from the unsloth GGUF (dequantized) + optional unmerged LoRA.
Mirrors OpenMOSS pipeline_moss_image.py. Used to validate NPU kernels block by block."""
import math, numpy as np, torch, gguf
from torch.nn import functional as F

DIM, FFN, HEADS, NL = 3072, 8192, 24, 42

class W:
    """lazy GGUF weight store: name -> fp32 tensor [out, in]"""
    def __init__(self, path):
        self.r = gguf.GGUFReader(path)
        self.t = {t.name: t for t in self.r.tensors}
    def __call__(self, name):
        t = self.t[name]
        a = gguf.quants.dequantize(t.data, t.tensor_type) if t.tensor_type not in (gguf.GGMLQuantizationType.F32, gguf.GGMLQuantizationType.F16, gguf.GGMLQuantizationType.BF16) else t.data
        a = np.asarray(a)
        if t.tensor_type == gguf.GGMLQuantizationType.BF16:
            a = (np.ascontiguousarray(a).view(np.uint16).astype(np.uint32) << 16).view(np.float32)
        return torch.from_numpy(np.array(a, dtype=np.float32)).reshape(*[int(x) for x in t.shape][::-1])

def rms(x, w, eps=1e-6):
    return x * torch.rsqrt(x.square().mean(-1, keepdim=True) + eps) * w

def situ(h, g):
    return (4.0 * torch.tanh(g / 4.0) * torch.sigmoid(g)) * (25.0 * torch.tanh(h / 25.0))

def rope_tables(H, Wd, T, bands):
    h, w = torch.meshgrid(torch.arange(H), torch.arange(Wd), indexing='ij')
    img = torch.stack((torch.zeros_like(h), torch.full_like(h, T), h, w), -1).flatten(0, 1).float()
    img[:, 2] -= (H - 1) / 2; img[:, 3] -= (Wd - 1) / 2
    txt = torch.zeros(T, 4); txt[:, 1] = torch.arange(T)
    phase = torch.cat((img, txt), 0).unsqueeze(-1) * bands            # [N,4,16]
    r = torch.cat((phase.sin(), phase.cos()), -2).flatten(-2)          # [N,128]
    sin, cos = r.chunk(2, -1)
    return torch.cat((sin, sin), -1), torch.cat((cos, cos), -1)        # [N,128] each

def rotate(x, sin, cos):                                               # x [N,heads,128]
    a, b = x.chunk(2, -1)
    return x * cos[:, None] + torch.cat((-b, a), -1) * sin[:, None]

def attn(q, k, v, sin, cos, qn, kn, heads=HEADS):
    N = q.shape[0]
    q = rms(q.view(N, heads, -1), qn); k = rms(k.view(N, heads, -1), kn)
    q = rotate(q, sin, cos); k = rotate(k, sin, cos); v = v.view(N, heads, -1)
    o = F.scaled_dot_product_attention(q.transpose(0, 1)[None], k.transpose(0, 1)[None], v.transpose(0, 1)[None])
    return o[0].transpose(0, 1).reshape(N, -1)

def lin(x, w, b=None, lora=None):
    y = x @ w.T
    if b is not None: y = y + b
    if lora is not None:
        A, B, s = lora
        y = y + s * ((x @ A.T) @ B.T)
    return y

def block(x, mod, sin, cos, g, i, lora=None):
    """x [N,DIM]; mod [6,DIM] = shift_a, scale_a, gate_a, shift_f, scale_f, gate_f"""
    p = f"layers.{i}."
    sa, ca, ga, sf, cf, gf = mod
    L = lambda n: (lora(i, n) if lora else None)
    y = rms(x, g(p + "norm1.weight")) * (1 + ca) + sa
    q = lin(y, g(p + "proj.q.weight"), lora=L("q")); k = lin(y, g(p + "proj.k.weight"), lora=L("k")); v = lin(y, g(p + "proj.v.weight"), lora=L("v"))
    a = attn(q, k, v, sin, cos, g(p + "q_norm.weight"), g(p + "k_norm.weight"))
    x = x + lin(a, g(p + "proj.o.weight"), lora=L("o")) * ga
    y = rms(x, g(p + "norm2.weight")) * (1 + cf) + sf
    h, gt = lin(y, g(p + "proj.ffn_up_gate.weight"), lora=L("ffn_up_gate")).chunk(2, -1)
    return x + lin(situ(h, gt), g(p + "proj.ffn_down.weight"), lora=L("ffn_down")) * gf


# ---------------- full model (DiT) ----------------
from safetensors import safe_open

class Lora:
    def __init__(self, path):
        self.f = safe_open(path, "pt"); self.c = {}
    def __call__(self, i, n):
        k = f"diffusion_model.layers.{i}.proj.{n}."
        A = self.f.get_tensor(k + "lora_down.weight"); B = self.f.get_tensor(k + "lora_up.weight")
        return A, B, float(self.f.get_tensor(k + "alpha")) / A.shape[0]

def text_block(x, p, sin, cos, g):
    h = rms(x, g(p + "norm1.weight"))
    qkv = lin(h, g(p + "qkv.weight"), g(p + "qkv.bias"))
    q, k, v = qkv.chunk(3, -1)
    a = attn(q, k, v, sin, cos, g(p + "q_norm.weight"), g(p + "k_norm.weight"))
    x = x + lin(a, g(p + "proj.weight"), g(p + "proj.bias"))
    y = rms(x, g(p + "norm2.weight"))
    up = lin(y, g(p + "ffn_up.weight"), g(p + "ffn_up.bias")); gate = lin(y, g(p + "ffn_gate.weight"), g(p + "ffn_gate.bias"))
    return x + lin(situ(up, gate), g(p + "ffn_down.weight"), g(p + "ffn_down.bias"))

def text_refine(txt, sin_t, cos_t, g):
    x = lin(txt, g("text_embedder.proj.weight"), g("text_embedder.proj.bias"))
    for i in range(2): x = text_block(x, f"text_embedder.blocks.{i}.", sin_t, cos_t, g)
    return x

def time_vecs(t, g, tp=None):
    half = 128
    freq = 10000 ** (-torch.arange(half, dtype=torch.float64) / half)
    ph = float(t) * freq
    e = torch.cat((ph.cos(), ph.sin())).float()
    G = (lambda n: tp.get_tensor(n)) if tp is not None else g
    tm = F.silu(e @ G("time_embedding.0.weight").T + G("time_embedding.0.bias")) @ G("time_embedding.2.weight").T + G("time_embedding.2.bias")
    mod = tm @ g("shared_block_modulation.weight").T + G("shared_block_modulation.bias")
    head = tm @ g("video_head_modulation.weight").T + G("video_head_modulation.bias")
    return mod.view(6, -1), head.view(2, -1)

def dit_forward(lat, t, txt, g, lora=None, tp=None, nblocks=NL, log=None):
    """lat [128,H,W] fp32; txt [T,4096]; returns velocity [128,H,W]"""
    C, H, Wd = lat.shape; T = txt.shape[0]
    sin, cos = rope_tables(H, Wd, T, g("rope.bands"))
    x = lin(lat.flatten(1).T, g("video_embedder.weight"), g("video_embedder.bias"))
    tx = text_refine(txt, sin[H * Wd:], cos[H * Wd:], g)
    x = torch.cat((x, tx), 0)
    mod, head = time_vecs(t, g, tp)
    for i in range(nblocks):
        x = block(x, mod, sin, cos, g, i, lora)
        if log: log(i, x)
    x = rms(x[:H * Wd], g("final_norm_video.weight"))
    x = x * (1 + head[1]) + head[0]
    x = lin(x, g("final_linear_video.weight"))
    return x.T.reshape(C, H, Wd)
