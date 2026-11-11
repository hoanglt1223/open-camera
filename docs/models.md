<!--
SPDX-FileCopyrightText: Open Camera contributors
SPDX-License-Identifier: Apache-2.0
-->

# Models

Every model the app can load is declared in `ml/src/main/assets/models/<id>/model.json`
(source, license, size, SHA-256, accelerators, input shape, timeout). `ModelSpec.parse`
rejects any license outside Apache-2.0, MIT, BSD-2/3-Clause and CC0-1.0.

Bundled models ship in the APK. Optional ones are downloaded from the project's GitHub
release `models-v1` (`https://github.com/hoanglt1223/open-camera/releases/download/models-v1/<file>`),
written to a temporary file, and moved into `filesDir/models/<id>/` only when the size and
SHA-256 match. Each process re-checks the hash before first use.

| Id | File | Size | Delivery | License | SHA-256 | Source |
|---|---|---|---|---|---|---|
| `face_detector` | `face_detector.tflite` | 0.2 MB | bundled | Apache-2.0 | `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f` | MediaPipe BlazeFace short range, from https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task; model card: MediaPipe BlazeFace Model Card (Short Range) |
| `face_landmarks` | `face_landmarks_detector.tflite` | 2.4 MB | bundled | Apache-2.0 | `c7d54204ce0448474c7f3fa9af494787c0965cbdd6f20fc72867e43046bd43d5` | MediaPipe Face Mesh V2 (478 points), from https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task; model card: Model Card MediaPipe Face Mesh V2 |
| `nafnet_sidd` | `nafnet_sidd_w32_256.onnx` | 111.7 MB | download | MIT | `02616adecc933871f256eae6bb95f09bc50a99c4fe10004211b171e0e83346e0` | NAFNet SIDD width32 denoiser, megvii-research/NAFNet (MIT), exported 256x256 by tools/model-export/export_torch_models.py |
| `nima_mobilenet` | `nima_mobilenet.tflite` | 6.1 MB | download | Apache-2.0 | `fe3545478a07fb479f74b2f060cd2b4f27851cd1922fce1888f9ef7965b784aa` | NIMA aesthetic MobileNet, idealo/image-quality-assessment weights_mobilenet_aesthetic_0.07.hdf5, converted by tools/model-export/export_nima.py (float16 weights) |
| `realesr_general_x4` | `realesr_general_x4v3_128.onnx` | 4.6 MB | download | BSD-3-Clause | `a978abb8cac46480c429909e0de3d5c976b9937a70c0ebc014cfa82a58d2c754` | Real-ESRGAN realesr-general-x4v3 (SRVGGNetCompact), xinntao/Real-ESRGAN release v0.2.5.0, exported 128x128 by tools/model-export/export_torch_models.py |
| `scene_classifier` | `mobilenet_v3_small.tflite` | 4.9 MB | bundled | Apache-2.0 | `3ae6438a31a0493a5d48cb9533eb3e0df9cb3c672564ea0f7b9bf5565577a167` | MobileNetV3-Small ImageNet classifier, Keras Applications weights (Apache-2.0), converted to float16 TFLite by tools/model-export/export_mobilenet_v3_small.py |
| `selfie_multiclass` | `selfie_multiclass_256x256.tflite` | 15.6 MB | download | Apache-2.0 | `c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0` | MediaPipe Selfie Multiclass 256x256 (float32), https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite; model card: Model Card Multiclass Segmentation |
| `selfie_segmenter` | `selfie_segmenter.tflite` | 0.2 MB | bundled | Apache-2.0 | `191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b` | MediaPipe Selfie Segmenter (square, float16), https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite; model card: Model Card MediaPipe Selfie Segmentation |
| `u2netp` | `u2netp.onnx` | 4.4 MB | download | Apache-2.0 | `309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8` | U-2-Net small (u2netp) salient object detection, xuebinqin/U-2-Net weights, ONNX as distributed in danielgatis/rembg release v0.0.0 |

## Excluded

| Model | Reason |
|---|---|
| CACNet (Leica port composition) | Proprietary, from a vendor app |
| QNN `.minn` models, Qualcomm QNN/SNPE | Proprietary blobs; the app is CPU/GPU only |
| Zero-DCE | CC BY-NC 4.0 |
| CodeFormer | NTU S-Lab License 1.0, non-commercial |
| Deep White Balance | research only |
| GFPGAN weights | StyleGAN2 / FFHQ provenance unclear |

## Re-exporting

Scripts in `tools/model-export/` (run outside the app, Python venv with torch, onnx,
onnxruntime, tensorflow-cpu):

- `export_nima.py <weights_mobilenet_aesthetic_0.07.hdf5> <out.tflite>`
- `export_torch_models.py esrgan|nafnet <weights.pth> <out dir>`
- `export_mobilenet_v3_small.py <out.tflite>`
- `inspect_models.py <dir> <models...>`: prints input/output names, shapes and types.

Each export is checked against its source framework on random input (cosine >= 0.99999).