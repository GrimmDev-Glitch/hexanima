#!/bin/bash
# usage: bisect_enc.sh STAGE (run from qwen/); capped memory, 90 s
st=$1; source ~/anima-npu/work/env.sh
STAGE=$st systemd-run --user --scope -q -p MemoryMax=6G -p MemorySwapMax=0 ../moss/.venv/bin/python export_vae_enc.py vae_q/enc_s$st.onnx 8 2>&1 | grep -E "^out|rror" | cut -c1-90
cd vae_q; timeout 90 systemd-run --user --scope -q -p MemoryMax=5G -p MemorySwapMax=0 qairt-converter --onnx_no_simplification --input_network enc_s$st.onnx --output_path enc_s$st.dlc --float_bitwidth 16 2>&1 | grep -E "CONVERSION_SUCC|rror" | head -2 | cut -c1-140; ls enc_s$st.dlc 2>/dev/null || echo "stage $st: NO DLC"
