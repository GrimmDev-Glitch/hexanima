# LTX-Video on the NPU (prototype)

Text to video with **LTX-Video 2B 0.9.8 distilled** on the Snapdragon 8 Gen 2 (Hexagon V73): 512×320, 25 frames (≈1 s at 24 fps), 8 steps.

| Piece | Where | What |
|---|---|---|
| `t5enc/` | CPU (ggml) | prompt → T5-XXL states (128 × 4096) and mask, with stable-diffusion.cpp's T5 and the `t5xxl_fp16-q4_0.gguf` from `calcuis/ltxv-gguf`. Build: `t5enc/build.sh` (needs a sd.cpp tree with `ggml/`, e.g. `SD_SRC=~/sd-src-build`). |
| `ltxrun/` | NPU (QNN) | 8 flow-matching Euler steps through the 4 transformer contexts, then the VAE decoder context; writes RGB24 frames. Own runner, built with the QAIRT 2.28 headers, run with the **QAIRT 2.50** libraries the contexts need (`com.qualcomm.qti:qnn-runtime:2.50.0`). Build: `ltxrun/build.sh`. |

Context binaries: [timmyboy26/LTX-Video-2B-0.9.8-distilled-QNN](https://huggingface.co/timmyboy26/LTX-Video-2B-0.9.8-distilled-QNN), folder `v73/` (LTXV Open Weights License). The VAE latent statistics (128 means + 128 stds) come from `ltxv_0.9.5_vae_fp16.safetensors` (`per_channel_statistics.mean-of-means` / `std-of-means`).

## Run on the phone (ADB)

```
cd /data/local/tmp/ltx
printf "a prompt\nq\n" | ./t5enc t5xxl_fp16-q4_0.gguf p 6
export LD_LIBRARY_PATH=lib:/system/lib64:/vendor/lib64 ADSP_LIBRARY_PATH="/data/local/tmp/ltx/lib;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp"
echo "gen p.emb p.mask 42 out.rgb" | ./ltxrun lib/libQnnHtp.so lib/libQnnSystem.so v73 vae_stats.bin
ffmpeg -f rawvideo -pix_fmt rgb24 -s 512x320 -r 24 -i out.rgb -c:v libx264 -pix_fmt yuv420p out.mp4
```

## Measured on the Poco F6 Pro (15 nov 2026)

| Part | Time |
|---|---|
| T5 on the CPU, 6 threads (includes reading the 2.9 GB model) | 11.1 s |
| Loading the 4 transformer contexts | 3.8 s |
| 8 steps on the NPU | 9.2 s (1.15 s per step) |
| VAE decoder on the NPU (load 1.9 s + run) | 4.0 s |
| **Whole clip, cold** | **30.6 s** |

Battery 35.1 °C after two clips. Samples in `samples/`.

## In the app

Menu ⋯ → Video (experimental) (`VideoActivity`, `Ltx.java`). The model downloads from Models (5.5 GB, V73 only).
The APK carries the QAIRT 2.50 runtime renamed (`libqnn250htp.so`, `libqnn250system.so`, `libqnn250v73stub.so`, `libqnn250v73skel.so`);
`Ltx.prepare` links the original names in `files/ltx/lib`. `libltxrun.so` loads the backend from a memfd (`LTX_MEMFD=1`): the HTP backend
looks for `libQnnHtpV73Stub.so` next to its own file, and in the native lib dir that is the 2.28 stub of the other models.
