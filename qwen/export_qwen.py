"""Pack Qwen-Image-2.1-Turbo GGUF + Lightning v2-RL LoRA (r64, zero-padded to 128) into HMX q8 tile files, one per block (same tile format as MOSS).
blkNN.bin: W[q,k,v,o] [K=4096+128][N=4096] (q,k rows permuted per head: adjacent-pair -> half-split RoPE); W[gate_proj] [4096+128][24576] n-blocks interleaved (gate_j, proj_j);
W[down] [12288+128][4096]; A[qkv] [4096][384] (rank 64 in cols 0..63 of each 128), A[o] [4096][128], A[gate|proj] [4096][128] (gate cols 0..63, proj cols 64..127), A[down] [12288][128];
gain f32: q_norm (permuted)/sqrt(128), k_norm (permuted), pad to 8192 floats.   usage: python export_qwen.py OUTDIR [first last]"""
import os, sys, numpy as np, torch
sys.path.insert(0, "../moss")
from export_all_q8 import wt16, q8tiles
import qref
from qwen_ref import PERM
R = 128
T = qref.tensors()
def W(k):  # dequantized [out,in] for param key (gate_layer/proj come from fused gate_up)
    if k.endswith("img_mlp.gate_layer.weight"): return qref.deq(T[k.replace("gate_layer", "gate_up")])[:12288]
    if k.endswith("img_mlp.proj.weight"): return qref.deq(T[k.replace("proj", "gate_up")])[12288:]
    return qref.deq(T[k])
def lora(k):  # (A [r,in], B [out,r]*scale) fp32 or None
    from safetensors import safe_open
    f = qref.lora_delta  # reuse loader
    import qref as q; global _f
    if "_f" not in globals() or _f is None: _f = safe_open(q.LORA, "pt")
    b = "transformer." + k[:-len(".weight")]
    d = _f.get_tensor(b + ".lora_down.weight").float(); u = _f.get_tensor(b + ".lora_up.weight").float()
    return d, u * (float(_f.get_tensor(b + ".alpha")) / d.shape[0])
def headperm(w):  # [out,in] rows of 32 heads x 128 -> permuted within each head
    return w.view(32, 128, -1)[:, PERM].reshape(w.shape)
def mat(w_oi, B=None, extra=None):
    """w_oi [out,in]; B [out,r] (scaled) -> LoRA rows [128,N] appended; extra: list of (col0,col1,B) for interleaved case"""
    w = w_oi.T.contiguous()
    b = torch.zeros(R, w.shape[1])
    if B is not None: b[:B.shape[1]] = B.T
    return torch.cat((w, b), 0)
def tiles(w): return q8tiles(wt16(w.numpy()))
def interleave(w):   # [K,N=2h] -> (first block j, second block j) pairs of 32 columns
    K, N = w.shape; h = N // 2
    return torch.stack((w[:, :h].reshape(K, h // 32, 32), w[:, h:].reshape(K, h // 32, 32)), 2).reshape(K, N)
def pack_block(i):
    p = f"transformer_blocks.{i}."; out = []; sz = {}
    def add(n, b): sz[n] = len(b); out.append(b)
    for n in ("to_q", "to_k", "to_v", "to_out.0"):
        k = p + f"attn.{n}.weight"; w = W(k); A, B = lora(k)
        if n in ("to_q", "to_k"): w = headperm(w); B = headperm(B)
        add(n, tiles(mat(w, B)))
    kg, kp = p + "img_mlp.gate_layer.weight", p + "img_mlp.proj.weight"
    Ag, Bg = lora(kg); Ap, Bp = lora(kp)
    wg = torch.cat((W(kg).T, torch.cat((Bg.T, torch.zeros(64, 12288)), 0)), 0)      # [4096+128, 12288]: gate sees L cols 0..63
    wp = torch.cat((W(kp).T, torch.cat((torch.zeros(64, 12288), Bp.T), 0)), 0)      # proj sees L cols 64..127
    add("gate_proj", tiles(interleave(torch.cat((wg, wp), 1))))
    kd = p + "img_mlp.out.weight"; add("down", tiles(mat(W(kd), lora(kd)[1])))
    def Apad(A, c0=0):   # A [r,in] -> [in,128] with cols c0..c0+r
        o = torch.zeros(A.shape[1], 128); o[:, c0:c0 + A.shape[0]] = A.T; return o
    add("A_qkv", tiles(torch.cat([Apad(lora(p + f"attn.{n}.weight")[0]) if n != "to_q" and n != "to_k" else Apad(lora(p + f"attn.{n}.weight")[0]) for n in ("to_q", "to_k", "to_v")], 1)))
    add("A_o", tiles(Apad(lora(p + "attn.to_out.0.weight")[0])))
    add("A_gp", tiles(Apad(Ag) + Apad(Ap, 64)))
    add("A_down", tiles(Apad(lora(kd)[0])))
    qn = qref.deq(T[p + "attn.norm_q.weight"]); kn = qref.deq(T[p + "attn.norm_k.weight"])
    gain = torch.cat((qn[PERM] / np.sqrt(128.0), kn[PERM])).numpy().astype(np.float32)
    add("gain", gain.tobytes() + bytes(8192 * 4 - gain.nbytes))
    return b"".join(out), sz
if __name__ == "__main__":
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    a = int(sys.argv[2]) if len(sys.argv) > 2 else 0; b = int(sys.argv[3]) if len(sys.argv) > 3 else 31
    for i in range(a, b + 1):
        d, sz = pack_block(i); open(f"{out}/blk{i:02d}.bin", "wb").write(d); print(i, len(d), flush=True)
    print(sz)
