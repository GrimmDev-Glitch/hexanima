#!/bin/bash
# Smoke test of the Dev app over ADB: one picture per installed model family (Anima, MOSS, Qwen, Mage), generated through the app itself,
# with time, temperature and a check that the picture is not black, flat or broken. The test pictures are deleted and the app settings restored.
# usage (Git Bash or Linux, phone connected, Dev build com.hexanima.npu.dev installed):  bash tools/adb_smoke.sh [family ...]
# needs: adb, python with Pillow + numpy on the PC. Exit code 0 = every tested family passed.
export MSYS_NO_PATHCONV=1
PKG=com.hexanima.npu.dev
T=$(mktemp -d 2>/dev/null || echo /tmp/hx_smoke); mkdir -p "$T"
command -v cygpath >/dev/null && T=$(cygpath -m "$T")   # Git Bash: Windows-side python and adb need a Windows path
# the test restarts the app: never while it downloads a model (the download would stop; it resumes later, but better not)
if [ -n "$(adb shell "run-as $PKG sh -c 'find files -name \"*.part\" -mmin -1 2>/dev/null | head -1'" | tr -d '')" ]; then echo "The app is downloading something: run the test when it finishes."; exit 2; fi
PY=$(command -v python3 || command -v python)
P="1girl, long silver hair, blue eyes, white school uniform, red bowtie, smile, cherry blossoms, upper body"
PS="An anime girl with long silver hair and blue eyes, white school uniform with a red bow, smiling, cherry blossoms, soft light"
WANT="$*"
sh_app() { adb shell "run-as $PKG sh -c '$1'" | tr -d '\r'; }
temp() { adb shell 'cat /sys/class/power_supply/battery/temp' 2>/dev/null | tr -d '\r' | awk '{printf "%.1f", $1/10}'; }

# what is installed (model id per family)
ANIMA=$(sh_app 'ls -d files/anima/m_* 2>/dev/null | head -1' | sed 's|.*/m_||')
meta_id() { sh_app "cat files/$1/meta.json 2>/dev/null" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'; }
MOSS=$(meta_id moss); QWEN=$(meta_id qwen); MAGE=$(meta_id mage)
[ -n "$(sh_app 'ls files/qwen/.installed 2>/dev/null')" ] || QWEN=""
[ -n "$(sh_app 'ls files/mage/.installed 2>/dev/null')" ] || MAGE=""

# settings backup (restored at the end, whatever happens)
adb shell "am force-stop $PKG"; sh_app 'cp shared_prefs/anima.xml files/smoke_prefs_backup.xml'
restore() { adb shell "am force-stop $PKG"; sh_app 'cp files/smoke_prefs_backup.xml shared_prefs/anima.xml && rm files/smoke_prefs_backup.xml'; rm -rf "$T"; }
trap restore EXIT

setprefs() {   # key=type:value ...
  adb shell "am force-stop $PKG"; adb exec-out run-as $PKG cat shared_prefs/anima.xml > "$T/p.xml"
  "$PY" - "$T/p.xml" "$@" <<'PYEOF'
import sys, re
from xml.sax.saxutils import escape
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
for kv in sys.argv[2:]:
    k, tv = kv.split('=', 1); t, v = tv.split(':', 1)
    s = re.sub(r'\s*<(string|boolean|int|long|float) name="%s"(?: value="[^"]*")?\s*(?:/>|>.*?</string>)' % re.escape(k), '', s, flags=re.S)
    el = '<string name="%s">%s</string>' % (k, escape(v)) if t == 'string' else '<%s name="%s" value="%s" />' % (t, k, v)
    s = s.replace('</map>', '    ' + el + '\n</map>')
open(p, 'w', encoding='utf-8').write(s)
PYEOF
  adb push "$T/p.xml" /data/local/tmp/smoke_p.xml >/dev/null && adb shell "chmod 644 /data/local/tmp/smoke_p.xml; run-as $PKG cp /data/local/tmp/smoke_p.xml shared_prefs/anima.xml; rm /data/local/tmp/smoke_p.xml"
}
tap_generate() {   # finds the Generate button by its text (any screen size) and taps it
  adb shell "am start -n $PKG/com.hexanima.npu.Main >/dev/null"; sleep 4
  adb shell 'uiautomator dump /data/local/tmp/smoke_ui.xml >/dev/null 2>&1'
  local b; b=$(adb exec-out cat /data/local/tmp/smoke_ui.xml | "$PY" -c "
import re, sys
for n in re.findall(r'<node [^>]*>', sys.stdin.read()):
    t = re.search(r' text=\"([^\"]*)\"', n).group(1); m = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', n)
    if t.startswith(('Generar', 'Generate')) and m: a = list(map(int, m.groups())); print((a[0]+a[2])//2, (a[1]+a[3])//2); break")
  [ -n "$b" ] || return 1; adb shell "input tap $b"
}
check_png() { "$PY" - "$1" <<'PYEOF'
import sys, numpy as np
from PIL import Image
a = np.asarray(Image.open(sys.argv[1]).convert('RGB'), np.float32)
m, s = a.mean(), a.std(); r, g, b = a[..., 0].mean(), a[..., 1].mean(), a[..., 2].mean()
brown = r > g + 25 and g > b + 15 and s < 35          # the old failure: a flat brown picture
ok = 15 < m < 245 and s > 20 and not brown
print('%dx%d mean %.0f std %.0f %s' % (a.shape[1], a.shape[0], m, s, 'OK' if ok else 'BAD')); sys.exit(0 if ok else 1)
PYEOF
}

FAIL=0; printf '%-8s %-34s %8s %8s  %s\n' family model time temp result
run() {   # family model w h prompt
  local fam=$1 id=$2 w=$3 h=$4 pr=$5
  [ -n "$id" ] || { printf '%-8s %-34s %8s %8s  %s\n' "$fam" "-" "-" "-" "not installed"; return; }
  [ -z "$WANT" ] || [[ " $WANT " == *" $fam "* ]] || return
  setprefs "prompt=string:$pr" "model=string:$id" "seed=string:7" "w=int:$w" "h=int:$h" "count=int:1" "qwen_edit_on=boolean:false"
  local before; before=$(sh_app 'ls files/gallery | grep -c png'); local t0 tc0; t0=$(date +%s); tc0=$(temp)
  tap_generate || { printf '%-8s %-34s %8s %8s  %s\n' "$fam" "$id" "-" "-" "FAIL (no Generate button)"; FAIL=1; return; }
  local n=0 f=""
  while [ $n -lt 600 ]; do sleep 3; n=$((n + 3)); [ "$(sh_app 'ls files/gallery | grep -c png')" -gt "$before" ] && break; done
  local dt=$(( $(date +%s) - t0 )); f=$(sh_app 'ls -t files/gallery/*.png | head -1')
  if [ "$(sh_app 'ls files/gallery | grep -c png')" -le "$before" ]; then printf '%-8s %-34s %7ss %8s  %s\n' "$fam" "$id" "$dt" "-" "FAIL (no picture)"; FAIL=1; return; fi
  adb exec-out run-as $PKG cat "$f" > "$T/out.png"
  local res; res=$(check_png "$T/out.png") || FAIL=1
  printf '%-8s %-34s %7ss %3s->%-4s  %s\n' "$fam" "$id" "$dt" "$tc0" "$(temp)" "$res"
  sh_app "rm -f $f ${f%.png}.json"   # the test picture leaves the History again
}
run anima "$ANIMA" 512 768 "$P"
run moss  "$MOSS"  512 768 "$PS"
run qwen  "$QWEN"  512 768 "$PS"
run mage  "$MAGE"  768 1024 "$PS"
exit $FAIL
