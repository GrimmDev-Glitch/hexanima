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

## Imagen a vídeo y duración elegible (15 nov 2026)

**Funcionan en la app Dev.** Medido en el Poco F6 Pro:

| Clip | Tiempo total | Notas |
|---|---|---|
| 1 s, texto | 41,5 s | texto 23 s en frío |
| 3 s, texto (3 partes, 73 fotogramas) | 133 s | cada parte extra ≈ 30 s (codificar el último fotograma + recargar el DiT + 8 pasos + VAE) |
| 2 s desde imagen (49 fotogramas) | 117 s | la chica de la foto se mantiene; el primer fotograma sale algo pixelado (codificador fp8) |

- **Imagen a vídeo:** la foto se recorta a 512×320 y se codifica con el VAE 0.9.5 en el procesador (`libltxt5.so enc`, 1,25 GB en fp8, ~5 s por imagen con el modelo cargado). Sus 160 tokens se fijan como primer fotograma con «timestep 0» y no se actualizan en los 8 pasos (como el pipeline de imagen a vídeo de LTX).
- **Duración:** cada parte de 1 s empieza en el último fotograma de la anterior (24 fotogramas nuevos por parte). Opciones: 1, 2, 3, 5, 10 y 15 s.
- **Deriva de color:** sin corrección, la saturación subía en cada parte. Ahora cada parte se ajusta por canal (media y desviación) para que su primer fotograma coincida con el fotograma del que parte. Comparación: `ltx/samples/app-3s-before-colorfix.mp4` (antes) y `app-i2v-2s.mp4` (después).
- **RAM:** el DiT se recarga en cada parte, porque en V73 el VAE no cabe a su lado. Con lectura por `mmap` la recarga baja de 10–20 s a menos de 1 s por bloque.

## Viabilidad: calidad y duración pedidas

### Duración de 3 a 15 s

| Duración | Partes | Tiempo estimado | Viable |
|---|---|---|---|
| 3 s | 3 | ~2 min (medido 133 s) | Sí |
| 5 s | 5 | ~3 min | Sí |
| 10 s | 10 | ~5,5 min | Sí, con aviso de tiempo |
| 15 s | 15 | ~8 min | Sí, pero la escena cambia bastante a lo largo del clip |

El límite real no es la memoria (cada parte usa lo mismo) sino la coherencia: cada parte solo ve **un** fotograma de la anterior, así que el movimiento puede dar un pequeño salto entre partes y la escena se va alejando del prompt. Mejoras posibles: condicionar con los últimos 8–9 fotogramas (un fotograma latente completo) en lugar de uno, o compilar contextos de 49 fotogramas (2 s por parte).

### Calidad 360p – 1080p

El modelo genera **512×320** en V73 (los contextos de 768×512 no caben en el espacio de direcciones de la NPU del 8 Gen 2, según su autor). Opciones:

| Calidad | Cómo | Coste por fotograma | 3 s (73 fot.) | 10 s (240 fot.) |
|---|---|---|---|---|
| 360p (576×360) | 512×320 tal cual, o reescalado suave | 0 | +0 s | +0 s |
| 480p (768×480) | Real-ESRGAN ×2 en la NPU y reducir | ~0,9 s* | +1 min | +3,5 min |
| 720p (1152×720) | Real-ESRGAN ×4 y reducir | ~2,1 s* | +2,5 min | +8,5 min |
| 1080p (1728×1080) | Real-ESRGAN ×4 (2048×1280) y reducir | ~2,1 s* | +2,5 min | +8,5 min |

\* Estimado a partir de lo medido en imágenes (×2 en 2 s y ×4 en 5 s para 512×768).

- **Ampliar fotograma a fotograma** es viable con lo que ya tiene la app. Riesgo: un ligero parpadeo, porque cada fotograma se amplía por separado.
- **Resolución nativa mayor** necesitaría compilar contextos nuevos con QAIRT 2.50 (hace falta una cuenta de Qualcomm para bajar el SDK) y trocear el DiT en más partes cargadas por turnos para que 768×512 quepa en V73. Cada paso tardaría ~2,5× más (1.536 tokens en lugar de 640). Es posible pero son días de trabajo y no está garantizado que quepa.

**Recomendación:** ofrecer 360p (nativo) y 720p/1080p como «Ampliar el vídeo» después de generarlo, igual que el botón ⤢ de las imágenes, para que el usuario decida si espera los minutos extra.

## Grok Imagine: qué usa y qué hay parecido

- **Grok Imagine (xAI)** genera vídeo e imagen a vídeo con **Aurora**, un modelo propio **autorregresivo de mezcla de expertos** (predice el vídeo por partes, cada una condicionada en lo anterior), no un modelo de difusión. No es público: no hay pesos ni artículo técnico, y las fuentes son de terceros. Corre en GPUs de centro de datos, con versiones a 480p–1080p.
- **No se puede adaptar**: no hay pesos, y por tamaño (un MoE de ese tipo tiene decenas de miles de millones de parámetros) no cabría en un teléfono.
- **Modelos abiertos parecidos** (texto e imagen a vídeo):

| Modelo | Tamaño | Licencia | ¿En el 8 Gen 2? |
|---|---|---|---|
| **LTX-Video 2B 0.9.8** (lo que ya usamos) | 2B | LTXV Open Weights | **Sí**, 512×320, ~30 s por segundo de vídeo |
| LTX-Video 13B 0.9.8 | 13B | LTXV Open Weights | No sin trocear: ~6,5 GB en INT4, 3–4 veces más lento |
| Wan 2.2 TI2V-5B | 5B | Apache 2.0 | Quizás: necesitaría convertirlo entero (DiT + VAE propio) y trocearlo; calculo varios minutos por segundo de vídeo |
| LTX-2 / 2.3 (con audio) | 19B | LTXV | No: pensado para GPUs de 32 GB o más |
| S2DiT (investigación, móvil) | 1,8B | sin publicar | — |

**Conclusión:** lo más parecido a Grok que cabe en el teléfono ya es lo que tenemos (LTX-Video 2B con imagen a vídeo y clips encadenados). El siguiente salto de calidad sería **Wan 2.2 TI2V-5B** (Apache 2.0, mejor movimiento), pero es un proyecto grande de conversión a la NPU, como lo fue Qwen-Image.

Fuentes: [Replicate · Grok Imagine Video](https://replicate.com/xai/grok-imagine-video/readme), [Lumenfall · arquitectura](https://lumenfall.ai/models/xai/grok-imagine-video/about), [MindStudio](https://www.mindstudio.ai/blog/what-is-grok-imagine-video-xai), [ImagineArt](https://docs.imagine.art/ai-models/video/grok-video), [TechTimes](https://www.techtimes.com/articles/318635/20260618/grok-imagine-video-15-goes-live-xai-tops-ai-video-leaderboard-86-percent-below-sora.htm), [Fuser · modelos abiertos](https://fuser.studio/articles/best-open-source-video-models), [LocalAIMaster · Wan vs LTX](https://localaimaster.com/blog/local-ai-video-generation), [S2DiT](https://arxiv.org/pdf/2601.12719).

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
