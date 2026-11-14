#!/bin/bash
# Export and compile the 512 px Qwen VAE decoder + encoder contexts for SM8550.
# Requires ref/vae_dir (Qwen-Image-2.1 VAE weights), Diffusers, QAIRT 2.28,
# and a Python 3.10 QAIRT environment with numpy, onnx, PyYAML and packaging.
set -euo pipefail
cd "$(dirname "$0")"
source ~/anima-npu/work/env.sh
PYTHON=${PYTHON:-../../moss/.venv/bin/python}
QNN_PYTHON=${QNN_PYTHON:-../../moss/.venv-qairt/bin/python}
# Make the path absolute without resolving the interpreter symlink: resolving
# it to the base Python drops pyvenv.cfg discovery and hides venv packages.
QNN_PYTHON="$(cd "$(dirname "$QNN_PYTHON")" && pwd)/$(basename "$QNN_PYTHON")"
export QNN_PYTHON
"$QNN_PYTHON" -c 'import numpy, onnx, yaml, packaging' || {
  echo "QAIRT Python needs numpy, onnx, PyYAML and packaging: $QNN_PYTHON" >&2; exit 2;
}
[ -x "$PYTHON" ] || { echo "Diffusers Python not found: $PYTHON" >&2; exit 2; }
[ -s ref/vae_dir/config.json ] && [ -s ref/vae_dir/diffusion_pytorch_model.safetensors ] || {
  echo "missing Qwen VAE weights under ref/vae_dir" >&2; exit 2;
}

BUILD_TMP=${BUILD_TMP:-/tmp/hexanima-qwen-vae-v73}
mkdir -p "$BUILD_TMP"
cp build_vae.sh "$BUILD_TMP/build_vae.sh"
build_context() {
  local name=$1
  cp "$name.onnx" "$BUILD_TMP/$name.onnx"
  (cd "$BUILD_TMP" && QNN_PYTHON="$QNN_PYTHON" bash build_vae.sh "$name" v73)
  mkdir -p out_v73
  cp "$BUILD_TMP/out_v73/$name.bin" "out_v73/$name.bin"
}

# Fixed RMS scaling matched synthetic latents but collapsed real transformer
# outputs on SM8550. Adaptive scaling preserves small activations (FP16
# underflow) while reducing large RMS inputs before HMX squaring.
RMS_INPUT_SCALE=adaptive ACTIVATION_SCALE=256 "$PYTHON" ../export_vae_qwen.py vae_t32.onnx 32
build_context vae_t32
"$PYTHON" ../export_vae_enc.py enc_t32.onnx 32
build_context enc_t32
