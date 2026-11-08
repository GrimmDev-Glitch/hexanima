#!/bin/sh
# usage: dump_emb.sh <llm.gguf> <tag> ; dumps embeddings of prompts in prompts.txt
cd "$(dirname "$0")"
i=0
while IFS= read -r p; do
  SD_DUMP_COND=emb/$2_$i.f32 SD_DUMP_COND_EXIT=1 ./sdcpp/build/bin/sd-cli --mmap \
    --diffusion-model models/MOSS-Image-SFT-UD-Q5_K_XL.gguf --llm "$1" --vae models/flux2-vae_small.safetensors \
    --tokenizer models/tokenizer.json -p "$p" -H 256 -W 256 --steps 1 -o /dev/null 2>&1 | grep -E "dumped|error|Error" 
  i=$((i+1))
done < prompts.txt
