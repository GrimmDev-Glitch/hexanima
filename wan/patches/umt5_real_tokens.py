# stable-diffusion.cpp patch: Wan's umT5 encodes only the real tokens of the prompt and pads the rest of the 512 positions with zeros
# (the official Wan pipeline keeps context[:seq_len] and zero-pads to text_len). On a phone CPU this turns ~130 s into a few seconds.
# usage: python3 umt5_real_tokens.py <sd-src>/src/conditioning/conditioner.hpp   (idempotent)
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()

# 1. the class field is_umt5 is never set from the constructor argument (only the tokenizer gets it)
a1 = ': use_mask(use_mask), mask_pad(mask_pad), t5_tokenizer(is_umt5) {'
if a1 in s and 'HexAnima: the field was left false' not in s:
    s = s.replace(a1, a1 + '\n        this->is_umt5 = is_umt5;   // HexAnima: the field was left false')
    print('field set')

# 2. encode only the real tokens
marker = '// HexAnima: umT5 real tokens only'
if marker not in s:
    a = '''        size_t chunk_count = t5_tokens.size() / chunk_len;

        for (int chunk_idx = 0; chunk_idx < chunk_count; chunk_idx++) {
            // t5'''
    assert s.count(a) == 1, s.count(a)
    b = '''        size_t chunk_count = t5_tokens.size() / chunk_len;

        ''' + marker + '''
        if (is_umt5 && chunk_count == 1 && getenv("SD_UMT5_FULL") == nullptr) {
            int64_t n_real = 0;
            for (float m : t5_attn_mask_vec) if (m == 0.0f) n_real++;
            if (n_real > 0 && n_real < (int64_t)chunk_len) {
                std::vector<int> toks(t5_tokens.begin(), t5_tokens.begin() + n_real);
                std::vector<float> wts(t5_weights.begin(), t5_weights.begin() + n_real);
                sd::Tensor<int32_t> ids({n_real}, toks);
                auto h = t5->compute(n_threads, ids, sd::Tensor<float>(), false);
                GGML_ASSERT(!h.empty());
                h = apply_token_weights(std::move(h), wts);
                const int64_t dim = h.shape()[0];
                hidden_states = sd::Tensor<float>::zeros({dim, (int64_t)chunk_len, 1});
                memcpy(hidden_states.data(), h.data(), sizeof(float) * dim * n_real);
                LOG_INFO("umT5: %lld real tokens encoded, %lld zero padded, %lld ms", (long long)n_real, (long long)(chunk_len - n_real), (long long)(ggml_time_ms() - t0));
                modify_mask_to_attend_padding(&t5_attn_mask, static_cast<int>(t5_attn_mask.numel()), mask_pad);
                SDCondition result;
                result.c_crossattn = std::move(hidden_states);
                result.c_vector    = std::move(t5_attn_mask);
                return result;
            }
        }

        for (int chunk_idx = 0; chunk_idx < chunk_count; chunk_idx++) {
            // t5'''
    s = s.replace(a, b)
    print('real tokens patched')
open(p, 'w', encoding='utf-8').write(s)
