# MOSS-Image on the Hexagon NPU: sources

Everything needed to rebuild the MOSS-Image pack and the native parts. The weights are not here (they are Apache-2.0 models by OpenMOSS, Qwen, Black Forest Labs, Unsloth and darask0, see `../THIRD_PARTY.md`); the converted pack is downloaded by the app.

- `NOTES.md`: working notes (design, measurements, pitfalls).
- `moss_ref.py`, `qwen35_ref.py`, `sample_ref.py`, `pipeline_moss_image.py`: PyTorch references (the last one is OpenMOSS' own pipeline file) used to check every kernel.
- `export_*.py`, `make_lora_overlay.py`: GGUF / safetensors -> NPU tile packs (8-bit 32x32 tiles, the 4-step adapter kept as unmerged LoRA tiles); `upload_hf.py` pushes the pack (`../tools/make_moss_pack.py` writes its manifest).
- `hexagon/mega/`: the DSP side (HMX GEMMs, RMSNorm/modulation, SiTU, joint attention, Qwen3.5 delta-net layers) and its test harness `blk_main.c`. It is the generalisation of `../hexagon/mega` (Anima); build it the same way (needs the Hexagon SDK and QHL HMX sources, which are Qualcomm files and not included).
- `mossrun/`: `mossrun` (one process: tokens -> text encoder -> refiner -> 4 DiT steps -> latent, weights streamed from flash), `vaetile` (tiled FLUX.2 decoder for sizes without a ready context), `lorafold` (user LoRA check and on-device fold into the adapter slots) and `build.sh`.
- `*_test.py`, `cmp_blk.py`, `npu_*.py`, `lora_test.py`: laptop-side drivers that run the kernels on a phone over adb and compare with the references.

The app (`../app`) bundles `mossrun`, `vaetile`, `lorafold` and the DSP skel through `../tools/stage_jnilibs.sh` (set `MOSS_DIR` to this directory).
