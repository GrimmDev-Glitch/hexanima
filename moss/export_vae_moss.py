"""FLUX.2 small decoder tile for QNN: z [1,32,T,T] (already BN-denormalised + unpatchified) -> [1,3,8T,8T]. usage: python export_vae_moss.py OUT.onnx T"""
import sys, torch
from diffusers import AutoencoderKLFlux2
out, T = sys.argv[1], int(sys.argv[2]); TW = int(sys.argv[3]) if len(sys.argv) > 3 else T
vae = AutoencoderKLFlux2.from_pretrained("vae", torch_dtype=torch.float32).eval()
class D(torch.nn.Module):
    def __init__(s, v): super().__init__(); s.pq = v.post_quant_conv; s.dec = v.decoder
    def forward(s, z): return s.dec(s.pq(z))
m = D(vae).eval(); z = torch.randn(1, 32, T, TW)
import os
if not os.environ.get("NOREF"):
    with torch.no_grad(): ref = m(z)
    print("out", tuple(ref.shape), float(ref.abs().max()))
torch.onnx.export(m, z, out, input_names=["z"], output_names=["img"], opset_version=17, dynamo=False)
z.numpy().tofile(out + ".z.bin")
if not os.environ.get("NOREF"): ref.numpy().tofile(out + ".ref.bin")
