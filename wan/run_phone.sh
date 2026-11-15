# Wan 2.2 TI2V-5B Turbo on the phone (ADB, /data/local/tmp/wan): text or picture to video with sd-cli + ggml-hexagon (DiT on the NPU)
# usage: sh run_phone.sh W H FRAMES OUT "prompt" [init.png] [extra sd-cli args...]
# env: DIT (diffusion gguf), TAE (set empty to decode with the full VAE), STEPS (4), SAMPLER (euler), VRAM (GiB the DiT may keep on the NPU, 1.5), GGML_HEXAGON_MBUF (256M,256M: V73 cannot map 512 MB buffers)
cd /data/local/tmp/wan
export LD_LIBRARY_PATH=/system/lib64:/vendor/lib64 ADSP_LIBRARY_PATH="/data/local/tmp/wan;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp"
export GGML_HEXAGON_MBUF=${GGML_HEXAGON_MBUF:-256M,256M}
W=$1; H=$2; FR=$3; OUT=$4; P=$5; shift 5
INIT=""; if [ -n "$1" ] && [ -f "$1" ]; then INIT="-i $1"; shift; fi
T0=$(date +%s)
./sd-cli -M vid_gen --diffusion-model ${DIT:-Wan2_2-TI2V-5B-Turbo-Q4_0.gguf} --vae wan2.2_vae.safetensors ${TAE---tae taew2_2.safetensors} \
  --t5xxl umt5-xxl-encoder-Q4_K_M.gguf -p "$P" --cfg-scale 1.0 --steps ${STEPS:-4} --sampling-method ${SAMPLER:-euler} \
  -W $W -H $H --video-frames $FR $INIT --backend diffusion=HTP0,te=cpu,vae=cpu -t 6 \
  --params-backend diffusion=disk --flash-attn on --max-vram HTP0=${VRAM:-1.5} -o $OUT "$@"
echo "wall $(( $(date +%s) - T0 )) s"
