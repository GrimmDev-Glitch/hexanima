"""Export one Anima block in the HMX crouton formats + test inputs, and the fp32 torch reference.
usage: python export_block.py MODEL.safetensors BLOCK RUNDIR OUTDIR   (RUNDIR has x_0.raw emb_0.raw lora_0.raw ctx_0.raw ref_0.raw)"""
import os, sys, math
import numpy as np, torch
from safetensors import safe_open
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.join(HERE, '..', '..'))
import phase0_block as pb, block_test as bt
D, H, HD = bt.D, bt.H, bt.HD
NT, GH, GW = 1536, 48, 32

def act16(a):   # [M,K] -> [rs][cb] tiles of (r/2, c, r&1)
    M, K = a.shape; return np.ascontiguousarray(a.astype(np.float16).reshape(M // 32, 16, 2, K // 32, 32).transpose(0, 3, 1, 4, 2))
def x32(a):     # [M,K] fp32 -> [rs][cb] tiles of 32x32 row-major
    M, K = a.shape; return np.ascontiguousarray(a.astype(np.float32).reshape(M // 32, 32, K // 32, 32).transpose(0, 2, 1, 3))
def wt16(w):    # w [K,N] -> [nb][kb] tiles
    K, N = w.shape; return np.ascontiguousarray(w.astype(np.float16).reshape(K // 32, 16, 2, N // 32, 32).transpose(3, 0, 1, 4, 2))

def main():
    model, blk, run, out = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    os.makedirs(out, exist_ok=True); torch.set_grad_enabled(False)
    pb.GH, pb.GW = GH, GW
    with safe_open(model, "pt") as f:
        sd = {k: f.get_tensor(k) for k in f.keys() if k.startswith(f"net.blocks.{blk}.")}
    fb = bt.FullBlock(sd, blk, NT, GH, GW)
    ld = lambda n, s: torch.from_numpy(np.fromfile(f"{run}/{n}_0.raw", np.float32).reshape(s))
    x, emb, lora, ctx, ref = ld("x", (1, D, GH, GW)), ld("emb", (1, D, 1, 1)), ld("lora", (1, 3 * D, 1, 1)), ld("ctx", (1, 1024, 1, 512)), ld("ref", (1, D, GH, GW))
    mine = fb(x, emb, lora, ctx)
    print("torch FullBlock vs dumped ref (rel delta):", ((mine - ref).norm() / (ref - x).norm()).item())
    np.save(f"{out}/ref.npy", mine.reshape(D, NT).numpy())
    p = f"net.blocks.{blk}."
    W = lambda n: sd[p + n + ".weight"].float().numpy().T      # [in,out]
    for key, nm in {"sq": "self_attn.q_proj", "sk": "self_attn.k_proj", "sv": "self_attn.v_proj", "so": "self_attn.output_proj", "cq": "cross_attn.q_proj",
                    "ck": "cross_attn.k_proj", "cv": "cross_attn.v_proj", "co": "cross_attn.output_proj", "m1": "mlp.layer1", "m2": "mlp.layer2"}.items():
        wt16(W(nm)).tofile(f"{out}/w_{key}.bin")
    x32(x.reshape(D, NT).numpy().T).tofile(f"{out}/x32.bin")
    act16(ctx.reshape(1024, 512).numpy().T).tofile(f"{out}/ctx16.bin")
    mods = []
    for m in ("self_attn", "cross_attn", "mlp"):
        for t in fb.mod(m, emb, lora): mods.append(t.reshape(D).numpy())
    np.concatenate(mods).astype(np.float32).tofile(f"{out}/mod.bin")
    sc = 1.0 / math.sqrt(HD); nrm = lambda n: sd[p + n + ".weight"].float().numpy()
    np.concatenate([nrm("self_attn.q_norm") * sc, nrm("self_attn.k_norm"), nrm("cross_attn.q_norm") * sc, nrm("cross_attn.k_norm")]).astype(np.float32).tofile(f"{out}/gain.bin")
    cos, sin = fb.cos.numpy(), fb.sin.numpy()                    # [NT,64]
    for nm, a in (("cos", cos), ("sin", sin)):                   # [slab][j][32 rows][32 cols]
        np.ascontiguousarray(a.reshape(NT // 32, 32, 2, 32).transpose(0, 2, 1, 3)).astype(np.float32).tofile(f"{out}/{nm}.bin")
    print("exported to", out)
main()
