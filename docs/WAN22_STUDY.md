# Estudio: Wan 2.2 TI2V-5B en el Snapdragon 8 Gen 2 (V73)

15 nov 2026. Motivo: LTX-Video 2B funciona en el teléfono, pero la calidad es muy pobre. Las acciones del prompt casi no se cumplen, la imagen a vídeo se deforma y el dibujo es tosco. Wan 2.2 TI2V-5B es hoy el modelo abierto de vídeo pequeño con mejor movimiento y licencia Apache 2.0.

## Resumen

**Es viable, y mucho más barato de lo que parecía.** No hace falta portarlo a mano como Qwen:

- **Ya corre en un teléfono:** la app nightmare-mobile ejecuta **Wan 2.2 TI2V-5B Turbo** (4 pasos) en un Snapdragon 8 Gen 3 con 12 GB. Un clip de 2 s a 480×832 (49 fotogramas) sale en **72 s**: 63 s de muestreo en la NPU, 4 s de texto en el procesador y 3,5 s de VAE.
- **Usa piezas abiertas que ya tenemos en el PC:**
  - stable-diffusion.cpp con Wan 2.2 TI2V (`VERSION_WAN2_2_TI2V`, `wan.hpp`, VAE de Wan 2.2 y TAEHV);
  - el backend **ggml-hexagon** de ggml (matmul HMX y flash-attention en la NPU), que compila el kernel para **v73** y v75;
  - el SDK de Hexagon, que ya usamos para nuestros kernels.
- **Estimación para tu teléfono (V73):** el 8 Gen 2 es más lento que el 8 Gen 3. Calculo de 1,5 a 2,5 min por clip de 2 s a 480p, y unos 40–60 s a 512×320. Sin medir todavía.

## El modelo

| Pieza | Detalle | En el teléfono |
|---|---|---|
| DiT | 5B, 30 bloques, dim 3072, 24 cabezas, FFN 14336, patch 1×2×2 | NPU (ggml-hexagon), Q4_0: 3,0 GB |
| Versión rápida | **Wan2.2-TI2V-5B-Turbo** (quanhaol, Apache 2.0): 4 pasos sin CFG (destilado de pasos y de CFG) | — |
| Texto | umT5-XXL (encoder), 512 tokens, multilingüe | Procesador, GGUF Q4_K_M: 3,7 GB |
| VAE | Wan 2.2 VAE: compresión 4×16×16, 48 canales (4×32×32 contando el patch) | TAEHV (`taew2_2`, MIT, 40 MB) en la NPU o el procesador; el VAE completo es más lento |
| Imagen a vídeo | Integrada (TI2V): la foto ocupa el primer fotograma latente | Igual que el texto a vídeo, más el codificador del VAE |

Tokens por clip: 480×832 y 49 fotogramas son 13 fotogramas latentes de 15×26, es decir **5.070 tokens**. A 512×320 son 2.080. LTX-Video usaba 640.

## Comparación con lo que ya tenemos

| | LTX-Video 2B (actual) | Wan 2.2 TI2V-5B Turbo |
|---|---|---|
| Calidad | Pobre: prompt poco obedecido, dibujo tosco | Mucho mejor movimiento y fidelidad al prompt (VBench ~83–84) |
| Resolución en V73 | 512×320 fija | 480p (832×480), 704p en clips cortos |
| Duración por pasada | 1 s (encadenado para más) | 2 s a 480p; hasta 5 s en el 8 Gen 3 si hay memoria |
| Tiempo, 2 s | ~2 min (2 partes encadenadas) | ~1,5–2,5 min estimado (una sola pasada) |
| Descarga | 6,8 GB | ~6,7 GB (DiT Q4_0 3,0 + umT5 Q4_K_M 3,7 + TAEHV) |
| Licencia | LTXV Open Weights | Apache 2.0 (modelo, Turbo y umT5); TAEHV MIT |

## Otros proyectos (referencia)

- **nightmare-mobile** (Android, licencia CC BY-NC 4.0): Wan 2.2 TI2V-5B Turbo y 14B en la NPU con stable-diffusion.cpp y ggml-hexagon. En su tabla, el vídeo figura desde el 8 Gen 3. Problemas que reportan: a veces sale ruido (2 de 6 clips) hasta reiniciar el motor, y un aviso de convoluciones 1×1 que dan un resultado erróneo en TAEHV. No publican el backend de la NPU.
- **PulseX Wan** (portátiles Snapdragon X, Hexagon **v73**, código MIT): Wan 2.2 TI2V-5B base (15 pasos) con DiT Q4_0 leído por bloques desde el disco y VAE en grafos QNN v73 (QAIRT 2.45). Tarda 7,3 min para 2 s a 480p desde imagen. Sus contextos del VAE están en `Pexqman/Wan2.2-TI2V-5B-hexpack` (6,7 GB, Apache 2.0).
- **MobileWan** (Qualcomm AI Research): Wan 2.2 5B recortado y destilado a 3 pasos; anuncian 5 s a 480×832 en 20 s en un teléfono. Todavía no publicado.

## Riesgos

1. **V73 frente a V75:** ggml-hexagon compila para v73, pero los resultados publicados son del 8 Gen 3. El HMX del 8 Gen 2 es más lento y hay que medirlo.
2. **Ruido intermitente:** lo vio nightmare-mobile con su versión del backend. Habrá que comprobar si lo tiene la versión oficial de ggml.
3. **RAM (12 GB):** DiT 3 GB + umT5 3,7 GB. Se cargan por turnos: primero el texto, que se libera, y luego el DiT. 5 s a 480p necesitaba unos 4,3 GB solo para las activaciones en el 8 Gen 3.
4. **Convivencia en la app:** ggml-hexagon usa su propio kernel en el DSP (`libggml-htp-v73.so`), así que no choca con QNN 2.28 ni con nuestros kernels. Hay que confirmarlo.

## Plan propuesto

1. **Prototipo por ADB (1–2 días):**
   - Compilar `sd-cli` con `GGML_HEXAGON=ON` para v73 (NDK + SDK de Hexagon, que ya tenemos).
   - Bajar el DiT Turbo Q4_0, umT5 Q4_K_M y TAEHV (~6,7 GB).
   - Generar en el teléfono texto a vídeo e imagen a vídeo de 2 s a 512×320 y a 480p, y medir tiempo, RAM, temperatura y calidad.
2. Si la calidad y el tiempo convencen: cambiar el motor de la pantalla **Vídeo** de LTX a Wan, con la misma pantalla, descarga desde Modelos y elección de calidad y duración. LTX quedaría como opción rápida o se retiraría.
3. Después: más duración (5 s a 480p si cabe en memoria, o encadenar clips con el último fotograma), VAE completo para más nitidez y ampliar con Real-ESRGAN.

## Fuentes

- Configuración oficial: https://github.com/Wan-Video/Wan2.2/blob/main/wan/configs/wan_ti2v_5B.py y https://huggingface.co/Wan-AI/Wan2.2-TI2V-5B
- Turbo de 4 pasos: https://github.com/quanhaol/Wan2.2-TI2V-5B-Turbo y GGUF en https://huggingface.co/hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF
- umT5 GGUF: https://huggingface.co/city96/umt5-xxl-encoder-gguf
- nightmare-mobile: https://github.com/AbrahamPaulJ/nightmare-mobile (tiempos en el issue #23)
- PulseX Wan: https://github.com/Erik-matrix/pulsex-wan y https://huggingface.co/Pexqman/Wan2.2-TI2V-5B-hexpack
- MobileWan: https://qualcomm-ai-research.github.io/MobileWan/
