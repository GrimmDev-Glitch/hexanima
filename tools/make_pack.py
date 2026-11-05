"""Builds the downloadable model packs (the layout the Android app expects) in OUT:
  catalog.json, common/{manifest.json,...}, models/<id>/{manifest.json, small.bin, hdr.bin, blkNN.bin}
Upload OUT as is to a Hugging Face model repo (huggingface-cli upload REPO OUT .) and point the app at .../resolve/main/ .
usage: make_pack.py OUT [id ...]      (needs the venv with torch for the block export, and the phone on adb for the shared TE/VAE files)"""
import hashlib, json, os, struct, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__)); NPU = os.path.dirname(HERE); MODELS = os.path.join(os.path.dirname(NPU), "models")
EXPORT = os.path.join(NPU, "hexagon", "mega", "export_all.py")
# id -> (display name, description, safetensors, small.bin, q8, number of blocks)
CATALOG = {
    "harem2b": ("MiaoMiao 3D Harem · LH3D 1.1", "Быстрая, стиль 3D-рендер", "miaomiao3DHarem_LH3D11_turbo11_f16.safetensors", "small.bin", False),
    "real2b": ("MiaoMiao RealSkin · Anima 1.3", "Быстрая, реалистичная кожа", "miaomiaoRealskin_anima13_turbo11_f16.safetensors", "smallr2.bin", False),
    "harem29": ("MiaoMiao Harem · 2.9B BETA 1.1", "Выше качество и детализация", "miaomiaoHarem_29BBETA11_turbo4_f16.safetensors", "small29.bin", True),
    "real29": ("MiaoMiao RealSkin · Anima 2.9B 1.0", "Выше качество, реалистичная кожа", "miaomiaoRealskin_anima29B10_turbo4_f16.safetensors", "smallr29.bin", True),
}
# id -> (params, quantization, seconds per picture at 512x768, author, English description)
META = {'harem2b': ('2B', 'fp16', 7, 'MIAOKA', 'fast, ~7 s'), 'real2b': ('2B', 'fp16', 7, 'MIAOKA', 'fast, ~7 s'), 'harem29': ('2.9B', 'q8', 12, 'MIAOKA', 'higher quality, ~12 s, q8'), 'real29': ('2.9B', 'q8', 12, 'MIAOKA', 'higher quality, ~12 s, q8')}
def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""): h.update(b)
    return h.hexdigest()
def entry(d, rel): p = os.path.join(d, rel); return {"path": rel, "size": os.path.getsize(p), "sha256": sha(p)}
def header(st, out):
    with open(st, "rb") as f: n = struct.unpack("<Q", f.read(8))[0]; f.seek(0); h = f.read(8 + n)
    open(out, "wb").write(h); return os.path.getsize(st)
def main():
    out = sys.argv[1]; ids = sys.argv[2:] or list(CATALOG)
    os.makedirs(out, exist_ok=True)
    cd = os.path.join(out, "common"); os.makedirs(cd, exist_ok=True)
    for f in ["te0.bin", "te1.bin", "te2.bin", "te3.bin", "te_embed.f16", "vae.bin"]:
        if not os.path.exists(os.path.join(cd, f)): subprocess.check_call(["adb", "pull", "/data/local/tmp/anima/" + f, os.path.join(cd, f)])
    if not os.path.exists(os.path.join(cd, "taew2_1.safetensors")): subprocess.check_call(["cp", os.path.join(MODELS, "taew2_1.safetensors"), cd])
    llm = os.path.join(MODELS, "miaomiao3DHarem_animaLH3D10_txt.safetensors"); llm_size = header(llm, os.path.join(cd, "llm_hdr.bin"))
    cm = {"files": [entry(cd, f) for f in ["te0.bin", "te1.bin", "te2.bin", "te3.bin", "te_embed.f16", "vae.bin"]], "sd": [entry(cd, "taew2_1.safetensors")],
          "llm_stub": {"hdr": "llm_hdr.bin", "hdr_size": os.path.getsize(os.path.join(cd, "llm_hdr.bin")), "hdr_sha256": sha(os.path.join(cd, "llm_hdr.bin")), "name": "miaomiao3DHarem_animaLH3D10_txt.safetensors", "size": llm_size}}
    json.dump(cm, open(os.path.join(cd, "manifest.json"), "w"), indent=1)
    common_size = sum(e["size"] for e in cm["files"] + cm["sd"])
    models = []
    for i in ids:
        name, desc, st, small, q8 = CATALOG[i]; d = os.path.join(out, "models", i); os.makedirs(d, exist_ok=True)
        if not os.path.exists(os.path.join(d, "blk00.bin")):
            subprocess.check_call([sys.executable, EXPORT] + (["--q8"] if q8 else []) + [os.path.join(MODELS, st), d])
        subprocess.check_call(["cp", os.path.join(NPU, "phone", small), os.path.join(d, "small.bin")])
        size = header(os.path.join(MODELS, st), os.path.join(d, "hdr.bin"))
        blks = sorted(f for f in os.listdir(d) if f.startswith("blk") and f.endswith(".bin"))
        files = [entry(d, f) for f in ["small.bin"] + blks] + [entry(d, "hdr.bin")]
        json.dump({"id": i, "name": name, "files": files, "stub": {"hdr": "hdr.bin", "size": size}}, open(os.path.join(d, "manifest.json"), "w"), indent=1)
        pa, qu, sp, au, de = META[i]
        models.append({"id": i, "name": name, "desc": desc, "desc_en": de, "params": pa, "quant": qu, "speed": sp, "author": au, "size": sum(f["size"] for f in files), "manifest": f"models/{i}/manifest.json"})
        print("pack", i, models[-1]["size"], flush=True)
    json.dump({"version": 1, "common_size": common_size, "models": models}, open(os.path.join(out, "catalog.json"), "w"), indent=1)
main()
