"""Host-side tensors of the Qwen DiT (LoRA folded dense; delta < 2% so fp16 is safe) -> dist/glue.bin (same MGL1 format as moss/export_glue.py).  usage: python export_qglue.py"""
import struct, json, numpy as np, torch
import qref
T_ = qref.tensors()
def W(k):
    w = qref.deq(T_[k]); dw = qref.lora_delta(k); return w if dw is None else w + dw
f32 = lambda t: t.float().contiguous().numpy().astype(np.float32); f16 = lambda t: t.float().contiguous().numpy().astype(np.float16)
T = [("img_in.weight", 0, f32(W("img_in.weight"))), ("proj_out.weight", 0, f32(W("proj_out.weight"))),
     ("time.linear_1.weight", 0, f32(W("time_text_embed.timestep_embedder.linear_1.weight"))), ("time.linear_2.weight", 1, f16(W("time_text_embed.timestep_embedder.linear_2.weight"))),
     ("modulation.weight", 1, f16(W("modulation.1.weight"))), ("norm_out.weight", 1, f16(W("norm_out.linear.weight"))),
     ("txt_norm.weight", 0, f32(qref.deq(T_["txt_in.text_norm.weight"]) + 1)), ("txt_in.weight", 1, f16(W("txt_in.in_layer.weight"))), ("txt_out.weight", 1, f16(W("txt_in.out_layer.weight")))]
c = json.load(open("ref/Qwen-Image-2.1-Turbo/vae/config.json")); T.append(("vae.mean", 0, np.array(c["latents_mean"], np.float32))); T.append(("vae.std", 0, np.array(c["latents_std"], np.float32)))
hdr = 8 + len(T) * (40 + 4 + 4 + 12 + 8 + 8); off = (hdr + 127) // 128 * 128; ent = b""; data = []
for name, dt, a in T:
    d = list(a.shape) + [1] * (3 - a.ndim)
    ent += struct.pack("<40sii3iqq", name.encode(), dt, a.ndim, *d, off, a.nbytes); data.append((off, a.tobytes())); off = (off + a.nbytes + 127) // 128 * 128
with open("dist/glue.bin", "wb") as f:
    f.write(b"MGL1" + struct.pack("<i", len(T)) + ent)
    for o, b in data: f.seek(o); f.write(b)
print("glue.bin", off, "bytes,", len(T), "tensors")
