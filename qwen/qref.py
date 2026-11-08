"""Load Qwen-Image-2.1-Turbo GGUF (dequantized) into diffusers QwenImage21Transformer2DModel (first N layers)."""
import json, torch, gguf, numpy as np
from gguf.quants import dequantize
from diffusers import QwenImage21Transformer2DModel as M
GG='models/qwen-image-2.1-turbo-Q5_K_S.gguf'
CFG='ref/Qwen-Image-2.1-Turbo/transformer/config.json'
P='model.diffusion_model.'
_r=None
def reader():
    global _r
    if _r is None: _r=gguf.GGUFReader(GG)
    return _r
def tensors():
    return {t.name[len(P):]:t for t in reader().tensors}
def deq(t):
    d=t.data
    if t.tensor_type in (gguf.GGMLQuantizationType.F32,gguf.GGMLQuantizationType.F16): a=np.asarray(d,dtype=np.float32)
    elif t.tensor_type==gguf.GGMLQuantizationType.BF16:
        a=(np.ascontiguousarray(d).view(np.uint16).astype(np.uint32)<<16).view(np.float32)
    else: a=dequantize(d,t.tensor_type)
    return torch.from_numpy(np.ascontiguousarray(a)).reshape([int(x) for x in t.shape[::-1]])
LORA='loras/lightning_v2rl_r64.safetensors'
_l=None
def lora_delta(k):
    """dW (out,in) for param key k like 'transformer_blocks.0.attn.to_q.weight', or None"""
    global _l
    from safetensors import safe_open
    if _l is None: _l=safe_open(LORA,'pt')
    b='transformer.'+k[:-len('.weight')]
    try: u=_l.get_tensor(b+'.lora_up.weight').float(); d=_l.get_tensor(b+'.lora_down.weight').float()
    except Exception: return None
    return (u@d)*(float(_l.get_tensor(b+'.alpha'))/d.shape[0])
def load(nl=2, gate_first=True, lora=False):
    cfg={k:v for k,v in json.load(open(CFG)).items() if not k.startswith('_')}; cfg['num_layers']=nl
    m=M.from_config(cfg).float(); sd={}
    T=tensors()
    for k in m.state_dict():
        if k.endswith('img_mlp.proj.weight') or k.endswith('img_mlp.gate_layer.weight'):
            base=k.rsplit('img_mlp.',1)[0]+'img_mlp.gate_up.weight'
            w=deq(T[base]); g,u=w[:12288],w[12288:]
            if not gate_first: g,u=u,g
            sd[k]=u if k.endswith('proj.weight') else g
        else: sd[k]=deq(T[k])
        if lora and (dw:=lora_delta(k)) is not None: sd[k]=sd[k]+dw
    print(m.load_state_dict(sd,strict=True)); return m.eval()
if __name__=='__main__':
    m=load(2)
    for k,v in m.state_dict().items():
        if 'blocks.1.' in k or 'blocks.0.' in k or 'mod' in k: print(k,tuple(v.shape),float(v.std()))
