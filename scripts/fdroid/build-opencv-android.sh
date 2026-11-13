#!/usr/bin/env bash
# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
#
# Builds libopencv_java4.so and the generated Java bindings from OpenCV source (no Gradle).
# Usage: build-opencv-android.sh <opencv-src> <out-dir> [abi...]
# The foss flavor uses the result through the :opencv-source module when Gradle gets
# -PopenCamera.opencvDir=<out-dir> (the F-Droid recipe does this).
set -euo pipefail
SRC=$(cd "$1" && pwd); OUT=$2; shift 2
ABIS=${*:-arm64-v8a armeabi-v7a}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
export ANDROID_SDK_ROOT=$SDK
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK:-$SDK/ndk/27.0.12077973}}
CMAKE_DIR=${CMAKE_DIR:-$SDK/cmake/3.22.1/bin}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
for abi in $ABIS; do
  b="$OUT/work-$abi"
  "$CMAKE_DIR/cmake" -S "$SRC" -B "$b" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_DIR/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="-ffile-prefix-map=$SRC=opencv -ffile-prefix-map=$NDK=ndk" \
    -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$SRC=opencv -ffile-prefix-map=$NDK=ndk" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1" \
    -DBUILD_LIST=core,imgproc,imgcodecs,photo,calib3d,features2d,flann,java,java_bindings_generator \
    -DBUILD_SHARED_LIBS=OFF -DBUILD_JAVA=ON -DBUILD_ANDROID_PROJECTS=ON -DBUILD_ANDROID_EXAMPLES=OFF \
    -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_DOCS=OFF -DBUILD_opencv_apps=OFF \
    -DBUILD_KOTLIN_EXTENSIONS=OFF -DWITH_OPENCL=OFF -DWITH_IPP=OFF -DWITH_ITT=OFF -DWITH_TBB=OFF \
    -DWITH_KLEIDICV=OFF -DWITH_FASTCV=OFF -DWITH_CAROTENE=ON -DWITH_ANDROID_MEDIANDK=OFF \
    -DWITH_PROTOBUF=OFF -DWITH_EIGEN=OFF -DWITH_JPEG=OFF -DWITH_PNG=OFF -DWITH_TIFF=OFF -DWITH_WEBP=OFF \
    -DWITH_OPENJPEG=OFF -DWITH_JASPER=OFF -DWITH_OPENEXR=OFF -DWITH_AVIF=OFF -DWITH_IMGCODEC_HDR=OFF \
    -DWITH_IMGCODEC_SUNRASTER=OFF -DWITH_IMGCODEC_PXM=OFF -DWITH_IMGCODEC_PFM=OFF \
    -DOPENCV_SKIP_ANDROID_FORCE_CMAKE=ON >"$OUT/cmake-$abi.log"
  "$CMAKE_DIR/ninja" -C "$b" -j "${JOBS:-6}" opencv_java >"$OUT/ninja-$abi.log"
  mkdir -p "$OUT/jniLibs/$abi"
  cp "$(find "$b" -name libopencv_java4.so | head -1)" "$OUT/jniLibs/$abi/"
done
first=$(echo $ABIS | cut -d' ' -f1)
rm -rf "$OUT/java"; mkdir -p "$OUT/java"
for d in "$OUT/work-$first"/modules/java_bindings_generator/gen/java "$OUT/work-$first"/modules/java/generator/src/java "$SRC"/modules/java/generator/src/java; do
  [ -d "$d" ] && cp -r "$d"/. "$OUT/java"/
done
cp -r "$SRC"/modules/java/generator/android/java/. "$OUT/java"/ 2>/dev/null || true
cp -r "$SRC"/modules/java/generator/android-21/java/. "$OUT/java"/ 2>/dev/null || true
cp -r "$SRC"/modules/java/generator/android-24/java/. "$OUT/java"/ 2>/dev/null || true
cp -r "$OUT/work-$first"/modules/java_bindings_generator/gen/android/java/. "$OUT/java"/
find "$OUT/java" -name '*.in' -delete
# Camera view helpers need OpenCV's Android resources and are not used by the app.
rm -rf "$OUT"/java/org/opencv/osgi
rm -f "$OUT"/java/org/opencv/android/{Camera,JavaCamera,NativeCamera,FpsMeter}*.java
find "$OUT/java" -name '*.kt' -delete
echo "done: $OUT"
