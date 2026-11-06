#!/bin/bash
# copies the native binaries the APK bundles into app/jnilibs/lib/arm64-v8a (executables are named lib*.so: an app may only exec from its native lib dir)
set -e; cd "$(dirname "$0")/.."; J=app/jnilibs/lib/arm64-v8a; Q=~/anima-npu/sdk/qairt/2.28.0.241029/lib; mkdir -p $J
cp phone/animad $J/libanimad.so; cp hexagon/mega/out/megarun $J/libmegarun.so; cp hexagon/mega/out/libmega_skel.so $J/
cp chainrun/vaerun $J/libvaerun.so; cp chainrun/terun $J/libterun.so; cp android/build/bin/sd-cli.stripped $J/libsdcli.so   # llvm-strip of android/build/bin/sd-cli
cp $Q/aarch64-android/{libQnnHtp.so,libQnnSystem.so,libQnnHtpV69Stub.so} $J/; cp $Q/hexagon-v69/unsigned/libQnnHtpV69Skel.so $J/; cp $Q/aarch64-android/libQnnHtpV79Stub.so $J/; cp $Q/hexagon-v79/unsigned/libQnnHtpV79Skel.so $J/   # v79 = Snapdragon 8 Elite
ls -la $J
