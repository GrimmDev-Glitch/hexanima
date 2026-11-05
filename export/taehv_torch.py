"""Single-latent-frame TAEHV (taew2_1) decoder in torch, mirroring sd-src/src/model/vae/tae.hpp (TinyVideoDecoder, time_upscale [F,T,T]).
usage: python taehv_torch.py   -> compares with vae_ref.bin (sd.cpp ggml output of the same z.bin)"""
import os, sys, numpy as np, torch, torch.nn.functional as F
from safetensors import safe_open

MODEL = os.environ.get("MODELS", "models") + "/taew2_1.safetensors"


def load():
    f = safe_open(MODEL, "pt")
    return {k: f.get_tensor(k).float() for k in f.keys() if k.startswith("decoder.")}


def conv(x, w, b=None, pad=1):
    return F.conv2d(x, w, b, padding=pad)


def mem_block(P, i, x, past):
    h = torch.cat([x, past], 1)
    h = F.relu(conv(h, P[f"decoder.{i}.conv.0.weight"], P[f"decoder.{i}.conv.0.bias"]))
    h = F.relu(conv(h, P[f"decoder.{i}.conv.2.weight"], P[f"decoder.{i}.conv.2.bias"]))
    h = conv(h, P[f"decoder.{i}.conv.4.weight"], P[f"decoder.{i}.conv.4.bias"])
    return F.relu(h + x)  # all MemBlocks of this model have equal in/out channels (no skip conv)


def decode(P, z):  # z [1,16,H,W] -> [1,3,8H,8W]
    h = 3 * torch.tanh(z / 3)
    h = F.relu(conv(h, P["decoder.1.weight"], P["decoder.1.bias"]))
    idx, to_trim = 3, 3
    for layer, stride in enumerate((1, 2, 2)):
        for _ in range(3):
            past = torch.cat([torch.zeros_like(h[:1]), h[:-1]], 0)
            h = mem_block(P, idx, h, past); idx += 1
        if layer == 2 and stride > 1 and to_trim >= stride:
            drop = min(to_trim // stride, h.shape[0] - 1)
            h = h[drop:]; to_trim -= drop * stride
        h = F.interpolate(h, scale_factor=2, mode="nearest"); idx += 1
        h = conv(h, P[f"decoder.{idx}.conv.weight"], None, 0); idx += 1  # TGrow
        if stride != 1:
            h = h.reshape(h.shape[0] * stride, h.shape[1] // stride, *h.shape[2:])
        h = conv(h, P[f"decoder.{idx}.weight"]); idx += 1
    h = F.relu(h)
    h = conv(h, P["decoder.22.weight"], P["decoder.22.bias"])
    return h[to_trim:]


if __name__ == "__main__":
    torch.set_grad_enabled(False)
    P = load()
    z = torch.from_numpy(np.fromfile("z.bin", np.float32)).reshape(1, 16, 96, 64)
    out = decode(P, z)
    ref = torch.from_numpy(np.fromfile("vae_ref.bin", np.float32)).reshape(1, 3, 768, 512)
    print(out.shape, "rel err vs ggml", ((out - ref).norm() / ref.norm()).item(), "range", out.min().item(), out.max().item(), ref.min().item(), ref.max().item())
