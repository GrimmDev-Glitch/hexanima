**HexAnima is the work of [ayanoby](https://github.com/ayanoby/hexanima)**: the NPU kernels, the on-device server, the app and the NPU model packs. This fork ports it to the **Snapdragon 8 Gen 2**. Thank you, ayanoby!

**Install:** download `HexAnima-1.7.0-v73.apk` and install it over 1.6.0-v73. Your models, LoRAs and pictures are kept.
Coming from 1.5.x-v73 or older? Those were signed with another key: save your pictures (History → ZIP), uninstall, install this one and download the models again.

### New in 1.7.0-v73

**Enlarge on the NPU**
- A new ⤢ button under every picture and in the History viewer: **×2 or ×4** with Real-ESRGAN General x4v3 (BSD-3).
- About **2 s for ×2** and **5 s for ×4** on a 512×768 picture, on the NPU, so the CPU does not heat up.
- Lines in hair and eyes come out sharp instead of blurry. It can invent small details; the original is kept and the result is saved as a new picture with the same prompt.

**Corrections**
- A new screen (sliders button) to fix a finished picture **without touching the original**.
- Make alternatives from the accepted version, compare them with a slider (**double-tap to zoom 3×**), keep one or discard it, undo / redo, and save the result as a new picture with the list of corrections.
- **Noise:** cleans grain and artefacts at the same size, with a strength. About 1.5 s.
- **Resolution:** ×2 / ×4. About 2–5 s.
- **Hands, Details, Other:** paint an area and Qwen-Image redraws **only that area**, with a soft edge; the rest stays pixel for pixel. Hands needs no prompt (it asks for natural hands with five fingers). About a minute per alternative; needs Qwen-Image installed. Perfect anatomy is not guaranteed, so make a few alternatives and keep the best.
- **Face** is listed as coming later: it needs a face restorer converted to the NPU.

**Fixes**
- Swiping up or down over the Corrections comparator scrolls the screen (only a sideways drag moves the line).
- The mask editor is shared by img2img, Qwen editing and Corrections.

### Novedades (español)

**Ampliar en la NPU**
- Botón ⤢ nuevo en cada imagen y en el visor del Historial: **×2 o ×4** con Real-ESRGAN.
- Unos **2 s para ×2** y **5 s para ×4** en una imagen de 512×768, en la NPU y sin calentar la CPU.
- El pelo y los ojos salen nítidos. Puede inventar detalles pequeños; la original se conserva y la ampliada se guarda como imagen nueva.

**Correcciones**
- Pantalla nueva (botón de controles) para arreglar una imagen **sin tocar la original**.
- Generas alternativas, las comparas con un deslizador (**doble toque para acercar 3×**), te quedas con una o la descartas, deshaces y rehaces, y guardas el resultado como imagen nueva.
- **Ruido:** limpia grano y artefactos sin cambiar el tamaño.
- **Resolución:** ×2 / ×4.
- **Manos, Detalles, Otros:** pintas una zona y Qwen-Image redibuja **solo esa zona**; el resto queda igual. Alrededor de un minuto por alternativa; necesita Qwen-Image instalado.
- **Cara** llegará más adelante.

**Instalación:** se instala encima de 1.6.0-v73 sin perder nada. Si vienes de 1.5.x-v73, guarda tus imágenes (Historial → ZIP), desinstala, instala esta y vuelve a descargar los modelos.

---
Non-commercial use only. The models and LoRAs keep their authors' licenses. Real-ESRGAN: BSD-3-Clause (license text in the app, Info → Licenses).
SHA-256 of `HexAnima-1.7.0-v73.apk`: `135f775d8b076ac23b1bbc46ffc6660e4274a6b7111b3a9676945487c7c122fa`
