# Benchmarks

Macrobenchmark results for the smoothness plan, one directory per run. `baseline-261112/` is the
first run (before any smoothness work) and is the reference the later phases are gated against.

## Run

```sh
ADB=~/adb-gauguin.sh scripts/bench/run.sh            # all benchmarks, 5 iterations
ADB=~/adb-gauguin.sh scripts/bench/run.sh --test openPro --iterations 10
scripts/bench/compare.py benchmarks/<run> --against benchmarks/baseline-261112
```

`run.sh` assembles `:app:assembleFullBenchmark` and `:macrobenchmark:assembleBenchmark`, installs both,
grants camera/mic/location/media permissions, installs the APK's baseline profile, compiles
`speed-profile` (the state of a user install after dexopt), runs `am instrument` and pulls the
JSON into `benchmarks/<UTC timestamp>/` together with `instrument.log`. It then runs `compare.py`
and exits non-zero when a metric got more than 10 % (and more than 1 ms or 1 count) worse than
the previous run. `--strict` on `compare.py` also fails on missed plan thresholds. `ADB` may point
to any adb-compatible wrapper; Gradle never talks to the device. After the run the benchmark
package is force-stopped and the phone goes home.

The `benchmark` build type of `:app` is release code (R8, non-debuggable) made profileable, signed
with the debug key and installed as `dev.opencamera.app.bench`, next to the release app. Photos
taken by `shutterToReady` stay in the gallery.

## Benchmarks (`macrobenchmark/.../CameraBenchmarks.kt`)

| Test | Acceptance row | Main metric |
|---|---|---|
| `coldStart` | cold start → first viewfinder frame | `timeToFullDisplayMs` (the app calls `reportFullyDrawn()` at the first streaming preview) |
| `modeSwitchSameSession` | Photo ↔ Portrait ↔ Night ↔ Pro | `OcBindCount` (must be 0), `frameDurationCpuMs` P95 |
| `photoVideo` | Photo ↔ Video | `OcModeTapToPreviewMaxMs`, `mainFrameMaxMs` (longest `Choreographer#doFrame`) |
| `openSettings`, `openLooks`, `openPro`, `openGallery` | open a panel/screen | `Oc<Open...>MaxMs` (tap → first frame of the result), `frameDurationCpuMs` P95 |
| `openEditor` | open the editor | `timeToInitialDisplayMs` (EDIT intent on the newest photo, warm process) |
| `proDialDrag`, `editorSliderDrag` | slider drag | `frameDurationCpuMs` P95 |
| `lensSwitch` | lens 0.6× ↔ 1× | `OcLensTapToPreviewAverageMs` (information only) |
| `shutterToReady` | shutter → ready | `OcShutterToReadyMaxMs` (see below) |

"Ready" means the shutter takes the next shot: for a single frame, once CameraX reports the capture
started (CameraX queues a second request behind the first until its image arrives); for HDR/Night
bursts, once every frame is captured. JPEG encoding, writing and multi-frame merging continue in the
background (thumbnail ring) and are not counted. At most two single-frame saves wait in the
background (`CaptureReadiness`); merges stay bounded by the burst queue. Runs before this change
(baseline-261112) counted the save too, and in a dark room the auto-HDR burst itself (about 3 s).

`Oc...` metrics are async trace sections from `dev.opencamera.app.PerfTrace`; `OcBind` wraps every
camera bind. The gallery is another app, so `OcOpenGallery` ends when the camera activity pauses.
Monkey (30 min) and LeakCanary rows are not macrobenchmarks; they are measured in phase 5.

## Baseline profile

`scripts/bench/run.sh --profile` assembles an unminified benchmark APK, runs
`BaselineProfileGenerator` (the same scenarios) and merges the new rules into the committed
`app/src/main/baseline-prof.txt` (`merge-profile.py`). The release build only reads the committed
file, so it stays reproducible; regenerate and commit after large UI changes.
