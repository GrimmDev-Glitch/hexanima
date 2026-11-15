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

## Resultados del prototipo (ADB, `wan/`)

`sd-cli` de stable-diffusion.cpp con ggml-hexagon (`wan/build_sd_hexagon.sh`), DiT Turbo Q4_0 en la NPU, umT5 Q4_K_M y TAEHV en el procesador (`wan/run_phone.sh`). umT5 solo codifica los tokens reales (`wan/patches/umt5_real_tokens.py`): 18 s en lugar de unos 130 s.

| Prueba | Pasos | Muestreo | Decodificación | Total | Resultado |
|---|---|---|---|---|---|
| 512×320, 9 cuadros | 4 | — | — | — | Limpio, sigue el prompt |
| 512×320, 25 cuadros (NPU) | 4 | 134 s | 16 s (TAE) | ~3 min | **Bloques** |
| 512×320, 25 cuadros (CPU del teléfono) | 4 | 589 s | 16 s | ~10 min | Mismos bloques: no es la NPU |
| 512×320, 25 cuadros (CPU de la PC) | 4 | 656 s | 15 s | ~11 min | Mismos bloques |
| 512×320, 25 cuadros, VAE completo sin teselas | 4 | 131 s | — | — | Llenó la RAM (11 GB + 7,7 GB de swap) y Android reinició la interfaz |
| **832×480, 25 cuadros (NPU)** | 4 | 294 s (73 s/paso) | 135 s (TAE en CPU) | 7,5 min | **Limpio**: los bloques venían de la resolución |
| **Imagen a vídeo 832×480, 25 cuadros (NPU)** | 4 | 293 s | 144 s (TAE en CPU) | 7,7 min | Mantiene el personaje y **hace la acción del prompt** (levanta la mano y saluda). Textura algo "vidriosa" en pelo y fondo (TAE + Q4_0) |

Conclusiones:
- **Resolución:** TI2V-5B (y su Turbo) se entrenó a 1280×704; por debajo de 480p el vídeo largo se rompe. Solo usar 832×480 / 480×832 (y 1280×704 más adelante), con cuadros 4n+1.
- **Velocidad:** 73 s por paso a 480p y 25 cuadros con ggml-hexagon. La decodificación con TAE en el procesador ya pesa casi la mitad.
- **Memoria:** el VAE completo de Wan no cabe entero en el teléfono; hace falta decodificar por cuadros o por teselas.

## Enfoque: solo imagen a vídeo + prompt

La pantalla Vídeo partiría siempre de una imagen generada en la app y una descripción de lo que hace. Ventajas:
- **Coherencia:** el primer cuadro es la imagen exacta, así que el personaje, el estilo y la composición no cambian; el modelo solo inventa el movimiento.
- **Calidad:** la imagen ya viene de nuestros modelos (Anima, Qwen), con más detalle que lo que Wan dibujaría solo.
- **Más simple:** un solo modo, siempre a 832×480 o 480×832 (recortando la imagen al centro), con el prompt sugerido a partir de las etiquetas de la imagen.
- **Fluidez (fps):** Wan 2.2 genera a 24 fps. Para ahorrar tiempo se puede generar a 12 fps (la mitad de cuadros, la mitad de tiempo) y duplicar los cuadros con **interpolación RIFE**; la interpolación de RIFE ya se ha medido en tiempo real en Snapdragon (8 Gen 3, QNN).

Modelos solo de imagen a vídeo revisados: Wan2.1-Fun-1.3B-InP (Apache 2.0, 16 fps, 4× más ligero que el 5B, pero sin destilado de pocos pasos confirmado y de menor calidad), Wan 2.2 I2V-A14B (demasiado grande para 12 GB a nuestra velocidad), MobileI2V y Mobile Video Diffusion (sin prompt). **Wan 2.2 TI2V-5B sigue siendo el mejor**: ya hace imagen a vídeo nativa con prompt. FastWan2.2-TI2V-5B (3 pasos) es una alternativa al Turbo para comparar.

## Camino propuesto

**Fase 1 — Calidad (prototipo por ADB)**
1. Imagen a vídeo a 480p con `-i` (Wan TI2V fija el primer latente con la imagen).
2. Decodificador: probar **Turbo-VAED para Wan 2.2** (hustvl, 2× más rápido que el VAE de Wan con igual o mejor PSNR) y los **grafos QNN v73 del VAE de PulseX** (decodifican cuadro a cuadro, w8a8, Apache 2.0). Elegir el más nítido que quepa en memoria; TAE queda como modo rápido.
3. Cuantización: comparar Q4_0 con Q4_0 + `ffn.2` en Q8_0 (lo que usa PulseX) en nitidez y detalle.
4. Movimiento y prompt: probar 4, 6 y 8 pasos y frases de acción; si Turbo se mueve poco, probar el modelo base con un LoRA de pocos pasos.

**Fase 2 — Velocidad**
5. Decodificador en la NPU (QNN, como LTX).
6. Llevar los bloques del DiT de Wan a nuestros kernels mega de Hexagon (como Qwen y MOSS): estimado ~10× más rápido que ggml-hexagon. Es el paso grande y el que hace práctico 2–5 s.

**Fase 3 — App**
7. Cambiar el motor de la pantalla **Vídeo** de LTX a Wan: descarga desde Modelos (~7 GB), 480p horizontal o vertical, 1–5 s, imagen a vídeo, cancelar, MP4 a la galería.
8. Duración larga encadenando clips con el último cuadro; "Ampliar el vídeo" a 720p/1080p con Real-ESRGAN.

**Fase 4 — LoRAs**
9. stable-diffusion.cpp aplica LoRAs de Wan al cargar. Hay pocos LoRAs para el 5B (la mayoría de Civitai son para 14B y no sirven); probar uno del 5B.

## Otras vías revisadas

- **MobileI2V** (hustvl, 0,27B, Apache 2.0): 17 cuadros a 720p en 2 s, pero en iPhone (Core ML), **sin prompt de texto** (solo imagen y cantidad de movimiento) y entrenado con caras y paisajes. No sirve para describir acciones.
- **Mobile Video Diffusion** (Qualcomm, basado en SVD): tampoco usa texto.
- **SnapGen-V, CineMobile, MobileWan, Neodragon:** artículos sin pesos utilizables.
- **Wan 2.2 14B + LoRA de 4 pasos:** funciona en nightmare-mobile solo desde el 8 Gen 3 y no cabe bien en 12 GB a nuestra velocidad.

## Fuentes

- Configuración oficial: https://github.com/Wan-Video/Wan2.2/blob/main/wan/configs/wan_ti2v_5B.py y https://huggingface.co/Wan-AI/Wan2.2-TI2V-5B
- Turbo de 4 pasos: https://github.com/quanhaol/Wan2.2-TI2V-5B-Turbo y GGUF en https://huggingface.co/hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF
- umT5 GGUF: https://huggingface.co/city96/umt5-xxl-encoder-gguf
- nightmare-mobile: https://github.com/AbrahamPaulJ/nightmare-mobile (tiempos en el issue #23)
- PulseX Wan: https://github.com/Erik-matrix/pulsex-wan y https://huggingface.co/Pexqman/Wan2.2-TI2V-5B-hexpack
- MobileWan: https://qualcomm-ai-research.github.io/MobileWan/
- Turbo-VAED: https://github.com/hustvl/Turbo-VAED (checkpoint Wan 2.2: https://huggingface.co/zy12123/Turbo-VAED-Wan2.2-TI2V-5B)
- MobileI2V: https://github.com/hustvl/MobileI2V y https://arxiv.org/abs/2511.21475
- GGUF de Wan 2.2 para la NPU (v81): https://huggingface.co/JiangFeng12/wan22-npu-models
- Resolución 720p del checkpoint: https://huggingface.co/unsloth/Wan2.2-TI2V-5B-GGUF
