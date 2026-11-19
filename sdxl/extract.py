# Split a single-file SDXL checkpoint (A1111/Civitai layout) into diffusers folders (fp16), one component at a time to stay inside ~7 GB of RAM.
# usage: python extract.py CKPT.safetensors CFG_DIR OUT_DIR   (CFG_DIR: stabilityai/stable-diffusion-xl-base-1.0 JSON + tokenizers, no weights)
import sys, gc, shutil, os, torch
from diffusers import UNet2DConditionModel, AutoencoderKL, StableDiffusionXLPipeline
ckpt, cfg, out = sys.argv[1:4]
os.makedirs(out, exist_ok=True)
for sub in ['tokenizer', 'tokenizer_2', 'scheduler']:
    shutil.copytree(os.path.join(cfg, sub), os.path.join(out, sub), dirs_exist_ok=True)
shutil.copy(os.path.join(cfg, 'model_index.json'), out)
which = sys.argv[4].split(',') if len(sys.argv) > 4 else ['vae', 'unet', 'te']
if 'vae' in which:
    m = AutoencoderKL.from_single_file(ckpt, config=cfg, subfolder='vae', torch_dtype=torch.float32)
    m.save_pretrained(os.path.join(out, 'vae')); print('vae ok', m.config.scaling_factor); del m; gc.collect()
if 'unet' in which:
    m = UNet2DConditionModel.from_single_file(ckpt, config=cfg, subfolder='unet', torch_dtype=torch.float16)
    m.save_pretrained(os.path.join(out, 'unet')); print('unet ok'); del m; gc.collect()
if 'te' in which:
    from diffusers.loaders.single_file_utils import load_single_file_checkpoint, create_diffusers_clip_model_from_ldm
    from transformers import CLIPTextModel, CLIPTextModelWithProjection
    sd = load_single_file_checkpoint(ckpt)
    sd = {k: v for k, v in sd.items() if k.startswith('conditioner.')}
    for name, cls in [('text_encoder', CLIPTextModel), ('text_encoder_2', CLIPTextModelWithProjection)]:
        comp = create_diffusers_clip_model_from_ldm(cls, sd, config=cfg, subfolder=name, torch_dtype=torch.float16)[name] if False else \
               create_diffusers_clip_model_from_ldm(cls, sd, config=cfg, subfolder=name, torch_dtype=torch.float16)
        if isinstance(comp, dict): comp = comp[name]
        comp.save_pretrained(os.path.join(out, name)); print(name, 'ok'); del comp; gc.collect()
