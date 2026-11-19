**HexAnima is the work of [ayanoby](https://github.com/ayanoby/hexanima)**: the NPU kernels, on-device server, app and model packs. This fork targets the **Snapdragon 8 Gen 2 (Hexagon V73)**. Thank you, ayanoby!

**Install:** download `HexAnima-1.9.6-v73.1.apk` and install it over 1.6.0-v73 or newer. The package and signing key are unchanged, so your installed models, LoRAs and pictures are kept.

### New in 1.9.6-v73.1

This repack fixes the in-app What's New text for 1.9.6 and includes the changes shipped in 1.9.6:

- **Anima model packs:** import and export through Android folders. Imports validate the manifest, required files, sizes and SHA-256 hashes before installation; failed or cancelled imports roll back files they created.
- **Offline model catalog:** browse the saved catalog without a connection; downloads are disabled until the phone reconnects.
- **Automatic picture saving:** optionally copy each finished image to the phone gallery or an Android folder selected with SAF, while keeping it in HexAnima History.
- **PNG metadata:** corrections and upscaling preserve generation metadata and record the correction/upscale step.
- **Safer actions and diagnostics:** confirmation before stopping a generation or deleting a model; Anima load failures report the chip and recent log lines instead of waiting forever.
- **Batch queue:** adjust the remaining image count while a batch runs without changing the image already in progress.
- **Negative prompt default:** selecting it fills the prompt and enables CFG 4 when CFG 1 would otherwise skip the negative pass.

**Device validation:** installed and launched on a POCO F6 Pro (Snapdragon 8 Gen 2). Offline catalog, SAF saving and invalid model-pack rejection were exercised on that phone. A complete valid pack export/import round trip and the Anima load-failure path still need dedicated testing. Snapdragon 8 Gen 3 / V75 and other chips are not validated by this release.

### Novedades (español)

Este reempaquetado corrige el aviso interno de novedades de la 1.9.6 e incluye los cambios publicados en la 1.9.6:

- **Packs de modelos Anima:** importar y exportar mediante carpetas de Android. Antes de instalar, la importación valida el manifiesto, los archivos requeridos, tamaños y hashes SHA-256; si falla o se cancela, revierte los archivos que creó.
- **Catálogo de modelos sin conexión:** consulta el catálogo guardado sin internet; las descargas se desactivan hasta reconectar el teléfono.
- **Guardado automático de imágenes:** copia opcionalmente cada imagen terminada a la Galería o a una carpeta de Android elegida con SAF, y la conserva en el Historial de HexAnima.
- **Metadatos PNG:** las correcciones y el upscale conservan los metadatos de generación y registran el paso de corrección/upscale.
- **Confirmaciones y diagnóstico:** pide confirmar antes de detener una generación o borrar un modelo; si Anima no carga, muestra el chip y las últimas líneas del registro en vez de quedarse esperando.
- **Cola de generación:** ajusta la cantidad de imágenes pendientes mientras corre una tanda sin cambiar la imagen que ya está en curso.
- **Prompt negativo predeterminado:** lo rellena y activa CFG 4 cuando CFG 1 habría omitido el paso negativo.

**Pruebas en dispositivo:** instalado y abierto en un POCO F6 Pro (Snapdragon 8 Gen 2). En ese teléfono se probaron el catálogo sin conexión, el guardado SAF y el rechazo de packs inválidos. Aún falta probar el ciclo completo de exportación/importación de un pack válido y el flujo de fallo al cargar Anima. Esta release no valida Snapdragon 8 Gen 3 / V75 ni otros chips.

**Instalación:** se instala encima de 1.6.0-v73 o posterior sin perder modelos, LoRAs ni imágenes.

---
Non-commercial use only. Models and LoRAs keep their authors' licenses; license texts are in the app (Info → Licenses).

SHA-256 of `HexAnima-1.9.6-v73.1.apk`: `f249e65809644be863d36e95c05768d11c8e06918a3b860b754fb737a8adb118`
