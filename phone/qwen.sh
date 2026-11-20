#!/system/bin/sh
# Qwen-Image-2.1 on the NPU (Qwen3-VL text encoder, DiT, VAE; editing adds the ViT and the VAE encoder): one picture per call, weights are streamed from flash.
#   qwen.sh IDS DROP W H SEED STEPS OUT_RAW [EDIT_DIR]    tokens (int32, DROP leading system tokens are removed) -> raw fp32 RGB [3][H][W] in [-1,1]; exit 0 on success
#   qwen.sh stop                                           kill the helpers
# EDIT_DIR (editing): meta.txt "CW CH", rgba.f32 (RGBA [4][CH][CW] in [-1,1]), px.f32 (ViT patches); ids already contain the expanded image slots
# env: Q (model dir, files/qwen: glue.bin embd.q4k te/ dit/ vit/ vae/{vae_t32.bin,enc_t32.bin} tok_*.txt), BIN (the app's native lib dir)
# The DSP skel has the same file name as Anima's (libmega_skel.so): it is copied to $Q/dsp and that dir comes first in ADSP_LIBRARY_PATH.
if [ "$1" = stop ]; then for p in libqwenrun.so libvaeenc.so libqvaetile.so; do pidof $p >/dev/null && kill -9 $(pidof $p); done; pkill -f 'tail -f .*vae.cmd'; exit 0; fi
IDS=$1 DROP=$2 W=$3 H=$4 SEED=$5 STEPS=$6 OUT=$7 ED=$8
ARCH=v69; SKEL=$BIN/libqwenskel.so
case "$(getprop ro.soc.model)" in
  SM8550|SM8650|SM8735)
    ARCH=v73
    SKEL=$BIN/libqwenskel_v73.so
    ;;
esac
[ -f "$SKEL" ] || { echo "Missing Qwen DSP kernel for $ARCH" >&2; exit 1; }
VDIR=$Q/vae; [ "$ARCH" = v73 ] && VDIR=$Q/vae_v73
LIBS=$BIN:/system/lib64:/vendor/lib64; ADSPP="$Q/dsp;$BIN"
RUNX='export LD_LIBRARY_PATH="$LIBS" ADSP_LIBRARY_PATH="$ADSPP"; exec "$@"'
mkdir -p $Q/dsp && cp -f $SKEL $Q/dsp/libmega_skel.so
mkdir -p $Q/cache   # text-encoder cache: the same prompt (and photo) with another seed skips the ViT and the encoder
rm -f $Q/qwen.log $Q/z.raw $OUT
if [ -n "$ED" ]; then   # the condition image: VAE encoder tiles on the NPU -> raw latent (kept while the photo and size stay the same; the app deletes it when it rewrites the photo files)
  K=0   # one "CW CH" line per reference picture: picture 1 uses rgba.f32 / cond_raw.f32, picture K uses rgba_K.f32 / cond_raw_K.f32
  while read CW CH; do
    K=$((K+1)); SX=""; [ $K -gt 1 ] && SX="_$K"
    if [ ! -s $ED/cond_raw$SX.f32 ]; then
      printf 'run %s/rgba%s.f32 %s %s %s/cond_raw%s.f32\nquit\n' "$ED" "$SX" "$CW" "$CH" "$ED" "$SX" | LIBS=$LIBS ADSPP=$ADSPP sh -c "$RUNX" sh $BIN/libvaeenc.so $BIN/libQnnHtp.so $BIN/libQnnSystem.so $VDIR/enc_t32.bin >> $Q/qwen.log 2>&1
    fi
    [ -s $ED/cond_raw$SX.f32 ] || exit 1
  done < $ED/meta.txt
fi
# Run the transformer first: rejected arguments must not send quit while QNN is still opening the decoder.
printf 'load %s\ngen %s %s %s %s %s %s %s/z.raw\nquit\n' "$Q" "$IDS" "$DROP" "$H" "$W" "$SEED" "$STEPS" "$Q" | env LIBS=$LIBS ADSPP=$ADSPP QWEN_EDIT="$ED" QWEN_CACHE=$Q/cache QWEN_LORA="${QWEN_LORA:-}" sh -c "$RUNX" sh $BIN/libqwenrun.so >> $Q/qwen.log 2>&1
if [ -s $Q/z.raw ]; then
  VAE=${VAE:-vae_t32.bin}; rm -f $Q/vae.cmd $Q/vae.log; : > $Q/vae.cmd
  (tail -f $Q/vae.cmd | LIBS=$LIBS ADSPP=$ADSPP sh -c "$RUNX" sh $BIN/libqvaetile.so $BIN/libQnnHtp.so $BIN/libQnnSystem.so $VDIR/$VAE > $Q/vae.log 2>&1 &)
  n=0; while ! grep -q '^ready' $Q/vae.log && [ $n -lt 300 ]; do sleep 0.2; n=$((n+1)); done
  if grep -q '^ready' $Q/vae.log; then printf 'run %s/z.raw %s %s %s\nquit\n' "$Q" "$W" "$H" "$OUT" >> $Q/vae.cmd; fi
  n=0; while pidof libqvaetile.so >/dev/null && [ $n -lt 900 ]; do sleep 0.2; n=$((n+1)); done
  pkill -f "tail -f $Q/vae.cmd"; cat $Q/vae.log >> $Q/qwen.log 2>/dev/null
fi
[ -s $OUT ]
