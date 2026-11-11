# Phone maker features: extensions and vendor tags

Open Camera can use processing the phone maker exposes through public Android APIs. It never
ships vendor libraries or blobs; it only calls what the phone already offers.

| Source | API | Used for |
|---|---|---|
| CameraX extensions | `ExtensionsManager.isExtensionAvailable` | Night, HDR, Bokeh, Face retouch, Auto |
| Camera2 extensions (API 31+) | `CameraExtensionCharacteristics` | Probe only: supported modes, postview, strength, progress |
| Ultra HDR (API 34+) | `JPEG_R` output format | Settings > Ultra HDR |
| Vendor tags | `CaptureRequest.Key(name, type)` on keys the camera lists | Opt-in per verified device profile |

Code: `vendor-bridge/` (`VendorBridge`, `ExtensionsProbe`, `VendorTagRegistry`).

## Who processes a mode

Settings > Phone's camera features:

- **Use the phone's processing** turns the whole bridge off (no extensions chosen by the bridge, no
  vendor tags). The effect button still lets you pick an extension by hand.
- **HDR and auto HDR**, **Night**: *Phone's processing when available* (default) or *Open Camera
  processing*.

For the HDR auto, HDR on and Night settings, the order is: Open Camera processing if you chose it,
else the phone's extension (Auto, HDR, Night) if the camera offers it, else Open Camera processing.
The phone's extension is only used for plain JPEG (not RAW, Ultra HDR or ZSL). If binding it fails,
the app falls back to Open Camera processing for the rest of the session and says so.

Photos record the source in EXIF `ImageDescription`: `OEM Night`, `OEM HDR`, ... for the phone's
processing, `Open Camera HDR 5 frames`, ... for the open pipeline. A toast names the phone's mode
when it becomes active.

## Device report

At first launch the app logs what every camera exposes:

```
adb logcat -s VendorBridge
```

The report lists CameraX and Camera2 extensions per camera, Ultra HDR support, and the vendor
characteristics, request and result keys (every key whose name does not start with `android.`).
Attach it when you report a device.

More detail from the system (the tag list includes types):

```
adb shell dumpsys media.camera            # tags, types and current values per camera
adb shell cmd media.camera                # list available commands
```

## Adding vendor tags to a profile

Profiles live in the `open-camera-profiles` repository. A tag is written only when:

1. the profile has `"source": "verified"`,
2. the camera lists the tag among its request keys (see the report), and
3. the value parses and the tag has not failed before.

Format: `"vendorTags": {"<tag name>": "<type>:<value>"}`. Types: `i32`, `i64`, `u8` (byte),
`f32`, `f64`, `bool`, `i32[]` (comma separated). The type must match the type in
`dumpsys media.camera`.

```json
"vendorTags": {
  "org.codeaurora.qcamera3.some_section.some_tag": "u8:1"
}
```

Only add a tag whose effect the community has confirmed on that device and build (for example
choosing the HAL night mode, MFNR or a ZSL size). Write the build in `testedBuilds`.

## Safety

- A tag the framework rejects, or a request that fails, is dropped for the session (logged under
  `VendorTagRegistry`).
- The tags being written are recorded first. If the app dies before the session settles, those
  tags are blocked on later launches. Clear the app data to try them again.
- Vendor tags are not written while an extension session is active.
- Turning off **Use the phone's processing** disables all of the above.
