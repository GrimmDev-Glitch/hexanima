// animaconv: converts an Anima checkpoint (.safetensors from Civitai: "net." or "model.diffusion_model." names, BF16 / F16 / F32) on the phone into the
// layout the app runs (the same bytes tools/make_pack.py + hexagon/mega/export_all.py + phone/extract_small.py produce on a PC):
//   OUT/small.bin    non-block weights (x_embedder, t_embedder, llm_adapter incl. its blocks, final layer...) as fp16: u32 count; u32 namelen, name, u32 ndim, i64 dims[], data
//   OUT/blkNN.bin    per DiT block: the 10 matrices (W^T as 32x32 fp16 crouton tiles, or q8 tiles = 128 B fp16 column scales + 1024 B offset-binary int8),
//                    then gains f32 [q_norm/sqrt(128), k_norm, cross q_norm/sqrt(128), cross k_norm] padded to 4096 B, then the adaLN weights fp16 (.1 [256][2048], .2 [6144][256])
// usage: animaconv SRC.safetensors OUTDIR [q8|fp16|auto]   (auto: q8 for the 40-block 2.9B models, as the app's packs)   prints "blocks N", "blk i/N", "done"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>
#include <sys/stat.h>
#include "../hexagon/mega/lora_fold.h"   // LoraFile: mmap'ed safetensors reader (fp32 / fp16 / bf16 -> fp32)

static const char* ORDER[10] = {"self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.output_proj", "cross_attn.q_proj", "cross_attn.k_proj",
                                "cross_attn.v_proj", "cross_attn.output_proj", "mlp.layer1", "mlp.layer2"};

static bool put(FILE* f, const void* p, size_t n) { return fwrite(p, 1, n, f) == n; }

// W [N=out][K=in] (torch Linear) -> tiles of W^T [K][N]: [nb][kb][16][32][2], element (k, n) at ((((nb*KB+kb)*16 + (k%32)/2)*32 + n%32)*2 + k%2)
static std::vector<__fp16> wt16(const std::vector<float>& W, int N, int K) {
    std::vector<__fp16> t((size_t)K * N); const int KB = K / 32;
    auto job = [&](int n0, int n1) {
        for (int n = n0; n < n1; n++) { const float* row = &W[(size_t)n * K]; const int nb = n >> 5, c = n & 31;
            for (int k = 0; k < K; k++) t[((((size_t)nb * KB + (k >> 5)) * 16 + ((k & 31) >> 1)) * 32 + c) * 2 + (k & 1)] = (__fp16)row[k]; }
    };
    std::vector<std::thread> th; for (int i = 0; i < 4; i++) th.emplace_back(job, N * i / 4, N * (i + 1) / 4); for (auto& x : th) x.join();
    return t;
}
// q8 records per tile: scale s[c] = max |a| over the tile's 32 rows of column c / 127 (fp16, 0 -> 1); q = rint(a / s) clamped to +-127, stored + 128
static std::vector<uint8_t> q8tiles(const std::vector<__fp16>& t, int N, int K) {
    const size_t ntile = (size_t)(N / 32) * (K / 32); std::vector<uint8_t> out(ntile * 1152);
    auto job = [&](size_t a0, size_t a1) {
        for (size_t ti = a0; ti < a1; ti++) {
            const __fp16* a = &t[ti * 1024]; uint8_t* rec = &out[ti * 1152];
            __fp16 s[32]; float sf[32];
            for (int c = 0; c < 32; c++) {
                float m = 0; for (int r = 0; r < 16; r++) for (int p = 0; p < 2; p++) m = std::max(m, std::fabs((float)a[(r * 32 + c) * 2 + p]));
                float v = m / 127.0f; s[c] = (__fp16)(v == 0 ? 1.0f : v); sf[c] = (float)s[c];
            }
            __fp16* sv = (__fp16*)rec; for (int l = 0; l < 64; l++) sv[l] = s[l / 2];
            uint8_t* q = rec + 128;
            for (int r = 0; r < 16; r++) for (int c = 0; c < 32; c++) for (int p = 0; p < 2; p++) {
                int i = (r * 32 + c) * 2 + p; float v = std::nearbyint((float)a[i] / sf[c]); v = std::min(127.f, std::max(-127.f, v)); q[i] = (uint8_t)((int)v + 128);
            }
        }
    };
    std::vector<std::thread> th; for (int i = 0; i < 4; i++) th.emplace_back(job, ntile * i / 4, ntile * (i + 1) / 4); for (auto& x : th) x.join();
    return out;
}

int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: animaconv SRC.safetensors OUTDIR [q8|fp16|auto]\n"); return 2; }
    LoraFile F; if (!F.open(argv[1])) { printf("error cannot read %s\n", argv[1]); return 1; }
    std::string out = argv[2], mode = argc > 3 ? argv[3] : "auto"; mkdir(out.c_str(), 0755);
    std::string pre;   // name prefix of the DiT
    for (const char* p : {"net.", "model.diffusion_model.", "diffusion_model.", ""}) if (F.has(std::string(p) + "blocks.0.self_attn.q_proj.weight")) { pre = p; break; }
    if (!F.has(pre + "blocks.0.self_attn.q_proj.weight")) { printf("error not an Anima DiT (no blocks.0.self_attn.q_proj.weight)\n"); return 1; }
    int nb = 0; while (F.has(pre + "blocks." + std::to_string(nb) + ".self_attn.q_proj.weight")) nb++;
    std::vector<float> tmp; std::vector<int64_t> shp;
    if (!F.get(pre + "blocks.0.self_attn.q_proj.weight", tmp, &shp) || shp.size() != 2 || shp[0] != 2048 || shp[1] != 2048) { printf("error width is not 2048 (another model?)\n"); return 1; }
    const bool q8 = mode == "q8" || (mode == "auto" && nb > 28);
    printf("blocks %d %s prefix '%s'\n", nb, q8 ? "q8" : "fp16", pre.c_str()); fflush(stdout);
    // small.bin: every tensor of the DiT that is not in its blocks (the LLM adapter's own blocks stay)
    {
        std::vector<std::string> keep;
        for (auto& kv : F.t) {
            const std::string& k = kv.first; if (k.compare(0, pre.size(), pre) != 0) continue;
            std::string nm = k.substr(pre.size());
            if (nm.compare(0, 7, "blocks.") == 0) continue;
            if (nm.find("_extra_state") != std::string::npos) continue;
            keep.push_back(k);
        }
        FILE* f = fopen((out + "/small.bin").c_str(), "wb"); if (!f) { printf("error cannot write\n"); return 1; }
        uint32_t cnt = keep.size(); put(f, &cnt, 4);
        for (auto& k : keep) {
            std::string nm = k.substr(pre.size()); std::vector<float> v; std::vector<int64_t> d;
            if (!F.get(k, v, &d)) { printf("error dtype of %s\n", k.c_str()); return 1; }
            uint32_t nl = nm.size(), nd = d.size(); put(f, &nl, 4); put(f, nm.data(), nl); put(f, &nd, 4); put(f, d.data(), 8 * nd);
            std::vector<__fp16> h(v.size()); for (size_t i = 0; i < v.size(); i++) h[i] = (__fp16)v[i]; put(f, h.data(), 2 * h.size());
        }
        fclose(f);
    }
    const float sc = 1.0f / std::sqrt(128.0f);
    for (int b = 0; b < nb; b++) {
        std::string p = pre + "blocks." + std::to_string(b) + ".";
        char fn[64]; snprintf(fn, sizeof fn, "/blk%02d.bin", b);
        std::string path = out + fn, part = path + ".part";
        FILE* f = fopen(part.c_str(), "wb"); if (!f) { printf("error cannot write\n"); return 1; }
        for (const char* m : ORDER) {
            std::vector<float> W; std::vector<int64_t> d;
            if (!F.get(p + m + ".weight", W, &d) || d.size() != 2) { printf("error missing %s%s\n", p.c_str(), m); return 1; }
            auto t = wt16(W, (int)d[0], (int)d[1]);
            if (q8) { auto r = q8tiles(t, (int)d[0], (int)d[1]); put(f, r.data(), r.size()); } else put(f, t.data(), 2 * t.size());
        }
        std::vector<float> g(1024, 0.f), x;
        const char* gn[4] = {"self_attn.q_norm.weight", "self_attn.k_norm.weight", "cross_attn.q_norm.weight", "cross_attn.k_norm.weight"};
        for (int i = 0; i < 4; i++) { if (!F.get(p + gn[i], x) || x.size() != 128) { printf("error missing %s%s\n", p.c_str(), gn[i]); return 1; } for (int j = 0; j < 128; j++) g[i * 128 + j] = (i % 2 == 0) ? x[j] * sc : x[j]; }
        put(f, g.data(), 4096);   // 2048 B of gains, zero padding to 4096
        for (const char* site : {"self_attn", "cross_attn", "mlp"}) for (const char* l : {".1.weight", ".2.weight"}) {
            if (!F.get(p + "adaln_modulation_" + site + l, x)) { printf("error missing adaln %s\n", site); return 1; }
            std::vector<__fp16> h(x.size()); for (size_t i = 0; i < x.size(); i++) h[i] = (__fp16)x[i]; put(f, h.data(), 2 * h.size());
        }
        if (fclose(f) != 0 || rename(part.c_str(), path.c_str()) != 0) { printf("error write %s\n", path.c_str()); return 1; }
        printf("blk %d/%d\n", b + 1, nb); fflush(stdout);
    }
    printf("done %d\n", nb); fflush(stdout);
    return 0;
}
