# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
"""Dumps model inputs/outputs and SHA-256 hashes; extracts labels packed in TFLite metadata.

Usage: python -I inspect_models.py <out_dir> <model> [<model> ...]
"""
import hashlib
import io
import os
import sys
import zipfile

import onnxruntime as ort
import tensorflow as tf


def tflite_io(path):
    it = tf.lite.Interpreter(model_path=path)
    lines = []
    for kind, details in (("in", it.get_input_details()), ("out", it.get_output_details())):
        for d in details:
            lines.append(f"  {kind} {d['name']} {list(d['shape'])} {d['dtype'].__name__} quant={d['quantization']}")
    return lines


def onnx_io(path):
    s = ort.InferenceSession(path)
    return [f"  in {i.name} {i.shape} {i.type}" for i in s.get_inputs()] + \
           [f"  out {o.name} {o.shape} {o.type}" for o in s.get_outputs()]


def labels(path, out_dir):
    data = open(path, "rb").read()
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return None
    names = z.namelist()
    for n in names:
        if n.endswith(".txt"):
            dst = os.path.join(out_dir, "imagenet_labels.txt")
            open(dst, "wb").write(z.read(n))
            return f"  metadata files {names}, saved {n} -> imagenet_labels.txt"
    return f"  metadata files {names}"


def main(out_dir, models):
    io_lines, hash_lines = [], []
    for m in models:
        io_lines.append(os.path.basename(m))
        io_lines += tflite_io(m) if m.endswith(".tflite") else onnx_io(m)
        if m.endswith(".tflite"):
            lab = labels(m, out_dir)
            if lab:
                io_lines.append(lab)
        h = hashlib.sha256(open(m, "rb").read()).hexdigest()
        hash_lines.append(f"{os.path.basename(m)}\t{os.path.getsize(m)}\t{h}")
    open(os.path.join(out_dir, "model-io.txt"), "w").write("\n".join(io_lines) + "\n")
    open(os.path.join(out_dir, "hashes.txt"), "w").write("\n".join(hash_lines) + "\n")
    print("\n".join(io_lines))
    print("\n".join(hash_lines))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
