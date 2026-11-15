# ADB test of Mage-Flow on the V73 NPU, outside the app: sh run_phone.sh IDS.i32 DROP H W SEED STEPS OUT.rgb
# layout under /data/local/tmp/mage: magerun, dsp/libmega_skel.so (V73 build), model/ (upstream pack), cache/
cd /data/local/tmp/mage
IDS=$1 DROP=$2 H=$3 W=$4 SEED=$5 STEPS=$6 OUT=$7
rm -f z.f32 "$OUT"
T0=$(date +%s)
printf 'load %s\ngenp %s %s %s %s %s %s z.f32\nmem\nquit\n' "$PWD/model" "$IDS" "$DROP" "$H" "$W" "$SEED" "$STEPS" | \
  LD_LIBRARY_PATH=/system/lib64:/vendor/lib64 ADSP_LIBRARY_PATH="$PWD/dsp;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp" \
  MAGE_CACHE=$PWD/cache MAGE_RGB=$OUT ./magerun
echo "wall $(( $(date +%s) - T0 )) s"
