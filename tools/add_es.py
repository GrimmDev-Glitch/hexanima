# Adds to app/assets/lang/es.tsv the L.s/L.f strings of the given Java files whose first argument is already Spanish and whose English text is missing.
# usage: python tools/add_es.py app/src/com/hexanima/npu/StorageActivity.java ...   (prints what it added)
import re, sys, os
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
tsv = os.path.join(root, 'app', 'assets', 'lang', 'es.tsv')
have = set(l.split('\t', 1)[0] for l in open(tsv, encoding='utf-8').read().split('\n') if '\t' in l)
lit = r'"((?:[^"\\]|\\.)*)"'
pat = re.compile(r'L\.[sf]\(\s*' + lit + r'\s*,\s*' + lit)
cyr = re.compile('[Ѐ-ӿ]')
add = []
for f in sys.argv[1:]:
    for es, en in pat.findall(open(f, encoding='utf-8').read()):
        if cyr.search(es) or en in have or es == en: continue   # Russian first argument: translated elsewhere
        e_en = en.replace('\\"', '"'); e_es = es.replace('\\"', '"')
        if e_en in have: continue
        have.add(e_en); add.append(e_en + '\t' + e_es)
if add:
    s = open(tsv, encoding='utf-8').read()
    if not s.endswith('\n'): s += '\n'
    open(tsv, 'w', encoding='utf-8', newline='\n').write(s + '\n'.join(add) + '\n')
for a in add: print(a)
