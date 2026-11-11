# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
"""NIMA (idealo, Apache-2.0) MobileNet aesthetic weights -> TFLite (fp16 weights, fp32 IO).

Usage: python -I export_nima.py <weights.hdf5> <out.tflite>
"""
import sys

import h5py
import numpy as np
import tensorflow as tf


def build():
    base = tf.keras.applications.MobileNet(
        input_shape=(224, 224, 3), alpha=1.0, include_top=False, pooling="avg", weights=None)
    x = tf.keras.layers.Dropout(0.75)(base.output)
    out = tf.keras.layers.Dense(10, activation="softmax")(x)
    return tf.keras.Model(base.input, out)


def h5_arrays(path):
    """Weight arrays in layer order from a Keras 2 h5 weights file."""
    arrays = []
    with h5py.File(path, "r") as f:
        g = f["model_weights"] if "model_weights" in f else f
        for layer in [n.decode() if isinstance(n, bytes) else n for n in g.attrs["layer_names"]]:
            lg = g[layer]
            for wn in lg.attrs["weight_names"]:
                wn = wn.decode() if isinstance(wn, bytes) else wn
                arrays.append((layer, wn, np.array(lg[wn])))
    return arrays


def main(src, dst):
    model = build()
    arrays = h5_arrays(src)
    targets = model.weights
    # h5 order is per layer: kernel/gamma/beta/mean/var etc. Same as Keras weight order.
    if len(arrays) != len(targets):
        raise SystemExit(f"weight count mismatch: h5 {len(arrays)} vs model {len(targets)}")
    for (layer, name, arr), var in zip(arrays, targets):
        if tuple(arr.shape) != tuple(var.shape):
            raise SystemExit(f"shape mismatch {layer}/{name} {arr.shape} vs {var.path} {var.shape}")
        var.assign(arr)
    rng = np.random.default_rng(0)
    x = (rng.random((1, 224, 224, 3), dtype=np.float32) * 2 - 1)
    ref = model.predict(x, verbose=0)
    print("keras out", ref.round(4), "mean score", float((ref[0] * np.arange(1, 11)).sum()))

    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.target_spec.supported_types = [tf.float16]
    data = conv.convert()
    open(dst, "wb").write(data)
    it = tf.lite.Interpreter(model_content=data)
    it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]["index"], x)
    it.invoke()
    got = it.get_tensor(it.get_output_details()[0]["index"])
    cos = float((ref * got).sum() / np.linalg.norm(ref) / np.linalg.norm(got))
    print("tflite out", got.round(4), "cosine", cos)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
