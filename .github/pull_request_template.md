## Summary

## References used

<!-- Papers, public docs, observed app behaviour. -->

## Provenance checklist

- [ ] All code is written by me or comes from an Apache-2.0 compatible source (no decompiled or GPL code).
- [ ] No proprietary models, LUTs, watermarks, artwork or trademarks from other apps.
- [ ] New models/data are listed in `licenses/models.md` with source, license and SHA-256.
- [ ] `./gradlew assembleDebug test lint` passes and `app/Android.bp` is regenerated.
## Robustness checklist

- [ ] Every new optional feature degrades to preview + JPEG capture instead of throwing (see CONTRIBUTING.md, Robustness).
- [ ] CameraX binding, Camera2 interop, JNI and file/MediaStore I/O failures are logged and shown to the user.
- [ ] Native errors return status codes; nothing aborts.
- [ ] Fallback logic has unit tests.
