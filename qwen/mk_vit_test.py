"""ViT layer test for MODE 23: python mk_vit_test.py OUTDIR IMG W H -> OUTDIR/{x32,cos,sin,mod,in_lq,in_lo,in_lu,in_ld}.bin + ref.npz (block0 in/out, block8/16/24 outputs, final pooled+ds)"""
import sys, os, numpy as np, torch
import vit_ref as V
out, img, W, H = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]); os.makedirs(out, exist_ok=True)
px, thw = V.prep(img, W, H); N = px.shape[0]; ns = (N + 31) // 32; ntok = ns * 32
cap = {}
def pre(m, args, kw): cap["in"] = args[0].detach().clone(); cap["cos"], cap["sin"] = [t.detach().clone() for t in kw["position_embeddings"]]
def mk(i):
    def h(m, a, o): cap[f"out{i}"] = (o[0] if isinstance(o, tuple) else o).detach().clone()
    return h
V.m.blocks[0].register_forward_pre_hook(pre, with_kwargs=True)
for i in (0, 8, 16, 24, 26): V.m.blocks[i].register_forward_hook(mk(i))
with torch.no_grad(): o = V.m(px, grid_thw=thw)
cos, sin = cap["cos"], cap["sin"]; print("N", N, "cos", tuple(cos.shape), "in", tuple(cap["in"].shape), "rms in", float(cap["in"].pow(2).mean().sqrt()), "rms out0", float(cap["out0"].pow(2).mean().sqrt()))
def x32(t): r = torch.zeros(ntok, 1152); r[:t.shape[0]] = t; return np.ascontiguousarray(r.numpy().reshape(ns, 32, 36, 32).transpose(0, 2, 1, 3)).astype(np.float32)
def tab(t): r = torch.zeros(ntok, 64); r[:t.shape[0], :36] = t[:, :36]; return np.ascontiguousarray(r.numpy().reshape(ns, 32, 2, 32).transpose(0, 2, 1, 3)).astype(np.float32)
x32(cap["in"]).tofile(f"{out}/x32.bin"); tab(cos).tofile(f"{out}/cos.bin"); tab(sin).tofile(f"{out}/sin.bin")
mod = np.zeros(18432, np.float32); mod[:1152] = 1.0; mod.tofile(f"{out}/mod.bin")
def bias_tiles(ntile):
    t = np.zeros((ntile, 1024), np.float16)
    for i in range(0, ntile, 4):
        for r in range(32): t[i, (r // 2) * 64 + (r & 1)] = 1.0
    return t
for nm, nt in (("lq", ns * 12), ("lo", ns * 4), ("lu", ns * 4), ("ld", ns * 4)): bias_tiles(nt).tofile(f"{out}/in_{nm}.bin")
np.savez(f"{out}/ref.npz", ntok=ntok, nvalid=N, x_in=cap["in"].numpy(), **{k: v.numpy() for k, v in cap.items() if k.startswith("out")}, pooled=o.pooler_output.numpy(), **{f"ds{i}": d.numpy() for i, d in enumerate(o.deepstack_features)})
