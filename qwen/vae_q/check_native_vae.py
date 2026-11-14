"""Decode a saved latent with the untouched Diffusers Qwen VAE for comparison."""
import sys

import numpy as np
import torch
from diffusers import AutoencoderKLQwenImage21

z_path, output_path = sys.argv[1:3]
z_np = np.fromfile(z_path, dtype="<f4").reshape(1, 64, 32, 32)
vae = AutoencoderKLQwenImage21.from_pretrained("ref/vae_dir", torch_dtype=torch.float32).eval()
with torch.no_grad():
    result = vae.decode(torch.from_numpy(z_np.copy()).unsqueeze(2)).sample.squeeze(2)
result.cpu().numpy().tofile(output_path)
print("native range", float(result.min()), float(result.max()))
