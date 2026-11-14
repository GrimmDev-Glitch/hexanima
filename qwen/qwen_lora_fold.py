"""Fold a Qwen-Image 2.1 LoRA straight into the phone's pack v2 blocks, without the host weights (reference for qwenlorafold.cpp).

usage: python qwen_lora_fold.py DITDIR LORA.safetensors STRENGTH OUTDIR [first last]
   -> OUTDIR/blkNN.ovl (u32 n, then n x [u64 offset][u32 len][bytes]), the same overlay format the runner reads (qwenrun/overlay.h).

Each module has a 128-rank LoRA slot: B is stored as 4 extra 32-row groups appended to the big matrix (q8 or q4a records),
A as q8 tiles [in][128]. The built-in Lightning adapter uses ranks 0..63.
  * q / k / v / out / down: the user LoRA goes into ranks 64..127 (its 32-rank groups are quantised on their own, so the
    Lightning records stay byte for byte); a rank above 64 is cut to 64 by SVD.
  * the fused gate/proj matrix shares its 128 ranks (gate 0..63, proj 64..127): Lightning is read back from the pack,
    added to the user delta and re-factored to rank 64 by SVD for gate and for proj.
STRENGTH multiplies alpha/rank (rank when alpha is absent). Text-stream, glue and other modules are skipped (reported).
"""
import json, os, struct, sys
import numpy as np

REC = {1: 1152, 2: 768}
NAMES = ("q", "k", "v", "o", "gp", "down")


def perm_head():
    # adjacent-pair -> half-split RoPE permutation inside a 128-wide head (qwen_ref.PERM)
    return np.concatenate([np.arange(0, 128, 2), np.arange(1, 128, 2)])


HP = (np.arange(4096).reshape(32, 128)[:, perm_head()]).reshape(-1)


# ---------------------------------------------------------------- safetensors (numpy, bf16/f16/f32)
def load_st(path):
    f = open(path, "rb"); n = struct.unpack("<Q", f.read(8))[0]; h = json.loads(f.read(n)); meta = h.pop("__metadata__", {}) or {}
    base = 8 + n
    def get(k):
        d = h[k]; o0, o1 = d["data_offsets"]; f.seek(base + o0); raw = f.read(o1 - o0)
        if d["dtype"] == "BF16": a = (np.frombuffer(raw, np.uint16).astype(np.uint32) << 16).view(np.float32)
        elif d["dtype"] == "F16": a = np.frombuffer(raw, np.float16).astype(np.float32)
        elif d["dtype"] == "F32": a = np.frombuffer(raw, np.float32).copy()
        else: raise ValueError(f"{k}: dtype {d['dtype']}")
        return a.reshape(d["shape"])
    return h, meta, get


def norm_key(k):
    """-> (block, module, kind) with module in to_q/to_k/to_v/to_out.0/gate_up/out and kind A/B/alpha, or None"""
    s = k
    for pre in ("diffusion_model.", "transformer.", "base_model.model.", "lora_unet_"):
        if s.startswith(pre): s = s[len(pre):]
    s = s.replace(".default", "")
    if "lora_unet" in k or s.startswith("transformer_blocks_"):   # kohya flat names
        s = s.replace("transformer_blocks_", "transformer_blocks.", 1)
        for a, b in (("_attn_to_out_0", ".attn.to_out.0"), ("_attn_to_q", ".attn.to_q"), ("_attn_to_k", ".attn.to_k"), ("_attn_to_v", ".attn.to_v"),
                     ("_img_mlp_net_0_proj", ".img_mlp.gate_up"), ("_img_mlp_gate_up", ".img_mlp.gate_up"), ("_img_mlp_net_2", ".img_mlp.out"), ("_img_mlp_out", ".img_mlp.out")):
            s = s.replace(a, b)
    if not s.startswith("transformer_blocks."): return None
    parts = s.split(".")
    try: blk = int(parts[1])
    except ValueError: return None
    rest = ".".join(parts[2:])
    for suf, kind in ((".lora_A.weight", "A"), (".lora_down.weight", "A"), (".lora_B.weight", "B"), (".lora_up.weight", "B"), (".alpha", "alpha")):
        if rest.endswith(suf):
            mod = rest[:-len(suf)]
            mod = {"attn.to_q": "to_q", "attn.to_k": "to_k", "attn.to_v": "to_v", "attn.to_out.0": "to_out.0",
                   "img_mlp.gate_up": "gate_up", "img_mlp.net.0.proj": "gate_up", "img_mlp.out": "out", "img_mlp.net.2": "out"}.get(mod)
            return (blk, mod, kind) if mod else ("skip", rest, kind)
    return None


def user_pairs(path, strength):
    h, meta, get = load_st(path)
    table, skipped = {}, set()
    for k in h:
        r = norm_key(k)
        if r is None: skipped.add(k.split(".lora")[0]); continue
        if r[0] == "skip": skipped.add(r[1]); continue
        table.setdefault((r[0], r[1]), {})[r[2]] = k
    alpha_meta = meta.get("ss_network_alpha")
    pairs = {}
    for (blk, mod), d in table.items():
        if "A" not in d or "B" not in d: raise ValueError(f"block {blk} {mod}: incomplete A/B pair")
        A, B = get(d["A"]), get(d["B"]); r = A.shape[0]
        alpha = float(get(d["alpha"]).reshape(-1)[0]) if "alpha" in d else (float(alpha_meta) if alpha_meta else float(r))
        pairs[(blk, mod)] = (A.astype(np.float32), (B * (strength * alpha / r)).astype(np.float32))
    return pairs, sorted(skipped)


# ---------------------------------------------------------------- pack records
def dec_q8(rec):   # 1152 B -> [32 k][32 n]
    s = np.frombuffer(rec[:128], np.float16).astype(np.float32)[::2]
    q = (np.frombuffer(rec[128:], np.uint8).astype(np.int16) - 128).reshape(16, 32, 2).astype(np.float32)
    return (q * s[None, :, None]).transpose(0, 2, 1).reshape(32, 32)


def enc_q8(blk):   # [32 k][32 n] float -> 1152 B (export_all_q8.q8tiles)
    a = blk.reshape(16, 2, 32).transpose(0, 2, 1)   # [r2][c][p]
    s = np.abs(a).max(axis=(0, 2)) / 127.0
    s = np.where(s < 6.2e-5, 6.2e-5, s).astype(np.float16); sf = s.astype(np.float32)
    q = np.clip(np.rint(a / sf[None, :, None]), -127, 127).astype(np.int16) + 128
    return np.repeat(s, 2).tobytes() + q.astype(np.uint8).tobytes()


def dec_q4a(rec):   # 768 B -> [32 k][32 n]
    S = np.frombuffer(rec[:128], np.float16).astype(np.float32)[::2]; M = np.frombuffer(rec[128:256], np.float16).astype(np.float32)[::2]
    nib = np.frombuffer(rec[256:], np.uint8).reshape(4, 128)
    t = np.empty((4, 2, 128), np.uint8); t[:, 0] = nib & 15; t[:, 1] = nib >> 4
    q = t.reshape(16, 32, 2).astype(np.float32)   # [r/2][c][r&1]
    v = q * S[None, :, None] + M[None, :, None]
    return v.transpose(0, 2, 1).reshape(32, 32)


def enc_q4a(blk):   # [32 k][32 n] -> 768 B (quant_b per column over its 32 k + q4a_tiles)
    g = blk.T   # [c][k]
    mn = g.min(1); mx = g.max(1); S = (mx - mn) / 15.0
    q = np.where(S[:, None] > 0, np.rint((g - mn[:, None]) / np.where(S[:, None] > 0, S[:, None], 1)), 0).clip(0, 15).astype(np.uint8)   # [c][k]
    t = q.T.reshape(16, 2, 32).transpose(0, 2, 1).reshape(1024)   # halfword order [r/2][c][r&1]
    t2 = t.reshape(4, 2, 128); nib = (t2[:, 0] | (t2[:, 1] << 4)).astype(np.uint8)
    return np.repeat(S.astype(np.float16), 2).tobytes() + np.repeat(mn.astype(np.float16), 2).tobytes() + nib.tobytes()


class Block:
    def __init__(self, path):
        self.f = open(path, "rb"); hdr = self.f.read(4096)
        v = struct.unpack("<i11i6i", hdr[:72])
        if v[0] != 0x00325751: raise ValueError(f"{path}: not a Qwen pack v2 block")
        self.off = v[1:12]; self.fmt = v[12:18]; self.writes = []
    # big matrices: q k v o gp down = index 0..5; A_qkv A_o A_gp A_down = 6..9
    def geom(self, i):
        if i < 6:
            K = 12288 if i == 5 else 4096; N = 24576 if i == 4 else 4096
            return (K + 128) // 32, N // 32, REC[self.fmt[i]], self.fmt[i]
        K = 12288 if i == 9 else 4096; N = 384 if i == 6 else 128
        return K // 32, N // 32, 1152, 1
    def rec_off(self, i, nb, kb):
        KB, NB, rs, _ = self.geom(i); return self.off[i] + (nb * KB + kb) * rs
    def read_rec(self, i, nb, kb):
        _, _, rs, fm = self.geom(i); self.f.seek(self.rec_off(i, nb, kb)); r = self.f.read(rs)
        return dec_q8(r) if fm == 1 else dec_q4a(r)
    def write_rec(self, i, nb, kb, blk):
        fm = self.geom(i)[3]; self.writes.append((self.rec_off(i, nb, kb), enc_q8(blk) if fm == 1 else enc_q4a(blk)))
    # B slot [out N][128 ranks] of a big matrix, and A [in K][128] (cols c0..c0+127 of the A matrix)
    def B_groups(self, i, groups):
        KB, NB, _, _ = self.geom(i); kb0 = KB - 4
        out = np.zeros((NB * 32, 32 * len(groups)), np.float32)
        for nb in range(NB):
            for gi, g in enumerate(groups): out[nb * 32:(nb + 1) * 32, gi * 32:(gi + 1) * 32] = self.read_rec(i, nb, kb0 + g).T
        return out
    def put_B(self, i, groups, B, nbs):   # B [len(nbs)*32][32*len(groups)] for the given column blocks
        KB = self.geom(i)[0]; kb0 = KB - 4
        for j, nb in enumerate(nbs):
            for gi, g in enumerate(groups): self.write_rec(i, nb, kb0 + g, B[j * 32:(j + 1) * 32, gi * 32:(gi + 1) * 32].T.copy())
    def A_cols(self, i, nbs):
        KB = self.geom(i)[0]; out = np.zeros((KB * 32, 32 * len(nbs)), np.float32)
        for j, nb in enumerate(nbs):
            for kb in range(KB): out[kb * 32:(kb + 1) * 32, j * 32:(j + 1) * 32] = self.read_rec(i, nb, kb)
        return out
    def put_A(self, i, nbs, A):   # A [K][32*len(nbs)]
        KB = self.geom(i)[0]
        for j, nb in enumerate(nbs):
            for kb in range(KB): self.write_rec(i, nb, kb, A[kb * 32:(kb + 1) * 32, j * 32:(j + 1) * 32].copy())
    def overlay(self):
        w = sorted(self.writes); merged = []
        for o, b in w:
            if merged and merged[-1][0] + len(merged[-1][1]) == o: merged[-1] = (merged[-1][0], merged[-1][1] + b)
            else: merged.append((o, b))
        out = struct.pack("<I", len(merged))
        for o, b in merged: out += struct.pack("<QI", o, len(b)) + b
        return out


def rank_cut(A, B, r):   # best rank r of B @ A via QR + small SVD -> (A' [r,in], B' [out,r])
    Qb, Rb = np.linalg.qr(B); Qa, Ra = np.linalg.qr(A.T)
    U, S, Vh = np.linalg.svd(Rb @ Ra.T)
    s = np.sqrt(S[:r]); kept = float((S[:r] ** 2).sum() / max((S ** 2).sum(), 1e-30))
    return (s[:, None] * Vh[:r]) @ Qa.T, (Qb @ U[:, :r]) * s, kept


def fold_block(blkpath, pairs, b):
    P = Block(blkpath); notes = []
    def slot(i, mod, acol_nb, perm=False, K=4096):
        if (b, mod) not in pairs: return
        A, B = pairs[(b, mod)]
        if A.shape[1] != K or B.shape[0] != 4096: raise ValueError(f"block {b} {mod}: shape {A.shape}/{B.shape}")
        if A.shape[0] > 64: A, B, kept = rank_cut(A, B, 64); notes.append(f"{mod} rank cut to 64 ({kept:.4f})")
        r = A.shape[0]; Ap = np.zeros((128 if False else 64, K), np.float32); Ap[:r] = A
        Bp = np.zeros((4096, 64), np.float32); Bp[:, :r] = B
        if perm: Bp = Bp[HP]
        P.put_B(i, (2, 3), Bp, range(128))
        P.put_A(6 if i < 3 else (7 if i == 3 else 9), acol_nb, Ap.T.copy())
    slot(0, "to_q", (2, 3), True); slot(1, "to_k", (6, 7), True); slot(2, "to_v", (10, 11)); slot(3, "to_out.0", (2, 3))
    if (b, "out") in pairs:
        A, B = pairs[(b, "out")]
        if A.shape[0] > 64: A, B, kept = rank_cut(A, B, 64); notes.append(f"down rank cut to 64 ({kept:.4f})")
        r = A.shape[0]; Ap = np.zeros((64, 12288), np.float32); Ap[:r] = A; Bp = np.zeros((4096, 64), np.float32); Bp[:, :r] = B
        P.put_B(5, (2, 3), Bp, range(128)); P.put_A(9, (2, 3), Ap.T.copy())
    if (b, "gate_up") in pairs:
        A, B = pairs[(b, "gate_up")]
        AL = P.A_cols(8, (0, 1, 2, 3)).T   # [128][4096]: gate ranks 0..63, proj 64..127
        gate_nbs = list(range(0, 768, 2)); proj_nbs = list(range(1, 768, 2))
        def lightning_B(nbs, groups):
            KB = P.geom(4)[0]; out = np.zeros((len(nbs) * 32, 64), np.float32)
            for j, nb in enumerate(nbs):
                for gi, g in enumerate(groups): out[j * 32:(j + 1) * 32, gi * 32:(gi + 1) * 32] = P.read_rec(4, nb, KB - 4 + g).T
            return out
        newA = np.zeros((128, 4096), np.float32)
        for half, nbs, groups, rows in (("gate", gate_nbs, (0, 1), slice(0, 12288)), ("proj", proj_nbs, (2, 3), slice(12288, 24576))):
            BL = lightning_B(nbs, groups); AL_h = AL[0:64] if half == "gate" else AL[64:128]
            Ac = np.concatenate((AL_h, A), 0); Bc = np.concatenate((BL, B[rows]), 1)
            A2, B2, kept = rank_cut(Ac, Bc, 64); notes.append(f"{half} kept {kept:.4f}")
            if half == "gate": newA[0:64] = A2
            else: newA[64:128] = A2
            P.put_B(4, groups, B2, nbs)
        P.put_A(8, (0, 1, 2, 3), newA.T.copy())
    return P.overlay(), notes


def main():
    dit, lora, strength, out = sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4]
    first = int(sys.argv[5]) if len(sys.argv) > 5 else 0; last = int(sys.argv[6]) if len(sys.argv) > 6 else 31
    os.makedirs(out, exist_ok=True)
    pairs, skipped = user_pairs(lora, strength)
    if skipped: print("skipped (not in the V73 pack):", ", ".join(skipped[:8]), "..." if len(skipped) > 8 else "")
    blocks = sorted({b for b, _ in pairs})
    if not blocks: raise SystemExit("no supported Qwen image-transformer modules")
    for b in range(first, last + 1):
        if b not in blocks: continue
        ov, notes = fold_block(f"{dit}/blk{b:02d}.bin", pairs, b)
        open(f"{out}/blk{b:02d}.ovl", "wb").write(ov); print(b, len(ov), "; ".join(notes), flush=True)


if __name__ == "__main__":
    main()
