"""Qwen3-VL vision tower (HF bf16 visual weights, models/visual.safetensors) -> NPU layer packs vit00..vit26.bin for MODE 23 + host glue (vit_glue.bin, MGL1 format).
Layout trick: head_dim 72 is zero-padded to 128 so the DiT attention kernel (4 tiles/head) is reused: dim d<36 -> slot d, d>=36 -> slot 64+(d-36) (rotate-half pairs (d, d+36) -> (d, d+64)); q carries the 1/sqrt(72) scale.
Pack (q8 tile records; biases ride in the 4 extra K tiles, row 0): q,k,v [K=1152+128][N=2048]; o [2048+128][1152]; fc1 [1152+128][4352]; fc2 [4352+128][1152]; gain f32: ln1 w,b ln2 w,b (4 x 1152) padded to 8192 floats.
usage: python export_vit.py OUTDIR [first last]"""
import os, sys, struct, numpy as np, torch
sys.path.insert(0, "../moss")
from export_all_q8 import wt16, q8tiles
from safetensors import safe_open
f = safe_open("models/visual.safetensors", "pt"); g = lambda n: f.get_tensor("model.visual." + n).float()
H, HD, DM = 16, 72, 1152
SLOT = torch.tensor([d if d < 36 else 64 + d - 36 for d in range(HD)])
def pad_heads_out(w, b, scale=1.0):   # w [H*HD, in], b [H*HD] -> [H*128, in], [H*128]
    W = torch.zeros(H, 128, w.shape[1]); B = torch.zeros(H, 128)
    W[:, SLOT] = w.view(H, HD, -1) * scale; B[:, SLOT] = b.view(H, HD) * scale
    return W.reshape(H * 128, -1), B.reshape(-1)
def mat(w_oi, bias):   # [out,in] fp32 + bias[out] -> tiles [K=in+128][N=out] with the bias in lora row 0
    w = w_oi.T.contiguous(); b = torch.zeros(128, w.shape[1]); b[0] = bias
    return q8tiles(wt16(torch.cat((w, b), 0).numpy()))
def pack_layer(i):
    p = f"blocks.{i}."; qkv = g(p + "attn.qkv.weight"); qb = g(p + "attn.qkv.bias"); out = []; sz = {}
    def add(n, b): sz[n] = len(b); out.append(b)
    for j, (n, sc) in enumerate((("q", 72 ** -0.5), ("k", 1.0), ("v", 1.0))):
        w, b = pad_heads_out(qkv[j * DM:(j + 1) * DM], qb[j * DM:(j + 1) * DM], sc); add(n, mat(w, b))
    pw = g(p + "attn.proj.weight"); Wp = torch.zeros(DM, H, 128); Wp[:, :, SLOT] = pw.view(DM, H, HD); add("o", mat(Wp.reshape(DM, H * 128), g(p + "attn.proj.bias")))
    w1 = torch.zeros(4352, DM); w1[:4304] = g(p + "mlp.linear_fc1.weight"); b1 = torch.zeros(4352); b1[:4304] = g(p + "mlp.linear_fc1.bias"); add("fc1", mat(w1, b1))
    w2 = torch.zeros(DM, 4352); w2[:, :4304] = g(p + "mlp.linear_fc2.weight"); add("fc2", mat(w2, g(p + "mlp.linear_fc2.bias")))
    gain = torch.cat([g(p + "norm1.weight"), g(p + "norm1.bias"), g(p + "norm2.weight"), g(p + "norm2.bias")]).numpy().astype(np.float32)
    add("gain", gain.tobytes() + bytes(8192 * 4 - gain.nbytes))
    return b"".join(out), sz
def glue():
    T = []; f16 = lambda t: t.contiguous().numpy().astype(np.float16); f32 = lambda t: t.contiguous().numpy().astype(np.float32)
    T.append(("patch.weight", 1, f16(g("patch_embed.proj.weight").reshape(DM, -1)))); T.append(("patch.bias", 0, f32(g("patch_embed.proj.bias")))); T.append(("pos_embed", 1, f16(g("pos_embed.weight"))))
    T.append(("merger.norm.weight", 0, f32(g("merger.norm.weight")))); T.append(("merger.norm.bias", 0, f32(g("merger.norm.bias"))))
    T.append(("merger.fc1.weight", 1, f16(g("merger.linear_fc1.weight")))); T.append(("merger.fc1.bias", 0, f32(g("merger.linear_fc1.bias")))); T.append(("merger.fc2.weight", 1, f16(g("merger.linear_fc2.weight")))); T.append(("merger.fc2.bias", 0, f32(g("merger.linear_fc2.bias"))))
    for k in range(3):
        p = f"deepstack_merger_list.{k}."
        T.append((f"ds{k}.norm.weight", 0, f32(g(p + "norm.weight")))); T.append((f"ds{k}.norm.bias", 0, f32(g(p + "norm.bias"))))
        T.append((f"ds{k}.fc1.weight", 1, f16(g(p + "linear_fc1.weight")))); T.append((f"ds{k}.fc1.bias", 0, f32(g(p + "linear_fc1.bias")))); T.append((f"ds{k}.fc2.weight", 1, f16(g(p + "linear_fc2.weight")))); T.append((f"ds{k}.fc2.bias", 0, f32(g(p + "linear_fc2.bias"))))
    hdr = 8 + len(T) * (40 + 4 + 4 + 12 + 8 + 8); off = (hdr + 127) // 128 * 128; ent = b""; data = []
    for name, dt, a in T:
        d = list(a.shape) + [1] * (3 - a.ndim); ent += struct.pack("<40sii3iqq", name.encode(), dt, a.ndim, *d, off, a.nbytes); data.append((off, a.tobytes())); off = (off + a.nbytes + 127) // 128 * 128
    return b"MGL1" + struct.pack("<i", len(T)) + ent, data, off
if __name__ == "__main__":
    out = sys.argv[1]; os.makedirs(out, exist_ok=True)
    a = int(sys.argv[2]) if len(sys.argv) > 2 else 0; b = int(sys.argv[3]) if len(sys.argv) > 3 else 26
    for i in range(a, b + 1):
        d, sz = pack_layer(i); open(f"{out}/vit{i:02d}.bin", "wb").write(d); print(i, len(d), flush=True)
    print(sz)
    if b == 26:
        h, data, off = glue()
        with open(f"{out}/vit_glue.bin", "wb") as fo:
            fo.write(h)
            for o, bb in data: fo.seek(o); fo.write(bb)
        print("vit_glue.bin", off)
