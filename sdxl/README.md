# SDXL on the V73 NPU (feasibility prototype, in progress, 16 nov 2026)

Goal: one SDXL checkpoint, Nova 3DCG XL IL v9.0 (civitai.com/models/715287, Illustrious-family SDXL, `Nova3DCGILV9.safetensors`, 6.46 GB fp16),
running on the Hexagon V73 NPU of the Poco F6 Pro (SM8550). The goal is a phone picture that matches a PC reference, plus s/step and temperature.
App integration is out of scope.

## Status
- Done: components extracted, PC fp32 reference picture, UNet cut into 8 parts (checked against diffusers), all 8 parts built as **fp16** V73 contexts,
  multi-process phone runner `sdxlrun` (compiles, not yet run with all parts).
- Not finished on 16 nov (the work PC was put away at 20:00): CLIP-L / CLIP-G / VAE decoder fp16 contexts, `emb.bin`, `te_proj.bin`.
  Stopped at 19:45: `work/` has `te_l.onnx`, `te_g.onnx` (over 2 GB, so its weights are hundreds of loose external-data files in `work/`),
  `vae.onnx` (might be incomplete: export it again) and `te_proj.bin`. No CLIP/VAE context is built yet; `emb.bin` is missing (step 3).
  The first `emb` run was OOM-killed (it loaded the UNet in fp32); `export_parts.py emb` now loads fp16.
- Next: copy the parts to the phone, full UNet call vs PC (`test_unet.sh`), then `te`, `vae`, `gen`, then timings, temperature and DMD2 / Lightning.

## Findings so far
- **W8A16 does not work here**: part 1 (382M parameters, 32×32) quantized with qairt-quantizer (8-bit per-channel weights, 16-bit activations,
  40 calibration samples from the reference) gives cos 0.52 and scale 0.78 against fp32 (the same "under-scaled" look as the SD 1.5 packs, `../sd15/README.md`),
  and takes 4–5 s on the NPU. The **same part in fp16 gives a relative error of 0.1 % (cos 1.0000) and takes 0.53 s** (~1.4 TFLOPS). So the UNet runs in fp16.
- **One process (= one DSP session) maps at most ~2 GB of contexts** on this phone (2 × 742 MB + 3 × 121 MB loaded, the next one failed with 0x3f1).
  The limit is per process: two processes each held 2 × 742 MB at the same time. The fp16 UNet is 5.0 GB, so `sdxlrun` forks 3 workers
  (groups `0,1,2;3,4,7;5,6` = 1.67 / 1.76 / 1.57 GB), and the main process keeps every tensor in a shared mapping.
- The converter keeps NCHW for the model inputs/outputs (`preserve_io` layout is the default in QAIRT 2.28) but an extra output that is also used inside
  the graph can stay NHWC (part 1 `s7` is [1,32,32,1280]); `sdxlrun` transposes when producer and consumer disagree.
- PC reference speed: fp32 on the i5-11400H (WSL, 7 GB RAM), ~2 min per UNet call at 1024×1024; bf16 on this CPU is much slower (no native bf16).

## UNet parts (1024×1024, latent 128×128)
| part | units | params | fp16 context | inputs → outputs |
|---|---|---|---|---|
| 0 | conv_in … down2.resnet0 | 90M | 181 MB | h[1,4,128,128], emb, ctx → hout, s0–s6 |
| 1 | down2.attn0, down2.resnet1 | 382M | 742 MB | → hout, s7 |
| 2 | down2.attn1, mid.resnet0 | 382M | 742 MB | → hout, s8 |
| 3 | mid.attn, mid.resnet1 | 382M | 743 MB | → hout |
| 4 | up0.resnet0 + attn0 | 400M | 777 MB | + s8 |
| 5 | up0.resnet1 + attn1 | 400M | 777 MB | + s7 |
| 6 | up0.resnet2 + attn2 + upsample | 407M | 789 MB | + s6 → hout[1,1280,64,64] |
| 7 | up1, up2, out | 118M | 239 MB | + s0–s5 → eps[1,4,128,128] |

`emb` [1,1280] (time + pooled text + size embedding) is computed on the CPU (`sdxlrun` reads `emb.bin`), `ctx` [1,77,2048] = CLIP-L penultimate ‖ CLIP-G penultimate.

## Files
- `extract.py`: single-file checkpoint → diffusers folders, one component at a time (RAM).
- `parts.py`: the UNet as a list of units, cut into parts; fp32 reference that runs the same parts. `check_parts.py`: parts vs diffusers.
- `ref.py`: PC reference by stages (`te`, `unet` = parts in fp32 + calibration dumps, `vae`).
- `export_parts.py`: ONNX for parts / CLIP / VAE, `emb.bin`, `te_proj.bin`, calibration lists. `build.sh`: ONNX → V73 context (`part I` = W8A16, `fp16 NAME`).
- `sdxlrun.cpp` + `build_runner.sh`: phone runner (`te`, `unet`, `vae`, `gen`). `phone_setup.sh`, `test_part.sh`, `test_unet.sh`: phone helpers (WSL, Windows adb.exe).

## Where things are (WSL, not in Git)
- `~/Hexanima-dev/sdxl/dl/Nova3DCGILV9.safetensors`, `cfg/` (SDXL base JSON + tokenizers), `nova/` (diffusers folders: unet fp16, vae fp32, text encoders fp16).
- `~/Hexanima-dev/sdxl/cond_a.npz`: text embeddings of the test prompt; `ref_a/`: reference run (x_T, sigmas, timesteps, step??.npz with UNet in/out,
  emb_s??_?.npy, calib/p<i>/ per-part inputs, `latent.npy`, `ref.png`).
- `~/Hexanima-dev/sdxl/work/out_v73/`: built contexts (`part0..7.bin` fp16, `part1_w8.bin` = the failed W8A16 one).
- Phone: `/data/local/tmp/sdxl` holds the QAIRT 2.28 libs (libQnnHtp, libQnnSystem, V73 stub/skel) and `sdxlrun` (the latest build may not be pushed yet).

Reference prompt: "masterpiece, best quality, 1girl, solo, silver hair, blue eyes, smile, upper body, city street at night, neon lights",
negative "worst quality, low quality, bad anatomy, bad hands, blurry", 1024×1024, 20 steps Euler, CFG 5, seed 42.

## How to resume
Run everything from WSL (`wsl -d Ubuntu`). In Git Bash, prefix commands with `MSYS_NO_PATHCONV=1`, because Git Bash rewrites `/data/...` paths.
1. Rebuild and push the runner: `bash build_runner.sh`, then `adb.exe push sdxlrun /data/local/tmp/sdxl/ && adb.exe shell chmod +x /data/local/tmp/sdxl/sdxlrun`.
2. Copy the parts: `adb.exe shell mkdir -p /data/local/tmp/sdxl/m`, then for each `part<i>.bin`: `adb.exe push "$(wslpath -w ~/Hexanima-dev/sdxl/work/out_v73/part<i>.bin)" /data/local/tmp/sdxl/m/`.
3. If CLIP / VAE are not built yet: in `~/Hexanima-dev/sdxl` with `PYTHONPATH=<repo>/sdxl` and the moss venv python
   (`~/anima-npu/moss-venv/bin/python`): `export_parts.py emb nova work/out_v73`, `export_parts.py te nova work` (then copy `work/te_proj.bin` to `work/out_v73/`),
   `export_parts.py vae nova work 128 ref_a/latent.npy` (it prints the VAE peak activations: above 65504 the fp16 VAE overflows), then in `work/`:
   `bash <repo>/sdxl/build.sh fp16 te_l`, `... fp16 te_g`, `... fp16 vae`. Copy `te_l.bin te_g.bin vae.bin emb.bin te_proj.bin` to `/data/local/tmp/sdxl/m/`.
4. `bash test_unet.sh 10 1`: full UNet on the phone vs PC eps for step 10 (positive prompt). Expect cos ≈ 1 and ~4–5 s per call.
5. Then `sdxlrun ... m te`, `... m vae`, `... m gen` with ids from the PC tokenizer (`cond_a.npz` has `ids1`/`ids2`, order [negative, positive]),
   `ref_a/x_T.npy`, `sigmas.npy`, `timesteps.npy` as fp32 raw files. Compare the picture with `ref_a/ref.png`, log s/step and battery temperature
   (`adb shell dumpsys battery`).
6. Speed-up for 8 steps at CFG 1: merge a DMD2 or Lightning LoRA (download needs the user's approval first).
