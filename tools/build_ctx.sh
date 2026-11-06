#!/bin/bash
# DLC -> HTP context binary for another Hexagon generation. usage: build_ctx.sh DIR(te|vae) NAME ARCH(v73|v75|v79) SOC_MODEL  -> DIR/out_ARCH/NAME.bin
# SOC_MODEL (QNN docs): SM8450=36 SM8475=42 SM8550(v73)=43 SM8650(v75)=57 SM8750(v79)=69
set -e
cd "$(dirname "$0")/../$1"; source ~/anima-npu/work/env.sh
N=$2; A=$3; S=$4; O=out_$A; mkdir -p $O
echo "{\"graphs\":[{\"vtcm_mb\":8,\"graph_names\":[\"$N\"],\"O\":3.0,\"fp16_relaxed_precision\":1}],\"devices\":[{\"dsp_arch\":\"$A\",\"soc_model\":$S,\"cores\":[{\"perf_profile\":\"burst\",\"rpc_control_latency\":100}]}]}" > $O/htp_config_$N.json
echo "{\"backend_extensions\":{\"shared_library_path\":\"libQnnHtpNetRunExtensions.so\",\"config_file_path\":\"$PWD/$O/htp_config_$N.json\"}}" > $O/htp_backend_$N.json
rm -f $O/$N.bin
qnn-context-binary-generator --dlc_path $N.dlc --model $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnModelDlc.so --backend $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so \
  --output_dir $O --binary_file $N --config_file $O/htp_backend_$N.json --log_level warn 2>&1 | grep -E "ERROR|rror" | head -5 || true
ls -la $O/$N.bin
