# Krea 2 Turbo for Snapdragon 8 Gen 2 / V73

This is an experimental V73 port workspace. The shipping app does not load this pack yet.

## Current status (20 November 2026)

The V73 kernel library now has experimental Krea mode 71, a single-block Android smoke runner, and a deterministic synthetic K2B1 fixture. The smoke runner executed on a POCO F6 Pro (Snapdragon 8 Gen 2): 32 tokens, 3 workers, no DSP error or non-finite output. With zero synthetic weights, output differed from input by at most 3.73e-9. This checks dispatch, buffer layout, and basic execution only; it does not validate Krea weights, image quality, or performance.

Krea is not registered in the app and this code is not a usable end-to-end generator. The Hugging Face account has access to the gated repository, but no model weights have been downloaded. The DiT checkpoint is 26.3 GB, the text encoder is 8.88 GB, and the VAE is 508 MB. The laptop currently has 46.8 GB free, so download and conversion must be staged carefully. Do not commit model weights, converted packs, or build outputs.

## First vertical slice

`pack_krea.py` converts the 28 Krea 2 Turbo DiT blocks from the official `turbo.safetensors` checkpoint into a versioned `K2B1` pack:

- 5 matrices per block: fused Q/K/V, attention gate, attention output, interleaved MLP gate/up, and MLP down.
- Asymmetric 4-bit q4a tiles, with one scale/minimum pair per output channel and 32 input values.
- RMSNorm/QKNorm gains and each block's six-way timestep modulation are stored after the matrix data. The Q gain includes the SDPA `1/sqrt(128)` factor because the shared attention kernel takes unscaled QK products.
- A JSON manifest records matrix geometry and offsets.

The pack layout is a port-development artifact. The V73 block kernel and synthetic smoke runner are present, but the real checkpoint has not been packed or numerically checked. Text fusion, sampler, and the Qwen VAE path still need to be connected before this can generate images in HexAnima.

## Known architecture

- 28 single-stream DiT blocks, width 6144, 48 query heads, 12 KV heads, head width 128, SwiGLU width 16384. The block applies gated residuals around attention and MLP, with modulation order `[pre-scale, pre-shift, pre-gate, post-scale, post-shift, post-gate]`.
- The attention branch projects an elementwise `sigmoid(gate)` of width 6144 and multiplies it into the attended features before the output projection. This differs from Klein's head-wise `2*sigmoid` gate, so its mode-70 helper cannot be reused as-is.
- Q/K use per-head RMSNorm with epsilon 1e-5, then split-pair RoPE over three positional axes of `[32, 48, 48]` dimensions with theta 1000. Existing shared QK helper uses a different epsilon/rope layout and requires an explicit compatibility path or Krea-specific variant.
- Image patches use 16 channels and a 2×2 patch projection.
- The text path uses Qwen3-VL-4B hidden states from layers 2, 5, 8, 11, 14, 17, 20, 23, 26, 29, 32 and 35, followed by a learned fusion/refiner and projection.
- The model uses the Qwen Image VAE and an 8-step distilled flow sampler.

## Next work

1. Compare this packer's tensor names/shapes with an authorized Krea Turbo checkpoint and add a CPU reference for a single block.
2. Validate the K2B1 packer against authorized checkpoint tensors and a CPU reference; correct the current kernel path as those checks reveal issues.
3. Extend the runner for the model's 6144-wide activations, 3-axis RoPE geometry, and chunked attention at the 1024-pixel baseline.
4. Adapt the Mage Qwen3-VL-4B path for Krea's selected hidden layers and four-block text fusion.
5. Verify Qwen VAE equivalence, implement license-required content safeguards, and run end-to-end numerical/device comparisons before registering Krea in the app.

## Distribution conditions

The weights use Krea's Community License, separately from the Apache-2.0 inference code. A product integration must include the agreement and attribution, bind model recipients to its terms, and implement the required content filtering. Do not publish weights or packs until the license and app flow satisfy those requirements.
