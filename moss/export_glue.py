"""CPU-side tensors of the DiT (embedder, time MLP, modulation, head, sampler Q) -> glue.bin.
format: "MGL1", int32 n; n x { char name[40]; int32 dtype (0 f32, 1 f16); int32 nd; int32 dims[3]; int64 offset(from file start), int64 bytes }; data 128-byte aligned."""
import struct, sys, numpy as np, torch
from moss_ref import W
from safetensors import safe_open
g = W("models/MOSS-Image-SFT-UD-Q5_K_XL.gguf"); tp = safe_open("models/time_embedding.safetensors", "pt"); ds = safe_open("models/dataspace.safetensors", "pt")
T = []
f32 = lambda t: t.float().contiguous().numpy().astype(np.float32); f16 = lambda t: t.float().contiguous().numpy().astype(np.float16)
T.append(("video_embedder.weight", 0, f32(g("video_embedder.weight")))); T.append(("video_embedder.bias", 0, f32(g("video_embedder.bias"))))
for n in ("time_embedding.0.weight", "time_embedding.0.bias", "time_embedding.2.weight", "time_embedding.2.bias"): T.append((n, 0, f32(tp.get_tensor(n))))
T.append(("shared_block_modulation.weight", 1, f16(g("shared_block_modulation.weight")))); T.append(("shared_block_modulation.bias", 0, f32(tp.get_tensor("shared_block_modulation.bias"))))
T.append(("video_head_modulation.weight", 1, f16(g("video_head_modulation.weight")))); T.append(("video_head_modulation.bias", 0, f32(tp.get_tensor("video_head_modulation.bias"))))
T.append(("final_linear_video.weight", 0, f32(g("final_linear_video.weight")))); T.append(("final_norm_video.weight", 0, f32(g("final_norm_video.weight"))))
T.append(("latent_mix.Q", 0, f32(ds.get_tensor("latent_mix.Q")))); T.append(("step_codes", 0, f32(ds.get_tensor("step_codes")))); T.append(("rope.bands", 0, f32(g("rope.bands"))))
vs = safe_open("vae/diffusion_pytorch_model.safetensors", "pt")
T.append(("bn.mean", 0, f32(vs.get_tensor("bn.running_mean")))); T.append(("bn.var", 0, f32(vs.get_tensor("bn.running_var"))))
hdr = 8 + len(T) * (40 + 4 + 4 + 12 + 8 + 8); off = (hdr + 127) // 128 * 128; ent = b""; data = []
for name, dt, a in T:
    d = list(a.shape) + [1] * (3 - a.ndim)
    ent += struct.pack("<40sii3iqq", name.encode(), dt, a.ndim, *d, off, a.nbytes); data.append((off, a.tobytes())); off = (off + a.nbytes + 127) // 128 * 128
with open("glue.bin", "wb") as f:
    f.write(b"MGL1" + struct.pack("<i", len(T)) + ent); 
    for o, b in data: f.seek(o); f.write(b)
print("glue.bin", off, "bytes,", len(T), "tensors")
