import sys, numpy as np, torch
from diffusers import AutoencoderKLQwenImage21
H = int(sys.argv[2]); x = torch.from_numpy(np.fromfile(sys.argv[1] + ".x.bin", np.float32)).reshape(1, 4, 16 * H, 16 * H)
vae = AutoencoderKLQwenImage21.from_pretrained("ref/vae_dir", torch_dtype=torch.float32).eval()
with torch.no_grad(): full = vae.encode(x.unsqueeze(2)).latent_dist.mode()[:, :, 0]
mine = torch.from_numpy(np.fromfile(sys.argv[1] + ".ref.bin", np.float32)).reshape(full.shape)
print("max diff vs vae.encode", float((mine - full).abs().max()), "rms", float(full.pow(2).mean().sqrt()))
