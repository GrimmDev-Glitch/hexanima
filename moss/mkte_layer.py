"""TE layer test: python mkte_layer.py OUTDIR L  (L: 0 = delta, 3 = full attention; input = reference hidden state before layer L)"""
import sys, os, math, numpy as np, torch
import qwen35_ref as R
from export_te import pack_te_layer
from tokenizers import Tokenizer
out = sys.argv[1]; L = int(sys.argv[2]); os.makedirs(out, exist_ok=True)
q = R.Q("models/Qwen3.5-9B-UD-Q4_K_XL.gguf")
ids = Tokenizer.from_file("models/tokenizer.json").encode("<|im_start|>user\n" + open("prompts.txt").read().splitlines()[1] + "<|im_end|>\n<|im_start|>assistant\n<think>\n", add_special_tokens=False).ids
T = len(ids); ntok = (T + 31) // 32 * 32; ns = ntok // 32
st = {}
class Stop(Exception): pass
def log(i, x):
    st[i] = x.clone()
    if i == L: raise Stop()
try: R.encode(q, ids, log=log)
except Stop: pass
x_in = R.q.rows if False else None
x0 = q.rows("token_embd.weight", torch.tensor(ids)); xin = x0 if L == 0 else st[L - 1]; xout = st[L]
pk, info = pack_te_layer(q, L); open(f"{out}/wts.bin", "wb").write(pk); print("pack", len(pk), info)
np.save(f"{out}/ref.npy", xout.numpy())
xp = torch.zeros(ntok, 4096); xp[:T] = xin
np.ascontiguousarray(xp.numpy().reshape(ns, 32, 128, 32).transpose(0, 2, 1, 3)).astype(np.float32).tofile(f"{out}/x32.bin")
mm = np.zeros(18432, np.float32); mm[8192:12288] = 1; mm.tofile(f"{out}/mod.bin")
np.zeros(ns * 8192, np.float32).tofile(f"{out}/cos.bin"); np.zeros(ns * 8192, np.float32).tofile(f"{out}/sin.bin")
fr = 1e7 ** (-torch.arange(32, dtype=torch.float32) / 32); ang = torch.arange(T, dtype=torch.float32)[:, None] * fr
cs = np.zeros((ntok, 64), np.float32); cs[:T, :32] = ang.cos().numpy(); cs[:T, 32:] = ang.sin().numpy(); cs.tofile(f"{out}/in_cs.bin")
print("T", T, "ntok", ntok, "|xin|", float(xin.abs().max()), "|xout|", float(xout.abs().max()))
