import inspect
from diffusers.models.autoencoders.autoencoder_kl_qwenimage21 import QwenImage21ResidualUpBlock, QwenImage21ResidualBlock

print(inspect.getsource(QwenImage21ResidualUpBlock.forward))
print(inspect.getsource(QwenImage21ResidualBlock.forward))
