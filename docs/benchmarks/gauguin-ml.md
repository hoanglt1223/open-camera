<!--
SPDX-FileCopyrightText: Open Camera contributors
SPDX-License-Identifier: Apache-2.0
-->

# ML benchmark: gauguin (Mi 10T Lite, SM7225, Android 17)

Source: `pipeline/src/androidTest/.../ai/AiBenchmarkTest.kt`. Input: a 3000x4000 public-domain portrait.
The test writes `files/ai-bench.txt` in the test app.

Two runs were made on 2026-11-11:

- **Quiet**: 10:42, old ROM, device idle.
- **Loaded**: 12:42, new ROM, 10 minutes after boot. Load average was 14 because other test sessions were using the phone.

The quiet run already had the XNNPACK fix but not the open-timeout and multiclass softmax fixes. Its portrait numbers therefore use the selfie model. Times are medians in ms.

## Models per accelerator

The GPU result was checked against the CPU result on the same input; the last column is the cosine similarity.

| Model | CPU quiet | CPU loaded | GPU quiet | GPU loaded | Cosine GPU vs CPU |
|---|---|---|---|---|---|
| selfie_segmenter | 13.6 | 18.8 | not supported¹ | not supported¹ | – |
| selfie_multiclass | 461 | 1653 | 92 | 112 | 0.99992 |
| face_detector | 22 | 23 | 7 | 12 | 1.00000 |
| face_landmarks | 21 | 78 | 16 | 18 | 0.99997 |
| scene_classifier (EfficientNet-Lite0 int8, quiet) | 50 | – | 38 | – | 0.99654 |
| scene_classifier (MobileNetV3-Small, loaded) | – | 52 | – | ² | – |
| nima_mobilenet | 80 | 82 | 16.5 | 17 | 1.00000 |
| u2netp (320) | 1213 | 2184 | – | – | – |
| realesr_general_x4 (one 128 px tile) | 1032 | 2013 | – | – | – |
| nafnet_sidd (one 256 px tile) | 2499 | 3872 | – | – | – |

¹ The selfie model uses the MediaPipe custom op `Convolution2DTransposeBias`, which the stock GPU delegate rejects. It runs on CPU.
² Fell back to CPU.

NNAPI is not offered on Android 15 and later, so NNAPI requests ran on CPU, or on GPU for the multiclass model.

## Features

| Feature | Quiet | Loaded | Target |
|---|---|---|---|
| Portrait, 12 MP render (blur + composite) | 275 ms | 633 ms | – |
| Portrait, 12 MP total in steady state | about 0.6 s expected³ | 1.0–1.5 s | < 2 s |
| Portrait, first use (includes the one-time accelerator benchmark) | – | 10–17 s | – |
| Live portrait, 480 px, processing only | 95 ms (10.5 fps) | 211 ms (4.7 fps) | ≥ 15 fps |
| Scene detection per frame | 57 ms⁴ | 105 ms | < 30 ms |
| Composition with U²-Net-p | 1.6 s | 2.0 s (timed out, classical used) | – |
| Beauty, 12 MP | – | 1.2–1.8 s | – |
| Best shot of 3 (NIMA) | correct frame | correct frame | – |
| Classical denoise, 12 MP | – | 2.0 s | – |
| Classical denoise, 1 MP with σ 18 noise | – | 23.6 → 28.5 dB | – |
| NAFNet, 1 MP | 50 s, 27.9 dB | 87–101 s | 12 MP < 10 s |
| NAFNet, 12 MP with a 10 s budget | falls back to classical (TIMEOUT) | falls back to classical (TIMEOUT) | – |
| Real-ESRGAN, 512 → 1024 | 25 s | 46–55 s | – |
| Classical upscale, 512 → 1024 | 148 ms | 117 ms | – |

³ Expected from the parts: multiclass on GPU about 100 ms, plus resample and refine, plus the 275 ms render.

⁴ Measured before the switch to MobileNetV3-Small.

## Conclusions

- **Model sizes:** NAFNet width-32 and Real-ESRGAN general-x4v3 are far too slow for full photos on SM7225.
  - They stay optional downloads for crops of about 1 MP and smaller.
  - The tiled runner gives up after the first tile when the estimated total exceeds the budget, and then the classical path runs.
- **NAFNet quality:** it scores lower PSNR than the classical filter on synthetic Gaussian noise. It was trained on real SIDD sensor noise, so this test is not a fair quality comparison.
- **Live portrait:** about 10 fps when the phone is idle, with segmentation on CPU. Most of the time goes to the Kotlin pre- and post-processing around the 14 ms model. Moving that work to native code is the next step to reach 15 fps.
