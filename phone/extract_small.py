"""Packs the non-block weights (x_embedder, timestep MLPs, LLM adapter, final layer) of the DiT into a flat fp16 file for animad.
usage: python extract_small.py MODEL.safetensors small.bin     (format: u32 count; per tensor u32 namelen,name,u32 ndim,i64 dims[],fp16 data)"""
import struct, sys, json, numpy as np
src, dst = sys.argv[1], sys.argv[2]
f = open(src, "rb"); n = struct.unpack("<Q", f.read(8))[0]; hdr = json.loads(f.read(n)); base = 8 + n
keep = [k for k in hdr if k != "__metadata__" and "blocks." not in k.replace("llm_adapter.blocks.", "")]
out = open(dst, "wb"); out.write(struct.pack("<I", len(keep)))
for k in keep:
    m = hdr[k]; assert m["dtype"] == "F16", (k, m["dtype"])
    f.seek(base + m["data_offsets"][0]); data = f.read(m["data_offsets"][1] - m["data_offsets"][0])
    name = k[4:] if k.startswith("net.") else k
    out.write(struct.pack("<I", len(name)) + name.encode() + struct.pack("<I", len(m["shape"])) + struct.pack(f"<{len(m['shape'])}q", *m["shape"]) + data)
    print(name, m["shape"])
