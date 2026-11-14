#!/bin/bash
# build mossrun for Android arm64 (needs ../hexagon/mega/out/{mega_stub.c,mega.h} from the skel build)
cd "$(dirname "$0")"; M=$PWD/../hexagon/mega
source ~/anima-npu/work/env.sh
docker run --rm --platform linux/amd64 -v "$PWD/..:/work" -v "$QNN_SDK_ROOT/include/QNN:/qnninc:ro" ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash -c '
S=/opt/hexagon/6.7.0.0; NDK=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin; W=/work
INC="-w -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -I$S/ipc/fastrpc/rpcmem/inc -I$W/hexagon/mega/out -I$W/hexagon/mega"
mkdir -p $W/mossrun/out
$NDK/aarch64-linux-android31-clang -O2 $INC -c $W/hexagon/mega/out/mega_stub.c -o $W/mossrun/out/mega_stub.o
$NDK/aarch64-linux-android31-clang++ -std=c++17 -O3 -march=armv8.2-a+fp16+dotprod $INC $W/mossrun/mossrun.cpp $W/mossrun/out/mega_stub.o -L$W/hexagon/mega/syslib -lcdsprpc -llog -static-libstdc++ -o $W/mossrun/out/mossrun 2>&1 | grep -E "error" -A3 | head -30
$NDK/aarch64-linux-android31-clang++ -std=c++17 -O3 -march=armv8.2-a+fp16+dotprod -I/qnninc $W/mossrun/vaetile.cpp -llog -static-libstdc++ -o $W/mossrun/out/vaetile 2>&1 | grep -E "error" -A3 | head -30
$NDK/aarch64-linux-android31-clang++ -std=c++17 -O3 -ffast-math -march=armv8.2-a+fp16+dotprod -static-libstdc++ $W/mossrun/lorafold.cpp -o $W/mossrun/out/lorafold 2>&1 | grep -E "error" -A3 | head -30
$NDK/aarch64-linux-android31-clang++ -std=c++17 -O2 $INC $W/mossrun/maprobe.cpp $W/mossrun/out/mega_stub.o -L$W/hexagon/mega/syslib -lcdsprpc -llog -static-libstdc++ -o $W/mossrun/out/maprobe 2>&1 | grep -E "error" -A3 | head'
ls -la out/mossrun out/vaetile
