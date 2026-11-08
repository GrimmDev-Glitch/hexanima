"""Qwen3.5-9B text encoder reference (torch fp32, GGUF weights dequantised). last_hidden_state after output_norm, like sd.cpp MOSS conditioning.
python qwen35_ref.py MODEL.gguf "prompt" [out.f32]"""
import sys, math, numpy as np, torch, gguf
from torch.nn import functional as F
class Q:
    def __init__(self, path):
        self.r = gguf.GGUFReader(path); self.t = {t.name: t for t in self.r.tensors}
    def __call__(self, n):
        t = self.t[n]; a = t.data
        if t.tensor_type not in (gguf.GGMLQuantizationType.F32, gguf.GGMLQuantizationType.F16, gguf.GGMLQuantizationType.BF16): a = gguf.quants.dequantize(a, t.tensor_type)
        a = np.asarray(a, dtype=np.float32)
        return torch.from_numpy(a.copy()).reshape(*[int(x) for x in t.shape][::-1])
    def rows(self, n, ids):   # embedding rows only
        t = self.t[n]; a = gguf.quants.dequantize(t.data.reshape(int(t.shape[1]), -1)[ids], t.tensor_type); return torch.from_numpy(np.asarray(a, dtype=np.float32).copy())
def rms(x, w, eps=1e-6): return x * torch.rsqrt(x.square().mean(-1, keepdim=True) + eps) * w
import os
DBG = {}
def encode(q, ids, kmap=os.environ.get("KMAP","cyc"), qscale=True, log=None):
    T = len(ids); x = q.rows("token_embd.weight", torch.tensor(ids))
    for i in range(32):
        p = f"blk.{i}."; h = rms(x, q(p + "attn_norm.weight"))
        if p + "attn_qkv.weight" in q.t:                               # gated delta net
            qkv = h @ q(p + "attn_qkv.weight").T; z = h @ q(p + "attn_gate.weight").T
            a = h @ q(p + "ssm_alpha.weight").T; b = h @ q(p + "ssm_beta.weight").T
            cw = q(p + "ssm_conv1d.weight")                              # [8192, 4]  (c, kernel)
            pad = torch.cat((torch.zeros(3, qkv.shape[1]), qkv), 0)
            mixed = F.silu(sum(pad[j:j + T] * cw[:, j] for j in range(4)))
            qq, kk, vv = mixed[:, :2048].view(T, 16, 128), mixed[:, 2048:4096].view(T, 16, 128), mixed[:, 4096:].view(T, 32, 128)
            qq = rms(qq, 1.0, 1e-6 / 128); kk = rms(kk, 1.0, 1e-6 / 128)
            qq = qq / math.sqrt(128); kk = kk / math.sqrt(128)      # l2norm
            if qscale: qq = qq / math.sqrt(128)                      # the GDN op scales q by 1/sqrt(dk)
            beta = torch.sigmoid(b); g = F.softplus(a + q(p + "ssm_dt.bias")) * q(p + "ssm_a")      # [T,32]
            S = torch.zeros(32, 128, 128); outs = []
            for t in range(T):
                o = torch.zeros(32, 128)
                for hh in range(32):
                    kh = hh % 16 if kmap == "cyc" else hh // 2
                    S[hh] *= torch.exp(g[t, hh]); kv = S[hh].T @ kk[t, kh]
                    d = (vv[t, hh] - kv) * beta[t, hh]; S[hh] += torch.outer(kk[t, kh], d); o[hh] = S[hh].T @ qq[t, kh]
                outs.append(o)
            o = torch.stack(outs)                                        # [T,32,128]
            if i == int(os.environ.get('DBG_LAYER', -1)): DBG.update(h=h.clone(), qkv=qkv.clone(), z=z.clone(), ab=torch.cat((a, b), 1), mixed=mixed.clone(), core=o.clone())
            o = rms(o, q(p + "ssm_norm.weight")); o = F.silu(z.view(T, 32, 128)) * o
            if i == int(os.environ.get('DBG_LAYER', -1)): DBG['o'] = o.reshape(T, 4096).clone()
            att = o.reshape(T, 4096) @ q(p + "ssm_out.weight").T
        else:
            qg = (h @ q(p + "attn_q.weight").T).view(T, 16, 512); qq, gate = qg[..., :256], qg[..., 256:].reshape(T, 4096)
            kk = (h @ q(p + "attn_k.weight").T).view(T, 4, 256); vv = (h @ q(p + "attn_v.weight").T).view(T, 4, 256)
            qq = rms(qq, q(p + "attn_q_norm.weight")); kk = rms(kk, q(p + "attn_k_norm.weight"))
            pos = torch.arange(T, dtype=torch.float32)[:, None]; fr = 1e7 ** (-torch.arange(32, dtype=torch.float32) / 32); ang = pos * fr   # [T,32]
            def rope(t):
                a1, a2 = t[..., :32], t[..., 32:64]; c, s = ang.cos()[:, None], ang.sin()[:, None]
                return torch.cat((a1 * c - a2 * s, a2 * c + a1 * s, t[..., 64:]), -1)
            qq, kk = rope(qq), rope(kk)
            kk = kk.repeat_interleave(4, 1); vv = vv.repeat_interleave(4, 1)
            o = F.scaled_dot_product_attention(qq.transpose(0, 1)[None], kk.transpose(0, 1)[None], vv.transpose(0, 1)[None], is_causal=True)[0].transpose(0, 1).reshape(T, 4096)
            att = (o * torch.sigmoid(gate)) @ q(p + "attn_output.weight").T
        x = x + att
        h = rms(x, q(p + "post_attention_norm.weight"))
        x = x + (F.silu(h @ q(p + "ffn_gate.weight").T) * (h @ q(p + "ffn_up.weight").T)) @ q(p + "ffn_down.weight").T
        if log: log(i, x)
        print("layer", i, float(x.abs().max()), flush=True)
    return rms(x, q("output_norm.weight"))
if __name__ == "__main__":
    from tokenizers import Tokenizer
    tok = Tokenizer.from_file("models/tokenizer.json")
    text = "<|im_start|>user\n" + sys.argv[2] + "<|im_end|>\n<|im_start|>assistant\n<think>\n"
    ids = tok.encode(text, add_special_tokens=False).ids; print(len(ids), "tokens")
    q = Q(sys.argv[1]); h = encode(q, ids)
    if len(sys.argv) > 3: h.numpy().astype(np.float32).tofile(sys.argv[3])
