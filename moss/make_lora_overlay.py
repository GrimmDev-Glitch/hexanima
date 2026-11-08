"""User MOSS LoRA (kohya/ai-toolkit: layers.N.proj.{qkv,o,ffn_up_gate,ffn_down}.lora_A/B) folded into the rank-128 LoRA slots of the packs.
dW = s_t*Bt*At + s_u*Bu*Au  -> rank<=136 -> SVD -> rank 128 (B' carries the singular values).
Output: overlay.bin = [u32 nrec] then nrec x ([u64 blk_off][u32 len] data) per block file lora_ovl/blkNN.ovl (offsets inside blkNN.bin).
usage: make_lora_overlay.py USER.safetensors OUTDIR STRENGTH [check]"""
import os, sys, struct, numpy as np, torch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from moss_ref import Lora
from export_all_q8 import wt16, q8tiles
TILE = 1152
# (name, K, N) in pack order; qkv fused user matrices are split
MATS = [("q", 3072, 3072), ("k", 3072, 3072), ("v", 3072, 3072), ("o", 3072, 3072), ("up_gate", 3072, 16384), ("down", 8192, 3072)]
def il(b):  # up_gate n-block interleave of [128,N]
    h = b.shape[1] // 2
    return torch.stack((b[:, :h].reshape(128, h // 32, 32), b[:, h:].reshape(128, h // 32, 32)), 2).reshape(128, -1)
def fold(At, Bt, Au, Bu, r=128):  # At [128,in] Bt [N,128](scaled) ; Au [8,in] Bu [N,8](scaled)
    B = torch.cat((Bt, Bu), 1).double(); A = torch.cat((At, Au), 0).double()
    Qb, Rb = torch.linalg.qr(B); Qa, Ra = torch.linalg.qr(A.T)
    U, S, Vh = torch.linalg.svd(Rb @ Ra.T)
    Bn = Qb @ (U[:, :r] * S[:r]); An = (Vh[:r] @ Qa.T)
    return An.float(), Bn.float(), float(S[r:].norm() / S.norm())
if __name__ == "__main__":
    user, out, strength = sys.argv[1], sys.argv[2], float(sys.argv[3]); os.makedirs(out, exist_ok=True)
    from safetensors import safe_open
    uf = safe_open(user, "pt"); tl = Lora("models/lora.safetensors")
    rank = uf.get_tensor("diffusion_model.layers.0.proj.o.lora_A.weight").shape[0]
    su = float(uf.metadata().get("ss_network_alpha", rank)) / rank * strength if uf.metadata() else strength
    print("user rank", rank, "scale", su); worst = 0
    for i in range(42):
        def U(n):
            k = f"diffusion_model.layers.{i}.proj.{n}."
            if k + "lora_A.weight" not in uf.keys(): return None
            return uf.get_tensor(k + "lora_A.weight").float(), uf.get_tensor(k + "lora_B.weight").float() * su
        uq, uo, uu, ud = U("qkv"), U("o"), U("ffn_up_gate"), U("ffn_down")
        recs = []; off = 0; Aparts = {}
        for name, K, N in MATS:
            nb = N // 32; kb = K // 32; panel = (kb + 4) * TILE
            At, Bt, st = tl(i, {"q": "q", "k": "k", "v": "v", "o": "o", "up_gate": "ffn_up_gate", "down": "ffn_down"}[name])
            Bt = Bt * st
            usr = {"q": uq, "k": uq, "v": uq, "o": uo, "up_gate": uu, "down": ud}[name]
            if usr is None: Au = torch.zeros(rank, K); Bu = torch.zeros(N, rank)
            else:
                Au, Bu = usr
                if name in "qkv": j = "qkv".index(name); Bu = Bu[j * 3072:(j + 1) * 3072]
            An, Bn, err = fold(At, Bt, Au, Bu); worst = max(worst, err)
            b = Bn.T.contiguous()                                  # [128,N]
            if name == "up_gate": b = il(b)
            tiles = q8tiles(wt16(b.numpy()))                       # nb x 4 tiles
            assert len(tiles) == nb * 4 * TILE
            for n in range(nb): recs.append((off + n * panel + kb * TILE, tiles[n * 4 * TILE:(n + 1) * 4 * TILE]))
            off += nb * panel; Aparts[name] = An.T.contiguous()    # [in,128]
        # A matrices: A_qkv [3072][384], A_o, A_up_gate, A_down (q8 tile files)
        for nm, m in (("A_qkv", torch.cat((Aparts["q"], Aparts["k"], Aparts["v"]), 1)), ("A_o", Aparts["o"]), ("A_ug", Aparts["up_gate"]), ("A_down", Aparts["down"])):
            d = q8tiles(wt16(m.numpy())); recs.append((off, d)); off += len(d)
        with open(f"{out}/blk{i:02d}.ovl", "wb") as f:
            f.write(struct.pack("<I", len(recs)))
            for o, d in recs: f.write(struct.pack("<QI", o, len(d))); f.write(d)
        print(i, len(recs), "trunc err %.2e" % err, flush=True)
    print("worst truncation (rel fro of dropped sv)", worst)
