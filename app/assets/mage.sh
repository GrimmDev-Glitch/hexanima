#!/system/bin/sh
# Mage-Flow on Hexagon. The V73 skeleton is built from this repository's kernels;
# never copy upstream's v69 mageskel onto a Snapdragon 8 Gen 2.
if [ "$1" = stop ]; then pidof libmagerun_mage.so >/dev/null && kill -9 $(pidof libmagerun_mage.so); exit 0; fi
IDS=$1 DROP=$2 W=$3 H=$4 SEED=$5 STEPS=$6 OUT=$7 ED=$8
ARCH=v69; SKEL=$BIN/libmageskel.so
case "$(getprop ro.soc.model)" in SM8550) ARCH=v73; SKEL=$BIN/libmageskel_v73.so;; esac
[ -f "$SKEL" ] || { echo "Missing Mage DSP kernel for $ARCH" >&2; exit 1; }
mkdir -p "$M/dsp" "$M/cache"; cp -f "$SKEL" "$M/dsp/libmega_skel.so"
rm -f "$M/mage.log" "$M/z.f32" "$M/prev.bin" "$OUT"
if [ -n "$ED" ]; then CMD="edit $ED $DROP $SEED $STEPS $M/z.f32"; else CMD="genp $IDS $DROP $H $W $SEED $STEPS $M/z.f32"; fi
printf 'load %s\n%s\nquit\n' "$M" "$CMD" | LD_LIBRARY_PATH=$BIN:/system/lib64:/vendor/lib64 ADSP_LIBRARY_PATH="$M/dsp;$BIN" MAGE_CACHE=$M/cache MAGE_PREVIEW=$M/prev.bin MAGE_RGB=$OUT "$BIN/libmagerun_mage.so" > "$M/mage.log" 2>&1
[ -s "$OUT" ]
