"""Qwen DiT pack v2: blkNN.bin = 4096 B header {magic 'QW2\\0', off[11] (q,k,v,o,gate/proj,down,A_qkv,A_o,A_gp,A_down,gain), fmt[6] of the big matrices (1 = q8 g32 tile 1152 B, 2 = q4a 768 B)} + matrices + gain.
Matrices whose GGUF tensor is Q4_K are repacked LOSSLESSLY into q4a (the 4-bit values and the per-(32-group, row) scale/min are kept; the Lightning LoRA B rows are appended as 4-bit rows too: the LoRA delta is ~0.2% of W, a 4-bit B adds a ~0.02% error); everything else (Q5_K, Q6_K, Q8_0) stays q8.
usage: python export_qwen2.py OUTDIR [first last]"""
import os, sys, struct, numpy as np, torch, gguf
sys.path.insert(0, "../moss")
from export_te import q4k_parts, q4a_tiles
import export_qwen as E
from qwen_ref import PERM
Q4K = gguf.GGMLQuantizationType.Q4_K
HP = torch.arange(4096).view(32, 128)[:, PERM].reshape(-1).numpy()      # output-row permutation inside every head (adjacent-pair -> half-split rope)
def quant_b(B):   # B [rows, 128] float -> uint8 [rows,128], S, M [rows,4]: 4-bit per (row, 32 ranks)
    g = B.reshape(B.shape[0], 4, 32); mn = g.min(2); mx = g.max(2); S = (mx - mn) / 15.0
    q = np.where(S[..., None] > 0, np.rint((g - mn[..., None]) / np.where(S[..., None] > 0, S[..., None], 1)), 0).clip(0, 15).astype(np.uint8)
    return q.reshape(B.shape), S.astype(np.float32), mn.astype(np.float32)
def q4_mat(name, rows, cols, B, perm=None):
    """GGUF Q4_K tensor [rows,cols] (+ LoRA rows B [rows,128] float) -> q4a tile bytes [nb][kb+4]"""
    qq, S, M = q4k_parts(E.T[name].data, rows, cols)
    if perm is not None: qq, S, M = qq[perm], S[perm], M[perm]
    qb, Sb, Mb = quant_b(B if perm is None else B[perm])
    return q4a_tiles(np.concatenate((qq, qb), 1), np.concatenate((S, Sb), 1), np.concatenate((M, Mb), 1))
def inter(a, b):   # 32-row blocks of a and b interleaved (gate_j, proj_j)
    return np.stack((a.reshape(-1, 32, *a.shape[1:]), b.reshape(-1, 32, *b.shape[1:])), 1).reshape(-1, *a.shape[1:])
def big(name_w, k_key, perm=False):
    """returns (bytes, fmt) for to_q/k/v/out or down: name_w = param key"""
    t = E.T[name_w]
    A, B = E.lora(name_w)
    if t.tensor_type == Q4K and True:
        Bp = np.zeros((B.shape[0], 128), np.float32); Bp[:, :B.shape[1]] = B.numpy()
        return q4_mat(name_w, B.shape[0], int(t.shape[0]), Bp, HP if perm else None), 2
    w = E.W(name_w); Bq = B
    if perm: w = E.headperm(w); Bq = E.headperm(B)
    return E.tiles(E.mat(w, Bq)), 1
def pack_block(i):
    p = f"transformer_blocks.{i}."; mats = []; fm = []
    for n, perm in (("to_q", True), ("to_k", True), ("to_v", False), ("to_out.0", False)):
        b, f = big(p + f"attn.{n}.weight", None, perm); mats.append(b); fm.append(f)
    # gate/proj fused tensor
    gk, pk, fk = p + "img_mlp.gate_layer.weight", p + "img_mlp.proj.weight", p + "img_mlp.gate_up.weight"
    Ag, Bg = E.lora(gk); Ap, Bp = E.lora(pk)
    t = E.T[fk]
    if t.tensor_type == Q4K:
        B = np.zeros((24576, 128), np.float32); B[:12288, :64] = Bg.numpy(); B[12288:, 64:] = Bp.numpy()
        qq, S, M = q4k_parts(t.data, 24576, 4096); qb, Sb, Mb = quant_b(B)
        qq = np.concatenate((qq, qb), 1); S = np.concatenate((S, Sb), 1); M = np.concatenate((M, Mb), 1)
        b = q4a_tiles(inter(qq[:12288], qq[12288:]), inter(S[:12288], S[12288:]), inter(M[:12288], M[12288:])); f = 2
    else:
        wg = torch.cat((E.W(gk).T, torch.cat((Bg.T, torch.zeros(64, 12288)), 0)), 0); wp = torch.cat((E.W(pk).T, torch.cat((torch.zeros(64, 12288), Bp.T), 0)), 0)
        b = E.tiles(E.interleave(torch.cat((wg, wp), 1))); f = 1
    mats.append(b); fm.append(f)
    b, f = big(p + "img_mlp.out.weight", None, False); mats.append(b); fm.append(f)
    def Apad(A, c0=0): o = torch.zeros(A.shape[1], 128); o[:, c0:c0 + A.shape[0]] = A.T; return o
    Aq = [E.lora(p + f"attn.{n}.weight")[0] for n in ("to_q", "to_k", "to_v")]
    mats.append(E.tiles(torch.cat([Apad(a) for a in Aq], 1))); mats.append(E.tiles(Apad(E.lora(p + "attn.to_out.0.weight")[0])))
    mats.append(E.tiles(Apad(Ag) + Apad(Ap, 64))); mats.append(E.tiles(Apad(E.lora(p + "img_mlp.out.weight")[0])))
    qn = E.qref.deq(E.T[p + "attn.norm_q.weight"]); kn = E.qref.deq(E.T[p + "attn.norm_k.weight"])
    gain = torch.cat((qn[PERM] / np.sqrt(128.0), kn[PERM])).numpy().astype(np.float32); mats.append(gain.tobytes() + bytes(8192 * 4 - gain.nbytes))
    off = 4096; offs = []
    for m in mats: offs.append(off); off += len(m)
    hdr = struct.pack("<i11i6i", 0x00325751, *offs, *fm); hdr += bytes(4096 - len(hdr))
    return hdr + b"".join(mats), fm
if __name__ == "__main__":
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    a = int(sys.argv[2]) if len(sys.argv) > 2 else 0; b = int(sys.argv[3]) if len(sys.argv) > 3 else 31; tot = 0
    for i in range(a, b + 1):
        d, fm = pack_block(i); open(f"{out}/blk{i:02d}.bin", "wb").write(d); tot += len(d); print(i, len(d), fm, flush=True)
    print("total MB", tot / 1e6)
