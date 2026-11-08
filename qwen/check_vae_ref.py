# untouched vae.decode on the exporter's z -> compare with the 4D re-expression
import sys, numpy as np, torch
from diffusers import AutoencoderKLQwenImage21
z = torch.from_numpy(np.fromfile(sys.argv[1] + ".z.bin", np.float32)).reshape(1, 64, int(sys.argv[2]), int(sys.argv[2]))
vae = AutoencoderKLQwenImage21.from_pretrained("ref/vae_dir", torch_dtype=torch.float32).eval()
with torch.no_grad(): full = vae.decode(z.unsqueeze(2), return_dict=False)[0][:, :, 0]
mine = torch.from_numpy(np.fromfile(sys.argv[1] + ".ref.bin", np.float32)).reshape(full.shape)
print("max diff (mine clamped vs vae.decode)", float((mine.clamp(-1, 1) - full).abs().max()))
