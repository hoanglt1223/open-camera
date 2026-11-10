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
`files/device-profile-override.json` wins over everything; bundled profiles from
the separate `open-camera-profiles` repository (`assets/profiles.bundle.json`)
win over `auto`. Settings > Export device profile shares the JSON.

## Build

Requirements: JDK 17 or newer, Android SDK platform 36, NDK 27.0.12077973 and
CMake 3.22.1 (for `:pipeline`).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew test lint
./gradlew app:generateBp       # refresh the generated part of app/Android.bp
```

### ROM (Soong)

Put the repository in `packages/apps/OpenCamera` and add
`PRODUCT_PACKAGES += OpenCamera`. The module overrides `Aperture` and `Camera2`.
`androidx.camera` and `androidx.media3` prebuilts are provided under `app/libs`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), in particular the code provenance rule.

## License

Apache-2.0. See [NOTICE](NOTICE) and `LICENSES/`.