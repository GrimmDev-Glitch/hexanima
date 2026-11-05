"""Static Qwen3 text-encoder slices for QNN: x [1,SM,1024] -> layers [a,b) (+ final norm when b==28) -> [1,SM,1024].
SM fixed (right padding is harmless: causal attention). RMSNorm pre-scales by 1/64 so x^2 stays inside fp16.
usage: python export_te.py A B out.onnx [SM]    check: python export_te.py --check ref1/qids.bin ref1/qh_ref.bin"""
import sys, numpy as np, torch, torch.nn as nn, torch.nn.functional as F
from qwen3_torch import load, NL, H, NH, NKV, HD, I, EPS, THETA, rope_tables, rms as rms_ref

import os
TAP = int(os.environ.get("TAP", 0))
SC = 64.0
def rms(x, w):  # == x*rsqrt(mean(x^2)+eps)*w without squaring large activations in fp16 (the converter fuses this into RmsNorm(xs))
    xs = x / SC
    return xs * torch.rsqrt(xs.pow(2).mean(-1, keepdim=True) + EPS / (SC * SC)) * w

class Slice(nn.Module):
    def __init__(self, P, a, b, SM):
        super().__init__()
        self.a, self.b, self.SM = a, b, SM
        self.P = nn.ParameterDict()
        for i in range(a, b):
            for k in ("input_layernorm", "post_attention_layernorm", "self_attn.q_norm", "self_attn.k_norm"):
                self.P[f"{i}_{k}".replace(".", "_")] = nn.Parameter(P[f"model.layers.{i}.{k}.weight"], requires_grad=False)
            for k in ("self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.o_proj", "mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"):
                self.P[f"{i}_{k}".replace(".", "_")] = nn.Parameter(P[f"model.layers.{i}.{k}.weight"].T.contiguous(), requires_grad=False)
        if b == NL: self.P["norm"] = nn.Parameter(P["model.norm.weight"], requires_grad=False)
        c, s = rope_tables(SM)
        self.register_buffer("cos", c); self.register_buffer("sin", s)
        self.register_buffer("mask", torch.triu(torch.full((SM, SM), -3e4), 1))

    def rot(self, x):  # x [S,n,HD]
        c, s = self.cos[:, None], self.sin[:, None]
        x1, x2 = x[..., :HD // 2], x[..., HD // 2:]
        return x * c + torch.cat([-x2, x1], -1) * s

    def layer(self, i, x):
        g = lambda k: self.P[f"{i}_{k}".replace(".", "_")]
        S = self.SM
        h = rms(x, g("input_layernorm"))
        if TAP == 1: return h
        q = (h @ g("self_attn.q_proj")).view(S, NH, HD)
        k = (h @ g("self_attn.k_proj")).view(S, NKV, HD)
        v = (h @ g("self_attn.v_proj")).view(S, NKV, HD)
        q = self.rot(rms(q, g("self_attn.q_norm"))) * (HD ** -0.5)
        k = self.rot(rms(k, g("self_attn.k_norm")))
        k = k[:, :, None].expand(S, NKV, NH // NKV, HD).reshape(S, NH, HD)
        v = v[:, :, None].expand(S, NKV, NH // NKV, HD).reshape(S, NH, HD)
        if TAP == 2: return q.reshape(S, NH * HD)
        sc = q.transpose(0, 1) @ k.permute(1, 2, 0) + self.mask  # [NH,S,S]
        a = (torch.softmax(sc, -1) @ v.transpose(0, 1)).transpose(0, 1).reshape(S, NH * HD)
        if TAP == 3: return a
        x = x + a @ g("self_attn.o_proj")
        if TAP == 4: return x
        h = rms(x, g("post_attention_layernorm"))
        return x + (F.silu(h @ g("mlp.gate_proj")) * (h @ g("mlp.up_proj"))) @ g("mlp.down_proj")

    def forward(self, x):
        x = x[0]
        for i in range(self.a, self.b): x = self.layer(i, x)
        if self.b == NL: x = rms(x, self.P["norm"])
        return x[None]

if __name__ == "__main__":
    torch.set_grad_enabled(False)
    if sys.argv[1] == "--check":
        ids = np.fromfile(sys.argv[2], np.int32); ref = np.fromfile(sys.argv[3], np.float32).reshape(len(ids), 1024)
        P = load(); SM = 128
        x = P["model.embed_tokens.weight"][torch.as_tensor(np.pad(ids, (0, SM - len(ids)), constant_values=151643))][None]
        for a in range(0, NL, 7): x = Slice(P, a, a + 7, SM)(x)
        out = x[0, :len(ids)].numpy()
        print("static vs sd.cpp rel err", np.linalg.norm(out - ref) / np.linalg.norm(ref))
    else:
        a, b, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]; SM = int(sys.argv[4]) if len(sys.argv) > 4 else 128
        m = Slice(load(), a, b, SM).eval()
        torch.onnx.export(m, torch.zeros(1, SM, H), out, input_names=["x"], output_names=["y"], opset_version=13)
