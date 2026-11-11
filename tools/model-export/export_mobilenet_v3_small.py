# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
"""Export Keras MobileNetV3-Small (ImageNet, Apache-2.0 weights) to a float16 TFLite classifier.

Input: float32 NHWC [1,224,224,3] RGB in 0..255 (the model rescales internally).
Output: [1,1000] softmax in ImageNet order (index 0 = tench).
"""
import sys
import tensorflow as tf

out = sys.argv[1]
model = tf.keras.applications.MobileNetV3Small(input_shape=(224, 224, 3), weights="imagenet", include_preprocessing=True)
conv = tf.lite.TFLiteConverter.from_keras_model(model)
conv.optimizations = [tf.lite.Optimize.DEFAULT]
conv.target_spec.supported_types = [tf.float16]
open(out, "wb").write(conv.convert())
print("wrote", out)