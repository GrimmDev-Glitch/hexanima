#!/bin/bash
# build stable-diffusion.cpp's sd-cli for Android arm64 with the ggml-hexagon backend (HMX matmul / flash attention on the NPU) for Wan 2.2 TI2V-5B
# in the SDK container (NDK r29 + Hexagon SDK 6.7). SD_SRC must be a tree with ggml/ (e.g. ~/sd-src-build). Output: $OUT/bin/sd-cli and $OUT/lib*/libggml-htp-v73.so
SRC=${SD_SRC:-$HOME/sd-src-build}; OUT=${OUT:-$HOME/Hexanima-dev/wan/build}; mkdir -p "$OUT"
docker run --rm --platform linux/amd64 -v "$OUT:/out" -v "$SRC:/src:ro" ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
NDK=/opt/android-ndk-r29; HEX=/opt/hexagon/6.7.0.0
cmake -S /src -B /out -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
  -DCMAKE_BUILD_TYPE=Release -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DSD_BUILD_EXAMPLES=ON -DSD_WEBP=OFF -DSD_WEBM=OFF \
  -DGGML_HEXAGON=ON -DHEXAGON_SDK_ROOT=$HEX -DHEXAGON_TOOLS_ROOT=$HEXAGON_TOOLS_ROOT -DPREBUILT_LIB_DIR=android_aarch64 -DCMAKE_INSTALL_PREFIX=/out/install -DCMAKE_C_FLAGS="-O3" -DCMAKE_CXX_FLAGS="-O3" 2>&1 | grep -E "hexagon|Error|error|warning: " | head -20
cmake --build /out -j10 --target sd-cli 2>&1 | grep -E "error|FAILED|Linking" | head -30
find /out -name "libggml-htp-v*.so" -o -name sd-cli -type f | head'
