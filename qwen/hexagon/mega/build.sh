#!/bin/bash
# docker run --rm --platform linux/amd64 -v $PWD:/work ghcr.io/snapdragon-toolchain/arm64-android:v0.8 bash /work/build.sh
set -e
V=${ARCH:-v69}; [ "$V" = v69 ] || [ "$V" = v73 ] || { echo "unsupported ARCH: $V"; exit 2; }
SK=out/libmega_skel.so; [ "$V" = v69 ] || SK=out/libmega_skel_$V.so
S=/opt/hexagon/6.7.0.0; T=$S/tools/HEXAGON_Tools/21.0.04/Tools; cd /work; mkdir -p out out/dsp_$V
$S/ipc/fastrpc/qaic/bin/qaic -mdll -I$S/incs/stddef -I$S/incs -I$S/ipc/fastrpc/incs mega.idl -o out
INC="-I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -I$S/rtos/qurt/compute$V/include/qurt -I$S/rtos/qurt/compute$V/include/posix -Iout -I$S/libs/qhl_hmx/inc/qhblas_hmx -I$S/libs/qhl_hvx/inc/internal -I$S/utils/examples"
HF="-G0 -m$V -mhvx -mhvx-length=128B -mhmx -O2 -fPIC -DUSE_OS_QURT -D_HAS_EXCEPTIONS=0 -w $INC"
for f in mega_imp.c gemm.c stream.c copybw.c powermax.c kern.c ktest.c pool.c gemm2.c gtest.c blk.c hmxscale.c out/mega_skel.c $S/libs/qhl_hmx/src/qhblas_hmx/*.c $S/libs/qhl_hmx/src/qhblas_hmx/*.S hmx8.S hmxacc.S; do $T/bin/hexagon-clang $HF -c $f -o out/dsp_$V/$(basename $f).o; done
LG=$T/target/hexagon/lib/$V/G0/pic; $T/bin/hexagon-clang -G0 -m$V -shared -fPIC -Wl,-Bsymbolic -nodefaultlibs -o $SK out/dsp_$V/*.o -Wl,--start-group $LG/libgcc.a -Wl,--end-group
A=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin; L=/work/syslib
$A/aarch64-linux-android31-clang -O2 -w -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -I$S/ipc/fastrpc/rpcmem/inc -Iout mega_test.c out/mega_stub.c -L$L -lcdsprpc -o out/mega_test; $A/aarch64-linux-android31-clang -O2 -w -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -Iout bench_main.c out/mega_stub.c -L$L -lcdsprpc -o out/bench; $A/aarch64-linux-android31-clang -O2 -w -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -Iout stream_main.c out/mega_stub.c -L$L -lcdsprpc -o out/stream; $A/aarch64-linux-android31-clang -O2 -w -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -Iout copy_main.c out/mega_stub.c -L$L -lcdsprpc -o out/copybw; $A/aarch64-linux-android31-clang -O2 -w -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -Iout ktest_main.c out/mega_stub.c -L$L -lcdsprpc -o out/ktest; $A/aarch64-linux-android31-clang -O2 -w -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -Iout gtest_main.c out/mega_stub.c -L$L -lcdsprpc -o out/gtest; $A/aarch64-linux-android31-clang -O2 -w -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -I$S/ipc/fastrpc/rpcmem/inc -Iout blk_main.c out/mega_stub.c -L$L -lcdsprpc -o out/blk
$A/aarch64-linux-android31-clang -O2 -w -I$S/incs -I$S/incs/stddef -I$S/ipc/fastrpc/incs -Iout dump_main.c out/mega_stub.c -L$L -lcdsprpc -o out/hmxdump
ls -la $SK out/mega_test
