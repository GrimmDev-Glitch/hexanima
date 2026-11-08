"""4-step rapid-moss reference: python sample_ref.py emb/q4xl_0.f32 256 256 SEED out.png"""
import sys, time, numpy as np, torch
from moss_ref import *
from safetensors import safe_open
embf, Hpx, Wpx, seed, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
torch.set_num_threads(8)
g = W("models/MOSS-Image-SFT-UD-Q5_K_XL.gguf")
lora = Lora("models/lora.safetensors"); tp = safe_open("models/time_embedding.safetensors", "pt")
ds = safe_open("models/dataspace.safetensors", "pt"); Q = ds.get_tensor("latent_mix.Q"); codes = ds.get_tensor("step_codes")
txt = torch.from_numpy(np.fromfile(embf, dtype=np.float32)).view(-1, 4096)
H, Wd = Hpx // 16, Wpx // 16
gen = torch.Generator("cpu").manual_seed(seed)
z = torch.randn(128, H, Wd, generator=gen)
x0 = torch.zeros_like(z); dump = {}
for r in range(4):
    t0 = time.time()
    x = z + torch.einsum("oc,chw->ohw", Q, x0)
    log = (lambda i, h: dump.__setitem__(f"s{r}_b{i}", h.numpy())) if r == 0 and False else None
    v = dit_forward(x, codes[r], txt, g, lora, tp)
    x0 = z - v
    dump[f"xin{r}"] = x.numpy(); dump[f"vel{r}"] = v.numpy()
    print(f"step {r} {time.time()-t0:.0f}s |v|={float(v.abs().mean()):.3f} |x0|={float(x0.abs().mean()):.3f}", flush=True)
np.savez(out + ".npz", z=z.numpy(), x0=x0.numpy(), **dump)
from diffusers import AutoencoderKLFlux2
from PIL import Image
vae = AutoencoderKLFlux2.from_pretrained("vae", torch_dtype=torch.float32).eval()
bn = vae.bn; eps = vae.config.batch_norm_eps
zz = x0[None] * (bn.running_var + eps).sqrt().view(1, -1, 1, 1) + bn.running_mean.view(1, -1, 1, 1)
b, c, h, w = zz.shape
zz = zz.reshape(b, c // 4, 2, 2, h, w).permute(0, 1, 4, 2, 5, 3).reshape(b, c // 4, 2 * h, 2 * w)
with torch.no_grad(): img = vae.decode(zz, return_dict=False)[0].clamp(-1, 1)
Image.fromarray(((img[0].permute(1, 2, 0) + 1) * 127.5).round().byte().numpy()).save(out)
print("saved", out)
