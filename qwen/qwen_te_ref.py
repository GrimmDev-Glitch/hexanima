"""CPU reference of the Qwen3-VL-8B text encoder (text only) from the UD-Q4_K_XL GGUF, layer-streamed. Output = last decoder layer residual BEFORE the final norm, system tokens dropped.
usage: python qwen_te_ref.py "prompt" OUT.npy      -> float32 [T, 4096]"""
import sys, numpy as np, torch, gguf, torch.nn.functional as F
from gguf.quants import dequantize
from tokenizers import Tokenizer
G = "models/Qwen3-VL-8B-Instruct-UD-Q4_K_XL.gguf"; TOK = "ref/Qwen-Image-2.1-Turbo/processor/tokenizer.json"
SYS = "Comprehend and analyze the provided prompt."
def template(p): return f"<|im_start|>system\n{SYS}<|im_end|>\n<|im_start|>user\n{p if p else ' '}<|im_end|>\n<|im_start|>assistant\n"
def encode(p):
    tk = Tokenizer.from_file(TOK); ids = tk.encode(template(p)).ids
    drop = len(tk.encode(f"<|im_start|>system\n{SYS}<|im_end|>\n").ids); return ids, drop
r = gguf.GGUFReader(G); T = {t.name: t for t in r.tensors}
def deq(name, rows=None):
    t = T[name]; shape = [int(x) for x in t.shape[::-1]]
    if t.tensor_type == gguf.GGMLQuantizationType.F32: return torch.from_numpy(np.array(t.data, dtype=np.float32).reshape(shape))
    d = np.asarray(t.data)
    if rows is not None:   # gather rows of a [vocab, hidden] quantized matrix: row-wise blocks
        bpr = d.shape[-1] if d.ndim == 2 else None
        d = d.reshape(shape[0], -1)[rows]; return torch.from_numpy(np.ascontiguousarray(dequantize(d, t.tensor_type))).reshape(len(rows), shape[1])
    return torch.from_numpy(np.ascontiguousarray(dequantize(d, t.tensor_type))).reshape(shape)
def rms(x, w, eps=1e-6): return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + eps) * w
def rot_half(x): a, b = x[..., :64], x[..., 64:]; return torch.cat((-b, a), -1)
def run(prompt):
    ids, drop = encode(prompt); n = len(ids)
    x = deq("token_embd.weight", rows=ids)
    inv = 5e6 ** (-torch.arange(0, 128, 2).float() / 128); ang = torch.arange(n)[:, None] * inv[None]; cos = torch.cat((ang.cos(),) * 2, -1); sin = torch.cat((ang.sin(),) * 2, -1)
    for i in range(36):
        p = f"blk.{i}."; h = rms(x, deq(p + "attn_norm.weight"))
        q = (h @ deq(p + "attn_q.weight").T).view(n, 32, 128); k = (h @ deq(p + "attn_k.weight").T).view(n, 8, 128); v = (h @ deq(p + "attn_v.weight").T).view(n, 8, 128)
        q = rms(q, deq(p + "attn_q_norm.weight")); k = rms(k, deq(p + "attn_k_norm.weight"))
        q = q * cos[:, None] + rot_half(q) * sin[:, None]; k = k * cos[:, None] + rot_half(k) * sin[:, None]
        a = F.scaled_dot_product_attention(q.transpose(0, 1)[None], k.transpose(0, 1)[None].repeat_interleave(4, 1), v.transpose(0, 1)[None].repeat_interleave(4, 1), is_causal=True)[0].transpose(0, 1).reshape(n, -1)
        x = x + a @ deq(p + "attn_output.weight").T
        h = rms(x, deq(p + "ffn_norm.weight")); x = x + (F.silu(h @ deq(p + "ffn_gate.weight").T) * (h @ deq(p + "ffn_up.weight").T)) @ deq(p + "ffn_down.weight").T
        print("layer", i, "|x| rms %.2f max %.1f" % (float(x.pow(2).mean().sqrt()), float(x.abs().max())), flush=True)
    return ids, drop, x
if __name__ == "__main__":
    ids, drop, x = run(sys.argv[1]); print("T", len(ids), "drop", drop)
    np.save(sys.argv[2], x[drop:].numpy().astype(np.float32)); np.save(sys.argv[2].replace(".npy", "_ids.npy"), np.array(ids))
