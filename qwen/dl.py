import os
from huggingface_hub import hf_hub_download, snapshot_download
tok=os.environ.get("HF_TOKEN")
R="ref/Qwen-Image-2.1-Turbo"
snapshot_download("Qwen/Qwen-Image-2.1-Turbo", local_dir=R, token=tok, allow_patterns=["*.json","*.jinja","LICENSE","README.md"], ignore_patterns=["assets/*"])
print("base configs ok", flush=True)
hf_hub_download("unsloth/Qwen-Image-2.1-FP8","vae/qwen_image_2.1_vae_bf16.safetensors",local_dir="models",token=tok); print("vae ok", flush=True)
hf_hub_download("unsloth/Qwen3-VL-8B-Instruct-GGUF","Qwen3-VL-8B-Instruct-UD-Q4_K_XL.gguf",local_dir="models",token=tok); print("te ok", flush=True)
print("DONE", flush=True)
