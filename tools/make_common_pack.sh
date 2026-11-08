#!/bin/bash
# Builds the shared-files pack of one Hexagon generation (common_ARCH/: QNN text-encoder and VAE contexts + the arch-independent files) with the
# manifest.json the app's model manager reads (Models.installCommon), and with REPO also uploads it to that Hugging Face model repo (hf auth login first).
# usage: make_common_pack.sh ARCH [REPO]     e.g. make_common_pack.sh v73 someone/hexanima-packs
set -e; cd "$(dirname "$0")/.."; A=${1:?arch, e.g. v73}; REPO=$2
OUT=~/anima-npu/packs/common_$A; mkdir -p $OUT
BASE=https://huggingface.co/ayanoby/anima-npu-packs/resolve/main/common; C=~/anima-npu/common_dl; mkdir -p $C
for f in te_embed.f16 taew2_1.safetensors llm_hdr.bin manifest.json; do [ -s $C/$f ] || curl -sL -o $C/$f $BASE/$f; done
for f in te0 te1 te2 te3; do cp te/out_$A/$f.bin $OUT/; done
cp vae/out_$A/vae.bin $C/te_embed.f16 $C/taew2_1.safetensors $C/llm_hdr.bin $OUT/
python3 - "$OUT" "$C/manifest.json" <<'EOF'
import hashlib, json, os, sys
out, ref = sys.argv[1], json.load(open(sys.argv[2]))
def entry(f):
    h = hashlib.sha256()
    with open(os.path.join(out, f), "rb") as fh:
        for b in iter(lambda: fh.read(1 << 22), b""): h.update(b)
    return {"path": f, "size": os.path.getsize(os.path.join(out, f)), "sha256": h.hexdigest()}
m = {"files": [entry(f) for f in ["te0.bin", "te1.bin", "te2.bin", "te3.bin", "te_embed.f16", "vae.bin"]], "sd": [entry("taew2_1.safetensors")], "llm_stub": dict(ref["llm_stub"])}
m["llm_stub"]["hdr_sha256"] = entry("llm_hdr.bin")["sha256"]; m["llm_stub"]["hdr_size"] = os.path.getsize(os.path.join(out, "llm_hdr.bin"))
json.dump(m, open(os.path.join(out, "manifest.json"), "w"), indent=1)
print("manifest:", sum(e["size"] for e in m["files"] + m["sd"]) / 1e9, "GB")
EOF
ls -l $OUT
if [ -n "$REPO" ]; then
  HF=~/anima-npu/work/.venv/bin/hf
  $HF repos create "$REPO" --repo-type model --public --exist-ok   # public: the app downloads without an account
  $HF upload "$REPO" $OUT common_$A --repo-type model --commit-message "common_$A: QNN contexts for Hexagon ${A^^} (HexAnima)"
  [ -f ~/anima-npu/packs/README.md ] && $HF upload "$REPO" ~/anima-npu/packs/README.md README.md --repo-type model --commit-message "README"
fi
