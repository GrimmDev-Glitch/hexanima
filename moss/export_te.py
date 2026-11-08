"""Qwen3.5-9B GGUF -> NPU tile packs. q4a record (768 B per 32x32 tile): scale vec 128 B (halfword l = scale of column l//2), min vec 128 B, 512 B nibbles:
byte b of vector m: low nibble = halfword 256m+b, high nibble = halfword 256m+128+b (halfword order of the h16 tile [16][32][2]). value = nibble*scale + min.
q8 record: as export_all_q8.q8tiles."""
import sys, numpy as np, gguf, torch
from export_all_q8 import wt16, q8tiles
def q4k_parts(data, rows, cols):
    """Q4_K raw blocks -> q [rows, cols] uint8, S [rows, cols/32] f32 (d*sc), M [rows, cols/32] f32 (-dmin*m)"""
    b = np.asarray(data).reshape(rows, cols // 256, 144)
    d = b[:, :, 0:2].copy().view(np.float16).astype(np.float32)[..., 0]; dmin = b[:, :, 2:4].copy().view(np.float16).astype(np.float32)[..., 0]
    sc12 = b[:, :, 4:16].astype(np.uint8)
    sc = np.zeros(sc12.shape[:2] + (8,), np.float32); mn = np.zeros_like(sc)
    for j in range(4):
        sc[:, :, j] = sc12[:, :, j] & 63; mn[:, :, j] = sc12[:, :, j + 4] & 63
    for j in range(4, 8):
        sc[:, :, j] = (sc12[:, :, j + 4] & 0xF) | ((sc12[:, :, j - 4] >> 6) << 4); mn[:, :, j] = (sc12[:, :, j + 4] >> 4) | ((sc12[:, :, j] >> 6) << 4)
    qs = b[:, :, 16:144].reshape(rows, cols // 256, 4, 32)
    q = np.zeros((rows, cols // 256, 4, 2, 32), np.uint8); q[:, :, :, 0] = qs & 15; q[:, :, :, 1] = qs >> 4      # chunk c: low nibbles = subblock 2c, high = 2c+1
    q = q.reshape(rows, cols)
    S = (d[:, :, None] * sc).reshape(rows, cols // 32); M = (-dmin[:, :, None] * mn).reshape(rows, cols // 32)
    return q, S, M
def q4a_tiles(q, S, M):
    """q [out, in] uint8; S, M [out, in/32] -> bytes [nb][kb] x 768"""
    out, inn = q.shape; nb, kb = out // 32, inn // 32
    t = q.reshape(nb, 32, kb, 32).transpose(0, 2, 3, 1)                      # [nb][kb][r (k within group)][c (out within block)]
    t = t.reshape(nb, kb, 16, 2, 32).transpose(0, 1, 2, 4, 3).reshape(nb, kb, 1024)   # halfword order [r/2][c][r&1]
    lo = t[:, :, :512].reshape(nb, kb, 4, 128); hi = t[:, :, 512:].reshape(nb, kb, 4, 128)   # halfword 256m+b -> first half... (see below)
    # byte b of vector m: low nibble = halfword 256m+b, high nibble = halfword 256m+128+b
    t2 = t.reshape(nb, kb, 4, 2, 128)
    nib = (t2[:, :, :, 0] | (t2[:, :, :, 1] << 4)).astype(np.uint8)         # [nb][kb][4][128]
    s = S.reshape(nb, 32, kb).transpose(0, 2, 1).astype(np.float16); m = M.reshape(nb, 32, kb).transpose(0, 2, 1).astype(np.float16)   # [nb][kb][32 cols]
    rec = np.concatenate([np.repeat(s, 2, axis=2).view(np.uint8).reshape(nb, kb, 128), np.repeat(m, 2, axis=2).view(np.uint8).reshape(nb, kb, 128), nib.reshape(nb, kb, 512)], axis=2)
    return np.ascontiguousarray(rec).tobytes()
def unpack_q4a(b, nb, kb):
    """-> [K, N] float32 (for tests)"""
    rec = np.frombuffer(b, dtype=np.uint8).reshape(nb, kb, 768)
    s = rec[:, :, :128].copy().view(np.float16)[:, :, 0::2].astype(np.float32); m = rec[:, :, 128:256].copy().view(np.float16)[:, :, 0::2].astype(np.float32)
    nib = rec[:, :, 256:].reshape(nb, kb, 4, 128); v = np.zeros((nb, kb, 4, 2, 128), np.float32); v[:, :, :, 0] = nib & 15; v[:, :, :, 1] = nib >> 4
    v = v.reshape(nb, kb, 16, 32, 2) * s[:, :, None, :, None] + m[:, :, None, :, None]
    return v.transpose(1, 2, 4, 0, 3).reshape(kb * 32, nb * 32)

import struct
from qwen35_ref import Q
def _mat_q8(w_oi): return q8tiles(wt16(np.ascontiguousarray(w_oi.T)))      # w_oi [out,in] float32 numpy
def pack_mat(q, name):
    t = q.t[name]
    out, inn = int(t.shape[1]), int(t.shape[0])
    if t.tensor_type == gguf.GGMLQuantizationType.Q4_K:
        qq, S, M = q4k_parts(t.data, out, inn); return q4a_tiles(qq, S, M), 2, out // 32, inn // 32
    return _mat_q8(q(name).numpy()), 1, out // 32, inn // 32
def pack_gu(q, p):
    tg, tu = q.t[p + "ffn_gate.weight"], q.t[p + "ffn_up.weight"]
    inter = lambda a, b: np.stack((a.reshape(-1, 32, *a.shape[1:]), b.reshape(-1, 32, *b.shape[1:])), 1).reshape(-1, *a.shape[1:])
    if tg.tensor_type == tu.tensor_type == gguf.GGMLQuantizationType.Q4_K:
        a, b = q4k_parts(tg.data, 12288, 4096), q4k_parts(tu.data, 12288, 4096)
        return q4a_tiles(inter(a[0], b[0]), inter(a[1], b[1]), inter(a[2], b[2])), 2, 768, 128
    return _mat_q8(inter(q(p + "ffn_gate.weight").numpy(), q(p + "ffn_up.weight").numpy())), 1, 768, 128
def pack_te_layer(q, i):
    p = f"blk.{i}."; delta = p + "attn_qkv.weight" in q.t; mats = []
    if delta:
        mats.append(pack_mat(q, p + "attn_qkv.weight")); mats.append(pack_mat(q, p + "attn_gate.weight"))
        ab = np.concatenate((q(p + "ssm_alpha.weight").numpy(), q(p + "ssm_beta.weight").numpy()), 0); mats.append((_mat_q8(ab), 1, 2, 128))
        mats.append(pack_mat(q, p + "ssm_out.weight"))
    else:
        mats.append(pack_mat(q, p + "attn_q.weight"))
        kv = np.concatenate((q(p + "attn_k.weight").numpy(), q(p + "attn_v.weight").numpy()), 0); mats.append((_mat_q8(kv), 1, 64, 128))
        mats.append(pack_mat(q, p + "attn_output.weight"))
    mats.append(pack_gu(q, p)); mats.append(pack_mat(q, p + "ffn_down.weight"))
    hdr = [0] * 33; off = 4096
    for j, (b, f, nb, kb) in enumerate(mats): hdr[j] = off; hdr[8 + j] = f; hdr[16 + j] = nb; hdr[24 + j] = kb; off += len(b)
    vec = [q(p + "attn_norm.weight").numpy(), q(p + "post_attention_norm.weight").numpy()]
    if delta: vec += [q(p + "ssm_conv1d.weight").numpy().T.copy().reshape(-1), q(p + "ssm_a").numpy(), q(p + "ssm_dt.bias").numpy(), q(p + "ssm_norm.weight").numpy()]
    else: vec += [q(p + "attn_q_norm.weight").numpy(), q(p + "attn_k_norm.weight").numpy()]
    vb = np.concatenate([v.astype(np.float32).reshape(-1) for v in vec]).tobytes()
    hdr[32] = off
    return struct.pack("<33i", *hdr) + bytes(4096 - 132) + b"".join(m[0] for m in mats) + vb, [(m[1], len(m[0])) for m in mats]
