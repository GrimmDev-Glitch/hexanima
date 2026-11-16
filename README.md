<h1 align="center">HexAnima · Snapdragon 8 Gen 2</h1>
<p align="center"><b>Pictures made entirely on your phone's NPU.</b> No root, no cloud, no account, no ads.<br>
A fork of <a href="https://github.com/ayanoby/hexanima"><b>HexAnima by ayanoby</b></a>, ported to the Snapdragon 8 Gen 2 and extended.</p>

<p align="center">
  <img src="docs/screens/v73/01-generate.jpg" width="200" alt="A finished Anima picture">
  <img src="docs/screens/v73/02-preview.jpg" width="200" alt="Anima live preview while generating">
  <img src="docs/screens/v73/23-qwen-refs-result.jpg" width="200" alt="Qwen: two reference pictures in one new scene">
  <img src="docs/screens/v73/38-corrections-zoom.jpg" width="200" alt="Corrections: a hand redrawn by Qwen, before and after">
</p>

> **All credit for HexAnima goes to [ayanoby](https://github.com/ayanoby).** The NPU kernels, the on-device server, the app and the NPU model packs are their work.
> This fork ports it to the Snapdragon 8 Gen 2 (Hexagon V73) and adds the features listed below. Thank you, ayanoby!

## Download

Get `HexAnima-<version>-v73.apk` from [Releases](../../releases) and install it. If the original HexAnima is installed, uninstall it first: the two are signed with different keys.
Open the app, pick a model, write a prompt and press **Generate**. The models download inside the app (from ayanoby's packs); the V73 text-encoder, VAE and Qwen files come from [GrimmDev-Glitch/hexanima-packs](https://huggingface.co/GrimmDev-Glitch/hexanima-packs).

*En español:* descarga el APK en [Releases](../../releases), desinstala antes el HexAnima original si lo tienes, abre la app, elige un modelo y genera. La app está en español e inglés.

## Four model families

| Family | What it does | Time on a Poco F6 Pro |
|---|---|---|
| **Anima** (MiaoMiao Harem, 3D Harem, your own checkpoints) | Anime and illustration from tags | 2B turbo ~7 s, 2.9B turbo ~10–12 s, 20-step base models ~85–100 s (512×768) |
| **MOSS-Image** (base 5B, RealSkin, Harem) | Realistic pictures from plain sentences, text in the picture | ~30–45 s at 512×768, 4 steps |
| **Qwen-Image 2.1 Turbo** | Text to image, and **editing your own photos with words**, with up to 3 reference pictures | ~45–55 s for a picture or an edit, ~70 s with two references |
| **Mage-Flow-Edit-Turbo** (new in 1.8) | Fast text to image and photo edits from plain sentences, with an area to edit | ~10 s at 512×512 |

Times at full speed. Cool mode runs the NPU at a lower clock: about 17 % slower, with the CPU around 60 °C instead of 90 °C on long runs. By default it is automatic: full speed while the phone is cool, the lower clock once the battery reaches 40 °C (43 °C while charging).

## What this fork adds

| | |
|---|---|
| **Snapdragon 8 Gen 2** | NPU kernels rebuilt for Hexagon V73; text encoders (512 tokens), VAEs and the MOSS and Qwen runners compiled for V73. |
| **Qwen photo editing** | Pick a photo and write what to change. The edit keeps the photo's shape (portrait, landscape or square) unless you turn that off. *Area to edit* limits the change to a painted zone with soft edges; *High fidelity* keeps more detail; a before/after slider compares the result and can save both side by side. |
| **Reference pictures** | Add up to 3 pictures: each one goes to Qwen as its own input (image 1, 2, 3). Write, for example, "the character of image 1 wearing the outfit of image 2" or "the girls from image 1 and image 2 hug under cherry trees". |
| **LoRAs, one panel for every family** | Library, Import and Civitai under every model. Anima supports kohya, diffusers / ComfyUI, LyCORIS LoKr and LoHa, including adaLN, LLM and text-encoder adapters; several LoRAs at once, each with its own strength. MOSS accepts A/B adapters and full 4×4 LoKr adapters (baked on the phone in about 20 s). Qwen LoRAs (.safetensors, also from Civitai) are converted on the phone in a few seconds, with a strength slider. Long-press a LoRA for its trigger words, category, to rename it (Qwen too, new in 1.8) or to delete it. |
| **Civitai** | Search and download LoRAs and checkpoints for Anima, MOSS and Qwen, filtered by category; a ✓ marks what you already have. Optional API key for 18+ (sent only to civitai.com). |
| **Enlarge on the NPU** | ×2 or ×4, from the picture or the History, saved as a new picture. **Fast** (Real-ESRGAN General x4v3): about 2 s for ×2 and 5 s for ×4 on a 512×768 picture. **Detailed** (4x-UltraSharp, new in 1.8): about 10 times slower, keeps textures, eyes and thin lines better (×2 of a 768×1024 picture in about 9 s). |
| **Corrections** | A screen to fix a finished picture without touching it: make alternatives from the accepted version, compare them with a slider (double-tap to zoom), keep one or discard it, undo / redo, and save the result as a new picture. **Noise** (cleans grain and artefacts at the same size, with a strength) and **Resolution** (×2 / ×4) run on the NPU in 1–5 s. **Face, Hands, Details and Other**: paint an area and Qwen-Image redraws only that area (about a minute; needs Qwen-Image installed). **Detect faces / hands automatically** (new in 1.8) paints the area for you: face_yolov8n and hand_yolov8n run on the NPU in 6 ms. |
| **Mage-Flow on the 8 Gen 2** (new in 1.8) | ayanoby's Mage-Flow kernels ported to Hexagon V73: a 512×512 picture in about 10 s. Photo edits keep the original (before/after slider, side by side), keep the photo's shape, and can be limited to a painted area: the rest keeps its exact pixels. |
| **ChatGPT style** (new in 1.8) | One tap in the Anima LoRA panel downloads the gpt-image-2 style LoRA (23 MB, checked by SHA-256) and adds its trigger and tag to the prompt; tap again to remove it. Qwen and MOSS can use the GPT-Image style LoRAs from Civitai too. |
| **Storage** (new in 1.8) | Settings → Storage: free space, the size of every model, cache and picture folder; free up caches, empty the trash and the before/after originals. |
| **Your own checkpoints** | Any Anima `.safetensors` checkpoint becomes an NPU model on the phone. |
| **Prompt tools** | Tag palette, `( − ) ( + )` for `(tag:1.2)` weights, the negative prompt right under the prompt, saved and recent prompts (Templates). |
| **Sampling controls** | Anima: steps, CFG, samplers and schedulers per model, right after the negative prompt. MOSS: 1–4 steps (RealSkin also the 8-step DMD add-on). Qwen: fixed 4 steps. |
| **Extras** | Live preview (Anima), img2img, **inpainting** with brush, eraser and move, hires fix on the NPU, ADetailer for faces, presets, a queue, a **2×2 comparison grid** (seeds, CFG or steps). Batches can be extended while running, up to 999 images, with a contact grid. |
| **History** | Filter by model, LoRA or prompt words; favourites, trash, a ZIP of everything; *Reuse settings* brings back the sampling too. |
| **Heat and memory** | Repeated prompts skip the text encoder (a batch, *again* and the negative prompt reuse it: 0.29 s → 0.001 s). Automatic cool mode, a free-RAM check before loading, and batches that wait for the phone to cool down. A home-screen widget shows free RAM and frees it. |
| **Look** | Lavender light / dark themes, your own wallpaper behind see-through blocks, Barlow Semi Condensed font, folding sections. Spanish and English. |

## Screens

<table>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/44-chatgpt-style-button.jpg" width="220"><br><b>New: one-tap ChatGPT style (Anima)</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/45-chatgpt-style-result.jpg" width="220"><br><b>Anima + gpt-image-2 LoRA, 14 s</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/49-moss-gpt-lora.jpg" width="220"><br><b>MOSS + "GPT Image 2 Girls" LoRA</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/46-enlarge-detailed.jpg" width="220"><br><b>New: Enlarge Fast or Detailed (UltraSharp)</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/47-detect-face.jpg" width="220"><br><b>New: face found and masked automatically</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/43-storage.jpg" width="220"><br><b>New: Storage manager</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/48-qwen-lora-rename.jpg" width="220"><br><b>New: rename Qwen LoRAs</b></td>
<!--MAGE-->
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/21-welcome.jpg" width="220"><br><b>Welcome · checks your phone</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/03-models.jpg" width="220"><br><b>Model picker</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/19-catalog.jpg" width="220"><br><b>Model catalog</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/06-sampling.jpg" width="220"><br><b>Prompt, negative prompt, sampling</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/05-tags.jpg" width="220"><br><b>Tag palette</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/04-loras.jpg" width="220"><br><b>LoRA library with categories</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/07-extras.jpg" width="220"><br><b>Extras</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/08-mask.jpg" width="220"><br><b>Inpainting mask</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/09-grid.jpg" width="220"><br><b>Comparison grid</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/24-qwen-edit.jpg" width="220"><br><b>Qwen: edit a photo</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/25-qwen-edit-result.jpg" width="220"><br><b>"Make it a snowy winter night"</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/22-qwen-refs.jpg" width="220"><br><b>Qwen: reference pictures</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/32-upscale-dialog.jpg" width="220"><br><b>Enlarge ×2 / ×4 on the NPU</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/36-corrections-mask.jpg" width="220"><br><b>Corrections: paint the area</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/38-corrections-zoom.jpg" width="220"><br><b>Corrections: hands redrawn, 3× zoom</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/37-corrections-hands-panel.jpg" width="220"><br><b>Corrections: alternatives, undo / redo</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/27-qwen-lora.jpg" width="220"><br><b>Qwen LoRA with strength</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/28-moss.jpg" width="220"><br><b>MOSS RealSkin</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/10-history.jpg" width="220"><br><b>History with filters</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/18-viewer.jpg" width="220"><br><b>Picture details</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/11-civitai.jpg" width="220"><br><b>Civitai</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/12-settings.jpg" width="220"><br><b>Settings</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/20-wallpaper.jpg" width="220"><br><b>Your own wallpaper</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/13-light.jpg" width="220"><br><b>Light theme</b></td>
</tr>
<tr>
<td align="center" width="33%"><img src="docs/screens/v73/15-cool-mode.jpg" width="220"><br><b>Cool mode</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/26-before-after.jpg" width="220"><br><b>Qwen edit: before / after slider</b></td>
<td align="center" width="33%"><img src="docs/screens/v73/14b-info-credits.jpg" width="220"><br><b>Info · credits to ayanoby</b></td>
</tr>
</table>

## Requirements

- **Snapdragon 8 Gen 2** (SM8550), tested on a Poco F6 Pro with 12 GB. The Snapdragon 8 Gen 1 / 8+ Gen 1 path of the original app still works; other chips are not supported.
- Android 10 or newer, no root.
- Storage depends on the family: Anima packs are about 2.6–3.6 GB; MOSS-Image is 14.1 GB, with RealSkin MOSS 1.0 and Harem MOSS 1.1 adding about 6.3 GB each; the Qwen-Image 2.1 Turbo pack is about 15.6 GB.
- MOSS-Image and Qwen-Image on the 8 Gen 2 are **experimental**: they generate reliably in short sessions, but long thermal runs have not been measured. Mage-Flow-Edit-Turbo (about 8.7 GB) runs on V73 since 1.8 (see [docs/MAGE_V73_PORT.md](docs/MAGE_V73_PORT.md)).

## Credits and thanks

- **[ayanoby](https://github.com/ayanoby)**: author of [HexAnima](https://github.com/ayanoby/hexanima), its Hexagon HMX kernels, the on-device server, the app and the [NPU model packs](https://huggingface.co/ayanoby/anima-npu-packs). This fork stands entirely on that work.
- **Models**: Anima by CircleStone Labs (on NVIDIA Cosmos); MiaoMiao Harem / 3D Harem / RealSkin by MIAOKA; turbo LoRAs by somedoby (2B) and n_Arno (2.9B). Non-commercial use only.
- **Mage-Flow-Edit-Turbo** by Microsoft (MIT), mobile pack and kernels by ayanoby.
- **Software**: [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) (patched), Qualcomm QNN runtime, Real-ESRGAN General x4v3 (BSD-3), [4x-UltraSharp](https://huggingface.co/Kim2091/UltraSharp) by Kim2091 (CC BY-NC-SA 4.0), face_yolov8n and hand_yolov8n (Bingsu/adetailer, Apache-2.0), Barlow Semi Condensed font (SIL OFL).
- **gpt-image-2 style LoRA** for Anima by AgileNumbers (Civitai), downloaded by the app on request.
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
