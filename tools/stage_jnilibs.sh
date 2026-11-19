#!/bin/bash
# copies the native binaries the APK bundles into app/jnilibs/lib/arm64-v8a (executables are named lib*.so: an app may only exec from its native lib dir)
set -e; cd "$(dirname "$0")/.."; J=app/jnilibs/lib/arm64-v8a; Q=~/anima-npu/sdk/qairt/2.28.0.241029/lib; mkdir -p $J
cp phone/animad $J/libanimad.so; cp hexagon/mega/out/megarun $J/libmegarun.so; cp hexagon/mega/out/libmega_skel.so $J/
cp phone/animaconv $J/libanimaconv.so   # Anima checkpoint -> app model pack, on the phone (phone/animaconv.cpp)
cp hexagon/mega/out/libmega_skel_v73.so $J/   # Snapdragon 8 Gen 2 build (hexagon/mega/build.sh with ARCH=v73); anima.sh puts it in front of the V69 one
cp chainrun/vaerun $J/libvaerun.so; cp chainrun/terun $J/libterun.so; cp android/build/bin/sd-cli.stripped $J/libsdcli.so   # llvm-strip of android/build/bin/sd-cli
cp $Q/aarch64-android/{libQnnHtp.so,libQnnSystem.so,libQnnHtpV69Stub.so} $J/; cp $Q/hexagon-v69/unsigned/libQnnHtpV69Skel.so $J/; cp $Q/aarch64-android/libQnnHtpV73Stub.so $J/; cp $Q/hexagon-v73/unsigned/libQnnHtpV73Skel.so $J/; cp $Q/aarch64-android/libQnnHtpV79Stub.so $J/; cp $Q/hexagon-v79/unsigned/libQnnHtpV79Skel.so $J/   # v79 = Snapdragon 8 Elite
# SM8650 / Hexagon V75 reportedly runs the V73 kernels; provide the V75 QNN names as aliases.
cp $J/libQnnHtpV73Stub.so $J/libQnnHtpV75Stub.so; cp $J/libQnnHtpV73Skel.so $J/libQnnHtpV75Skel.so
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
# Mage-Flow: runner (mage/magerun/build.sh) and V73 DSP kernel (mage/hexagon/v73_patch.py, then ARCH=v73 mage/hexagon/mega/build.sh), both from upstream's MIT sources.
# Built in ${MAGE_BUILD:-~/Hexanima-dev/mage-build} (a copy of mage/hexagon and mage/magerun with LF line endings). Upstream's v69 DSP binary is never staged for V73.
MB=${MAGE_BUILD:-$HOME/Hexanima-dev/mage-build}
if [ -f "$MB/magerun/out/magerun" ] && [ -f "$MB/hexagon/mega/out/libmega_skel_v73.so" ]; then
  cp "$MB/magerun/out/magerun" "$J/libmagerun_mage.so"
  cp "$MB/hexagon/mega/out/libmega_skel_v73.so" "$J/libmageskel_v73.so"
fi
# picture upscaler: Real-ESRGAN General x4v3 (BSD-3), one 256x256 -> 1024x1024 fp16 HTP context per Hexagon generation (upscale/build.sh 256 ARCH), run by libvaerun.so
for a in v69 v73 v79; do [ -f upscale/out_$a/esrgan_256.bin ] && cp upscale/out_$a/esrgan_256.bin $J/libesrgan_$a.so; done
# "Detailed" upscaler: 4x-UltraSharp (Kim2091, CC BY-NC-SA 4.0; MODEL=ultrasharp upscale/build.sh 256 ARCH), same runner; offered only where its context exists
for a in v69 v73 v79; do [ -f upscale/out_$a/ultrasharp_256.bin ] && cp upscale/out_$a/ultrasharp_256.bin $J/libultrasharp_$a.so; done
# face / hand detectors for Corrections (face_yolov8n / hand_yolov8n, Bingsu/adetailer, Apache-2.0; detect/build.sh face|hand ARCH)
for a in v69 v73 v79; do for k in face hand; do [ -f detect/out_$a/${k}_640.bin ] && cp detect/out_$a/${k}_640.bin $J/lib${k}det_$a.so; done; done
# LTX-Video (video, experimental): the T5 text runner, the NPU runner and the QAIRT 2.50 runtime its contexts need, renamed so they do not clash with the 2.28 runtime above
# (Ltx.prepare links files/ltx/lib/libQnnHtp.so etc. to these). Built in ~/Hexanima-dev/ltx (ltx/t5enc/build.sh, ltx/ltxrun/build.sh; runtime from com.qualcomm.qti:qnn-runtime:2.50.0)
LTX=${LTX_DIR:-$HOME/Hexanima-dev/ltx}; R=$LTX/qnn250/jni/arm64-v8a
if [ -f $LTX/ltxrun/ltxrun ] && [ -f $LTX/t5enc/t5enc.bin ] && [ -f $R/libQnnHtp.so ]; then
  cp $LTX/t5enc/t5enc.bin $J/libltxt5.so; cp $LTX/ltxrun/ltxrun $J/libltxrun.so
  cp $R/libQnnHtp.so $J/libqnn250htp.so; cp $R/libQnnSystem.so $J/libqnn250system.so; cp $R/libQnnHtpV73Stub.so $J/libqnn250v73stub.so; cp $R/libQnnHtpV73Skel.so $J/libqnn250v73skel.so
fi
ls -la $J
