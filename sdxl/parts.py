# SDXL UNet cut into consecutive parts for the NPU (one QNN context each) + an fp32 reference that runs the same parts.
# The UNet is a flat list of units (conv_in, resnets, transformers, down/upsamplers, out); a part is a range of units. Tensors between parts:
#   h (activation, input "h", output "hout"), emb [1,1280] (time + pooled text + size embedding, computed outside), ctx [1,77,2048], skips "s<k>".
# Weights stay fp16 in RAM; each part is cast to fp32 only while it runs (the whole UNet in fp32 is 10 GB, WSL has 7 GB).
import torch, torch.nn as nn


def units_of(unet):
    U = [('conv_in', unet.conv_in, 'push')]
    for bi, b in enumerate(unet.down_blocks):
        att = getattr(b, 'attentions', None)
        for j, r in enumerate(b.resnets):
            U.append((f'd{bi}r{j}', r, None if att else 'push'))
            if att: U.append((f'd{bi}a{j}', att[j], 'push'))
        if b.downsamplers: U.append((f'd{bi}ds', b.downsamplers[0], 'push'))
    m = unet.mid_block
    U += [('m_r0', m.resnets[0], None), ('m_a0', m.attentions[0], None), ('m_r1', m.resnets[1], None)]
    for bi, b in enumerate(unet.up_blocks):
        att = getattr(b, 'attentions', None)
        for j, r in enumerate(b.resnets):
            U.append((f'u{bi}r{j}', r, 'pop'))
            if att: U.append((f'u{bi}a{j}', att[j], None))
        if b.upsamplers: U.append((f'u{bi}us', b.upsamplers[0], None))
    U.append(('out', nn.Sequential(unet.conv_norm_out, nn.SiLU(), unet.conv_out), None))
    return U


def kind(name):
    if name in ('conv_in', 'out') or name.endswith('ds') or name.endswith('us'): return 'plain'
    return 'attn' if name[-2] == 'a' else 'res'


def plan_io(U, cuts):
    """cuts: unit-index ranges [(a,b),...]. Returns per part: needs emb / ctx, skip inputs (in pop order), skip outputs."""
    stack, push_part, k = [], {}, 0
    io = [dict(emb=False, ctx=False, sin=[], sout=[]) for _ in cuts]
    part_of = lambda u: next(p for p, (a, b) in enumerate(cuts) if a <= u < b)
    for u, (name, mod, op) in enumerate(U):
        p = part_of(u); kd = kind(name)
        if kd == 'res': io[p]['emb'] = True
        if kd == 'attn': io[p]['ctx'] = True
        if op == 'pop':
            s = stack.pop()
            if push_part[s] != p:
                io[p]['sin'].append(s)
                if s not in io[push_part[s]]['sout']: io[push_part[s]]['sout'].append(s)
        if op == 'push': stack.append(k); push_part[k] = p; k += 1
    return io


class Part(nn.Module):
    def __init__(self, U, a, b, io, push_base):
        super().__init__()
        self.names = [U[u][0] for u in range(a, b)]; self.ops = [U[u][2] for u in range(a, b)]
        self.mods = nn.ModuleList([U[u][1] for u in range(a, b)])
        self.io = io; self.push_base = push_base
        self.input_names = ['h'] + (['emb'] if io['emb'] else []) + (['ctx'] if io['ctx'] else []) + [f's{s}' for s in io['sin']]
        self.output_names = ['hout'] + [f's{s}' for s in io['sout']]

    def forward(self, *args):
        d = dict(zip(self.input_names, args)); h = d['h']; emb = d.get('emb'); ctx = d.get('ctx')
        local, own, sin, k = {}, [], list(self.io['sin']), self.push_base
        for name, op, m in zip(self.names, self.ops, self.mods):
            kd = kind(name)
            if op == 'pop':
                if own: s = local[own.pop()]
                else: s = d[f's{sin.pop(0)}']
                h = torch.cat([h, s], 1)
            if kd == 'res': h = m(h, emb)
            elif kd == 'attn': h = m(h, encoder_hidden_states=ctx, return_dict=False)[0]
            else: h = m(h)
            if op == 'push': local[k] = h; own.append(k); k += 1
        return tuple([h] + [local[s] for s in self.io['sout']])


def make_parts(unet, cuts):
    U = units_of(unet); io = plan_io(U, cuts)
    base, parts = 0, []
    for (a, b), pio in zip(cuts, io):
        parts.append(Part(U, a, b, pio, base))
        base += sum(1 for u in range(a, b) if U[u][2] == 'push')
    return U, parts


def params(m): return sum(p.numel() for p in m.parameters())


def default_cuts(U, max_params=420e6):
    """Greedy: close a part before it would pass max_params (a 1280-wide 10-layer transformer is ~350M)."""
    cuts, a, acc = [], 0, 0
    for u, (n, m, op) in enumerate(U):
        p = params(m)
        if acc and acc + p > max_params: cuts.append((a, u)); a, acc = u, 0
        acc += p
    cuts.append((a, len(U)))
    return cuts


def embed(unet, t, pooled, time_ids):
    """emb [B,1280] in fp32 (the phone runner does the same in C++)."""
    with torch.no_grad():
        tp = unet.time_proj(t.reshape(-1).float()).float()
        te = unet.time_embedding.float()(tp)
        tid = unet.add_time_proj(time_ids.flatten().float()).reshape(time_ids.shape[0], -1)
        ae = unet.add_embedding.float()(torch.cat([pooled.float(), tid], -1))
        unet.time_embedding.half(); unet.add_embedding.half()
    return te + ae


def run_parts(parts, x, emb, ctx, dump=None):
    """fp32 forward through the parts (batch 1). dump(i, names, tensors) receives every part's inputs."""
    env = {'h': x, 'emb': emb, 'ctx': ctx}
    for i, p in enumerate(parts):
        ins = [env[n] for n in p.input_names]
        if dump: dump(i, p.input_names, ins)
        p.float()
        with torch.no_grad(): outs = p(*ins)
        p.half()
        for n, o in zip(p.output_names, outs): env['h' if n == 'hout' else n] = o
    return env['h']
