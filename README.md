<h1 align="center">HexAnima · Snapdragon 8 Gen 2 fork</h1>
<p align="center"><b>Anime image generation that runs entirely on your phone's NPU, now also on the Snapdragon 8 Gen 2 (Hexagon V73).</b><br>
No root, no cloud, no account, no ads. Free and non-commercial.</p>

<p align="center">
  <img src="docs/screens/v73/01-generate.jpg" width="200" alt="A picture made on a Poco F6 Pro">
  <img src="docs/screens/v73/02-models.jpg" width="200" alt="Model picker">
  <img src="docs/screens/v73/03-loras.jpg" width="200" alt="LoRA picker with checkboxes">
  <img src="docs/screens/v73/05-light.jpg" width="200" alt="Light theme">
</p>

This is a fork of [ayanoby/hexanima](https://github.com/ayanoby/hexanima). It ports the NPU kernels to the **Snapdragon 8 Gen 2** (SM8550, Hexagon V73) and adds LoRAs from Civitai, an on-phone checkpoint converter, img2img, hires fix, ADetailer, AI upscaling, presets, a generation queue, a RAM widget and a new look.
It was built and tested on a **Poco F6 Pro (12 GB)**. A 512x768 picture takes about **7 s** with the 2B models and **10-12 s** with the 2.9B models (4 steps).
The Snapdragon 8 Gen 1 / 8+ Gen 1 (V69) path of the original app is unchanged.

**Download:** the APK is on the [Releases](../../releases) page. The V73 text-encoder and VAE files download from [GrimmDev-Glitch/hexanima-packs](https://huggingface.co/GrimmDev-Glitch/hexanima-packs) the first time; the models come from the original packs repository.

> *En español:* fork de HexAnima para el **Snapdragon 8 Gen 2** (probado en un Poco F6 Pro). Añade LoRAs de Civitai, conversor de checkpoints Anima en el teléfono, img2img, hires fix, ADetailer, reescalado con IA, presets, cola, widget de RAM y un tema nuevo claro/oscuro. La app está en español e inglés. Descarga el APK en [Releases](../../releases).

## What this fork adds

| | |
|---|---|
| **Snapdragon 8 Gen 2 (V73)** | HMX kernels rebuilt for V73 (results go through the convert unit with a scale/bias block in VTCM, see `hexagon/mega/hmxacc.S`), V73 QNN contexts for the text encoder (512 tokens) and the VAE. |
| **LoRAs** | `<lora:name:weight>` in the prompt, several at once. A LoRA picker with checkboxes, a filter and a weight per LoRA: ticking adds the tag and its trigger words, unticking removes them. 2B LoRAs work on the 2.9B models (blocks remapped) and the other way round. |
| **Civitai browser** | Search Anima LoRAs and checkpoints, filter by type and sort, optional API key for 18+ content (sent only to civitai.com). LoRAs go straight into the library; checkpoints are converted on the phone. |
| **Checkpoint converter** | Any Anima `.safetensors` checkpoint (BF16 / F16 / F32) becomes an NPU model on the phone: fp16 for 2B, q8 for 2.9B. Byte-identical to the desktop export. |
| **Sampling** | Steps, CFG, negative prompt (with *Clear* / *Default*), 21 samplers, 9 schedulers. Values are saved per model; turbo models default to 4 steps / CFG 1, other models to 20 steps / DPM++ 2M / CFG 4. |
| **Extras** | img2img with a strength slider, hires fix (second pass on the NPU), AI upscale ×2 (RealESRGAN anime, ~80 s), ADetailer (face detector downloads on first use), presets, a generation queue. |
| **Memory and heat guards** | Checks free RAM before loading, skips heavy extras when memory is low, pauses between pictures when the phone is hot. |
| **RAM widget** | Home-screen widget: free RAM, model state, *Unload* and *Free RAM*. |
| **New look** | Lavender light and dark palettes, violet-to-pink accents, folding sections with one-line summaries, model cards with 2B / 2.9B / Turbo / speed badges, Barlow Semi Condensed font (SIL OFL). |
| **Languages** | Spanish and English. |

<p align="center">
  <img src="docs/screens/v73/04-extras.jpg" width="220" alt="Extras: hires fix, upscale, ADetailer, img2img, presets, queue">
</p>

---

*The rest of this page is the original README, with the requirements updated for the 8 Gen 2.*

## About HexAnima

HexAnima runs the [Anima](https://huggingface.co/circlestone-labs/Anima) diffusion model and fine-tunes of it on the Hexagon NPU of Snapdragon 8 Gen 1 / 8+ Gen 1 phones.
The diffusion transformer is executed by custom HMX kernels; the text encoder and the sampler run on the CPU.
A 512x768 picture takes about **7 s** with the 2B models and **12 s** with the 2.9B models (4 steps, Xiaomi 12T Pro).

It can also run **MOSS-Image 5B** (OpenMOSS, Apache-2.0), a larger model that you prompt with plain sentences. Its text encoder (Qwen3.5-9B), the transformer and the decoder all run on the NPU:
about 30 s for 512x768 and about 80 s for 1 Mpx; any size with sides that are multiples of 16 (up to 6144 patches). It is an optional ~14 GB download.

## Features

- Fully offline after the models are downloaded; nothing leaves the phone.
- Four MiaoMiao models (2B fp16 and 2.9B q8) with a one-tap model manager: resumable downloads, sha256 verification, delete to free space.
- Size picker with portrait / landscape / square presets (all multiples of 16) and custom sizes, seed, batch of several pictures, stop button.
- History with favourites, trash, multi-select, share and save; settings are stored in the PNG, so any picture can be reopened with its prompt and seed.
- Prompt tag suggestions (Danbooru tags), templates, quality-tag prefix (Anima models only; MOSS-Image takes plain sentences and gets no tags).
- Your own LoRA for MOSS-Image: pick a `.safetensors` file, the app checks it at once (layer names, shapes, rank, data type) and says why if it does not fit; a strength slider (0-2). The LoRA is folded with the built-in 4-step adapter on the phone (about 15 s the first time) and never leaves the device.
- Sampling settings for the Anima models (Settings): steps, CFG with a negative prompt, sampler (Euler, DPM++ 2M, DPM++ 2M SDE, ...) and scheduler (discrete, SGM Uniform, Karras, ...). The defaults (4 steps, Euler, CFG 1) are what the turbo models are made for; CFG above 1 doubles the time.
- Your own LoRA for the Anima 2B models (kohya `lora_unet_blocks_*` files from Civitai): folded into the block weights when the model loads (a few seconds, nothing extra on storage), strength slider. Only the DiT part is applied (the text encoder is a precompiled NPU graph); not for the 2.9B models.
- Live RAM meter, thermal / battery warnings, the model unloads itself when idle.
- Light / dark / system themes, notifications, 13 languages (English, Russian, Chinese, Japanese, Korean, Spanish, Portuguese, German, French, Indonesian, Turkish, Vietnamese, Hindi).

## Picture quality

Straight from the phone, 512x768, 4 steps, no upscaling, no editing (screenshots of the app; the date and battery are cropped out).

<p align="center">
  <img src="docs/screens/quality/3d-harem-2b.jpg" width="190" alt="MiaoMiao 3D Harem 2B">
  <img src="docs/screens/quality/realskin-2b.jpg" width="190" alt="MiaoMiao RealSkin 2B">
  <img src="docs/screens/quality/harem-2.9b.jpg" width="190" alt="MiaoMiao Harem 2.9B">
  <img src="docs/screens/quality/realskin-2.9b.jpg" width="190" alt="MiaoMiao RealSkin 2.9B">
</p>
<p align="center"><sub>3D Harem LH3D 1.1 (2B, ~7 s) · RealSkin Anima 1.3 (2B, ~7 s) · Harem 2.9B BETA 1.1 (~12 s) · RealSkin 2.9B 1.0 (~12 s)</sub></p>

### MOSS-Image

Four different prompts, 768x1024, 4 steps, about 60 s each, straight from the phone (same method as above, nothing retouched). Text in the picture is a strength of MOSS-Image but not a guarantee:
short words usually come out right, longer or secondary text (shop-front boards, book covers, small signs) is often garbled. The pictures below are the first and only attempt for each prompt, bad text left as it is.

<p align="center">
  <img src="docs/screens/quality/moss-1.jpg" width="190" alt="MOSS-Image: coffee shop with a neon sign">
  <img src="docs/screens/quality/moss-2.jpg" width="190" alt="MOSS-Image: fox reading a book, poster title">
  <img src="docs/screens/quality/moss-3.jpg" width="190" alt="MOSS-Image: girl holding a board with text">
  <img src="docs/screens/quality/moss-4.jpg" width="190" alt="MOSS-Image: arcade machine, GAME OVER screen">
</p>
<p align="center"><sub>"neon sign that says OPEN LATE" (the sign is right, the boards are not) · "title at the top says THE LAST LIBRARY" (title right, subtitle garbled) · "board that says HEXANIMA" (right) · "the words GAME OVER in pixel font" (readable, with small glitches)</sub></p>

**Your own LoRA.** Pick a `.safetensors` file made for MOSS-Image; the app checks it at once (layer names, shapes, rank, data type) and tells you why if it does not fit (a FLUX or SDXL LoRA, a wrong shape, a rank above 128, a text-encoder-only file...). A strength slider goes from 0 to 2.
The LoRA is folded with the built-in 4-step adapter on the phone (about 15 s the first time for a new LoRA or strength, then cached) and never leaves the device. Left: without a LoRA, right: with a third-party style LoRA from Civitai (same prompt, seed and size; LoRAs stay under their authors' licenses).

<p align="center"><img src="docs/screens/quality/moss-lora-compare.jpg" width="640" alt="Without and with a LoRA"></p>

Limits worth knowing: MOSS-Image only does text-to-image (no editing or image input), it needs about 14 GB of storage and was tested on a 12 GB phone only, and its weights are streamed from storage for every picture, so a picture takes 30 s at 512x768 and about 80 s at 1 Mpx.

## A tour of the app

<table>
<tr>
<td align="center" width="33%"><img src="docs/screens/03-suggestions.jpg" width="220"><br><b>Tag suggestions</b><br><sub>Danbooru tags complete as you type</sub></td>
<td align="center" width="33%"><img src="docs/screens/04-templates.jpg" width="220"><br><b>Templates</b><br><sub>Recent and saved prompts</sub></td>
<td align="center" width="33%"><img src="docs/screens/05-size.jpg" width="220"><br><b>Size picker</b><br><sub>Portrait, landscape, square, custom</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screens/06-model-picker.jpg" width="220"><br><b>Model picker</b><br><sub>Switch models in one tap</sub></td>
<td align="center"><img src="docs/screens/07-model-manager.jpg" width="220"><br><b>Model manager</b><br><sub>Size, speed, download and delete</sub></td>
<td align="center"><img src="docs/screens/10-viewer.jpg" width="220"><br><b>Picture details</b><br><sub>Prompt, seed, size, model; reuse the settings</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screens/09-select.jpg" width="220"><br><b>Multi-select</b><br><sub>Favourite, save, share or trash many at once</sub></td>
<td align="center"><img src="docs/screens/11-favourites.jpg" width="220"><br><b>Favourites</b><br><sub>Starred pictures in one place</sub></td>
<td align="center"><img src="docs/screens/12-settings.jpg" width="220"><br><b>Settings</b><br><sub>Language, theme, idle unload, quality tags</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screens/13-info.jpg" width="220"><br><b>Info</b><br><sub>Why 4 steps, which sizes work, MOSS-Image and LoRA notes, licenses</sub></td>
<td align="center"><img src="docs/screens/14-light.jpg" width="220"><br><b>Light theme</b><br><sub>Light, dark or follow the system</sub></td>
<td align="center"><img src="docs/screens/15-languages.jpg" width="220"><br><b>13 languages</b><br><sub>Chosen in Settings</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screens/16-trash.jpg" width="220"><br><b>Trash</b><br><sub>Deleted pictures stay 30 days, restore any time</sub></td>
<td align="center"><img src="docs/screens/17-moss.jpg" width="220"><br><b>MOSS-Image</b><br><sub>Plain-sentence prompts, any size</sub></td>
<td align="center"><img src="docs/screens/18-lora.jpg" width="220"><br><b>Your LoRA</b><br><sub>Checked at once, strength slider, prepared on the phone</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screens/19-moss-models.jpg" width="220"><br><b>Five models</b><br><sub>MOSS-Image next to the four Anima models</sub></td>
<td align="center"><img src="docs/screens/20-info-moss.jpg" width="220"><br><b>Info: MOSS-Image</b><br><sub>Sizes, LoRA, licenses</sub></td>
<td></td>
</tr>
</table>

## Requirements

- A phone with **Snapdragon 8 Gen 1 or 8+ Gen 1** (SM8450 / SM8475, Hexagon V69): the tested target.
- **Snapdragon 8 Elite** (SM8750, Hexagon V79, e.g. Galaxy S25): not supported. Versions 1.2.0 and 1.2.1 had experimental support, withdrawn in 1.2.2: on a tester's Galaxy S25 the NPU matrix unit is sometimes silently unavailable after an interrupted run (garbage pictures) and large sizes come out as noise. A proper port is not planned in the short term.
- **Snapdragon 8 Gen 2** (SM8550, Hexagon V73): supported by this fork, tested on a Poco F6 Pro (12 GB): 512x768 in about 7 s with the 2B models and 10-12 s with the 2.9B models, 1024x1024 in about 17 s. The DiT kernels have their own V73 build (V73 stores HMX results through the convert unit, see `hexagon/mega/hmxacc.S`); the V73 text-encoder and VAE contexts download from `GrimmDev-Glitch/hexanima-packs`. MOSS-Image does not run on it.
- Other chips (Snapdragon 8 Gen 3, SM8650) are not supported yet; the app tells you if yours is not.
- Android 10 or newer, 64-bit. No root needed.
- RAM: tested on a 12 GB phone (about 10.9 GB usable). Phones with less RAM are untested; the 2.9B models need the most. Storage: 1.2 GB for the shared files plus 3.6-4.2 GB per Anima model; MOSS-Image needs about 14 GB (and ~300 MB more while a user LoRA is in use). MOSS-Image was tested on a 12 GB phone only.

## Install

1. Download `HexAnima-<version>.apk` from the [Releases](../../releases) page of this fork and install it (allow installs from your browser or file manager).
2. Open the app and pick a model in **Models**. The files are downloaded from Hugging Face (`ayanoby/anima-npu-packs`) the first time.
3. Type a prompt (Danbooru-style tags work best), press **Generate**.

MOSS-Image is a separate download in the same manager (it lives in its own Hugging Face repository with its own `LICENSE` and `NOTICE.md`).

Tips: the models are distilled for 4 steps and use no negative prompt; see **Info** in the app for the other notes.

## Disclaimer

**HexAnima is an independent, unofficial project. It is not affiliated with, endorsed by, sponsored by or otherwise connected to CircleStone Labs, NVIDIA, Qualcomm, Alibaba (Qwen), OpenMOSS, Unsloth, Black Forest Labs or the authors of the models it can download.**
"Anima" and "Cosmos" refer to third-party models, "NVIDIA" and "Qualcomm", "Snapdragon", "Hexagon" are trademarks of their respective owners.
They are used only to describe what the software works with.
The models (MiaoMiao Harem / 3D Harem / RealSkin by MIAOKA, based on Anima by CircleStone Labs and NVIDIA Cosmos) stay under their authors' licenses: non-commercial use only.
The models are made with a turbo (distillation) LoRA merged in: Anima Turbo v1.1 Lora by somedoby for the 2B models and TURBO for ANIMA (V4-29B) by n_Arno for the 2.9B models (both on Civitai). See `THIRD_PARTY.md`.
MOSS-Image, Qwen3.5-9B, the rapid-moss 4-step adapter (darask0, on lodestones' 8-step DMD), the FLUX.2 small decoder (Black Forest Labs) and the Unsloth GGUF quantisations are all under the **Apache License 2.0**; the MOSS-Image pack holds converted copies (the changes are listed in its `NOTICE.md`, also shown in the app under Info -> Licenses).
LoRA files that you load yourself stay under their authors' licenses; check them before you share pictures made with them.
A model author can ask for removal of their model from the download catalog: open a discussion in the Community tab of the model packs repository.

## Third-party components

The APK includes the Qualcomm AI Engine Direct (QNN) runtime libraries (`libQnnHtp*.so`, `libQnnSystem.so`) under the Qualcomm AI Stack License.
They are redistributed only in object code and only as part of the app, never standalone; the license text and the third-party notices are in `app/assets/licenses/` and in the app (Info -> Licenses).
Qualcomm may terminate that license at any time, in which case distribution of the APK stops.

## Layout

- `app/`: the Android app (plain Java, built without Gradle by `app/build.sh`), package `com.hexanima.npu`. Languages in `app/assets/lang/`.
- `phone/`: the on-device server (`animad.cpp`) and `anima.sh`, which the app runs.
- `chainrun/`, `hexagon/mega/`: helper processes and the Hexagon HMX kernels (see `THIRD_PARTY.md` for the Qualcomm files you have to supply).
- `patches/`: patches for stable-diffusion.cpp (NPU/TE/VAE hooks, serve mode); `android/build_sd.sh` builds it.
- `export/`, `tools/`: model conversion (`make_pack.py` builds the downloadable packs), translations (`build_lang.py`, `lang/`), `stage_jnilibs.sh`.
- `docs/PITFALLS.md`: engineering notes on the NPU work.

## Build

You need the Android SDK (build-tools 34, platform 34), Docker, the Qualcomm QNN SDK (download it from Qualcomm and accept its license) and, for the DSP kernels, the Hexagon SDK.
Paths in the scripts default to `~/anima-npu/sdk/...`. The order is: build the native helpers (`phone/build.sh`, `chainrun/build.sh`, `hexagon/mega/build*.sh`, `android/build_sd.sh`),
copy them with `tools/stage_jnilibs.sh`, then `app/build.sh` produces and installs `hexanima.apk`.
The model packs are built by `tools/make_pack.py` and downloaded by the app from a Hugging Face repository (set in `Main.DEFAULT_BASE`).

## License

The HexAnima source code is MIT (`LICENSE`). Models, QNN and the other components keep their own licenses: see `THIRD_PARTY.md`.
The app shows all required license texts and notices (Info -> Licenses): Qualcomm AI Stack License, QNN notices, Apache License 2.0 and the MOSS-Image notice; the files are in `app/assets/licenses/`.
