<h1 align="center">HexAnima</h1>
<p align="center"><b>Anime image generation that runs entirely on your phone's NPU.</b><br>
No root, no cloud, no account, no ads. Free and non-commercial.</p>

<p align="center">
  <img src="docs/screens/01-generate.jpg" width="240" alt="Generating a picture">
  <img src="docs/screens/02-progress.jpg" width="240" alt="Progress and stop button">
  <img src="docs/screens/08-history.jpg" width="240" alt="History">
</p>

HexAnima runs the [Anima](https://huggingface.co/circlestone-labs/Anima) diffusion model and fine-tunes of it on the Hexagon NPU of Snapdragon 8 Gen 1 / 8+ Gen 1 phones.
The diffusion transformer is executed by custom HMX kernels; the text encoder and the sampler run on the CPU.
A 512x768 picture takes about **7 s** with the 2B models and **12 s** with the 2.9B models (4 steps, Xiaomi 12T Pro).

## Features

- Fully offline after the models are downloaded; nothing leaves the phone.
- Four MiaoMiao models (2B fp16 and 2.9B q8) with a one-tap model manager: resumable downloads, sha256 verification, delete to free space.
- Size picker with portrait / landscape / square presets (all multiples of 16) and custom sizes, seed, batch of several pictures, stop button.
- History with favourites, trash, multi-select, share and save; settings are stored in the PNG, so any picture can be reopened with its prompt and seed.
- Prompt tag suggestions (Danbooru tags), templates, quality-tag prefix.
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
<td align="center"><img src="docs/screens/13-info.jpg" width="220"><br><b>Info</b><br><sub>Why 4 steps, which sizes work, licenses</sub></td>
<td align="center"><img src="docs/screens/14-light.jpg" width="220"><br><b>Light theme</b><br><sub>Light, dark or follow the system</sub></td>
<td align="center"><img src="docs/screens/15-languages.jpg" width="220"><br><b>13 languages</b><br><sub>Chosen in Settings</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screens/16-trash.jpg" width="220"><br><b>Trash</b><br><sub>Deleted pictures stay 30 days, restore any time</sub></td>
<td></td><td></td>
</tr>
</table>

## Requirements

- A phone with **Snapdragon 8 Gen 1 or 8+ Gen 1** (SM8450 / SM8475, Hexagon V69): the tested target.
- **Snapdragon 8 Elite** (SM8750, Hexagon V79, e.g. Galaxy S25): not supported. Versions 1.2.0 and 1.2.1 had experimental support, withdrawn in 1.2.2: on a tester's Galaxy S25 the NPU matrix unit is sometimes silently unavailable after an interrupted run (garbage pictures) and large sizes come out as noise. A proper port is not planned in the short term.
- Other chips (Snapdragon 8 Gen 2 / 8 Gen 3, SM8550 / SM8650) are not supported yet; the app tells you if yours is not.
- Android 10 or newer, 64-bit. No root needed.
- RAM: tested on a 12 GB phone (about 10.9 GB usable). Phones with less RAM are untested; the 2.9B models need the most. Storage: 1.2 GB for the shared files plus 3.6-4.2 GB per model.

## Install

1. Download `HexAnima-<version>.apk` from the [Releases](../../releases) page and install it (allow installs from your browser or file manager).
2. Open the app and pick a model in **Models**. The files are downloaded from Hugging Face (`ayanoby/anima-npu-packs`) the first time.
3. Type a prompt (Danbooru-style tags work best), press **Generate**.

Tips: the models are distilled for 4 steps and use no negative prompt; see **Info** in the app for the other notes.

## Disclaimer

**HexAnima is an independent, unofficial project. It is not affiliated with, endorsed by, sponsored by or otherwise connected to CircleStone Labs, NVIDIA, Qualcomm, Alibaba (Qwen) or the authors of the models it can download.**
"Anima" and "Cosmos" refer to third-party models, "NVIDIA" and "Qualcomm", "Snapdragon", "Hexagon" are trademarks of their respective owners.
They are used only to describe what the software works with.
The models (MiaoMiao Harem / 3D Harem / RealSkin by MIAOKA, based on Anima by CircleStone Labs and NVIDIA Cosmos) stay under their authors' licenses: non-commercial use only.
The models are made with a turbo (distillation) LoRA merged in: Anima Turbo v1.1 Lora by somedoby for the 2B models and TURBO for ANIMA (V4-29B) by n_Arno for the 2.9B models (both on Civitai). See `THIRD_PARTY.md`.
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
