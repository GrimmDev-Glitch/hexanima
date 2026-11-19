#!/bin/bash
# One-part check on the phone: runs work/out_v73/part<I>.bin on a calibration sample and compares its "hout" with the fp32 PC value
# (the next part's saved input "h"). usage: ./test_part.sh I [SAMPLE=s10_1] [REPS=3]
set -e
I=$1; S=${2:-s10_1}; R=${3:-3}; J=$((I + 1))
W=$HOME/Hexanima-dev/sdxl/work; REF=$HOME/Hexanima-dev/sdxl/ref_a; PH=/data/local/tmp/sdxl; T=$PH/t$I
w() { wslpath -w "$1"; }
adb.exe shell mkdir -p $T
adb.exe push "$(w $W/out_v73/part$I.bin)" $T/part0.bin >/dev/null
ARGS=""
for n in h emb ctx; do adb.exe push "$(w $W/calib_p$I/${S}_$n.raw)" $T/$n.raw >/dev/null; done
adb.exe shell "cd $PH && LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. ./sdxlrun libQnnHtp.so libQnnSystem.so $T unet $T/h.raw $T/emb.raw $T/ctx.raw $T/out.raw $R"
adb.exe pull $T/out.raw "$(w /tmp/sdxl_out$I.raw)" >/dev/null
/home/grimm/anima-npu/moss-venv/bin/python - <<EOF
import numpy as np, os
o = np.fromfile('/tmp/sdxl_out$I.raw', np.float32)
f = '$REF/calib/p$J/$S.npz'
r = np.load(f)['h'].astype(np.float32).ravel() if os.path.exists(f) else None
if r is None: print('no reference for part $I'); raise SystemExit
print('part $I: rel err %.4f  cos %.5f  scale %.4f  |ref| %.3f' % (np.linalg.norm(o - r) / np.linalg.norm(r), o @ r / np.linalg.norm(o) / np.linalg.norm(r), (o @ r) / (r @ r), np.abs(r).mean()))
EOF
