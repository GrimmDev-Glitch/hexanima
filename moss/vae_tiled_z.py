"""Tiled FLUX.2 VAE decode on the phone NPU (256 px tiles, qnn-net-run), compare with the full-image torch decode. python vae_tiled.py x0.npy out.png"""
import sys, subprocess, numpy as np, torch
from PIL import Image
from diffusers import AutoencoderKLFlux2
zraw = np.fromfile(sys.argv[1], dtype=np.float32); Hp, Wp = int(sys.argv[3]), int(sys.argv[4])
z = torch.from_numpy(zraw.reshape(32, Hp // 8, Wp // 8)); vae = AutoencoderKLFlux2.from_pretrained("vae", torch_dtype=torch.float32).eval()
T, S, OV = 32, 24, 8
def starts(n): s = list(range(0, max(n - T, 0) + 1, S)); s = s + [n - T] if s[-1] != n - T else s; return s
ys, xs = starts(z.shape[1]), starts(z.shape[2])
PH = "/data/local/tmp/moss/qnn"
def sh(c): return subprocess.run(["adb", "shell", c], capture_output=True, text=True, check=True).stdout
acc = np.zeros((3, z.shape[1] * 8, z.shape[2] * 8), np.float32); wsum = np.zeros_like(acc[:1])
def ramp(n, lo, hi):
    w = np.ones(n, np.float32); r = OV * 8
    if lo: w[:r] = np.linspace(0, 1, r + 2)[1:-1]
    if hi: w[-r:] = np.linspace(1, 0, r + 2)[1:-1]
    return w
import time; t0 = time.time()
for y in ys:
    for x in xs:
        tile = z[:, y:y + T, x:x + T].contiguous().numpy().astype(np.float32); tile.tofile("tile_in.raw")
        subprocess.run(["adb", "push", "tile_in.raw", f"{PH}/tile.raw"], capture_output=True, check=True)
        sh(f"cd {PH} && echo {PH}/tile.raw > l1.txt && export LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. && ./qnn-net-run --backend libQnnHtp.so --retrieve_context vae_t32.bin --input_list l1.txt --output_dir ot --log_level error >/dev/null 2>&1")
        subprocess.run(["adb", "pull", f"{PH}/ot/Result_0/img.raw", "tile_out.raw"], capture_output=True, check=True)
        o = np.fromfile("tile_out.raw", dtype=np.float32).reshape(3, 256, 256)
        wy = ramp(256, y > 0, y + T < z.shape[1]); wx = ramp(256, x > 0, x + T < z.shape[2]); wt = (wy[:, None] * wx[None, :])[None]
        acc[:, y * 8:y * 8 + 256, x * 8:x * 8 + 256] += o * wt; wsum[:, y * 8:y * 8 + 256, x * 8:x * 8 + 256] += wt
img = (acc / np.maximum(wsum, 1e-6)).clip(-1, 1)
Image.fromarray(((img.transpose(1, 2, 0) + 1) * 127.5).round().astype(np.uint8)).save(sys.argv[2])
print("tiles", len(ys) * len(xs), "tiled decode wall", round(time.time() - t0, 1), "s (adb overhead included)")
with torch.no_grad(): ref = vae.decode(z[None], return_dict=False)[0].clamp(-1, 1)[0].numpy()
print("tiled vs full decode: PSNR %.1f dB, max abs diff %.4f" % (10 * np.log10(4 / ((img - ref) ** 2).mean()), np.abs(img - ref).max()))
