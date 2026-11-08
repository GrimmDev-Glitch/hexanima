"""Inputs for qwenrun edit mode from a qwen_edit_ref.py run: python mk_edit_inputs.py PREFIX(e.g. out/edit_ref_fox) CW CH "prompt" OUTDIR -> OUTDIR/{ids.i32,meta.txt,cond.f32,vit.f32}"""
import sys, os, numpy as np
from tokenizers import Tokenizer
pre, CW, CH, prompt, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
os.makedirs(out, exist_ok=True)
tk = Tokenizer.from_file("ref/Qwen-Image-2.1-Turbo/processor/tokenizer.json")
SYS = "Comprehend and analyze the provided prompt."
ids = tk.encode(f"<|im_start|>system\n{SYS}<|im_end|>\n<|im_start|>user\n<image1><|vision_start|><|image_pad|><|vision_end|>{prompt}<|im_end|>\n<|im_start|>assistant\n").ids
PAD = tk.token_to_id("<|image_pad|>"); k0 = ids.index(PAD); nm = (CH // 32) * (CW // 32)
ids = ids[:k0] + [PAD] * nm + ids[k0 + 1:]; np.array(ids, np.int32).tofile(f"{out}/ids.i32")
open(f"{out}/meta.txt", "w").write(f"{CW} {CH}\n")
np.load(pre + "_cond.npy").astype(np.float32).tofile(f"{out}/cond.f32")
v = np.load(pre + "_vit.npz"); np.concatenate([v["pooled"], v["ds0"], v["ds1"], v["ds2"]]).astype(np.float32).tofile(f"{out}/vit.f32")
print("tokens", len(ids), "k0", k0, "pads", nm, "drop", len(tk.encode(f"<|im_start|>system\n{SYS}<|im_end|>\n").ids))
