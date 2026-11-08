"""Read a Qwen DiT block pack (v1 all-q8 or v2 with header and q4a) back into dense matrices for the reference tests."""
import sys, struct, numpy as np, torch
sys.path.insert(0, "../moss")
from export_te import unpack_q4a
def unpack(b, nb, kb, fmt):
    if fmt == 2: return torch.from_numpy(unpack_q4a(b, nb, kb).astype(np.float32).copy())
    rec = np.frombuffer(b, dtype=np.uint8).reshape(nb, kb, 1152)
    sc = rec[:, :, :128].copy().view(np.float16)[:, :, 0::2].astype(np.float32)
    q = (rec[:, :, 128:].astype(np.int16) - 128).reshape(nb, kb, 16, 32, 2).astype(np.float32) * sc[:, :, None, :, None]
    return torch.from_numpy(q.transpose(1, 2, 4, 0, 3).reshape(kb * 32, nb * 32).copy())
def read_pack(wb):
    mats = (("q", 128, 132), ("k", 128, 132), ("v", 128, 132), ("o", 128, 132), ("gp", 768, 132), ("down", 128, 388))
    amat = (("qkv", 12, 128), ("o", 4, 128), ("gp", 4, 128), ("dn", 4, 384))
    Wm, A = {}, {}
    if struct.unpack_from("<i", wb, 0)[0] == 0x00325751:
        h = struct.unpack_from("<11i6i", wb, 4); offs, fm = h[:11], h[11:]
    else:
        offs = [0, 19464192, 38928384, 58392576, 77856768, 194641920, 251854848, 253624320, 254214144, 254803968, 256573440]; fm = [1] * 6
    for i, (n, nb, kb) in enumerate(mats):
        sz = nb * kb * (768 if fm[i] == 2 else 1152); Wm[n] = unpack(wb[offs[i]:offs[i] + sz], nb, kb, fm[i])
    for i, (n, nb, kb) in enumerate(amat): A[n] = unpack(wb[offs[6 + i]:offs[6 + i] + nb * kb * 1152], nb, kb, 1)
    g = np.frombuffer(wb[offs[10]:offs[10] + 8192 * 4], dtype=np.float32)
    return Wm, A, torch.from_numpy(g[:128].copy()) * np.sqrt(128.0), torch.from_numpy(g[128:256].copy())
