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
- **Capturas:** guardar en `docs/screenshots/` una de cada función nueva, para el README y las notas del release.

El usuario pidió además dejar para el final investigar edición/composición con varias imágenes de referencia (p. ej. imagen 1 como personaje e imagen 2 como pose/meme) en Qwen/Mage; no está implementada ni validada.

## Primer paso al retomar

No empieces otra generación Mage. Comprueba el estado real de la descarga Qwen en Android user 999 y confirma que el perfil principal user 0 conserva su Qwen/MOSS. Reanuda desde el punto existente si es posible. Después continúa con las tareas pendientes, documenta cada evidencia real y mantén el objetivo activo hasta completar la lista.

---
