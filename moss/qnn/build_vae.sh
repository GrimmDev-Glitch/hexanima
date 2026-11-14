#!/bin/bash
# Convert one fixed-size MOSS FLUX.2 VAE tile and build its HTP context.
# Usage: ARCH=v73 SOC_MODEL=43 ./build_vae.sh vae_512x512
set -e
cd "$(dirname "$0")"
source ~/anima-npu/work/env.sh
source "$QNN_SDK_ROOT/bin/envsetup.sh" >/dev/null
QNN_PYTHON=${QNN_PYTHON:-../.venv-qairt/bin/python}
[ -x "$QNN_PYTHON" ] || { echo "Python 3.10 QAIRT venv missing; create moss/.venv-qairt" >&2; exit 2; }
PYTHON_LIB_DIR=$("$QNN_PYTHON" -c 'import sysconfig; print(sysconfig.get_config_var("LIBDIR"))')
export LD_LIBRARY_PATH="$PYTHON_LIB_DIR:$LD_LIBRARY_PATH"
N=${1:?usage: build_vae.sh NAME}
ARCH=${ARCH:-v69}
case "$ARCH" in
  v69) SOC_MODEL=${SOC_MODEL:-36} ;;
  v73) SOC_MODEL=${SOC_MODEL:-43} ;;
  *) echo "unsupported ARCH: $ARCH" >&2; exit 2 ;;
esac
OUT=out; [ "$ARCH" = v69 ] || OUT=out_$ARCH
mkdir -p "$OUT"
echo "{\"graphs\":[{\"vtcm_mb\":8,\"graph_names\":[\"$N\"],\"O\":3.0,\"fp16_relaxed_precision\":1}],\"devices\":[{\"dsp_arch\":\"$ARCH\",\"soc_model\":$SOC_MODEL,\"cores\":[{\"perf_profile\":\"burst\",\"rpc_control_latency\":100}]}]}" > "$OUT/htp_config_$N.json"
echo "{\"backend_extensions\":{\"shared_library_path\":\"libQnnHtpNetRunExtensions.so\",\"config_file_path\":\"$PWD/$OUT/htp_config_$N.json\"}}" > "$OUT/htp_backend_$N.json"
"$QNN_PYTHON" "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qairt-converter" --onnx_no_simplification --input_network "$N.onnx" --output_path "$OUT/$N.dlc" --float_bitwidth 16
qnn-context-binary-generator --dlc_path "$OUT/$N.dlc" --model "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnModelDlc.so" --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" \
  --output_dir "$OUT" --binary_file "$N" --config_file "$OUT/htp_backend_$N.json" --log_level warn
ls -lh "$OUT/$N.bin"
