"""End-to-end CPU reference: TE embeddings (npy) -> txt_in -> prefix pass (t=0, causal) -> 4 Euler steps with the Lightning LoRA -> VAE decode. Blocks streamed from the GGUF.
usage: python qwen_e2e_ref.py TE.npy H W SEED OUT.png"""
import sys, math, time, numpy as np, torch, torch.nn.functional as F
import qref, qwen_ref as R
te_np, Hpx, Wpx, seed, outp = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
SIG = [1.0, 0.95418, 0.89508, 0.704534, 0.0]
T_ = qref.tensors()
def W(k):  # dequantized [out,in] + LoRA delta (dense) for model key like 'transformer_blocks.0.attn.to_q.weight'
    if k.endswith("img_mlp.gate_layer.weight"): w = qref.deq(T_[k.replace("gate_layer", "gate_up")])[:12288]
    elif k.endswith("img_mlp.proj.weight"): w = qref.deq(T_[k.replace("proj", "gate_up")])[12288:]
    else: w = qref.deq(T_[k])
    dw = qref.lora_delta(k); return w if dw is None else w + dw
def lin(x, k): return x @ W(k).T
def temb(tv):
    fr = torch.exp(-math.log(10000) * torch.arange(128).float() / 128); a = 1000 * tv * fr
    e = torch.cat((torch.cos(a), torch.sin(a)))
    return lin(F.silu(lin(e, "time_text_embed.timestep_embedder.linear_1.weight")), "time_text_embed.timestep_embedder.linear_2.weight")
def block(i, x, mod, cos, sin, Kp=None, Vp=None, causal=False):
    p = f"transformer_blocks.{i}."; n = x.shape[0]
    sc1, g1, sc2, g2 = mod.chunk(4)
    h = R.ln(x) * (1 + sc1)
    q = lin(h, p + "attn.to_q.weight").view(n, 32, 128); k = lin(h, p + "attn.to_k.weight").view(n, 32, 128); v = lin(h, p + "attn.to_v.weight").view(n, 32, 128)
    qn = qref.deq(T_[p + "attn.norm_q.weight"]); kn = qref.deq(T_[p + "attn.norm_k.weight"])
    q = R.apply_rope(R.rms(q, qn)[..., R.PERM], cos, sin); k = R.apply_rope(R.rms(k, kn)[..., R.PERM], cos, sin)
    kk, vv = (k, v) if Kp is None else (torch.cat((k, Kp), 0), torch.cat((v, Vp), 0))
    mask = torch.tril(torch.ones(n, n, dtype=torch.bool)) if causal else None
    a = F.scaled_dot_product_attention(q.transpose(0, 1)[None], kk.transpose(0, 1)[None], vv.transpose(0, 1)[None], attn_mask=mask)[0].transpose(0, 1).reshape(n, -1)
    x = x + lin(a, p + "attn.to_out.0.weight") * torch.tanh(g1)
    h = R.ln(x) * (1 + sc2)
    mid = F.silu(lin(h, p + "img_mlp.gate_layer.weight")) * lin(h, p + "img_mlp.proj.weight")
    return x + lin(mid, p + "img_mlp.out.weight") * torch.tanh(g2), k, v
torch.set_grad_enabled(False)
t0 = time.time()
te = torch.from_numpy(np.load(te_np)); Tt = te.shape[0]
h_, w_ = Hpx // 16, Wpx // 16; N = h_ * w_
te = te * torch.rsqrt(te.pow(2).mean(-1, keepdim=True) + 1e-6) * (qref.deq(T_["txt_in.text_norm.weight"]) + 1)
te = lin(F.gelu(lin(te, "txt_in.in_layer.weight"), approximate="tanh"), "txt_in.out_layer.weight")
from diffusers import QwenImage21Transformer2DModel as M
import json
cfg = {k: v for k, v in json.load(open(qref.CFG)).items() if not k.startswith("_")}; cfg["num_layers"] = 1
pe = M.from_config(cfg).pos_embed
fr = pe([(1, h_, w_)], torch.cat((torch.zeros(Tt, dtype=torch.bool), torch.ones(N, dtype=torch.bool))), device="cpu")
cos, sin = R.rope_halfsplit(fr)
mod0 = lin(F.silu(temb(torch.tensor(0.0))), "modulation.1.weight")
# prefix pass
x = te; KV = []
for i in range(32):
    x, k, v = block(i, x, mod0, cos[:Tt], sin[:Tt], causal=True); KV.append((k, v)); print("prefix", i, "%.0fs" % (time.time() - t0), flush=True)
g = torch.Generator().manual_seed(seed); z = torch.randn(64, h_ * w_, generator=g).T.contiguous()   # [N,64] tokens
for s in range(4):
    sg = SIG[s]; tb = temb(torch.tensor(sg)); mod = lin(F.silu(tb), "modulation.1.weight")
    x = lin(z, "img_in.weight")
    for i in range(32):
        x, _, _ = block(i, x, mod, cos[Tt:], sin[Tt:], KV[i][0], KV[i][1]); 
        if i % 8 == 7: print("step", s, "block", i, "%.0fs" % (time.time() - t0), flush=True)
    sc = lin(F.silu(tb), "norm_out.linear.weight"); v = lin(R.ln(x) * (1 + sc), "proj_out.weight")
    z = z + (SIG[s + 1] - sg) * v
    print("step", s, "done |v| %.3f |z| %.3f" % (float(v.pow(2).mean().sqrt()), float(z.pow(2).mean().sqrt())), flush=True)
np.save(outp.replace(".png", "_z.npy"), z.numpy())
from diffusers import AutoencoderKLQwenImage21
vae = AutoencoderKLQwenImage21.from_pretrained("ref/vae_dir", torch_dtype=torch.float32).eval()
lm = torch.tensor(vae.config.latents_mean).view(1, 64, 1, 1, 1); ls = torch.tensor(vae.config.latents_std).view(1, 64, 1, 1, 1)
lat = z.T.reshape(1, 64, 1, h_, w_) * ls + lm
img = vae.decode(lat, return_dict=False)[0][:, :, 0]
from PIL import Image
im = ((img[0].clamp(-1, 1) + 1) * 127.5).permute(1, 2, 0).byte().numpy(); Image.fromarray(im).save(outp); print("saved", outp, "%.0fs" % (time.time() - t0))
