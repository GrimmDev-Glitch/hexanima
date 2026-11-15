#!/bin/bash
# build ltxrun for Android arm64 in the SDK container (NDK clang, QNN headers from QAIRT 2.28; the API is stable, the runtime is 2.50)
cd "$(dirname "$0")"
docker run --rm --platform linux/amd64 -v "$PWD:/work" -v "$HOME/anima-npu/sdk/qairt/2.28.0.241029:/qairt:ro" ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
NDK=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin
$NDK/aarch64-linux-android29-clang++ -std=c++17 -O2 -I/qairt/include/QNN /work/ltxrun.cpp -o /work/ltxrun -ldl -llog -static-libstdc++ 2>&1 | grep -E "error" -A3 | head -30'
ls -la ltxrun
