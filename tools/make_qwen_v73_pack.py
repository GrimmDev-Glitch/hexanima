#!/usr/bin/env python3
"""Stage the V73-only Qwen VAE contexts as a small companion pack.

Usage: make_qwen_v73_pack.py QWEN_VAE_OUT OUTPUT_DIR
The output directory is uploaded as qwen_v73/ in GrimmDev-Glitch/hexanima-packs.
"""
import hashlib
import json
import os
import shutil
import sys

src, out = sys.argv[1:3]
expected = {
    "vae_t32.bin": ("vae_t32_adaptive.bin", 527_971_856,
                    "b7776f55405e6f96e4a4bfa5a55b09710711aa3f98a6f4d345b6bceeeab1a295"),
    "enc_t32.bin": ("enc_t32.bin", 165_709_704,
                    "3bd61a45bf5f958f793c75d29f61aa8cab13ac9b67fe083a7abe802813a1890a"),
}
files = []
os.makedirs(out, exist_ok=True)
for target, (source, size, digest) in expected.items():
    src_path = os.path.join(src, source)
    dst_path = os.path.join(out, target)
    if os.path.getsize(src_path) != size:
        raise SystemExit(f"unexpected size for {src_path}: {os.path.getsize(src_path)} != {size}")
    h = hashlib.sha256()
    with open(src_path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    sha = h.hexdigest()
    if digest and sha != digest:
        raise SystemExit(f"unexpected SHA-256 for {src_path}: {sha}")
    if not os.path.isfile(dst_path) or os.path.getsize(dst_path) != size:
        shutil.copy2(src_path, dst_path)
    else:
        dst_hash = hashlib.sha256()
        with open(dst_path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                dst_hash.update(chunk)
        if dst_hash.hexdigest() != sha:
            shutil.copy2(src_path, dst_path)
    files.append({"path": target, "size": size, "sha256": sha})
    print(f"{target}\t{size}\t{sha}", flush=True)

with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
    json.dump({"files": files}, f, indent=1)
    f.write("\n")

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
shutil.copy2(os.path.join(root, "app/assets/licenses/qwen_research_license.txt"), os.path.join(out, "LICENSE.txt"))
shutil.copy2(os.path.join(root, "app/assets/licenses/qwen_notice.txt"), os.path.join(out, "NOTICE.txt"))
with open(os.path.join(out, "README.md"), "w", encoding="utf-8") as f:
    f.write("""# HexAnima Qwen-Image V73 contexts\n\n"""
            """Companion runtime files for the Qwen-Image 2.1 Turbo pack in HexAnima on Snapdragon 8 Gen 2 (SM8550 / Hexagon V73).\n\n"""
            """`vae_t32.bin` is the adaptive-RMS decoder validated against a real transformer latent on the Poco F6 Pro. `enc_t32.bin` is the VAE encoder used by photo editing. Both are generated from the Qwen-Image 2.1 VAE with QAIRT 2.28 and are installed under `files/qwen/vae_v73/`. SHA-256 and byte lengths are in `manifest.json`.\n\n"""
            """These are modified Qwen Materials. Non-commercial use only. The Qwen Research License and required attribution are included in `LICENSE.txt` and `NOTICE.txt`; see `NOTICE.txt` for the changes made.\n""")
