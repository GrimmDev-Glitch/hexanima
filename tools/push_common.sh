#!/bin/bash
# Installs the shared files ("common" pack) for a Hexagon generation that has no download on the model server, straight into the app over adb
# (the APK must be debuggable for run-as). The QNN contexts come from tools/build_ctx.sh (te/out_ARCH, vae/out_ARCH); the arch-independent files
# (token embeddings, preview decoder, text-encoder header) are taken from the V69 pack. The app then sees common_ARCH as installed.
# usage: push_common.sh ARCH   (e.g. v73)
set -e; cd "$(dirname "$0")/.."; A=${1:?arch, e.g. v73}; PKG=com.hexanima.npu
BASE=https://huggingface.co/ayanoby/anima-npu-packs/resolve/main/common; C=~/anima-npu/common_dl; mkdir -p $C
for f in te_embed.f16 taew2_1.safetensors llm_hdr.bin manifest.json; do [ -s $C/$f ] || curl -sL -o $C/$f $BASE/$f; done
LLM=$(python3 -c "import json; m = json.load(open('$C/manifest.json')); print(m['llm_stub']['name'], m['llm_stub']['size'])")
put() { adb push "$1" /data/local/tmp/hx_push >/dev/null && adb shell "cat /data/local/tmp/hx_push | run-as $PKG sh -c 'cat > files/$2'" && adb shell rm /data/local/tmp/hx_push; echo "  $2"; }
adb shell "run-as $PKG mkdir -p files/anima/common_$A files/sd"
for f in te0 te1 te2 te3; do put te/out_$A/$f.bin anima/$f.bin; done
put vae/out_$A/vae.bin anima/vae.bin
put $C/te_embed.f16 anima/te_embed.f16
put $C/taew2_1.safetensors sd/taew2_1.safetensors
put $C/llm_hdr.bin anima/common_$A/llm_hdr.bin
set -- $LLM   # sd.cpp only reads the header of the text-encoder file: a sparse stub of the full size, like Models.stub()
adb shell "run-as $PKG sh -c 'cp files/anima/common_$A/llm_hdr.bin files/sd/$1 && truncate -s $2 files/sd/$1 && touch files/anima/common_$A/.installed'"
adb shell "run-as $PKG ls -la files/anima files/sd files/anima/common_$A"
