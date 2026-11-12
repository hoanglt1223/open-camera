#!/usr/bin/env python3
# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
"""Prints a macrobenchmark run against the previous run and the smoothness plan thresholds.

    compare.py RUN_DIR [--against PREV_DIR] [--strict]

PREV_DIR defaults to the newest other directory under benchmarks/. Exits 1 when a metric got
more than 10 % worse than the previous run (and by more than NOISE_FLOOR), or with --strict
when a plan threshold is missed; 0 otherwise. All metrics are lower-is-better.
"""
import argparse
import glob
import json
import os
import sys

REGRESSION = 0.10
NOISE_FLOOR = 1.0  # ms or count; smaller absolute changes are not regressions

# (benchmark, metric, statistic, threshold, acceptance row)
THRESHOLDS = [
    ("coldStart", "timeToFullDisplayMs", "median", 700, "Mở app nguội → khung ngắm đầu tiên"),
    ("modeSwitchSameSession", "OcBindCount", "max", 0, "Đổi chế độ cùng use case: không bind lại"),
    ("modeSwitchSameSession", "frameDurationCpuMs", "P95", 16.7, "Đổi chế độ cùng use case: frame p95"),
    ("photoVideo", "OcModeTapToPreviewMaxMs", "median", 400, "Ảnh ↔ Video: chạm → khung ngắm mới"),
    ("photoVideo", "mainFrameMaxMs", "median", 50, "Ảnh ↔ Video: frame main thread dài nhất"),
    ("openSettings", "OcOpenSettingsMaxMs", "median", 100, "Mở Cài đặt: phản hồi chạm"),
    ("openSettings", "frameDurationCpuMs", "P95", 16.7, "Mở Cài đặt: frame p95"),
    ("openLooks", "OcOpenLooksMaxMs", "median", 100, "Mở bộ lọc: phản hồi chạm"),
    ("openLooks", "frameDurationCpuMs", "P95", 16.7, "Mở bộ lọc: frame p95"),
    ("openPro", "OcOpenProMaxMs", "median", 100, "Mở bảng Pro: phản hồi chạm"),
    ("openPro", "frameDurationCpuMs", "P95", 16.7, "Mở bảng Pro: frame p95"),
    ("openGallery", "OcOpenGalleryMaxMs", "median", 100, "Mở thư viện: phản hồi chạm"),
    ("openEditor", "timeToInitialDisplayMs", "median", 100, "Mở trình sửa ảnh: hiển thị đầu"),
    ("openEditor", "frameDurationCpuMs", "P95", 16.7, "Mở trình sửa ảnh: frame p95"),
    ("proDialDrag", "frameDurationCpuMs", "P95", 16.7, "Kéo thanh trượt Pro: frame p95"),
    ("editorSliderDrag", "frameDurationCpuMs", "P95", 16.7, "Kéo thanh trượt sửa ảnh: frame p95"),
    ("lensSwitch", "OcLensTapToPreviewAverageMs", "median", None, "Đổi ống kính 0.6× ↔ 1×: chạm → khung ngắm"),
    ("shutterToReady", "OcShutterToReadyMaxMs", "median", 300, "Bấm chụp → nút sẵn sàng (Ảnh)"),
]


def load(run_dir):
    """{benchmark: {metric: {statistic: value}}} from every *benchmarkData.json in run_dir."""
    out = {}
    for path in sorted(glob.glob(os.path.join(run_dir, "*.json"))):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        for b in data.get("benchmarks", []):
            m = out.setdefault(b["name"], {})
            for name, v in b.get("metrics", {}).items():
                m[name] = {k: v[k] for k in ("minimum", "median", "maximum") if k in v}
                m[name]["max"] = v.get("maximum")
            for name, v in b.get("sampledMetrics", {}).items():
                m[name] = {k: v[k] for k in ("P50", "P90", "P95", "P99") if k in v}
    return out


def previous_dir(run_dir):
    parent = os.path.dirname(os.path.abspath(run_dir))
    dirs = [os.path.join(parent, d) for d in os.listdir(parent)]
    dirs = [d for d in dirs if os.path.isdir(d) and glob.glob(os.path.join(d, "*.json"))
            and os.path.abspath(d) != os.path.abspath(run_dir)]
    return max(dirs, key=os.path.getmtime) if dirs else None


def stat_of(metric):
    return "P95" if any(k.startswith("P") for k in metric) else "median"


def fmt(v):
    return "-" if v is None else (f"{v:.0f}" if abs(v) >= 100 else f"{v:.1f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir")
    ap.add_argument("--against")
    ap.add_argument("--strict", action="store_true", help="fail on missed plan thresholds")
    args = ap.parse_args()

    cur = load(args.run_dir)
    if not cur:
        sys.exit(f"no results in {args.run_dir}")
    prev_dir = args.against or previous_dir(args.run_dir)
    prev = load(prev_dir) if prev_dir else {}
    failed = False

    print(f"run: {args.run_dir}\nprevious: {prev_dir or '-'}\n")
    print(f"{'benchmark':24} {'metric':30} {'stat':6} {'prev':>8} {'now':>8} {'change':>8}")
    for bench in sorted(cur):
        for metric in sorted(cur[bench]):
            s = stat_of(cur[bench][metric])
            now = cur[bench][metric].get(s)
            before = prev.get(bench, {}).get(metric, {}).get(s)
            change, flag = "", ""
            if now is not None and before is not None:
                if before:
                    change = f"{(now - before) / before * 100:+.0f}%"
                if now > before * (1 + REGRESSION) and now - before > NOISE_FLOOR:
                    flag, failed = "  REGRESSION", True
            print(f"{bench:24} {metric:30} {s:6} {fmt(before):>8} {fmt(now):>8} {change:>8}{flag}")

    print(f"\n{'acceptance row':48} {'threshold':>9} {'now':>8}  result")
    for bench, metric, s, limit, row in THRESHOLDS:
        now = cur.get(bench, {}).get(metric, {}).get(s)
        if now is None:
            result = "not measured"
        elif limit is None:
            result = "info"
        elif now <= limit:
            result = "ok"
        else:
            result = "MISSED"
            failed = failed or args.strict
        print(f"{row:48} {fmt(limit) if limit is not None else '-':>9} {fmt(now):>8}  {result}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
