"""Qwen-Image-2.1 VAE decoder for QNN, 4D-only graph: z [1,64,H,W] (already de-normalised: z*std+mean) -> img [1,4,16H,16W] (RGB + alpha). usage: python export_vae_qwen.py OUT.onnx H [W]
All 5D (T=1) modules are re-expressed on 4D tensors (HTP rejects rank-5 Sigmoid, l2_norm, reshape(-1) etc.); checked against vae.decode."""
import sys, os, torch, torch.nn.functional as F
import diffusers.models.autoencoders.autoencoder_kl_qwenimage21 as A
from diffusers import AutoencoderKLQwenImage21
out, H = sys.argv[1], int(sys.argv[2]); W = int(sys.argv[3]) if len(sys.argv) > 3 else H
def conv(self, x, cache_x=None): return F.conv2d(x, self.weight, self.bias, self.stride, (self._padding[2], self._padding[0]), self.dilation, self.groups)   # padding as a conv attribute, not a dynamic Pad op
def rms(self, x): g = self.gamma.reshape(-1, 1, 1); return x * torch.rsqrt(x.pow(2).sum(1, keepdim=True) + 1e-24) * self.scale * g + (self.bias if isinstance(self.bias, float) else self.bias.reshape(-1, 1, 1))
def attn(self, x):
    identity = x; b, c, h, w = [int(v) for v in x.shape]; y = self.norm(x)
    qkv = self.to_qkv(y).reshape(b, 3, c, h * w); q, k, v = (qkv[:, i].transpose(1, 2)[:, None] for i in range(3))
    a = F.scaled_dot_product_attention(q, k, v)[:, 0].transpose(1, 2).reshape(b, c, h, w); return self.proj(a) + identity
def resample(self, x, feat_cache=None, feat_idx=None): return self.resample(x)   # first frame: the temporal conv is skipped
def dupup(self, x, first_chunk=False):   # rank <= 4: channel gather + pixel_shuffle (DepthToSpace) instead of a 7-D reshape/permute
    ft, fs, oc = self.factor_t, self.factor_s, self.out_channels; t = ft - 1 if first_chunk else 0
    o = torch.arange(oc)[:, None, None]; a = torch.arange(fs)[None, :, None]; b_ = torch.arange(fs)[None, None, :]
    idx = ((o * (ft * fs * fs) + t * fs * fs + a * fs + b_) // self.repeats).reshape(-1)
    return F.pixel_shuffle(torch.index_select(x, 1, idx), fs)
A.QwenImage21CausalConv3d.forward = conv; A.QwenImage21RMS_norm.forward = rms; A.QwenImage21AttentionBlock.forward = attn; A.QwenImage21Resample.forward = resample; A.QwenImage21DupUp3D.forward = dupup
A.QwenImage21Upsample.forward = lambda self, x: F.interpolate(x, scale_factor=2.0, mode='nearest')   # nearest-exact x2 == nearest x2
vae = AutoencoderKLQwenImage21.from_pretrained("ref/vae_dir", torch_dtype=torch.float32).eval()
class D(torch.nn.Module):
    def __init__(s, v): super().__init__(); s.pq = v.post_quant_conv; s.dec = v.decoder
    def forward(s, z):
        st = int(os.environ.get("STAGE", "99"))
        if st == 99: return s.dec(s.pq(z), first_chunk=True)
        d = s.dec; x = d.conv_in(s.pq(z))
        if st >= 1: x = d.mid_block(x)
        for i, ub in enumerate(d.up_blocks):
            if st >= 2 + i: x = ub(x, first_chunk=True)
        if st >= 7: x = d.conv_out(d.nonlinearity(d.norm_out(x)))
        return x
m = D(vae).eval()
for p in m.parameters(): p.requires_grad_(False)
z = torch.randn(1, 64, H, W)
with torch.no_grad():
    ref = m(z)
    import importlib; full = None
print("out", tuple(ref.shape), "max", float(ref.abs().max()))
if os.environ.get("CHECK"):   # compare with the untouched decode (needs the original forwards: run in a fresh process without patches)
    ref.numpy().tofile(out + ".ref.bin"); z.numpy().tofile(out + ".z.bin"); sys.exit()
with torch.no_grad(): torch.onnx.export(m, z, out, input_names=["z"], output_names=["img"], opset_version=17, dynamo=False)
z.numpy().tofile(out + ".z.bin"); ref.numpy().tofile(out + ".ref.bin"); print("saved", out, os.path.getsize(out))
