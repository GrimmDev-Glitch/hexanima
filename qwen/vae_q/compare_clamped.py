"""Compare raw decoder output to the original postprocessed Qwen VAE output."""
import sys
import numpy as np

a = np.clip(np.fromfile(sys.argv[1], dtype=np.float32), -1.0, 1.0)
b = np.clip(np.fromfile(sys.argv[2], dtype=np.float32), -1.0, 1.0)
d = a - b
rmse = np.sqrt(np.mean(d * d))
print("elements", a.size)
print("max abs", float(np.abs(d).max()))
print("rmse", float(rmse))
print("relative rmse", float(rmse / np.sqrt(np.mean(b * b))))
