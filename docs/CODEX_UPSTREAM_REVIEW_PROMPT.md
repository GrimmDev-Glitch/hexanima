# Prompt para GPT Codex — revisión del upstream y metadatos de las imágenes

> Copia todo lo que está debajo de la línea y pégalo en Codex, abierto en la carpeta del repositorio `Hexanima`.

---

## Contexto

Trabajas en **HexAnima V73**, un fork Android (Java + C/C++ para el NPU Hexagon V73 del Poco F6 Pro) del proyecto original **ayanoby/hexanima**.

- Nuestro repositorio: `origin` = https://github.com/GrimmDev-Glitch/hexanima (rama `main`, nueva versión final **1.9.6-v73**, versionCode 33, en `app/AndroidManifest.xml`).
- Repositorio principal (upstream): `upstream` = https://github.com/ayanoby/hexanima (actualiza la referencia con `git fetch upstream`; en la revisión del 19-nov-2026, `upstream/main` = `12b51a7`, release v1.8.3). El remoto `upstream` ya está configurado en el repositorio.
- Responde y escribe todos los informes **en español**. El dueño del proyecto no lee inglés.
- La app sale **solo en inglés y español**. No portes las otras traducciones del upstream. Los textos de la interfaz van con `L.s("español", "English")` y `es.tsv`.
   - Lee primero `CHANGES.md`, `docs/CLAUDE_HANDOFF.md`, `docs/PITFALLS.md`, `docs/MAGE_V73_PORT.md` y la carpeta `docs/upstream/` para saber qué se portó ya y qué problemas tiene el V73.
   - Revisa **todas** las releases de https://github.com/ayanoby/hexanima/releases/ (una fila por release, de la primera a la última). Contrasta cada función con el código del fork y marca ya está / parcial / falta / descartada; no deduzcas estado por el nombre o hash del commit.

**No hagas commit ni push, y no cambies de rama** hasta que el dueño apruebe el informe. La primera entrega es solo el análisis, más el arreglo de metadatos si queda claro (parte 2).

## Parte 1 — Comparar con el upstream

1. `git fetch upstream` y compara `upstream/main` con nuestro `main`:
   - `git log --oneline main..upstream/main` (lo que ellos tienen y nosotros no)
   - `git log --oneline upstream/main..main` (lo que tenemos nosotros y ellos no)
   - `git merge-base main upstream/main` y `git diff --stat` entre la base y cada rama.
   Nuestro fork **no** se mantiene con merges: portamos a mano y adaptamos los kernels al V73. Por eso muchos commits del upstream ya están "dentro" con otro hash. Para decidir si algo ya está, compara el código, no solo los hashes. Pistas: commits nuestros que dicen "upstream", por ejemplo `f26f196`, `54ec707` y `4b7881f`.
2. Ya portado (no lo propongas otra vez): Mage-Flow por ventanas (flux3/hexagon/mega, modos 53/55/61), Anima sin límite de tokens (attn_chunked, megarun TMAX 98304), el reintento del self-test HMX (rc -77) y Real-ESRGAN.
   Decisión actualizada por el dueño: **sí evaluar MiniMax-H3 y LTX-2.5**, basándose en lo que implementa el repo original (código, packs, dependencias, licencias, memoria, integración y límites reales; no especular ni asumir que un release significa soporte V73). Distingue modelos H3-Image y H3 Ref2VA, y distingue LTX-2.5 del prototipo local LTX-Video 2B. El recorte de ropa con SegFormer sigue descartado. Los idiomas de producto se limitan estrictamente a EN/ES.
3. Arma tres listas con una tabla cada una (columnas: commit o archivo del upstream, qué hace, esfuerzo S/M/L, riesgo en el V73, recomendación):
   - **A. Integrar tal cual:** cambios del upstream que entran casi sin tocar (Java, UI, arreglos de bugs, catálogo de modelos, formato de packs).
   - **B. Adaptar:** cambios del upstream que necesitan trabajo para el V73. Por ejemplo, kernels Hexagon escritos para V75/V79, HMX, VTCM o tamaños de memoria. Explica qué habría que cambiar y en qué archivos (`hexagon/`, `flux3/`, `mage/`, `app/src/com/hexanima/npu/...`). Incluye Klein (FLUX.3, 5.6B), MiniMax-H3 y LTX-2.5 si aparecen; para estos últimos inspecciona los exporters/runners Java/kernels y catálogo del upstream y compáralos con cualquier prototipo local.
   - **C. Ofrecer al upstream:** cosas nuestras que el upstream no tiene y le servirían. Por ejemplo, ADetailer de caras y manos, metadatos A1111/Civitai, plantillas de configuración, "Copiar prompt", soporte V73 y scripts de compilación portables. Indica si conviene un PR o un issue. Ya hay borradores en `docs/upstream-*.md`.
4. Señala los **conflictos**: archivos que tocamos los dos (por ejemplo `MainRun.java`, `Base.java`, `megarun.cpp`, `blk.c`), donde portar algo del upstream rompería lo nuestro. Incluye una evaluación específica de viabilidad/alcance para MiniMax-H3 y LTX-2.5 y un orden recomendado que los considere sin reabrir SegFormer ni otros idiomas.
5. Termina con un **orden recomendado** de trabajo, empezando por lo más barato y útil.

## Parte 2 — Las imágenes guardadas no traen los metadatos

**Problema:** al guardar o exportar una imagen generada, el archivo que queda en el teléfono (Pictures/Anima, lo que se comparte o lo que se sube a Civitai) no muestra el prompt, la semilla, el modelo ni los LoRA.

Qué hay hoy en el código (verifícalo):
- `app/src/com/hexanima/npu/PngMeta.java` → `embed()` mete dos chunks **iTXt** después del IHDR: `anima` (nuestro JSON) y `parameters` (texto estilo A1111). Borra los `tEXt/iTXt/zTXt` con esas claves que haya escrito sd.cpp. No hace nada si `PngMeta.keep` es false (ajuste "Guardar metadatos en la imagen", `keep_meta`, activado por defecto en `Settings.java:27`).
- Se llama desde `MainRun.java` (líneas ~166, 258, 311, 375, 400, 454) y `Base.java:299` → `Gallery.save(...)`.
- `Gallery.export()` copia el PNG byte a byte a `MediaStore` (Pictures/Anima).
- Hay archivos que **vuelven a codificar** el PNG con `Bitmap.compress`, y eso borra todos los chunks de texto: `Corrections.java:237` y `:287`, `Base.java:477` y `Detector.java:84`. Revisa también el ADetailer después de la imagen (caras y manos), el upscale y el img2img/inpaint.

Hipótesis que debes comprobar, una por una:
1. Algún camino guarda la imagen final después de un `Bitmap.compress` (ADetailer, correcciones, upscale) **sin** volver a pasar por `PngMeta.embed`.
2. Compartir (`Gallery.share` con el FileProvider) o "Ver" entrega otro archivo, o una versión recomprimida.
3. Los lectores esperan **`tEXt` "parameters"** (Latin-1). Revisa qué leen A1111, Civitai, ComfyUI y exiftool, y si con iTXt sin comprimir alcanza. Si hace falta, escribe `parameters` como tEXt cuando el texto sea Latin-1 y como iTXt UTF-8 cuando no.
4. El orden de los chunks: comprueba que van antes del primer IDAT y que el CRC está bien.
5. La galería de Android o Google Photos no muestran los chunks de PNG aunque estén. En ese caso el archivo está bien y solo falta explicarlo (y quizás añadir EXIF/XMP como respaldo).

Cómo comprobarlo:
- Escribe una prueba o un script pequeño que lea un PNG y liste sus chunks (tipo, clave, longitud, CRC válido). Pásalo por: la imagen recién generada (`Gallery.dir`), la exportada a Pictures/Anima, una con ADetailer, una con correcciones y una con upscale.
- Si hay un teléfono conectado: `adb pull` de `/sdcard/Pictures/Anima/...` y `exiftool -a -G1 archivo.png`.

Arreglo esperado:
- **Todos** los caminos que producen una imagen final deben terminar en `PngMeta.embed(png, meta)` con el meta completo: prompt, negativo, pasos, sampler, CFG, semilla, tamaño, modelo, LoRA con hashes AutoV2 y los recursos de Civitai. Las pasadas extra (ADetailer, upscale) se añaden al texto como hace A1111 (`ADetailer model: ...`, `Hires upscale: ...`).
- Respeta el ajuste `keep_meta`: si está apagado, no se escribe nada.
- No cambies el formato de `parameters` que ya funciona con Civitai salvo que la hipótesis 3 lo exija.
- Compila con los helpers del repositorio (`tools/dev_build.sh` o `tools/release_build.ps1`) y verifica de nuevo con el lector de chunks.

## Entrega

1. Un informe en español en `docs/upstream/REVIEW_<fecha>.md` con las listas A, B y C, los conflictos y el orden recomendado.
2. Para los metadatos: la causa encontrada (con archivo y línea), el diff del arreglo y la salida del lector de chunks antes y después.
3. Una lista corta de preguntas para el dueño, si algo depende de su decisión.

No hagas commit hasta que el dueño lo apruebe.
