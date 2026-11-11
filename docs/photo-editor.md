<!--
SPDX-FileCopyrightText: Open Camera contributors
SPDX-License-Identifier: Apache-2.0
-->

# Photo editor (develop)

Package `dev.opencamera.app.develop`, activity `DevelopActivity`. It opens from the share sheet and
the gallery "Edit" action ("Edit with Open Camera", `image/*`, plus `application/octet-stream` for
camera RAW files). The rollback switch is the `editor_v2` preference: when it is `false`, the intent
is handed to the older `AiEditActivity`.

## Non-destructive edits

- The edit stack is JSON (`schemaVersion` 2, `model/StackCodec.kt`). It is stored at
  `files/edits/<MediaStore id>-<first 16 bytes of SHA-256>.json`. If the photo's id changes, the
  stack is found again by its hash. If the hash changes, the stack is found by its id. The hash can
  change because MediaStore hides location bytes depending on permissions.
- Version 1 stacks are migrated on read. A version 1 stack is a list of `{"tool", "params"}` steps.
  White balance and geometry steps become sliders. Every other step is kept as a `ToolStep`.
- Exports carry XMP with `xmp:CreatorTool`, a `photoshop:History` summary and
  `opencamera:EditStack` (deflate + base64). When an export is opened and its original still
  exists, the original is opened with those edits.
- The original is overwritten only after the user confirms. On API 30+ this goes through
  `MediaStore.createWriteRequest`, on API 29 through `RecoverableSecurityException`. A copy of the
  original is kept in `files/develop-originals` for 30 days.

## Render pipeline

`render/DevelopKernel.kt` is the reference for every pixel step and the CPU fallback.
`render/DevelopGlsl.kt` is the GLES 3.0 port. The two must stay in sync. Look LUTs use the same
GLSL as the camera preview (`pipeline/.../looks/LookGlsl.kt`).

The steps always run in this order:

1. Noise reduction
2. White balance (Bradford)
3. Exposure
4. Dehaze (dark channel prior)
5. Tone: highlights, shadows and contrast on an edge-aware base layer
6. Clarity and texture
7. Highlight shoulder, then sRGB encode
8. Whites and blacks
9. Saturation, vibrance and HSL
10. Grading
11. Tone curve
12. Look LUT
13. Sharpening
14. Vignette
15. Grain

Local masks add their deltas to the global values. Up to 4 masks are active at once.

The medium and large blurs depend only on the source, so they are computed once per photo. The
fine-detail blur is cached for the proxy, and export computes it once per tile. During a slider
drag the preview renders at screen resolution, and the full proxy renders when the finger lifts.
Export renders the same shader over 2048 px tiles. Global blurs and masks come from the proxy, so
the preview and the export agree.

## Pluggable tools (retouch integration point)

Retouch tools implement `tools/DevelopTool` and register once with
`ToolRegistry.register(tool)`. Each tool:

- receives the upright, uncropped image and returns a modified copy;
- stores its parameters as JSON in a `ToolStep`, and its raster patches in the edit's asset
  directory (`ToolHost.assetDir`);
- may provide a panel through `createPanel(context, host)`, which commits steps with
  `host.commit(step, label)`.

Steps run before tone, in stack order, at proxy and export resolution (`scale` = image width / full
width). If a tool is unknown or fails, it is skipped and the user is told. It never crashes the
editor.

## Theme tokens

Editor views read colours and the corner radius only from `ui/DevelopTheme`. A camera UI skin can
set `DevelopTheme.current` before the editor opens.

## Lightroom XMP (partial, approximate)

"Save Lightroom XMP" and "Import Lightroom XMP" (in the More menu) map these `crs:` settings slider
for slider:

- `Exposure2012`, `Contrast2012`, `Highlights2012`, `Shadows2012`, `Whites2012`, `Blacks2012`
- `IncrementalTemperature` and `IncrementalTint` (on import, the raw `Temperature`/`Tint` are
  converted)
- `Vibrance`, `Saturation`, `Clarity2012`, `Texture`, `Dehaze`
- the parametric curve and `ToneCurvePV2012` (RGB/R/G/B)
- HSL for 8 bands
- split toning and colour grading
- sharpening, noise reduction, post-crop vignette and grain
- perspective, crop and angle

The algorithms differ from Adobe's, so the same numbers do not look the same.

These do not transfer (listed in `CrsXmp.NOT_TRANSFERRED` and shown in the UI):

- local masks of any kind
- looks, LUTs and look strength
- retouch steps
- quarter turns, flips, and crops of rotated photos
- camera profiles and lens corrections

## RAW

- DNG uses the app's own reader (`raw/DngReader.kt`). It supports:
  - uncompressed or lossless JPEG (compression 7) data, in strips or tiles;
  - 2x2 CFA and LinearRaw images;
  - `LinearizationTable`, `ActiveArea`, `DefaultCrop`;
  - `OpcodeList2` gain maps;
  - `ColorMatrix1/2` or `ForwardMatrix1/2`, interpolated by the as-shot colour temperature.

  Previews use 2x2 binning. Exports use the Malvar-He-Cutler demosaic. No Adobe camera profiles
  are used.
- Other camera RAW files (CR2/CR3, NEF, ARW, RAF, ORF, RW2, ...) go through LibRaw 0.22.3. LibRaw
  is built unmodified from `pipeline/third_party/LibRaw` as `libopencamera_libraw.so`, under
  LGPL-2.1. The JNI glue `libopencamera_rawjni.so` uses only LibRaw's C API.
- To use a different LibRaw: build a compatible LibRaw as `libopencamera_libraw.so` for the same
  ABI, replace `lib/<abi>/libopencamera_libraw.so` in the APK, and re-sign the APK. See `NOTICE`.
- RAW sources are decoded into linear half-float bitmaps, so values above white survive until the
  highlights slider.

## Export

- Formats:
  - JPEG (quality 50–100)
  - HEIF through `HeifWriter`, offered on API 28+ when the device has an HEVC encoder
  - AVIF, offered only if the platform exposes `Bitmap.CompressFormat.AVIF`
  - PNG
- Ultra HDR sources are edited on the SDR base image. The gain map is dropped, and the user sees a
  notice.
- Metadata options:
  - keep all;
  - remove location (the default when sharing);
  - remove all.

  Keeping GPS needs `ACCESS_MEDIA_LOCATION`, because MediaStore redacts the location otherwise. The
  editor asks for it when it is needed. Without it, the export has no GPS at all; redacted zeros
  are never written.
- Files are saved to `Pictures/OpenCamera/Edited` with `IS_PENDING` set until the file is
  complete. Sharing goes through the editor's `FileProvider`.

## Measuring

`adb shell am start -a android.intent.action.EDIT -d <content uri> -t image/* --ez develop_benchmark true -n <pkg>/dev.opencamera.app.develop.DevelopActivity`
logs render medians, the AI mask times and two full-resolution exports, under the tag
`DevelopBench`.
