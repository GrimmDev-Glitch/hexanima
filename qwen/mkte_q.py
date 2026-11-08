"""One Qwen3-VL TE layer test (mode 22). usage: python mkte_q.py OUTDIR PACKDIR LAYER "prompt"  -> OUTDIR/{wts.bin,x32.bin,mod.bin,in_cs.bin,ref_x.npy}; run: NCB=128 MODE=22 WTSIZE=<pack size> ./blk DIR NTOK T"""
import sys, os, numpy as np, torch
import qwen_te_ref as TE
out, pdir, L, prompt = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
os.makedirs(out, exist_ok=True); torch.set_grad_enabled(False)
ids, drop = TE.encode(prompt); n = len(ids); ntok = (n + 31) // 32 * 32
x = TE.deq("token_embd.weight", rows=ids)
inv = 5e6 ** (-torch.arange(0, 128, 2).float() / 128); ang = torch.arange(n)[:, None] * inv[None]; cos = torch.cat((ang.cos(),) * 2, -1); sin = torch.cat((ang.sin(),) * 2, -1)
import torch.nn.functional as F
def layer(x, i):
    p = f"blk.{i}."; h = TE.rms(x, TE.deq(p + "attn_norm.weight"))
    q = (h @ TE.deq(p + "attn_q.weight").T).view(n, 32, 128); k = (h @ TE.deq(p + "attn_k.weight").T).view(n, 8, 128); v = (h @ TE.deq(p + "attn_v.weight").T).view(n, 8, 128)
    q = TE.rms(q, TE.deq(p + "attn_q_norm.weight")); k = TE.rms(k, TE.deq(p + "attn_k_norm.weight"))
    q = q * cos[:, None] + TE.rot_half(q) * sin[:, None]; k = k * cos[:, None] + TE.rot_half(k) * sin[:, None]
    a = F.scaled_dot_product_attention(q.transpose(0, 1)[None], k.transpose(0, 1)[None].repeat_interleave(4, 1), v.transpose(0, 1)[None].repeat_interleave(4, 1), is_causal=True)[0].transpose(0, 1).reshape(n, -1)
    x = x + a @ TE.deq(p + "attn_output.weight").T
    h = TE.rms(x, TE.deq(p + "ffn_norm.weight")); return x + (F.silu(h @ TE.deq(p + "ffn_gate.weight").T) * (h @ TE.deq(p + "ffn_up.weight").T)) @ TE.deq(p + "ffn_down.weight").T
for i in range(L): x = layer(x, i)
y = layer(x, L); np.save(f"{out}/ref_x.npy", y.numpy())
def x32(t): r = torch.zeros(ntok, 4096); r[:t.shape[0]] = t; return np.ascontiguousarray(r.numpy().reshape(ntok // 32, 32, 128, 32).transpose(0, 2, 1, 3)).astype(np.float32)
x32(x).tofile(f"{out}/x32.bin")
cs = np.zeros((ntok, 128), np.float32); cs[:n, :64] = cos[:, :64].numpy(); cs[:n, 64:] = sin[:, :64].numpy(); cs.tofile(f"{out}/in_cs.bin")
def tab(t): r = torch.zeros(ntok, 64); r[:t.shape[0]] = t[:, :64]; return np.ascontiguousarray(r.numpy().reshape(ntok // 32, 32, 2, 32).transpose(0, 2, 1, 3)).astype(np.float32)
tab(cos).tofile(f"{out}/cos.bin"); tab(sin).tofile(f"{out}/sin.bin")
mod = np.zeros(18432, np.float32); mod[8192:12288] = 1.0; mod.tofile(f"{out}/mod.bin")
os.system(f"cp {pdir}/te{L:02d}.bin {out}/wts.bin"); print("T", n, "ntok", ntok, "pack", os.path.getsize(f"{out}/wts.bin"))
