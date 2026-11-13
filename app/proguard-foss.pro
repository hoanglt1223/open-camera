# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
#
# foss flavor: OpenCV is only packaged when built from source; without it the code that uses it
# is unreachable at runtime (OpenCv.available is false).
-dontwarn org.opencv.**
