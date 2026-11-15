**HexAnima is the work of [ayanoby](https://github.com/ayanoby/hexanima)**: the NPU kernels, the on-device server, the app and the NPU model packs. This fork ports it to the **Snapdragon 8 Gen 2**. Thank you, ayanoby!

> [!IMPORTANT]
> **1.6.0-v73 is signed with a new key: it does not install over 1.5.x-v73.**
> 1. In the old app, open **History → ZIP** to save your pictures.
> 2. Uninstall HexAnima 1.5.x-v73 (and the original HexAnima, if installed).
> 3. Install `HexAnima-1.6.0-v73.apk` and download your models again from the catalog.
>
> Future versions will install over 1.6.0-v73 and keep your data.

### New in 1.6.0-v73

**Three model families on the Snapdragon 8 Gen 2**
- **MOSS-Image** (base 5B, RealSkin, Harem) on V73: realistic pictures from plain sentences, about 30–45 s at 512×768.
- **Qwen-Image 2.1 Turbo** on V73: text to image and **photo editing with words**, about 45–55 s.

**Qwen photo editing**
- The edit keeps the photo's shape (portrait, landscape or square). A switch uses the picked size instead.
- *Area to edit* limits the change to a painted zone with soft edges. *High fidelity* keeps more detail.
- A before/after slider compares the result and can save both side by side.
- **Up to 3 reference pictures**, each one its own input: "the character of image 1 wearing the outfit of image 2", "the girls from image 1 and image 2 hug under cherry trees" (about 70 s with two).

**LoRAs**
- **One LoRA panel for every family**: Library, Import and Civitai.
- **Qwen LoRAs** (.safetensors, also from Civitai) are converted on the phone in a few seconds. Set the strength with the slider or type it.
- **MOSS LoKr**: full 4×4 LoKr adapters are baked into a copy of the weights (about 20 s, only the last one kept).
- **Categories** (character, clothing, pose, style, concept, background, object) in the libraries and the Civitai search.
- Long-press a LoRA to edit its trigger words, add them to the prompt, change its category or delete it.

**Civitai**
- Search for Anima, MOSS and Qwen. A **✓ Downloaded** mark shows what you already have.

**Interface**
- *Load model / Free model* sits right under the model picker.
- The negative prompt sits right under the prompt, and steps, CFG and sampler come right after it.
- The size and seed fold into an *Image* section.
- **Your own wallpaper** (from upstream 1.6.0): pick a picture, place it, and set the block transparency and dimming.
- Themed dialogs and menu. A new Welcome screen presents the three families.
- Complete Spanish translation. The build fails if a string is missing.

**Fixes**
- Civitai search could list the results twice.
- The LoRA check text now follows the app language.
- The catalog no longer calls the 20-step base models "fast".
- Mage, which does not run on V73 yet, is no longer offered for download.

**Not yet:** Mage-Flow on V73 (see `docs/MAGE_V73_PORT.md`). Long thermal runs of MOSS and Qwen have not been measured.

### Novedades (español)
> [!IMPORTANT]
> **Esta versión está firmada con una clave nueva y no se instala encima de 1.5.x-v73.**
> Guarda tus imágenes (Historial → ZIP), desinstala la versión anterior, instala esta y vuelve a descargar los modelos. Las próximas versiones ya se instalarán encima sin perder nada.

**Modelos**
- **MOSS-Image** y **Qwen-Image 2.1 Turbo** funcionan en el Snapdragon 8 Gen 2.
- Qwen puede **editar tus fotos con palabras**:
  - La edición conserva la forma de la foto, y puedes limitarla a una zona pintada.
  - Un deslizador compara el antes y el después.
  - Admite **hasta 3 imágenes de referencia**.

**LoRAs**
- Un solo **panel de LoRA** para todas las familias.
- Las **LoRAs de Qwen** se convierten en el teléfono.
- MOSS acepta **LoKr**.
- Las LoRAs tienen **categorías**, y con una pulsación larga editas sus activadoras.

**Civitai**
- Marca con un **✓** lo que ya tienes descargado.

**Interfaz**
- «Cargar modelo» queda bajo el selector.
- El prompt negativo va bajo el prompt, y el muestreo justo después.
- Puedes poner un **fondo de pantalla propio**.
- La traducción al español está completa.

**Arreglos**
- La búsqueda de Civitai ya no repite resultados.
- El texto de compatibilidad de las LoRAs sigue el idioma de la app.
- El catálogo ya no llama «rápidos» a los modelos base.
- Mage ya no se ofrece para descargar mientras no funcione en V73.

---
Non-commercial use only. The models and LoRAs keep their authors' licenses.
SHA-256 of `HexAnima-1.6.0-v73.apk`: `850a97fa1344f1ac1ae19518a272ab8e6436c9d2afd8f1478c2f8c24e529575f`
