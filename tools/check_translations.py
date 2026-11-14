#!/usr/bin/env python3
"""Fails when an L.s / L.f English string in app/src has no Spanish line in app/assets/lang/es.tsv.
   usage: python3 tools/check_translations.py [--list]   (exit 1 and the missing strings when something is untranslated)"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app', 'src')
TSV = os.path.join(ROOT, 'app', 'assets', 'lang', 'es.tsv')

LIT = r'"((?:[^"\\]|\\.)*)"'
# L.s("first", "english")  /  L.f("first", "english", args...) -- the English text is the second literal (maybe "a" + "b")
CALL = re.compile(r'\bL\.[sf]\(\s*' + LIT + r'(?:\s*\+\s*' + LIT + r')*\s*,\s*((?:' + LIT + r'\s*\+?\s*)+)', re.S)

def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            if n == 'u': out.append(chr(int(s[i + 2:i + 6], 16))); i += 6; continue
            out.append({'n': '\n', 't': '\t', '"': '"', "'": "'", '\\': '\\'}.get(n, n)); i += 2; continue
        out.append(c); i += 1
    return ''.join(out)

def english_strings():
    found = {}
    for d, _, fs in os.walk(SRC):
        for f in fs:
            if not f.endswith('.java'): continue
            p = os.path.join(d, f); txt = open(p, encoding='utf-8').read()
            for m in CALL.finditer(txt):
                en = ''.join(unescape(x) for x in re.findall(LIT, m.group(3)))
                line = txt.count('\n', 0, m.start()) + 1
                found.setdefault(en, f'{os.path.relpath(p, ROOT)}:{line}')
    return found

def table():
    keys = set()
    for line in open(TSV, encoding='utf-8'):
        line = line.rstrip('\r\n'); t = line.find('\t')
        if t > 0: keys.add(line[:t].replace('\\n', '\n'))
    return keys

def main():
    en, es = english_strings(), table()
    missing = {k: v for k, v in en.items() if k not in es and re.search(r'[A-Za-z]{2}', k)}
    for k, where in sorted(missing.items(), key=lambda kv: kv[1]):
        print(f'{where}\t{k!r}')
    print(f'{len(en)} strings, {len(missing)} without Spanish', file=sys.stderr)
    return 1 if missing else 0

if __name__ == '__main__':
    sys.exit(main())
