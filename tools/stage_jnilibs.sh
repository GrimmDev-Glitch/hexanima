#!/bin/bash
# copies the native binaries the APK bundles into app/jnilibs/lib/arm64-v8a (executables are named lib*.so: an app may only exec from its native lib dir)
set -e; cd "$(dirname "$0")/.."; J=app/jnilibs/lib/arm64-v8a; Q=~/anima-npu/sdk/qairt/2.28.0.241029/lib; mkdir -p $J
cp phone/animad $J/libanimad.so; cp hexagon/mega/out/megarun $J/libmegarun.so; cp hexagon/mega/out/libmega_skel.so $J/
cp phone/animaconv $J/libanimaconv.so   # Anima checkpoint -> app model pack, on the phone (phone/animaconv.cpp)
cp hexagon/mega/out/libmega_skel_v73.so $J/   # Snapdragon 8 Gen 2 build (hexagon/mega/build.sh with ARCH=v73); anima.sh puts it in front of the V69 one
cp chainrun/vaerun $J/libvaerun.so; cp chainrun/terun $J/libterun.so; cp android/build/bin/sd-cli.stripped $J/libsdcli.so   # llvm-strip of android/build/bin/sd-cli
cp $Q/aarch64-android/{libQnnHtp.so,libQnnSystem.so,libQnnHtpV69Stub.so} $J/; cp $Q/hexagon-v69/unsigned/libQnnHtpV69Skel.so $J/; cp $Q/aarch64-android/libQnnHtpV73Stub.so $J/; cp $Q/hexagon-v73/unsigned/libQnnHtpV73Skel.so $J/; cp $Q/aarch64-android/libQnnHtpV79Stub.so $J/; cp $Q/hexagon-v79/unsigned/libQnnHtpV79Skel.so $J/   # v79 = Snapdragon 8 Elite
# MOSS-Image (optional, from the research tree): the runner and its DSP skel (same file name as Anima's, so it is shipped as libmossskel.so and copied to files/moss/dsp/libmega_skel.so by moss.sh)
MOSS=${MOSS_DIR:-moss}; [ -f $MOSS/mossrun/out/mossrun ] && cp $MOSS/mossrun/out/mossrun $J/libmossrun.so && cp $MOSS/hexagon/mega/out/libmega_skel.so $J/libmossskel.so && cp $MOSS/mossrun/out/vaetile $J/libvaetile.so && cp $MOSS/mossrun/out/lorafold $J/liblorafold.so
[ -f qwen/qwenrun/out/qwenlorafold ] && cp qwen/qwenrun/out/qwenlorafold $J/libqwenlorafold.so   # Qwen LoRA -> V73 overlays on the phone (qwen/qwenrun/qwenlorafold.cpp)
MOSS_V73_EXPECTED_SHA=20A23EAE163F6DC66A09D5224AD77173F1079199DFDF0DCF733E42999E1EE055
MOSS_V73_SRC=$MOSS/hexagon/mega/out/libmega_skel_v73.so
if [ -f "$MOSS_V73_SRC" ]; then
  MOSS_V73_ACTUAL=$(sha256sum "$MOSS_V73_SRC" | cut -d ' ' -f 1)
  if [ "$MOSS_V73_ACTUAL" = "$MOSS_V73_EXPECTED_SHA" ]; then
    cp "$MOSS_V73_SRC" "$J/libmossskel_v73.so"
  else
    echo "Skipping unvalidated MOSS V73 skeleton $MOSS_V73_ACTUAL; expected $MOSS_V73_EXPECTED_SHA"
  fi
fi
if [ ! -f "$J/libmossskel_v73.so" ] || [ "$(sha256sum "$J/libmossskel_v73.so" | cut -d ' ' -f 1)" != "$MOSS_V73_EXPECTED_SHA" ]; then
  echo "ERROR: no validated MOSS V73 skeleton staged; fix and test the V73 kernel before packaging" >&2
  exit 1
fi
[ -f "$MOSS/qnn/out_v73/vae_512x512.bin" ] && { mkdir -p app/assets/moss/vae_v73; cp "$MOSS/qnn/out_v73/vae_512x512.bin" app/assets/moss/vae_v73/vae_512x512.bin; }
# Qwen-Image-2.1 (optional, from the research tree): runner, its DSP skel (libqwenskel.so -> files/qwen/dsp/libmega_skel.so), the VAE decoder tiler and the VAE encoder tiler
QW=${QWEN_DIR:-qwen}
if [ -f "$QW/qwenrun/out/qwenrun" ]; then
  cp "$QW/qwenrun/out/qwenrun" "$J/libqwenrun.so"
  cp "$QW/qwenrun/out/vaetile" "$J/libqvaetile.so"
  cp "$QW/qwenrun/out/vaeenc" "$J/libvaeenc.so"
  if [ -f "$QW/hexagon/mega/out/libmega_skel.so" ]; then cp "$QW/hexagon/mega/out/libmega_skel.so" "$J/libqwenskel.so"; fi
fi
[ -f $QW/hexagon/mega/out/libmega_skel_v73.so ] && cp $QW/hexagon/mega/out/libmega_skel_v73.so $J/libqwenskel_v73.so
# Mage's Android host runner is shipped in the MIT-licensed upstream release; its v69 DSP binary is deliberately not staged.
MAGE=${MAGE_DIR:-mage}
if [ -f "$MAGE/native/libmagerun.so" ]; then
  cp "$MAGE/native/libmagerun.so" "$J/libmagerun_mage.so"
  cp hexagon/mega/out/libmega_skel_v73.so "$J/libmageskel_v73.so"
fi
ls -la $J
