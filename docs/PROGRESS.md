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
| 8s Gen 4 | SM8735 | Dev3/5 con QAIRT 2.50 reconocen el runtime, pero el firmware reporta stub V73 2.28 y `deviceCreate` falla por incompatibilidad (1008); Dev5 confirma que la configuración `socModel=85` se ignora en un destino real. Dev4 con QAIRT 2.28 coincide con el stub, pero no reconoce el SM8735 (`SnapdragonModel` desconocido) y falla al inicializar el backend (4000). Dev6 no alcanzó a cargar sus bibliotecas por rutas ZIP inválidas. Dev7 corrige el empaquetado y prueba QAIRT 2.51; pendiente resultado físico y compatibilidad con el stub remoto 2.28. Generación no validada. |
| 8 Elite | SM8750 | V79 | OnePlus CPH2649: Mage genera correctamente. Dev16/17/18 completan DiT y VAE sin valores no finitos, pero la imagen sigue siendo una textura lavada. Dev18 recalculó texto/refiner en su primera prueba y versionó su caché. La prueba 512×512 de Dev18 usó el contexto VAE de cuadro completo (`vaerun`, sin `vaetile`), por lo que el mosaico no es causa única. Dev19 repitió el aspecto a 512×512 y 1024×1024; la salida RGB bruta del VAE conserva bajo contraste antes del guardado (`sd` R/G/B ≈0.08/0.06/0.08 en ambas escalas), y en 1024×1024 el latente usa `vaetile`. Resolución y mosaico no explican por sí solos el fallo. Sigue sin estar aislado si el latente DiT o el contexto/modelo VAE es la fuente. Anima/Qwen siguen bloqueados. |
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

- **SM8735 / POCO F7:** [HexAnima Dev SM8735 diag7.apk](../app/HexAnima%20Dev%20SM8735%20diag7.apk), `1.9.6-v73.4-sm8735-dev7` (código 57), paquete `com.hexanima.npu.sm8735`, SHA-256 `45adaf89519f18b8fb8dab9a04d3bf71efb722878a05ca10fb3a819317d1013d`. Corrige el empaquetado de bibliotecas nativas usando separadores ZIP `/`; Dev6 quedó inutilizable porque Android no encontró `libanimad.so`. Incluye el mismo runtime QAIRT 2.51 y el diagnóstico `socModel=85`. Firma v3 y alineación verificadas. Pendiente prueba física para comprobar la creación del dispositivo/backend y compatibilidad con el stub remoto 2.28.
- **SM8735 / POCO F7:** [HexAnima Dev SM8735 diag6.apk](../app/HexAnima%20Dev%20SM8735%20diag6.apk), `1.9.6-v73.4-sm8735-dev6` (código 56), paquete `com.hexanima.npu.sm8735`, SHA-256 `41b21e370a4c4917565e169aa9856004b3bd76105700267d15b749dfcc42686a`. Incluía cuatro bibliotecas QAIRT 2.51 (22,104,408 bytes en total), pero el ZIP las guardó con separadores Windows `\\`; el registro 0032 confirma que Android no pudo cargar `libanimad.so`. Reemplazada por diag7.
- **SM8735 / POCO F7:** [HexAnima Dev SM8735 diag5.apk](../app/HexAnima%20Dev%20SM8735%20diag5.apk), `1.9.6-v73.4-sm8735-dev5` (código 55), paquete `com.hexanima.npu.sm8735`, SHA-256 `74cc8fd03f9bef17c5df73d8e969fdedfe167469e9c235457066383df4c05b2b`. En la prueba física, QAIRT 2.50 ignoró `socModel=85` en destino real y repitió el error de Dev3: stub esperado 2.50, remoto 2.28, `deviceCreate 0x3f0`. Firma v3, paquete/versión y alineación verificados; misma firma/paquete que Dev4. No genera imagen.
- **SM8735 / POCO F7:** [HexAnima Dev SM8735 diag4.apk](../app/HexAnima%20Dev%20SM8735%20diag4.apk), `1.9.6-v73.4-sm8735-dev4` (código 54), paquete `com.hexanima.npu.sm8735`, SHA-256 `521a176b1b577418e7800fe7b84394fb7b3e8672e84ca07166bbba8b32cee992`. Firma v3, alineación, metadatos y paquete verificados; reemplaza Dev3 con la misma firma/paquete. En el F7, QAIRT 2.28 informa `SoC model (SnapdragonModel) is unknown`, falla al cargar los símbolos remotos (4000) y termina en `backendCreate 0xfa0`. Coincidir con el stub del firmware no basta: la versión de QAIRT no reconoce este modelo de SoC. No genera imagen.
- **SM8735 / POCO F7:** [HexAnima Dev SM8735 diag3.apk](../app/HexAnima%20Dev%20SM8735%20diag3.apk), `1.9.6-v73.4-sm8735-dev3` (código 53), paquete de prueba `com.hexanima.npu.sm8735`, SHA-256 `2200eef27c17dd0196a2614f343899c3eb84d585cc74dbb6f1542250950178ea`. Dev3 recompila `vaerun` con proveedor/build/API y logger QNN, etiqueta mensajes por runner y conserva el runtime QAIRT 2.50. Alineación, firma APK, paquete/versión y hashes de las dos librerías instrumentadas verificados; usa la misma firma y paquete que diag2, así que puede actualizarse sobre ella. No demuestra que `deviceCreate` o generación ya funcionen y no incluye contextos model 85.
- El POCO F6 Pro conserva la Dev instalada `com.hexanima.npu.dev`, versión `1.9.5-v73`.
- Dev SD8E dev19: `1.9.6-v79-sd8e-dev19`, código 57; paquete `com.hexanima.npu.dev`; APK [hexanima-sd8e-dev19.apk](../app/hexanima-sd8e-dev19.apk), SHA-256 `12acb95da8db26e26fbcf506a3ec118e69e5c4e0ee7f4d0b1b2a56972f6d10fc`. Compilada sobre staging Dev18; runner VAE actualizado (`17ce5f7a987a3b56bd1fc185a5159b40b303c5d3c17f7dc8bf9480da3632fef9`). Firma v3 verificada, alineación correcta, paquete/versión y presencia de `libvaerun.so`, `libmossrun.so` y skeleton V79 comprobadas. Solo añade métricas de distribución del latente que entra a `vaerun`, del valor tras conversión fp16 y de cada canal RGB a la salida; no cambia los cálculos de generación ni la conversión/guardado. El ZIP 2253 confirma 512×512, latente VAE finito (`sd=0.7862`), cuantización fp16 casi idéntica (`sd=0.7862`) y RGB bruto con `sd` R/G/B de `0.0797/0.0573/0.0743`. El ZIP 2326 confirma 1024×1024 y 4 pasos finitos (215.7 s de DiT); el mosaico VAE de 512 px también produce RGB finito con `sd` R/G/B `0.0790/0.0600/0.0772`. La salida bruta se mantiene plana a ambas escalas, antes de la conversión a PNG.
- Dev SD8E dev18: `1.9.6-v79-sd8e-dev18`, código 56; paquete `com.hexanima.npu.dev`; APK [hexanima-sd8e-dev18.apk](../app/hexanima-sd8e-dev18.apk), SHA-256 `77222724870f78490d8da971552166d691f945bc5f57aef933d1d104462b7456`. Firma v3 verificada, alineación correcta, sin nombres ZIP inválidos; runner coincide con la compilación aislada (`2970d4c47e8e3cd87b33f209954b345c09d4fbfaae58d43d1a04a4c11ef0a01e`) y skeleton V79 coincide con Dev16. Versiona `tx.cache`; la primera ejecución confirmó recálculo (`te: 3.89s`, `refiner: 0.23s`). El ZIP 2230 corresponde a 512×512: DiT y VAE de cuadro completo terminan, todos los valores medidos son finitos, pero la imagen sigue lavada. Esto descarta el mosaico como causa única; el sampler coincide con `sample_ref.py`. El siguiente ensayo usa Dev19 para comparar latente/RGB y debe aproximarse a la escala de entrenamiento (~1 MP).
- Dev SD8E dev17: `1.9.6-v79-sd8e-dev17`, código 55; APK [hexanima-sd8e-dev17.apk](../app/hexanima-sd8e-dev17.apk), SHA-256 `d3806d13b6d7f32587639dc9e90a80117fdde8a0ea7eec57196aab42bf982121`. Registró activaciones por bloque y latentes por paso. La prueba tardó 85.1 s de DiT y 0.38 s de VAE; no hubo no-finitos. El cache hit trajo los valores extremos exactos de Dev15 (`-1729.344` a `1014.312`), confirmando que no ejercitó la ruta actualizada de texto/refiner.
- Mage genera correctamente en el OnePlus CPH2649/SM8750. Dev16 completó los cuatro pasos con 4,761,600/4,761,600 valores finitos en cada bloque y salida VAE finita, pero el PNG original es una textura lavada. La salida VAE antes de guardar tiene desviación estándar RGB de 0.061–0.086, por lo que la conversión lineal a PNG no parece ser la causa. Dev17 añadió estadísticas para investigar la degradación; Dev18 elimina el cache hit obsoleto que impedía verificar el texto/refiner actualizado. La calidad de imagen sigue sin validarse.
- La candidata SM8650/V75 anterior incluía rutas V73 para Anima, MOSS, Qwen, Mage, detectores, pose y upscalers; alias QNN V75; y 3 workers Anima (14/14/12). ADB no logró iniciar su servidor y quedó sin prueba física.

## SD8E Dev6: corrección de entrega

Se comprobó que los runners incluidos en dev5 no contenían la cadena `dit-input`: la compilación anterior no había actualizado esos binarios, aunque la versión del APK sí cambió. Se recompilaron MOSS con sus metadatos RPC y Mage con el árbol compartido Flux3, con comprobación directa de las trazas en ambos ejecutables. Dev6 (código 43) añade rechazo de caché de texto con valores no finitos. Los registros 0358 siguen mostrando fallo del primer bloque pero no permiten afirmar que la entrada fuese finita. Pendiente resultado físico de Dev6; no declarar corregida la generación.








## SD8E: revisión ZIP 2326 y Dev20 focalizada

- El ZIP 2326 (Dev19, OnePlus CPH2649/SM8750, 1024×1024) completa cuatro pasos con todos los valores finitos. La muestra de salida del DiT pasa de `sd≈37.5` en el bloque 39 a `sd≈351` en el 40; el bloque 41 permanece cerca de `sd≈351`, y el salto se repite en los cuatro pasos. En las pruebas 512×512 anteriores ya aparecía en el mismo punto (bloque 40 `sd≈74–107` según la corrida), así que no lo causa únicamente la resolución.
- La salida final del sampler permanece finita (`x0 sd≈0.42–0.46` en 512; latente VAE completo `sd≈0.786`), pero RGB bruto del VAE sigue bajo contraste. Los logs 2218 muestran que la salida del refiner de texto llega a `[-1729, 1014]`; todavía falta contrastar cada operación con una referencia para atribuirle causalidad. No se aplicó un cambio matemático especulativo.
- Dev20 agrega métricas min/max/media/desviación y valores finitos en una muestra fija de 4096 elementos para las etapas internas de los bloques 39, 40 y 41 (RMS atención, salida atención, residual atención, RMS FFN, proyección descendente y salida de bloque). No toca la matemática ni los demás bloques y pasos; queda listo para que el usuario del SM8750 comparta un log.
- APK: [hexanima-sd8e-dev20.apk](../app/hexanima-sd8e-dev20.apk), `1.9.6-v79-sd8e-dev20`, código 58, paquete `com.hexanima.npu.dev`, SHA-256 `d80e85170ef9f3a766d44aa5a6ddeab4ae5ee2146bf62acc13e5c79750b8b2f5`.
- Compilación aislada de runner y esqueleto V79 terminada. Verificados alineación ZIP, firma v3 y mismo certificado que Dev19, metadatos de paquete/versión y coincidencia de hashes de `libmossrun.so`, `libmossskel_v79.so` y `assets/moss.sh` dentro del APK. No hay teléfono SM8750 conectado aquí, así que la traza de ejecución Dev20 queda pendiente de la persona que lo tiene.
