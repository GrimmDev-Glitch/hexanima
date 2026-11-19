#!/bin/bash
# Full UNet call on the phone (all part*.bin in /data/local/tmp/sdxl/m) vs the PC fp32 eps for one reference step.
# usage: ./test_unet.sh [STEP=10] [B=1 (0 = negative prompt, 1 = positive)] [REPS=2]
set -e
S=${1:-10}; B=${2:-1}; R=${3:-2}
REF=$HOME/Hexanima-dev/sdxl/ref_a; PH=/data/local/tmp/sdxl; M=$PH/m; TMP=/tmp/sdxl_tu; mkdir -p $TMP
P=/home/grimm/anima-npu/moss-venv/bin/python
$P - <<EOF
import numpy as np
d = np.load('$REF/step%02d.npz' % $S); c = np.load('$REF/../cond_a.npz')
d['x'][0].astype(np.float32).tofile('$TMP/x.raw'); c['ctx'][$B].astype(np.float32).tofile('$TMP/ctx.raw')
np.load('$REF/emb_s%02d_$B.npy' % $S).astype(np.float32).tofile('$TMP/emb.raw')
EOF
for f in x emb ctx; do adb.exe push "$(wslpath -w $TMP/$f.raw)" $M/$f.raw >/dev/null; done
adb.exe shell "cd $PH && LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH=. ./sdxlrun libQnnHtp.so libQnnSystem.so $M unet $M/x.raw $M/emb.raw $M/ctx.raw $M/eps.raw $R"
adb.exe pull $M/eps.raw "$(wslpath -w $TMP/eps.raw)" >/dev/null
$P - <<EOF
import numpy as np
o = np.fromfile('$TMP/eps.raw', np.float32); r = np.load('$REF/step%02d.npz' % $S)['eps'][$B].ravel()
print('unet step $S b$B: rel err %.4f  cos %.5f  scale %.4f' % (np.linalg.norm(o - r) / np.linalg.norm(r), o @ r / np.linalg.norm(o) / np.linalg.norm(r), (o @ r) / (r @ r)))
EOF
