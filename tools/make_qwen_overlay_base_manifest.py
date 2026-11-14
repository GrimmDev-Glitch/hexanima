#!/usr/bin/env python3
"""Extract the pinned Qwen phone-pack fingerprints from a verified overlay manifest."""
import json
import sys
from pathlib import Path

source, output = map(Path, sys.argv[1:3])
manifest = json.loads(source.read_text(encoding="utf-8"))
blocks = manifest.get("blocks", [])
if manifest.get("format") != "hexanima-qwen-overlay-v1" or len(blocks) != 32:
    raise SystemExit("source must describe the complete Qwen V73 overlay")
fingerprints = []
for i, block in enumerate(blocks):
    digest = block.get("base_sha256", "")
    if block.get("block") != i or len(digest) != 64:
        raise SystemExit(f"invalid base fingerprint for block {i}")
    fingerprints.append({"block": i, "sha256": digest})
glue = manifest.get("glue", {}).get("base_sha256", "")
if len(glue) != 64:
    raise SystemExit("missing Qwen glue fingerprint")
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({
    "base_pack_id": "hexanima-qwen-image-2.1-turbo-v1",
    "model_id": "qwen-image-21-turbo",
    "blocks": fingerprints,
    "glue_sha256": glue,
}, indent=2) + "\n", encoding="utf-8")
print(f"Wrote pinned Qwen base fingerprints: {output}")
