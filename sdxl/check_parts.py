# Check parts.py against the diffusers UNet forward at a small size (parts in fp32, diffusers in bf16: the full UNet in fp32 does not fit in RAM).
# usage: python check_parts.py MODEL [LATENT_SIZE=32]
import sys, torch
from diffusers import UNet2DConditionModel
import parts as P

unet = UNet2DConditionModel.from_pretrained(sys.argv[1], subfolder='unet', torch_dtype=torch.float16).eval()
U = P.units_of(unet); cuts = P.default_cuts(U)
U, parts = P.make_parts(unet, cuts)
tot = 0
for i, (p, (a, b)) in enumerate(zip(parts, cuts)):
    n = P.params(p); tot += n
    print(f'part {i}: units {a}-{b-1} {p.names[0]}..{p.names[-1]}  {n/1e6:.0f}M  in={p.input_names} out={p.output_names}')
print('total', tot / 1e6, 'unet', P.params(unet) / 1e6)
g = torch.Generator().manual_seed(0)
S = int(sys.argv[2]) if len(sys.argv) > 2 else 32
x = torch.randn(1, 4, S, S, generator=g); ctx = torch.randn(1, 77, 2048, generator=g); pooled = torch.randn(1, 1280, generator=g)
t = torch.tensor([500.0]); tid = torch.tensor([[1024., 1024, 0, 0, 1024, 1024]])
emb = P.embed(unet, t, pooled, tid)
y = P.run_parts(parts, x, emb, ctx)
unet.bfloat16()
with torch.no_grad():
    r = unet(x.bfloat16(), t, encoder_hidden_states=ctx.bfloat16(), added_cond_kwargs={'text_embeds': pooled.bfloat16(), 'time_ids': tid.bfloat16()}).sample.float()
print('rel err parts(fp32) vs diffusers(bf16):', float((y - r).norm() / r.norm()), 'cos', float(torch.nn.functional.cosine_similarity(y.flatten(), r.flatten(), 0)))
