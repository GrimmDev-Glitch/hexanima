#!/bin/bash
# build sdxlrun for Android arm64 inside the SDK container (NDK clang), like chainrun/build.sh. PUSH=1 also copies it to /data/local/tmp/sdxl.
cd "$(dirname "$0")"
docker run --rm --platform linux/amd64 -v "$PWD":/work -v ~/anima-npu/sdk/qairt/2.28.0.241029:/qairt:ro ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
NDK=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin
$NDK/aarch64-linux-android29-clang++ -std=c++17 -O3 -I/qairt/include/QNN /work/sdxlrun.cpp -o /work/sdxlrun -ldl -llog -static-libstdc++ 2>&1 | grep -E "error|warning" -A3 | head -40'
ls -la sdxlrun
[ "$PUSH" = 1 ] && adb shell mkdir -p /data/local/tmp/sdxl && adb push sdxlrun /data/local/tmp/sdxl/ >/dev/null && adb shell chmod +x /data/local/tmp/sdxl/sdxlrun
