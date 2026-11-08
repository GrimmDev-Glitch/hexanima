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
    s = np.where(s < 6.2e-5, 6.2e-5, s).astype(np.float16); sf = s.astype(np.float32)   # floor at fp16 min normal: a denormal/zero scale made 0/0 = NaN
    q = np.clip(np.rint(a / sf[:, :, None, :, None]), -127, 127).astype(np.int8)
    q = (q.astype(np.int16) + 128).astype(np.uint8)   # offset binary: the DSP builds 1024 + u as an fp16 bit pattern
    sv = np.repeat(s, 2, axis=2)                           # [nb][kb][64]
    rec = np.concatenate([sv.view(np.uint8).reshape(nb, kb, 128), q.reshape(nb, kb, 1024).view(np.uint8)], axis=2)
    return np.ascontiguousarray(rec).tobytes()
