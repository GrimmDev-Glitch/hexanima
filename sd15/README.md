# SD 1.5 on the V73 NPU (paused, 15 nov 2026)

Own runner (`sd15run.cpp`, build with `build.sh`) for the prebuilt 8 Gen 2 packs of [xororz/sd-qnn](https://huggingface.co/xororz/sd-qnn)
(`<Model>_qnn2.28_8gen2.zip`, ~1 GB each: Absolute Reality, Anything V5, MeinaMix V12, DreamShaper V8… — the same list LLM Hub shows).
local-dream's app code is CC BY-NC 4.0, so none of it is used; only the packs' file format was studied.

## Pack contents (checked)
- `unet.bin` (QNN 2.28 context, W8A16): inputs `sample` [1,4,64,64] uint16, `timestamp` [1] int32, `text_embedding` [1,77,768] uint16; output [1,4,64,64] uint16 (epsilon).
- `vae_decoder.bin` / `vae_encoder.bin` (QNN): latent ÷ 0.18215 → image in [-1,1]; encoder gives `mean`/`std`.
- `clip_v2.mnn` (MNN, CPU): input `input_embedding` = `token_emb[id] + pos_emb[pos]` (the graph adds no position itself; tested), output
  `last_hidden_state`. It matches CLIP layer 11 + final LN (clip skip 2) of the model's own text encoder (cos 0.93 vs SD 1.5's, range = the UNet's calibration).
- `token_emb.bin` / `pos_emb.bin`: fp32, identical to SD 1.5 CLIP. `768.patch` / `1024.patch`: zstd patches with `unet.bin` as dictionary.
- MNN 3.6.1 for Android (Apache-2.0) runs the CLIP; libMNN.so + libc++_shared.so next to the runner.

## What works
- Loading (2 s), CLIP 0.16 s, UNet 0.25 s per call, VAE 0.28 s: a 20-step CFG picture would take ~5.4 s.
- VAE round trip (`rt` command) gives back the picture exactly → quantization helpers and NCHW layout are right.

## Where it is stuck
Full generation gives coloured noise with vertical stripes. The one-step test (`dn` command: real picture latent + noise, one UNet call) shows the UNet
output correlates with the true noise (0.95–0.99) but is only ~0.73 of its size at every noise level, and almost ignores the timestep and the text
(even an all-zero text embedding changes nothing). Ruled out: input order, NHWC/transposed layouts, timestep as float, input scaling (c_in), CLIP precision,
quantization formula (same as the QNN SDK floatToTfN). Next ideas: check the QNN context config local-dream uses (multi-context spill-fill, graph
options), compare with an x86 run of an fp32 SD 1.5 UNet on the same inputs, and try another pack (AnythingV5) to rule out the file.

Debug env vars in `sd15run`: SD_T, SD_TFLOAT, SD_NHWC, SD_INHWC, SD_ONHWC, SD_TRHW, SD_NOSCALE, SD_TT, SD_TZERO, SD_ORDER, SD_EPSMUL, SD_DUMP_CLIP, SD_DUMP_EPS, SD_CLIP_HIGH/LOW.
Phone test folder: /data/local/tmp/sd15 (runner, libs, MeinaMix model, test ids).
