"""Compare a phone decoder-stage dump against its FP32 PyTorch ONNX-export reference."""
import sys
import numpy as np

if len(sys.argv) not in (3, 4):
    raise SystemExit("usage: compare_decoder_stage.py PHONE.raw REFERENCE.raw [REFERENCE_SCALE]")

def read(path):
    try_path = path if __import__("os").path.exists(path) else "out_v73/" + path
    return np.fromfile(try_path, np.float32)

a = read(sys.argv[1])
b = read(sys.argv[2])
if len(sys.argv) == 4:
    b *= float(sys.argv[3])
if a.shape != b.shape:
    raise SystemExit(f"shape mismatch: phone={a.shape}, reference={b.shape}")
d = a - b
rmse = np.sqrt(np.mean(d * d))
print("elements", a.size)
print("phone range", float(a.min()), float(a.max()))
print("reference range", float(b.min()), float(b.max()))
print("max abs", float(np.abs(d).max()))
print("rmse", float(rmse))
print("relative rmse", float(rmse / np.sqrt(np.mean(b * b))))
