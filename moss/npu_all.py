"""All heavy stages on the phone NPU, driven over adb from the laptop: tokens -> TE (Qwen3.5, 32 layers) -> text refiner -> DiT x4 -> VAE (full-frame 768x512 context).
python npu_all.py "prompt" SEED out.png      (size fixed to 768x512 for now)"""
import sys, subprocess, time, numpy as np, torch
from tokenizers import Tokenizer
import qwen35_ref as R
from moss_ref import W, rope_tables, lin, rms, time_vecs
from safetensors import safe_open
prompt, seed, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
PH = "/data/local/tmp/moss"; ENV = "export LD_LIBRARY_PATH=/vendor/lib64 ADSP_LIBRARY_PATH=/data/local/tmp/moss"
def sh(c, show=False):
    r = subprocess.run(["adb", "shell", c], capture_output=True, text=True, check=True).stdout
    if show: print("   ", r.strip().replace("\n", " | "))
    return r
def push(a, b): subprocess.run(["adb", "push", a, b], capture_output=True, check=True)
def pull(a, b): subprocess.run(["adb", "pull", a, b], capture_output=True, check=True)
t00 = time.time()
ids = Tokenizer.from_file("models/tokenizer.json").encode("<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n<think>\n", add_special_tokens=False).ids
T = len(ids); ntokT = (T + 31) // 32 * 32; nsT = ntokT // 32
# ---- 1. TE on the NPU ----
q = R.Q("models/Qwen3.5-9B-UD-Q4_K_XL.gguf")
emb = q.rows("token_embd.weight", torch.tensor(ids)); xp = torch.zeros(ntokT, 4096); xp[:T] = emb
sh(f"mkdir -p {PH}/te {PH}/tr"); np.ascontiguousarray(xp.numpy().reshape(nsT, 32, 128, 32).transpose(0, 2, 1, 3)).astype(np.float32).tofile("te_x32.bin"); push("te_x32.bin", f"{PH}/te/x32.bin")
fr = 1e7 ** (-torch.arange(32, dtype=torch.float32) / 32); ang = torch.arange(T, dtype=torch.float32)[:, None] * fr
cs = np.zeros((ntokT, 64), np.float32); cs[:T, :32] = ang.cos().numpy(); cs[:T, 32:] = ang.sin().numpy(); cs.tofile("te_cs.bin"); push("te_cs.bin", f"{PH}/te/in_cs.bin")
mm = np.zeros(18432, np.float32); mm[8192:12288] = 1; mm.tofile("te_mod.bin"); push("te_mod.bin", f"{PH}/te/mod.bin")
np.zeros(nsT * 8192, np.float32).tofile("zero.bin"); push("zero.bin", f"{PH}/te/cos.bin"); push("zero.bin", f"{PH}/te/sin.bin")
t0 = time.time(); sh(f"cd {PH} && {ENV} && TE=1 TEFINAL=1 NCB=128 WTSIZE=270000000 ./blk te {ntokT} {T} 0 3 1 {PH}/te_packs/te%02d.bin 33 2>&1 | tail -1", True); print(f"TE: {time.time()-t0:.1f}s")
# ---- 2. refiner on the NPU (reads the TE output tiles directly) ----
g = W("models/MOSS-Image-SFT-UD-Q5_K_XL.gguf")
sin_t, cos_t = (lambda s, c: (s[1:], c[1:]))(*rope_tables(1, 1, T, g("rope.bands")))
def tab(t): t = torch.zeros(ntokT, 64).index_copy_(0, torch.arange(T), t[:, :64]); return np.ascontiguousarray(t.numpy().reshape(nsT, 32, 2, 32).transpose(0, 2, 1, 3)).astype(np.float32)
tab(cos_t).tofile("rf_cos.bin"); tab(sin_t).tofile("rf_sin.bin"); push("rf_cos.bin", f"{PH}/tr/cos.bin"); push("rf_sin.bin", f"{PH}/tr/sin.bin")
def consts(n): t = np.zeros((n, 1024), np.float16); [t.__setitem__((slice(0, None, 4), (r // 2) * 64 + (r & 1)), 1.0) for r in range(32)]; return t
for nm, n in (("lq", nsT * 12), ("lo", nsT * 4), ("lu", nsT * 4), ("ld", nsT * 4)): consts(n).tofile(f"rf_{nm}.bin"); push(f"rf_{nm}.bin", f"{PH}/tr/in_{nm}.bin")
m = np.zeros((6, 3072), np.float32); m[2] = 1; m[5] = 1; m.tofile("rf_mod.bin"); push("rf_mod.bin", f"{PH}/tr/mod.bin")
np.zeros((nsT, 96, 32, 32), np.float32).tofile("rf_x32.bin"); push("rf_x32.bin", f"{PH}/tr/x32.bin")
t0 = time.time()
sh(f"cd {PH} && {ENV} && cp te/out_h.bin tr/in_h.bin && cp tr/proj.bin tr/wts.bin && MODE=2 NCB=96 WTSIZE=135397376 ./blk tr {ntokT} {T} 0 3 1 2>&1 | tail -1 && cp tr/out_x.bin tr/x32.bin && MODE=1 ./blk tr {ntokT} {T} 0 3 1 {PH}/tr/ref%d.bin 2 2>&1 | tail -1", True)
pull(f"{PH}/tr/out_x.bin", "rf_out.bin")
tx = torch.from_numpy(np.fromfile("rf_out.bin", dtype=np.float32)[:ntokT * 3072].reshape(nsT, 96, 32, 32).transpose(0, 2, 1, 3).reshape(ntokT, 3072)[:T].copy())
print(f"refiner: {time.time()-t0:.1f}s")
# ---- 3. DiT, 4 steps (blocks on the NPU) ----
Hpx, Wpx = 768, 512; H, Wd = Hpx // 16, Wpx // 16; N = H * Wd + T; ntok = (N + 31) // 32 * 32; ns = ntok // 32
tp = safe_open("models/time_embedding.safetensors", "pt"); ds = safe_open("models/dataspace.safetensors", "pt"); Q = ds.get_tensor("latent_mix.Q"); codes = ds.get_tensor("step_codes")
sin, cos = rope_tables(H, Wd, T, g("rope.bands"))
def tab2(t): t = torch.zeros(ntok, 64).index_copy_(0, torch.arange(t.shape[0]), t[:, :64]); return np.ascontiguousarray(t.numpy().reshape(ns, 32, 2, 32).transpose(0, 2, 1, 3)).astype(np.float32)
sh(f"mkdir -p {PH}/run"); tab2(cos).tofile("run_cos.bin"); tab2(sin).tofile("run_sin.bin"); push("run_cos.bin", f"{PH}/run/cos.bin"); push("run_sin.bin", f"{PH}/run/sin.bin")
def x32(t): r = torch.zeros(ntok, 3072); r[:t.shape[0]] = t; return np.ascontiguousarray(r.numpy().reshape(ns, 32, 96, 32).transpose(0, 2, 1, 3)).astype(np.float32)
def dit(lat, code):
    x = torch.cat((lin(lat.flatten(1).T, g("video_embedder.weight"), g("video_embedder.bias")), tx), 0)
    mod, head = time_vecs(code, g, tp)
    x32(x).tofile("run_x32.bin"); mod.numpy().astype(np.float32).tofile("run_mod.bin"); push("run_x32.bin", f"{PH}/run/x32.bin"); push("run_mod.bin", f"{PH}/run/mod.bin")
    sh(f"cd {PH} && {ENV} && ./blk run {ntok} {N} 0 3 1 {PH}/packs/blk%02d.bin 42 2>&1 | tail -1", True)
    pull(f"{PH}/run/out_x.bin", "run_out.bin")
    xo = torch.from_numpy(np.fromfile("run_out.bin", dtype=np.float32).reshape(ns, 96, 32, 32).transpose(0, 2, 1, 3).reshape(ntok, 3072)[:H * Wd].copy())
    xo = rms(xo, g("final_norm_video.weight")); xo = xo * (1 + head[1]) + head[0]
    return lin(xo, g("final_linear_video.weight")).T.reshape(128, H, Wd)
z = torch.randn(128, H, Wd, generator=torch.Generator("cpu").manual_seed(seed)); x0 = torch.zeros_like(z)
for r in range(4):
    t0 = time.time(); v = dit(z + torch.einsum("oc,chw->ohw", Q, x0), codes[r]); x0 = z - v; print(f"step {r}: {time.time()-t0:.1f}s")
# ---- 4. VAE full-frame on the NPU ----
from diffusers import AutoencoderKLFlux2
from PIL import Image
vae = AutoencoderKLFlux2.from_pretrained("vae", torch_dtype=torch.float32).eval(); bn = vae.bn
zz = x0[None] * (bn.running_var + vae.config.batch_norm_eps).sqrt().view(1, -1, 1, 1) + bn.running_mean.view(1, -1, 1, 1)
b_, c_, h_, w_ = zz.shape; zz = zz.reshape(b_, c_ // 4, 2, 2, h_, w_).permute(0, 1, 4, 2, 5, 3).reshape(1, c_ // 4, 2 * h_, 2 * w_)
zz[0].numpy().astype(np.float32).tofile("vz.raw"); push("vz.raw", f"{PH}/qnn/vz.raw")
t0 = time.time(); sh(f"cd {PH}/qnn && echo {PH}/qnn/vz.raw > lv.txt && export LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. && ./qnn-net-run --backend libQnnHtp.so --retrieve_context vae_96x64.bin --input_list lv.txt --output_dir ov --log_level error >/dev/null 2>&1"); print(f"VAE: {time.time()-t0:.1f}s (incl. process start)")
pull(f"{PH}/qnn/ov/Result_0/img.raw", "vimg.raw"); img = np.fromfile("vimg.raw", dtype=np.float32).reshape(3, 768, 512).clip(-1, 1)
Image.fromarray(((img.transpose(1, 2, 0) + 1) * 127.5).round().astype(np.uint8)).save(out); print("saved", out, f"total {time.time()-t00:.0f}s wall (adb-driven)")
