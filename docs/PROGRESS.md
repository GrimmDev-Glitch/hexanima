# HexAnima — tablero de trabajo

Actualizado: 21 nov 2026. Los porcentajes distinguen código implementado de pruebas completadas; una función no pasa a 100% hasta verificarla.

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
| Krea 2 Turbo en Snapdragon 8 Gen 2 | ████████░░ 80% | En el POCO se extrajeron estados Qwen3-VL, se ejecutaron en el teléfono la fusión, ruido gaussiano desde semilla local, los 28 bloques Q8 × 8 pasos, VAE oficial Qwen Image y escritura PNG RGB. Dos extracciones dieron los mismos 12 estados byte a byte, las fusiones fueron idénticas y dos generaciones completas produjeron latente/RGB idénticos; PNG 256×256 creado en el teléfono. El VAE coincide con Diffusers CPU (RMSE 0,00225; correlación 0,99999) y el primer bloque DSP con referencia Q8 CPU (error relativo 1,01%; coseno 0,99997). Se compactan 22 tokens activos a 32 para evitar el error conocido con máscaras largas. Todavía faltan paridad completa/calidad a más resoluciones, manejo de recursos/licencia y salvaguardas de contenido e integración en la app. Pesos y artefactos permanecen en `work/` ignorado y en el directorio temporal del teléfono; no se publicaron. |
| SeedVR2 / RIFE | Pendiente | Medir almacenamiento, memoria, temperatura y velocidad antes de adaptar. |
| LTX-2.5 | Pendiente | Comparar requisitos con el prototipo LTX-Video 2B, recordando que son arquitecturas distintas. |
| MiniMax-H3-Image / Ref2VA | Pendiente | Estudiar integración, recursos y licencias después de las evaluaciones anteriores. |
| Adaptación multi-chip y APK Dev por SoC | En espera del bloque funcional | Meta final: cubrir todos los chips indicados y converger a APK universal. Tras cerrar y priorizar las funcionalidades, se retoma la candidata SM8650/V75 y se evalúan las demás arquitecturas. |

## Matriz de chips

| Snapdragon | SoC | Hexagon | Estado de HexAnima |
|---|---|---|---|
| 8 Gen 1 / 8+ Gen 1 | SM8450 / SM8475 | V69 | La app tiene ruta V69; el fallo reportado requiere reproducirse y corregirse antes de ofrecer APK de prueba. |
| 8 Gen 2 | SM8550 | V73 | POCO F6 Pro de referencia; ruta actual probada para generación y metadatos. Krea ejecutó codificador Qwen, fusión de texto, ruido desde semilla local, denoising Q8 (28 bloques × 8 pasos) y VAE oficial en el teléfono; dos recorridos completos y dos codificaciones de texto independientes fueron idénticos. |
| 8 Gen 3 | SM8650 | V75 | APK candidata reutiliza kernels/contextos V73 según el reporte, añade alias QNN V75 y reduce Anima de 40 bloques a 3 workers (14/14/12); falta validación física y por función. |
| 8s Gen 4 | SM8735 | V73 reportado; sin confirmar en tabla QAIRT | APK Dev de diagnóstico preparada con ruta V73 experimental; falta validar FP16, contextos QNN y cada función en un teléfono real. |
| 8 Elite | SM8750 | V79 | Pruebas OnePlus CPH2649: MOSS generó una imagen amarilla; los registros siguientes muestran NaN desde el primer bloque DiT en MOSS y Mage (VAE no ejecutado). Dev5 añade estadísticas de entrada/salida para aislar el problema. Sigue sin compatibilidad funcional validada; ESRGAN aún sin resultado comunicado. Anima/Qwen siguen bloqueados. |
| 8 Gen 5 | SM8845 | V81 | Faltan toolchain/contextos V81 y prueba física. |
| 8 Elite Gen 5 | SM8850 | V81 | Faltan toolchain/contextos V81 y prueba física. |

## Próximos hitos

1. Probar en el POCO la APK final de importación/exportación y el aviso cuando falla la carga; después validar la cola y cerrar las mejoras de gestión de modelos.
2. Validar la paridad de extremo a extremo de Krea y más resoluciones; corregir máscara DSP larga y revisar licencia/filtros antes de integrarlo en la app.
3. Evaluar SeedVR2/RIFE midiendo almacenamiento, RAM, temperatura y velocidad en el teléfono.
4. Estudiar LTX-2.5 usando LTX-Video 2B solo como referencia; luego abordar MiniMax-H3-Image y Ref2VA considerando integración, recursos y licencias.
5. Cuando el bloque funcional esté atendido, retomar el trabajo de ports: resolver el fallo Gen 1, validar la candidata Gen 3 y preparar APK Dev para cada chip; mantener una matriz de regresión por chip, modelo y función.
6. Consolidar una APK universal cuando las rutas de arquitectura estén probadas y sean compatibles.

## Último APK Dev

- El POCO F6 Pro conserva la Dev instalada `com.hexanima.npu.dev`, versión `1.9.5-v73`.
- Dev SD8E dev5 compilada el 20 nov: `1.9.6-v79-sd8e-dev5`, código 42; paquete `com.hexanima.npu.dev`. Firma, manifiesto y librerías MOSS/Mage verificadas; SHA-256 `8c9e8c49e73db681ef3f61d77e1e457fee6b3d4a4c757f9f89402b15b9a586ce`.
- La Dev SD8E habilita generación experimental de MOSS/Mage y ESRGAN V79; Anima/Qwen se bloquean. El OnePlus CPH2649/SM8750 reportó primero una imagen amarilla con MOSS y después registros con valores no finitos en la salida del primer bloque DiT de MOSS y Mage; el VAE no llegó a ejecutarse. Dev5 registra rango/valores de entrada y salida del primer bloque. Falta probar esta APK y comparar los diagnósticos. APK y contextos binarios ignorados por Git.
- La candidata SM8650/V75 anterior incluía rutas V73 para Anima, MOSS, Qwen, Mage, detectores, pose y upscalers; alias QNN V75; y 3 workers Anima (14/14/12). ADB no logró iniciar su servidor y quedó sin prueba física.
