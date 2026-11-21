# Prompt para continuar la importación de Kromoss en casa

Copia el texto bajo la línea y pégalo en Codex con la carpeta `Hexanima` abierta.

---

Continúa este trabajo en español. Mantén la interfaz en español e inglés y el estilo actual de HexAnima. El objetivo es investigar e implementar soporte real para importar **Kromoss (MOSS-Image)** y su LoRA DMD desde archivos locales, comenzando por la ruta MOSS V73 existente. No declares compatibilidad hasta convertir pesos y obtener imágenes válidas en un teléfono.

## Antes de tocar código

1. Inspecciona `git status`, `git log`, `git remote -v`, `docs/PROGRESS.md`, `moss/NOTES.md`, `moss/README.md`, `moss/export_moss.py`, `moss/mossrun/lorafold.cpp`, `app/src/com/hexanima/npu/Moss.java`, `MossLora.java`, `Models.java`, `ModelsActivity.java`, `MainTools.java`, `Main.java` y `SafetensorsFamily.java`.
2. Conserva cualquier cambio local que no pertenezca a este trabajo. No uses `reset`, `checkout`, `clean` ni reemplaces artefactos. En la sesión de escritorio quedó preparado un commit aislado con este prompt y el diagnóstico/importador previo; confirma qué llegó con `pull` antes de continuar.
3. Comprueba espacio libre y versiones del entorno antes de descargar pesos o dependencias. No guardes credenciales en el repositorio. No publiques un release. Pide aprobación antes de hacer otro commit o push.

## Datos del modelo y límites conocidos

- Fuente oficial: `lodestones/Kromoss`, ficha `https://huggingface.co/lodestones/Kromoss`.
- Es un fine-tune completo de MOSS-Image a 1024 px, con la misma arquitectura general, codificador Qwen3.5-9B y VAE FLUX.2 small que MOSS. Confirma tensor por tensor qué componentes se pueden compartir con el pack de HexAnima; no supongas que `glue`, refiner o pesos no bloque son idénticos.
- Checkpoint int8 ConvRot OPD-priming: aproximadamente **5,41 GB**. Checkpoint BF16: aproximadamente **10,2 GB**. Descarga solo el formato seleccionado, no el repositorio completo ni ambas variantes.
- La cuantización ConvRot W8A8 usa escalas por fila y una rotación Hadamard regular de 256 elementos en la dimensión de entrada; ComfyUI la aplica online. El pack actual de HexAnima usa tiles Q8 de 32×32 y el kernel HMX actual espera pesos sin esa rotación. El exporter `moss/export_moss.py` parte de GGUF Q5 + rapid-moss LoRA, no de ConvRot. No basta con renombrar archivos, aceptar otra clave o quitar la validación.
- LoRAs oficiales DMD/DMD2: aproximadamente **1,19 GB** cada una, rank 128, 42 bloques y proyecciones `q`, `k`, `v`, `o`, `ffn_up_gate` y `ffn_down`. La ficha indica 8 pasos, CFG 1, y que 4 pasos también funciona; comprueba qué parámetros y sampler puede representar el runner actual.
- El límite local de LoRA MOSS quedó elevado a 2 GB con una comprobación preliminar de espacio. Valida el archivo DMD real con `MossLora.check`/`liblorafold.so`; no presupongas compatibilidad por el tamaño ni por la familia.
- El DMD LoRA se transfirió a Kromoss según la ficha, pero eso no demuestra que el pack base MOSS de HexAnima produzca los resultados de Kromoss.
- El pack MOSS instalado ronda 14 GB e incluye componentes compartidos. Estima el tamaño del nuevo pack y el espacio temporal leyendo el manifest actual; durante conversión local puede coexistir el checkpoint de 5,41 GB, temporales y los pesos convertidos. Si el pico de almacenamiento o memoria no es seguro en el teléfono, convierte en PC y deja que el teléfono importe un pack ya validado.

## Trabajo solicitado

1. **Auditoría de formatos:** inspecciona las cabeceras y claves del checkpoint oficial y de ambos DMD LoRA. Documenta dtypes, escalas, shapes, número de bloques, pesos extra y archivos auxiliares. Determina si la ruta int8 se puede transformar sin pérdida inaceptable a los tiles existentes o si hace falta una ruta/kernel ConvRot nuevo. Compara contra una referencia oficial o Diffusers en CPU/PC.
2. **Diseño primero:** escribe un plan corto con el formato de entrada elegido, el formato de pack de salida, componentes compartibles, espacio/RAM máximos estimados, validaciones y pruebas. Prioriza int8 ConvRot; deja BF16 como referencia de comparación si hace falta. Mantén una ruta de cancelación/limpieza segura.
3. **Importación local del modelo:** el selector actual convierte checkpoints Anima; no reutilices `AnimaImport` para MOSS. Añade un importador MOSS/Kromoss que clasifique por cabecera, compruebe licencia, dtypes, arquitectura, espacio disponible y componentes requeridos antes de copiar o convertir. Guarda la variante con manifiesto, hashes, nombre/versión y estado completo, permitiendo cancelar y recuperar tras fallo. Generaliza con cuidado la infraestructura de variantes MOSS (`rs`/`hm`) o crea un tipo explícito; no hardcodees Kromoss como RealSkin/Harem.
4. **Ejecución:** conserva los pesos y componentes específicos de Kromoss, y comparte con el MOSS instalado solo lo que los hashes/configuración confirmen idéntico. Ajusta selección de modelo, paths del runner, instalación/eliminación y metadatos. Evita duplicar encoder/VAE si son realmente idénticos.
5. **LoRA DMD/DMD2:** confirma que el parser acepta las claves kohya `diffusion_model.layers.N.proj.*.lora_down/up.weight`, rank 128 y 42 bloques. Implementa los cambios que falten para importarla localmente, conservarla en biblioteca, activarla y combinarla correctamente con el adaptador base del runner. Añade controles para los modos DMD de 8 pasos/CFG 1 y define explícitamente qué pasa si se mezclan con el modo rapid-moss de 4 pasos.
6. **UX y seguridad:** conserva el diseño HexAnima; todos los mensajes nuevos en español e inglés. Muestra progreso real de copia/conversión, errores concretos, tamaño estimado y espacio requerido. Nunca borres el archivo original ni una instalación válida hasta confirmar el pack nuevo y sus hashes.
7. **Pruebas:** valida el lector de cabecera y el manifiesto con entradas válidas, truncadas, de familia equivocada y canceladas. Compara bloques exportados con la referencia numérica y comprueba valores finitos. Compila la Dev, instala en un teléfono V73 compatible, genera con Kromoss y prueba el DMD LoRA por separado. Guarda logs y parámetros. Registra por separado compilación, validación numérica y generación real; un runner que termina sin imagen correcta cuenta como fallo.

## Criterios para considerar terminado

- La importación distingue Kromoss de Anima/Qwen/Mage y no intenta copiar un checkpoint incompatible a la carpeta Anima.
- El manifest verifica todos los archivos y permite reanudar/reparar una instalación incompleta.
- Una generación de Kromoss produce una imagen no vacía y finita en teléfono V73, usando parámetros documentados.
- El DMD LoRA se importa y pasa la validación estructural; su generación se prueba en el modo compatible. El modelo base Kromoss y el DMD LoRA se reportan como resultados separados.
- No se anuncia soporte para V79, SD8E u otros chips desde una prueba V73.
- `docs/PROGRESS.md` refleja el avance, los tamaños observados, el hardware probado y lo que siga pendiente.

Al terminar cada bloque, informa en español qué cambió, qué se compiló, qué se probó en teléfono, qué quedó sin validar y cuál es el siguiente paso. No hagas release; espera autorización antes de commit/push.
