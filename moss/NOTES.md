# MOSS-Image on the phone NPU (V69): research notes, 2026-11-07

Goal (user): run `unsloth/MOSS-Image-SFT-GGUF` UD-Q5_K_XL on the Snapdragon 8+ Gen 1 phone in HexAnima. Nothing is built yet; this is the study.

## Model facts (from pipeline_moss_image.py, sd.cpp moss_image.hpp, GGUF header)
- DiT 5.1B: 42 joint blocks, dim 3072, 24 heads x 128, ffn 8192 (fused ffn_up_gate = 16384 out: first half = up/"hidden", second = gate), no biases in blocks.
- Single stream: seq = image tokens (H/16 x W/16, latent 128 ch = 32 ch FLUX.2 VAE x 2x2) followed by text tokens; text tokens go through all 42 blocks (recomputed every step, shared per-step modulation).
- Per-step vectors, computed on CPU: time MLP (256->3072->3072), `shared_block_modulation` (3072->6x3072: shift/scale/gate attn, shift/scale/gate ffn; SAME for every block, no per-block adaLN), `video_head_modulation`.
- Block: y = RMSNorm(x)*(1+scale)+shift; q,k,v; q/k RMSNorm per head (128); 4D RoPE (r,f,h,w; 16 bands each, rotate-half over 128, text tokens: f=index); o-proj * gate (residual in x); FFN: SiTU(up, gate) = (4 tanh(gate/4) sigmoid(gate)) * (25 tanh(up/25)); down * gate.
- Text refiner: Linear(4096->3072) + 2 small blocks (qkv with bias, ffn gate/up/down with bias, same SiTU), runs once per step on the real text length (text path depends on nothing but text -> its output is step-independent EXCEPT rope? rope text part is fixed -> can be computed once per prompt).
- Text encoder: Qwen3.5-9B (hybrid: 24 gated-delta-net layers + 8 full attention, hidden 4096), `last_hidden_state` of the chat-template-wrapped prompt (enable_thinking=True). sd.cpp (master >= 975) implements it in llm.hpp (ggml_gated_delta_net, CPU ok).
- VAE: FLUX.2 AE (decoder 96/192/384/384 ch), BN denorm (running_mean/var) + 2x2 unpatchify. 250 MB (small) / 336 MB. A tiny TAE exists: AcademiaSD/TAE_Flux2_AcademiaSD.
- Scheduler: shift 5, 50 steps, CFG 3 with block 22 skipped in the negative branch (= 100 DiT passes).

## fp16 / HMX
- Official weights: residual stream peaks at 62k (fp16 max 65.5k). KBlueLeaf/MOSS-Image-FP16-fixed folds c=1/16 into embedders and the gate rows of shared_block_modulation. In my engine the residual x is fp32 in DDR and gates are applied in the fp32 epilogue, so the stream itself cannot overflow; o/ffn_down outputs peak 1.3k/0.7k (fine). Apply the same fold only if anything else shows inf.

## GGUF UD-Q5_K_XL (3.95 GB, 463 tensors)
- Block linears: Q5_K 1.72 GB, Q6_K 1.73 GB, Q8_0 0.16 GB per type mix (q: 12xQ5/30xQ6, k 13/29, v 13xQ8/29xQ6, o 39xQ6/3xQ8, ffn_up_gate 32xQ5/10xQ6, ffn_down 26xQ5/16xQ6); everything else BF16/F32 (embedder, time MLP, modulation 113 MB, norms).
- All K-quant groups run along K (the input dim) per output row -> one 32-group = one HMX tile column-block (32 K x 32 N), so per-(group,column) scale/min vectors map straight onto a tile dequant.

## Turbo / distillation (all UNMERGED LoRA: delta < bf16 rounding, merging destroys it)
- darask0/MOSS-Image_4-step_adapter_lora (rapid-moss, 4 steps, no CFG, best detail): lora.safetensors 1.19 GB fp32, rank 128 alpha 128 on q,k,v,o,ffn_up_gate,ffn_down of all 42 blocks; time_embedding.safetensors 40 MB (replaces time_embedding.*, shared_block_modulation.bias, video_head_modulation.bias; keep fp32); dataspace.safetensors: latent_mix.Q [128,128] + step_codes [1000,968,926,871]. Sampler: x = z + Q x0 ; x0 = z - v(x, code[r]) repeated 4x, same z, no re-noising. Trained at ~1 MP only (1024^2, 896x1152, 832x1216, 768x1344); smaller sizes unverified.
- lodestones/MOSS-Image-SFT-8-steps-dmd: same file format, 8 steps (each prefix trained), lora_latest at strength 0.7.
- akhaliq/MOSS-Image-SFT-4-steps: v2.0 4-step, plus 2-step (v0.1) and 3-step (v1.0) LoRAs (not inspected).
- LoRA cost on NPU: +6.5% FLOPs, 42 x 7.1M params -> ~600 MB fp16 (300 MB q8).

## Cost estimates (V69, measured Anima 2B: 28 blocks 2.07 s/step at 1536 tokens = ~2.7 TFLOPS effective, GEMM peak 5.3-5.9)
- Linear FLOPs/step = 2 x 4.75e9 x (img+text tokens). 512x768 (1536+~200 tokens): ~16.5 T + attention 1.5 T + LoRA 1.1 T = ~19 TFLOP -> ~6-7 s/step. 1024x1024 (4096+200): ~54 TFLOP -> ~17-19 s/step (attention ~5 s of that).
- 4-step total (no CFG): ~25-28 s DiT at 512x768, ~70-76 s at 1 MP; first-steps-low-res trick saves ~25%.
- Text encoder on phone CPU (sd.cpp/ggml, Q4_K_M 5.7 GB or UD-Q4_K_XL 6.0 GB): ~3-8 s per prompt (prefill only, no lm_head), cache embeddings.
- VAE: FLUX.2 decoder ~1.5 TFLOP at 512x768 (QNN fp16 on NPU ~1-2 s); TAE ~0.2 s.
- Disk: DiT q8 repack 5.1 GB (or native Q5/Q6 repack ~4.3 GB) + TE 5.7 GB + VAE 0.25 GB + LoRA 0.6 GB = ~11-12 GB. RAM: phases are sequential (TE, then DiT); 12 GB phone needed.

## Port work list (megakernel is Anima-shaped: DIM 2048, 16 heads, cross-attn, gelu)
1. Generalise DIM/heads (3072/24), joint attention (no cross-attn; nkv = nq = img+text padded to 32), 4D rope tables, per-head q/k RMSNorm (already), shared modulation (one set), SiTU in HVX (tanh/sigmoid via exp tables like gelu).
2. LoRA branch: x@A (K 3072/8192 -> N 128) then @B, added in the epilogue of q,k,v,o,up_gate,down.
3. Weight pack: GGUF -> dequant -> q8 per-column (phase 1) or native Q5/Q6 tile dequant (phase 2).
4. CPU side: time/modulation per step, text refiner (or HMX), sampler with Q mix, TE via sd.cpp Qwen3.5, VAE.
5. Reference: numpy/torch block reference with real weights to validate each kernel (same method as ref/anima_ref.py).

## Progress 2026-11-07 (evening)
- Files in models/: UD-Q5_K_XL gguf, darask0 lora.safetensors (756 keys: `diffusion_model.layers.N.proj.{q,k,v,o,ffn_up_gate,ffn_down}.{alpha,lora_down.weight,lora_up.weight}`), time_embedding, dataspace.
- moss_ref.py + fetch_bf16.py (range-fetch single tensors from the official bf16 repo). gguf shapes are ne-order: reshape reversed -> [out,in]. gguf BF16 data must be viewed as uint16.
- Verified layer 0: GGUF dequant vs official bf16: q 3.9%, k 3.9%, v (Q8_0) 0.7%, o (Q6_K) 1.8%, ffn_up_gate 3.8%, ffn_down 3.9% rel err; norms/modulation exact; shapes and ffn_up_gate layout match. Reference block runs (finite, 1.2 s on CPU for 136 tokens).
- q8 repack of the dequantized weights: per-output-column scale adds ~1.2% (v/Q8_0: 2.0%, worse than its own 0.7%); per-32-group scale adds 0.55% (v exact). -> per-tile (32xK group) scales are better than per-column; decide when writing the pack format.
- Still needed: real prompt embeddings (Qwen3.5-9B Q4 via sd.cpp CPU) for end-to-end reference.

## Kernel port design (read of hexagon/mega/{blk.c,gemm2.c,kern.h}), 2026-11-07
- The existing q8 tile record (1152 B = 128 B scale vector + 1024 B int8 for a 32x32 tile) is ALREADY per (32-K group, column) = "q8 g32" -> Q8_0 tensors repack exactly, Q5_K/Q6_K add ~0.55% error. No format change needed for phase 1.
- gemm_run_geo is generic in M,K,N (tiles); DIM=2048/NCB are compile-time macros in kern.h/blk.c/kern.c -> make DIM a parameter (3072, NCB 96, 24 heads x 4 tiles). attn_run loops 16 heads -> 24, keys nkv = img+text padded to 32 -> must MASK padded keys of the last key tile (write -inf into the S-tile lanes 2c,2c+1 for c >= valid before softmax; layout (r/2)*64 + c*2 + (r&1)).
- RoPE: MOSS = rotate-half over 128 with 64 distinct freqs per token (sin/cos duplicated) = same table shape as Anima's split-half ([slab][2048 floats]); q/k RMSNorm gain[128] shared by heads = k_rmsrope_head as is. Only the table builder changes (4D coords r,f,h,w).
- SiTU: ffn_up_gate N=16384 = [up 8192 | gate 8192]. Interleave N-blocks at pack time so panel p (n-blocks 2p,2p+1) = (up_p, gate_p); new epilogue mode computes SiTU(up,gate) = 4 tanh(g/4) sigmoid(g) * 25 tanh(h/25) via exp table (tanh = (1-e^-2|u|)/(1+e^-2|u|), sigmoid similar) and writes ONE tile -> mid is [N,8192] (half the memory, replaces mode 2 GELU).
- Shared modulation: a single (shift,scale,gate)x2 set for all blocks (Anima had per-block) -> k_ln_prep once per step; gates in the fp32 epilogue (residual x stays fp32 -> no FP16-fix fold needed).
- LoRA as extra K tiles: out = x*W + (x*A)*B_s = [x | L] * [W ; B_s] with L = x*A [ntok,128] (4 tiles). Implement as: small GEMM L = x*A_cat (N=128 per proj, qkv fused N=384), then in gemm_run_geo accumulate 4 more K tiles from the L buffer (A2 pointer) against 4 extra weight tiles appended to each n-block panel (kb+4 tiles; LoRA B pre-multiplied by alpha/rank, stored q8). +4% MACs for K=3072, +1.5% for ffn_down. LoRA down/up weights as q8 g32: error negligible vs Q5 base error.
- VTCM: K=3072 panel 384 KB, slab 196 KB -> ~31 slabs (1000 rows) per row group (weights re-read 2x at 1736 tokens, still hidden behind compute); K=8192 must be split into K parts (existing geo trick, 2-3 parts).
- Text refiner (2 blocks, biases, separate up/gate, no modulation) depends only on the text -> compute ONCE per prompt (CPU or NPU with mod=(0,0,1)), not per step.
- Per-step CPU work: time MLP + shared_block_modulation (113 MB bf16 matvec ~20-40 ms) + sampler mix Q (128x128 per token).

## Progress (night of 2026-11-07)
- sd.cpp (master 9234ad8) built CPU-only in sdcpp/ with SD_DUMP_COND(+_EXIT) hook in conditioner.hpp; `dump_emb.sh LLM TAG` dumps embeddings of prompts.txt to emb/TAG_i.f32 (float32 [T,4096]); UD-Q4_K_XL dumps for 4 prompts exist (26-39 tokens). Use --mmap to keep RAM low.
- moss_ref.py full DiT + sample_ref.py (rapid-moss 4 steps, same z, x = z + Q x0, codes 1000/968/926/871, VAE decode with BN denorm + 2x2 unpatchify) WORK END TO END: 256x256 "cat holding a sign HexAnima, watercolor" -> clean image with correct text (out/ref_cat_256.png), 52-61 s per step on the laptop CPU. So Q5_K_XL + LoRA + time path + Qwen3.5-Q4_K_XL embeddings is a valid recipe.
- export_moss.py packs a block (q,k,v,o, interleaved up_gate, down, with LoRA rows appended as extra K tiles, plus A matrices) into q8 tiles: 135 MB per block (5.7 GB total incl. 0.5 GB LoRA), round-trip error 0.58% on the base part.

## KERNEL PORT WORKS (2026-11-07 late): one MOSS block on the phone NPU matches the reference
- hexagon/mega (copy of the Anima megakernel, generalised): DIM 3072/24 heads, RMSNorm+modulation (k_rmsmod_s), joint attention with masked last key tile, LoRA as 4 extra K tiles (gemm geo A2), SiTU epilogue (mode 5, interleaved up/gate, 3 gather tables), K=8192 split in two parts. Build: `docker run --rm --platform linux/amd64 -v $PWD:/work ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash /work/build.sh` in hexagon/mega. Test: mkblk_test.py (reference from the q8 pack) -> adb push to /data/local/tmp/moss/tN -> `blk tN NTOK NVALID` -> cmp_blk.py.
- Block 0, 288 tokens: q/k/v/o/mid within 0.4-0.6%, x 0.21%. Block 20, 1562 tokens (512x768): q 0.97%, k 1.0%, v 1.2%, o 1.6%, mid 0.9%, final x 0.09% vs the q8-pack reference. Residual stream reaches 42985 (> fp16 max) -> fp32 x is required (it is).
- SPEED: 118 ms per block steady at 1562 tokens -> 42 blocks = ~5.0 s/step (matmul ~90 ms/block ~3.9 TFLOPS, attention 17 ms, LN 8 ms). 4 steps ~20 s (+ low-res trick possible).

## END-TO-END ON THE PHONE NPU (2026-11-08 00:00)
- npu_sample.py: 42 blocks of the q8 packs on the V69 NPU via `blk run NTOK NVALID 0 3 1 packs/blk%02d.bin 42` (one process per forward, packs on the phone in /data/local/tmp/moss/packs, 5.7 GB), embedder/time/head/sampler/VAE on the laptop CPU. 256x256 cat: velocities match the CPU reference (|v| within 0.001), PSNR 23.9 dB vs the CPU image (same composition/text, details differ).
- 512x768 "1girl silver hair, red eyes, school uniform, rooftop sunset" seed 7: clean, detailed image (out/npu_anime_512x768.png). Per step: 8.3-9.3 s wall of which 3.0-4.2 s is reading the 42 pack files from flash -> DiT compute ~5.1 s/step (matches the 118 ms/block estimate); 4 steps ~20.5 s compute. In the app the packs must stay resident (5.7 GB rpcmem) to avoid the file reads.

## VAE on the NPU (2026-11-08)
- export_vae_moss.py (moss venv, torch 2.15 + diffusers + onnx, legacy exporter, then ir_version=9) exports post_quant_conv + FLUX.2 small decoder for a fixed latent tile z[1,32,T,T] (BN-denorm + 2x2 unpatchify stay on the host). qnn/build_vae.sh NAME builds the fp16 HTP v69 context with the QAIRT env (qairt-converter --float_bitwidth 16 + qnn-context-binary-generator), 58 MB for T=32 (256x256 px).
- Phone test (qnn-net-run, /data/local/tmp/moss/qnn): rel err 0.16% vs fp32 torch (PSNR 76 dB), ~0.31 s per 256x256 tile (4.8 us/px) -> 512x768 ~2 s without overlap, 1 MP ~5 s. 112 MB onnx has InstanceNormalization (GroupNorm) + Softmax attention, all supported by HTP fp16.
- TODO: tile + blend runner (overlap), larger tile (512) contexts, TAE alternative not needed.

## Text refiner on the NPU (2026-11-08)
- Biases ride in the LoRA mechanism: the 4 extra K tiles hold row 0 = bias; the host pre-fills the L buffers with the "bias tile" (fp16 1.0 in column 0 of every 4th tile) and sets M_MODE=1 (LoRA-A GEMMs skipped). Norm weights come from the pack gain area, mod = (shift 0, scale 0, gate 1). The projection 4096->3072 runs as M_MODE=2 (x32 += gate*(text16 * Wp + bias)) with its own pack (export_moss.pack_proj). Reference check mkref_test.py: projection 1.5%, whole refiner (proj + 2 blocks) 0.94% rel err vs the CPU reference, ~0.1 s for 26 tokens.
- NaN fix in q8tiles: scale floor 6.2e-5 (denormal fp16 scales gave 0/0).
- Pitfall: blk_main H buffer must hold 4096 cols for the text tiles (was 3072: silent truncation gave 48% error).

## Text encoder: measured + reference (2026-11-08)
- sd.cpp (Android build, i8mm, Qwen3.5-9B UD-Q4_K_XL, mmap, 8 threads) on the 12T Pro: 33 tokens 10-14 s, 104 tokens ~23 s per prompt (!), so the TE on the CPU costs as much as the whole DiT. -> TE goes to the NPU.
- qwen35_ref.py (torch fp32, GGUF dequant) matches the sd.cpp dumps: per-token cosine mean 0.9989 min 0.9965 (rel err 4.7% = sd.cpp's own activation quantisation). Formulas (GGUF layout): rms weights already contain the +1; delta-net: causal depthwise conv k=4 over [q 2048|k 2048|v 4096] + silu; q,k l2norm (rms with eps/128, /sqrt(128)), q additionally /sqrt(128); beta=sigmoid(in_proj_b), g = softplus(in_proj_a + dt_bias) * ssm_a (ssm_a = -exp(A_log)); V head j uses q/k head j%16 (cyclic; j//2 gives cosine 0.64); S*=exp(g); kv=S^T k; S+=k (x) (v-kv)*beta; out=S^T q; per-head rms(ssm_norm) then silu(z)*out; ssm_out. Full attention: q_proj -> per head [q256|gate256], q/k head rms, NEOX rope on 64 dims (theta 1e7, pairs i,i+32), GQA 16/4, causal, scale 1/sqrt(256), out*sigmoid(gate), attn_output. MLP silu(gate)*up, down. final output_norm. Layer pattern: layers 3,7,...,31 full attention.
- GGUF UD-Q4_K_XL composition: Q4_K 2.36 GB (ffn gate/up, attn q/k/o), Q5_K 1.4 (qkv, gate), Q6_K 1.48 (ffn_down, v, output.weight 0.83 GB unused), Q8_0 0.43 (ssm_out), IQ4_XS 0.27, token_embd Q4_K (CPU lookup).

## !!! V69 qf16 subtract bug (found 2026-11-08 while building the q4a dequant) !!!
- `Q6_Vqf16_vsub_VhfVhf((0x6400|u), 1024.0)` returns u+1 for u = 4, 8, 12 (multiples of 4, u>0); the old q8 dequant `(1024+u) - 1152` had the same defect (some values off by one level). Fix: widening qf32 product `Q6_Wqf32_vmpy_VhfVhf(hf(1024+u), S)` and subtract/add the constant `C*S` (or `Mn - 1024*S`) in qf32, convert back with `Q6_Vhf_equals_Wqf32(vcombine(hi, lo))`. Dequant error went from 17% (q4a) / ~1% (q8) to 2e-4 (fp16 rounding); DiT block 20 q/k/v 0.97-1.2% -> 0.04%, o 1.6 -> 0.19%, mid 0.9 -> 0.07%, x 0.09 -> 0.01%. Speed unchanged for q8 (120 ms/block). The shipped HexAnima 1.2.2 q8 path (hexagon/mega/gemm2.c dq_tile) has the same defect -> its 2.9B models would improve too (not yet changed in the public repo).
- Stale-binary trap: when only `blk` (host) is pushed after editing the DSP side, the old libmega_skel.so runs (0x4e return for unknown modes, silently old behaviour for others). Always push BOTH.
- TE FFN on the NPU (layer 0, 33 tokens): 0.2% rel err vs reference, gate/up q4a (75 MB) 15 ms, down q8 (56 MB) 8 ms -> ~0.75 s for all 32 FFNs.

## Text encoder layers on the NPU (2026-11-08 night)
- kern.c: te_t2r/te_r2t (tile <-> row-major fp32), te_conv_silu, te_delta (gated delta rule, per-head state in VTCM-free static arrays, workers split the 32 heads), te_attn_prep (head rms + partial rope) and te_attn (causal GQA, hd 256, sigmoid output gate) all in the fp32 row domain with HVX; matmuls stay on HMX (q4a for Q4_K tensors = lossless repack, q8 g32 for Q5_K/Q6_K/Q8_0/IQ4_XS/F16).
- blk.c MODE 20 (delta layer), 21 (full-attention layer), 30 (final norm), pack = te_hdr_t table (4096 B) + matrices + f32 vectors (export_te.pack_te_layer); NCB 128 runtime.
- Verified on the phone with 33 tokens: layer 0 (delta) rel err 0.11%, layer 3 (full attention) 0.15% vs the torch reference; ~37-41 ms per layer (FFN 24 ms, qkv 6 ms) -> TE compute ~1.3 s for 32 layers at 33 tokens. Pack sizes 180-210 MB per layer (6.4 GB total) -> loading from flash ~4-5 s dominates.
- Pitfalls found: `Q6_Vqf32_vadd_Vqf32Vqf32` on an sf vector (stored state) silently gave 1e20 values -> use `..._Vqf32Vsf` for sf operands; blob M_H must hold 4096 cols; push both blk and libmega_skel.so.

## ALL HEAVY STAGES ON THE NPU (2026-11-08 ~01:00)
- te_packs/te00..te31 (6.2 GB: Q4_K -> q4a, others -> q8 g32; delta layers 180-210 MB, full-attn 180 MB, some 246 MB) + te32 (final norm). `blk te NTOK T 0 3 1 te_packs/te%02d.bin 33` with TE=1 TEFINAL=1 NCB=128 WTSIZE=270000000: 33 tokens, 5.6-6.5 s of which 4.3-5.2 s is reading the packs from flash; output (h16 tiles [slab][128 cb]) vs the torch reference: cosine mean 0.99976 (rel err 2.2%), vs the sd.cpp dump 0.9986 (same as the torch reference vs sd.cpp).
- Refiner chained straight on the TE output tiles (MODE 2 + 2x MODE 1): 0.4 s.
- Full-frame VAE contexts: qnn/vae_96x64.bin (768x512, 68 MB): PSNR 76.5 dB vs torch, ~2.0 s per image. Tiled 256 px contexts decode any size but GroupNorm statistics per tile give a smooth tone shift (PSNR 28 dB vs full-frame, visually minor) -> per-preset full-frame contexts + tiled fallback.
- npu_all.py (adb-driven, laptop does tokenizer/embedding lookup/embedder/time/head/sampler): prompt -> 768x512 image, all matmul/conv work on the NPU, out/npu_all_anime.png clean. Timing on the phone without adb overhead: TE ~6.5 s (flash bound), refiner 0.4 s, DiT 4 x ~5.1 s compute (+4-5 s per step of pack file reads in this test; resident in the app), VAE 2 s.
- Left for the app: C++ phone runner (embedding lookup from token_embd Q4_K, time/mod/head/sampler on the CPU, pack loading/residency, VAE call), tokenizer (Java or sd.cpp's hf_tokenizer), model manager/download of ~13 GB, UI.

## mossrun: the whole pipeline in ONE process on the phone (2026-11-08 ~01:30)
- mossrun/mossrun.cpp (+ build.sh; maprobe.cpp): `load MODELDIR` then `gen IDS.i32 H W SEED STEPS OUT.z`; stdout "step i ms", "done ms". It does: Q4_K embedding rows (CPU), TE 32 layers + final norm (NPU), refiner proj + 2 blocks (NPU), then per DiT step: sampler mix + embedder (CPU), time/modulation (CPU matvec f16/f32), 42 blocks (NPU), final norm/modulate/linear 3072->128 (CPU), x0 = z - v; BN denorm + 2x2 unpatchify -> fp32 [32][H/8][W/8]. The VAE decode is vaerun (existing generic QNN runner) on that file: 783 ms for 768x512 (vae_96x64.bin).
- MODELDIR: glue.bin (export_glue.py, 195 MB), embd.q4k (572 MB, raw GGUF Q4_K rows), te/te00..te32.bin, ref/{proj,ref0,ref1}.bin, dit/blk00..blk41.bin.
- Measured on the 12T Pro (768x512, 33 tokens, 4 steps): TE 5.3 s, refiner 0.36 s, steps 6.1-6.4 s each, total 30.6 s to the latent, + VAE 0.8 s. Clean anime image generated with no laptop (out/mossrun_anime.png).
- DSP process limits measured with maprobe: ~1.5-1.8 GB of fastrpc_mmap'd buffers per process in total (any chunk size <= 512 MB), but a 128 MB map costs only 0.6 ms (unmap 0.3 ms) -> map on demand (LRU window).
- Resident DiT (5.7 GB rpcmem) got the process killed (phone MemAvailable ~6 GB with other apps) -> weights are STREAMED from flash through two 250 MB buffers with a loader thread (read of block k+1 overlaps compute of k; read ~104 ms vs compute ~118 ms per block, so no slowdown).
- Remaining per step overhead (~1 s of CPU glue: embedder 1536x128x3072, head, time MLP/modulation matvec) can be NEON-optimised; 6.1 s/step vs 5.1 s compute.
- TODO for the app: tokenizer (Java or sd.cpp hf_tokenizer), size presets with matching VAE contexts (+tiled fallback), PNG + metadata writing, model manager (about 13 GB: dit 5.7, te 6.2, embd 0.57, glue 0.2, refiner 0.4, vae contexts 70 MB each), lmkd/thermal behaviour, UI strings.

## MOSS in the app (2026-11-08 ~02:00), working tree of anima/hexanima (UNCOMMITTED, not released)
- Java tokenizer QwenTok.java (tok_vocab/merges/special.txt made from tokenizer.json): token-for-token identical to HF tokenizers on 5 prompts (CJK, emoji, JSON, spaces), loads in 190 ms.
- phone/moss.sh (tokens -> mossrun -> vaerun -> raw RGB), Moss.java (ids file, sizes from installed VAE contexts vae/vae_WxH.bin, raw->PNG), Models/ModelsActivity (catalog kind "moss", files/moss, no sd stub/common pack), Main (isMoss: runOneMoss with progress from moss.log, no quality prefix, size list from contexts, Load button disabled, Stop kills helpers), tools/make_moss_pack.py (manifest with sha256), tools/stage_jnilibs.sh (libmossrun.so, libmossskel.so; skel copied to files/moss/dsp/libmega_skel.so and put first in ADSP_LIBRARY_PATH).
- Test build: package com.hexanima.mosstest (separate app, debuggable; the user's HexAnima 1.2.2 untouched), model files copied with `run-as cp` from /data/local/tmp (13 GB in its files/moss). Result: prompt typed in the UI -> "Gotovo za 32,3 s", 512x768 picture with the sign text correct (te 5.5 s, refiner 0.4 s, steps 5.6-6.0 s, VAE 0.8 s). Apps run mossrun as untrusted_app fine (rpcmem, FastRPC, ';' ADSP path, skel from files dir).
- Not done: HF pack upload + catalog.json entry (needs the user's HF token), more VAE size contexts (only 512x768 exists), tiled VAE fallback, Settings/Info/README texts, App size presets, thermal/lmkd behaviour over many pictures, version bump (1.3.0).

## Pack, licenses, upload (2026-11-08 ~01:30)
- Licenses (HF cards + repo files): MOSS-Image-SFT, unsloth GGUFs, Qwen3.5-9B, rapid-moss adapter (+ lodestones DMD), FLUX.2 small decoder (BFL) are ALL Apache-2.0. The VAE file in OpenMOSS (249 521 372 B) == BFL FLUX.2-small-decoder diffusion_pytorch_model.safetensors (249 521 340 B + header). Pack docs: dist/LICENSE (Apache-2.0), dist/NOTICE.md (sources, authors, changes), dist/README.md; THIRD_PARTY.md row added in the hexanima working tree. Do NOT put the pack in ayanoby/anima-npu-packs (CircleStone non-commercial license).
- Full-frame VAE contexts built (qnn/out/vae_WxH.bin): 512x512 640x640 768x768 512x768 768x512 576x1024 1024x576 1152x640 640x1152. >= ~0.8 MP (768x1024, 1024x768, 1024x1024, ...) cannot be exported on the 14 GB laptop (torch ONNX export is OOM-killed, even without the reference decode) -> need the tiled fallback (vae_t32 256 px tiles, vae_tiled.py proves it: 576x832 fine, PSNR 26.7 dB vs full frame, smooth tone shift from per-tile GroupNorm).
- Pitfalls: `ulimit -v` makes torch exports fail (virtual size), `pkill -f <name in my own command line>` kills the tool shell, long foreground waits get the shell killed -> use setsid nohup.
- Pack = dist/ (symlinks), manifest.json (14.14 GB, sha256), uploaded with upload_hf.py (per-file upload_file, resumable, skips present files) to the PRIVATE repo ayanoby/hexanima-moss-packs. The catalog entry (kind moss, absolute manifest URL) is printed by tools/make_moss_pack.py and must be added to anima-npu-packs/catalog.json only at release time (1.2.2 would show it as an Anima model).

## Any size (2026-11-08 ~02:40)
- vaetile (mossrun/vaetile.cpp, from vaerun): tiled decode with one 512 px context (vae_512x512.bin, tile 64 latents, overlap 16, linear blend, replicate pad for small images): 576x832 PSNR 30.8 dB vs full frame (256 px tiles gave 26.7), 2.1 s; 1024x1024 4.7 s. moss.sh uses vaerun when vae_WxH.bin exists, else vaetile.
- Sizes: any multiple of 16, >= 256, <= 6144 patches (attention VTCM: 17*N*2 KB <= 7 MB -> N <= ~6700 tokens); presets = 18 sizes incl. the adapter's training sizes (1024x1024, 896x1152, 832x1216, 768x1344 + landscape). mossrun checks the patch limit.
- Measured in the test app: 832x1216 (3952 patches) 79 s total; mossrun 1024x1024: te 5.4 s, steps 16.7-18.9 s, total 76 s + VAE 4.7 s.

## Speed analysis (2026-11-08 ~03:00), 768x512 unless noted
- Time split (30 s): TE 5.2 s (flash read of 6.2 GB bound), refiner 0.35, 4 steps x 5.7-6.2 s (DSP compute ~5.0-5.3 + CPU glue 0.3: mix+embed 125 ms, head+sampler 150 ms, time/mod 25 ms), VAE 0.8, app ~1.
- Flash is the limiter: one reader ~0.9 GB/s, 4 parallel readers ~1.5 GB/s. DiT streams 5.7 GB per step (~4.1 s at 1.4 GB/s) vs ~5 s compute -> at small sizes steps are read-bound (half-size steps took 4.2 s = the read time).
- DONE: 4-way parallel pread (TE 5.3 -> 4.5 s); tx.cache (text side reused for the same prompt: -5.4 s per extra picture / seed / batch item, second picture 25.4 s).
- TRIED, rejected: low-res first steps (MOSS_LOWRES=2, quarter of the tokens, rope scale 2 or 1): same style but different composition and stray glyph artifacts, only -1.4 s per step because of the read bound; page-cache keep of the first N DiT blocks (MOSS_KEEP): slower on the 12 GB phone (reclaim stalls), now opt-in.
- Block profile at 1568 tokens (118 ms): qkv 24.5 (3.8 TFLOPS), attn 17.4, o 8.5 (3.6), LN 8, up_gate 28.5 (5.8), down 26.4 (3.0). At 4128 tokens (1 MP, 355 ms/block): attn 93 ms (26%): setup 13, QK 21, PV 16, rowscale 12, softmax wait 28 (workers are the bottleneck, main idle), flush 2.
- Ideas not done (est. gain): 5/6-bit native weight tiles and de-duplicated scale vectors (-25..30% bytes: TE -1 s, steps -0.5 s each at small sizes); qkv as one GEMM + faster down-proj (up to -15 ms/block = -0.6 s/step); attention: overlap K^T setup with HMX, main thread joins softmax (-5% at 1 MP); NEON embedder/head (-0.15 s/step).

## User LoRA on the fly (2026-11-08 ~02:10)
- LoKR design check (2026-11-13): the original `MOSSRealisticSnapshotV1.safetensors` contains 164 complete BF16 `lokr_w1`/`lokr_w2` pairs over 41 blocks. `w1` is 4×4; block 0 has `w2` shapes QKV 2304×768, O 768×768, FFN up/gate 4096×768, and FFN down 768×2048.
- `moss/verify_lokr_factorization.py` checks the exact factorized forward against dense `kron(w1,w2)` for the four block-0 projections. Relative errors: 3.21e-7 (QKV), 3.05e-7 (O), 2.96e-7 (up/gate), 4.49e-7 (down). For activation groups `[input_group,input_width]`, apply `w2` to each group, then mix the output groups using `w1`; this avoids creating the dense update.
- The factored sidecar and V73 block-kernel path were implemented experimentally and built. On the phone, the original adapter passed validation and sidecar folding, but generation with it stayed at `step_start 1` for more than 60 s; the same prompt without an adapter completed in 22.8 s. Strength is passed into the factors, but practical generation is not validated. The app therefore disables LoKR and continues to support only compatible A/B adapters. Do not package the experimental LoKR path as an enabled feature until a bounded V73 run completes correctly.
- Test LoRA: loras/MeIoN_MOSS.safetensors (civitai MeioN39 style, ai-toolkit): rank 8, alpha 4, keys layers.N.proj.{qkv (fused q,k,v rows), o, ffn_up_gate, ffn_down}.lora_A/B (bf16, no alpha tensors, alpha in metadata ss_network_alpha), layer 22 absent. Trained on the BASE model (not turbo).
- make_lora_overlay.py USER OUTDIR STRENGTH: folds turbo (rank 128) + user LoRA into the existing 4 LoRA K-tile slots by QR+SVD (rank 136 -> 128, dropped energy <= 0.7 %), writes per-block lora_ovl/blkNN.ovl = list of (offset, bytes) patches (B tiles in each n-panel + the A matrices; ~8 MB/block). Takes ~40 s on the laptop for all blocks. mossrun: env MOSS_LORA=dir -> the loader thread patches the buffer after each DiT pack read (no kernel change, no measurable slowdown).
- Result (512x768, same seed/prompt): clean pictures, style/composition clearly changed (out/lora_cmp.png left = base, right = LoRA). lora_test.py drives the phone over adb.
- Open: SVD must move on-device (or use a 5th K tile in the kernel) for in-app use; strength slider = re-fold; LoRAs with larger rank than ~64 would lose more in the 128-rank truncation.
- In-app LoRA (2026-11-08 ~02:20): mossrun/lorafold.cpp (check + fold, built by mossrun/build.sh, shipped as liblorafold.so by hexanima/tools/stage_jnilibs.sh). `check` reads only the safetensors header: key pattern layers.N.proj.{qkv|q|k|v|o|ffn_up_gate|ffn_down}.lora_A|B (down|up), shapes (in 3072/8192, out 9216/3072/16384), rank 1..128, F32/F16/BF16, layer < 42; foreign keys (double_blocks, lora_unet...) -> OTHERMODEL. `fold` reads the turbo LoRA back from the packs (dequant of the rank-128 tiles/A matrices), Gram + Jacobi eig/SVD in double, rank 136 -> 128, requantises tiles; all 42 blocks in 13.6 s on the phone (6 threads), 312 MB of .ovl; error vs exact sum 1.0-1.1 % (two q8 round trips + truncation). Generation with the device overlay differs from the laptop overlay by 6/255 mean pixel diff.
- App (MossLora.java + Main.java): LoRA card under SEED (MOSS only): Choose -> copy to files/moss/lora/user.safetensors, check -> green "Compatible: rank 8, 41 of 42 blocks" or a dialog with the reason (tested: FLUX-style keys -> "другая модель", wrong shape, rank 200). Strength slider 0..2 step .05 (prefs moss_lora_str); first generation at a new (LoRA, strength) shows "Preparing the LoRA… k/42" (~14 s), cache files/moss/lora/ovl_<id>_<strength*100> (only the last kept), then MOSS_LORA=dir is passed to moss.sh/mossrun. Whole picture with LoRA in the UI: 43.8 s (14 s fold + 29 s). Metadata of the picture gets lora + lora_strength.
