#!/usr/bin/env python3
"""Write a deterministic, de-normalized Qwen VAE latent for context tests."""
import json
import sys
import numpy as np

config, output = sys.argv[1:3]
h, w = (int(sys.argv[3]), int(sys.argv[4])) if len(sys.argv) > 4 else (32, 32)
seed = int(sys.argv[5]) if len(sys.argv) > 5 else 42
c = json.load(open(config, encoding="utf-8"))
mean = np.asarray(c["latents_mean"], dtype=np.float32).reshape(1, 64, 1, 1)
std = np.asarray(c["latents_std"], dtype=np.float32).reshape(1, 64, 1, 1)
z = np.random.default_rng(seed).standard_normal((1, 64, h, w), dtype=np.float32) * std + mean
z.astype("<f4").tofile(output)
print(f"saved fp32 latent {z.shape} to {output}")
