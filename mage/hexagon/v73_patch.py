# Port the upstream Mage-Flow Hexagon kernel (mage/hexagon/mega, V69) to V73, the same way qwen/ and moss/ were ported:
#  - V73 HMX fp16 stores pass through the convert unit, which reads a scale/bias block from VTCM (hmxacc.S + hmxscale.c, copied from qwen/)
#  - every VTCM reservation that is followed by HMX work initialises that block in the last 4 KB of the reservation
#  - build.sh takes ARCH=v69|v73 and uses the SDK's qhl_hmx
# usage: python3 mage/hexagon/v73_patch.py   (idempotent)
import os, re, shutil
D = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'mega')
Q = os.path.join(os.path.dirname(D), '..', '..', 'qwen', 'hexagon', 'mega')
for f in ('hmxacc.S', 'hmxscale.c'):
    shutil.copyfile(os.path.join(Q, f), os.path.join(D, f))

def edit(name, fn):
    p = os.path.join(D, name); s = open(p, encoding='utf-8').read(); t = fn(s)
    if t != s: open(p, 'w', encoding='utf-8', newline='\n').write(t); print('patched', name)

DECL = 'void hmx_scale_init(uint8_t*);'
def add_decl(s):
    if 'hmx_scale_init(uint8_t' in s: return s
    return re.sub(r'(void hmx_store\(uint8_t ?\*, ?uint32_t\);)', r'\1\n' + DECL, s, count=1)

def init_after(var_size):   # insert hmx_scale_init(<ptr> + size - 4096) right after "<ptr> = ...get_vtcm_ptr(...);"
    def fn(s):
        out = []
        for line in s.split('\n'):
            out.append(line)
            m = re.search(r'(\w+) = \(uint8_t \*\)HAP_compute_res_attr_get_vtcm_ptr\(&\w+\);', line)
            if m and 'hmx_scale_init' not in line:
                v = m.group(1); size = var_size.get(v)
                if size: out.append(re.match(r'\s*', line).group(0) + 'hmx_scale_init(%s + (%s) - 4096);' % (v, size))
        return '\n'.join(out)
    return fn

edit('gemm2.h', lambda s: s if 'hmx_scale_init' in s else s.replace('void hmx_store(uint8_t *, uint32_t);', 'void hmx_store(uint8_t *, uint32_t);\nvoid hmx_scale_init(uint8_t *vtcm);', 1))
edit('blk.c', lambda s: s if 'hmx_scale_init(g_vt' in s else s.replace('g_vt = (uint8_t *)HAP_compute_res_attr_get_vtcm_ptr(&attr);', 'g_vt = (uint8_t *)HAP_compute_res_attr_get_vtcm_ptr(&attr);\n  hmx_scale_init(g_vt + (8u << 20) - 4096);', 1))
for f in ('gemm.c', 'gtest.c', 'stream.c'):
    edit(f, lambda s: init_after({'vt': '8u << 20'})(add_decl(s)) if 'hmx_scale_init(vt' not in s else s)
def mega_imp(s):
    if 'hmx_scale_init(vt' in s: return s
    s = add_decl(s)
    return init_after({'vt': '1u << 20'})(s)   # mega_probe and mega_dump reserve 1 MB
edit('mega_imp.c', mega_imp)

edit('build.sh', lambda s: s if 'ARCH' in s else s
     .replace('S=/opt/hexagon/6.7.0.0; T=$S/tools/HEXAGON_Tools/21.0.04/Tools; cd /work; mkdir -p out',
              'V=${ARCH:-v69}; [ "$V" = v69 ] || [ "$V" = v73 ] || { echo "unsupported ARCH: $V"; exit 2; }\nSK=out/libmega_skel.so; [ "$V" = v69 ] || SK=out/libmega_skel_$V.so\nS=/opt/hexagon/6.7.0.0; T=$S/tools/HEXAGON_Tools/21.0.04/Tools; cd /work; mkdir -p out out/dsp_$V')
     .replace('computev69', 'compute$V').replace('-Iqhl_hmx/inc/qhblas_hmx', '-I$S/libs/qhl_hmx/inc/qhblas_hmx')
     .replace('-mv69 -mhvx', '-m$V -mhvx')
     .replace('blk.c out/mega_skel.c qhl_hmx/src/qhblas_hmx/*.c qhl_hmx/src/qhblas_hmx/*.S hmx8.S hmxacc.S; do $T/bin/hexagon-clang $HF -c $f -o out/$(basename $f).o; done',
              'blk.c hmxscale.c out/mega_skel.c $S/libs/qhl_hmx/src/qhblas_hmx/*.c $S/libs/qhl_hmx/src/qhblas_hmx/*.S hmx8.S hmxacc.S; do $T/bin/hexagon-clang $HF -c $f -o out/dsp_$V/$(basename $f).o; done')
     .replace('L69=$T/target/hexagon/lib/v69/G0/pic; $T/bin/hexagon-clang -G0 -mv69 -shared -fPIC -Wl,-Bsymbolic -nodefaultlibs -o out/libmega_skel.so out/*.o -Wl,--start-group $L69/libgcc.a -Wl,--end-group',
              'LG=$T/target/hexagon/lib/$V/G0/pic; $T/bin/hexagon-clang -G0 -m$V -shared -fPIC -Wl,-Bsymbolic -nodefaultlibs -o $SK out/dsp_$V/*.o -Wl,--start-group $LG/libgcc.a -Wl,--end-group')
     .replace('ls -la out/libmega_skel.so out/mega_test', 'ls -la $SK out/mega_test'))
