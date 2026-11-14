# Mage-Flow-Edit-Turbo en Snapdragon 8 Gen 2 V73

## Estado

El port experimental ya integra `Mage.java`, la plantilla de texto Mage, la administración del pack, el runner Android de la release upstream y un script que selecciona el skeleton V73 local. El gestor descarga el pack a la carpeta Mage y verifica cada archivo por SHA-256. El APK Dev compiló y se instaló en el Poco F6 Pro; la pantalla confirmó el modelo.

La primera generación real en SM8550 se detuvo a los 120 s en «Encoding text on NPU…». Se repitió la prueba el 14 nov 2026 con el pack ya instalado y Mage habilitado temporalmente en el mismo APK Dev: a los 170 s el runner seguía activo sin avanzar. `mage.log` conservó únicamente `ready` y `loaded 0.3`; no registró `te:`, salida del encoder, ni un paso DiT. Se detuvo desde la app, sin generar imagen. La temperatura de batería fue 29,2 °C y Android thermal status 0.

En esta repetición, el SHA-256 del skeleton guardado por la app fue `0ae4c87b90104e508c0d5ac7ba14f12b00a9362b5dd7d1d2638315769cff44bc`, idéntico al build local `hexagon/mega/out/libmega_skel_v73.so`. Esto descarta que la ejecución estuviera usando un skeleton distinto al validado localmente; no determina si el DSP está trabajando muy lentamente o si la RPC quedó bloqueada. La UI solo cambia de «Encoding text on NPU…» cuando el runner escribe `te:` al completar todo el encoder, así que por ahora no ofrece progreso interno de sus 36 capas. Mage permanece fuera del selector y el pack instalado se conserva para diagnóstico.

El skeleton V69 extraído de upstream no se distribuye como V73. Se probó el skeleton Hexagon V73 local con el pack completo: carga inicial correcta, ejecución del encoder aún no validada. No afirmar compatibilidad completa por el mero `loaded 0.3`.

## Arquitectura que hay que portar

La configuración oficial de [Mage-Flow-Edit-Turbo](https://huggingface.co/microsoft/Mage-Flow-Edit-Turbo/blob/main/transformer/config.json) fija 12 bloques DiT, `hidden_size=3072`, 24 cabezas, `context_in_dim=2560`, `in_channels=128`, `patch_size=1`, packing multimodal y agenda `z-image` de cuatro pasos (`static_shift=6`). Es un diseño distinto del DiT Qwen-Image local (32 bloques, canales/anchos distintos), por lo que no se puede reutilizar el runner Qwen cambiando solamente el número de capas.

### Runner encontrado en la release upstream

La release upstream [v1.5.6](https://github.com/ayanoby/hexanima/releases/tag/v1.5.6) incluye `libmagerun.so` (6.049.144 bytes) y `libmageskel.so` (106.160 bytes), aunque no están en Git. El APK upstream revisado tiene SHA-256 `c2213a321c8cf03674313cf9fc96ecb3f46af2794d49f963a34008dbbd035ac6`. `libmagerun.so` es un ejecutable AArch64 para Android 31; importa `libcdsprpc.so` y carga `libmega_skel.so` por FastRPC. `libmageskel.so` es ELF Hexagon v69 (el comando de build está en su debug info), así que no se puede copiar como si fuera compatible con V73.

La interfaz RPC usada por el binario upstream exporta `mega_open`, `mega_block`, `mega_close` y otros métodos genéricos que también aparecen en `hexagon/mega/mega.idl` de esta rama. En el Poco F6 Pro se arrancó el runner con `libmega_skel_v73.so`: con un directorio vacío devolvió `ready` y el error esperado `error glue`; luego, con el pack completo, devolvió `ready` y `loaded 0.3`. El pack se descargó desde una revisión fija (`1937989fd5327472322d273366aa9ee8d944729d`), se verificaron los SHA-256 de 82/82 archivos en el PC y ADB transfirió los 8.710.533.044 bytes al teléfono. Esto verifica arranque FastRPC y carga inicial del pack en V73, pero aún no prueba un paso del DiT ni una generación. El pack está en `/data/local/tmp/hexanima-mage-v73-test/model/upstream-pack`; el runner usa una carpeta temporal separada. No hubo generación durante esta carga. El host runner y su aviso MIT deben acompañar cualquier APK de prueba/distribución.

El modelo usa encoder Qwen3-VL y Mage-VAE; la edición además necesita el tower de visión y codificación de la foto. El pipeline empaqueta texto e imágenes de referencia en una atención conjunta con RoPE multimodal y resolución variable. La configuración y los archivos originales están en Diffusers (`transformer/`, `vae/`, `scheduler/`, `text_encoder/`). El VAE Mage usa 128 canales latentes y reducción espacial de 16×. La edición admite múltiples imágenes de referencia.

La implementación oficial informa un pico de 18–20 GB en una A100 para 1024×1024; esa cifra no es una medida del Poco, pero sí confirma que la ruta BF16 de escritorio no cabe directamente en un teléfono de 12 GB. El port V73 necesita pesos cuantizados y transmitidos desde almacenamiento, activaciones acotadas, contextos compatibles con QAIRT 2.28 y kernels Hexagon específicos. La pérdida de calidad por cuantización debe medirse antes de decidir si el modelo es práctico.

Hay dos comportamientos distintos que no deben confundirse: el pipeline Python de Microsoft incluye un filtro de texto y una marca de agua Gaussian-Shading; el aviso del pack móvil de upstream declara explícitamente que ninguno forma parte de su runner Android convertido. Por tanto, el port a V73 no puede afirmar paridad con el pipeline Python ni que esos componentes estén disponibles hasta implementarlos y probarlos por separado. Debemos conservar el aviso MIT/Qwen de terceros con el pack y documentar con precisión qué ruta se integra.

## Pack preconvertido de upstream

El manifiesto público de `ayanoby/hexanima-mage-packs` se comprobó de nuevo el 14 de noviembre de 2026: 82 archivos, 8.710.533.044 bytes; 12 bloques DiT (`262.836.224` bytes cada uno), 36 capas de texto (`113.566.720` bytes cada una), 24 capas de visión (`20.676.608` bytes cada una), `glue.bin`, embeddings/tokenizer y VAE. No incluye `libmagerun.so`, `libmageskel.so` ni contextos QNN. La licencia [MIT notice del pack en upstream](https://github.com/ayanoby/hexanima/blob/main/app/assets/licenses/mage_notice.txt) describe sus `.bin` como copias cuantizadas a 8-bit y reordenadas para HMX, pero advierte que el filtro y la marca de agua del pipeline oficial no se portaron. Los pesos listos ahorran conversión, no la implementación del runner ni la validación de compatibilidad con QAIRT 2.28/V73.

## Trabajo técnico pendiente

1. Comparar la tokenización y los IDs del prompt de Mage con el tokenizer de referencia.
2. Instrumentar el runner para identificar cuál de las 36 capas de texto bloquea la primera llamada FastRPC; comparar carga y salida con el runner upstream y el skeleton V73.
3. Validar el encoder de texto primero con un prompt mínimo y un test de una sola capa, incluyendo tiempos por RPC y presión de memoria.
4. Después de corregirlo, probar los cuatro pasos DiT, VAE, salida RGB y edición; registrar memoria, cancelación y temperaturas.
5. Mantener Mage fuera del selector hasta completar la primera generación V73 y la edición de una foto. El pack puede permanecer descargado para diagnóstico.

## Diagnóstico del 14 nov 2026 (bloque 6)

- **Causa probable del atasco:** en V73, `mage.sh` copia `libmageskel_v73.so`, que en `tools/stage_jnilibs.sh` es el kernel de Anima (`hexagon/mega/out/libmega_skel_v73.so`). Ese kernel no tiene modos de codificador de texto, de visión ni de DiT de Mage. Un modo desconocido no devuelve error: cae en el camino genérico de bloque y trabaja con una geometría equivocada. Eso encaja con un runner que se queda en «Encoding text on NPU…» sin escribir `te:`.
- **Qué pide el runner upstream:** `libmagerun.so` lee `te%02d.bin`, `tefinal.bin`, `vit/vit%02d.bin`, `vit/vit_glue.bin`, `dit/blk%02d.bin` y `vaepack.bin`, y su perfil nombra un `mode50(ln1 q k lnv v o ln2 fc1 fc2)`. Ninguna fuente pública de upstream (`hexagon/`, `moss/hexagon/`, `qwen/hexagon/`) implementa el modo 50: el kernel de Mage solo se publicó como binario V69 (`libmageskel.so`).
- **V69 en V73 no es salida:** el commit ca1bbb6 midió que los kernels V69 corren en V73 pero cada resultado fp16 de HMX sale mal, porque V73 guarda a través de la unidad de conversión.
- **Siguiente prueba barata:** usar `libqwenskel_v73.so` en `mage.sh`. Ese kernel tiene el modo 22 (capa de texto Qwen3-VL) y el 23 (visión Qwen3-VL). Si el codificador pasa `te:`, el problema queda acotado a los modos propios de Mage (modo 50 y DiT).
- **Resultado de la prueba con el kernel de Qwen V73 (14 nov 2026):** ya no se atasca. La primera llamada del codificador de texto falla en unos 20 s con `mega_block r=0x8000040d rc=0 mode=22` y `error te`. Esto confirma que el runner usa el modo 22 (capa de texto Qwen3-VL). El modo 22 de nuestro kernel está escrito para Qwen3-VL de 4096 de ancho (Qwen-Image); el codificador de Mage es Qwen3-VL-4B, con 2560 de ancho, 36 capas, 32 cabezas de consulta y 8 de clave/valor, y FFN de 9728. `mage.sh` usa ahora este kernel en V73, para que falle rápido en vez de colgarse. Mage sigue oculto.
- **Lo que haría falta:** generalizar el modo 22 al ancho que mande el runner (80 bloques de 32 en vez de 128) y averiguar qué modos usa después para el DiT (12 bloques de 3072), la visión (modo 50) y el VAE (`vaepack.bin`). Para eso hay que registrar las llamadas del runner.
- **Opciones:**
  1. Pedir a upstream el código o una compilación V73 del kernel de Mage. Es lo más rápido.
  2. Registrar las llamadas FastRPC del runner (modo y meta de cada `mega_block`) con una librería intermedia e implementar esos modos en el kernel V73. Son días de trabajo, con validación capa por capa.
  3. Dejar Mage oculto: Qwen ya cubre la edición.

## Criterios para decidir si se integra

- La salida del tokenizer, encoder y VAE coincide con el pipeline de referencia dentro de tolerancias acordadas.
- Una generación y una edición 512×512 terminan en el teléfono, sin NaN, corrupción de color ni bloque marrón.
- La edición mantiene la composición y los píxeles fuera de una máscara cuando se use una máscara.
- La prueba registra tiempo, RAM, temperatura de batería/superficie y recuperación después de cancelar.
- El pack cabe en el almacenamiento que el gestor declara y su licencia/atribución acompaña la distribución.

## Referencias oficiales

- [Mage-Flow-Edit-Turbo en Hugging Face](https://huggingface.co/microsoft/Mage-Flow-Edit-Turbo)
- [Configuración oficial del transformer](https://huggingface.co/microsoft/Mage-Flow-Edit-Turbo/blob/main/transformer/config.json)
- [Código y documentación Mage-Flow de Microsoft](https://github.com/microsoft/Mage/tree/main/mage_flow)
- [Pipeline oficial de edición y referencias múltiples](https://github.com/microsoft/Mage/blob/main/mage_flow/pipeline.py)
- [Filtro de contenido obligatorio en el encoder Mage](https://github.com/microsoft/Mage/blob/main/mage_flow/models/modules/mage_text.py)
- [Modelo Qwen3-VL usado por el encoder Mage](https://github.com/microsoft/Mage/blob/main/mage_flow/models/modules/mage_text.py)
- [Manifiesto público del pack Mage de upstream](https://huggingface.co/ayanoby/hexanima-mage-packs/resolve/main/manifest.json)
