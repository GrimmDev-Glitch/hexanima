#!/system/bin/sh
# MOSS-Image on the NPU (text encoder, refiner, DiT, VAE): one picture per call, weights are streamed from flash.
#   moss.sh IDS_FILE W H SEED STEPS OUT_RAW      tokens (int32) -> raw fp32 RGB [3][H][W] in [-1,1]; exit 0 on success
#   moss.sh stop                                  kill the helpers
# env: M (model dir, files/moss: glue.bin embd.q4k te/ ref/ dit/ vae/vae_<W>x<H>.bin), BIN (the app's native lib dir)
# The MOSS DSP skel has the same file name as Anima's (libmega_skel.so): it is copied to $M/dsp and that dir comes first in ADSP_LIBRARY_PATH.
if [ "$1" = stop ]; then for p in libmossrun.so libvaerun.so libvaetile.so; do pidof $p >/dev/null && kill -9 $(pidof $p); done; exit 0; fi
IDS=$1 W=$2 H=$3 SEED=$4 STEPS=$5 OUT=$6
LIBS=$BIN:/system/lib64:/vendor/lib64
ARCH=v69; SKEL=$BIN/libmossskel.so
if [ "$(getprop ro.soc.model)" = SM8550 ]; then
  ARCH=v73
  SKEL=$BIN/libmossskel_v73.so
  # Use the validated architecture-matched SM8550 kernel; LoKr sidecars remain experimental and are not selected by the app.
  [ -f "$SKEL" ] || { echo "MOSS DSP kernel is missing" >> "$M/moss.log"; exit 1; }
fi
ADSPP="$M/dsp;$BIN"
RUNX='export LD_LIBRARY_PATH="$LIBS" ADSP_LIBRARY_PATH="$ADSPP"; exec "$@"'
mkdir -p "$M/dsp" && cp -f "$SKEL" "$M/dsp/libmega_skel.so"
rm -f $M/moss.log $M/z.raw $OUT
MODE=${MODE:-4}   # 8 selects the DMD add-on (glue8.bin + lora8/)
printf 'load %s\nmode %s\ngen %s %s %s %s %s %s/z.raw\nquit\n' "$M" "$MODE" "$IDS" "$H" "$W" "$SEED" "$STEPS" "$M" | env LIBS=$LIBS ADSPP=$ADSPP sh -c "$RUNX" sh $BIN/libmossrun.so > $M/moss.log 2>&1
[ -s $M/z.raw ] || exit 1
VDIR=$M/vae; [ "$ARCH" = v73 ] && VDIR=$M/vae_v73
if [ -f $VDIR/vae_${W}x${H}.bin ]; then   # a full-frame context for this HTP architecture and size
  printf 'run %s/z.raw %s\nquit\n' "$M" "$OUT" | LIBS=$LIBS ADSPP=$ADSPP sh -c "$RUNX" sh $BIN/libvaerun.so $BIN/libQnnHtp.so $BIN/libQnnSystem.so $VDIR/vae_${W}x${H}.bin >> $M/moss.log 2>&1
else   # any other size: 512 px tiles of the 512x512 context, blended (GroupNorm statistics per tile: a slight smooth tone shift, ~31 dB vs full frame)
  [ -f $VDIR/vae_512x512.bin ] || { echo "Missing VAE context for $ARCH" >> "$M/moss.log"; exit 1; }
  printf 'run %s/z.raw %s %s %s\nquit\n' "$M" "$W" "$H" "$OUT" | LIBS=$LIBS ADSPP=$ADSPP sh -c "$RUNX" sh $BIN/libvaetile.so $BIN/libQnnHtp.so $BIN/libQnnSystem.so $VDIR/vae_512x512.bin >> $M/moss.log 2>&1
fi
[ -s $OUT ]
