# Relevo para Claude — HexAnima V73

Copia el bloque siguiente en Claude para continuar el trabajo.

---

Eres el agente técnico que continuará HexAnima para el Snapdragon 8 Gen 2. Trabaja en el checkout local `C:\Users\VICTUS\Desktop\Grimm Waves\Hexanima`; el repositorio destino es **https://github.com/GrimmDev-Glitch/hexanima**. No mezcles estos cambios con el upstream `ayanoby/hexanima`.

## Objetivo

Completar y verificar la lista acordada en esta rama V73: controles por capacidad de modelo; instalación limpia de Qwen V73; MOSS RealSkin y el diagnóstico de LoKr; evaluación de Mage-Flow-Edit-Turbo; Civitai por familia; textos español/inglés; documentación; APK firmado, capturas y release. **No publiques ni crees el release antes de acabar las pruebas y preparar el paquete. Excluye MiniMax-H3.** El usuario trabaja con **HexAnima Dev** para las pruebas; no crees una app clon nueva.

Antes de editar, revisa `git status`, `git diff`, `README.md`, `CHANGES.md`, `docs/MAGE_V73_PORT.md` y el estado real del teléfono. Hay muchos cambios locales sin commit y artefactos de prueba; no los borres, reviertas en bloque ni sobrescribas sin entenderlos. Conserva los datos del perfil principal del teléfono.

## Estado verificado

- **Controles por modelo:** Anima conserva pasos/CFG/sampler propios. MOSS muestra pasos que soporta (1–4; RealSkin tiene modo DMD 8-step), mientras CFG y samplers alternativos no implementados están ocultos. Qwen usa cuatro pasos fijos y oculta esos controles. La preview por paso debe permanecer solo en Anima; Qwen y MOSS muestran el resultado final.
- **RealSkin MOSS:** el modo DMD de 8 pasos está portado y completó una generación 512×768 en el Poco F6 Pro en 58 s. Cancelar en 2/8 devolvió el runner a `ready`. Harem y RealSkin MOSS base también generaron en V73. Revisar `CHANGES.md` y `README.md` para detalles/capturas.
- **LoKr MOSS:** se aparcó deliberadamente. El archivo `MOSSRealisticSnapshotV1.safetensors` no llegó a generar de forma fiable: hubo esperas largas y resultados cuadrados marrones. LoKr queda deshabilitado; no dedicar más pruebas al mismo adaptador si no aparece una hipótesis técnica nueva. Los adaptadores MOSS A/B compatibles sí tienen pruebas en teléfono.
- **Qwen-Image 2.1 Turbo:** ruta V73 integrada y ya probada para generación y edición; companion V73 de aproximadamente 694 MB verificado con hashes. La instalación limpia de la APK y el primer inicio pasaron en XSpace, pero no se había descargado ni probado el modelo en ese perfil.
- **Prueba de instalación limpia en curso:** el 14 nov 2026 se inició desde el gestor una descarga de Qwen Turbo de 15,6 GB en XSpace (usuario Android 999). Último estado observado: **5,9/15,6 GB**, descargando `te/te27.bin`, batería 54 %, batería a 32,7 °C, Android thermal status 0 y 70 GB libres. El usuario pidió detener la actividad; se envió un toque al botón «Cancelar», pero no se pudo verificar que la descarga terminara. Al reanudar, inspecciona primero el perfil 999 y comprueba si el parcial se conservó; reanuda o limpia solo el parcial del perfil de prueba. No descargues otra vez desde cero si puede reanudarse.
- **Mage-Flow-Edit-Turbo:** integración experimental de app, tokenizer, picker de foto, catálogo/pack y host runner upstream con skeleton local V73; pack de 8,71 GB presente en el perfil principal. Dos pruebas llegaron a «Encoding text on NPU…» durante aproximadamente 120 s y 170 s. `mage.log` terminó solo con `ready` / `loaded 0.3`; no apareció `te:`, salida del encoder ni paso DiT; no se generó imagen. En la segunda prueba, SHA-256 del skeleton usado y del build local coincidió: `0ae4c87b90104e508c0d5ac7ba14f12b00a9362b5dd7d1d2638315769cff44bc`. Se detuvo sin sobretemperatura (29,2 °C en batería, Android thermal status 0). La causa sigue sin distinguir si FastRPC está bloqueada o el encoder va extremadamente lento. **Mage está oculto en el selector**; no lo vuelvas a habilitar para usuarios hasta completar generación V73. Informe: `docs/MAGE_V73_PORT.md`.
- **Civitai:** enlaces de modelos/cuenta abren `civitai.red`; la llamada API de búsqueda/descarga aún usa el dominio API soportado. Anima y MOSS A/B tienen rutas directas validadas. Para Qwen, Safetensors/metadata se descargan y se convierten en PC a overlay ZIP V73, luego se importan; una LoRA Qwen de Civitai ya produjo una generación correcta en teléfono. Mage no está habilitado para Civitai.
- **Traducciones y docs:** la interfaz usa inglés de fuente con cadenas `L.s`/`L.f` y `app/assets/lang/es.tsv`; una auditoría anterior dio cero claves sin español. Verifica de nuevo los strings nuevos. `README.md`, `CHANGES.md` y `docs/MAGE_V73_PORT.md` registran pruebas y límites; el trabajo local aún no está publicado.
- **Dev instalado:** el mismo `app/hexanima-dev.apk` se reconstruyó e instaló después de la prueba Mage, con `Mage.V73_RUNNER_VALIDATED=false`; abrió en perfil principal y mostró Qwen seleccionado. No se generó otro paquete de app. No hay cambios de este trabajo publicados en GitHub.

## Qué falta

1. Verificar la cancelación/parcial de Qwen en XSpace; terminar descarga y validación de todos los ficheros y del companion requerido; seleccionar Qwen y completar al menos una generación desde el perfil limpio. Confirmar datos intactos del perfil principal y registrar hash, tiempo, RAM y temperatura.
2. Mage: investigar en local y con código fuente cómo instrumentar/diagnosticar las 36 capas del text encoder y la espera FastRPC; comparar shape/tokenizer/contextos, salida por capa y runner. No confundir `ready`/`loaded 0.3` con compatibilidad. Mantener oculto hasta lograr generación y edición de una foto completas en el Poco.
3. LoKr MOSS y `MOSSRealisticSnapshotV1.safetensors`: conservar diagnóstico y decisión de aparcar; solo retomar con nueva evidencia de compatibilidad o una solución concreta. No bloquear el resto de la lista por este LoKr.
4. Completar Civitai por familias solo cuando runner/formatos estén probados; Mage sigue pendiente. Revisar mensaje de LoRA familiar, compatibilidad y activadores/triggers.
5. Revisar y completar traducciones nuevas y documentación de cambios.
6. Hacer pruebas largas/térmicas pendientes en el teléfono (MOSS, Qwen, cancelación y tandas) sin continuar si la temperatura o la memoria no son seguras.
7. Al completar lo anterior, preparar APK **release firmado**, capturas limpias de las funciones, revisar README/CHANGES/licencias, instalar y verificar el paquete; entonces publicar en **GrimmDev-Glitch/hexanima** según la autorización del usuario. No publicar antes.

Actualización del 14 nov 2026:
- **Bloque 1 hecho e instalado en Dev:** editor de máscara sin pintar al pellizcar y con botones Pincel/Borrador/Mover; botón Importar en las bibliotecas de LoRA; MOSS con 4 pasos fijos y RealSkin con interruptor 4/8 DMD.
- **XSpace:** la descarga de Qwen se detuvo con `te/te31.bin.part`. El parcial se conserva y se puede reanudar.
- **Orden acordado de los siguientes bloques:**
  2. Prueba limpia de Qwen en XSpace.
  3. Calidad de edición Qwen: máscara difuminada, deslizador antes/después y referencia a más resolución.
  4. Funciones de upstream: caché de texto, contadores, renombrar LoRAs y modelos, metadatos, varias LoRAs con fuerza, ZIP.
  5. Categorías de LoRAs y Civitai Qwen directo; LoRAs Qwen sin convertir o convertidas en el teléfono, con intensidad ajustable.
  6. LoKr mediante una caché de bloques q8 ya modificados, y Mage.
  7. Traducciones, documentación y release.
  8. Varias imágenes de referencia y fusión.
- **Revisión visual completa (antes del release):** recorrer todas las pantallas y diálogos, comprobar que todo usa el tema actual (lavanda claro/oscuro) y la fuente Barlow Semi Condensed, corregir lo que se vea fuera de estilo y proponer una organización más clara de la pantalla principal y de los ajustes, con capturas antes/después.
  - Pantallas: principal, Historial, visor, Modelos y catálogo, Civitai, Ajustes, Info y Bienvenida.
  - Diálogos: selectores de modelo, tamaño y LoRA; máscara; antes/después; plantillas; etiquetas; ZIP; renombrar; diálogos del sistema que salen con el aspecto por defecto de Android.
  - Elementos sueltos: interruptores, barras de progreso, toasts y menús emergentes.
- **Al final, instalar la release en el teléfono del usuario sin perder sus datos.**
  - **Situación:** el teléfono tiene solo `com.hexanima.npu.dev` (firma de desarrollo), con unos 55 GB de modelos en su almacenamiento privado (Anima 11 GB, MOSS 13 GB, Qwen 23 GB, Mage 8 GB). La release es `com.hexanima.npu`, firmada con otra clave: se instala como otra app y no puede leer los datos de Dev. Al 14 nov 2026 hay 68 GB libres.
  - **Plan:**
    1. Añadir a la app la importación de modelos desde una carpeta de preparación, `/sdcard/Android/data/com.hexanima.npu/files/import/`: mover archivo por archivo y verificar SHA-256 con los manifiestos, para no necesitar el doble de espacio.
    2. Copiar los packs desde Dev con `adb shell "run-as com.hexanima.npu.dev cat … > /sdcard/Android/data/com.hexanima.npu/files/import/…"`, por familias.
    3. Abrir la release, verificar una generación por familia y solo entonces, con permiso del usuario, desinstalar Dev.
  - **Conservar también** el historial (`files/gallery`), los ajustes, los nombres propios (`model_names.json`, `lora_names.json`), las LoRAs y sus JSON, y las plantillas (preferencias).
- **Lo último de todo, antes del release: limpiar qué va a GitHub.** Revisar qué vale la pena publicar. Por ejemplo, `docs/CLAUDE_HANDOFF.md` (este prompt) no debería quedar en el repositorio público. Lo mismo para scripts de prueba locales, notas de diagnóstico y archivos que solo sirven en este PC. Sacarlos con `git rm --cached` y añadirlos a `.gitignore`, conservándolos en disco.
- **Mejorar fotos existentes con un escalador (pedido por el usuario):**
  - Empezar con **Real-ESRGAN General x4v3** (unos 3,5 MB, GPU/CPU): la opción más fácil de probar, como en LLM Hub.
  - Después, en NPU, comparar con **UltraSharp Realistic Upscaler** sobre la misma foto, ampliando ojos y manos, y quedarse con el que conserve mejor párpados, iris y dedos.
  - Avisar en la interfaz que, si la foto original está muy borrosa, el escalador puede inventar detalles.
  - Encaja con la sugerencia 2 (ampliación ×2 en la NPU para Qwen y MOSS).
- **Caché de codificaciones de texto (upstream 1.5.4): pendiente.** Primero hay que reconstruir un árbol stable-diffusion.cpp que reproduzca el `libsdcli.so` actual; ver CHANGES.md.
- **Nueva familia solicitada (después de la lista actual):** vídeo con LTX-Video 2B 0.9.8 Distilled en NPU V73, texto a vídeo e imagen a vídeo, con controles de pasos, ancho, alto, fotogramas, velocidad de fotogramas (fps), semilla y prompt negativo opcional. Hay que evaluar la viabilidad: DiT de vídeo, VAE causal de vídeo, memoria, tiempos y codificación MP4 en el teléfono.
- **Pendientes pequeños de interfaz:** «✕» en la imagen generada para volver a «Tu imagen aparecerá aquí» (sin borrarla del Historial); deslizador antes/después desde el Historial; el selector muestra Qwen como «2B».
- **Capturas:** guardar en `docs/screenshots/` una de cada función nueva, para el README y las notas del release.

## Añadidos del usuario (14 nov 2026, tarde)

- **Hecho en Dev (sin release):** la intensidad de la LoRA de Qwen se puede escribir con el teclado tocando el número. Con una pulsación larga se editan las palabras activadoras (o una frase activadora) de cualquier LoRA de Qwen, MOSS o Anima y se añaden al prompt. Las ediciones de Qwen salen por defecto con la forma de la foto (vertical, horizontal o cuadrada, con un tamaño parecido al elegido), con el interruptor «Forma de la foto» para usar exactamente el tamaño elegido. Mage y las futuras ediciones de MOSS y Anima deben seguir la misma regla.
- **Calidad (detalles deformes y pixelados al ampliar):** diagnóstico, en las últimas ediciones del teléfono:
  - Salían a 1024×768 horizontal aunque la foto era vertical o cuadrada: se recortaba arriba y abajo y el modelo reinventaba la parte perdida. Ya corregido con «Forma de la foto».
  - El resultado ronda 0,4–0,8 Mpx: al ampliarlo en el visor se ve pixelado. Ahí entran el escalador (Real-ESRGAN / UltraSharp) y el detailer de caras y manos (sugerencias 1 y 2).
  - 4 pasos destilados más LoRA: ojos, orejas, dedos y pelo pierden definición. En gate/proj la LoRA del usuario se fusiona por SVD con Lightning a rango 64. Probar la misma edición sin LoRA y a intensidad 0,6–0,8 para separar la causa.
- **Varias imágenes de referencia (amplía el bloque 8):** «personaje de la imagen 1 con la ropa de la imagen 2». También partir de una hoja de referencia o de la imagen de un personaje para cambiar de estilo (3D, anime…) o de pose sin perder su identidad. En Anima, Qwen, MOSS o Mage, donde cada runner lo permita.
- **Correcciones después de generar:** especificación aparte en `docs/IMAGE_CORRECTIONS_FLOW.md` (y `docs/CLAUDE_IMAGE_CORRECTIONS_PROMPT.md`). Cara, Manos, Detalles, Ruido, Resolución y Otros, con alternativas, comparación, «Usar esta» y deshacer.
- **Estilo de la imagen de ChatGPT** (ilustración anime brillante, colores saturados, líneas limpias): evaluar Anima con un prompt de estilo y LoRAs de estilo de Civitai. Si no basta, buscar un modelo anime que corra en la NPU V73.
- **Modelos de LLM Hub** (son SD 1.5 en QNN: Absolute Reality, Realisian V6, Realistic Vision Hyper, SweetMix, Anything V5, ChilloutMix, CrossKemono, CuteYukiMix, DarkSushi, DreamShaper V8, HyperSpire, Majicmix Realistic V7, MeinaMix V12, Mistoon Anime V3, Nai Anime V2, NeverEndingDream, QteaMix):
  - HexAnima no tiene hoy un runner de SD 1.5. Habría que añadir una familia nueva, con un runner QNN de SD 1.5 (UNet, VAE, CLIP) y su catálogo.
  - Antes de integrar: revisar el origen de las conversiones para V73 (8 Gen 2), la licencia del runner y de cada modelo, y probar en el teléfono.

## Sugerencias y mejoras propuestas por Claude (al final de la lista; el usuario decide cuáles se hacen)

**Calidad de imagen**
1. **Detailer para Qwen y MOSS:** detectar caras y manos (face_yolov8n ya está en la app; falta hand_yolov8n), recortar cada zona, reeditarla con máscara y recomponer. Es lo que más ayudaría con dedos y ojos.
2. **Ampliación ×2 en la NPU** (un upscaler ligero tipo ESRGAN) para las salidas de Qwen y MOSS, que salen a unos 0,4–0,6 Mpx.
3. **Medir Alta fidelidad** contra la referencia normal con la misma semilla, y decidir con datos si se activa por defecto en ediciones sin máscara.

**Uso diario**
4. **Tandas grandes en varias cuadrículas** de 9 en vez de una sola con miniaturas diminutas.
5. **Antes/después y lado a lado también desde el Historial** y el visor.
6. **Ajustes recordados por modelo:** tamaño, prefijo de calidad y negativo por defecto al cambiar de modelo.
7. **Variaciones en tandas:** comodines en el prompt (`{rojo|azul|verde}`) y «variar semilla» a partir de una imagen que guste.
8. **Cola con prompts o modelos distintos**, y tiempo restante estimado de toda la tanda.
9. **Temperatura visible en la cabecera** junto a la RAM, y una notificación cuando la tanda se pausa por calor.
10. **Gestor de almacenamiento:** tamaño por pack y por caché (LoRA q8, `qwen/before/`, `edit/`), con botón para limpiar cada una.

**LoRAs y modelos**
11. **Un mismo panel de LoRA para Anima, MOSS y Qwen:** lista, fuerza con deslizador, renombrar, triggers y categorías. Hoy cada familia tiene su propia interfaz.
12. **Renombrar también los overlays de Qwen**; hoy solo se pueden renombrar las LoRAs de Anima y MOSS.

**Interfaz**
13. Ver la revisión visual de la lista. Además: diálogos propios en lugar de los de Android (evitar botones en MAYÚSCULAS), descripciones de accesibilidad en los botones de icono y zonas táctiles de al menos 48 dp.
14. **Breve recorrido o «novedades»** al abrir una versión nueva.

**Mantenimiento**
15. **Dividir `Main.java`** (unas 3000 líneas con funciones de una sola línea muy largas) en clases como EditPanel, LoraPanel, BatchView y MaskEditor. Así habrá menos errores al tocar una parte. Hoy mismo, poner un botón nuevo en la fila de la imagen rompió el diseño de esa fila.
16. **Comprobación automática en `app/build.sh`:** que falle si una cadena `L.s`/`L.f` nueva no tiene traducción en `es.tsv`.
17. **Script de pruebas por ADB:** generar una imagen por familia, una edición y una tanda, comprobar tiempos, temperatura y que la salida no sea negra ni marrón. Acortaría mucho cada release.
18. **«Exportar registros»** (`animad.log`, `qwen.log`, `moss` y datos del dispositivo) en un ZIP para reportar fallos.
19. **GitHub Actions** que compile la parte Java del APK con los binarios nativos ya generados, para que cada push quede verificado.

El usuario pidió además dejar para el final investigar edición/composición con varias imágenes de referencia (p. ej. imagen 1 como personaje e imagen 2 como pose/meme) en Qwen/Mage; no está implementada ni validada.

## Primer paso al retomar

No empieces otra generación Mage. Comprueba el estado real de la descarga Qwen en Android user 999 y confirma que el perfil principal user 0 conserva su Qwen/MOSS. Reanuda desde el punto existente si es posible. Después continúa con las tareas pendientes, documenta cada evidencia real y mantén el objetivo activo hasta completar la lista.

---
