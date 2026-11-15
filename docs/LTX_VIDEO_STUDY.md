# Estudio: LTX-Video en el Snapdragon 8 Gen 2 (V73)

15 nov 2026. Objetivo: saber si HexAnima puede generar vídeos cortos en la NPU del Poco F6 Pro, cuánto tardaría y qué costaría integrarlo.

## Resumen

**Sí es viable, con LTX-Video 2B 0.9.8 distilled, a 512×320 y 25 fotogramas (≈1 s de vídeo a 24 fps).** Ya existe una conversión pública a QNN con una carpeta específica para V73, medida en un 8 Gen 2: **36 s por clip** (texto 22,9 s en CPU, 8 pasos 9,2 s y VAE 4,1 s en la NPU). LTX-2 (19B, con audio) no es viable en este teléfono.

Recomendación: hacer un **prototipo en Dev** con esa conversión y un runner propio, y medir en el teléfono antes de decidir si entra en la app.

## Modelos de la familia

| Modelo | Tamaño | ¿En el teléfono? |
|---|---|---|
| LTX-Video 2B 0.9.8 distilled | 2B, 28 bloques DiT, 8 pasos, CFG 1 | **Sí**, ya convertido para V69/V73/V75/V79/V81 |
| LTX-Video 13B 0.9.8 distilled | 13B | No: no cabe en el espacio de direcciones de la NPU (~3,5 GB) sin trocear por bloques |
| LTX-2 / LTX-2.3 | 19B, audio + vídeo | No: pensado para GPU de escritorio; ni en INT4 cabe junto a las activaciones de vídeo |

El VAE de vídeo comprime 32×32×8 píxeles por token (1:192), así que un clip de 512×320×25 son solo 16×10×4 = 640 tokens: menos que una imagen de Anima a 512×768. Por eso los pasos son rápidos; lo caro es el texto (T5-XXL) y el VAE.

## La conversión existente

[timmyboy26/LTX-Video-2B-0.9.8-distilled-QNN](https://huggingface.co/timmyboy26/LTX-Video-2B-0.9.8-distilled-QNN) (hecha para la app LLM Hub):

- **DiT:** 28 bloques en 4 contextos QNN (`ltxv_chunk0..3.bin`), pesos INT8 por canal y activaciones FP16. En FP16 los pesos (3,9 GB) no caben en el espacio de direcciones de la NPU.
- **VAE decodificador:** el de 0.9.5, W8A16 calibrado (`ltxv_vae_512x320x25.bin`). En FP16 puro los últimos fotogramas salen borrosos.
- **Texto:** T5-XXL en **CPU**, GGUF q4_0 (`calcuis/ltxv-gguf`).
- **Imagen a vídeo:** el codificador del VAE corre en CPU (unos 80 s a 768×512 en un 8 Gen 3).
- **Formas fijas:** en V73 solo 512×320×25. Según el autor, 768×512 no cabe en el espacio de direcciones del 8 Gen 2.
- **QAIRT 2.50.0.260828:** las librerías de ejecución tienen que ser de esa versión. Nuestra app usa QAIRT 2.28.

Tiempos publicados (no verificados por nosotros):

| Chip | Salida | Total | Texto (CPU) | 8 pasos (NPU) | VAE (NPU) |
|---|---|---|---|---|---|
| 8 Gen 2 (Tab S9+) | 512×320×25 | 36 s | 22,9 s | 9,2 s | 4,1 s |
| 8 Gen 3 | 768×512×25 | 39,8 s | 10,3 s | 22,7 s | 6,7 s |
| 8 Elite | 768×512×25 | 25,9 s | 6,1 s | 15,2 s | 4,4 s |

## Licencias

- **Pesos LTX-Video 0.9.8:** LTXV Open Weights License 0.X. Uso libre (también comercial por debajo de 10 M USD de ingresos anuales), con restricciones de uso (Anexo A: nada ilegal, nada de suplantar a personas reales sin consentimiento, etc.). Al redistribuir: incluir la licencia, marcar los archivos modificados y trasladar las restricciones. Compatible con HexAnima (uso no comercial).
- **Contextos QNN de timmyboy26:** declarados derivados del modelo, misma licencia; avisos de Qualcomm en `QNN_NOTICE.txt`.
- **Runner y scripts de LLM Hub** (`ltxv_qnn.hpp`, `android/tools/ltxv_qnn`): PolyForm Noncommercial 1.0.0, que **prohíbe publicar en tiendas de apps** y exige el aviso de copyright. HexAnima se distribuye por GitHub, pero es mejor **escribir nuestro propio runner** y usar sus scripts solo como referencia (o pedir permiso a su autor).

## Cómo encajaría en HexAnima

1. **Runner propio** (C++, como `libvaerun.so`): carga los 4 contextos del DiT como un grupo, hace los 8 pasos con la agenda fija, libera el DiT si el VAE no cabe al lado y decodifica. Con su propio juego de librerías QAIRT 2.50 en una carpeta aparte (`ADSP_LIBRARY_PATH` por proceso), sin tocar las 2.28 de Anima, MOSS y Qwen.
2. **Texto:** primero T5-XXL q4_0 en CPU (≈23 s). Mejora posterior: T5 en la NPU con nuestros kernels de texto (como el codificador de Qwen), que podría bajar a pocos segundos y dejar el clip en ~15 s.
3. **Pantalla:** un modo «Vídeo» con prompt, semilla y vista previa en bucle; guardar MP4 (MediaCodec H.264) en el Historial con los metadatos.
4. **Extra con lo que ya tenemos:** ampliar ×2 cada fotograma con Real-ESRGAN (≈1 s por fotograma en V73, unos 25 s más) para llegar a 1024×640.
5. **Descarga:** carpeta `v73/` (DiT + VAE) más el T5 q4_0. Calculo unos **5 GB** (el repositorio entero son 16,7 GB con todas las arquitecturas).
6. **RAM:** 12 GB alcanzan si antes se libera el modelo de imagen, igual que hace Correcciones con Qwen.

## Riesgos

- **Calidad:** 2B a 512×320 y 1 s es poco; sirve para animar una escena, no para clips largos. Hay que verlo en el teléfono.
- **QAIRT 2.50 junto a 2.28:** dos versiones del runtime en la misma app; hay que comprobar que no chocan en el DSP.
- **Más fotogramas (49 = 2 s):** exigiría convertir de nuevo con sus scripts y quizá no quepa en V73.
- **Imagen a vídeo:** con el codificador en CPU tardaría más de un minuto; habría que pasarlo a la NPU.
- **Temperatura:** cada clip son ~36 s de NPU + CPU; medir varios seguidos.

## Prototipo en el Poco F6 Pro (15 nov 2026)

**Funciona.** Con los contextos `v73/` y dos programas propios (`ltx/t5enc` en CPU y `ltx/ltxrun` en la NPU) salen vídeos de 512×320 y 25 fotogramas coherentes con el prompt: la chica gira la cabeza o saluda con la mano entre los cerezos.

| Parte | Tiempo |
|---|---|
| T5 en CPU, 6 hilos (incluye leer 2,9 GB) | 11,1 s |
| Cargar los 4 contextos del DiT | 3,8 s |
| 8 pasos en la NPU | 9,2 s (1,15 s por paso) |
| VAE en la NPU (carga 1,9 s + ejecución) | 4,0 s |
| **Clip completo en frío** | **30,6 s** |

- Batería a 35,1 °C después de dos clips.
- Las librerías QAIRT 2.50 conviven con el resto: se cargan desde su propia carpeta (`ADSP_LIBRARY_PATH`) en su propio proceso.
- Calidad: estilo de dibujo sencillo y manos algo borrosas en movimiento; es un modelo de 2B a baja resolución.
- Con los procesos ya cargados (T5 y DiT en memoria) un segundo clip bajaría a unos 20 s; con T5 en la NPU, a unos 15 s.
- Muestras: `ltx/samples/`.

## Plan propuesto

1. ~~Descargar `v73/` y el T5 q4_0~~ (hecho).
2. ~~Runner de prueba por ADB y primer MP4~~ (hecho, ver arriba).
3. Si la calidad convence: integrarlo en Dev con pantalla de vídeo, guardar MP4 e Historial.
4. Después: T5 en la NPU, ampliación ×2 por fotograma, imagen a vídeo.

## Fuentes

- https://huggingface.co/timmyboy26/LTX-Video-2B-0.9.8-distilled-QNN
- https://github.com/timmyy123/LLM-Hub/tree/main/android/tools/ltxv_qnn
- https://huggingface.co/Lightricks/LTX-Video y su `LTX-Video-Open-Weights-License-0.X.txt`
- https://huggingface.co/Lightricks/LTX-2
- https://huggingface.co/docs/diffusers/en/api/pipelines/ltx_video
