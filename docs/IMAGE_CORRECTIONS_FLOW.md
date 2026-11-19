# Correcciones posteriores a la generación

## Objetivo

Añadir a HexAnima una herramienta local y no destructiva para corregir una imagen ya generada. Debe permitir elegir un tipo de corrección, producir varias alternativas, compararlas y aceptar solo la versión preferida. La imagen original nunca se reemplaza ni se pierde.

Este flujo debe poder usarse con cualquier imagen final que HexAnima pueda abrir —Anima, MOSS, Qwen y, cuando esté validado, Mage— aunque cada corrección solo aparezca cuando exista un procesador compatible.

## Experiencia solicitada

1. Al ver una imagen generada, el usuario pulsa **Correcciones**.
2. Aparece un panel con categorías: **Cara**, **Manos**, **Detalles**, **Ruido**, **Resolución** y **Otros**.
3. Al escoger una categoría, aparece su panel de trabajo con controles adecuados y un botón **Generar**.
4. Cada toque en **Generar** crea una nueva alternativa. La imagen base aceptada permanece sin cambios.
5. El usuario puede comparar la alternativa actual con la base o con otras alternativas, por ejemplo con vista antes/después o una tira de miniaturas.
6. **Usar esta** acepta la alternativa para esta categoría. **Descartar** mantiene intacta la base. Se puede generar otra alternativa y compararla con la anterior.
7. Al volver a las categorías, las alternativas se conservan durante la sesión. La categoría siguiente opera sobre la última versión aceptada.
8. **Deshacer** revierte la última corrección aceptada. La imagen inicial siempre se puede recuperar.

Las nuevas alternativas de un mismo paso deben partir de la misma versión base aceptada, no de la alternativa descartada anterior. Esto evita degradación acumulada cuando el usuario pulsa Generar varias veces.

## Herramientas y controles por categoría

| Categoría | Ruta propuesta | Controles iniciales | Límites conocidos |
|---|---|---|---|
| Cara | Detectar/cortar el rostro, aplicar restaurador facial al recorte y mezclarlo de vuelta con borde suave | Intensidad/restauración; selector de rostro si hay varios | Los restauradores pueden inventar rasgos o cambiar identidad. Mostrar comparación antes/después. |
| Manos | Detectar o marcar la mano; recorte local; edición/inpainting local con Qwen y máscara | Región objetivo, fuerza de edición y prompt opcional | No garantiza anatomía exacta ni conservar perfectamente tatuajes, joyas o uñas. Requiere prueba en V73. |
| Detalles | Refinar recorte local; usar máscara de Qwen para cambios semánticos concretos | Pincel/máscara, prompt y fuerza | Evitar modificar el resto de la imagen al recomponer el recorte. |
| Ruido | Restaurador/denoiser no generativo cuando sea posible | Fuerza de reducción de ruido | Un denoise excesivo borra textura fina. |
| Resolución | Superresolución local, inicialmente ×2 y luego evaluar ×4/tileado | Escala; intensidad de detalle si el modelo la admite | Puede sintetizar textura; no corrige dedos ni rasgos anatómicos. |
| Otros | Abrir el editor Qwen con máscara y prompt, reutilizando la imagen elegida | Máscara, prompt, fuerza | Consume más tiempo y energía que los filtros de restauración. |

La interfaz puede compartir el patrón del panel, pero debe cambiar las opciones según la tarea. No usar una única barra de “intensidad” para todos los algoritmos si su significado técnico es distinto.

## Interfaz y gestión de versiones

- Comparar la imagen actual con la base con un deslizador antes/después; permitir también cambiar entre miniaturas de alternativas.
- Etiquetar cada resultado como **Original**, **Base aceptada**, **Alternativa 1**, etc.
- Aceptar explícitamente una alternativa antes de pasar a otra categoría.
- Mantener deshacer/rehacer y poder regresar a la imagen original.
- Guardar como una nueva imagen/version en el historial; no sobrescribir el archivo original.
- Mantener el prompt, semilla, modelo y metadatos originales; añadir el historial de correcciones como metadatos adicionales.
- Para edición local, guardar máscara/recorte y recomponer el resultado dentro de la región objetivo con borde suave.
- Trabajar una corrección a la vez, mostrar progreso, tiempo, cancelación y aviso térmico. El uso del NPU debe respetar QAIRT 2.28 y el skeleton V73.

## Viabilidad técnica

- **Restauración facial:** GFPGAN se diseñó para restauración de caras y el repositorio publica su código bajo Apache 2.0. La salida puede modificar identidad y los archivos de pesos/dependencias deben revisarse por separado antes de distribuirlos. Referencia: [TencentARC/GFPGAN](https://github.com/TencentARC/GFPGAN).
- **Superresolución:** Real-ESRGAN se orienta a restauración y ampliación general, y su código usa BSD-3-Clause. Su trabajo es aumentar nitidez/resolución, no reconstruir anatomía. Revisar las condiciones de los pesos concretos. Referencia: [xinntao/Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN).
- **NPU Android:** Qualcomm mantiene una app de muestra de superresolución que utiliza QNN, pero es una referencia y no un módulo listo para copiar. Las dependencias/runtime deben coincidir con QAIRT 2.28; no mezclar binarios de versiones distintas. Referencias: [AI Hub Apps, super resolution](https://github.com/qualcomm/ai-hub-apps/tree/release/super_resolution_android) y [QAI AppBuilder Android samples](https://github.com/qualcomm/qai-appbuilder/tree/main/samples/apps/android).
- **Manos y otros cambios semánticos:** el camino más práctico con lo que ya existe es el editor local enmascarado de Qwen. El recorte mejora el control sobre la zona, pero no asegura conservar todos sus detalles. El detector de manos puede proponer la región; debe permitirse corregir la máscara manualmente.
- **Fuera de línea:** la edición y todos los procesadores deben ejecutarse localmente; no usar APIs ni servicios en la nube.

## Plan sugerido

1. Auditar cómo se abren, versionan y guardan imágenes en historial/galería; diseñar la vista de correcciones sin modificar la generación actual.
2. Implementar comparación, alternativas, aceptar/descartar, original permanente y deshacer/rehacer.
3. Probar un primer restaurador facial y una superresolución ×2 en crops/imágenes de prueba. Medir calidad, memoria, latencia y temperatura en el Poco F6 Pro.
4. Integrar la categoría Manos con detección/crop/máscara y la ruta Qwen; probar dedos, tatuajes y preservación del fondo con imágenes reales de referencia.
5. Añadir Ruido, Detalles y Otros después de validar sus modelos/rutas, licencias y requisitos de runtime.
6. Traducir todos los textos a español e inglés, documentar modelos/licencias y tomar capturas en el teléfono.

No presentar una categoría como disponible mientras no haya terminado una prueba real en V73. Mantener las imágenes originales y los datos existentes del usuario.

## Criterios de aceptación

- La corrección crea una versión nueva y la imagen original sigue intacta.
- Se pueden generar y comparar varias alternativas desde una misma base aceptada.
- Aceptar, descartar, cambiar de categoría, deshacer y rehacer mantienen el historial correcto.
- Cara, manos, detalles, ruido y resolución solo aparecen habilitados con su procesador real disponible.
- Las máscaras/recortes limitan los cambios al área elegida; se comprueba el fondo y las zonas fuera de la máscara.
- Las pruebas se hacen offline en HexAnima Dev sobre el Poco F6 Pro V73 y registran calidad, latencia, RAM, temperatura y cancelación.
- No se publica un release hasta terminar las pruebas y el paquete global del proyecto.
