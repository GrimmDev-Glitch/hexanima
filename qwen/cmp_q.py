import sys, numpy as np
d = sys.argv[1]; ref = np.load(sys.argv[2]); ntok = int(ref["ntok"]); nv = int(ref["nvalid"]); ns = ntok // 32
def h16(name, ncb):
    a = np.fromfile(f"{d}/out_{name}.bin", dtype=np.float16).astype(np.float32)[: ns * ncb * 1024].reshape(ns, ncb, 16, 32, 2)
    return a.transpose(0, 2, 4, 1, 3).reshape(ns * 32, ncb * 32)
def x32(name):
    a = np.fromfile(f"{d}/out_{name}.bin", dtype=np.float32)[: ntok * 4096].reshape(ns, 128, 32, 32)
    return a.transpose(0, 2, 1, 3).reshape(ntok, 4096)
def rel(a, b): return float(np.linalg.norm(a[:nv] - b[:nv]) / (np.linalg.norm(b[:nv]) + 1e-12))
for name, ncb, key in (("h", 128, "h"), ("q", 128, "q"), ("k", 128, "k"), ("v", 128, "v"), ("o", 128, "o"), ("mid", 384, "mid")):
    print(f"{name:4s} rel err {rel(h16(name, ncb), ref[key]):.4f}   max|ref| {np.abs(ref[key]).max():.2f}")
print("x    rel err %.4f   max|ref| %.1f" % (rel(x32("x"), ref["x"]), np.abs(ref["x"]).max()))
