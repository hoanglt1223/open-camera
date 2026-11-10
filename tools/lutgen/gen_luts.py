#!/usr/bin/env python3
# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
"""Generate the built-in looks (3D LUT + descriptor) procedurally.

Every look is described by a handful of colour operations (white balance,
Oklab chroma/hue edits, split toning, fade, channel-mix mono). The colour part
is baked into a 33^3 `.cube`; tone curve, highlight roll-off, grain and
vignette stay as runtime parameters in `look.json` so the app can apply them
identically in the preview shader and the still renderer.

Usage: python tools/lutgen/gen_luts.py [--out app/src/main/assets/looks] [--size 33]
"""

import argparse
import json
import math
import os

import numpy as np


# --- colour helpers -------------------------------------------------------

def srgb_to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


_M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929],
                [0.2119034982, 0.6806995451, 0.1073969566],
                [0.0883024619, 0.2817188376, 0.6299787005]])
_M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
                [1.9779984951, -2.4285922050, 0.4505937099],
                [0.0259040371, 0.7827717662, -0.8086757660]])


def linear_to_oklab(rgb):
    lms = rgb @ _M1.T
    return np.cbrt(lms) @ _M2.T


def oklab_to_linear(lab):
    lms = lab @ np.linalg.inv(_M2).T
    return (lms ** 3) @ np.linalg.inv(_M1).T


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def hue_weight(h, center_deg, width_deg):
    d = np.angle(np.exp(1j * (h - math.radians(center_deg))))
    return np.exp(-0.5 * (d / math.radians(width_deg)) ** 2)


# --- the look model -------------------------------------------------------

def apply_look(rgb, p):
    """rgb: (..., 3) sRGB in [0, 1]. Returns graded sRGB."""
    lin = srgb_to_linear(rgb)

    # White balance as linear gains, normalised to keep green.
    temp, tint = p.get("temp", 0.0), p.get("tint", 0.0)
    gains = np.array([1 + 0.12 * temp, 1 - 0.06 * tint, 1 - 0.12 * temp])
    lin = lin * gains / gains[1]

    if "mono" in p:
        w = np.array(p["mono"]["mix"], dtype=float)
        y = np.clip(lin @ (w / w.sum()), 0, None)
        lin = np.repeat(y[..., None], 3, axis=-1)

    lab = linear_to_oklab(np.clip(lin, 0, None))
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.hypot(a, b)
    h = np.arctan2(b, a)

    # Fade and base contrast on lightness.
    contrast = p.get("contrast", 0.0)
    if contrast:
        L = L + contrast * (L - 0.5) * (1 - np.abs(2 * L - 1)) * 1.2
    fade = p.get("fade", 0.0)
    L = fade + (1 - fade - p.get("highlight_cap", 0.0)) * L

    # Saturation: global, luminance-dependent and per hue band.
    sat = p.get("sat", 1.0)
    sat = sat * (1 + p.get("shadow_sat", 0.0) * (1 - smoothstep(0.0, 0.6, L)))
    for band in p.get("bands", []):
        wgt = hue_weight(h, band["hue"], band.get("width", 30))
        sat = sat * (1 + (band.get("sat", 1.0) - 1) * wgt)
        h = h + math.radians(band.get("shift", 0.0)) * wgt
    C = C * sat
    # Soft chroma ceiling keeps saturated colours from clipping hard.
    C = 0.32 * np.tanh(C / 0.32)

    a, b = C * np.cos(h), C * np.sin(h)

    # Split toning (Oklab a/b offsets in shadows and highlights).
    for key in ("shadows", "highlights"):
        tone = p.get(key)
        if not tone:
            continue
        w = (1 - smoothstep(0.1, 0.55, L)) if key == "shadows" else smoothstep(0.45, 0.95, L)
        ang = math.radians(tone["hue"])
        a = a + tone["amount"] * math.cos(ang) * w
        b = b + tone["amount"] * math.sin(ang) * w

    lab = np.stack([L, a, b], axis=-1)
    out = linear_to_srgb(oklab_to_linear(lab))
    return np.clip(out, 0, 1)


# --- the catalogue (original designs; neutral names) -----------------------

LOOKS = [
    dict(id="natural", name="Natural",
         description="Faithful colour with a gentle highlight shoulder.",
         params=dict(sat=1.03),
         runtime=dict(curve=[[0, 0], [1, 1]], highlightRolloff=0.25)),
    dict(id="vivid", name="Vivid",
         description="Punchy saturation and contrast for bright scenes.",
         params=dict(sat=1.22, contrast=0.12,
                     bands=[dict(hue=140, sat=1.12), dict(hue=250, sat=1.10, shift=-4)]),
         runtime=dict(curve=[[0, 0], [0.25, 0.22], [0.75, 0.79], [1, 1]], highlightRolloff=0.2)),
    dict(id="classic-ccd", name="Classic CCD",
         description="Early digital look: dense reds, cyan skies, cool shadows, crisp mid-contrast.",
         params=dict(sat=1.08, contrast=0.10, temp=-0.15, tint=0.05,
                     bands=[dict(hue=30, sat=1.18, shift=-3), dict(hue=85, sat=0.95, shift=-8),
                            dict(hue=230, sat=1.12, shift=-10), dict(hue=140, sat=0.9, shift=8)],
                     shadows=dict(hue=260, amount=0.012)),
         runtime=dict(curve=[[0, 0], [0.2, 0.17], [0.5, 0.5], [0.8, 0.84], [1, 1]],
                      highlightRolloff=0.1, vignette=dict(amount=0.12, softness=0.6))),
    dict(id="classic-ccd-plus", name="Classic CCD Plus",
         description="A richer take on Classic CCD with warm highlights and a darker frame edge.",
         params=dict(sat=1.16, contrast=0.16, temp=-0.05, tint=0.06,
                     bands=[dict(hue=30, sat=1.25, shift=-4), dict(hue=85, sat=1.0, shift=-10),
                            dict(hue=230, sat=1.18, shift=-12), dict(hue=140, sat=0.88, shift=10)],
                     shadows=dict(hue=255, amount=0.018), highlights=dict(hue=70, amount=0.012)),
         runtime=dict(curve=[[0, 0], [0.18, 0.14], [0.5, 0.5], [0.82, 0.87], [1, 1]],
                      highlightRolloff=0.15, vignette=dict(amount=0.28, softness=0.55),
                      grain=dict(amount=0.05, size=1.0))),
    dict(id="rangefinder-neutral", name="Rangefinder Neutral",
         description="Muted, warm-neutral palette with a long highlight roll-off.",
         params=dict(sat=0.9, temp=0.08, shadow_sat=-0.15,
                     bands=[dict(hue=140, sat=0.85, shift=12), dict(hue=40, sat=1.05)]),
         runtime=dict(curve=[[0, 0.01], [0.25, 0.24], [0.75, 0.76], [1, 0.99]],
                      highlightRolloff=0.55, vignette=dict(amount=0.15, softness=0.7))),
    dict(id="silver-mono", name="Silver Mono",
         description="Black and white with a green-filter mix and a faint silver-warm tone.",
         params=dict(mono=dict(mix=[0.25, 0.6, 0.15]), contrast=0.08,
                     shadows=dict(hue=250, amount=0.006), highlights=dict(hue=75, amount=0.008)),
         runtime=dict(curve=[[0, 0], [0.25, 0.22], [0.75, 0.80], [1, 1]],
                      highlightRolloff=0.3, grain=dict(amount=0.12, size=1.2),
                      vignette=dict(amount=0.18, softness=0.6))),
    dict(id="warm-film", name="Warm Film",
         description="Golden consumer-film warmth with lifted blacks.",
         params=dict(sat=1.05, temp=0.35, fade=0.03,
                     bands=[dict(hue=140, sat=0.9, shift=-12), dict(hue=60, sat=1.1)],
                     highlights=dict(hue=80, amount=0.015)),
         runtime=dict(curve=[[0, 0.03], [0.5, 0.52], [1, 0.97]], highlightRolloff=0.4,
                      grain=dict(amount=0.10, size=1.4), vignette=dict(amount=0.12, softness=0.6))),
    dict(id="cool-chrome", name="Cool Chrome",
         description="Cool, slide-film chrome: deep blues, restrained warmth, hard contrast.",
         params=dict(sat=0.95, temp=-0.25, contrast=0.14,
                     bands=[dict(hue=250, sat=1.25, shift=-6), dict(hue=40, sat=0.85)]),
         runtime=dict(curve=[[0, 0], [0.22, 0.17], [0.78, 0.84], [1, 1]], highlightRolloff=0.15,
                      grain=dict(amount=0.06, size=1.0))),
    dict(id="faded-matte", name="Faded Matte",
         description="Low-contrast matte print with soft pastel colour.",
         params=dict(sat=0.8, fade=0.07, highlight_cap=0.04,
                     shadows=dict(hue=200, amount=0.01)),
         runtime=dict(curve=[[0, 0.04], [0.5, 0.5], [1, 0.96]], highlightRolloff=0.35,
                      grain=dict(amount=0.08, size=1.3))),
    dict(id="soft-portrait", name="Soft Portrait",
         description="Gentle portrait-film palette: warm skin, pastel greens, soft contrast.",
         params=dict(sat=0.95, temp=0.12, fade=0.02,
                     bands=[dict(hue=50, sat=1.05, shift=4), dict(hue=140, sat=0.75, shift=-15),
                            dict(hue=250, sat=0.9)],
                     highlights=dict(hue=60, amount=0.008)),
         runtime=dict(curve=[[0, 0.02], [0.3, 0.31], [0.7, 0.71], [1, 0.98]],
                      highlightRolloff=0.5, grain=dict(amount=0.06, size=1.1))),
    dict(id="teal-orange", name="Teal and Orange",
         description="Cinematic split: teal shadows and warm skin tones.",
         params=dict(sat=1.05, contrast=0.08,
                     bands=[dict(hue=50, sat=1.15, shift=-5), dict(hue=200, sat=1.1, shift=-10),
                            dict(hue=140, sat=0.8, shift=40)],
                     shadows=dict(hue=210, amount=0.03), highlights=dict(hue=60, amount=0.018)),
         runtime=dict(curve=[[0, 0], [0.25, 0.22], [0.75, 0.79], [1, 1]], highlightRolloff=0.25,
                      vignette=dict(amount=0.15, softness=0.6))),
    dict(id="high-contrast-mono", name="High Contrast Mono",
         description="Red-filter black and white: dark skies, bright skin, heavy grain.",
         params=dict(mono=dict(mix=[0.55, 0.4, 0.05]), contrast=0.2),
         runtime=dict(curve=[[0, 0], [0.2, 0.12], [0.5, 0.5], [0.8, 0.9], [1, 1]],
                      highlightRolloff=0.1, grain=dict(amount=0.2, size=1.5),
                      vignette=dict(amount=0.22, softness=0.5))),
]


def build_lut(params, size):
    grid = np.linspace(0, 1, size)
    # .cube order: red changes fastest, then green, then blue.
    b, g, r = np.meshgrid(grid, grid, grid, indexing="ij")
    rgb = np.stack([r, g, b], axis=-1).reshape(-1, 3)
    return apply_look(rgb, params)


def write_cube(path, title, lut, size):
    with open(path, "w", newline="\n") as f:
        f.write(f'TITLE "{title}"\n# Generated by tools/lutgen/gen_luts.py\n')
        f.write(f"LUT_3D_SIZE {size}\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n")
        for r, g, b in lut:
            f.write(f"{r:.5f} {g:.5f} {b:.5f}\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join("app", "src", "main", "assets", "looks"))
    ap.add_argument("--size", type=int, default=33)
    args = ap.parse_args()

    index = []
    for look in LOOKS:
        d = os.path.join(args.out, look["id"])
        os.makedirs(d, exist_ok=True)
        write_cube(os.path.join(d, "lut.cube"), look["name"], build_lut(look["params"], args.size),
                   args.size)
        desc = {"id": look["id"], "name": look["name"], "description": look["description"],
                "lut": "lut.cube", "intensity": 1.0}
        desc.update(look["runtime"])
        with open(os.path.join(d, "look.json"), "w", newline="\n") as f:
            json.dump(desc, f, indent=2)
            f.write("\n")
        index.append(look["id"])
    with open(os.path.join(args.out, "index.json"), "w", newline="\n") as f:
        json.dump({"looks": index}, f, indent=2)
        f.write("\n")
    print(f"wrote {len(index)} looks to {args.out}")


if __name__ == "__main__":
    main()
