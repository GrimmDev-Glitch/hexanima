import unittest

from lora_keys import KeyIndex, adapter_multiplier, canonical_key, validate_qwen21


class Tensor:
    def __init__(self, shape, value=0):
        self.shape = shape
        self.value = value

    def item(self):
        return self.value


class QwenLoraKeyTests(unittest.TestCase):
    def test_applies_user_strength_times_alpha_over_rank(self):
        self.assertEqual(adapter_multiplier(0.75, 16, 8), 1.5)
        self.assertEqual(adapter_multiplier(0.6, 8, 8), 0.6)
        with self.assertRaisesRegex(ValueError, "invalid LoRA scaling"):
            adapter_multiplier(1.0, 4, 0)

    def test_strips_diffusers_peft_prefixes_and_default_slot(self):
        self.assertEqual(
            canonical_key(
                "base_model.model.transformer.transformer_blocks.3.attn.to_q.lora_A.default.weight"
            ),
            "transformer_blocks.3.attn.to_q.lora_A.weight",
        )

    def test_maps_kohya_down_up_and_defaults_alpha_to_rank(self):
        keys = KeyIndex([
            "diffusion_model.transformer_blocks.2.attn.to_k.lora_down.weight",
            "transformer.transformer_blocks.2.attn.to_k.lora_up.weight",
        ])
        tensors = {
            "diffusion_model.transformer_blocks.2.attn.to_k.lora_down.weight": Tensor((8, 64)),
            "transformer.transformer_blocks.2.attn.to_k.lora_up.weight": Tensor((64, 8)),
        }
        a, b, alpha = keys.pair(
            "transformer_blocks.2.attn.to_k", tensors.__getitem__
        )
        self.assertEqual((a.shape, b.shape, alpha), ((8, 64), (64, 8), 8.0))

    def test_reads_explicit_alpha(self):
        keys = KeyIndex([
            "transformer_blocks.0.attn.to_q.lora_A.weight",
            "transformer_blocks.0.attn.to_q.lora_B.weight",
            "transformer_blocks.0.attn.to_q.lora_alpha",
        ])
        tensors = {
            "transformer_blocks.0.attn.to_q.lora_A.weight": Tensor((4, 16)),
            "transformer_blocks.0.attn.to_q.lora_B.weight": Tensor((32, 4)),
            "transformer_blocks.0.attn.to_q.lora_alpha": Tensor((), 16),
        }
        self.assertEqual(keys.pair("transformer_blocks.0.attn.to_q", tensors.__getitem__)[2], 16.0)

    def test_reads_peft_alpha_from_metadata_when_no_alpha_tensor_exists(self):
        names = ["transformer_blocks.0.attn.to_q.lora_A.weight", "transformer_blocks.0.attn.to_q.lora_B.weight"]
        index = KeyIndex(names, {"lora_adapter_metadata": '{"transformer.lora_alpha": 12, "transformer.alpha_pattern": {}}'})
        tensors = {names[0]: Tensor((4, 16)), names[1]: Tensor((32, 4))}
        self.assertEqual(index.pair("transformer_blocks.0.attn.to_q", tensors.__getitem__)[2], 12.0)
        with self.assertRaisesRegex(ValueError, "alpha_pattern"):
            KeyIndex(names, {"lora_adapter_metadata": '{"transformer.alpha_pattern": {"to_q": 8}}'})

    def test_missing_pair_is_not_mistaken_for_supported_adapter(self):
        keys = KeyIndex(["transformer_blocks.0.attn.to_q.lora_A.weight"])
        with self.assertRaisesRegex(ValueError, "missing B"):
            keys.pair("transformer_blocks.0.attn.to_q", lambda _: Tensor((4, 16)))

    def test_conflicting_aliases_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            KeyIndex([
                "transformer_blocks.0.attn.to_q.lora_A.weight",
                "transformer.transformer_blocks.0.attn.to_q.lora_A.default.weight",
            ])

    def test_accepts_supported_qwen_21_image_module(self):
        index = KeyIndex([
            "transformer.transformer_blocks.31.attn.to_q.lora_A.default.weight",
            "transformer.transformer_blocks.31.attn.to_q.lora_B.default.weight",
        ])
        validate_qwen21(index)

    def test_rejects_unrelated_and_larger_transformer_adapters(self):
        with self.assertRaisesRegex(ValueError, "no Qwen transformer_blocks"):
            validate_qwen21(KeyIndex([
                "text_encoder.layers.0.lora_A.weight",
                "text_encoder.layers.0.lora_B.weight",
            ]))
        with self.assertRaisesRegex(ValueError, "supports the 32-block"):
            validate_qwen21(KeyIndex([
                "transformer_blocks.32.attn.to_q.lora_A.weight",
                "transformer_blocks.32.attn.to_q.lora_B.weight",
            ]))

    def test_rejects_incomplete_or_unsupported_module_pairs(self):
        index = KeyIndex([
            "transformer_blocks.0.txt_mlp.0.lora_A.weight",
            "transformer_blocks.0.txt_mlp.0.lora_B.weight",
        ])
        with self.assertRaisesRegex(ValueError, "not implemented by the V73 block exporter"):
            validate_qwen21(index)

    def test_accepts_global_targets_packed_in_glue(self):
        index = KeyIndex([
            "transformer.transformer_blocks.0.attn.to_q.lora_A.weight",
            "transformer.transformer_blocks.0.attn.to_q.lora_B.weight",
            "transformer.time_text_embed.timestep_embedder.linear_1.lora_A.weight",
            "transformer.time_text_embed.timestep_embedder.linear_1.lora_B.weight",
        ])
        validate_qwen21(index)

    def test_rejects_global_target_without_glue_support(self):
        index = KeyIndex([
            "transformer_blocks.0.attn.to_q.lora_A.weight",
            "transformer_blocks.0.attn.to_q.lora_B.weight",
            "time_text_embed.timestep_embedder.linear_3.lora_A.weight",
            "time_text_embed.timestep_embedder.linear_3.lora_B.weight",
        ])
        with self.assertRaisesRegex(ValueError, "not implemented"):
            validate_qwen21(index)

    def test_rejects_misleading_suffix_that_the_exporter_never_requests(self):
        index = KeyIndex([
            "transformer_blocks.0.extra.attn.to_q.lora_A.weight",
            "transformer_blocks.0.extra.attn.to_q.lora_B.weight",
        ])
        with self.assertRaisesRegex(ValueError, "not implemented"):
            validate_qwen21(index)


if __name__ == "__main__":
    unittest.main()
