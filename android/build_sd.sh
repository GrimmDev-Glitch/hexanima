#!/bin/bash
# build sd.cpp (CPU only, with the SD_NPU hook already in sd-src) for Android arm64 in the SDK container, output android/build/bin/sd-cli
cd "$(dirname "$0")"
docker run --rm --platform linux/amd64 -v $PWD:/work -v ${SD_SRC:-$HOME/sd-src}:/src:ro ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
NDK=/opt/android-ndk-r29
cmake -S /src -B /work/build -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DSD_BUILD_EXAMPLES=ON -DSD_WEBP=OFF -DSD_WEBM=OFF -DCMAKE_C_FLAGS="-O3" -DCMAKE_CXX_FLAGS="-O3" 2>&1 | tail -15
cmake --build /work/build -j8 --target sd-cli 2>&1 | tail -25'
