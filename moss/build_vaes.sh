#!/bin/bash
# full-frame VAE decoder contexts (HTP v69, fp16) for the preset sizes: vae_WxH.bin (pixels)
cd "$(dirname "$0")"
ARCH=${ARCH:-v69}; SOC_MODEL=${SOC_MODEL:-}; [ -n "$SOC_MODEL" ] || { [ "$ARCH" = v73 ] && SOC_MODEL=43 || SOC_MODEL=36; }
OUT=out; [ "$ARCH" = v69 ] || OUT=out_$ARCH
SIZES=${SIZES:-"768x512 576x1024 1024x576 1152x640 640x1152"}
for s in $SIZES; do
  W=${s%x*}; H=${s#*x}; n=vae_${W}x${H}; [ -f qnn/$OUT/$n.bin ] && continue
  (NOREF=1 .venv/bin/python export_vae_moss.py $n.onnx $((H/8)) $((W/8)) > /dev/null 2>&1) || { echo "export failed $s"; continue; }
  .venv/bin/python -c "
import onnx; m=onnx.load('$n.onnx'); m.ir_version=9; onnx.save(m,'$n.onnx')"
  mv $n.onnx $n.onnx.z.bin $n.onnx.ref.bin qnn/ 2>/dev/null
  (cd qnn && ARCH=$ARCH SOC_MODEL=$SOC_MODEL ./build_vae.sh $n 2>&1 | tail -1); rm -f qnn/$n.onnx qnn/$n.onnx.*.bin qnn/$n.dlc
done
echo finished; ls -la qnn/$OUT/*.bin
