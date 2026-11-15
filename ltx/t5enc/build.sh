#!/bin/bash
# build t5enc for Android arm64 (CPU, ggml from stable-diffusion.cpp) in the SDK container; output build/t5enc
cd "$(dirname "$0")"
docker run --rm --platform linux/amd64 -v $PWD:/work -v ${SD_SRC:-$HOME/sd-src}:/src:ro ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
NDK=/opt/android-ndk-r29
cmake -S /work -B /work/build -G Ninja -DSD_SRC=/src -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DSD_WEBP=OFF -DSD_WEBM=OFF -DCMAKE_C_FLAGS="-O3" -DCMAKE_CXX_FLAGS="-O3" -DCMAKE_EXE_LINKER_FLAGS="-static-libstdc++" 2>&1 | tail -5
cmake --build /work/build -j10 --target t5enc 2>&1 | grep -E "error|warning: unused|Linking|FAILED" | head -30'
ls -la build/t5enc
