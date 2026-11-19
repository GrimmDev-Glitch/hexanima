# SDXL (diffusers folder from extract.py) -> ONNX pieces for the V73 NPU + the small fp32 files sdxlrun needs.
#   python export_parts.py parts MODEL REF_DIR OUT_DIR [i,j,...]   UNet parts (parts.py cuts) -> OUT/part<i>.onnx; example inputs from REF_DIR/calib/p<i>/
#   python export_parts.py calib REF_DIR OUT_DIR i LAYOUT [N]     raw fp32 calibration files + input list for qairt-quantizer (LAYOUT nchw|nhwc for 4D tensors)
#   python export_parts.py emb   MODEL OUT_DIR                    emb.bin (time + size embedding MLPs, fp32)
#   python export_parts.py te    MODEL OUT_DIR                    te_l.onnx, te_g.onnx (ids int32 [1,77] -> penultimate hidden; te_g also "last" = final LN), te_proj.bin
#   python export_parts.py vae   MODEL OUT_DIR LATENT_HW [REF_LATENT.npy]   vae.onnx: z (= latent / scaling_factor) [1,4,h,w] -> image [1,3,8h,8w]; prints peak activations
import sys, os, glob, numpy as np, torch
mode = sys.argv[1]
torch.set_num_threads(os.cpu_count())

def onnx_export(m, args, path, inames, onames):
    torch.onnx.export(m, args, path, input_names=inames, output_names=onames, opset_version=17, dynamo=False, do_constant_folding=True)
    print('wrote', path, f'{os.path.getsize(path) / 1e6:.0f} MB', flush=True)

if mode == 'parts':
    from diffusers import UNet2DConditionModel
    import parts as P
    model, ref, out = sys.argv[2:5]; os.makedirs(out, exist_ok=True)
    unet = UNet2DConditionModel.from_pretrained(model, subfolder='unet', torch_dtype=torch.float16).eval()
    U, parts = P.make_parts(unet, P.default_cuts(P.units_of(unet)))
    sel = [int(i) for i in sys.argv[5].split(',')] if len(sys.argv) > 5 else range(len(parts))
    for i in sel:
        p = parts[i]
        d = np.load(sorted(glob.glob(f'{ref}/calib/p{i}/*.npz'))[0])
        ins = tuple(torch.from_numpy(d[n].astype(np.float32)) for n in p.input_names)
        p.float()
        with torch.no_grad(): ref_out = p(*ins)
        onnx_export(p, ins, f'{out}/part{i}.onnx', p.input_names, p.output_names)
        p.half()
        print(f'part{i} in', [(n, tuple(t.shape)) for n, t in zip(p.input_names, ins)], 'out', [(n, tuple(t.shape)) for n, t in zip(p.output_names, ref_out)])

elif mode == 'calib':
    ref, out, i, layout = sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5]
    N = int(sys.argv[6]) if len(sys.argv) > 6 else 1000
    files = sorted(glob.glob(f'{ref}/calib/p{i}/*.npz'))
    files = files[::max(1, len(files) // N)][:N]
    cd = f'{out}/calib_p{i}'; os.makedirs(cd, exist_ok=True); lines = []
    for f in files:
        d = np.load(f); b = os.path.basename(f)[:-4]; ent = []
        for n in d.files:
            a = d[n].astype(np.float32)
            if a.ndim == 4 and layout == 'nhwc': a = a.transpose(0, 2, 3, 1)
            fn = f'{cd}/{b}_{n}.raw'; np.ascontiguousarray(a).tofile(fn); ent.append(f'{n}:={os.path.abspath(fn)}')
        lines.append(' '.join(ent))
    open(f'{out}/calib_p{i}.txt', 'w').write('\n'.join(lines) + '\n'); print(f'{len(lines)} samples -> {out}/calib_p{i}.txt')

elif mode == 'emb':
    from diffusers import UNet2DConditionModel
    model, out = sys.argv[2:4]; os.makedirs(out, exist_ok=True)
    unet = UNet2DConditionModel.from_pretrained(model, subfolder='unet', torch_dtype=torch.float16)   # fp32 would be 10 GB (OOM in WSL)
    unet.time_embedding.float(); unet.add_embedding.float()
    c = unet.config; assert c.flip_sin_to_cos and c.freq_shift == 0 and c.addition_time_embed_dim == 256, 'sdxlrun assumes the SDXL embedding config'
    ws = []
    for L in [unet.time_embedding.linear_1, unet.time_embedding.linear_2, unet.add_embedding.linear_1, unet.add_embedding.linear_2]:
        ws += [L.weight.detach().numpy().ravel(), L.bias.detach().numpy().ravel()]
    np.concatenate(ws).astype(np.float32).tofile(f'{out}/emb.bin'); print('wrote emb.bin')

elif mode == 'te':
    from transformers import CLIPTextModel, CLIPTextModelWithProjection
    model, out = sys.argv[2:4]; os.makedirs(out, exist_ok=True)
    class TE(torch.nn.Module):
        def __init__(self, m, last): super().__init__(); self.m = m; self.last = last
        def forward(self, ids):
            o = self.m(input_ids=ids, output_hidden_states=True)
            h = o.hidden_states[-2]
            if not self.last: return h
            return h, self.m.text_model.final_layer_norm(o.hidden_states[-1])
    ids = torch.full((1, 77), 49407, dtype=torch.int32); ids[0, 0] = 49406; ids[0, 1:4] = torch.tensor([1125, 539, 320])
    te1 = CLIPTextModel.from_pretrained(model, subfolder='text_encoder', torch_dtype=torch.float32, attn_implementation='eager').eval()
    with torch.no_grad(): onnx_export(TE(te1, False), (ids,), f'{out}/te_l.onnx', ['ids'], ['hidden'])
    del te1
    te2 = CLIPTextModelWithProjection.from_pretrained(model, subfolder='text_encoder_2', torch_dtype=torch.float32, attn_implementation='eager').eval()
    te2.text_projection.weight.detach().numpy().astype(np.float32).tofile(f'{out}/te_proj.bin')
    with torch.no_grad(): onnx_export(TE(te2, True), (ids,), f'{out}/te_g.onnx', ['ids'], ['hidden', 'last'])

elif mode == 'vae':
    from diffusers import AutoencoderKL
    model, out, hw = sys.argv[2], sys.argv[3], int(sys.argv[4]); os.makedirs(out, exist_ok=True)
    vae = AutoencoderKL.from_pretrained(model, subfolder='vae', torch_dtype=torch.float32).eval()
    class Dec(torch.nn.Module):
        def __init__(self, v): super().__init__(); self.v = v
        def forward(self, z): return self.v.decoder(self.v.post_quant_conv(z))
    dec = Dec(vae).eval()
    z = torch.from_numpy(np.load(sys.argv[5])) / vae.config.scaling_factor if len(sys.argv) > 5 else torch.randn(1, 4, hw, hw)
    peak = {}
    hooks = [m.register_forward_hook(lambda m, i, o, n=n: peak.__setitem__(n, max(peak.get(n, 0), float(o.abs().max()))))
             for n, m in dec.named_modules() if isinstance(m, (torch.nn.Conv2d, torch.nn.GroupNorm, torch.nn.Linear))]
    with torch.no_grad(): dec(z)
    for h in hooks: h.remove()
    top = sorted(peak.items(), key=lambda kv: -kv[1])[:6]; print('peak activations (fp16 max 65504):', [(n, round(v)) for n, v in top])
    with torch.no_grad(): onnx_export(dec, (z[:, :, :hw, :hw].contiguous(),), f'{out}/vae.onnx', ['z'], ['image'])
