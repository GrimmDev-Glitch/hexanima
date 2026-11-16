#!/bin/bash
# WD ViT Tagger v3 (SmilingWolf, Apache-2.0; ONNX from huggingface.co/SmilingWolf/wd-vit-tagger-v3) -> fp16 HTP context.
# Input "input" [1,448,448,3] BGR 0..255 (white-padded square) -> "output" [1,10861] tag probabilities (sigmoid), in selected_tags.csv order.
# Usage: ./build.sh v73 -> out_v73/wd_vit_448.bin   (ONNX in ~/Hexanima-dev/tagger/dl/model.onnx)
set -e
ARG2=${1:-v73}; ARG1=wd_vit   # saved first: sourcing envsetup.sh replaces the positional arguments
cd "$(dirname "$0")"
source ~/anima-npu/work/env.sh
source "$QNN_SDK_ROOT/bin/envsetup.sh" >/dev/null
QNN_PYTHON=${QNN_PYTHON:-../moss/.venv-qairt/bin/python}
[ -x "$QNN_PYTHON" ] || { echo "Python 3.10 QAIRT venv missing (moss/.venv-qairt)" >&2; exit 2; }
PYLIB=$("$QNN_PYTHON" -c 'import sysconfig; print(sysconfig.get_config_var("LIBDIR"))')   # the converter needs libc++, libc++abi and libunwind for the host (same setup as qwen/vae_q/build_vae.sh)
export LD_LIBRARY_PATH="$PYLIB:$HOME/Hexanima-dev/qairt-runtime:$HOME/anima-npu/sdk/android/build-tools/34.0.0/lib64:$HOME/anima-npu/sdk/Hexagon_SDK/6.7.0.0/tools/HEXAGON_Tools/21.0.04/Tools/lib:$LD_LIBRARY_PATH"
[ "$ARG1" = help ] && { "$QNN_PYTHON" "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter" -h; exit 0; }
T=448; N=${ARG1}_448
ARCH=$ARG2
case "$ARCH" in v69) SOC=36 ;; v73) SOC=43 ;; v75) SOC=57 ;; v79) SOC=69 ;; *) echo "unsupported ARCH $ARCH" >&2; exit 2 ;; esac
SRC=${TAG_ONNX:-$HOME/Hexanima-dev/tagger/dl/model.onnx}
OUT=out_$ARCH; mkdir -p "$OUT"
echo "{\"graphs\":[{\"vtcm_mb\":8,\"graph_names\":[\"$N\"],\"O\":3.0,\"fp16_relaxed_precision\":1}],\"devices\":[{\"dsp_arch\":\"$ARCH\",\"soc_model\":$SOC,\"cores\":[{\"perf_profile\":\"burst\",\"rpc_control_latency\":100}]}]}" > "$OUT/htp_config_$N.json"
echo "{\"backend_extensions\":{\"shared_library_path\":\"libQnnHtpNetRunExtensions.so\",\"config_file_path\":\"$PWD/$OUT/htp_config_$N.json\"}}" > "$OUT/htp_backend_$N.json"
"$QNN_PYTHON" "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter" --input_network "$SRC" --desired_input_shape input 1,$T,$T,3 \
  --output_path "$OUT/$N.dlc" --float_bitwidth 16
qnn-context-binary-generator --dlc_path "$OUT/$N.dlc" --model "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnModelDlc.so" --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" \
  --output_dir "$OUT" --binary_file "$N" --config_file "$OUT/htp_backend_$N.json" --log_level warn
ls -lh "$OUT/$N.bin"
