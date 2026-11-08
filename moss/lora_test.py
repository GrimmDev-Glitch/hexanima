import sys, subprocess, numpy as np
from tokenizers import Tokenizer
from PIL import Image
prompt, tag, lora = sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else ""
P = "/data/local/tmp/moss"
def sh(c): return subprocess.run(["adb", "shell", c], capture_output=True, text=True).stdout
ids = Tokenizer.from_file("models/tokenizer.json").encode("<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n<think>\n", add_special_tokens=False).ids
np.array(ids, np.int32).tofile("ids_t.i32"); subprocess.run(["adb", "push", "ids_t.i32", P + "/ids_t.i32"], capture_output=True)
env = f"export LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:{P}/qnn ADSP_LIBRARY_PATH={P}" + (f" MOSS_LORA={P}/{lora}" if lora else "")
print(sh(f"cd {P} && {env} && printf 'load {P}/model\\ngen {P}/ids_t.i32 512 768 7 4 {P}/z_t.raw\\nquit\\n' | ./mossrun 2>&1 | tail -8"))
print(sh(f"cd {P}/qnn && {env} && printf 'run {P}/z_t.raw {P}/img_t.raw\\nquit\\n' | ./vaerun ./libQnnHtp.so ./libQnnSystem.so vae_768x512.bin 2>&1 | tail -2"))
subprocess.run(["adb", "pull", P + "/img_t.raw", "img_t.raw"], capture_output=True)
a = np.fromfile("img_t.raw", np.float32).reshape(3, 512, 768); Image.fromarray(((a.transpose(1, 2, 0).clip(-1, 1) + 1) * 127.5).astype(np.uint8)).save(f"out/lora_{tag}.png"); print("saved", tag)
