#!/bin/bash
# usage: bisect.sh STAGE  (run from qwen/)
st=$1; source ~/anima-npu/work/env.sh
STAGE=$st ../moss/.venv/bin/python export_vae_qwen.py vae_q/vae_s$st.onnx 8 2>&1 | grep -E "^out|Error" | cut -c1-100
cd vae_q; qairt-converter --onnx_no_simplification --input_network vae_s$st.onnx --output_path vae_s$st.dlc --float_bitwidth 16 2>&1 | grep -E "CONVERSION_SUCC|rror" | head -2 | cut -c1-140
echo "{\"graphs\":[{\"vtcm_mb\":8,\"graph_names\":[\"vae_s$st\"],\"O\":3.0,\"fp16_relaxed_precision\":1}],\"devices\":[{\"dsp_arch\":\"v69\",\"soc_model\":36,\"cores\":[{\"perf_profile\":\"burst\",\"rpc_control_latency\":100}]}]}" > htp_config_s$st.json
echo "{\"backend_extensions\":{\"shared_library_path\":\"libQnnHtpNetRunExtensions.so\",\"config_file_path\":\"$PWD/htp_config_s$st.json\"}}" > hb_s$st.json
qnn-context-binary-generator --dlc_path vae_s$st.dlc --model $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnModelDlc.so --backend $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so --output_dir out --binary_file vae_s$st --config_file hb_s$st.json --log_level error 2>&1 | tail -2 | cut -c1-150
ls out | grep "vae_s$st" || echo "stage $st: NO CONTEXT"
