"""Export Qwen's dense glue weights, including optional user LoRA deltas."""
import json
import struct
import sys
from pathlib import Path

import numpy as np

import qref
from lora_keys import adapter_multiplier


def write_glue(path="dist/glue.bin", user_index=None, user_tensor=None, strength=1.0):
    source = qref.tensors()

    def weight(key):
        value = qref.deq(source[key])
        built_in = qref.lora_delta(key)
        if built_in is not None:
            value = value + built_in
        if user_index is not None:
            pair = user_index.pair(key.removesuffix(".weight"), user_tensor)
            if pair is not None:
                a, b, alpha = pair
                if (b.shape[0], a.shape[1]) != tuple(value.shape):
                    raise ValueError(f"LoRA shape does not match Qwen glue {key}: A={tuple(a.shape)} B={tuple(b.shape)} base={tuple(value.shape)}")
                value = value + (b.float() @ a.float()) * adapter_multiplier(strength, alpha, a.shape[0])
        return value

    def f32(value):
        return value.float().contiguous().numpy().astype(np.float32)

    def f16(value):
        return value.float().contiguous().numpy().astype(np.float16)

    tensors = [
        ("img_in.weight", 0, f32(weight("img_in.weight"))),
        ("proj_out.weight", 0, f32(weight("proj_out.weight"))),
        ("time.linear_1.weight", 0, f32(weight("time_text_embed.timestep_embedder.linear_1.weight"))),
        ("time.linear_2.weight", 1, f16(weight("time_text_embed.timestep_embedder.linear_2.weight"))),
        ("modulation.weight", 1, f16(weight("modulation.1.weight"))),
        ("norm_out.weight", 1, f16(weight("norm_out.linear.weight"))),
        ("txt_norm.weight", 0, f32(qref.deq(source["txt_in.text_norm.weight"]) + 1)),
        ("txt_in.weight", 1, f16(weight("txt_in.in_layer.weight"))),
        ("txt_out.weight", 1, f16(weight("txt_in.out_layer.weight"))),
    ]
    with open("ref/Qwen-Image-2.1-Turbo/vae/config.json", encoding="utf-8") as config_file:
        config = json.load(config_file)
    tensors.append(("vae.mean", 0, np.array(config["latents_mean"], np.float32)))
    tensors.append(("vae.std", 0, np.array(config["latents_std"], np.float32)))
    offset = (8 + len(tensors) * 76 + 127) // 128 * 128
    entries = b""
    data = []
    for name, dtype, array in tensors:
        dims = list(array.shape) + [1] * (3 - array.ndim)
        entries += struct.pack("<40sii3iqq", name.encode(), dtype, array.ndim, *dims, offset, array.nbytes)
        data.append((offset, array.tobytes()))
        offset = (offset + array.nbytes + 127) // 128 * 128
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as out:
        out.write(b"MGL1" + struct.pack("<i", len(tensors)) + entries)
        for location, payload in data:
            out.seek(location)
            out.write(payload)
    print(path, offset, "bytes,", len(tensors), "tensors")


if __name__ == "__main__":
    write_glue(sys.argv[1] if len(sys.argv) > 1 else "dist/glue.bin")
