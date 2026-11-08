"""CPU reference of the Qwen3-VL vision tower (HF transformers) with the visual weights of Qwen-Image-2.1. usage: python vit_ref.py IMG.png SIZE_W SIZE_H OUT.npz  (image resized to W x H, multiples of 32)"""
import sys, json, numpy as np, torch
from PIL import Image
from safetensors.torch import load_file
from transformers.models.qwen3_vl.configuration_qwen3_vl import Qwen3VLVisionConfig
from transformers.models.qwen3_vl.modeling_qwen3_vl import Qwen3VLVisionModel
cfg = Qwen3VLVisionConfig(**{k: v for k, v in json.load(open("ref/Qwen-Image-2.1-Turbo/text_encoder/config.json"))["vision_config"].items() if k not in ("dtype", "model_type")})
m = Qwen3VLVisionModel(cfg).eval()
sd = {k[len("model.visual."):]: v.float() for k, v in load_file("models/visual.safetensors").items()}
print(m.load_state_dict(sd, strict=True))
def prep(path, W, H):
    im = Image.open(path).convert("RGB").resize((W, H), Image.BICUBIC); a = np.asarray(im).astype(np.float32) / 255.0
    mean = np.array([0.5, 0.5, 0.5], np.float32); std = np.array([0.5, 0.5, 0.5], np.float32); a = ((a - mean) / std).transpose(2, 0, 1)   # [3,H,W]
    p, ms = 16, 2; gh, gw = H // p, W // p
    t = np.stack([a, a])                                                                   # temporal patch 2 (image repeated)  [2,3,H,W]
    t = t.reshape(2, 3, gh // ms, ms, p, gw // ms, ms, p).transpose(2, 5, 3, 6, 1, 0, 4, 7)  # [gh/2, gw/2, ms, ms, 3, 2, p, p]
    return torch.from_numpy(t.reshape(gh * gw, 3 * 2 * p * p).copy()), torch.tensor([[1, gh, gw]])
if __name__ == "__main__":
    path, W, H, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
    px, thw = prep(path, W, H)
    with torch.no_grad(): o = m(px, grid_thw=thw)
    print(type(o), [type(x) for x in (o if isinstance(o, (tuple, list)) else [o])][:3])
    pooled = o.pooler_output if hasattr(o, "pooler_output") else o[1]; ds = o.deepstack_features if hasattr(o, "deepstack_features") else None
    print("pooled", tuple(pooled.shape), "deepstack", [tuple(d.shape) for d in ds] if ds is not None else None)
    np.savez(out, px=px.numpy(), pooled=pooled.numpy(), **({f"ds{i}": d.numpy() for i, d in enumerate(ds)} if ds is not None else {}))
