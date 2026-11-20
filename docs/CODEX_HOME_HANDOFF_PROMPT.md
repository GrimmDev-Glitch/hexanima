# Prompt para continuar HexAnima en casa

Copia el texto de abajo en Codex con la carpeta `Hexanima` abierta.

---

Continúa el trabajo de este repositorio en español. Primero inspecciona `git status`, `git log`, `docs/PROGRESS.md`, `krea/README.md` y los scripts de build afectados. No restaures ni sobrescribas cambios del usuario. Mantén la app en español e inglés. No hagas release ni commit/push hasta que te lo pida.

## Objetivos de esta sesión

Revisa y continúa **Krea 2 Turbo para Snapdragon 8 Gen 2 (SM8550/V73)** y el **port experimental para Snapdragon 8 Elite (SM8750/V79)**. Son trabajos de investigación y prueba: separa con claridad lo que compila de lo que ya generó una imagen correcta en un teléfono real.

### Krea 2 Turbo / SD8G2

- El commit anterior `feat(krea): add experimental V73 block smoke path` añadió un smoke test sintético. En el POCO F6 Pro ejecutó 32 tokens con 3 workers sin error DSP; con pesos cero, la diferencia máxima fue 3.73e-9. Esto valida despacho/buffers, **no** los pesos reales ni la calidad de imagen.
- Se aceptó el acceso al modelo en Hugging Face en la sesión anterior, pero no se descargaron los pesos reales. Comprueba primero el estado del login y los archivos existentes; no imprimas ni guardes tokens.
- Investiga la arquitectura, configuración y licencia de Krea 2 Turbo desde sus fuentes oficiales. Determina los artefactos que faltan para convertir pesos, encoder/fusión, RoPE/atención, sampler, VAE y filtros requeridos por licencia. Mide el espacio antes de descargar y reutiliza el POCO SD8G2 para las pruebas que requieran hardware.
- Avanza en pequeños pasos verificables hacia una imagen real en V73; no anuncies compatibilidad de Krea dentro de la app hasta completar la ruta y validar una imagen. Si falta un componente o consentimiento/licencia, deja el bloqueo y la siguiente acción documentados.

### Snapdragon 8 Elite / SD8E / V79

- Se preparó una Dev `1.9.6-v79-sd8e-dev` (versionCode 37): incluye skeletons V79 experimentales para MOSS y Mage, VAE MOSS compilado con QAIRT 2.28 y Real-ESRGAN V79. La app bloquea generación Anima/Qwen porque faltan sus contextos V79.
- Los skeletons MOSS/Mage compilaron como ELF Qualcomm Hexagon y no tienen símbolos `hmx79` de diagnóstico sin resolver. Esto es solo verificación estática: **no** se han probado en un SD8E ni se ha confirmado una imagen correcta.
- La APK y los contextos `.bin` se excluyen de Git. Tras el pull, revisa si existen los artefactos locales necesarios. Si falta el VAE MOSS V79, reconstruye desde el ONNX si está disponible con QAIRT 2.28 / `SOC_MODEL=69`; si faltan fuentes/pesos, consíguelos de la fuente autorizada antes de continuar. No marques esos binarios como validados por el mero hecho de compilar.
- Con el OnePlus 13/SM8750 del compañero, prueba por separado: carga y generación MOSS, carga y generación Mage, ESRGAN upscale, cancelación, y una segunda generación tras cancelar/reiniciar. En cada fallo, exporta logs inmediatamente antes de reintentar o cambiar de modelo y guarda captura, modelo de teléfono, modelo de IA, resolución y acción exacta.
- Investiga estabilidad del HMX V79/FastRPC, límites de sesiones y recuperación después de cancelación. No copies código de `adiirfan/anima-android` sin revisar su licencia. Mantén Anima/Qwen bloqueados hasta tener contextos correctos y pruebas.

## Límites y entrega

- Conserva la meta de una APK universal a futuro, pero no declares un chip compatible hasta validar runner/kernel y contextos por modelo. Por ahora el foco de hardware de Krea es SD8G2 y el de este port es SD8E.
- La Dev SD8E local fue verificada con SHA-256 `d06d04e4f5277238919650513ee1e3849edc2c9d59f8198f3db21777007e5c0c`, pero no se incluye en Git; comprueba si sigue disponible antes de asumir que puedes instalarla.
- No instales una APK V79 en el POCO F6 Pro (SM8550). No hagas pruebas destructivas con los modelos locales. No cambies licencias, filtros, dependencias o scripts globales sin documentar el motivo.
- Actualiza `docs/PROGRESS.md` después de cada hito con porcentaje de implementación y porcentaje de prueba separados, resultados y bloqueos. Al terminar, entrega un resumen corto de cambios, pruebas reales, lo que falta y el siguiente paso.
