# PC reference for SDXL (Nova 3DCG XL), run in stages so that only one component is in RAM at a time (WSL has ~7 GB).
#   python ref.py te   MODEL OUT.npz "prompt" "negative"          -> ctx [2,77,2048] (uncond, cond), pooled [2,1280]
#   python ref.py unet MODEL COND.npz OUT_DIR W H STEPS CFG SEED [dtype]  -> OUT_DIR/x_T.npy, latent.npy, calib/step*.npz (UNet inputs per step)
#   python ref.py vae  MODEL LATENT.npy OUT.png
# Sampler: Euler (discrete, "leading"/"trailing" from the SDXL scheduler config), epsilon prediction, scaled-linear betas.
import sys, os, numpy as np, torch
mode, model = sys.argv[1], sys.argv[2]
torch.set_num_threads(os.cpu_count())

def time_ids(W, H):  # original size, crop top-left, target size
    return torch.tensor([[H, W, 0, 0, H, W]], dtype=torch.float32)

if mode == 'te':
    from transformers import CLIPTokenizer, CLIPTextModel, CLIPTextModelWithProjection
    out, prompts = sys.argv[3], [sys.argv[5], sys.argv[4]]          # [negative, positive]
    tok1 = CLIPTokenizer.from_pretrained(model, subfolder='tokenizer'); tok2 = CLIPTokenizer.from_pretrained(model, subfolder='tokenizer_2')
    ids1 = tok1(prompts, padding='max_length', max_length=77, truncation=True, return_tensors='pt').input_ids
    ids2 = tok2(prompts, padding='max_length', max_length=77, truncation=True, return_tensors='pt').input_ids
    with torch.no_grad():
        te1 = CLIPTextModel.from_pretrained(model, subfolder='text_encoder', torch_dtype=torch.float32)
        h1 = te1(ids1, output_hidden_states=True).hidden_states[-2]; del te1
        te2 = CLIPTextModelWithProjection.from_pretrained(model, subfolder='text_encoder_2', torch_dtype=torch.float32)
        o2 = te2(ids2, output_hidden_states=True); h2 = o2.hidden_states[-2]; pooled = o2.text_embeds; del te2
    ctx = torch.cat([h1, h2], -1)
    np.savez(out, ctx=ctx.numpy(), pooled=pooled.numpy(), ids1=ids1.numpy().astype(np.int32), ids2=ids2.numpy().astype(np.int32))
    print('ctx', tuple(ctx.shape), float(ctx.abs().max()), 'pooled', tuple(pooled.shape))

elif mode == 'unet':
    from diffusers import UNet2DConditionModel, EulerDiscreteScheduler
    cond, od, W, H, steps, cfg, seed = sys.argv[3], sys.argv[4], int(sys.argv[5]), int(sys.argv[6]), int(sys.argv[7]), float(sys.argv[8]), int(sys.argv[9])
    # The UNet runs as the NPU parts (parts.py) in fp32; every part's inputs are saved (fp16) under OUT_DIR/calib/p<i>/ for W8A16 calibration.
    import parts as P
    dump_calib = os.environ.get('SDXL_CALIB', '1') == '1'
    os.makedirs(od, exist_ok=True)
    c = np.load(cond); ctx = torch.from_numpy(c['ctx']); pooled = torch.from_numpy(c['pooled'])
    if cfg == 1.0: ctx, pooled = ctx[1:], pooled[1:]
    unet = UNet2DConditionModel.from_pretrained(model, subfolder='unet', torch_dtype=torch.float16).eval()
    U, parts = P.make_parts(unet, P.default_cuts(P.units_of(unet)))
    sch = EulerDiscreteScheduler.from_pretrained(model, subfolder='scheduler'); sch.set_timesteps(steps)
    g = torch.Generator().manual_seed(seed)
    x = torch.randn(1, 4, H // 8, W // 8, generator=g) * sch.init_noise_sigma
    np.save(od + '/x_T.npy', x.numpy()); np.save(od + '/sigmas.npy', sch.sigmas.numpy()); np.save(od + '/timesteps.npy', sch.timesteps.numpy())
    tid = time_ids(W, H)
    import time
    for i, t in enumerate(sch.timesteps):
        t0 = time.time()
        xin = sch.scale_model_input(x, t)
        eps = []
        for b in range(ctx.shape[0]):
            emb = P.embed(unet, t.reshape(1), pooled[b:b + 1], tid)
            def dump(pi, names, ts, b=b):
                if not dump_calib: return
                os.makedirs(f'{od}/calib/p{pi}', exist_ok=True)
                np.savez(f'{od}/calib/p{pi}/s{i:02d}_{b}.npz', **{n: v.numpy().astype(np.float16) for n, v in zip(names, ts)})
            eps.append(P.run_parts(parts, xin, emb, ctx[b:b + 1], dump))
            np.save(f'{od}/emb_s{i:02d}_{b}.npy', emb.numpy())
        eps = torch.cat(eps)
        np.savez(f'{od}/step{i:02d}.npz', x=xin.numpy(), t=np.float32(t), eps=eps.numpy())
        if eps.shape[0] == 2: eps = eps[0:1] + cfg * (eps[1:2] - eps[0:1])
        x = sch.step(eps, t, x).prev_sample
        print(f'step {i} t={float(t):.0f} {time.time()-t0:.1f}s', flush=True)
    np.save(od + '/latent.npy', x.numpy())

elif mode == 'vae':
    from diffusers import AutoencoderKL
    from PIL import Image
    vae = AutoencoderKL.from_pretrained(model, subfolder='vae', torch_dtype=torch.float32).eval()
    z = torch.from_numpy(np.load(sys.argv[3])) / vae.config.scaling_factor
    with torch.no_grad(): img = vae.decode(z).sample
    a = ((img[0].permute(1, 2, 0).clamp(-1, 1) + 1) * 127.5).round().byte().numpy()
    Image.fromarray(a).save(sys.argv[4]); print('wrote', sys.argv[4], a.shape)
