#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker run --rm --platform linux/amd64 -v "$ROOT:/work" \
  ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -lc '
    NDK=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin
    mkdir -p /work/krea/out
    "$NDK/aarch64-linux-android31-clang++" -O3 -std=c++17 -march=armv8.2-a+fp16+dotprod \
      -fPIE -pie /work/krea/krea_vae_cpu.cpp -o /work/krea/out/krea_vae_cpu
  '
ls -lh "$ROOT/krea/out/krea_vae_cpu"
