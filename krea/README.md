# Krea 2 Turbo for Snapdragon 8 Gen 2 / V73

This is an experimental V73 port workspace. The shipping app does not load these packs yet.

## Current status (21 November 2026)

Krea remains an experimental V73 port and is not registered in the app. The official 26.3 GB `turbo.safetensors` checkpoint was downloaded after Hugging Face device authorization to the ignored `work/krea-real/` directory. Its 430 tensors and expected shapes were inspected. Blocks 0 and 27 were packed separately (325.8 MB each) and run on the POCO F6 Pro (Snapdragon 8 Gen 2 / V73).

Compared with the FP32 CPU reference, real-weight DSP outputs were finite. Block 0 at 32 tokens had relative L2 **2.32%** at the attention cut and **3.69%** after the full block; another seed gave **7.46%** full-block error. With the corrected slab-major input layout, block 0 at 64 tokens gave **3.78%** attention / **9.05%** full-block error for one seed and **2.65%** / **5.85%** for another. At 128 tokens, it gave **4.21%** attention / **9.16%** full-block error (cosine **0.996260**). Block 27 at 64 tokens gave **12.71%** attention (cosine **0.991963**) / **11.85%** full-block error (cosine **0.993017**). These FP32 differences include q4a weight quantization.

To separate quantization from kernel math, the CPU harness now also reproduces q4a weight quantization and fp16 dequantization. On block 27 at 64 tokens, DSP vs this q4a reference was **0.156% relative L2** for attention and **0.138%** for the full block; cosine was within float-rounding distance of 1. This indicates the observed FP32 difference is predominantly quantization for this case, rather than an incorrect DSP result. It does not establish that q4a preserves end-to-end image quality. The slab-major layout above 32 tokens was corrected and 32/64/128-token execution now completes without DSP errors.

The real weights, converted blocks, and test artifacts stay under ignored `work/krea-real/` and `work/krea-real-l27/`; do not commit them. A synthetic mode-71 smoke also passed earlier (32 tokens, 3 workers, max delta `3.7252903e-9`, RMSE `1.48289092e-9`). A run with `com.hexanima.npu` active failed to reserve 8 MiB VTCM (`rc=-1`); resource contention is plausible, not confirmed. Device test files were removed after these runs.

### Q8 and full DiT block-chain smoke (21 November 2026)

The packer now also emits experimental symmetric int8 `K2B2` blocks (488.6 MB each; about 13.68 GB for all 28). The Q8 tile ordering was corrected and checked on the phone: for block 27 at 64 tokens, DSP vs the CPU Q8 reference is **0.131% relative L2** (cosine 1.000020); vs FP32 it is **0.900%** (cosine 0.999979). This is one synthetic activation sample and does not validate model quality.

All 28 real Q8 blocks were then run sequentially on the POCO F6 Pro at 32 tokens, passing each block's output to the next. Every DSP call returned success. The final output was finite for all **196,608** values; min/max were **−6799.43 / 4319.44**, RMS **254.545**. Inputs, RoPE and timestep projection were synthetic/neutral, so these unusually large values are a pipeline smoke only, not a representative Krea activation or image. Each call took roughly 0.08 s of DSP stage time at this token count, excluding host I/O and RPC setup.

The all-block Q8 pack is in ignored `work/krea-all-q8/`, and the public Mage Qwen3-VL-4B text-encoder pack/tokenizer assets (4.48 GB) are in ignored `work/mage-upstream/`. Neither pack is part of the APK or Git. At the time of this initial synthetic smoke, text-state extraction, Krea text fusion/projector, timestep/patch embeddings, real masking, sampler and Qwen Image VAE connection were still missing. The later vertical slice below used real prompt conditioning and generated a local image, while remaining outside the shipping app.

### End-to-end image checkpoint (21 November 2026)

The phone's Mage Qwen3-VL encoder extracted the selected hidden states for the prompt, and the Krea text-fusion/projector reference produced a 512×6144 context with 22 active tokens (17 prompt tokens plus the five suffix tokens). A real-conditioned 28-block Q8 denoising pass ran for all eight Euler steps on the POCO F6 Pro; the resulting latent was finite. The initial official Qwen Image VAE decode ran on the Windows host and produced `work/krea-first-image.png` (256×256), a red fox in a snowy forest at dawn. The follow-up below moves that decode to the POCO and compares it with the host reference.

The first 768-token run exposed a large mismatch in the DSP attention when the full 512 padded text positions were retained. Compacting the 22 active text tokens to 32 rows, padding only to the DSP slab boundary and preserving their order/positions fixed the first-block comparison: Q8 DSP vs CPU Q8 reference at 288 total tokens was **1.01% relative L2** (cosine **0.999971**) on real prompt/image activations. Keep this compact form for the current phone runner; the long padded mask case still needs a kernel-level fix.

This is a research vertical slice, not an app integration: Qwen encoding, text fusion, Q8 DiT denoising, and VAE decoding have now all run on the phone. The sampler run used a single conditional pass and caller-supplied initial noise (the seed-42 noise file was prepared on Windows). CFG parity, application integration, long-mask behavior, and license/content-filter requirements remain open. The checkpoint, Q8 packs and output remain under ignored `work/` paths and must not be published.

### Complete phone generation (21 November 2026)

The native POCO path consumed the Qwen hidden states already extracted on the phone, fused them with `krea_text_fuse_cpu`, ran all 28 Q8 DiT blocks for 8 Euler steps on the DSP, and decoded the final latent with the Qwen Image VAE on the phone CPU. It generated a 256×256 red fox in a snowy forest. Two complete runs with identical inputs produced bit-identical latent and RGB float outputs (RMSE 0). The VAE CPU result was previously checked against the Diffusers reference (RMSE 0.00225 in [-1,1], max error 0.00394, correlation 0.99999). The native output is `work/krea-text-fusion/krea-phone-stable-a.png`; the image, weights and binaries are local test artifacts and are not committed.

This confirms a reproducible device-side research path, not a usable HexAnima feature. The app still has no Krea model entry or UI, installer, production error handling, CFG path, or license-required content safeguards. Do not compile a Krea-test release or distribute the model until those app and license requirements are implemented.

### Phone-side VAE validation (21 November 2026)

The official Qwen Image VAE checkpoint was exported to a temporary FP32 pack and decoded by `krea/krea_vae_cpu.cpp` on the POCO F6 Pro (SM8550/V73). This closes the phone-side decoder milestone for the same 256×256 latent used by the image checkpoint above. The runner took **51.84 seconds** and wrote a 256×256 RGB float image. Compared with the official Diffusers decoder on Windows CPU, output RMSE was **0.00225** in `[-1,1]`, max absolute error **0.00394**, and pixel correlation **0.99999**. The visible result remains the red fox in a snowy forest.

The first phone decode exposed an upsampler weight-order bug: PyTorch Conv2d stores OIHW while the runner's im2col expects HWC input order. Repacking those weights in the runner fixed it. Intermediate outputs from the input convolution through all four up blocks and the final convolution match the CPU reference (worst observed block RMSE below `7.3e-6`; final convolution RMSE `1.5e-6`). The comparison image is `work/krea-vae-cpu/phone-image.png`; weights, binary, latents and debug dumps stay in ignored `work/` on the PC and `/data/local/tmp/krea-vae/` on the phone. Do not publish them.

The VAE validation above was followed by native on-phone text fusion and full sampling. The active text context is compacted to 32 rows to avoid the known DSP long-mask mismatch; app integration, production sampler parity and license/content-filter requirements remain open.

### Audit on the home PC (21 November)

- The official Krea repository confirms the Turbo inference recipe: 8 Euler steps, CFG disabled, fixed `mu=1.15`; the public example supports 1024–2048 px. Architecture: 28 single-stream blocks, width 6144, 48 Q heads / 12 KV heads, SwiGLU 16384, 2×2 latent patches, and a Qwen3-VL-4B encoder selecting 12 hidden layers. The q4a prototype was checked on blocks 0 and 27; the later Q8 phone path ran all 28 blocks and generated a repeatable 256×256 image. This is not a high-resolution quality/parity validation.
- The official pipeline uses a Qwen-Image VAE. The repository already has Qwen-Image VAE conversion/runtime code, so it is a strong reuse candidate; compare the exact config, normalization and numerical output before connecting it. The Qwen3-VL text path also has local implementations, but Krea's 2560-wide text features and its two layerwise plus two refiner fusion blocks need a separate shape/flow audit.
- Current K2B1 q4a dimensions imply about **9.12 GB** for all 28 DiT blocks (calculated from the current 6-bit-per-weight tile records, excluding small headers). With the reported 26.3 GB source checkpoint, conversion needs at least ~35.5 GB while both are present; including the 8.88 GB text encoder and 508 MB VAE raises the staged total to ~44.9 GB before temporary files. D: currently has about 290 GiB free.
- The streaming packer was run against the authorized checkpoint for blocks 0 and 27. DSP output tracks a CPU q4a-dequantized reference closely on block 27, while FP32 comparison shows layer-dependent quantization error of roughly 3–12% in these samples. Test a higher-precision pack (q8 candidate) and more representative hidden states before choosing the shipping format.
- HF device authorization succeeded and the official checkpoint is present under ignored work files. At the time of the initial home-PC audit, the app still lacked text fusion, the complete block runner and sampler integration; the experimental vertical slice below has since executed all 28 Q8 blocks and produced a local image, but remains outside the app.
- License check: Krea's official Community License requires suitable content-filter safeguards for a deployment, and its distribution section requires providing the agreement and binding recipients when distributing the model or a derivative. This must be resolved before adding a downloadable/in-app model flow. The license is not just an attribution notice.

## First vertical slice

`pack_krea.py` converts the 28 Krea 2 Turbo DiT blocks from the official `turbo.safetensors` checkpoint into a versioned `K2B1` pack:

- 5 matrices per block: fused Q/K/V, attention gate, attention output, interleaved MLP gate/up, and MLP down.
- Asymmetric 4-bit q4a tiles, with one scale/minimum pair per output channel and 32 input values.
- RMSNorm/QKNorm gains and each block's six-way timestep modulation are stored after the matrix data. The Q gain includes the SDPA `1/sqrt(128)` factor because the shared attention kernel takes unscaled QK products.
- A JSON manifest records matrix geometry and offsets.

The pack layout is a port-development artifact. These earlier q4a comparisons covered blocks 0 and 27 only; the later Q8 phone run covers all 28 blocks with model-derived activations at 256×256 latent resolution. Higher resolutions, long padded attention masks and the complete phone-side text-fusion/VAE path remain unvalidated.

## Known architecture

- 28 single-stream DiT blocks, width 6144, 48 query heads, 12 KV heads, head width 128, SwiGLU width 16384. The block applies gated residuals around attention and MLP, with modulation order `[pre-scale, pre-shift, pre-gate, post-scale, post-shift, post-gate]`.
- The attention branch projects an elementwise `sigmoid(gate)` of width 6144 and multiplies it into the attended features before the output projection. This differs from Klein's head-wise `2*sigmoid` gate, so its mode-70 helper cannot be reused as-is.
- Q/K use per-head RMSNorm with epsilon 1e-5, then split-pair RoPE over three positional axes of `[32, 48, 48]` dimensions with theta 1000. Existing shared QK helper uses a different epsilon/rope layout and requires an explicit compatibility path or Krea-specific variant.
- Image patches use 16 channels and a 2×2 patch projection.
- The text path uses Qwen3-VL-4B hidden states from layers 2, 5, 8, 11, 14, 17, 20, 23, 26, 29, 32 and 35, followed by a learned fusion/refiner and projection.
- The model uses the Qwen Image VAE and an 8-step distilled flow sampler.

## Next work

1. Compare complete device generation against the official pipeline on matched inputs, then validate CFG and image quality at larger resolutions.
2. Fix long padded masks; measure memory, storage streaming, speed and thermals on V73.
3. Build production error handling, cancellation and storage management around the phone-side runner.
4. Resolve license-compliant distribution and content safeguards before registering Krea or distributing weights/packs.
5. Integrate Krea into the app only after the preceding correctness, runtime and licensing work.

## Distribution conditions

The weights use Krea's Community License, separately from the Apache-2.0 inference code. See the [official license](https://www.krea.ai/krea-2-licensing): deployments must implement appropriate content filtering; distribution of the model or derivatives has agreement, recipient-binding and attribution/notice requirements. Do not publish weights or packs until the license and app flow satisfy those requirements.
