"""px.f32 for the ViT: python mk_px.py IMG.png W H OUT.f32 (RGB, bicubic resize, mean/std 0.5, merge-ordered 2x16x16 patches == vit_ref.prep)"""
import sys, numpy as np
from PIL import Image
img, W, H, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
a = np.asarray(Image.open(img).convert("RGB").resize((W, H), Image.BICUBIC)).astype(np.float32) / 255.0; a = ((a - 0.5) / 0.5).transpose(2, 0, 1)
p, ms = 16, 2; gh, gw = H // p, W // p
t = np.stack([a, a]).reshape(2, 3, gh // ms, ms, p, gw // ms, ms, p).transpose(2, 5, 3, 6, 1, 0, 4, 7)
t.reshape(gh * gw, 3 * 2 * p * p).astype(np.float32).tofile(out); print("px", gh * gw)
