"""rapid-moss 4 steps with the DiT blocks on the phone NPU (driven over adb), the rest on the laptop CPU.
python npu_sample.py EMB H W SEED OUT.png [NBLK=42]"""
import sys, subprocess, time, numpy as np, torch
from moss_ref import *
from safetensors import safe_open
embf, Hpx, Wpx, seed, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
NBLK = int(sys.argv[6]) if len(sys.argv) > 6 else 42
PH = "/data/local/tmp/moss"
def sh(c): return subprocess.run(["adb", "shell", c], capture_output=True, text=True, check=True).stdout
def push(a, b): subprocess.run(["adb", "push", a, b], capture_output=True, check=True)
def pull(a, b): subprocess.run(["adb", "pull", a, b], capture_output=True, check=True)
g = W("models/MOSS-Image-SFT-UD-Q5_K_XL.gguf"); tp = safe_open("models/time_embedding.safetensors", "pt")
ds = safe_open("models/dataspace.safetensors", "pt"); Q = ds.get_tensor("latent_mix.Q"); codes = ds.get_tensor("step_codes")
txt = torch.from_numpy(np.fromfile(embf, dtype=np.float32)).view(-1, 4096); T = txt.shape[0]
H, Wd = Hpx // 16, Wpx // 16; N = H * Wd + T; ntok = (N + 31) // 32 * 32; ns = ntok // 32
sin, cos = rope_tables(H, Wd, T, g("rope.bands"))
tx = text_refine(txt, sin[H * Wd:], cos[H * Wd:], g)           # once per prompt
def tab(t): t = torch.zeros(ntok, 64).index_copy_(0, torch.arange(t.shape[0]), t[:, :64]); return np.ascontiguousarray(t.numpy().reshape(ns, 32, 2, 32).transpose(0, 2, 1, 3)).astype(np.float32)
sh(f"mkdir -p {PH}/run"); tab(cos).tofile("run_cos.bin"); tab(sin).tofile("run_sin.bin"); push("run_cos.bin", f"{PH}/run/cos.bin"); push("run_sin.bin", f"{PH}/run/sin.bin")
def x32(t): r = torch.zeros(ntok, 3072); r[:t.shape[0]] = t; return np.ascontiguousarray(r.numpy().reshape(ns, 32, 96, 32).transpose(0, 2, 1, 3)).astype(np.float32)
def npu_forward(lat, code):
    x = torch.cat((lin(lat.flatten(1).T, g("video_embedder.weight"), g("video_embedder.bias")), tx), 0)
    mod, head = time_vecs(code, g, tp)
    x32(x).tofile("run_x32.bin"); mod.numpy().astype(np.float32).tofile("run_mod.bin")
    push("run_x32.bin", f"{PH}/run/x32.bin"); push("run_mod.bin", f"{PH}/run/mod.bin")
    t0 = time.time()
    o = sh(f"cd {PH} && LD_LIBRARY_PATH=/vendor/lib64 ADSP_LIBRARY_PATH={PH} ./blk run {ntok} {N} 0 3 1 {PH}/packs/blk%02d.bin {NBLK} 2>&1 | tail -3")
    print("   phone:", o.strip().replace("\n", " | "), f"({time.time()-t0:.1f}s wall)", flush=True)
    pull(f"{PH}/run/out_x.bin", "run_out.bin")
    xo = np.fromfile("run_out.bin", dtype=np.float32).reshape(ns, 96, 32, 32).transpose(0, 2, 1, 3).reshape(ntok, 3072)
    xo = torch.from_numpy(xo[:H * Wd].copy())
    xo = rms(xo, g("final_norm_video.weight")); xo = xo * (1 + head[1]) + head[0]
    return lin(xo, g("final_linear_video.weight")).T.reshape(128, H, Wd)
if __name__ == "__main__":
    z = torch.randn(128, H, Wd, generator=torch.Generator("cpu").manual_seed(seed)); x0 = torch.zeros_like(z)
    for r in range(4):
        t0 = time.time(); v = npu_forward(z + torch.einsum("oc,chw->ohw", Q, x0), codes[r]); x0 = z - v
        print(f"step {r} {time.time()-t0:.1f}s |v|={float(v.abs().mean()):.3f} |x0|={float(x0.abs().mean()):.3f}", flush=True)
    np.save(out + ".x0.npy", x0.numpy())
    from diffusers import AutoencoderKLFlux2
    from PIL import Image
    vae = AutoencoderKLFlux2.from_pretrained("vae", torch_dtype=torch.float32).eval(); bn = vae.bn
    zz = x0[None] * (bn.running_var + vae.config.batch_norm_eps).sqrt().view(1, -1, 1, 1) + bn.running_mean.view(1, -1, 1, 1)
    b, c, h, w = zz.shape; zz = zz.reshape(b, c // 4, 2, 2, h, w).permute(0, 1, 4, 2, 5, 3).reshape(b, c // 4, 2 * h, 2 * w)
    with torch.no_grad(): img = vae.decode(zz, return_dict=False)[0].clamp(-1, 1)
    Image.fromarray(((img[0].permute(1, 2, 0) + 1) * 127.5).round().byte().numpy()).save(out); print("saved", out)
