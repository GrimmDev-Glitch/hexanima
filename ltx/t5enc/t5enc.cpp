// t5enc: prompt -> T5-XXL encoder hidden states for LTX-Video, on the CPU with ggml (stable-diffusion.cpp's T5).
// usage: t5enc <t5xxl.gguf> <out_prefix> <threads>   (prompt lines are read from stdin; one line = one prompt, "q" quits)
// For every prompt it writes <out_prefix>.emb (128 x 4096 float32, padding rows kept as computed) and
// <out_prefix>.mask (128 float32: 0 for real tokens, -10000 for padding) and prints "done <ms> <tokens>".
// The model stays in memory between prompts, so a second prompt does not reload 2.9 GB.
#include <cmath>
#include <unistd.h>
#include <cstdio>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "model/te/t5.hpp"
#include "model/vae/ltx_vae.hpp"

static constexpr int kTokens = 128;

// "enc" mode: the LTX-Video 0.9.5 VAE encoder for ONE picture (the first frame of image to video / of the next segment of a longer clip).
// usage: t5enc enc <vae.safetensors> <stats.bin> <threads>; stdin: "img IN.rgb W H OUT.lat" (RGB24) -> OUT.lat = (W/32*H/32) x 128 float32,
// token-major (row, column), normalized with the per-channel statistics like the transformer's latents; prints "done <ms>".
static int encMode(int argc, char** argv) {
    if (argc < 5) { fprintf(stderr, "usage: t5enc enc <vae.safetensors> <stats.bin> <threads>\n"); return 1; }
    const std::string path = argv[2]; const int threads = atoi(argv[4]);
    std::vector<float> st(256); { FILE* f = fopen(argv[3], "rb"); if (!f || fread(st.data(), 4, 256, f) != 256) { fprintf(stderr, "error stats\n"); return 1; } fclose(f); }
    ggml_backend_t backend = sd_backend_cpu_init();
    auto manager = std::make_shared<ModelManager>();
    ModelLoader loader;
    if (!loader.init_from_file_and_convert_name(path, "vae.")) { fprintf(stderr, "error load %s\n", path.c_str()); return 1; }
    auto& storage = loader.get_tensor_storage_map();
    auto vae = std::make_shared<LTXVideoVAE>(backend, storage, "first_stage_model", false, VERSION_LTXAV, manager);
    if (!manager->set_loader(loader) || !manager->register_runner_params(ModelComponent::VAE, *vae, ModelManager::ResidencyMode::ParamBackend, backend, backend) || !manager->validate_registered_tensors()) {
        fprintf(stderr, "error register vae\n"); return 1;
    }
    printf("ready\n"); fflush(stdout);
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line == "q") break;
        std::istringstream ss(line); std::string cmd, in, out; int W = 0, H = 0; ss >> cmd >> in >> W >> H >> out;
        if (cmd != "img" || W % 32 || H % 32) { printf("error args\n"); fflush(stdout); continue; }
        int64_t t0 = ggml_time_ms();
        std::vector<uint8_t> rgb((size_t)W * H * 3);
        FILE* f = fopen(in.c_str(), "rb"); if (!f || fread(rgb.data(), 1, rgb.size(), f) != rgb.size()) { if (f) fclose(f); printf("error read\n"); fflush(stdout); continue; } fclose(f);
        sd::Tensor<float> x({W, H, 3, 1});   // ggml order [W, H, C, N], pixels in -1..1
        float* xd = x.data();
        for (int c = 0; c < 3; c++) for (int y = 0; y < H; y++) for (int i = 0; i < W; i++) xd[(size_t)c * W * H + y * W + i] = rgb[((size_t)y * W + i) * 3 + c] / 127.5f - 1.f;
        auto z = vae->_compute(threads, x, false);   // [w, h, 1, 256(, 1)]: mean then log-variance
        const int w = W / 32, h = H / 32;
        if (z.empty() || z.numel() < (int64_t)w * h * 128) { printf("error encode\n"); fflush(stdout); continue; }
        const float* zd = z.data(); std::vector<float> lat((size_t)w * h * 128);
        for (int c = 0; c < 128; c++) for (int t = 0; t < w * h; t++) lat[(size_t)t * 128 + c] = (zd[(size_t)c * w * h + t] - st[c]) / st[128 + c];
        f = fopen(out.c_str(), "wb"); fwrite(lat.data(), 4, lat.size(), f); fclose(f);
        printf("done %lld\n", (long long)(ggml_time_ms() - t0)); fflush(stdout);
    }
    fflush(stdout); _exit(0);
}

int main(int argc, char** argv) {
    sd_set_log_callback([](sd_log_level_t l, const char* t, void*) { if (l >= SD_LOG_WARN || getenv("T5_VERBOSE")) fputs(t, stderr); }, nullptr);
    if (argc > 1 && std::string(argv[1]) == "enc") return encMode(argc, argv);
    if (argc < 4) { fprintf(stderr, "usage: t5enc <t5xxl.gguf> <out_prefix> <threads>\n"); return 1; }
    const std::string path = argv[1], out = argv[2];
    const int threads = atoi(argv[3]);

    sd_set_log_callback([](sd_log_level_t l, const char* t, void*) { if (l >= SD_LOG_WARN || getenv("T5_VERBOSE")) fputs(t, stderr); }, nullptr);
    ggml_backend_t backend = sd_backend_cpu_init();
    auto manager = std::make_shared<ModelManager>();
    ModelLoader loader;
    // the prefix makes sd.cpp map llama.cpp names (enc.blk.N.attn_q) to HF T5 names
    if (!loader.init_from_file_and_convert_name(path, "text_encoders.t5xxl.transformer.")) { fprintf(stderr, "error load %s\n", path.c_str()); return 1; }
    auto& storage = loader.get_tensor_storage_map();
    const std::string prefix = "text_encoders.t5xxl.transformer";
    fprintf(stderr, "t5 prefix '%s', %zu tensors\n", prefix.c_str(), storage.size());
    if (getenv("T5_VERBOSE")) { int k = 0; for (auto& [name, ts] : storage) if (k++ < 12 || name.find("block") == std::string::npos) fprintf(stderr, "  %s\n", name.c_str()); }
    auto t5 = std::make_shared<T5Embedder>(backend, storage, prefix, false, manager);
    if (!manager->set_loader(loader) ||
        !manager->register_runner_params(ModelComponent::Conditioner, *t5, prefix, ModelManager::ResidencyMode::ParamBackend, backend, backend) ||
        !manager->validate_registered_tensors()) {
        fprintf(stderr, "error register\n"); return 1;
    }
    printf("ready\n"); fflush(stdout);

    std::string line;
    while (std::getline(std::cin, line)) {
        if (line == "q") break;
        int64_t t0 = ggml_time_ms();
        auto tw = t5->tokenize(line, kTokens, true);
        auto& tokens = std::get<0>(tw);
        auto& mask = std::get<2>(tw);
        if (tokens.size() > (size_t)kTokens) { tokens.resize(kTokens); mask.resize(kTokens); }
        int real = 0; for (float m : mask) if (m == 0.f) real++;
        auto ids = sd::Tensor<int32_t>::from_vector(tokens);
        auto am = sd::Tensor<float>::from_vector(mask);
        auto h = t5->model.compute(threads, ids, am);
        if (h.empty() || h.numel() != (int64_t)kTokens * 4096) { printf("error t5 %lld\n", (long long)h.numel()); fflush(stdout); continue; }
        FILE* f = fopen((out + ".emb").c_str(), "wb"); fwrite(h.data(), sizeof(float), h.numel(), f); fclose(f);
        std::vector<float> m(kTokens); for (int i = 0; i < kTokens; i++) m[i] = mask[i] == 0.f ? 0.f : -10000.f;
        f = fopen((out + ".mask").c_str(), "wb"); fwrite(m.data(), sizeof(float), m.size(), f); fclose(f);
        printf("done %lld %d\n", (long long)(ggml_time_ms() - t0), real); fflush(stdout);
    }
    fflush(stdout); _exit(0);   // skip the ggml teardown (it crashes on exit and the OS frees everything anyway)
}
