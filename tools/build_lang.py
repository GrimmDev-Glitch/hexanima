"""Builds app/assets/lang/<code>.tsv from tools/lang/<code>.txt (lines "N<TAB>translation", N = index of the English string in the key list).
The key list (tools/lang/keys.json) is every English string passed to L.s / L.f in app/src (plus the Info screen table and the catalog descriptions); new strings are appended at the end,
so existing numbers stay valid: run the script, it prints NEW key N lines, add "N<TAB>translation" to each .txt file (--keys prints the whole list). Placeholders (%d %s %.1f) must match the English string."""
import glob, json, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__)); APP = os.path.join(os.path.dirname(HERE), "app")
lit = r'"((?:[^"\\]|\\.)*)"'
def keys():
    out = []
    def add(k):
        k = k.replace('\\n', '\n').replace('\\"', '"')
        if k not in out: out.append(k)
    src = {f: open(f, encoding="utf-8").read() for f in glob.glob(os.path.join(APP, "src/com/hexanima/npu/*.java"))}
    for f, s in src.items():
        for m in re.finditer(r'L\.[sf]\(\s*' + lit + r'\s*,\s*' + lit, s): add(m.group(2))
    for m in re.finditer(r'\{\s*' + lit + r',\s*' + lit + r',\s*' + lit + r',\s*' + lit + r'\}', src[os.path.join(APP, "src/com/hexanima/npu/Info.java")]): add(m.group(2)); add(m.group(4))
    for d in ["Fast, 3D-render style", "Fast, realistic skin", "Higher quality and detail", "Higher quality, realistic skin"]: add(d)   # catalog descriptions (tools/make_pack.py META)
    return out
def main():
    # the numbering lives in tools/lang/keys.json and never changes: strings found in the sources that are not listed yet are appended (and reported)
    kf = os.path.join(HERE, "lang/keys.json"); ks = json.load(open(kf, encoding="utf-8"))
    for k in keys():
        if k not in ks: ks.append(k); print("NEW key", len(ks) - 1, repr(k))
    json.dump(ks, open(kf, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    if "--keys" in sys.argv:
        for i, k in enumerate(ks): print(i, k.replace("\n", "\\n"))
        return
    spec = lambda s: sorted(re.findall(r'%(?:\.\d)?[sdf]', s))
    os.makedirs(os.path.join(APP, "assets/lang"), exist_ok=True)
    for f in sorted(glob.glob(os.path.join(HERE, "lang/*.txt"))):
        code = os.path.basename(f)[:-4]; tr = {}
        for line in open(f, encoding="utf-8").read().split("\n"):
            if line.strip(): n, t = line.split("\t", 1); tr[int(n)] = t
        out = []
        for i, k in enumerate(ks):
            if i not in tr: print(code, "missing", i, k); continue
            if spec(tr[i]) != spec(k): print(code, "placeholder mismatch", i, k); continue
            out.append(k.replace("\n", "\\n") + "\t" + tr[i])
        open(os.path.join(APP, "assets/lang", code + ".tsv"), "w", encoding="utf-8").write("\n".join(out) + "\n")
        print(code, len(out), "of", len(ks))
main()
