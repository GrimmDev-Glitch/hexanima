# Third-party components

This repository contains only HexAnima's own source code (MIT, see `LICENSE`). Nothing below is stored here.

| Component | Used for | License |
|---|---|---|
| Anima (CircleStone Labs), built on NVIDIA Cosmos | base model | CircleStone Labs Non-Commercial License; NVIDIA Open Model License |
| MiaoMiao Harem / 3D Harem / RealSkin (MIAOKA) | downloadable models | author's terms: non-commercial, no selling of images |
| Qwen3 0.6B text encoder, Qwen-Image VAE | text encoder, VAE | Apache 2.0 |
| stable-diffusion.cpp | sampler and CPU parts (`patches/` are applied to it) | MIT |
| TAEHV (`taew2_1`) | preview decoder | see github.com/madebyollin/taehv |
| Danbooru tag list (DominikDoom/a1111-sd-webui-tagcomplete) | prompt suggestions | see that repository |
| Qualcomm AI Engine Direct (QNN) runtime libraries | bundled in the APK | Qualcomm AI Stack License |
| Qualcomm Hexagon SDK / QHL (HMX matrix library) | building the DSP kernels | Qualcomm license, not redistributed here |

## Qualcomm components

- The QNN runtime libraries (`libQnnHtp*.so`, `libQnnSystem.so`) are **not** in this repository and must never be added to it or published on their own:
  the Qualcomm AI Stack License allows distributing them only in object code and only as part of your own application (not standalone).
  `tools/stage_jnilibs.sh` copies them from a QNN SDK that you download from Qualcomm and accept the license for.
  The license text and notices are shown in the app (Info -> Licenses) and live in `app/assets/licenses/`.
- `hexagon/mega/build.sh` needs the Hexagon SDK and the QHL HMX sources (`hexagon/mega/qhl_hmx`) plus `syslib/libcdsprpc.so` from a device.
  They are Qualcomm proprietary files and are not included; get them from the SDK you are licensed for.
