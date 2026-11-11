# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
"""Exports Real-ESRGAN general-x4v3 (BSD-3-Clause) and NAFNet SIDD width32 (MIT) to ONNX.

Architectures are written here from the published papers/configs so the original state
dicts load by key. Usage:
  python -I export_torch_models.py esrgan <realesr-general-x4v3.pth> <out_dir>
  python -I export_torch_models.py nafnet <NAFNet-SIDD-width32.pth> <out_dir>
"""
import os
import sys

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F


class SRVGGNetCompact(nn.Module):
    def __init__(self, num_feat=64, num_conv=32, upscale=4):
        super().__init__()
        self.upscale = upscale
        body = [nn.Conv2d(3, num_feat, 3, 1, 1), nn.PReLU(num_parameters=num_feat)]
        for _ in range(num_conv):
            body += [nn.Conv2d(num_feat, num_feat, 3, 1, 1), nn.PReLU(num_parameters=num_feat)]
        body.append(nn.Conv2d(num_feat, 3 * upscale * upscale, 3, 1, 1))
        self.body = nn.ModuleList(body)
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for m in self.body:
            out = m(out)
        out = self.upsampler(out)
        return out + F.interpolate(x, scale_factor=self.upscale, mode="nearest")


class LayerNorm2d(nn.Module):
    def __init__(self, c, eps=1e-6):
        super().__init__()
        self.weight = nn.Parameter(torch.ones(c))
        self.bias = nn.Parameter(torch.zeros(c))
        self.eps = eps

    def forward(self, x):
        mu = x.mean(1, keepdim=True)
        var = (x - mu).pow(2).mean(1, keepdim=True)
        y = (x - mu) / torch.sqrt(var + self.eps)
        return self.weight.view(1, -1, 1, 1) * y + self.bias.view(1, -1, 1, 1)


class SimpleGate(nn.Module):
    def forward(self, x):
        a, b = x.chunk(2, dim=1)
        return a * b


class NAFBlock(nn.Module):
    def __init__(self, c):
        super().__init__()
        dw = c * 2
        self.conv1 = nn.Conv2d(c, dw, 1)
        self.conv2 = nn.Conv2d(dw, dw, 3, padding=1, groups=dw)
        self.conv3 = nn.Conv2d(dw // 2, c, 1)
        self.sca = nn.Sequential(nn.AdaptiveAvgPool2d(1), nn.Conv2d(dw // 2, dw // 2, 1))
        self.sg = SimpleGate()
        ffn = c * 2
        self.conv4 = nn.Conv2d(c, ffn, 1)
        self.conv5 = nn.Conv2d(ffn // 2, c, 1)
        self.norm1 = LayerNorm2d(c)
        self.norm2 = LayerNorm2d(c)
        self.beta = nn.Parameter(torch.zeros(1, c, 1, 1))
        self.gamma = nn.Parameter(torch.zeros(1, c, 1, 1))

    def forward(self, inp):
        x = self.conv2(self.conv1(self.norm1(inp)))
        x = self.sg(x)
        x = x * self.sca(x)
        y = inp + self.conv3(x) * self.beta
        x = self.conv5(self.sg(self.conv4(self.norm2(y))))
        return y + x * self.gamma


class NAFNet(nn.Module):
    def __init__(self, width=32, enc=(2, 2, 4, 8), middle=12, dec=(2, 2, 2, 2)):
        super().__init__()
        self.intro = nn.Conv2d(3, width, 3, padding=1)
        self.ending = nn.Conv2d(width, 3, 3, padding=1)
        self.encoders, self.decoders, self.ups, self.downs = (nn.ModuleList() for _ in range(4))
        c = width
        for n in enc:
            self.encoders.append(nn.Sequential(*[NAFBlock(c) for _ in range(n)]))
            self.downs.append(nn.Conv2d(c, 2 * c, 2, 2))
            c *= 2
        self.middle_blks = nn.Sequential(*[NAFBlock(c) for _ in range(middle)])
        for n in dec:
            self.ups.append(nn.Sequential(nn.Conv2d(c, c * 2, 1, bias=False), nn.PixelShuffle(2)))
            c //= 2
            self.decoders.append(nn.Sequential(*[NAFBlock(c) for _ in range(n)]))
        self.padder_size = 2 ** len(enc)

    def forward(self, inp):
        x = self.intro(inp)
        skips = []
        for e, d in zip(self.encoders, self.downs):
            x = e(x)
            skips.append(x)
            x = d(x)
        x = self.middle_blks(x)
        for dcd, up, skip in zip(self.decoders, self.ups, skips[::-1]):
            x = dcd(up(x) + skip)
        return self.ending(x) + inp


def load(model, path):
    sd = torch.load(path, map_location="cpu", weights_only=True)
    sd = sd.get("params_ema", sd.get("params", sd))
    model.load_state_dict(sd, strict=True)
    return model.eval()


def export(model, path, shape, dynamic):
    x = torch.rand(*shape)
    kw = {"dynamic_axes": {"input": {2: "h", 3: "w"}, "output": {2: "oh", 3: "ow"}}} if dynamic else {}
    torch.onnx.export(model, x, path, input_names=["input"], output_names=["output"],
                      opset_version=17, dynamo=False, **kw)
    with torch.no_grad():
        ref = model(x).numpy().ravel()
    got = ort.InferenceSession(path).run(None, {"input": x.numpy()})[0].ravel()
    cos = float(np.dot(ref, got) / np.linalg.norm(ref) / np.linalg.norm(got))
    params = sum(p.numel() for p in model.parameters())
    print(f"{os.path.basename(path)} params={params} cosine={cos:.6f} maxabs={np.abs(ref - got).max():.2e}")


def macs(model, shape):
    total = 0

    def hook(m, i, o):
        nonlocal total
        total += o.numel() * (m.in_channels // m.groups) * m.kernel_size[0] * m.kernel_size[1]

    hs = [m.register_forward_hook(hook) for m in model.modules() if isinstance(m, nn.Conv2d)]
    with torch.no_grad():
        model(torch.rand(*shape))
    for h in hs:
        h.remove()
    return total


def main(kind, weights, out):
    if kind == "esrgan":
        m = load(SRVGGNetCompact(), weights)
        export(m, os.path.join(out, "realesr_general_x4v3.onnx"), (1, 3, 64, 64), True)
        export(m, os.path.join(out, "realesr_general_x4v3_128.onnx"), (1, 3, 128, 128), False)
        print("GMACs per 128x128 tile:", macs(m, (1, 3, 128, 128)) / 1e9)
    else:
        m = load(NAFNet(), weights)
        export(m, os.path.join(out, "nafnet_sidd_w32_256.onnx"), (1, 3, 256, 256), False)
        print("GMACs per 256x256 tile:", macs(m, (1, 3, 256, 256)) / 1e9)


if __name__ == "__main__":
    main(*sys.argv[1:4])
