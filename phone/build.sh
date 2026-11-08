#!/bin/bash
# build animad for Android arm64 (NDK in the SDK container) and push to the phone
cd "$(dirname "$0")"
docker run --rm --platform linux/amd64 -v $PWD:/work ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
NDK=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin
$NDK/aarch64-linux-android29-clang++ -std=c++17 -O3 -march=armv8.2-a+fp16+dotprod /work/animad.cpp -o /work/animad -llog -static-libstdc++ 2>&1 | grep -E "error" -A3 | head -30'
# animaconv (Anima checkpoint -> app model pack on the phone) shares the safetensors reader of hexagon/mega/lora_fold.h: the repo root is mounted
docker run --rm --platform linux/amd64 -v $PWD/..:/repo ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
NDK=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin
$NDK/aarch64-linux-android29-clang++ -std=c++17 -O3 -march=armv8.2-a+fp16+dotprod /repo/phone/animaconv.cpp -o /repo/phone/animaconv -static-libstdc++ 2>&1 | grep -E "error" -A3 | head -30'
ls -la animad animaconv && adb push animad /data/local/tmp/anima/animad >/dev/null && adb shell chmod +x /data/local/tmp/anima/animad
