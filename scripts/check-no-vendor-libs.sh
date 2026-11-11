#!/usr/bin/env bash
# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
#
# Fails when the APK carries native libraries other than the project's and its open source
# dependencies. Phone maker features are only called through public APIs, never bundled.
set -euo pipefail

apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"
allowed='^lib/[^/]+/lib(opencamera_[a-z_]+|zxingcpp_android|image_processing_util_jni|surface_util_jni)\.so$'

libs=$(unzip -Z1 "$apk" 'lib/*' 2>/dev/null || true)
bad=$(printf '%s\n' "$libs" | grep -Ev "$allowed" | grep -v '^$' || true)
if [[ -n "$bad" ]]; then
    echo "Unexpected native libraries in $apk:"
    echo "$bad"
    exit 1
fi
echo "No vendor libraries in $apk"
