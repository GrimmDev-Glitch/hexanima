"""Normalize common Qwen-Image Diffusers/PEFT LoRA tensor names.

The runtime exporter uses canonical module names without a model-root prefix:
``transformer_blocks.0.attn.to_q.lora_A.weight``. Civitai files commonly add
``diffusion_model.`` or ``transformer.`` and PEFT may insert ``.default``.
"""
from __future__ import annotations

import json
import re


_ROOTS = (
    "base_model.model.",
    "base_model.",
    "module.",
    "model.",
    "diffusion_model.",
    "transformer.",
)
_SUPPORTED_MODULES = (
    "attn.to_q",
    "attn.to_k",
    "attn.to_v",
    "attn.to_out.0",
    "img_mlp.gate_up",
    "img_mlp.gate_layer",
    "img_mlp.proj",
    "img_mlp.out",
)
_SUPPORTED_GLOBALS = (
    "img_in",
    "proj_out",
    "time_text_embed.timestep_embedder.linear_1",
    "time_text_embed.timestep_embedder.linear_2",
    "modulation.1",
    "norm_out.linear",
    "txt_in.in_layer",
    "txt_in.out_layer",
)


def canonical_key(name: str) -> str:
    """Return a rootless, adapter-name-independent Safetensors key."""
    key = name
    changed = True
    while changed:
        changed = False
        for root in _ROOTS:
            if key.startswith(root):
                key = key[len(root):]
                changed = True
                break
    key = key.replace(".default.", ".")
    if key.endswith(".lora_down.weight"):
        key = key[:-len(".lora_down.weight")] + ".lora_A.weight"
    elif key.endswith(".lora_up.weight"):
        key = key[:-len(".lora_up.weight")] + ".lora_B.weight"
    elif key.endswith(".lora_alpha"):
        key = key[:-len(".lora_alpha")] + ".alpha"
    return key


class KeyIndex:
    """Map normalized keys to original Safetensors names without loading tensors."""

    def __init__(self, names, metadata=None):
        self.names: dict[str, str] = {}
        self.metadata_alpha = None
        if metadata and metadata.get("lora_adapter_metadata"):
            config = json.loads(metadata["lora_adapter_metadata"])
            if any(key.endswith(".alpha_pattern") and value for key, value in config.items()):
                raise ValueError("per-module LoRA alpha_pattern is not supported")
            alphas = {float(value) for key, value in config.items() if key.endswith(".lora_alpha")}
            if len(alphas) > 1:
                raise ValueError("conflicting LoRA alpha values in metadata")
            self.metadata_alpha = next(iter(alphas)) if alphas else None
        for name in names:
            normalized = canonical_key(name)
            previous = self.names.get(normalized)
            if previous is not None and previous != name:
                raise ValueError(
                    f"ambiguous LoRA tensor names normalize to {normalized}: "
                    f"{previous} and {name}"
                )
            self.names[normalized] = name

    def source(self, normalized: str) -> str | None:
        return self.names.get(canonical_key(normalized))

    def pair(self, module: str, load_tensor):
        """Load an A/B pair and its optional alpha; return None for untouched modules."""
        module = canonical_key(module)
        a_name = self.source(module + ".lora_A.weight")
        b_name = self.source(module + ".lora_B.weight")
        if a_name is None and b_name is None:
            return None
        if a_name is None or b_name is None:
            missing = "A" if a_name is None else "B"
            raise ValueError(f"incomplete LoRA pair for {module}: missing {missing} tensor")
        a, b = load_tensor(a_name), load_tensor(b_name)
        if len(a.shape) != 2 or len(b.shape) != 2 or a.shape[0] != b.shape[1]:
            raise ValueError(
                f"invalid LoRA shapes for {module}: A={tuple(a.shape)} B={tuple(b.shape)}"
            )
        alpha_name = self.source(module + ".alpha")
        alpha = (float(load_tensor(alpha_name).item()) if alpha_name is not None
                 else self.metadata_alpha if self.metadata_alpha is not None else float(a.shape[0]))
        if alpha <= 0:
            raise ValueError(f"invalid LoRA alpha for {module}: {alpha}")
        return a, b, alpha


def adapter_multiplier(strength: float, alpha: float, rank: int) -> float:
    """Return the user strength multiplied by the adapter's alpha/rank factor."""
    if rank <= 0 or alpha <= 0:
        raise ValueError(f"invalid LoRA scaling: alpha={alpha}, rank={rank}")
    return float(strength) * float(alpha) / rank


def validate_qwen21(index: KeyIndex, block_count: int = 32) -> None:
    """Reject incomplete, unsupported, or larger Qwen transformer LoRAs up front."""
    parts: dict[str, set[str]] = {}
    blocks: set[int] = set()
    for key in index.names:
        if key.endswith(".lora_A.weight"):
            module, part = key[:-len(".lora_A.weight")], "A"
        elif key.endswith(".lora_B.weight"):
            module, part = key[:-len(".lora_B.weight")], "B"
        else:
            continue
        parts.setdefault(module, set()).add(part)
        match = re.match(r"^transformer_blocks\.(\d+)\.(.+)$", module)
        if match:
            blocks.add(int(match.group(1)))
    if not blocks:
        raise ValueError("no Qwen transformer_blocks LoRA tensors were found")
    if max(blocks) >= block_count:
        raise ValueError(
            f"LoRA contains transformer block {max(blocks)}; this exporter supports "
            f"the {block_count}-block Qwen-Image 2.1 Turbo pack only"
        )
    unsupported = []
    incomplete = []
    for module, pair in parts.items():
        if pair != {"A", "B"}:
            incomplete.append(module)
        match = re.match(r"^transformer_blocks\.(\d+)\.(.+)$", module)
        if not (match and match.group(2) in _SUPPORTED_MODULES) and module not in _SUPPORTED_GLOBALS:
            unsupported.append(module)
    if incomplete:
        raise ValueError("incomplete Qwen LoRA pairs: " + ", ".join(sorted(incomplete)[:4]))
    if unsupported:
        raise ValueError(
            "Qwen LoRA contains targets not implemented by the V73 block exporter: "
            + ", ".join(sorted(unsupported)[:6])
        )
    if not parts:
        raise ValueError("no complete LoRA pairs target supported Qwen image transformer layers")
