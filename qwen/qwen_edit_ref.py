"""CPU reference of Qwen-Image-2.1 EDITING (one condition image), kernel-form math, blocks streamed from the GGUF.
usage: python qwen_edit_ref.py COND.png CW CH "prompt" TW TH SEED OUT.png      (cond image resized to CW x CH, target TW x TH, multiples of 32)
Writes OUT.png + intermediates next to it (OUT_te.npy: TE rows after drop; OUT_vit.npz: pooled + deepstack; OUT_cond.npy: cond latent tokens)."""
import sys, math, time, json, numpy as np, torch, torch.nn.functional as F
from PIL import Image
import qref, qwen_ref as R, qwen_te_ref as TE
cond_p, CW, CH, prompt, TW, TH, seed, outp = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], int(sys.argv[5]), int(sys.argv[6]), int(sys.argv[7]), sys.argv[8]
SIG = [1.0, 0.95418, 0.89508, 0.704534, 0.0]; T_ = qref.tensors(); torch.set_grad_enabled(False); t0 = time.time()
def W(k):
    if k.endswith("img_mlp.gate_layer.weight"): w = qref.deq(T_[k.replace("gate_layer", "gate_up")])[:12288]
    elif k.endswith("img_mlp.proj.weight"): w = qref.deq(T_[k.replace("proj", "gate_up")])[12288:]
    else: w = qref.deq(T_[k])
    dw = qref.lora_delta(k); return w if dw is None else w + dw
def lin(x, k): return x @ W(k).T
def temb(tv):
    fr = torch.exp(-math.log(10000) * torch.arange(128).float() / 128); a = 1000 * tv * fr; e = torch.cat((torch.cos(a), torch.sin(a)))
    return lin(F.silu(lin(e, "time_text_embed.timestep_embedder.linear_1.weight")), "time_text_embed.timestep_embedder.linear_2.weight")
def block(i, x, mod, cos, sin, Kp=None, Vp=None, mask=None):
    p = f"transformer_blocks.{i}."; n = x.shape[0]; sc1, g1, sc2, g2 = mod.chunk(4)
    h = R.ln(x) * (1 + sc1)
    q = lin(h, p + "attn.to_q.weight").view(n, 32, 128); k = lin(h, p + "attn.to_k.weight").view(n, 32, 128); v = lin(h, p + "attn.to_v.weight").view(n, 32, 128)
    q = R.apply_rope(R.rms(q, qref.deq(T_[p + "attn.norm_q.weight"]))[..., R.PERM], cos, sin); k = R.apply_rope(R.rms(k, qref.deq(T_[p + "attn.norm_k.weight"]))[..., R.PERM], cos, sin)
    kk, vv = (k, v) if Kp is None else (torch.cat((k, Kp), 0), torch.cat((v, Vp), 0))
    a = F.scaled_dot_product_attention(q.transpose(0, 1)[None], kk.transpose(0, 1)[None], vv.transpose(0, 1)[None], attn_mask=mask)[0].transpose(0, 1).reshape(n, -1)
    x = x + lin(a, p + "attn.to_out.0.weight") * torch.tanh(g1); h = R.ln(x) * (1 + sc2)
    mid = F.silu(lin(h, p + "img_mlp.gate_layer.weight")) * lin(h, p + "img_mlp.proj.weight")
    return x + lin(mid, p + "img_mlp.out.weight") * torch.tanh(g2), k, v
import os, gc
tk = TE.Tokenizer.from_file(TE.TOK)
tmpl = f"<|im_start|>system\n{TE.SYS}<|im_end|>\n<|im_start|>user\n<image1><|vision_start|><|image_pad|><|vision_end|>{prompt}<|im_end|>\n<|im_start|>assistant\n"
ids0 = tk.encode(tmpl).ids; PAD = tk.token_to_id("<|image_pad|>"); k0 = ids0.index(PAD); hc, wc = CH // 16, CW // 16; nm = (hc // 2) * (wc // 2)
ids = ids0[:k0] + [PAD] * nm + ids0[k0 + 1:]; n = len(ids); drop = TE.encode("x")[1]
from diffusers import AutoencoderKLQwenImage21
vae = AutoencoderKLQwenImage21.from_pretrained("ref/vae_dir", torch_dtype=torch.float32).eval()
lm = torch.tensor(vae.config.latents_mean).view(1, 64, 1, 1); ls = torch.tensor(vae.config.latents_std).view(1, 64, 1, 1)
f_cond, f_te = outp.replace(".png", "_cond.npy"), outp.replace(".png", "_te.npy")
if os.path.exists(f_cond) and os.path.exists(f_te):
    cond = torch.from_numpy(np.load(f_cond)); te = torch.from_numpy(np.load(f_te)); print("cached cond/te", flush=True)
else:
    import vit_ref as V   # builds the 0.6B ViT (2.4 GB fp32): only when the cache is missing
    im = Image.open(cond_p).convert("RGBA"); im = im.resize((CW, CH), Image.BICUBIC)
    white = Image.new("RGB", im.size, (255, 255, 255)); white.paste(im, mask=im.getchannel("A")); white.save(outp.replace(".png", "_condrgb.png"))
    px, thw = V.prep(outp.replace(".png", "_condrgb.png"), CW, CH)
    vo = V.m(px, grid_thw=thw); pooled = vo.pooler_output; ds = vo.deepstack_features; nm = pooled.shape[0]; print("vit", tuple(pooled.shape), "%.0fs" % (time.time() - t0), flush=True)
    np.savez(outp.replace(".png", "_vit.npz"), pooled=pooled.numpy(), ds0=ds[0].numpy(), ds1=ds[1].numpy(), ds2=ds[2].numpy())
    vimg = torch.from_numpy(np.asarray(im).astype(np.float32) / 127.5 - 1).permute(2, 0, 1)[None, :, None]           # [1,4,1,H,W] RGBA in [-1,1]
    mu = vae.encode(vimg).latent_dist.mode()[:, :, 0]                                                                # [1,64,h,w]
    del V.m; gc.collect()
    cond = ((mu - lm) / ls)[0].flatten(1).T.contiguous(); assert cond.shape[0] == hc * wc    # [Nc,64] row-major tokens
    np.save(outp.replace(".png", "_cond.npy"), cond.numpy()); print("cond latent", tuple(cond.shape), "%.0fs" % (time.time() - t0), flush=True)
    # ---------------- text encoder with the image ----------------
    x = TE.deq("token_embd.weight", rows=ids); padpos = torch.tensor([i for i in range(n) if ids[i] == PAD]); x[padpos] = pooled
    # interleaved mrope positions (t,h,w): text sequential; image grid (merged) rows/cols offset by the position before the image
    pos = torch.zeros(3, n); cur = 0; gh, gw = hc // 2, wc // 2
    for i in range(n):
        if i < k0 or i >= k0 + nm: pos[:, i] = cur if i < k0 else cur; 
        if i < k0: cur = i + 1
    base = k0
    for i in range(k0): pos[:, i] = i
    for r in range(gh):
        for c in range(gw): j = k0 + r * gw + c; pos[0, j] = base; pos[1, j] = base + r; pos[2, j] = base + c
    nxt = base + max(gh, gw)
    for i in range(k0 + nm, n): pos[:, i] = nxt + (i - (k0 + nm))
    inv = 5e6 ** (-torch.arange(0, 128, 2).float() / 128)                      # 64 freqs
    axis = torch.zeros(64, dtype=torch.long)
    for i in range(64):
        if i % 3 == 1 and i < 60: axis[i] = 1
        elif i % 3 == 2 and i < 60: axis[i] = 2
    ang = pos[axis].T * inv[None]; cos = torch.cat((ang.cos(),) * 2, -1); sin = torch.cat((ang.sin(),) * 2, -1)          # [n,128]
    def layer(x, i):
        p = f"blk.{i}."; h = TE.rms(x, TE.deq(p + "attn_norm.weight"))
        q = (h @ TE.deq(p + "attn_q.weight").T).view(n, 32, 128); k = (h @ TE.deq(p + "attn_k.weight").T).view(n, 8, 128); v = (h @ TE.deq(p + "attn_v.weight").T).view(n, 8, 128)
        q = TE.rms(q, TE.deq(p + "attn_q_norm.weight")); k = TE.rms(k, TE.deq(p + "attn_k_norm.weight"))
        q = q * cos[:, None] + TE.rot_half(q) * sin[:, None]; k = k * cos[:, None] + TE.rot_half(k) * sin[:, None]
        a = F.scaled_dot_product_attention(q.transpose(0, 1)[None], k.transpose(0, 1)[None].repeat_interleave(4, 1), v.transpose(0, 1)[None].repeat_interleave(4, 1), is_causal=True)[0].transpose(0, 1).reshape(n, -1)
        x = x + a @ TE.deq(p + "attn_output.weight").T; h = TE.rms(x, TE.deq(p + "ffn_norm.weight"))
        return x + (F.silu(h @ TE.deq(p + "ffn_gate.weight").T) * (h @ TE.deq(p + "ffn_up.weight").T)) @ TE.deq(p + "ffn_down.weight").T
    for i in range(36):
        x = layer(x, i)
        if i < 3: x[padpos] += ds[i]                                           # DeepStack
        if i % 6 == 5: print("te layer", i, "%.0fs" % (time.time() - t0), flush=True)
    te = x[drop:]; np.save(f_te, te.numpy()); del x; gc.collect()

ids_after = ids[drop:]; padrows = torch.tensor([i for i, t in enumerate(ids_after) if t == PAD])       # rows of the TE output that are image slots
# ---------------- DiT ----------------
te = te * torch.rsqrt(te.pow(2).mean(-1, keepdim=True) + 1e-6) * (qref.deq(T_["txt_in.text_norm.weight"]) + 1)
te = lin(F.gelu(lin(te, "txt_in.in_layer.weight"), approximate="tanh"), "txt_in.out_layer.weight")
th, tw = TH // 16, TW // 16; Nt = th * tw
first, last = int(padrows[0]), int(padrows[-1]) + 1                       # text_before | pads | text_after
seq_text_before, seq_text_after = te[:first], te[last:]
cond_emb = lin(cond, "img_in.weight")
prefix = torch.cat((seq_text_before, cond_emb, seq_text_after), 0); Lp = prefix.shape[0]; nb, nc = seq_text_before.shape[0], cond.shape[0]
isimg = torch.cat((torch.zeros(nb, dtype=torch.bool), torch.ones(nc, dtype=torch.bool), torch.zeros(seq_text_after.shape[0], dtype=torch.bool), torch.ones(Nt, dtype=torch.bool)))
from diffusers import QwenImage21Transformer2DModel as M
cfg = {k: v for k, v in json.load(open(qref.CFG)).items() if not k.startswith("_")}; cfg["num_layers"] = 1
_m = M.from_config(cfg); pe = _m.pos_embed; del _m; gc.collect()
fr = pe([(1, hc, wc), (1, th, tw)], isimg, device="cpu"); cosD, sinD = R.rope_halfsplit(fr)
blk = torch.arange(Lp + Nt); bid = blk.clone(); bid[nb:nb + nc] = nb                  # cond rows share one block id; every other row its own
mask_pre = (blk[:Lp, None] >= blk[None, :Lp]) | (bid[:Lp, None] == bid[None, :Lp])
mod0 = lin(F.silu(temb(torch.tensor(0.0))), "modulation.1.weight"); x = prefix; KV = []
for i in range(32):
    x, k, v = block(i, x, mod0, cosD[:Lp], sinD[:Lp], mask=mask_pre); KV.append((k, v))
    if i % 8 == 7: print("prefix", i, "%.0fs" % (time.time() - t0), flush=True)
g = torch.Generator().manual_seed(seed); z = torch.randn(64, Nt, generator=g).T.contiguous()
for s in range(4):
    sg = SIG[s]; tb = temb(torch.tensor(sg)); mod = lin(F.silu(tb), "modulation.1.weight"); x = lin(z, "img_in.weight")
    for i in range(32):
        mask = (bid[Lp:, None] >= 0) | torch.zeros(1, Lp + Nt, dtype=torch.bool)   # target rows see the whole prefix and the whole target block
        x, _, _ = block(i, x, mod, cosD[Lp:], sinD[Lp:], KV[i][0], KV[i][1])
        if i % 8 == 7: print("step", s, "block", i, "%.0fs" % (time.time() - t0), flush=True)
    sc = lin(F.silu(tb), "norm_out.linear.weight"); v = lin(R.ln(x) * (1 + sc), "proj_out.weight"); z = z + (SIG[s + 1] - sg) * v
    print("step", s, "done", flush=True)
np.save(outp.replace(".png", "_z.npy"), z.numpy())
lat = z.T.reshape(1, 64, 1, th, tw) * ls.view(1, 64, 1, 1, 1) + lm.view(1, 64, 1, 1, 1)
img = vae.decode(lat, return_dict=False)[0][:, :, 0]; Image.fromarray(((img[0, :3].clamp(-1, 1) + 1) * 127.5).permute(1, 2, 0).byte().numpy()).save(outp); print("saved", outp, "%.0fs" % (time.time() - t0))
