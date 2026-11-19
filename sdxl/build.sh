#!/bin/bash
# SDXL pieces (ONNX from export_parts.py) -> QNN HTP context binaries for one Hexagon arch. Same toolchain as pose/build.sh (QAIRT 2.28).
#   ./build.sh part I [ARCH=v73]   UNet part I, W8A16 (8-bit per-channel weights, 16-bit activations, fp32 I/O), calibrated with calib_pI.txt
#   ./build.sh fp16 NAME [ARCH]    te_l | te_g | vae as fp16 contexts
# Work folder: $SDXL_WORK (default ~/Hexanima-dev/sdxl/work) holds the ONNX, calibration lists and outputs (out_<arch>/).
set -e
WHAT=$1; ARG=$2; ARCH=${3:-v73}   # saved first: sourcing envsetup.sh replaces the positional arguments
SRCDIR=$(cd "$(dirname "$0")" && pwd)
W=${SDXL_WORK:-$HOME/Hexanima-dev/sdxl/work}; cd "$W"
source ~/anima-npu/work/env.sh
source "$QNN_SDK_ROOT/bin/envsetup.sh" >/dev/null
QNN_PYTHON=${QNN_PYTHON:-$HOME/Hexanima-dev/.venv-qairt/bin/python}
PYLIB=$("$QNN_PYTHON" -c 'import sysconfig; print(sysconfig.get_config_var("LIBDIR"))')
export LD_LIBRARY_PATH="$PYLIB:$HOME/Hexanima-dev/qairt-runtime:$HOME/anima-npu/sdk/android/build-tools/34.0.0/lib64:$HOME/anima-npu/sdk/Hexagon_SDK/6.7.0.0/tools/HEXAGON_Tools/21.0.04/Tools/lib:$LD_LIBRARY_PATH"
case "$ARCH" in v69) SOC=36 ;; v73) SOC=43 ;; v75) SOC=57 ;; v79) SOC=69 ;; *) echo "unsupported ARCH $ARCH" >&2; exit 2 ;; esac
BIN=$QNN_SDK_ROOT/bin/x86_64-linux-clang
OUT=out_$ARCH; mkdir -p "$OUT"
if [ "$WHAT" = part ]; then
  N=part$ARG; GOPT="\"O\":3.0"
  # the graph name comes from the converter's output file name, so the float DLC is called $N and the quantized one replaces it
  "$QNN_PYTHON" "$BIN/qairt-converter" --input_network $N.onnx --output_path "$OUT/$N.dlc" 2>&1 | grep -vE "WARNING - (Symbolic|Onnx model simpl|Simplified)" | grep -iE "error|warn" | head -20
  "$QNN_PYTHON" "$BIN/qairt-quantizer" --input_dlc "$OUT/$N.dlc" --output_dlc "$OUT/${N}_q.dlc" --input_list calib_p$ARG.txt \
    --weights_bitwidth 8 --act_bitwidth 16 --bias_bitwidth 32 --use_per_channel_quantization --preserve_io_datatype ${QUANT_EXTRA} 2>&1 | grep -iE "error|warn" | head -20
  mv "$OUT/${N}_q.dlc" "$OUT/$N.dlc"
elif [ "$WHAT" = fp16 ]; then
  N=$ARG; GOPT="\"O\":3.0,\"fp16_relaxed_precision\":1"
  "$QNN_PYTHON" "$BIN/qairt-converter" --input_network $N.onnx --output_path "$OUT/$N.dlc" --float_bitwidth 16 ${CONV_EXTRA} 2>&1 | grep -vE "WARNING - (Symbolic|Onnx model simpl|Simplified)" | grep -iE "error|warn" | head -20
else echo "usage: build.sh part I | fp16 NAME [ARCH]" >&2; exit 2; fi
echo "{\"graphs\":[{\"vtcm_mb\":8,\"graph_names\":[\"$N\"],$GOPT}],\"devices\":[{\"dsp_arch\":\"$ARCH\",\"soc_model\":$SOC,\"cores\":[{\"perf_profile\":\"burst\",\"rpc_control_latency\":100}]}]}" > "$OUT/htp_config_$N.json"
echo "{\"backend_extensions\":{\"shared_library_path\":\"libQnnHtpNetRunExtensions.so\",\"config_file_path\":\"$PWD/$OUT/htp_config_$N.json\"}}" > "$OUT/htp_backend_$N.json"
qnn-context-binary-generator --dlc_path "$OUT/$N.dlc" --model "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnModelDlc.so" --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" \
  --output_dir "$OUT" --binary_file "$N" --config_file "$OUT/htp_backend_$N.json" --log_level warn
ls -lh "$OUT/$N.bin"
