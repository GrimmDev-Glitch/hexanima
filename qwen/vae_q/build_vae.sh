#!/bin/bash
# usage: build_vae.sh NAME [ARCH=v69|v73]; (NAME.onnx in cwd) -> out_ARCH/NAME.bin
set -e
cd "$(dirname "$0")"; source ~/anima-npu/work/env.sh
source "$QNN_SDK_ROOT/bin/envsetup.sh" >/dev/null
QNN_PYTHON=${QNN_PYTHON:-../../moss/.venv-qairt/bin/python}
[ -x "$QNN_PYTHON" ] || { echo "QAIRT Python missing: $QNN_PYTHON" >&2; exit 2; }
PYTHON_LIB_DIR=$("$QNN_PYTHON" -c 'import sysconfig; print(sysconfig.get_config_var("LIBDIR"))')
QAIRT_RUNTIME_DIR=${QAIRT_RUNTIME_DIR:-$HOME/Hexanima-dev/qairt-runtime}
ANDROID_CXX_LIB=${ANDROID_CXX_LIB:-$HOME/anima-npu/sdk/android/build-tools/34.0.0/lib64}
HEXAGON_HOST_LIB=${HEXAGON_HOST_LIB:-$HOME/anima-npu/sdk/Hexagon_SDK/6.7.0.0/tools/HEXAGON_Tools/21.0.04/Tools/lib}
export LD_LIBRARY_PATH="$PYTHON_LIB_DIR:$QAIRT_RUNTIME_DIR:$ANDROID_CXX_LIB:$HEXAGON_HOST_LIB:$LD_LIBRARY_PATH"
N=${1:-vae_t32}
ARCH=${2:-v69}; SOC=36
case "$ARCH" in v69) ;; v73) SOC=43 ;; *) echo "unsupported ARCH: $ARCH (use v69 or v73)" >&2; exit 2;; esac
FLOAT_BITWIDTH=${FLOAT_BITWIDTH:-16}
FP16_RELAXED_PRECISION=${FP16_RELAXED_PRECISION:-1}
if [ ! -s "$N.onnx" ]; then echo "missing input: $N.onnx" >&2; exit 2; fi
OUT=out_$ARCH; mkdir -p "$OUT"
echo "{\"graphs\":[{\"vtcm_mb\":8,\"graph_names\":[\"$N\"],\"O\":3.0,\"fp16_relaxed_precision\":$FP16_RELAXED_PRECISION}],\"devices\":[{\"dsp_arch\":\"$ARCH\",\"soc_model\":$SOC,\"cores\":[{\"perf_profile\":\"burst\",\"rpc_control_latency\":100}]}]}" > "$OUT/htp_config_$N.json"
echo "{\"backend_extensions\":{\"shared_library_path\":\"libQnnHtpNetRunExtensions.so\",\"config_file_path\":\"$PWD/$OUT/htp_config_$N.json\"}}" > "$OUT/htp_backend_$N.json"
"$QNN_PYTHON" "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter" --onnx_no_simplification --input_network "$N.onnx" --output_path "$N.dlc" --float_bitwidth "$FLOAT_BITWIDTH"
rm -f "$OUT/$N.bin"
qnn-context-binary-generator --dlc_path $N.dlc --model $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnModelDlc.so --backend $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so \
  --output_dir "$OUT" --binary_file $N --config_file "$OUT/htp_backend_$N.json" --log_level warn 2>&1 | grep -E "ERROR|rror" | head -8 || true
ls -la "$OUT/$N.bin"
