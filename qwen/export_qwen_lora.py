"""DiT pack v2 with a second (user) LoRA folded next to the built-in Lightning adapter.
usage: python export_qwen_lora.py OUTDIR LORA.safetensors SCALE [first last]
Slot budget per module is 128 ranks: Lightning uses 64 -> q/k/v/o/down take up to 64 more ranks as they are; the fused gate_up has to share 128 ranks between
gate (64) and proj (64), so gate and proj deltas (Lightning + LoRA) are each re-factored by an exact SVD and cut to rank 64 (energy kept is printed)."""
import sys, numpy as np, torch
from safetensors import safe_open
import export_qwen as E
import export_qwen2 as X
out, path, scale = sys.argv[1], sys.argv[2], float(sys.argv[3])
first = int(sys.argv[4]) if len(sys.argv) > 4 else 0; last = int(sys.argv[5]) if len(sys.argv) > 5 else 31
F = safe_open(path, "pt"); orig = E.lora; kept = []
def user(k):   # (A [r,in], B [out,r]) of the user LoRA for param key k, or None; gate/proj are halves of the fused gate_up
    base = "diffusion_model." + k[:-len(".weight")]
    if k.endswith("img_mlp.gate_layer.weight") or k.endswith("img_mlp.proj.weight"):
        gu = base.rsplit("img_mlp.", 1)[0] + "img_mlp.gate_up"
        A = F.get_tensor(gu + ".lora_A.weight").float(); B = F.get_tensor(gu + ".lora_B.weight").float()
        return A, (B[:12288] if k.endswith("gate_layer.weight") else B[12288:]) * scale
    return F.get_tensor(base + ".lora_A.weight").float(), F.get_tensor(base + ".lora_B.weight").float() * scale
def lora(k):
    A, B = orig(k); u = user(k)
    if u is None: return A, B
    A2, B2 = u; Ac = torch.cat((A, A2), 0); Bc = torch.cat((B, B2), 1)
    if k.endswith("img_mlp.gate_layer.weight") or k.endswith("img_mlp.proj.weight"):   # keep the best rank 64 of the sum
        Qb, Rb = torch.linalg.qr(Bc); Qa, Ra = torch.linalg.qr(Ac.T)
        U, S, Vh = torch.linalg.svd(Rb @ Ra.T)
        kept.append(float((S[:64] ** 2).sum() / (S ** 2).sum()))
        s = S[:64].sqrt(); return (s[:, None] * Vh[:64]) @ Qa.T, (Qb @ U[:, :64]) * s
    assert Ac.shape[0] <= 128, Ac.shape
    return Ac, Bc
E.lora = lora
if __name__ == "__main__":
    import os; os.makedirs(out, exist_ok=True)
    for i in range(first, last + 1):
        d, fm = X.pack_block(i); open(f"{out}/blk{i:02d}.bin", "wb").write(d)
        print(i, len(d), fm, "gate/proj energy kept min %.4f" % (min(kept[-2:]) if kept else 1), flush=True)
    print("overall min energy kept", min(kept))
