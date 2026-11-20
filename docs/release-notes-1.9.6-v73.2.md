**HexAnima is the work of [ayanoby](https://github.com/ayanoby/hexanima)**: the NPU kernels, on-device server, app and model packs. This fork targets the **Snapdragon 8 Gen 2 (Hexagon V73)**. Thank you, ayanoby!

**Install:** download `HexAnima-1.9.6-v73.2.apk` and install it over 1.6.0-v73 or newer. The package and signing key are unchanged, so installed models, LoRAs and pictures are kept.

### New in 1.9.6-v73.2

- **Qwen photo edits:** replaced portrait sizes whose patch counts the NPU runner rejects; the VAE now starts only after Qwen successfully produces a latent, avoiding the startup/quit race behind the native crash.
- **In-app updates:** Settings checks the latest GitHub Release, verifies the APK SHA-256 and package/version, then hands it to Android's installer as an in-place update.
- **Share and MOSS:** Dev builds now use their own gallery provider authority; MOSS tries the existing V73 route on Snapdragon 8 Gen 3 / Hexagon V75 (experimental).
- **What's New:** the release changes are listed in English and Spanish and shown after the app version changes.

**Device validation:** the .1 → .2 update flow was exercised from Settings on a POCO F6 Pro (Snapdragon 8 Gen 2); the selected photo and prompt remained. The corrected APK was then installed over it, and its packaged Qwen shell script passed `sh -n` on Android. A full Qwen generation was not run because the phone had 4,382 MB available and the app requires 5,000 MB before starting. MOSS now tries the existing V73 route on V75 and remains experimental until tested on Snapdragon 8 Gen 3.

### Novedades (español)

- **Edición de fotos con Qwen:** se reemplazaron los tamaños verticales cuyo número de parches rechazaba el runner de la NPU. El VAE ahora arranca solo después de que Qwen genere correctamente un latente, evitando la carrera de inicio/cierre detrás del crash nativo.
- **Actualizaciones en la app:** Ajustes consulta el Release más reciente de GitHub, verifica el SHA-256, el paquete y la versión del APK, y luego abre el instalador de Android para actualizar sin borrar datos.
- **Compartir y MOSS:** Dev ahora usa la autoridad propia de su proveedor de galería; MOSS intenta la ruta V73 existente en Snapdragon 8 Gen 3 / Hexagon V75 (experimental).
- **Novedades:** los cambios aparecen en español o inglés al cambiar la versión de la app.

**Validación en dispositivo:** se probó desde Ajustes la actualización .1 → .2 en un POCO F6 Pro (Snapdragon 8 Gen 2); conservó la foto y el prompt seleccionados. Después instalé encima el APK corregido y el `qwen.sh` incluido pasó `sh -n` en Android. No ejecuté una generación completa porque el teléfono tenía 4.382 MB disponibles y la app exige 5.000 MB antes de iniciar. MOSS intenta la ruta V73 existente en V75 y sigue experimental hasta probarlo en Snapdragon 8 Gen 3.

**Instalación:** se instala encima de 1.6.0-v73 o posterior sin borrar modelos, LoRAs ni imágenes.

---
Non-commercial use only. Models and LoRAs keep their authors' licenses; license texts are in the app (Info → Licenses).

SHA-256 of `HexAnima-1.9.6-v73.2.apk`: `b1fdee9a684dbba62fc6ccbdbd20d74fd0dd088d063da3a5b57f8d1fc93550c5`
