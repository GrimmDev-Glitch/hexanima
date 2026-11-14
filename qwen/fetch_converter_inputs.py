"""Fetch pinned, SHA-256-checked host inputs for the Qwen V73 LoRA exporter.

Run inside Dockerfile.converter with /work mapped to this qwen/ directory.
Large files resume from .part; no credentials are needed for these public files.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parent
FILES = {
    "gguf": (
        "models/qwen-image-2.1-turbo-Q5_K_S.gguf",
        "https://huggingface.co/unsloth/Qwen-Image-2.1-Turbo-GGUF/resolve/bd53e520a64472adaff780da0b2f2d7eb1605e30/qwen-image-2.1-turbo-Q5_K_S.gguf",
        "72d93eb6f5d7be1a619c34f525393d001bc2fb9485d087f63c8462932b40177d",
    ),
    "lightning": (
        "loras/lightning_v2rl_r64.safetensors",
        "https://huggingface.co/lightx2v/Qwen-Image-2.1-Lightning-v2-RL/resolve/682b2529ffb5ae9ba39ceb81a31ce99e602da2ee/qwen_image_21_lightning_v2_rl_4steps_lora_rank64.safetensors",
        "02363ec990ef2c908be8be3fc5e757d169830efe09bc03490af8c5ead3b09c6d",
    ),
    "consistency": (
        "loras/qwen21_consistency_v1.safetensors",
        "https://civitai.com/api/download/models/3364470",
        "4f44ada1be2189cc23b3d010f9603543403f48454e2f76842a3d30109b20bd63",
    ),
    "vae-config": (
        "ref/Qwen-Image-2.1-Turbo/vae/config.json",
        "https://huggingface.co/Qwen/Qwen-Image-2.1-Turbo/resolve/fc13ba1986fcb905854dc8de2f52eee395f49b5b/vae/config.json",
        "42bfdec412459bbb7bc38a0617ab70edcfc873f84ec47e0c7d01c53ba09885a5",
    ),
    "transformer-config": (
        "ref/Qwen-Image-2.1-Turbo/transformer/config.json",
        "https://huggingface.co/Qwen/Qwen-Image-2.1-Turbo/resolve/066a07d448bc120b2d99d75f1935a76b8c80864b/transformer/config.json",
        "2c567038ca190824728844b8d06d94ae02360e668707afd9734e789a7eb58ce3",
    ),
}


def digest(path: Path) -> str:
    hash_ = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(4 << 20), b""):
            hash_.update(chunk)
    return hash_.hexdigest()


def fetch(name: str) -> None:
    relative, url, expected = FILES[name]
    dest = ROOT / relative
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_file() and (expected is None or digest(dest) == expected):
        print(f"{name}: already verified ({dest.stat().st_size} bytes)", flush=True)
        return
    part = dest.with_name(dest.name + ".part")
    have = part.stat().st_size if part.exists() else 0
    headers = {"User-Agent": "HexAnima V73 converter"}
    if have:
        headers["Range"] = f"bytes={have}-"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        if have and response.status != 206:
            have = 0
        mode = "ab" if have else "wb"
        written = have
        next_report = ((written // (128 << 20)) + 1) * (128 << 20)
        with part.open(mode) as file:
            while chunk := response.read(4 << 20):
                file.write(chunk)
                written += len(chunk)
                if written >= next_report:
                    print(f"{name}: {written / (1 << 20):.0f} MiB", flush=True)
                    next_report += 128 << 20
    actual = digest(part)
    if expected and actual != expected:
        raise ValueError(f"{name}: SHA-256 mismatch: {actual} != {expected}")
    if name == "vae-config":
        config = json.loads(part.read_text(encoding="utf-8"))
        if not config.get("latents_mean") or not config.get("latents_std"):
            raise ValueError("VAE config is missing latent normalization values")
    if name == "transformer-config":
        config = json.loads(part.read_text(encoding="utf-8"))
        expected = {
            "_class_name": "QwenImage21Transformer2DModel",
            "num_layers": 32,
            "num_attention_heads": 32,
            "attention_head_dim": 128,
        }
        if any(config.get(key) != value for key, value in expected.items()):
            raise ValueError("Transformer config does not match the pinned Qwen-Image-2.1 architecture")
    part.replace(dest)
    print(f"{name}: verified {dest.stat().st_size} bytes, SHA-256 {actual}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("names", nargs="+", choices=tuple(FILES))
    args = parser.parse_args()
    for item in args.names:
        fetch(item)
