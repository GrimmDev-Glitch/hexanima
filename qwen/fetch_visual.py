"""Range-fetch only the visual-tower tensors from Qwen/Qwen-Image-2.1-Turbo text_encoder/model.safetensors (17.5 GB) -> models/visual.safetensors"""
import json, struct, urllib.request, numpy as np, torch
from safetensors.torch import save_file
U = "https://huggingface.co/Qwen/Qwen-Image-2.1-Turbo/resolve/main/text_encoder/model.safetensors"
def get(a, b):
    r = urllib.request.Request(U, headers={"Range": f"bytes={a}-{b}"}); return urllib.request.urlopen(r).read()
n = struct.unpack("<Q", get(0, 7))[0]; hdr = json.loads(get(8, 8 + n - 1)); base = 8 + n
keys = sorted([k for k in hdr if k != "__metadata__" and "visual" in k], key=lambda k: hdr[k]["data_offsets"][0])
print(len(keys), "visual tensors; first", keys[0], "total MB", sum(hdr[k]["data_offsets"][1] - hdr[k]["data_offsets"][0] for k in keys) / 1e6)
out = {}; dt = {"BF16": torch.bfloat16, "F32": torch.float32, "F16": torch.float16}
i = 0
while i < len(keys):   # merge contiguous ranges
    j = i; a = hdr[keys[i]]["data_offsets"][0]; b = hdr[keys[i]]["data_offsets"][1]
    while j + 1 < len(keys) and hdr[keys[j + 1]]["data_offsets"][0] == b and b - a < 256 << 20: j += 1; b = hdr[keys[j]]["data_offsets"][1]
    blob = get(base + a, base + b - 1)
    for k in keys[i:j + 1]:
        o0, o1 = hdr[k]["data_offsets"]; t = torch.frombuffer(bytearray(blob[o0 - a:o1 - a]), dtype=dt[hdr[k]["dtype"]]).reshape(hdr[k]["shape"]); out[k] = t.clone()
    i = j + 1; print("fetched", i, "/", len(keys), flush=True)
save_file(out, "models/visual.safetensors"); print("saved")
