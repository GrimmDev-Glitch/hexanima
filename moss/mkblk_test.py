"""Build a one-block test case for the phone: python mkblk_test.py OUTDIR BLOCK [emb] [H W]  -> OUTDIR/{wts,x32,mod,cos,sin}.bin + ref.npz"""
import sys, numpy as np, torch
from moss_ref import *
from export_moss import pack_layer
from safetensors import safe_open
out = sys.argv[1]; bi = int(sys.argv[2]); embf = sys.argv[3] if len(sys.argv) > 3 else "emb/q4xl_0.f32"
H, Wd = (int(sys.argv[4]), int(sys.argv[5])) if len(sys.argv) > 5 else (16, 16)
import os; os.makedirs(out, exist_ok=True)
g = W("models/MOSS-Image-SFT-UD-Q5_K_XL.gguf"); lo = Lora("models/lora.safetensors"); tp = safe_open("models/time_embedding.safetensors", "pt")
txt = torch.from_numpy(np.fromfile(embf, dtype=np.float32)).view(-1, 4096); T = txt.shape[0]
gen = torch.Generator("cpu").manual_seed(1); z = torch.randn(128, H, Wd, generator=gen)
ds = safe_open("models/dataspace.safetensors", "pt")
N = H * Wd + T; ntok = (N + 31) // 32 * 32
sin, cos = rope_tables(H, Wd, T, g("rope.bands"))
x = lin(z.flatten(1).T, g("video_embedder.weight"), g("video_embedder.bias"))
x = torch.cat((x, text_refine(txt, sin[H * Wd:], cos[H * Wd:], g)), 0)
mod, head = time_vecs(ds.get_tensor("step_codes")[0], g, tp)
# run blocks before bi in reference (so the test input is a realistic residual stream)
for i in range(bi): x = block(x, mod, sin, cos, g, i, lo)
print("ntok", ntok, "valid", N, "|x| max", float(x.abs().max()))
# pack + unpack (reference uses the q8 weights)
wb, sz = pack_layer(g, lo, bi); assert len(wb) == 135397376, len(wb); open(f"{out}/wts.bin", "wb").write(wb)
def unpack(b, nb, kb):
    rec = np.frombuffer(b, dtype=np.uint8).reshape(nb, kb, 1152)
    sc = rec[:, :, :128].copy().view(np.float16)[:, :, 0::2].astype(np.float32)
    q = (rec[:, :, 128:].astype(np.int16) - 128).reshape(nb, kb, 16, 32, 2).astype(np.float32) * sc[:, :, None, :, None]
    return torch.from_numpy(q.transpose(1, 2, 4, 0, 3).reshape(kb * 32, nb * 32).copy())   # [K, N]
o = 0; Wm = {}
for n, nb, kb in (("q", 96, 100), ("k", 96, 100), ("v", 96, 100), ("o", 96, 100), ("up_gate", 512, 100), ("down", 96, 260)):
    Wm[n] = unpack(wb[o:o + nb * kb * 1152], nb, kb); o += nb * kb * 1152
A = {}
for n, nb, kb in (("qkv", 12, 96), ("o", 4, 96), ("ug", 4, 96), ("dn", 4, 256)):
    A[n] = unpack(wb[o:o + nb * kb * 1152], nb, kb); o += nb * kb * 1152
gain = np.frombuffer(wb[o:o + 8192 * 4], dtype=np.float32)
qg, kg, n1, n2 = [torch.from_numpy(gain[a:b].copy()) for a, b in ((0, 128), (128, 256), (256, 3328), (3328, 6400))]
def deinterleave(w):  # [K', 16384] interleaved (up_j, gate_j) -> up, gate
    K = w.shape[0]; v = w.view(K, 256, 2, 32); return v[:, :, 0].reshape(K, 8192), v[:, :, 1].reshape(K, 8192)
sa, ca, ga, sf, cf, gf = mod
def mm(xin, Wk, Lx):  # [x | L] @ W' with L = x @ A
    return torch.cat((xin, Lx), 1) @ Wk
st = {}
y = rms(x, n1) * (1 + ca) + sa; st["h"] = y
L = y @ A["qkv"]
q = mm(y, Wm["q"], L[:, :128]); k = mm(y, Wm["k"], L[:, 128:256]); v = mm(y, Wm["v"], L[:, 256:])
Nn = x.shape[0]
q = rms(q.view(Nn, 24, 128), qg); k = rms(k.view(Nn, 24, 128), kg); q = rotate(q, sin, cos); k = rotate(k, sin, cos)
st["q"] = q.reshape(Nn, -1); st["k"] = k.reshape(Nn, -1); st["v"] = v
a = F.scaled_dot_product_attention(q.transpose(0, 1)[None], k.transpose(0, 1)[None], v.view(Nn, 24, 128).transpose(0, 1)[None], scale=1.0)[0].transpose(0, 1).reshape(Nn, -1)
st["o"] = a
xa = x + mm(a, Wm["o"], a @ A["o"]) * ga; st["xa"] = xa
y2 = rms(xa, n2) * (1 + cf) + sf; Lu = y2 @ A["ug"]
wu, wg = deinterleave(Wm["up_gate"]); Lw = Wm["up_gate"][3072:]; lu, lg = deinterleave(Wm["up_gate"])[0][3072:], deinterleave(Wm["up_gate"])[1][3072:]
up = torch.cat((y2, Lu), 1) @ wu; gt = torch.cat((y2, Lu), 1) @ wg
mid = situ(up, gt); st["mid"] = mid
xo = xa + (torch.cat((mid, mid @ A["dn"]), 1) @ Wm["down"]) * gf; st["x"] = xo
# ---- write device-layout inputs ----
def pad(t, rows=ntok): r = torch.zeros(rows, t.shape[1]); r[:t.shape[0]] = t; return r
def x32(t):  # [rows, C] -> [slab][cb][32][32] fp32
    t = pad(t); return np.ascontiguousarray(t.numpy().reshape(ntok // 32, 32, -1, 32).transpose(0, 2, 1, 3)).astype(np.float32)
x32(x).tofile(f"{out}/x32.bin")
mod.numpy().astype(np.float32).tofile(f"{out}/mod.bin")
def tab(t):  # [N,128] (dup halves) -> [slab][2 tiles][32 rows][32] from first 64 cols
    t = pad(t[:, :64]); return np.ascontiguousarray(t.numpy().reshape(ntok // 32, 32, 2, 32).transpose(0, 2, 1, 3)).astype(np.float32)
tab(cos).tofile(f"{out}/cos.bin"); tab(sin).tofile(f"{out}/sin.bin")
np.savez(f"{out}/ref.npz", ntok=ntok, nvalid=Nn, **{k_: v_.numpy() for k_, v_ in st.items()})
print("ref written; xo max", float(xo.abs().max()))
