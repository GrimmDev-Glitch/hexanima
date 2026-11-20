**HexAnima is the work of [ayanoby](https://github.com/ayanoby/hexanima)**: the NPU kernels, on-device server, app and model packs. This fork targets the **Snapdragon 8 Gen 2 (Hexagon V73)**. Thank you, ayanoby!

**Install:** download `HexAnima-1.9.6-v73.2.apk` and install it over 1.6.0-v73 or newer. The package and signing key are unchanged, so installed models, LoRAs and pictures are kept.

### New in 1.9.6-v73.2

- **Qwen photo edits:** replaced portrait sizes whose patch counts the NPU runner rejects; the VAE now starts only after Qwen successfully produces a latent, avoiding the startup/quit race behind the native crash.
- **In-app updates:** Settings checks the latest GitHub Release, verifies the APK SHA-256 and package/version, then hands it to Android's installer as an in-place update.
- **Share and MOSS:** Dev builds now use their own gallery provider authority; MOSS exits before native startup on unsupported Snapdragon 8 Gen 3 / Hexagon V75 devices.
- **What's New:** the release changes are listed in English and Spanish and shown after the app version changes.

**Device validation:** the updater UI and version gate were installed on a POCO F6 Pro (Snapdragon 8 Gen 2). Qwen generation was not forced because Android reported 4,423 MB free while the app's preflight requires 5,000 MB. The Share and MOSS changes are included; MOSS is intentionally blocked on V75 until validated.

### Novedades (español)

- **Edición de fotos con Qwen:** se reemplazaron los tamaños verticales cuyo número de parches rechazaba el runner de la NPU. El VAE ahora arranca solo después de que Qwen genere correctamente un latente, evitando la carrera de inicio/cierre detrás del crash nativo.
- **Actualizaciones en la app:** Ajustes consulta el Release más reciente de GitHub, verifica el SHA-256, el paquete y la versión del APK, y luego abre el instalador de Android para actualizar sin borrar datos.
- **Compartir y MOSS:** Dev ahora usa la autoridad propia de su proveedor de galería; MOSS sale antes del código nativo en Snapdragon 8 Gen 3 / Hexagon V75 no compatibles.
- **Novedades:** los cambios aparecen en español o inglés al cambiar la versión de la app.

**Validación en dispositivo:** se instaló la interfaz del actualizador y se comprobó su control de versión en un POCO F6 Pro (Snapdragon 8 Gen 2). No forcé la generación Qwen porque Android informó 4.423 MB libres y la app requiere 5.000 MB antes de empezar. Se incluyen los cambios de Compartir y MOSS; MOSS queda bloqueado en V75 hasta validarlo.

**Instalación:** se instala encima de 1.6.0-v73 o posterior sin borrar modelos, LoRAs ni imágenes.

---
Non-commercial use only. Models and LoRAs keep their authors' licenses; license texts are in the app (Info → Licenses).

SHA-256 of `HexAnima-1.9.6-v73.2.apk`: `f940b7f012f19ffa97efe97b279a9c3fa7313bd4ec59794b9f06460de0f01e3e`
