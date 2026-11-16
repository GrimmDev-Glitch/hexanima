# Qwen-Image VAE (the Wan 2.1 VAE Anima uses, Apache-2.0) encoder for ONE picture as a plain 2D network -> ONNX at a fixed tile size.
# For a single frame the causal 3D convolutions only see their last temporal slice and the temporal downsamples are skipped, so every Conv3d becomes a Conv2d.
# usage: python export_vae_enc.py VAE_DIR OUT.onnx [TILE=384]     (VAE_DIR: diffusers AutoencoderKLQwenImage folder). Output: the latent mean [1,16,T/8,T/8], not normalised.
import sys, torch, torch.nn as nn, torch.nn.functional as F
from diffusers import AutoencoderKLQwenImage
import diffusers.models.autoencoders.autoencoder_kl_qwenimage as M

class Enc2D(nn.Module):
    def __init__(self, vae):
        super().__init__(); self.vae = vae
    @staticmethod
    def conv(c, x):
        p = c._padding[0] if hasattr(c, '_padding') else c.padding[1]
        return F.conv2d(x, c.weight[:, :, -1], c.bias, stride=c.stride[1:], padding=p)
    @staticmethod
    def norm(n, x):
        return F.normalize(x, dim=1) * n.scale * n.gamma.reshape(1, -1, 1, 1)
    def res(self, r, x):
        h = x if isinstance(r.conv_shortcut, nn.Identity) else self.conv(r.conv_shortcut, x)
        x = self.conv(r.conv1, F.silu(self.norm(r.norm1, x)))
        x = self.conv(r.conv2, F.silu(self.norm(r.norm2, x)))
        return x + h
    def attn(self, a, x):
        b, c, hh, ww = x.shape
        q, k, v = a.to_qkv(self.norm(a.norm, x)).reshape(b, 3, c, hh * ww).unbind(1)      # [b][c][n] each
        q, k, v = q.transpose(1, 2), k.transpose(1, 2), v.transpose(1, 2)               # [b][n][c]
        w = torch.softmax(q @ k.transpose(1, 2) / (c ** 0.5), dim=-1)
        return a.proj((w @ v).transpose(1, 2).reshape(b, c, hh, ww)) + x
    def forward(self, x):
        e = self.vae.encoder
        x = self.conv(e.conv_in, x)
        for l in e.down_blocks:
            if isinstance(l, M.QwenImageResidualBlock): x = self.res(l, x)
            elif isinstance(l, M.QwenImageAttentionBlock): x = self.attn(l, x)
            else: x = l.resample(x)                                                    # ZeroPad2d + Conv2d stride 2 (the temporal conv is skipped on the first frame)
        m = e.mid_block
        x = self.res(m.resnets[0], x)
        for a, r in zip(m.attentions, m.resnets[1:]):
            if a is not None: x = self.attn(a, x)
            x = self.res(r, x)
        x = self.conv(e.conv_out, F.silu(self.norm(e.norm_out, x)))
        return self.conv(self.vae.quant_conv, x)[:, :16]

if __name__ == '__main__':
    vae = AutoencoderKLQwenImage.from_pretrained(sys.argv[1], torch_dtype=torch.float32).eval()
    T = int(sys.argv[3]) if len(sys.argv) > 3 else 384
    net = Enc2D(vae).eval(); x = torch.rand(1, 3, T, T) * 2 - 1
    with torch.no_grad():
        ref = vae.encode(x.unsqueeze(2)).latent_dist.mean[:, :, 0]; out = net(x)
    print('shape', tuple(out.shape), 'max abs diff vs diffusers', float((ref - out).abs().max()), 'ref abs mean', float(ref.abs().mean()))
    print('latents_mean', vae.config.latents_mean); print('latents_std', vae.config.latents_std)
    torch.onnx.export(net, x, sys.argv[2], input_names=['image'], output_names=['latent'], opset_version=17, dynamo=False)
    print('wrote', sys.argv[2])
