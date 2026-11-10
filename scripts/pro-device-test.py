#!/usr/bin/env python3
# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
"""Device test for Pro mode: shoots with fixed manual settings and checks EXIF/DNG.

Needs adb on PATH, a debuggable build (run-as), and Pillow. Usage:
  python scripts/pro-device-test.py [--package dev.opencamera.app.dev] [--pin-file PATH] [--out DIR]
"""
import argparse
import json
import os
import re
import struct
import subprocess
import sys
import time

ACTIVITY = "dev.opencamera.app.CameraActivity"
DCIM = "/sdcard/DCIM/Camera"


def adb(*args, check=True, stdin=None):
    r = subprocess.run(["adb", *args], input=stdin, capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace')}")
    return r.stdout.decode(errors="replace")


def unlock(pin_file):
    window = adb("shell", "dumpsys window")
    if not re.search(r"(mDreamingLockscreen|mShowingLockscreen|isStatusBarKeyguard)=true", window):
        adb("shell", "svc power stayon true; input keyevent 224")
        return
    with open(pin_file, encoding="utf-8") as f:
        pin = f.read().strip()
    adb("shell", "svc power stayon true; input keyevent 224; sleep 1; "
        "input swipe 540 2100 540 700 200; sleep 1; "
        f"input text {pin}; input keyevent 66")
    time.sleep(1.5)


def write_pro_prefs(pkg, settings, camera_id="0"):
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           '    <boolean name="enabled" value="true" />\n'
           f'    <string name="camera_{camera_id}">{json.dumps(settings).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace(chr(34), "&quot;")}</string>\n'
           "</map>\n")
    adb("shell", f"am force-stop {pkg}")
    adb("exec-in", "run-as", pkg, "sh", "-c", "cat > shared_prefs/pro_mode.xml", stdin=xml.encode())


def start_camera(pkg):
    adb("shell", f"am start -W -n {pkg}/{ACTIVITY}")
    time.sleep(4)


def list_dcim():
    return set(adb("shell", f"ls {DCIM}", check=False).split())


def shoot(expect, wait):
    before = list_dcim()
    adb("shell", "input keyevent 27")
    deadline = time.time() + wait
    new = set()
    while time.time() < deadline:
        time.sleep(1)
        new = list_dcim() - before
        if len(new) >= expect:
            time.sleep(1)
            break
    return sorted(list_dcim() - before)


def tap_desc(desc):
    adb("shell", "uiautomator dump /sdcard/ui.xml")
    ui = adb("shell", "cat /sdcard/ui.xml")
    m = re.search(r'content-desc="' + re.escape(desc) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ui)
    if not m:
        raise RuntimeError(f"no view with description {desc!r}")
    x1, y1, x2, y2 = map(int, m.groups())
    adb("shell", f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(3)


def tiff_tags(path):
    """Tags of IFD0 and its EXIF IFD for a TIFF/DNG or a JPEG APP1 segment."""
    data = open(path, "rb").read()
    base = 0
    if data[:2] == b"\xff\xd8":
        i = data.find(b"Exif\x00\x00")
        if i < 0:
            return {}
        base = i + 6
    tiff = data[base:]
    endian = "<" if tiff[:2] == b"II" else ">"
    if struct.unpack(endian + "H", tiff[2:4])[0] != 42:
        return {}
    sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8, 12: 8}

    def read_ifd(off):
        tags = {}
        n = struct.unpack(endian + "H", tiff[off:off + 2])[0]
        for k in range(n):
            e = off + 2 + 12 * k
            tag, typ, cnt = struct.unpack(endian + "HHI", tiff[e:e + 8])
            size = sizes.get(typ, 1) * cnt
            voff = e + 8 if size <= 4 else struct.unpack(endian + "I", tiff[e + 8:e + 12])[0]
            raw = tiff[voff:voff + size]
            if typ == 3:
                val = struct.unpack(endian + "H" * cnt, raw)
            elif typ == 4:
                val = struct.unpack(endian + "I" * cnt, raw)
            elif typ in (5, 10):
                f = "I" if typ == 5 else "i"
                nums = struct.unpack(endian + f * (2 * cnt), raw)
                val = tuple(nums[j] / nums[j + 1] if nums[j + 1] else 0 for j in range(0, len(nums), 2))
            else:
                val = raw
            tags[tag] = val[0] if isinstance(val, tuple) and len(val) == 1 else val
        return tags

    ifd0 = read_ifd(struct.unpack(endian + "I", tiff[4:8])[0])
    if 0x8769 in ifd0:
        ifd0.update(read_ifd(ifd0[0x8769]))
    return ifd0


def mean_rb(path):
    from PIL import Image
    img = Image.open(path).convert("RGB").resize((64, 48))
    px = list(img.getdata())
    r = sum(p[0] for p in px) / len(px)
    b = sum(p[2] for p in px) / len(px)
    return r, b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", default="dev.opencamera.app.dev")
    ap.add_argument("--pin-file", required=True)
    ap.add_argument("--out", default="pro-device-test-out")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    unlock(args.pin_file)

    cases = [
        ("iso100_1-1000_6500K_far", dict(iso=100, exposureNs=1_000_000, wbMode="KELVIN", kelvin=6500, focus=0.0)),
        ("iso1600_1s_3200K_near", dict(iso=1600, exposureNs=1_000_000_000, wbMode="KELVIN", kelvin=3200, focus=100.0)),
    ]
    results = []
    for name, s in cases:
        write_pro_prefs(args.package, {**s, "histogram": True})
        start_camera(args.package)
        files = shoot(1, 15)
        jpg = [f for f in files if f.lower().endswith(".jpg")]
        if not jpg:
            results.append((name, "FAIL no JPEG"))
            continue
        local = os.path.join(args.out, f"{name}.jpg")
        adb("pull", f"{DCIM}/{jpg[0]}", local)
        t = tiff_tags(local)
        iso, exp, wb = t.get(0x8827), t.get(0x829A), t.get(0xA403)
        ok = iso == s["iso"] and exp and abs(exp - s["exposureNs"] / 1e9) / (s["exposureNs"] / 1e9) < 0.05
        r, b = mean_rb(local)
        results.append((name, f"{'PASS' if ok else 'FAIL'} ISO={iso} exposure={exp} WhiteBalance={wb} meanR={r:.0f} meanB={b:.0f}"))

    # RAW + JPEG: tap the format chip (JPEG -> RAW+JPEG), shoot, then cycle back to JPEG.
    write_pro_prefs(args.package, dict(iso=100, exposureNs=10_000_000, wbMode="DAYLIGHT"))
    start_camera(args.package)
    tap_desc("Photo format: JPEG")
    files = shoot(2, 20)
    dng = [f for f in files if f.lower().endswith(".dng")]
    if dng:
        local = os.path.join(args.out, "raw.dng")
        adb("pull", f"{DCIM}/{dng[0]}", local)
        head = open(local, "rb").read(4)
        t = tiff_tags(local)
        ok = head in (b"II*\x00", b"MM\x00*") and 0xC612 in t
        results.append(("dng", f"{'PASS' if ok else 'FAIL'} files={files} DNGVersion={tuple(t.get(0xC612, b''))} "
                               f"Orientation={t.get(0x0112)} ISO={t.get(0x8827)} exposure={t.get(0x829A)} "
                               f"size={os.path.getsize(local)}"))
    else:
        results.append(("dng", f"FAIL no DNG, new files {files}"))
    tap_desc("Photo format: RAW+JPEG")
    tap_desc("Photo format: RAW")

    # Leave Pro mode off so other testers get the stock camera.
    adb("shell", f"am force-stop {args.package}")
    adb("exec-in", "run-as", args.package, "sh", "-c", "rm -f shared_prefs/pro_mode.xml")

    for name, res in results:
        print(f"{name}: {res}")
    return 0 if all(r.startswith("PASS") for _, r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
