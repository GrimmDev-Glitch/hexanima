**Title:** es: "No está en memoria" / "Liberar memoria" instead of "Descargado" for an unloaded model

Hi ayanoby! A small Spanish wording fix (8 lines of `app/assets/lang/es.tsv`, nothing else).

In Spanish, *descargar* means both "to download" and "to unload", so a Spanish user reads "Descargado" on a model that is only unloaded from RAM as "downloaded" (or worse, thinks the files are gone). The Models screen uses the same verb for real downloads, which makes it more confusing.

This change keeps *descargar* only for downloading files and uses:

| English | Before | After |
|---|---|---|
| Unloaded | Descargado | No está en memoria |
| Unloaded · loads itself when you generate (~5 s) | Descargado · se carga solo… | No está en memoria · se carga solo… |
| Model is unloaded: … | El modelo está descargado: … | El modelo no está en memoria: … |
| Unload | Descargar de memoria | Liberar memoria |
| Unloading the model… | Descargando el modelo de la memoria… | Liberando el modelo de la memoria… |
| Unload when idle | Descargar de memoria al estar inactivo | Liberar memoria al estar inactivo |
| Loading and unloading | Cargar y descargar de memoria | Cargar y liberar memoria |
| (the help text) pulsa «Descargar de memoria» | | pulsa «Liberar memoria» |

We have used this wording in the Snapdragon 8 Gen 2 fork (https://github.com/GrimmDev-Glitch/hexanima) since 1.6.0-v73.
