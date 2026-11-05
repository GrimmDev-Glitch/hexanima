#!/bin/bash
# vae.onnx -> FP16 DLC -> HTP context binary (v69) -> phone. usage: build_vae.sh NAME(onnx basename)
set -e
cd "$(dirname "$0")"; source ~/anima-npu/work/env.sh
N=${1:-vae}
echo "{\"graphs\":[{\"vtcm_mb\":8,\"graph_names\":[\"$N\"],\"O\":3.0,\"fp16_relaxed_precision\":1}],\"devices\":[{\"dsp_arch\":\"v69\",\"soc_model\":36,\"cores\":[{\"perf_profile\":\"burst\",\"rpc_control_latency\":100}]}]}" > htp_config_$N.json
echo "{\"backend_extensions\":{\"shared_library_path\":\"libQnnHtpNetRunExtensions.so\",\"config_file_path\":\"$PWD/htp_config_$N.json\"}}" > htp_backend_$N.json
qairt-converter --onnx_no_simplification --input_network $N.onnx --output_path $N.dlc --float_bitwidth 16 2>&1 | grep -iE "rror" | head -3 || true
mkdir -p out; rm -f out/$N.bin
qnn-context-binary-generator --dlc_path $N.dlc --model $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnModelDlc.so --backend $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so \
  --output_dir out --binary_file $N --config_file htp_backend_$N.json --log_level warn 2>&1 | grep -E "ERROR|rror" | head -5 || true
ls -la out/$N.bin
adb push out/$N.bin /data/local/tmp/anima/$N.bin >/dev/null
