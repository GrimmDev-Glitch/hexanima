#!/bin/bash
# Pose control for Anima: the two fp16 HTP contexts. rtmw = RTMW whole-body pose (OpenMMLab, Apache-2.0; pose/merge_simcc.py), vaeenc = Qwen-Image / Wan 2.1 VAE encoder tile (Apache-2.0; pose/export_vae_enc.py).
# rtmw: "input" [1,3,256,192] BGR, (x - mean) / std -> "simcc" [1,133,896]. vaeenc: "image" [1,3,384,384] RGB -1..1 -> "latent" [1,16,48,48] (mean, not normalised).
# Usage: ./build.sh rtmw|vaeenc v73 -> out_v73/rtmw_256x192.bin | vae_enc_384.bin   (ONNX in ~/Hexanima-dev/pose/dl)
set -e
ARG1=${1:-rtmw}; ARG2=${2:-v73}   # saved first: sourcing envsetup.sh replaces the positional arguments
cd "$(dirname "$0")"
source ~/anima-npu/work/env.sh
source "$QNN_SDK_ROOT/bin/envsetup.sh" >/dev/null
QNN_PYTHON=${QNN_PYTHON:-../moss/.venv-qairt/bin/python}
[ -x "$QNN_PYTHON" ] || { echo "Python 3.10 QAIRT venv missing (moss/.venv-qairt)" >&2; exit 2; }
PYLIB=$("$QNN_PYTHON" -c 'import sysconfig; print(sysconfig.get_config_var("LIBDIR"))')   # the converter needs libc++, libc++abi and libunwind for the host (same setup as qwen/vae_q/build_vae.sh)
export LD_LIBRARY_PATH="$PYLIB:$HOME/Hexanima-dev/qairt-runtime:$HOME/anima-npu/sdk/android/build-tools/34.0.0/lib64:$HOME/anima-npu/sdk/Hexagon_SDK/6.7.0.0/tools/HEXAGON_Tools/21.0.04/Tools/lib:$LD_LIBRARY_PATH"
[ "$ARG1" = help ] && { "$QNN_PYTHON" "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter" -h; exit 0; }
if [ "$ARG1" = rtmw ]; then N=rtmw_256x192; SHAPE="input 1,3,256,192"; ONNX=rtmw_simcc.onnx; else N=vae_enc_384; SHAPE="image 1,3,384,384"; ONNX=vae_enc_384.onnx; fi
ARCH=$ARG2
case "$ARCH" in v69) SOC=36 ;; v73) SOC=43 ;; v75) SOC=57 ;; v79) SOC=69 ;; *) echo "unsupported ARCH $ARCH" >&2; exit 2 ;; esac
SRC=${POSE_ONNX:-$HOME/Hexanima-dev/pose/dl/$ONNX}
OUT=out_$ARCH; mkdir -p "$OUT"
echo "{\"graphs\":[{\"vtcm_mb\":8,\"graph_names\":[\"$N\"],\"O\":3.0,\"fp16_relaxed_precision\":1}],\"devices\":[{\"dsp_arch\":\"$ARCH\",\"soc_model\":$SOC,\"cores\":[{\"perf_profile\":\"burst\",\"rpc_control_latency\":100}]}]}" > "$OUT/htp_config_$N.json"
echo "{\"backend_extensions\":{\"shared_library_path\":\"libQnnHtpNetRunExtensions.so\",\"config_file_path\":\"$PWD/$OUT/htp_config_$N.json\"}}" > "$OUT/htp_backend_$N.json"
"$QNN_PYTHON" "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter" --input_network "$SRC" --desired_input_shape $SHAPE \
  --output_path "$OUT/$N.dlc" --float_bitwidth 16
qnn-context-binary-generator --dlc_path "$OUT/$N.dlc" --model "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnModelDlc.so" --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" \
  --output_dir "$OUT" --binary_file "$N" --config_file "$OUT/htp_backend_$N.json" --log_level warn
ls -lh "$OUT/$N.bin"
