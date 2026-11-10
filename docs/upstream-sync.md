<!--
SPDX-FileCopyrightText: Open Camera contributors
SPDX-License-Identifier: Apache-2.0
-->

# Syncing with upstream Aperture

Upstream: `https://github.com/LineageOS/android_packages_apps_Aperture`, branch
`lineage-24.0` (remote `upstream`).

```sh
git fetch upstream
git merge upstream/lineage-24.0
```

## Rename map

| Upstream | Open Camera |
|---|---|
| package `org.lineageos.aperture` | `dev.opencamera.app` |
| `app/src/main/java/org/lineageos/aperture/` | `app/src/main/java/dev/opencamera/app/` |
| Soong `Aperture`, `Aperture_*` prebuilts | `OpenCamera`, `OpenCamera_*` |
| `ApertureFrameworksBaseOverlay` | `OpenCameraFrameworksBaseOverlay` |
| `ApertureLensLauncher` | `OpenCameraLensLauncher` |
| `app/*-org.lineageos.aperture.xml` | `app/*-dev.opencamera.app.xml` |
| `app_name` "Camera" (translated) | "Open Camera" (not translatable) |
| launcher icon | original vector in `res/drawable/ic_launcher_*.xml` |

Class names (`ApertureApplication`, `Theme.Aperture*`) are kept to reduce merge
conflicts. Git rename detection handles the moved package directory; if a merge
reports conflicts in moved files, re-run the namespace replacement on the
incoming files.

## Module notes

`gradle-generatebp` resolves only Maven dependencies and fails on Gradle
project dependencies (`No POM found`). Modules other than `:app` therefore keep
hand-written `Android.bp` files, and their sources are added to `app/Android.bp`
outside the generated `static_libs` section.