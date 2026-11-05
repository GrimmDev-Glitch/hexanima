"""Export all DiT blocks as HMX weight files: <out>/blkNN.bin = [10 weight matrices in fp16 crouton][gain 4x128 f32 (pad 4096)][mod weights fp16: per site a[256][2048], b[6144][256]].
usage: python export_all.py [--q8] MODEL.safetensors OUTDIR [first last]   (--q8: int8 tiles + per-column fp16 scales, 83 MB per block)"""
import os, sys, math
import numpy as np, torch
from safetensors import safe_open
HD = 128
def wt16(w):    # w [K,N] -> [nb][kb] tiles (paired rows)
    K, N = w.shape; return np.ascontiguousarray(w.astype(np.float16).reshape(K // 32, 16, 2, N // 32, 32).transpose(3, 0, 1, 4, 2))
ORDER = ["self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.output_proj", "cross_attn.q_proj", "cross_attn.k_proj", "cross_attn.v_proj", "cross_attn.output_proj", "mlp.layer1", "mlp.layer2"]
def q8tiles(t):   # t: wt16 result [nb][kb][16][32][2] fp16 -> bytes of [nb][kb] records: 128 B scale vector (halfword l = scale of column l//2) + 1024 B int8
    a = t.astype(np.float32); nb, kb = a.shape[:2]
    s = np.abs(a).max(axis=(2, 4)) / 127.0              # [nb][kb][32 cols]
    s = np.where(s == 0, 1.0, s).astype(np.float16); sf = s.astype(np.float32)
    q = np.clip(np.rint(a / sf[:, :, None, :, None]), -127, 127).astype(np.int8)
    q = (q.astype(np.int16) + 128).astype(np.uint8)   # offset binary: the DSP builds 1024 + u as an fp16 bit pattern
    sv = np.repeat(s, 2, axis=2)                           # [nb][kb][64]
    rec = np.concatenate([sv.view(np.uint8).reshape(nb, kb, 128), q.reshape(nb, kb, 1024).view(np.uint8)], axis=2)
    return np.ascontiguousarray(rec).tobytes()
def main():
    Q8 = "--q8" in sys.argv; sys.argv = [x for x in sys.argv if x != "--q8"]
    model, out = sys.argv[1], sys.argv[2]; os.makedirs(out, exist_ok=True)
    first = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    with safe_open(model, "pt") as f:
        n = 1 + max(int(k.split(".")[2]) for k in f.keys() if k.startswith("net.blocks."))
        last = int(sys.argv[4]) if len(sys.argv) > 4 else n - 1
        for i in range(first, last + 1):
            p = f"net.blocks.{i}."
            g = lambda k: f.get_tensor(p + k).float().numpy()
            with open(f"{out}/blk{i:02d}.bin", "wb") as o:
                for nm in ORDER: o.write(q8tiles(wt16(g(nm + ".weight").T)) if Q8 else wt16(g(nm + ".weight").T).tobytes())
                sc = 1.0 / math.sqrt(HD)
                gain = np.concatenate([g("self_attn.q_norm.weight") * sc, g("self_attn.k_norm.weight"), g("cross_attn.q_norm.weight") * sc, g("cross_attn.k_norm.weight")]).astype(np.float32)
                o.write(gain.tobytes()); o.write(bytes(4096 - gain.nbytes))
                for m in ("self_attn", "cross_attn", "mlp"):
                    o.write(g(f"adaln_modulation_{m}.1.weight").astype(np.float16).tobytes())   # [256][2048]
                    o.write(g(f"adaln_modulation_{m}.2.weight").astype(np.float16).tobytes())   # [6144][256]
            print("block", i, os.path.getsize(f"{out}/blk{i:02d}.bin"), flush=True)
main()
