<!--
SPDX-FileCopyrightText: Open Camera contributors
SPDX-License-Identifier: Apache-2.0
-->

# Open Camera

An open-source Android camera app built on CameraX, forked from
[LineageOS Aperture](https://github.com/LineageOS/android_packages_apps_Aperture).
The goal is a camera that works well on any Android 8.0+ device, with per-device
lens profiles, colour looks, multi-frame processing and on-device (CPU/GPU) AI
features, shipped on F-Droid and buildable into a ROM with Soong.

Application id: `dev.opencamera.app` (debug builds use `dev.opencamera.app.dev`).
This project is unrelated to the GPL app `net.sourceforge.opencamera`; the two
install side by side.

## Install

Signed release APKs are on [GitHub releases](https://github.com/hoanglt1223/open-camera/releases).
To get updates through an F-Droid client (F-Droid, Neo Store, Droid-ify), add the
project's own repository, which serves the same signed full build:

- URL: `https://hoanglt1223.github.io/open-camera-fdroid/repo`
- Fingerprint (SHA-256): `25CB5CB382DF8BA83A046EBA1E4B10EB37302296BE9A48597F6689A09C95BE1B`
- One-tap link: [add repository](https://hoanglt1223.github.io/open-camera-fdroid/repo?fingerprint=25CB5CB382DF8BA83A046EBA1E4B10EB37302296BE9A48597F6689A09C95BE1B)

APK signing certificate (SHA-256): `999623e2de4a85557102624929916dcd80126aaf2354697dc0406efd0a345902`.
Maintainers publish a release to that repository with
`scripts/fdroid-repo/update-repo.sh <tag>`; the repository index key stays offline.

## Modules

| Module | Purpose |
|---|---|
| `:app` | UI, modes, settings (Aperture code base) |
| `:profiles` | Device profiles: lens catalog, zoom stops, JSON (pure Kotlin/JVM, unit tested) |
| `:capture` | Camera session and burst capture (skeleton) |
| `:pipeline` | Native image pipeline, NDK + CMake (skeleton, JNI `nativeVersion()`) |
| `:ml` | On-device ML runtime, CPU/GPU only (skeleton) |
| `:vendor-bridge` | CameraX extensions / vendor tags (skeleton) |
| `:lens_launcher` | Google Lens launcher shim from Aperture |

## Device profiles

On first launch the app reads Camera2 characteristics (listed ids plus probed ids
0..15) off the main thread and builds an `auto` profile: duplicate ids are merged,
low-resolution macro/depth sensors are excluded from zoom stops, and a 2x crop
stop is added when there is no telephoto. The result is cached per build
fingerprint in `noBackupFilesDir`. A profile placed at
`files/device-profile-override.json` wins over everything, then the profile pinned in
Settings > Device profile (a bundled profile, "scanned only" or "off" for the plain
camera list), then the best bundled match from the separate `open-camera-profiles`
repository: measured profiles replace the scan, spec catalog profiles keep the scanned
cameras and supply the published roles and stop labels. The zoom chips are these stops,
mapped onto camera ids CameraX can open (hidden ids fall back to a duplicate id or a
logical camera, otherwise the chip is left out). Settings > Export device profile shares
the JSON.

`gradle/bundle-profiles.gradle` writes `assets/profiles.bundle.json` at build time from a
local checkout (`openCamera.profiles.dir=<path>` in `local.properties` or `-P`) or else the
release tag pinned by `openCamera.profiles.version` in `gradle.properties`.

## Build

Requirements: JDK 17 or newer, Android SDK platform 36, NDK 27.0.12077973 and
CMake 3.22.1 (for `:pipeline`).

```sh
./gradlew assembleFullDebug    # app/build/outputs/apk/full/debug/app-full-debug.apk
./gradlew test lint
./gradlew app:generateBp       # refresh the generated part of app/Android.bp
```

Two product flavors:

- `full`: everything, including the prebuilt LiteRT, ONNX Runtime and OpenCV libraries and the
  bundled models. GitHub releases, the self-hosted F-Droid repo and the ROM ship this one.
- `foss`: for the main F-Droid repository. It leaves out the prebuilt ML runtimes, OpenCV and
  the models; AI features, Portrait, Panorama, Document mode and the OpenCV based retouch tools
  hide themselves. Everything else is the same. Build it with `./gradlew assembleFossRelease`.

### ROM (Soong)

Put the repository in `packages/apps/OpenCamera` and add
`PRODUCT_PACKAGES += OpenCamera`. The module overrides `Aperture` and `Camera2`.
`androidx.camera` and `androidx.media3` prebuilts are provided under `app/libs`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), in particular the code provenance rule.

## License

Apache-2.0. See [NOTICE](NOTICE) and `LICENSES/`.