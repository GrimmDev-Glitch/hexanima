import sys, numpy as np
from export_te import pack_te_layer, Q
q = Q("models/Qwen3.5-9B-UD-Q4_K_XL.gguf")
for i in range(int(sys.argv[1]), int(sys.argv[2]) + 1):
    b, info = pack_te_layer(q, i); open(f"te_packs/te{i:02d}.bin", "wb").write(b); print(i, len(b), flush=True)
w = q("output_norm.weight").numpy().astype(np.float32); open("te_packs/te32.bin", "wb").write(w.tobytes() + bytes(16384 - w.nbytes)); print("final")
