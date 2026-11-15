# 4x-UltraSharp (Kim2091, CC BY-NC-SA 4.0; ESRGAN RRDBNet, 23 blocks, old "model.N" key layout) -> ONNX with the same interface as the
# Real-ESRGAN x4v3 context: input "image" [1,3,T,T] in 0..1 RGB -> "upscaled_image" [1,3,4T,4T].
# usage (torch venv): python export_ultrasharp.py dl/4x-UltraSharp.safetensors 64 dl/ultrasharp.onnx   (traced small with dynamic H, W: tracing at 256 needs >7 GB; the QNN converter fixes the tile size)
import sys, torch, torch.nn as nn, torch.nn.functional as F
from safetensors.torch import load_file

class RDB(nn.Module):
    def __init__(s, nf=64, gc=32):
        super().__init__()
        s.c = nn.ModuleList([nn.Conv2d(nf + i * gc, gc if i < 4 else nf, 3, 1, 1) for i in range(5)])
    def forward(s, x):
        feats = [x]
        for i, c in enumerate(s.c):
            y = c(torch.cat(feats, 1))
            if i < 4: feats.append(F.leaky_relu(y, 0.2))
        return y * 0.2 + x

class RRDB(nn.Module):
    def __init__(s):
        super().__init__(); s.r = nn.ModuleList([RDB() for _ in range(3)])
    def forward(s, x):
        y = x
        for r in s.r: y = r(y)
        return y * 0.2 + x

class Net(nn.Module):
    def __init__(s, nb=23):
        super().__init__()
        s.first = nn.Conv2d(3, 64, 3, 1, 1); s.body = nn.ModuleList([RRDB() for _ in range(nb)]); s.trunk = nn.Conv2d(64, 64, 3, 1, 1)
        s.up1 = nn.Conv2d(64, 64, 3, 1, 1); s.up2 = nn.Conv2d(64, 64, 3, 1, 1); s.hr = nn.Conv2d(64, 64, 3, 1, 1); s.last = nn.Conv2d(64, 3, 3, 1, 1)
    def forward(s, x):
        f = s.first(x); y = f
        for b in s.body: y = b(y)
        f = f + s.trunk(y)
        f = F.leaky_relu(s.up1(F.interpolate(f, scale_factor=2, mode='nearest')), 0.2)
        f = F.leaky_relu(s.up2(F.interpolate(f, scale_factor=2, mode='nearest')), 0.2)
        return s.last(F.leaky_relu(s.hr(f), 0.2))

def load(path):
    sd = load_file(path); nb = 1 + max(int(k.split('.')[3]) for k in sd if k.startswith('model.1.sub.') and 'RDB' in k)
    m = Net(nb); out = {}
    for k, v in sd.items():
        p = k.split('.')
        if p[1] == '0': out['first.' + p[2]] = v
        elif p[1] == '1':
            if 'RDB' in k: out['body.%s.r.%d.c.%d.%s' % (p[3], int(p[4][3]) - 1, int(p[5][4]) - 1, p[7])] = v   # model.1.sub.B.RDBr.convc.0.w
            else: out['trunk.' + p[4]] = v                                                                # model.1.sub.<nb>.w
        else: out[{'3': 'up1', '6': 'up2', '8': 'hr', '10': 'last'}[p[1]] + '.' + p[2]] = v
    m.load_state_dict(out, strict=True); return m.eval()

if __name__ == '__main__':
    src, T, dst = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    m = load(src); x = torch.rand(1, 3, T, T)
    with torch.no_grad(): y = m(x)
    print('blocks', len(m.body), 'out', tuple(y.shape))
    torch.onnx.export(m, x, dst, input_names=['image'], output_names=['upscaled_image'], opset_version=17, dynamo=False, dynamic_axes={'image': {2: 'h', 3: 'w'}, 'upscaled_image': {2: 'H', 3: 'W'}})
    print('wrote', dst)
