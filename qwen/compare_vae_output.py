#!/usr/bin/env python3
"""Compare the phone's fp32 RGB VAE output against the Qwen-Image-2.1 CPU decoder.

Usage: python compare_vae_output.py VAE_DIR LATENT.raw PHONE_RGB.raw WIDTH HEIGHT
LATENT is fp32 [64,H/16,W/16], already de-normalized as expected by vae.decode.
PHONE_RGB is fp32 [3,H,W] in [-1,1], as written by vaetile.
"""
import sys
import numpy as np
import torch
from diffusers import AutoencoderKLQwenImage21

model_dir, latent_path, phone_path = sys.argv[1:4]
w, h = int(sys.argv[4]), int(sys.argv[5])
z = np.fromfile(latent_path, np.float32).reshape(1, 64, h // 16, w // 16)
phone_raw = np.fromfile(phone_path, np.float32).reshape(3, h, w)
raw_mode = len(sys.argv) > 6 and sys.argv[6] == "raw"
phone = np.clip(phone_raw, -1, 1) if raw_mode else phone_raw
vae = AutoencoderKLQwenImage21.from_pretrained(model_dir, torch_dtype=torch.float32).eval()
with torch.inference_mode():
    cpu = vae.decode(torch.from_numpy(z).unsqueeze(2), return_dict=False)[0][:, :3, 0].numpy()[0]
cpu = np.clip(cpu, -1, 1)
diff = phone - cpu
mse = float(np.mean(diff * diff))
psnr = float("inf") if mse == 0 else 10 * np.log10(4 / mse)
print(f"max_abs={np.max(np.abs(diff)):.6f} mae={np.mean(np.abs(diff)):.6f} psnr={psnr:.2f} dB")
if raw_mode:
    print(f"phone_raw=[{phone_raw.min():.6f},{phone_raw.max():.6f}]")
for c, name in enumerate(("R", "G", "B")):
    d = diff[c]
    print(f"{name}: max_abs={np.max(np.abs(d)):.6f} mae={np.mean(np.abs(d)):.6f} phone=[{phone[c].min():.3f},{phone[c].max():.3f}] cpu=[{cpu[c].min():.3f},{cpu[c].max():.3f}]")
