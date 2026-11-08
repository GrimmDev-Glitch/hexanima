"""TE FFN (layer L) test: python mkte_ffn.py OUTDIR [L]"""
import sys, os, numpy as np, torch, gguf
from qwen35_ref import Q, rms
from export_te import q4k_parts, q4a_tiles, unpack_q4a
from export_all_q8 import wt16, q8tiles
from tokenizers import Tokenizer
out = sys.argv[1]; L = int(sys.argv[2]) if len(sys.argv) > 2 else 0; os.makedirs(out, exist_ok=True)
q = Q("models/Qwen3.5-9B-UD-Q4_K_XL.gguf"); p = f"blk.{L}."
ids = Tokenizer.from_file("models/tokenizer.json").encode("<|im_start|>user\n" + open("prompts.txt").read().splitlines()[1] + "<|im_end|>\n<|im_start|>assistant\n<think>\n", add_special_tokens=False).ids
T = len(ids); ntok = (T + 31) // 32 * 32; ns = ntok // 32
x = q.rows("token_embd.weight", torch.tensor(ids)); x = x * (30.0 / x.abs().max())   # realistic-size residual
parts = {}
for n in ("ffn_gate", "ffn_up"):
    t = q.t[p + n + ".weight"]; assert t.tensor_type == gguf.GGMLQuantizationType.Q4_K, t.tensor_type
    parts[n] = q4k_parts(t.data, 12288, 4096)
def inter(a, b):   # interleave 32-row blocks (gate_j, up_j)
    return np.stack((a.reshape(-1, 32, *a.shape[1:]), b.reshape(-1, 32, *b.shape[1:])), 1).reshape(-1, *a.shape[1:])
qq = inter(parts["ffn_gate"][0], parts["ffn_up"][0]); S = inter(parts["ffn_gate"][1], parts["ffn_up"][1]); M = inter(parts["ffn_gate"][2], parts["ffn_up"][2])
gu = q4a_tiles(qq, S, M); assert len(gu) == 768 * 128 * 768, len(gu)
wd = q.t[p + "ffn_down.weight"]; wdd = q(p + "ffn_down.weight")                      # [4096, 12288]
dn = q8tiles(wt16(wdd.T.contiguous().numpy())); assert len(dn) == 128 * 384 * 1152
nw = q(p + "post_attention_norm.weight").numpy().astype(np.float32)
open(f"{out}/wts.bin", "wb").write(gu + dn + nw.tobytes() + bytes(16384 - nw.nbytes))
print("wts size", len(gu) + len(dn) + 16384, "type down", wd.tensor_type.name)
# reference with exact GGUF weights
g_, u_ = q(p + "ffn_gate.weight"), q(p + "ffn_up.weight")
h = rms(x, torch.from_numpy(nw)); ref = x + (torch.nn.functional.silu(h @ g_.T) * (h @ u_.T)) @ wdd.T
np.save(f"{out}/ref.npy", ref.numpy())
# check the q4a pack is lossless vs the dequantised gate weights
gate_back = unpack_q4a(gu[: 128 * 768], 1, 128)   # first n-block (gate rows 0..31) -> [K, 32]
print("q4a roundtrip rel err (gate rows 0-31):", float(np.linalg.norm(gate_back.T - g_[:32].numpy()) / np.linalg.norm(g_[:32].numpy())))
xp = torch.zeros(ntok, 4096); xp[:T] = x
np.ascontiguousarray(xp.numpy().reshape(ns, 32, 128, 32).transpose(0, 2, 1, 3)).astype(np.float32).tofile(f"{out}/x32.bin")
m = np.zeros((6, 3072), np.float32); m[2] = 1; m.tofile(f"{out}/mod_small.bin")
mm = np.zeros(18432, np.float32); mm[8192:12288] = 1; mm.tofile(f"{out}/mod.bin")
np.zeros(ns * 8192 * 4 // 4, np.float32).tofile(f"{out}/cos.bin"); np.zeros(ns * 8192 // 4, np.float32).tofile(f"{out}/sin.bin")
print("T", T, "ntok", ntok)
