#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker run --rm --platform linux/amd64 -v "$ROOT:/work" \
  ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -lc '
    NDK=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin
    S=/opt/hexagon/6.7.0.0
    "$NDK/aarch64-linux-android31-clang" -O2 -w \
      -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs \
      -I$S/ipc/fastrpc/rpcmem/inc -I/work/flux3/hexagon/mega -I/work/flux3/hexagon/mega/out \
      /work/krea/krea_blk_smoke.c /work/flux3/hexagon/mega/out/mega_stub.c \
      -L/work/flux3/hexagon/mega/syslib -lcdsprpc -o /work/krea/out/krea_blk_smoke
  '
ls -lh "$ROOT/krea/out/krea_blk_smoke"
