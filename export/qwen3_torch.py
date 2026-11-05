"""Qwen3-0.6B text encoder (the Anima LLM) in plain torch: ids [S] -> final-normed hidden [S,1024]. Reference for the NPU export."""
import os, json, struct, numpy as np, torch, torch.nn.functional as F
W = os.environ.get("MODELS", "models") + "/miaomiao3DHarem_animaLH3D10_txt.safetensors"
NL, H, NH, NKV, HD, I, EPS, THETA = 28, 1024, 16, 8, 128, 3072, 1e-6, 1e6

def load(path=W):
    f = open(path, "rb"); n = struct.unpack("<Q", f.read(8))[0]; hd = json.loads(f.read(n)); base = 8 + n
    out = {}
    for k, v in hd.items():
        if k == "__metadata__": continue
        a, b = v["data_offsets"]; f.seek(base + a); raw = np.frombuffer(f.read(b - a), np.uint8)
        if v["dtype"] == "BF16": t = torch.from_numpy((raw.view(np.uint16).astype(np.uint32) << 16).view(np.float32).copy())
        elif v["dtype"] == "F16": t = torch.from_numpy(raw.view(np.float16).astype(np.float32))
        else: t = torch.from_numpy(raw.view(np.float32).copy())
        out[k] = t.reshape(v["shape"])
    return out

def rms(x, w): return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + EPS) * w

def rope_tables(S):  # HF rotate_half convention: cos/sin [S,HD] with the half repeated
    inv = 1.0 / (THETA ** (torch.arange(0, HD, 2).float() / HD))
    fr = torch.outer(torch.arange(S).float(), inv)
    e = torch.cat([fr, fr], -1)
    return e.cos(), e.sin()

def rot(x, c, s):
    x1, x2 = x[..., :HD // 2], x[..., HD // 2:]
    return x * c + torch.cat([-x2, x1], -1) * s

def layer(P, i, x, c, s):
    p = f"model.layers.{i}."
    S = x.shape[0]
    h = rms(x, P[p + "input_layernorm.weight"])
    q = (h @ P[p + "self_attn.q_proj.weight"].T).view(S, NH, HD)
    k = (h @ P[p + "self_attn.k_proj.weight"].T).view(S, NKV, HD)
    v = (h @ P[p + "self_attn.v_proj.weight"].T).view(S, NKV, HD)
    q = rot(rms(q, P[p + "self_attn.q_norm.weight"]), c[:, None], s[:, None])
    k = rot(rms(k, P[p + "self_attn.k_norm.weight"]), c[:, None], s[:, None])
    k = k.repeat_interleave(NH // NKV, 1); v = v.repeat_interleave(NH // NKV, 1)
    a = F.scaled_dot_product_attention(q.transpose(0, 1), k.transpose(0, 1), v.transpose(0, 1), is_causal=True).transpose(0, 1).reshape(S, NH * HD)
    x = x + a @ P[p + "self_attn.o_proj.weight"].T
    h = rms(x, P[p + "post_attention_layernorm.weight"])
    return x + (F.silu(h @ P[p + "mlp.gate_proj.weight"].T) * (h @ P[p + "mlp.up_proj.weight"].T)) @ P[p + "mlp.down_proj.weight"].T

def encode(P, ids):
    x = P["model.embed_tokens.weight"][torch.as_tensor(ids)]
    c, s = rope_tables(len(ids))
    for i in range(NL): x = layer(P, i, x, c, s)
    return rms(x, P["model.norm.weight"])

if __name__ == "__main__":
    import sys
    torch.set_grad_enabled(False)
    P = load()
    ids = np.fromfile(sys.argv[1], np.int32)
    out = encode(P, ids).numpy()
    ref = np.fromfile(sys.argv[2], np.float32).reshape(len(ids), 1024)
    print("rel err", np.linalg.norm(out - ref) / np.linalg.norm(ref), "max abs", abs(out - ref).max(), "ref max", abs(ref).max())
