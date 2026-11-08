#!/bin/bash
# full-frame VAE decoder contexts (HTP v69, fp16) for the preset sizes: vae_WxH.bin (pixels)
cd "$(dirname "$0")"
for s in 768x512 576x1024 1024x576 1152x640 640x1152; do
  W=${s%x*}; H=${s#*x}; n=vae_${W}x${H}; [ -f qnn/out/$n.bin ] && continue
  (NOREF=1 .venv/bin/python export_vae_moss.py $n.onnx $((H/8)) $((W/8)) > /dev/null 2>&1) || { echo "export failed $s"; continue; }
  .venv/bin/python -c "
import onnx; m=onnx.load('$n.onnx'); m.ir_version=9; onnx.save(m,'$n.onnx')"
  mv $n.onnx $n.onnx.z.bin $n.onnx.ref.bin qnn/ 2>/dev/null
  (cd qnn && ./build_vae.sh $n 2>&1 | tail -1); rm -f qnn/$n.onnx qnn/$n.onnx.*.bin qnn/$n.dlc
done
echo finished; ls -la qnn/out/*.bin
