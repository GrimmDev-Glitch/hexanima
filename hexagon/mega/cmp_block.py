import numpy as np, sys
d = sys.argv[1]; NT, D = 1536, 2048
o = np.fromfile(f"{d}/out32.bin", np.float32).reshape(NT // 32, D // 32, 32, 32).transpose(0, 2, 1, 3).reshape(NT, D).T   # [D,NT]
ref = np.load(f"{d}/ref.npy"); x = np.fromfile(f"{d}/x32.bin", np.float32).reshape(NT // 32, D // 32, 32, 32).transpose(0, 2, 1, 3).reshape(NT, D).T
dl = ref - x
print("rel err on block delta:", np.linalg.norm(o - ref) / np.linalg.norm(dl), " cos:", (o - x).ravel() @ dl.ravel() / np.linalg.norm(o - x) / np.linalg.norm(dl), "nan:", np.isnan(o).sum())
