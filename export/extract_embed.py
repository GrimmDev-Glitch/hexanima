"""Qwen3 embedding table (bf16 in the safetensors) -> fp16 [151936,1024] file for animad's token gather. usage: python extract_embed.py OUT.f16"""
import sys, json, struct, numpy as np
from qwen3_torch import W
f = open(W, "rb"); n = struct.unpack("<Q", f.read(8))[0]; h = json.loads(f.read(n)); v = h["model.embed_tokens.weight"]
a, b = v["data_offsets"]; f.seek(8 + n + a)
u = np.frombuffer(f.read(b - a), np.uint16).astype(np.uint32) << 16
np.save if 0 else None
u.view(np.float32).astype(np.float16).tofile(sys.argv[1])
print(v["shape"], "ok")
