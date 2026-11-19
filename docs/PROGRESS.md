# HexAnima — tablero de trabajo

Actualizado: 19 nov 2026. Los porcentajes distinguen código implementado de pruebas completadas; una función no pasa a 100% hasta verificarla.

## Objetivo del proyecto

Adaptar HexAnima a Snapdragon 8 Gen 1/8+ Gen 1, 8 Gen 2, 8 Gen 3, 8 Elite, 8 Gen 5 y 8 Elite Gen 5. La meta es una APK universal cuando las rutas y dependencias de cada arquitectura sean compatibles; durante el desarrollo se distribuirán APK Dev para pruebas físicas por chip y se registrarán fallos por función/modelo.

## Orden actual — funcionalidades primero, ports después

| Objetivo | Progreso | Estado |
|---|---:|---|
| Metadatos PNG y lectura en Civitai | ██████████ 100% | El usuario confirmó que comprobó la generación, upscale y metadatos en Civitai. |
| Gestos y confirmaciones de interfaz | ██████████ 100% | El gesto de cerrar el visor ya existía; confirmar al detener generación o borrar modelo se compiló e instaló en Dev. |
| Estimación de duración | ███████▌░░ 75% | Ya aprende por modelo, tamaño y llamadas; queda valorar temperatura/estado del teléfono. |
| Guardado automático y carpeta SAF | ██████████ 100% | En el POCO se eligió una carpeta SAF de prueba, se concedió acceso, una generación se copió allí con PNG válido y metadatos iTXt; después se restauraron Galería, autoguardado apagado y modelo descargado. |
| Catálogo de modelos sin conexión | ██████████ 100% | Probado sin red: usa la caché y desactiva las descargas; conexión restaurada después. |
| Importar/exportar packs de modelos | █████████▌ 95% | En el POCO, un manifiesto incompleto se rechazó sin crear archivos ni marcador de instalación; el caso de hash incorrecto tampoco dejó archivo ni `.part`. Se corrigió el respaldo del VAE y del codificador compartido, se reforzó la validación del pack y se añadió rollback transaccional; falta probar exportación y cancelación con la APK final. |
| Cola de generación | █████████░ 90% | El código permite cambiar la cantidad pendiente durante una tanda y mantiene fija la imagen activa; las cuadrículas comparativas no se pueden alterar. Falta validarlo en el teléfono. |
| Errores de carga y progreso | ███████░░░ 70% | La Dev finaliza la espera si el runner Anima no arranca o no queda listo y muestra un diálogo con chip y últimas líneas del registro. Compilación y prueba física pendientes; luego comparar el progreso de generación con upstream. |
| Krea 2 Turbo en Snapdragon 8 Gen 2 | ░░░░░░░░░░ 0% | Pendiente estudiar requisitos y probar primero en el POCO F6 Pro; si funciona, evaluar otras familias Snapdragon. |
| SeedVR2 / RIFE | Pendiente | Medir almacenamiento, memoria, temperatura y velocidad antes de adaptar. |
| LTX-2.5 | Pendiente | Comparar requisitos con el prototipo LTX-Video 2B, recordando que son arquitecturas distintas. |
| MiniMax-H3-Image / Ref2VA | Pendiente | Estudiar integración, recursos y licencias después de las evaluaciones anteriores. |
| Adaptación multi-chip y APK Dev por SoC | En espera del bloque funcional | Meta final: cubrir todos los chips indicados y converger a APK universal. Tras cerrar y priorizar las funcionalidades, se retoma la candidata SM8650/V75 y se evalúan las demás arquitecturas. |

## Matriz de chips

| Snapdragon | SoC | Hexagon | Estado de HexAnima |
|---|---|---|---|
| 8 Gen 1 / 8+ Gen 1 | SM8450 / SM8475 | V69 | La app tiene ruta V69; el fallo reportado requiere reproducirse y corregirse antes de ofrecer APK de prueba. |
| 8 Gen 2 | SM8550 | V73 | POCO F6 Pro de referencia; ruta actual probada para generación y metadatos. Próximo banco para Krea 2 Turbo y regresión de funciones. |
| 8 Gen 3 | SM8650 | V75 | APK candidata reutiliza kernels/contextos V73 según el reporte, añade alias QNN V75 y reduce Anima de 40 bloques a 3 workers (14/14/12); falta validación física y por función. |
| 8 Elite | SM8750 | V79 | Desactivado hasta resolver la corrupción/HMX observada en pruebas previas. |
| 8 Gen 5 | SM8845 | V81 | Faltan toolchain/contextos V81 y prueba física. |
| 8 Elite Gen 5 | SM8850 | V81 | Faltan toolchain/contextos V81 y prueba física. |

## Próximos hitos

1. Probar en el POCO la APK final de importación/exportación y el aviso cuando falla la carga; después validar la cola y cerrar las mejoras de gestión de modelos.
2. Estudiar Krea 2 Turbo en el Snapdragon 8 Gen 2.
3. Evaluar SeedVR2/RIFE midiendo almacenamiento, RAM, temperatura y velocidad en el teléfono.
4. Estudiar LTX-2.5 usando LTX-Video 2B solo como referencia; luego abordar MiniMax-H3-Image y Ref2VA considerando integración, recursos y licencias.
5. Cuando el bloque funcional esté atendido, retomar el trabajo de ports: resolver el fallo Gen 1, validar la candidata Gen 3 y preparar APK Dev para cada chip; mantener una matriz de regresión por chip, modelo y función.
6. Consolidar una APK universal cuando las rutas de arquitectura estén probadas y sean compatibles.

## Último APK Dev

- El POCO F6 Pro conserva la Dev instalada `com.hexanima.npu.dev`, versión `1.9.5-v73`.
- APK candidata SM8650 recién compilada y firmada: incluye rutas V73 para Anima, MOSS, Qwen, Mage, detectores, pose y upscalers; alias QNN V75; y 3 workers para Anima de 40 bloques.
- Firma y paquete verificados (`com.hexanima.npu.dev`, etiqueta HexAnima Dev). ADB no logró iniciar su servidor en esta sesión, así que no se instaló ni probó en el POCO; la validación V75 queda pendiente en el teléfono Snapdragon 8 Gen 3.
- APK local: `app/hexanima-dev.apk` (ignorado por Git; no se incluye en el commit).
