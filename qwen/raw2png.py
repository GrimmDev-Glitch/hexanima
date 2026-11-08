import sys, numpy as np
from PIL import Image
raw, W, H, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
a = np.fromfile(raw, np.float32).reshape(3, H, W); Image.fromarray(((a.transpose(1, 2, 0).clip(-1, 1) + 1) * 127.5).round().astype(np.uint8)).save(out)
