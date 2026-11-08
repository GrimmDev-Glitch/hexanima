"""One-block test case for the phone (mode 40). usage: python mkblk_q.py OUTDIR PACKDIR BLOCK A|B [T] [H W]
A = prefix prefill (text rows, causal, mod from t=0); B = denoise step (image rows, keys = own + cached prefix K/V from A). Writes wts.bin x32.bin mod.bin cos.bin sin.bin [in_k.bin in_v.bin] ref.npz"""
import sys, os, math, numpy as np, torch, torch.nn.functional as F
import qref, qwen_ref as R
out, pdir, bi, kind = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
T = int(sys.argv[5]) if len(sys.argv) > 5 else 40
hh, ww = (int(sys.argv[6]), int(sys.argv[7])) if len(sys.argv) > 7 else (8, 8)
os.makedirs(out, exist_ok=True); torch.manual_seed(3)
wb = open(f"{pdir}/blk{bi:02d}.bin", "rb").read(); open(f"{out}/wts.bin", "wb").write(wb)
from packio import read_pack
Wm, A, qg, kg = read_pack(wb)
N = hh * ww; S = T + N
m = qref.load(1, lora=False)
def L(x, k): return R.lin(x, m.state_dict()[k + ".weight"].float(), qref.lora_delta(k + ".weight"))
def modvec(tv):
    fr = torch.exp(-math.log(10000) * torch.arange(128).float() / 128); a = 1000 * tv * fr
    e = torch.cat((torch.cos(a), torch.sin(a)))
    t = L(F.silu(L(e, "time_text_embed.timestep_embedder.linear_1")), "time_text_embed.timestep_embedder.linear_2")
    return L(F.silu(t), "modulation.1")
def setmod(tv):
    global sc1, g1, sc2, g2, modk
    sc1, g1, sc2, g2 = modvec(torch.tensor(tv)).chunk(4); modk = torch.cat((sc1, torch.tanh(g1), sc2, torch.tanh(g2)))
setmod(0.0 if kind in ("A", "C") else 0.7)
fr = m.pos_embed([(1, hh, ww)], torch.cat((torch.zeros(T, dtype=torch.bool), torch.ones(N, dtype=torch.bool))), device="cpu")
cos_all, sin_all = R.rope_halfsplit(fr)
def blockq(x, cos, sin, Kp=None, Vp=None, causal=False, blk=None):
    n = x.shape[0]; st = {}
    h = R.ln(x) * (1 + sc1); st["h"] = h
    Lq = h @ A["qkv"]; mm = lambda xin, W, l: torch.cat((xin, l), 1) @ W
    q = mm(h, Wm["q"], Lq[:, :128]).view(n, 32, 128); k = mm(h, Wm["k"], Lq[:, 128:256]).view(n, 32, 128); v = mm(h, Wm["v"], Lq[:, 256:]).view(n, 32, 128)
    q = R.apply_rope(R.rms(q, qg), cos, sin); k = R.apply_rope(R.rms(k, kg), cos, sin)
    st["q"], st["k"], st["v"] = q.reshape(n, -1), k.reshape(n, -1), v.reshape(n, -1)
    kk, vv = (k, v) if Kp is None else (torch.cat((k, Kp), 0), torch.cat((v, Vp), 0))
    mask = torch.tril(torch.ones(n, n, dtype=torch.bool)) if causal else torch.ones(n, kk.shape[0], dtype=torch.bool)
    if blk is not None: mask[blk[0]:blk[1], blk[0]:blk[1]] = True   # one bidirectional block
    a = F.scaled_dot_product_attention(q.transpose(0, 1)[None], kk.transpose(0, 1)[None], vv.transpose(0, 1)[None], attn_mask=mask)[0].transpose(0, 1).reshape(n, -1)
    st["o"] = a
    xa = x + mm(a, Wm["o"], a @ A["o"]) * torch.tanh(g1); st["xa"] = xa
    h2 = R.ln(xa) * (1 + sc2); L2 = h2 @ A["gp"]
    w = torch.cat((h2, L2), 1) @ Wm["gp"]; w = w.view(n, 384, 2, 32); mid = F.silu(w[:, :, 0].reshape(n, 12288)) * w[:, :, 1].reshape(n, 12288); st["mid"] = mid
    st["x"] = xa + mm(mid, Wm["down"], mid @ A["dn"]) * torch.tanh(g2)
    return st, k, v
def pad(t, rows): r = torch.zeros(rows, t.shape[1]); r[:t.shape[0]] = t; return r
def x32(t, ntok): t = pad(t, ntok); return np.ascontiguousarray(t.numpy().reshape(ntok // 32, 32, -1, 32).transpose(0, 2, 1, 3)).astype(np.float32)
def h16(t, rows): t = pad(t, rows); ns, ncb = rows // 32, t.shape[1] // 32; return np.ascontiguousarray(t.numpy().reshape(ns, 16, 2, ncb, 32).transpose(0, 3, 1, 4, 2)).astype(np.float16)
def tab(t, ntok): t = pad(t[:, :64] if t.shape[1] > 64 else t, ntok); return np.ascontiguousarray(t.numpy().reshape(ntok // 32, 32, 2, 32).transpose(0, 2, 1, 3)).astype(np.float32)
with torch.no_grad():
    xt = torch.randn(T, 4096) * 2.0
    ntokA = (T + 31) // 32 * 32
    if kind == "A":
        ntok, nv, x, cs, sn = ntokA, T, xt, cos_all[:T], sin_all[:T]
        st, k, v = blockq(x, cs, sn, causal=True)
    elif kind == "C":   # block-causal prefill: rows [BA,BB) bidirectional (env BA/BB)
        ba, bb = int(os.environ["BA"]), int(os.environ["BB"]); ntok, nv, x, cs, sn = ntokA, T, xt, cos_all[:T], sin_all[:T]
        st, k, v = blockq(x, cs, sn, causal=True, blk=(ba, bb))
    else:
        setmod(0.0); stA, kP, vP = blockq(xt, cos_all[:T], sin_all[:T], causal=True)     # prefix K/V from the prefill pass (mod from t=0)
        setmod(0.7); x = torch.randn(N, 4096) * 2.0; ntok, nv = (N + 31) // 32 * 32, N + T; assert N % 32 == 0
        st, k, v = blockq(x, cos_all[T:], sin_all[T:], Kp=kP, Vp=vP)
        npre = ntokA // 32; print("NPRE", npre)
        kb = np.concatenate((h16(k.reshape(N, -1), ntok), h16(kP.reshape(T, -1), ntokA))); vb = np.concatenate((h16(v.reshape(N, -1), ntok), h16(vP.reshape(T, -1), ntokA)))
        kb.tofile(f"{out}/in_k.bin"); vb.tofile(f"{out}/in_v.bin")
        cs, sn = cos_all[T:], sin_all[T:]
    ntok = ntok if kind == "A" else ntok
x32(x, ntok).tofile(f"{out}/x32.bin")
mf = np.zeros(18432, np.float32); mf[:modk.numel()] = modk.numpy(); mf.tofile(f"{out}/mod.bin")
tab(cs, ntok).tofile(f"{out}/cos.bin"); tab(sn, ntok).tofile(f"{out}/sin.bin")
np.savez(f"{out}/ref.npz", ntok=ntok, nvalid=x.shape[0], kind=kind, **{a: b.numpy() for a, b in st.items()})
print("kind", kind, "ntok", ntok, "nvalid", nv, "rows", x.shape[0])
