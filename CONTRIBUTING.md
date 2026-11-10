<!--
SPDX-FileCopyrightText: Open Camera contributors
SPDX-License-Identifier: Apache-2.0
-->

# Contributing

## Code provenance (mandatory)

Open Camera is Apache-2.0. Every line, model, LUT and image in this repository
must be either written by the contributor or taken from a source whose license
allows redistribution under Apache-2.0.

- You may study decompiled or proprietary camera apps to understand *what* they
  do (behaviour, parameters, algorithms described in public literature). You
  must then write the implementation yourself, with your own structure and
  names. Never paste decompiled code, even partially or "cleaned up".
- Never add proprietary models (`.onnx`, `.tflite`, `.minn`, `.dlc`, ...),
  LUTs, watermarks, sounds, fonts or artwork extracted from other apps or
  firmware.
- No GPL code (for example from `net.sourceforge.opencamera`). Reading its
  feature list is fine; copying its code is not.
- No trademarks: no Leica, Xiaomi, Hasselblad, Zeiss, Google camera brand
  names or logos in UI strings, assets or file names. Use neutral names.
- Third-party models or data must list source URL, license and SHA-256 in
  `licenses/models.md` before they are merged.
- In the pull request, list what you used as reference (paper, public doc,
  observed behaviour of an app) so reviewers can check provenance.

## Licensing headers

<!-- REUSE-IgnoreStart -->
Every new file carries SPDX headers (`SPDX-FileCopyrightText: Open Camera
contributors`, `SPDX-License-Identifier: Apache-2.0`) or is covered by
`REUSE.toml`. Files inherited from Aperture keep their LineageOS header.
`reuse lint` must pass.
<!-- REUSE-IgnoreEnd -->

## Workflow

- Branch from `main`, keep commits focused, use Conventional Commits.
- Run `./gradlew assembleDebug test lint` and `./gradlew app:generateBp` before
  opening a pull request; CI fails if `Android.bp` is out of date.
- Upstream Aperture changes are merged from the `upstream` remote; see
  [docs/upstream-sync.md](docs/upstream-sync.md).