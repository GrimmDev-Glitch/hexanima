# Pitfalls, findings and "how to resume" (read this first after a context reset)

Goal: Anima 2B DiT on the Xiaomi 12T Pro NPU (SM8475, Hexagon V69). Status and numbers: `results/README.md`; original (partly wrong) report: `REPORT.md`.
Current best: a real 512x768 image is generated with the 28 DiT blocks on the NPU: 28 blocks 2.82 s per step on the phone (3.8 s per step with transfers), ~15 s for 4 steps (laptop sd.cpp: 18.6 s). Single-block one-shot figure: 173 ms (RmsRope v2) vs 219-230 ms stock.

## Environment / how to run (things that were expensive to find)
- Phone: adb works, Magisk root (`su`), plain `shell` user is enough for qnn-net-run and for unsigned FastRPC DSP modules. Files live in `/data/local/tmp/anima/` (use UNIQUE names: an earlier test overwrote `x_*.raw` and produced bogus errors).
- QAIRT 2.28 environment on the laptop: `source ~/anima-npu/work/env.sh` (python 3.10 venv with torch cpu, onnx 1.16.1 (newer breaks the converter), lxml and mako for the op-package generator). Outside that env `python` does not exist; use `~/anima-npu/work/.venv/bin/python` for numpy checks.
- Hexagon SDK: Docker image `ghcr.io/snapdragon-toolchain/arm64-android:v0.8` (SDK 6.7.0.0, hexagon-clang 21.0.04, `-mv69` works). `docker pull` log can look stuck at 8/11 layers while it has finished; check `docker images`. The image has NO `make` (Makefile rules are written out in `build.sh`), only `computev81` qurt headers (used for v69 too), and needs `apt-get install libc++-dev libc++abi-dev` for the x86 package. Files created in containers are root-owned: delete them with `docker run --rm -v $PWD:/work <image> rm -rf /work/gen`.
- The DSP has NO `libc++.so.1`: link op packages with static `libc++.a`/`libc++abi.a` (`-nostdlib++ -Wl,--start-group ...`), otherwise registration fails with err 1003/4007 (look in `adb logcat`, tag `QNN`).
- Never run the laptop GPU generation while GUI/other sd process holds the GPU (hangs the laptop); this NPU work does not touch the laptop GPU.

## Measuring (traps)
- `qnn-net-run --profiling_level detailed` makes graphs whose tensors live in TCM (small graphs) fail with "Dma execution failed on the skel side (6006)". It cost an hour of bisecting the kernel. Use `basic` for timing; `detailed` is fine for big graphs.
- Per-inference time jumps 220..250 ms (DVFS/thermal): compare MIN over many runs, alternate A/B (`ab.sh`). Cycles from the profile and wall time disagree (idle/stalls are not in cycles).
- Debug on the DSP: `errlog` output is not visible. Bisect with early `return Success`, or write debug numbers into the output tensor and decode them (see git history of hvx_op.inc.dbg approach: dims were recovered from dequantized output values).
- The profile `viewer.txt` contains the graph 3 times; `parse_profile.py` uses the first. Op names `X.nhwc` / `X.nchw` are converter-inserted layout copies.
- Quant error references: stock block error on the delta 6.5-8.3% (cos 0.997); attention 2.5/2.0/1.7%. New ops must not change these.

## Quantization / dtype facts
- W8A16 is the working mode. FP16 compiles and runs on V69 (docs: "8 Gen 1 or newer") but is ~1.8x slower for matmul layers and 20x more accurate; mixed FP16 via quantization overrides is 2x slower (float MatMul after float softmax, unfused RMSNorm) and does not improve block accuracy; the fused float `RmsNorm` does not exist (`no properties registered for q::QNN_RmsNorm`).
- `qnn-net-run --use_native_input_files` needs fp16 files for FP16 graphs; for fp32 files use no native flag.
- V69 has no int->float conversion instructions (`vconv_sf_w`, `vcvt_hf_h` are V73+; the compiler crashes with "Cannot select"). Use the exponent trick: `bits(0x4B000000|u) - (2^23+zp)`, and back via +2^23 and the low 16 bits.

## Custom HTP op packages (QNN) - the long list
- Toolchain: `qnn-op-package-generator -p X.xml` (needs SupplementalOpDef for BACKEND_SPECIFIC), implement the op in the generated `src/ops/*.cpp` (`patch_op.py` does it automatically), build for hexagon-v69 AND x86 (x86 is needed by `qnn-context-binary-generator --op_packages`), `qairt-converter --op_package_config X.xml`, `qairt-quantizer --op_package_lib <CPU package>`, then `qnn-net-run --op_packages libQnnPkg_v69.so:ProviderName:HTP`.
- `qairt-quantizer -opl` loads the package into the CPU backend: a quantized graph needs a SECOND package with a CPU float reference of the op (`RmsRopeCpu.xml`, `patch_cpu.py`, built with plain g++).
- The converter needs the custom op output shapes: give `value_info` in the ONNX (otherwise "inferOutputShapes: Please provide the op package library").
- A custom op's input is treated as NHWC (converter inserts NCHW->NHWC), its output stays NHWC. A RANK-3 output avoids any later layout copies; do not put a Reshape in front of the op (that inserts `.nhwc` copies, 6.4M cycles each).
- All tensor arguments of the registered function, INCLUDING `eps`, must be the base `Tensor` type. With `PlainFloatTensor eps` the op cannot be created when its inputs live in TCM ("could not create op ... QuantUint16_TCM"). One generic function dispatches on `in.get_dtype() == DType::QUInt16` to the HVX path.
- `DEF_PACKAGE_OPTIMIZATION` patterns list the param as an extra operand (`Op("RmsRope","X","G","C","S","E")`); the 4-operand pattern silently never matches. `AUTOSPLIT` needs input and output split along the same axis; it worked (4 threads) but gave no wall-time gain, so it is off.
- `DEF_TENSOR_PROPERTIES(Op("RmsRope","in0","gain","cos","sin","eps"), Flat("*"), MainMemory("*"))`; RESOURCE_HMX is not available to external packages (HVX only).
- torch export of a custom op: `torch.autograd.Function` with `symbolic` -> `g.op("anima::RmsRope", ..., eps_f=...)`, `out.setType(x.type().with_sizes([...]))`, `torch.onnx.export(..., custom_opsets={"anima":1})`.
- ONNX Transpose (0,2,1) of K in front of MatMul is folded by QNN into MatMul (no extra copy).

## Converter layout rules for custom ops (found the hard way with HeadsPermute)
- INPUTS: a rank-4 input arrives in the converter's native NHWC (dims `[1,H,W,C]`), no copy if it comes from a Conv. A rank-3 GRAPH INPUT gets an
  implicit NCF->NFC `Transpose` (named `x.nfc`) in front of the op, so the op sees `[N,F,C]`; for a rank-3 INTERMEDIATE tensor (e.g. MatMul output)
  nothing is inserted and the op sees the ONNX shape as is. A standalone test with a rank-3 graph input therefore needs the shape declared transposed.
- OUTPUTS: the converter takes the declared ONNX output shape literally (it assumes NCHW for rank 4) and inserts `<name>.nhwc` Transposes before a
  Conv. If the op writes NHWC memory, declare the output as the NHWC dims `[1,gh,gw,C]` and follow it with ONNX `Transpose(0,3,1,2)`: the converter
  then merges the two transposes and the Conv reads the op output as is. Check with `qairt-dlc-info -i X.dlc | grep "^| [0-9]"` that no layout copy sits around the op.
- A standalone graph whose op output is a graph output returns the op's raw memory order (NHWC) in the .raw file - compare against the right layout.
- `flow.sh ... rebuild` prints only `error` lines of the package build; if the build fails the OLD .so stays on the phone and the test silently runs the old kernel
  (cost me a long detour). When results do not change after an edit, run `build.sh` by hand and read the errors.
- Calling `out.set_dims(array)` on the output tensor is needed before reading `out.get_interface_scale()` in some cases; `set_dims(prototype)` copies the input's dims.
- Phone throttling: the stock block went 219 -> 250 ms during a long session; only alternate A/B runs (`ab.sh bk_block.bin a.bin b.bin`) are comparable, and cycles in a profile
  inflate by 15-20% when hot. Let the phone cool or compare against a stock run taken in the same minute.
- HeadsPermute results: split-heads for v (mode 0) gave no gain (kept off, `RR_V=1` enables); merge-heads (mode 1) gave ~3-6% in A/B, within noise of throttling.

## Kernel (HVX) lessons
- Instruction count was not the bottleneck; dependency LATENCY was: processing 4 rows STAGE BY STAGE (independent chains interleaved) halved the time; 2 or 8 rows were not better; `#pragma unroll` alone did nothing.
- Vector->scalar round trips (store vector, load scalar, sqrtf) are slow; keep rsqrt in the vector domain (0x5f3759df trick + 2-3 Newton steps; every lane holds the sum after the `vror`+add reduction).
- Per-position tables with gain and 1/out_scale folded in; sequential memory order; `dcfetch` of the next block gives ~10%.
- Raw HVX: a pass over a DDR-resident 3.1M-element tensor costs ~2.4 ms (memory bound, same as stock QNN Mul); in-cache rates are 10-17x higher. Fusing passes is the only way to win.
- Layout lesson: keep the residual stream as `[1,D,gh,gw]` (making it `[1,D,1,n]` made the block 268 ms instead of 173).

## Where the time goes now (block 13, cycles, total 148M, wall 174 ms)
Conv 31.8M, MatMul 19.9M (together ~4 TFLOPS: matmul part is fine), Softmax 16.8M, GELU 12.7M, RmsRope 12.6M, LayerNorm 8.2M, Mul/Add ~11M, attention-output layout copies ~13M, v-path copies ~4M, graph I/O converts 12.8M (absent in a real model).
Next candidates (in order): merge-heads op [16,P,128] -> NHWC and split-heads for v; LayerNorm+adaLN fused; GELU via table/vgather (VTCM access from a package is unknown); softmax with LUT exp.

## End-to-end generation on the NPU (stage notes)
- Plan: sd.cpp does text encoder, noise, Euler loop and VAE; its `AnimaRunner::compute` is hooked (`sd-npu-hook.patch`, env `SD_NPU_DIR`, `SD_NPU_MODE=dump|extern`):
  dump mode writes x/t/ctx/ids/w and sd.cpp's own velocity per step; extern mode writes the inputs and WAITS for `out_<n>.bin` + `out_<n>.bin.done`.
  `ref/serve.py` answers it with the torch reference (`--backend ref`) or the phone (`--backend npu`). The hook was added IN PLACE in `sd-src/src/model/diffusion/anima.hpp`
  (original saved as `anima/npu/anima.hpp.orig`, env-gated so the normal behaviour is unchanged); the hooked binary is `anima/sd-cli-npu` (build: `ninja sd-cli` in `sd-src/build`, 12 s);
  `tools/bin-opt-sd-*` were NOT touched. Run it exactly like `gen-anima.sh` (`SD_VK_TILE=1`, taesd, clip on cpu) and check the GPU is free first.
- The shell safety check refuses `rm` with a glob after `cd` (even in a fresh dump dir): use a new directory name instead of cleaning.
- The reference `ref/anima_ref.py` reproduces sd.cpp's velocity at 5.8% rel err / cos 0.998 (fp32 torch vs f16 ggml) and the 4-step image has the same composition and
  character with different fine detail. Details that mattered: RoPE of h/w uses NTK-scaled theta (`10000*4^(42/40)`; without it the error is 17%), tables are split-half with
  axes (t 44, h 42, w 42); timestep embedding takes sigma directly ([cos, sin], no x1000); x tokens flatten as (c, ph, pw), output as (ph, pw, c); the adapter output is
  multiplied by the t5 weights and zero padded to 512 (a zero context row is harmless for the cross-attention k/v since it stays zero through k_norm); final layer chunks are [shift, scale].
  The earlier `phase0_block.rope_cos_sin` lacks the NTK scaling (speed results are unaffected, calibration data were slightly off).
- Real-weights block 0 on the NPU: rel err on the delta 3.6%, cos 0.9994 (better than block 13 with synthetic data, 7%).
- Per block ~40 s to export+quantize+compile; `hexagon/rmsrope/export_all.sh FIRST LAST` does a range, `ref/run_blocks.sh` runs the chain on the phone (one `qnn-net-run` per block).

## Persistent runner (chainrun) lessons
- `qnn-net-run` per block costs ~0.65 s of process start + context create/free on top of ~0.17 s of compute (24 s per step in total). `chainrun/chainrun.cpp`
  (QNN C API via dlopen, no linking; build with the NDK in the Hexagon-SDK container, `chainrun/build.sh`) loads the contexts once.
- One DSP session (= one process) holds ~10 block contexts: `contextCreateFromBinary` fails with 0x138d at the 11th (~330 MB each incl. activation scratch, ~3.5 GB of
  session address space). Several processes (sessions) are independent: 3 processes hold all 28 blocks at once. x passes between them through files on the phone.
- Burst power config must be set by hand in a custom app (`deviceGetInfrastructure` -> `perfInfra.createPowerConfigId/setPowerConfig`, DCVS_V3 with MAX voltage
  corners + RPC control latency 100); with it a block takes ~100 ms vs 170-220 ms measured through one-shot `qnn-net-run` runs (cold start, first inference slower).
- Keep the binary-info system context alive (tensor names point into it); input tensors are identified by name (x, emb, lora, ctx), IO is float32 (preserve_io_datatype).
- stdout/stderr of the adb shell are merged in `npu_chain.py`: skip stray log lines when waiting for `done` (the last `loaded block` line can arrive after `ready`).
- The adapter output (cross-attention context) is constant across steps: cached in `anima_ref.prelude`.

## Files
`hexagon/rmsrope/`: `RmsRopePkg.xml`/`RmsRopeCpu.xml` (op definitions), `patch_op.py`/`patch_cpu.py` (fill generated skeletons), `hvx_op.inc` (kernel), `build.sh`, `flow.sh` (convert -> quantize -> compile -> push), `time_cfg.sh`, `ab.sh`, `make_attn_graph.py`, `make_rr_graph.py`, `block_rr.py`; `hexagon/hello`, `hexagon/toyop`, `hexagon/oppkg` are earlier steps. Generated `gen*/`, `run/` are git-ignored; recreate with `flow.sh ... rebuild`.
Quick resume: `cd hexagon/rmsrope/run/blk; source ~/anima-npu/work/env.sh; python ../../block_rr.py <model> . ; ../../flow.sh block_rr main_graph input_list.txt rebuild; ../../ab.sh bk_block.bin block_rr.bin` (inputs `x_i.raw` etc. were copied from `~/anima-npu/work/full2`, phone has `bk_*.raw`, `bk_dev.txt`, `bk_one.txt`).

## Phone-only pipeline (animad / Android sd.cpp)
- sd.cpp builds for Android with the SDK container's NDK (`android/build_sd.sh`): -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16. `--mmap` keeps the 4 GB DiT file out of RAM (the DiT is never computed in extern mode).
- The unstripped sd-cli is 250 MB (fine). Run it with `-t 8`; no GPU device on the phone ("auto-fit: no GPU devices").
- `t_embedder`: lora = linear_2(silu(linear_1(tp))) (silu AFTER linear_1; I first applied it twice -> lora error 24%, step error 47%). Check intermediates (x tokens, emb, lora) against `anima_ref.prelude` with `ANIMAD_DBG=1`.
- `chainrun` children must be exec'ed from /data/local/tmp/anima with LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. (animad does this itself).
- TAEHV (taew2_1) on the NPU: single latent frame = 2D convs only (time upscale [F,T,T], frame 0 trimmed): `vae/taehv_torch.py` (matches ggml) -> `vae/export_vae.py` -> `vae/build_vae.sh` (FP16, vtcm 8, fp16_relaxed_precision). 17 MB context, 0.22 s with qnn-net-run, 0.1 s in `vaerun`.
  Its I/O tensors are FP16 in the context (`vaerun` converts the fp32 files itself; the W8A16 blocks have fp32 I/O thanks to preserve_io_datatype).
- Text encoder time on the phone depends on the page cache: 1.2 GB bf16 file via `--mmap` = 3.5 s warm, 7-14 s cold or after the NPU contexts evicted it; `--type`/`--tensor-type-rules` (f16/q8_0) gave no speedup.
- adb shell hangs if a background process started inside it keeps the session: use `setsid nohup ... < /dev/null > log 2>&1 &`; `adb push` drops the executable bit (chmod again).
- animad serve protocol: request dir `/data/local/tmp/anima/req/r<id>/` with `start` (step count); sd-cli gets SD_NPU_DIR=<dir> SD_VAE_DIR=<dir>/vae (extern modes); animad writes `finished`; `req/quit` stops it.
- Android app: every `su` start makes Magisk run an `app_process` log call (+ toast) that steals CPU from the 4 text-encoder threads (text 5.1 s vs 3.7 s from an adb root shell). Use ONE long-lived root shell (stdin commands + `echo __END__` marker) and a second one for progress polling; give every spawned command `< /dev/null` or it eats the shell's stdin.
- Text encoder on the NPU (Qwen3): the converter fuses `x*rsqrt(mean(x^2)+eps)` into RmsNorm taking the INPUT of the pow; my first fp16-safe variant (square of x/64, but multiply by the unscaled x) was fused wrongly (output 64x too small). Write it as `xs=x/64; xs*rsqrt(mean(xs^2)+eps/4096)*w` (identical math, fused correctly). Bisect with output taps (`TAP=n` env in export_te.py).
  Qwen3 residual activations reach ~7000 (massive activations): squaring them in fp16 overflows, hence the 1/64 pre-scale. FP16 (not W8A16) is fast enough here: 28 layers = 70 ms.
- QNN `contextCreateFromBinary` copies the binary: free the client buffer afterwards. `terun` first kept 880 MB of bins resident and the whole phone slowed down (adapter 0.4 -> 2-4 s, NPU steps 2.9 -> 3.5 s, swap in use); freeing them restored the numbers.
- When one process may or may not provide a hook result, the other side needs an explicit "do it yourself" marker (`qh.cpu`), otherwise sd.cpp waits forever.
- Benchmark blocks with chainrun, not qnn-net-run (200 vs 100 ms per block). Block-merge experiment: `NB=4 /tmp/exp_blk.sh`-style compile of `npu_export.py ... I NB` (module `Multi`); merged graphs need no extra calibration (input of the first block only).
- `pkill -f PATTERN` inside a Bash call kills the call itself when the pattern is in its own command line (exit 144/137); use `ps -eo pid,args | awk` + `kill` instead, and never `pgrep -f` in a wait loop.
- Server started by one uid (adb shell) cannot write into request dirs created by another (root app): `anima.sh` now chmods them 777; `animad` dies on "cannot write".
- Custom-op lessons from LnMod (details in HANDOFF): add `dcfetch` of the next row in HVX loops (4x); qf32xqf32 multiply is wrong on V69; AUTOSPLIT output of a custom op can break when a Conv consumes it; compare custom-op accuracy against the STOCK op on the same quantized input, not against fp32; a Flat custom output costs the next Conv ~7M cycles, so a fused op must beat stock by more than that.

## Own HMX "mega-kernel" probe (hexagon/mega/, session 4)
- A custom FastRPC skel (IDL via SDK `qaic`, no QNN) loads from /data/local/tmp as unsigned PD from the `shell` user:
  `remote_session_control(DSPRPC_CONTROL_UNSIGNED_MODULE,{CDSP_DOMAIN_ID,1})`, then `mega_open(mega_URI CDSP_DOMAIN)`,
  with ADSP_LIBRARY_PATH=. Link the ARM side against the phone's /vendor/lib64/libcdsprpc.so (SDK android lib is 32-bit).
- Skel build needs: `-G0 -fPIC`, `-nodefaultlibs` + static `libgcc.a` (no libgcc.so on DSP), and stubs for
  `__register_frame_info_bases/__deregister_frame_info_bases`; error 0x80000406 "RX VA ... outside ELF segment" = unresolved symbol.
- `qurt_hmx_lock2` is NOT resolvable in the unsigned PD (loader error). `HAP_compute_res_attr_set_hmx_param(&attr,1)` +
  `HAP_compute_res_hmx_lock(ctx)` works (rc 0), VTCM acquire works, qhl_hmx HVX transfer routines run.
- RESOLVED (later same session): the hang/SSR was HMX being powered down. `HAP_power_set(h,{type=HAP_power_set_HMX,hmx.power_up=TRUE})` BEFORE `HAP_compute_res_hmx_lock` fixes it; both the SDK fp16 `.hf` GEMM and an int8 `ub/b` tile then run on v69. (The notes in the next bullet describe the failure without that call.)
- ANY HMX compute instruction I tried hangs the call and then crashes CDSP (SSR, ~25 s): the SDK's fp16 `.hf` GEMM (qhl_hmx,
  v73+ only) and an int8 `activation.ub/weight.b` tile with guessed ctrl words. After the crash every FastRPC call hangs (D state)
  until the DSP is restarted: `su -c 'echo stop > /sys/class/remoteproc/remoteproc1/state; sleep 3; echo start > ...'`.
  Stale animad/chainrun must be restarted afterwards (`anima.sh stop; load`).
- Unknown: v69 HMX tile layout / ctrl words (undocumented in the SDK), and whether HMX also needs a clock vote that QNN does.
- Measured HMX fp16 on v69 (1 thread, qhl_hmx tile 32xKx32, data in VTCM): 17 pcycles per 32x32x32 block, ~5.3 TFLOPS at K=512..1024
  (276 cyc @K=512, 511 @K=1024; 0.2 us/call). That equals QNN's best matmul (~5 TFLOPS) => the matmul itself is already near HMX peak;
  a custom kernel can only win by removing the glue (~49% of block cycles), not by faster matmul. Max realistic: step 2.5 s -> ~1.4-1.7 s.

## HMX fp16 GEMM engine on v69 (hexagon/mega/gemm.c, stream.c) — verified
- Layout ("crouton"): 32x32 fp16 tile = 2 KB, halfword index of (r,c) = (r/2)*64 + (c%32)*2 + (r&1) (row pairs interleaved).
  The SAME tile format is used for activation (rows=M, cols=K), weight (rows=K, cols=N) and HMX output (rows=M, cols=N) => a GEMM
  output tile is directly the activation tile of the next GEMM (no permutes). Activation/out: tiles [row slab][col block];
  weights: [n block][k block] (K tiles contiguous per n block). Per call: act ctrl=(kb<<11)|0x7C, wgt ctrl=(kb<<11)-1.
- Ctrl length field caps at 64 KB => at most 32 k-blocks (K=1024) per call. For larger K chain `hmx_acc` calls (activation.hf=mxmem(..):deep;
  weight.hf=mxmem(..)) and finish with `hmx_store` (mxmem(out):after.hf=acc); accumulator survives between calls (hmxacc.S). Verified to 0.004 abs err.
- Speed: ~6.2 TFLOPS with operands in VTCM; ~6.0 TFLOPS with weights streamed DDR->VTCM by ONE helper pthread (HVX copy+l2fetch ~13 GB/s)
  when M>=512 rows per weight pass (act slabs resident in VTCM: 512x2048 = 2 MB). M=256 is copy-bound (3.3 TFLOPS). pthreads work in the unsigned PD.
- Remaining for a full block: attention (K^T needs a transpose into weight-tile layout; V and softmax(S) fit as-is), LN/RMS/RoPE/GELU on
  crouton data (elementwise ops are layout-agnostic; row reductions use even/odd lane halves), fp16 crouton weights offline (~4 GB), glue on HVX threads overlapped with HMX.

## Mega-kernel building blocks (hexagon/mega/, all verified on DSP vs scalar refs)
- Layouts: h16 tile (fp16, 2 KB) as above; x32 tile (fp32, 4 KB) = float (r,c) at r*32+c%32, so widening an h16 vector (Q6_Wqf32_vmpy_VhfVhf(v, 0x3c00 splat))
  gives lo = row 2p, hi = row 2p+1 (32 lanes each) and Q6_Vhf_equals_Wqf32(vcombine(hi,lo)) packs back. v69 has NO hf<->sf convert (vcvt.sf.hf fails to select).
- Kernels (kern.c): k_lnmod (LN+adaLN, x32->h16, 95us/2 slabs), k_resadd (x32 += gate*t16), k_rmsrope (per-head RMS + split-half RoPE, 71us/slab), k_gelu/k_softmax (poly exp, slow:
  ~340ns/vec), k_gelu2/k_softmax2 (TABLE versions via vgather: 50-60 ns/vec, GELU = max(x,0) - |x|*Phi(-|x|) table; softmax exp(-|x|) table).
- vgather: Q6_vgather_ARMVh(dst_vtcm, table_base, 65535, byte_offsets) ; offsets are 16-bit => tables of <=32768 fp16 entries (index = 15 magnitude bits).
- Row sum of softmax numerators can come from HMX for free: append an all-ones 5th n-block to V (P·1), then k_rowscale_sum multiplies O by 1/sum.
- Bug classes hit: passing qf32 where sf expected (Q6_Vqf32_vadd_VsfVsf(MULSF..)), macro args without parens in test code, vgather test branch shadowed by `which>=5`.
- Pool (pool.c): spin-wait pthread workers (4 ok + main); GEMM driver gemm_run(): 5.3-5.9 TFLOPS on 1536x2048x2048/8192, 4.8 on K=8192 (act row-group 128 rows).
- Block estimate with this design: matmuls ~39 ms + attention ~11 + glue ~10 => ~60 ms/block (QNN: 89) => ~1.7 s/step, i.e. ~25-30% end-to-end, NOT 2x.

## Full block on HMX (hexagon/mega/blk.c, export_block.py, cmp_block.py) — first complete run, block 13
- Result: rel err vs torch fp32 block delta 6.9e-4 (fp16-level), no NaN. Time per block 97 ms of stages (+~20 ms fastrpc cache maintenance of the 207 MB blob per call), QNN is 89 ms.
- Stage ms: LN 2 x3, QKV gemms 7.5, rope(q,k) 6, self-attn 22.5, o 2.5, cross q/k/v 3.3, rope 3.5, cross-attn 8, co 2.5, m1 8.8, gelu 5.8, m2 12.7, resadd 2.5 x3.
- HARD LIMITS found: (1) at most 3 pool workers + main: with 4 workers the main thread's HVX use deadlocks (4 HVX contexts). (2) pthread default stack on DSP is 16 KB;
  pthread_attr_setstacksize(256K) works, pthread_attr_setstack is unresolved (module fails to load, 0x80000406). (3) A big static .bss array also breaks loading.
  (4) DSP clocks are NOT pinned by default: after a CDSP restart everything ran 2.5x slower (2.1 vs 5.3 TFLOPS) until powermax.c (DCVS v3: dcvs off, core+bus VCORNER_MAX, sleep_disable) was added.
  Always call power_max() at start. (5) Scalar stores into VTCM are extremely slow: transpose in a stack buffer, then vector-copy to VTCM (attention setup 100 ms -> ~15).
  (6) Softmax split across workers must handle n=3 (16 pair-rows / 3) — a "16 % n" shortcut silently ran everything on worker 0.
- Remaining hot spots: attention setup (K^T scalar transpose, ~16M thread-cycles), rowscale_sum on main thread, rope, resadd/LN without prefetch, per-call blob cache maintenance.
- Estimated best case after tuning ~65-70 ms/block (vs 89), i.e. step 2.5 s -> ~2.0 s. Integration needs persistent weight mapping (fastrpc mmap) to avoid per-call cache flush, 4 GB fp16 weights.
- fastrpc_mmap: a DSP process maps only ~13 distinct 126 MB rpcmem buffers in total (map+unmap of 13 DIFFERENT buffers poisons the process; re-mapping the SAME buffer is fine); FASTRPC_MAP_FD_EXTENDED fails (rc 14); mapping before the first mega_open fails (rc 114). Run several processes.
  Passing rpcmem buffers as call args instead leaves the mappings cached and dies at the 12th block (r=0xe). Clean CPU caches with DMA_BUF_IOCTL_SYNC (END|RW) after CPU-writing weights, then HAP_mmap_get(fd) on the DSP.
- Spinning pool workers of an idle process steal DSP threads from the active one: create/join them per call. Per-call VTCM acquire/release and thread creation cost ~nothing measurable.
- Fusing RMS+RoPE into the q/k GEMM flush made things slower (GEMM+epilogue is worker-bound); residual add and GELU epilogues in the flush are free.
- hf (qf16) reciprocal Newton for the softmax normalisation doubled the error (6.9e-4 -> 1.4e-3) for no speed gain; keep fp32.
- Passing the activation blob as a FastRPC arg every call vs a persistent mapped buffer: ~2-5 ms/block difference; a setup/putx/getx copy API (activations in DSP heap) was WORSE (+45 ms per run in copies). Use the mapped rpcmem blob + manual cache sync.
- Fixed per-call cost was ~5.7 ms (VTCM acquire/release, pool create/join, HMX/DCVS votes): hold them across a process's consecutive blocks (processes run strictly one after another, so this is safe).
- GEMM epilogues (residual) are DDR-bandwidth bound: o/co-proj 3.2 ms vs 2.5 plain; split-K for K=8192 saved only ~0.7 ms (m2 is limited by the epilogue, not weight traffic).
- HAP_power_set_HMX_v2 (set_clock, max corner) fails (rc -3) on SM8475/V69: no separate HMX clock to vote for. GEMM stays at 5.26 TFLOPS.
- Low-res first steps: rebuild the low-res input from (x0 estimate, noise), not by pooling x_t (pooling a noisy mixture breaks the noise variance); keep the original noise for the whole request.
