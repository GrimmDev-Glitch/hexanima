#!/usr/bin/env bash
# Convert a Qwen-Image-2.1 Turbo Civitai Safetensors LoRA into a V73 overlay pack.
# Run from WSL in qwen/: bash convert_civitai_lora.sh /path/to/download.safetensors [strength] [pack-name] [comma-separated-trigger-words]
set -euo pipefail
cd "$(dirname "$0")"

usage() { echo "Usage: $0 LORA.safetensors [strength 0..2] [pack-name] [trigger-words]" >&2; exit 2; }
[[ $# -ge 1 && $# -le 4 ]] || usage
src=$(realpath "$1")
[[ -f "$src" && "$src" == *.safetensors ]] || usage
strength=${2:-1.0}
name=${3:-$(basename "$src" .safetensors)}
triggers=${4:-}
sidecar="${src%.safetensors}.json"
if [[ -z "$triggers" && -s "$sidecar" ]]; then
  triggers=$(python3 - "$sidecar" <<'PY'
import json, pathlib, sys
data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
words = data.get("words", [])
if isinstance(words, list):
    print(", ".join(str(word).strip() for word in words if str(word).strip()))
PY
)
fi
[[ "$strength" =~ ^([0-2]([.][0-9]+)?|[.]([0-9]+))$ ]] || usage
awk -v n="$strength" 'BEGIN { exit !(n >= 0 && n <= 2) }' || usage
[[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || { echo "Invalid pack name" >&2; exit 2; }
[[ ${#triggers} -le 500 ]] || { echo "Trigger words exceed 500 characters" >&2; exit 2; }

[[ -s models/qwen-image-2.1-turbo-Q5_K_S.gguf ]] || { echo "Missing pinned Qwen GGUF: run python3 fetch_converter_inputs.py gguf lightning" >&2; exit 1; }
[[ -s loras/lightning_v2rl_r64.safetensors ]] || { echo "Missing Lightning LoRA: run python3 fetch_converter_inputs.py gguf lightning" >&2; exit 1; }
[[ -s ref/Qwen-Image-2.1-Turbo/transformer/config.json && -s ref/Qwen-Image-2.1-Turbo/vae/config.json ]] || { echo "Missing pinned Qwen config files" >&2; exit 1; }
for i in $(seq -w 0 31); do [[ -s "phone_base/dit/blk${i}.bin" ]] || { echo "Missing phone_base/dit/blk${i}.bin" >&2; exit 1; }; done
[[ -s phone_base/glue.bin ]] || { echo "Missing phone_base/glue.bin" >&2; exit 1; }

out="converted/$name"
mkdir -p artifacts converted
[[ ! -e "$out" && ! -e "$out.zip" ]] || { echo "Output already exists: $out (choose another pack-name)" >&2; exit 1; }
tmp=$(mktemp -d "converted/.build-${name}.XXXXXX")
raw="artifacts/.civitai-${name}-$$.safetensors"
cleanup() { rm -f "$raw"; rm -rf "$tmp"; }
trap cleanup EXIT
cp -- "$src" "$raw"

if ! docker image inspect hexanima-qwen-converter >/dev/null 2>&1; then
  docker build --platform linux/amd64 -t hexanima-qwen-converter -f Dockerfile.converter .
fi

docker run --rm --platform linux/amd64 \
  -v "$(cd .. && pwd):/repo" -w /repo/qwen \
  hexanima-qwen-converter bash -lc '
    set -euo pipefail
    python3 export_qwen_lora.py "$1/fused" "$2" "$3"
    python3 make_qwen_overlays.py phone_base "$1/fused" "$1/overlay"
    python3 - "$1/overlay/manifest.json" "$4" "$3" "$5" <<"PY"
import json, pathlib, sys
p = pathlib.Path(sys.argv[1]); m = json.loads(p.read_text(encoding="utf-8"))
m["base_pack_id"] = "hexanima-qwen-image-2.1-turbo-v1"
m["adapter"] = {"name": sys.argv[2], "strength": float(sys.argv[3]), "base": "Qwen-Image-2.1-Turbo V73",
                 "trigger_words": [x.strip() for x in sys.argv[4].split(",") if x.strip()]}
p.write_text(json.dumps(m, indent=2) + "\n", encoding="utf-8")
PY
    python3 - "$1/overlay" "$4" <<"PY"
import pathlib, sys, zipfile
root = pathlib.Path(sys.argv[1]); target = pathlib.Path("converted") / (sys.argv[2] + ".zip")
target.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as z:
    for p in sorted(root.iterdir()):
        if p.is_file(): z.write(p, p.name)
print(f"Ready: {target} ({target.stat().st_size} bytes)")
PY
  ' bash "$tmp" "$raw" "$strength" "$name" "$triggers"

mv "$tmp/overlay" "$out"
printf 'Overlay folder: %s\nOverlay archive: %s.zip\n' "$out" "$out"

