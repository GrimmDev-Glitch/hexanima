#!/bin/bash
# build chainrun for Android arm64 inside the SDK container (NDK clang), then push to the phone
cd "$(dirname "$0")"
docker run --rm --platform linux/amd64 -v $PWD:/work -v ~/anima-npu/sdk/qairt/2.28.0.241029:/qairt:ro ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
NDK=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin
$NDK/aarch64-linux-android29-clang++ -std=c++17 -O2 -I/qairt/include/QNN /work/chainrun.cpp -o /work/chainrun -ldl -llog 2>&1 | grep -E "error|warning: unused" | head -20
$NDK/aarch64-linux-android29-clang++ -std=c++17 -O2 -I/qairt/include/QNN /work/vaerun.cpp -o /work/vaerun -ldl -llog -static-libstdc++ 2>&1 | grep -E "error" -A3 | head -20
$NDK/aarch64-linux-android29-clang++ -std=c++17 -O2 -I/qairt/include/QNN /work/terun.cpp -o /work/terun -ldl -llog -static-libstdc++ 2>&1 | grep -E "error" -A3 | head -20'
ls -la chainrun && adb push chainrun /data/local/tmp/anima/chainrun >/dev/null && adb push vaerun /data/local/tmp/anima/vaerun >/dev/null && adb push terun /data/local/tmp/anima/terun >/dev/null && adb shell chmod +x /data/local/tmp/anima/chainrun /data/local/tmp/anima/vaerun /data/local/tmp/anima/terun
