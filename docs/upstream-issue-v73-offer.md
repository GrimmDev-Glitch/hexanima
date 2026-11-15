**Title:** From the Snapdragon 8 Gen 2 (V73) fork: features you may want, a catalog note, and a question about Mage

Hi ayanoby! Thanks again for HexAnima. This is a summary of what we built in the Snapdragon 8 Gen 2 / Hexagon V73 fork (https://github.com/GrimmDev-Glitch/hexanima), in case any of it is useful for you. No pressure at all: tell me what (if anything) you would like as a PR and I will prepare it against your `main`.

### 1. Features you may want

- **Enlarge on the NPU (×2 / ×4).** Real-ESRGAN General x4v3 (BSD-3) as a QNN context run by a tiny generic runner (`libvaerun.so`: "run IN OUT" on any single-input context). 256×256 tiles with overlap; about 2 s for ×2 and 5 s for ×4 on a 512×768 picture on V73. We also built the **V69 context**, but we could not test it because we do not have a V69 phone. Code: `Upscaler.java`, `upscale/build.sh`.
- **Corrections screen.** Fix a finished picture without touching the original: make alternatives from the accepted version, compare them with a slider (double-tap zoom), keep or discard, undo / redo, save as a new picture with the list of corrections in its metadata. Noise and Resolution use the upscaler; Face, Hands, Details and Other paint an area and run a masked Qwen-Image edit (your `Qwen` edit path, with a soft edge so the rest stays pixel for pixel). Code: `Corrections.java`, `Qwen.runMaskedEdit`.
- **The V73 port itself.** V73 builds of the Anima, MOSS and Qwen kernels and QNN contexts (QAIRT 2.28). The main V73-specific finding: V69 HMX kernels run on V73 but every fp16 HMX result comes out wrong, because V73 stores through the conversion unit; the kernels need a V73 build, not only new contexts. If you ever want to support the 8 Gen 2 in your app, I am happy to help.

### 2. Catalog note: "Fast" on the full (base) models

In `catalog8.json` the description is per family, so the **(base)** modes of *MiaoMiao 3D Harem LH3D 1.1*, *MiaoMiao Harem Anima 1.6 2B* and *MiaoMiao RealSkin Anima 1.3* share "Fast, …" with their turbo sibling, while the base mode takes about 100 s (25 steps with CFG). In the fork we drop "Fast," for modes with `speed >= 60` and add "Full model: 20+ steps, slower but follows the prompt more closely." Maybe a `desc_en` per mode in the catalog would be cleaner.

### 3. A question about Mage-Flow-Edit-Turbo

We could not run Mage on V73. What we found:

- `libmageskel.so` in the release is a Hexagon **V69** build, and its source is not in the repository (`hexagon/`, `moss/hexagon/` and `qwen/hexagon/` do not implement the modes `libmagerun.so` asks for, for example its `mode50`).
- With our V73 Qwen kernel the runner gets further: the first text-encoder call is mode 22 and fails (`mega_block r=0x8000040d mode=22`, `error te`), because our mode 22 is written for the 4096-wide Qwen3-VL of Qwen-Image, while Mage's text encoder is Qwen3-VL-4B (2560 wide, 36 layers, 32 query / 8 key-value heads, FFN 9728).

Would you be willing to share the source of the Mage kernel (or a V73 build of `libmageskel.so`)? With that we could finish the port and send it back to you.

---

*Кратко по-русски:* в форке для Snapdragon 8 Gen 2 (V73) есть увеличение ×2/×4 на NPU (Real-ESRGAN, есть и контекст V69, но не проверен), экран исправлений (лицо, руки, детали через маскированное редактирование Qwen) и сам порт на V73. Могу подготовить PR, если что-то нужно. В каталоге базовые режимы трёх моделей помечены «Fast», хотя занимают ~100 с. И вопрос: можно ли получить исходники ядра Mage или сборку `libmageskel.so` под V73?
