"""Qwen-Image-2.1 VAE ENCODER for QNN, 4D-only graph: img [1,4,16H,16W] in [-1,1] (RGBA) -> mean latent [1,64,H,W] (un-normalised; host does (z-mean)/std). usage: python export_vae_enc.py OUT.onnx H [W]   (H,W = latent size)
All 5D (T=1) modules are re-expressed on 4D tensors (HTP rejects rank-5 Sigmoid, l2_norm, reshape(-1) etc.); checked against vae.decode."""
import sys, os, torch, torch.nn.functional as F
import diffusers.models.autoencoders.autoencoder_kl_qwenimage21 as A
from diffusers import AutoencoderKLQwenImage21
out, H = sys.argv[1], int(sys.argv[2]); W = int(sys.argv[3]) if len(sys.argv) > 3 else H
def conv(self, x, cache_x=None): return F.conv2d(x, self.weight, self.bias, self.stride, (self._padding[2], self._padding[0]), self.dilation, self.groups)   # padding as a conv attribute, not a dynamic Pad op
def rms(self, x):   # normalize over channels in a fp16-safe way: scale by the channel max first (sum of squares of raw activations up to ~300 overflows fp16)
    g = self.gamma.reshape(-1, 1, 1); sc = x.abs().amax(1, keepdim=True).clamp(min=1e-4); y = x / sc
    return y * torch.rsqrt(y.pow(2).sum(1, keepdim=True) + 1e-12) * self.scale * g + (self.bias if isinstance(self.bias, float) else self.bias.reshape(-1, 1, 1))
def attn(self, x):
    identity = x; b, c, h, w = [int(v) for v in x.shape]; y = self.norm(x)
    qkv = self.to_qkv(y).reshape(b, 3, c, h * w); q, k, v = (qkv[:, i].transpose(1, 2)[:, None] for i in range(3))
    a = F.scaled_dot_product_attention(q, k, v)[:, 0].transpose(1, 2).reshape(b, c, h, w); return self.proj(a) + identity
def avgdown(self, x):   # image case: T=1 padded with a zero frame in front, then space-to-depth + group mean == ONE strided conv (weights built in numpy: a traced python loop made 1000+ ONNX nodes)
    import numpy as np
    ft, fs, C, O, gs = self.factor_t, self.factor_s, self.in_channels, self.out_channels, self.group_size
    M = np.zeros((O, C * fs * fs), np.float32)
    for j in range(C * ft * fs * fs):
        c, t, ab = j // (ft * fs * fs), (j // (fs * fs)) % ft, j % (fs * fs)
        if t == ft - 1: M[j // gs, c * fs * fs + ab] += 1.0 / gs
    return F.conv2d(x, torch.from_numpy(M).view(O, C, fs, fs).to(x.dtype), stride=fs)
def resample(self, x, feat_cache=None, feat_idx=None): return self.resample(x)   # first frame: the temporal conv is skipped
def dupup(self, x, first_chunk=False):   # rank <= 4: channel gather + pixel_shuffle (DepthToSpace) instead of a 7-D reshape/permute
    ft, fs, oc = self.factor_t, self.factor_s, self.out_channels; t = ft - 1 if first_chunk else 0
    o = torch.arange(oc)[:, None, None]; a = torch.arange(fs)[None, :, None]; b_ = torch.arange(fs)[None, None, :]
    idx = ((o * (ft * fs * fs) + t * fs * fs + a * fs + b_) // self.repeats).reshape(-1)
    return F.pixel_shuffle(torch.index_select(x, 1, idx), fs)
A.QwenImage21CausalConv3d.forward = conv; A.QwenImage21RMS_norm.forward = rms; A.QwenImage21AttentionBlock.forward = attn; A.QwenImage21Resample.forward = resample; A.QwenImage21DupUp3D.forward = dupup
A.QwenImage21AvgDown3D.forward = avgdown
A.QwenImage21Upsample.forward = lambda self, x: F.interpolate(x, scale_factor=2.0, mode='nearest')   # nearest-exact x2 == nearest x2
vae = AutoencoderKLQwenImage21.from_pretrained("ref/vae_dir", torch_dtype=torch.float32).eval()
class E(torch.nn.Module):
    def __init__(s, v): super().__init__(); s.enc = v.encoder; s.qc = v.quant_conv
    def forward(s, x):
        st = int(os.environ.get("STAGE", "99"))
        if st == 99: return s.qc(s.enc(x))[:, :64]
        e = s.enc; x = e.conv_in(x)
        for i, b in enumerate(e.down_blocks):
            if st >= 1 + i: x = b(x)
        if st >= 1 + len(e.down_blocks): x = e.mid_block(x)
        return x
m = E(vae).eval()
for p in m.parameters(): p.requires_grad_(False)
x = torch.randn(1, 4, 16 * H, 16 * W).clamp(-1, 1)
with torch.no_grad(): ref = m(x)
print("out", tuple(ref.shape), "max", float(ref.abs().max()))
if os.environ.get("CHECK"): ref.numpy().tofile(out + ".ref.bin"); x.numpy().tofile(out + ".x.bin"); sys.exit()
with torch.no_grad(): torch.onnx.export(m, x, out, input_names=["img"], output_names=["z"], opset_version=17, dynamo=False)
x.numpy().tofile(out + ".x.bin"); ref.numpy().tofile(out + ".ref.bin"); print("saved", out, os.path.getsize(out))
