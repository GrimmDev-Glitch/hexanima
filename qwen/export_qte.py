"""Qwen3-VL-8B (UD-Q4_K_XL GGUF) text encoder -> NPU layer packs te00..te35.bin (+ te36.bin: final norm weight, unused for Qwen-Image).
Pack = te_hdr_t (4096 B: off[8], fmt[8], nb[8], kb[8], voff) + matrices [q, kv(k|v), o, gate/up interleaved(32-row blocks), down] + f32 vectors [attn_norm 4096, ffn_norm 4096, q_norm 128, k_norm 128].  usage: python export_qte.py OUTDIR [first last]"""
import os, sys, struct, numpy as np, gguf
sys.path.insert(0, "../moss")
from export_te import q4k_parts, q4a_tiles
from export_all_q8 import wt16, q8tiles
import qwen_te_ref as TE
T = TE.T; Q4K = gguf.GGMLQuantizationType.Q4_K
def q8mat(w_oi): return q8tiles(wt16(np.ascontiguousarray(w_oi.numpy().T)))
def mat(name):
    t = T[name]; out, inn = int(t.shape[1]), int(t.shape[0])
    if t.tensor_type == Q4K: qq, S, M = q4k_parts(t.data, out, inn); return q4a_tiles(qq, S, M), 2, out // 32, inn // 32
    return q8mat(TE.deq(name)), 1, out // 32, inn // 32
def inter(a, b): return np.stack((a.reshape(-1, 32, *a.shape[1:]), b.reshape(-1, 32, *b.shape[1:])), 1).reshape(-1, *a.shape[1:])
def pack_layer(i):
    p = f"blk.{i}."; mats = [mat(p + "attn_q.weight")]
    import torch
    kv = torch.cat((TE.deq(p + "attn_k.weight"), TE.deq(p + "attn_v.weight")), 0); mats.append((q8mat(kv), 1, 64, 128))
    mats.append(mat(p + "attn_output.weight"))
    tg, tu = T[p + "ffn_gate.weight"], T[p + "ffn_up.weight"]
    if tg.tensor_type == tu.tensor_type == Q4K:
        a, b = q4k_parts(tg.data, 12288, 4096), q4k_parts(tu.data, 12288, 4096); mats.append((q4a_tiles(inter(a[0], b[0]), inter(a[1], b[1]), inter(a[2], b[2])), 2, 768, 128))
    else:
        g, u = TE.deq(p + "ffn_gate.weight").numpy(), TE.deq(p + "ffn_up.weight").numpy(); mats.append((q8tiles(wt16(np.ascontiguousarray(inter(g, u).T))), 1, 768, 128))
    mats.append(mat(p + "ffn_down.weight"))
    hdr = [0] * 33; off = 4096
    for j, (b, f, nb, kb) in enumerate(mats): hdr[j] = off; hdr[8 + j] = f; hdr[16 + j] = nb; hdr[24 + j] = kb; off += len(b)
    vec = [TE.deq(p + n).numpy().astype(np.float32).reshape(-1) for n in ("attn_norm.weight", "ffn_norm.weight", "attn_q_norm.weight", "attn_k_norm.weight")]
    hdr[32] = off
    return struct.pack("<33i", *hdr) + bytes(4096 - 132) + b"".join(m[0] for m in mats) + np.concatenate(vec).tobytes(), [(m[1], len(m[0])) for m in mats]
if __name__ == "__main__":
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    a = int(sys.argv[2]) if len(sys.argv) > 2 else 0; b = int(sys.argv[3]) if len(sys.argv) > 3 else 35
    for i in range(a, b + 1):
        d, info = pack_layer(i); open(f"{out}/te{i:02d}.bin", "wb").write(d); print(i, len(d), info, flush=True)
    if b == 35: w = TE.deq("output_norm.weight").numpy().astype(np.float32); open(f"{out}/te36.bin", "wb").write(w.tobytes() + bytes(16384 - w.nbytes))
