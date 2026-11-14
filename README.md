<h1 align="center">HexAnima · Snapdragon 8 Gen 2</h1>
<p align="center"><b>Anime pictures made entirely on your phone's NPU.</b> No root, no cloud, no account, no ads.<br>
A fork of <a href="https://github.com/ayanoby/hexanima"><b>HexAnima by ayanoby</b></a>, ported to the Snapdragon 8 Gen 2 and extended.</p>

<p align="center">
  <img src="docs/screens/v73/01-generate.jpg" width="200" alt="A finished picture">
  <img src="docs/screens/v73/02-preview.jpg" width="200" alt="Anima live preview while generating">
  <img src="docs/screens/v73/04-loras.jpg" width="200" alt="LoRA picker">
  <img src="docs/screens/v73/03-models.jpg" width="200" alt="Model picker">
</p>

> **All credit for HexAnima goes to [ayanoby](https://github.com/ayanoby).** The NPU kernels, the on-device server, the app and the NPU model packs are their work.
> This fork ports it to the Snapdragon 8 Gen 2 (Hexagon V73) and adds the features listed below. Thank you, ayanoby!

## Download

Get `HexAnima-<version>-v73.apk` from [Releases](../../releases) and install it. If the original HexAnima is installed, uninstall it first: the two are signed with different keys.
Open the app, pick a model, write a prompt and press **Generate**. The models download inside the app (from ayanoby's packs); the V73 text-encoder and VAE files come from [GrimmDev-Glitch/hexanima-packs](https://huggingface.co/GrimmDev-Glitch/hexanima-packs).

*En español:* descarga el APK en [Releases](../../releases), desinstala antes el HexAnima original si lo tienes, abre la app, elige un modelo y genera. La app está en español e inglés.

## Speed (Poco F6 Pro, 512×768)

| Model | Time |
|---|---|
| 2B turbo, 4 steps | ~7 s |
| 2.9B turbo, 4 steps | ~10-12 s |
| 2B non-turbo, 20 steps, CFG 4 (40 NPU calls) | ~85 s |

Times at full speed. Cool mode runs the NPU at a lower clock: about 17 % slower, with the CPU around 60 °C instead of 90 °C on long runs. By default it is automatic: full speed while the phone is cool, the lower clock once the battery reaches 40 °C (43 °C while charging).

## What this fork adds

| | |
|---|---|
| **Snapdragon 8 Gen 2** | NPU kernels rebuilt for Hexagon V73; text encoder (512 tokens) and VAE compiled for V73. |
| **LoRAs** | Anima supports kohya, diffusers / ComfyUI, LyCORIS LoKr and LoHa, including adaLN, LLM and text-encoder adapters. MOSS accepts A/B adapters (folded into the turbo slots) and full 4×4 LoKr adapters, baked into a copy of the DiT blocks on the phone (about 20 s and 5.7 GB, only the last one kept). Qwen LoRAs (.safetensors, also from Civitai) are converted on the phone in a few seconds, with a strength slider; V73 overlay ZIPs still import. Selected Anima LoRAs insert their trigger tags into the prompt. 2B LoRAs also work on 2.9B Anima models. Merged Anima blocks are kept as q8 and cached. |
| **Civitai** | Search and download Anima LoRAs/checkpoints and compatible MOSS A/B LoRAs. Qwen 2.1 Safetensors and metadata download to `Downloads/HexAnima/Civitai`; convert them on the PC into a V73 overlay ZIP, then import and select the ZIP in the app. This full Qwen route was exercised with a downloaded LoRA and a phone generation. Raw Civitai files cannot run directly. The MOSS library shows trigger words and selects one validated adapter at a time. Optional API key for 18+ (sent only to civitai.com); model and account links open on civitai.red. |
| **Your own checkpoints** | Any Anima `.safetensors` checkpoint becomes an NPU model on the phone. |
| **Live preview** | Available for Anima. MOSS and Qwen show the finished image after decoding, avoiding the extra VAE work between steps. |
| **Prompt tools** | Tag palette (quality, framing, lighting, style, expression, background), `( − ) ( + )` for `(tag:1.2)` weights, a negative prompt with Clear and Default. |
| **Sampling controls** | Anima exposes steps, CFG, samplers and schedulers per model. Base MOSS exposes 1–4 steps; RealSkin MOSS also offers the optional 8-step DMD add-on. CFG and alternate samplers are not implemented by the MOSS runner. Qwen uses its fixed 4-step pipeline without sampling controls. |
| **Other model families** | Mage-Flow-Edit-Turbo has an experimental app port and its 8.71 GB pack is installed in the Dev profile. On the Poco F6 Pro its first generation stalled in the text-encoder FastRPC call for 120 s, so Mage is hidden from the model selector until that V73 path can generate successfully. Photo editing has not been verified. See the [Mage V73 port assessment](docs/MAGE_V73_PORT.md). |
| **Extras** | img2img, **inpainting** (paint a mask), hires fix on the NPU, ADetailer for faces, presets, a queue, a **2×2 comparison grid** (seeds, CFG or steps). A regular batch can be extended while running, up to 999 images; every result is saved and a contact grid is created for swiping through the batch. |
| **History** | Filter by model, LoRA or prompt words; *Reuse settings* brings back the sampling too. |
| **Heat and memory** | Automatic cool mode, a free-RAM check before loading, and batches that wait for the phone to cool down. A home-screen widget shows free RAM and frees it. |
| **Look** | Lavender light / dark themes, Barlow Semi Condensed font, folding sections. Spanish and English. |

## Screens

<table>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/05-tags.jpg" width="220"><br><b>Tag palette</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/06-sampling.jpg" width="220"><br><b>Sampling</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/07-extras.jpg" width="220"><br><b>Extras</b></td>
</tr>
<tr>
<td align="center"><img src="docs/screens/v73/08-mask.jpg" width="220"><br><b>Inpainting mask</b></td>
<td align="center"><img src="docs/screens/v73/09-grid.jpg" width="220"><br><b>Comparison grid</b></td>
<td align="center"><img src="docs/screens/v73/10-history.jpg" width="220"><br><b>History with filters</b></td>
</tr>
<tr>
<td align="center"><img src="docs/screens/v73/11-civitai.jpg" width="220"><br><b>Civitai</b></td>
<td align="center"><img src="docs/screens/v73/12-settings.jpg" width="220"><br><b>Settings · cool mode</b></td>
<td align="center"><img src="docs/screens/v73/13-light.jpg" width="220"><br><b>Light theme</b></td>
</tr>
<tr>
<td align="center"><img src="docs/screens/v73/14-info.jpg" width="220"><br><b>Info · credits to ayanoby</b></td>
<td align="center"><img src="docs/screens/v73/15-cool-mode.jpg" width="220"><br><b>Cool mode: Automatic / Always / Never</b></td>
<td align="center"><img src="docs/screens/v73/17-lora-cache.jpg" width="220"><br><b>LoRA library · q8 cache size</b></td>
</tr>
<tr>
<td align="center" colspan="3"><img src="docs/screens/v73/16-lora-text-encoder.jpg" width="440"><br><b>A LoRA with text-encoder weights: applied, the prompt is encoded on the CPU</b></td>
</tr>
</table>

## Requirements

- **Snapdragon 8 Gen 2** (SM8550), tested on a Poco F6 Pro with 12 GB. The Snapdragon 8 Gen 1 / 8+ Gen 1 path of the original app still works; other chips are not supported.
- Android 10 or newer, no root. Storage depends on the family: Anima packs are about 2.6–3.6 GB; MOSS-Image is 14.1 GB, with RealSkin MOSS 1.0 and Harem MOSS 1.1 adding about 6.3 GB each; the Qwen-Image 2.1 Turbo pack is about 14.95 GB.
- **MOSS-Image runs experimentally on the 8 Gen 2**; text-to-image and a short two-image batch were tested on a Poco F6 Pro. RealSkin MOSS 1.0 and Harem MOSS 1.1 both generated at 1, 2, and 4 steps; RealSkin also passed 3 steps. At 512×512, earlier runs measured 5–9 s at 1–2 steps and 15–19 s at 4 steps. An earlier build used VAE previews; the current app shows only the final MOSS image. One-step results are softer and less detailed. Android thermal status remained 0; during the Harem run the battery was 38.1 °C at completion and the skin sensor peaked at 45.0 °C. Long thermal runs remain unverified.
- RealSkin MOSS also supports the optional 8-step DMD add-on. HexAnima Dev downloaded `glue8.bin` and all 42 overlays on the Poco F6 Pro; the 512×768 test completed all eight steps in 58.0 s, with a 35.1 °C battery reading. Stopping a second run at step 2/8 returned the runner to ready. This is an initial short-run result, not a sustained thermal test. See `docs/screenshots/realskin-moss-dmd8-phone.png`.
- **Qwen-Image 2.1 Turbo has completed text-to-image and photo-edit runs on V73** (Poco F6 Pro, 512×768, four steps). HexAnima Dev downloaded the 694 MB V73 context companion from `GrimmDev-Glitch/hexanima-packs`; both files passed their expected SHA-256 checks. A fresh post-download run completed in 77.3 s with per-step previews in an earlier build; the current app shows only the final Qwen image. Battery temperature rose from 32.1 °C to 33.4 °C and 5.2 GB RAM remained free. A clean install and first launch also passed in Xiaomi XSpace. On 14 Nov 2026, a clean XSpace profile started downloading the 15.6 GB Qwen pack; the last confirmed screen showed 5.9 GB downloaded (`te/te27.bin`), 54% battery, 32.7 °C battery temperature, thermal status 0 and 70 GB free. The user then asked to stop; a tap on Cancelar was sent, but its completion was not verified. On resume, check the partial download and either resume or clean it up before testing generation in that profile. Long-run thermal behavior remains unverified.
- **Masked Qwen editing** was visually checked on the phone at 640×640: painting the hat on a generated cowboy image and requesting a red hat changed the painted area while the character and background looked unchanged. The edit used the detail crop and took 92.4 s; battery temperature rose from 40.0 °C to 41.9 °C, and Android reported thermal status 0. This is one short sample, not a guarantee that unmasked or broadly masked edits preserve fine details; long-run temperature tests remain open.

## Credits and thanks

- **[ayanoby](https://github.com/ayanoby)**: author of [HexAnima](https://github.com/ayanoby/hexanima), its Hexagon HMX kernels, the on-device server, the app and the [NPU model packs](https://huggingface.co/ayanoby/anima-npu-packs). This fork stands entirely on that work.
- **Models**: Anima by CircleStone Labs (on NVIDIA Cosmos); MiaoMiao Harem / 3D Harem / RealSkin by MIAOKA; turbo LoRAs by somedoby (2B) and n_Arno (2.9B). Non-commercial use only.
- **Software**: [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) (patched), Qualcomm QNN runtime, face_yolov8n (Bingsu/adetailer) for ADetailer, Barlow Semi Condensed font (SIL OFL).
- LoRAs you download stay under their authors' licenses.

## Disclaimer

HexAnima is an independent, unofficial project, not affiliated with CircleStone Labs, NVIDIA, Qualcomm, Alibaba (Qwen), OpenMOSS, Civitai or the model authors. "Snapdragon" and "Hexagon" are trademarks of Qualcomm.
The APK includes the Qualcomm AI Engine Direct (QNN) runtime libraries under the Qualcomm AI Stack License, redistributed only as part of the app; the license texts are in `app/assets/licenses/` and in the app (Info → Licenses).
A model author can ask for removal of their model from the download catalog.

## Build

Android SDK (build-tools 34), Docker, the Qualcomm QNN SDK (QAIRT 2.28) and the Hexagon SDK. Build the native parts (`phone/build.sh`, `chainrun/build.sh`, `hexagon/mega/build.sh` with `ARCH=v73` and `v69`, `hexagon/mega/build_megarun.sh`, `android/build_sd.sh`), stage them with `tools/stage_jnilibs.sh`, then run `app/build.sh` for a debuggable development APK.

For a non-debuggable release APK, provide a release keystore outside the repository and run `BUILD_TYPE=release RELEASE_KEYSTORE=/path/to/release.keystore RELEASE_KEY_ALIAS=... RELEASE_KEYSTORE_PASSWORD=... app/build.sh`. `RELEASE_KEY_PASSWORD` is optional and defaults to the keystore password. Release builds are written to `app/hexanima-release.apk` and are not installed automatically. Keep the keystore and passwords out of Git.

V73 QNN contexts: `tools/build_ctx.sh v73`. Engineering notes: `docs/PITFALLS.md`.

## License

Source code: MIT (`LICENSE`), as in the original project. Models, QNN and other components keep their own licenses: see `THIRD_PARTY.md`.
