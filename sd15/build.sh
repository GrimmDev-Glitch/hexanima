#!/bin/bash
# build sd15run for Android arm64 (NDK r29 in the SDK container). Needs QAIRT 2.28 headers and MNN 3.6.1 (Apache-2.0):
#   MNN=~/Hexanima-dev/sd15/mnn  with  mnn_3.6.1_android_armv7_armv8_cpu_opencl_vulkan/arm64-v8a/libMNN.so  and  MNN-3.6.1/include
# The binary links libMNN.so and libc++_shared.so dynamically; ship both next to it (MNN's own libc++_shared.so).
cd "$(dirname "$0")"
MNN=${MNN:-$HOME/Hexanima-dev/sd15/mnn}
docker run --rm --platform linux/amd64 -v "$PWD":/work -v "$MNN":/mnn:ro -v ~/anima-npu/sdk/qairt/2.28.0.241029:/qairt:ro ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
NDK=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin; L=/mnn/mnn_3.6.1_android_armv7_armv8_cpu_opencl_vulkan/arm64-v8a
mkdir -p /work/out
$NDK/aarch64-linux-android29-clang++ -std=c++17 -O3 -march=armv8.2-a+fp16 -I/qairt/include/QNN -I/mnn/MNN-3.6.1/include /work/sd15run.cpp -o /work/out/sd15run \
  -L$L -lMNN -ldl -llog -Wl,-rpath,\$ORIGIN 2>&1 | grep -E "error" -A3 | head -30'
ls -la out/sd15run
