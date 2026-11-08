# Qwen-Image 2.1 Turbo on the Hexagon NPU: sources

Everything needed to rebuild the Qwen-Image pack and the native parts (text-to-image and photo editing). Weights are not here; the models are Qwen team / LightX2V / Unsloth works (Qwen Research License for the transformer, adapter and VAE: **non-commercial**, Apache-2.0 for the Qwen3-VL text encoder), see `../THIRD_PARTY.md`. The converted pack is downloaded by the app.

- `NOTES.md`: working notes (findings, kernel port design, numerics, speed passes, pitfalls).
- `qref.py`, `qwen_ref.py`, `qwen_te_ref.py`, `qwen_e2e_ref.py`, `qwen_edit_ref.py`, `vit_ref.py`: PyTorch/numpy references used to check every kernel (GGUF loader, DiT block in kernel form, text encoder, ViT, whole pipeline, editing).
- `export_qwen2.py` (DiT pack v2, current), `export_qwen.py` (v1), `export_qte.py` (text encoder), `export_vit.py`, `export_qglue.py`, `export_vae_qwen.py` / `export_vae_enc.py` (+ `vae_q/build_vae.sh`, QAIRT) , `packio.py`, `fetch_visual.py`; `../tools/make_qwen_pack.py` writes the manifest, `pack/` holds the README / NOTICE / LICENSE that go to the pack repository.
- `hexagon/mega/`: DSP side (generalisation of `../hexagon/mega`): mode 40 DiT block with a block-causal mask, mode 22 text-encoder layer (HMX attention), mode 23 ViT layer. Needs the Hexagon SDK and QHL HMX sources (Qualcomm files, not included).
- `qwenrun/`: `qwenrun` (`cat head.inc body.inc > qwenrun.cpp`: tokens -> ViT -> text encoder -> 4 DiT steps -> latent, weights streamed from flash, prefix and text caches), `vaetile` (tiled decoder), `vaeenc` (tiled encoder for the photo), `build.sh`.
- `mk*.py`, `cmp_q.py`, `test_pack.py`, `check_vae_*.py`: laptop-side drivers that run the kernels on a phone over adb and compare with the references.

The app bundles `qwenrun`, `vaetile`, `vaeenc` and the DSP skel through `../tools/stage_jnilibs.sh` (set `QWEN_DIR` to this directory).
