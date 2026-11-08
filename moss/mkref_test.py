"""Text refiner on the NPU test: python mkref_test.py OUTDIR [emb]  -> OUTDIR/{proj.bin,ref0.bin,ref1.bin,...}, ref.npz"""
import sys, os, numpy as np, torch
from moss_ref import *
from export_moss import pack_refiner, pack_proj
out = sys.argv[1]; embf = sys.argv[2] if len(sys.argv) > 2 else "emb/q4xl_0.f32"; os.makedirs(out, exist_ok=True)
g = W("models/MOSS-Image-SFT-UD-Q5_K_XL.gguf")
txt = torch.from_numpy(np.fromfile(embf, dtype=np.float32)).view(-1, 4096); T = txt.shape[0]; ntok = (T + 31) // 32 * 32; ns = ntok // 32
sin, cos = rope_tables(1, 1, T, g("rope.bands")); sin_t, cos_t = sin[1:], cos[1:]          # text rows only (H*W = 1 image token skipped)
open(f"{out}/proj.bin", "wb").write(pack_proj(g))
for i in range(2): b, _ = pack_refiner(g, i); assert len(b) == 135397376; open(f"{out}/ref{i}.bin", "wb").write(b)
ref = text_refine(txt, sin_t, cos_t, g); np.save(f"{out}/ref_out.npy", ref.numpy())
def tab(t): t = torch.zeros(ntok, 64).index_copy_(0, torch.arange(T), t[:, :64]); return np.ascontiguousarray(t.numpy().reshape(ns, 32, 2, 32).transpose(0, 2, 1, 3)).astype(np.float32)
tab(cos_t).tofile(f"{out}/cos.bin"); tab(sin_t).tofile(f"{out}/sin.bin")
t16 = torch.zeros(ntok, 4096); t16[:T] = txt                                               # fp16 tiles [slab][128 cb], halfword (r,c) = (r/2)*64+(c%32)*2+(r&1)
tiles = np.ascontiguousarray(t16.numpy().astype(np.float16).reshape(ns, 16, 2, 128, 32).transpose(0, 3, 1, 4, 2))   # [slab][cb][r/2][c][r&1]
tiles.tofile(f"{out}/in_h.bin")
m = np.zeros((6, 3072), np.float32); m[2] = 1; m[5] = 1; m.tofile(f"{out}/mod.bin")
np.zeros((ns, 96, 32, 32), np.float32).tofile(f"{out}/x32.bin")
def consts(ntiles):   # every 4th tile has fp16 1.0 in column 0 (bias row selector)
    t = np.zeros((ntiles, 1024), np.float16)
    for r in range(32): t[0::4, (r // 2) * 64 + (r & 1)] = 1.0
    return t
consts(ns * 12).tofile(f"{out}/in_lq.bin"); consts(ns * 4).tofile(f"{out}/in_lo.bin"); consts(ns * 4).tofile(f"{out}/in_lu.bin"); consts(ns * 4).tofile(f"{out}/in_ld.bin")
print("T", T, "ntok", ntok)
