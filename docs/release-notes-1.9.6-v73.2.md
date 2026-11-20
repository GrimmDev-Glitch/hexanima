**HexAnima is the work of [ayanoby](https://github.com/ayanoby/hexanima)**: the NPU kernels, on-device server, app and model packs. This fork targets the **Snapdragon 8 Gen 2 (Hexagon V73)**. Thank you, ayanoby!

**Install:** download `HexAnima-1.9.6-v73.2.apk` and install it over 1.6.0-v73 or newer. The package and signing key are unchanged, so installed models, LoRAs and pictures are kept.

### New in 1.9.6-v73.2

- **Qwen photo edits:** replaced portrait sizes whose patch counts the NPU runner rejects; the VAE now starts only after Qwen successfully produces a latent, avoiding the startup/quit race behind the native crash.
- **In-app updates:** Settings checks the latest GitHub Release, verifies the APK SHA-256 and package/version, then hands it to Android's installer as an in-place update.
- **Share and MOSS:** Dev builds now use their own gallery provider authority; MOSS tries the existing V73 route on Snapdragon 8 Gen 3 / Hexagon V75 (experimental).
- **What's New:** the release changes are listed in English and Spanish and shown after the app version changes.

**Device validation:** the update was installed from Settings on a POCO F6 Pro (Snapdragon 8 Gen 2), preserving its selected photo and prompt. A newly exported log exposed a shell syntax error in the Qwen runner; the corrected APK is being retested through this same Release. MOSS now tries the existing V73 route on V75 and remains experimental until a Snapdragon 8 Gen 3 device is tested.

### Novedades (español)

- **Edición de fotos con Qwen:** se reemplazaron los tamaños verticales cuyo número de parches rechazaba el runner de la NPU. El VAE ahora arranca solo después de que Qwen genere correctamente un latente, evitando la carrera de inicio/cierre detrás del crash nativo.
- **Actualizaciones en la app:** Ajustes consulta el Release más reciente de GitHub, verifica el SHA-256, el paquete y la versión del APK, y luego abre el instalador de Android para actualizar sin borrar datos.
- **Compartir y MOSS:** Dev ahora usa la autoridad propia de su proveedor de galería; MOSS intenta la ruta V73 existente en Snapdragon 8 Gen 3 / Hexagon V75 (experimental).
- **Novedades:** los cambios aparecen en español o inglés al cambiar la versión de la app.

**Validación en dispositivo:** la actualización se instaló desde Ajustes en un POCO F6 Pro (Snapdragon 8 Gen 2) y conservó la foto y el prompt seleccionados. Un registro nuevo reveló un error de sintaxis en el script Qwen; el APK corregido se vuelve a probar mediante este mismo Release. MOSS ahora intenta la ruta V73 existente en V75 y sigue experimental hasta probarlo en un Snapdragon 8 Gen 3.

**Instalación:** se instala encima de 1.6.0-v73 o posterior sin borrar modelos, LoRAs ni imágenes.

---
Non-commercial use only. Models and LoRAs keep their authors' licenses; license texts are in the app (Info → Licenses).

SHA-256 of `HexAnima-1.9.6-v73.2.apk`: `b1fdee9a684dbba62fc6ccbdbd20d74fd0dd088d063da3a5b57f8d1fc93550c5`
