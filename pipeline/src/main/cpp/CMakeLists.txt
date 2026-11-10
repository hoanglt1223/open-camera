# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0

cmake_minimum_required(VERSION 3.22.1)
project(opencamera_pipeline CXX)

add_library(opencamera_pipeline SHARED pipeline_jni.cpp)
target_link_libraries(opencamera_pipeline log)
# Looks: native still renderer (LUT, tone, vignette, grain).
add_library(opencamera_looks SHARED looks/look_apply.cpp)
target_compile_options(opencamera_looks PRIVATE -O3)
target_link_libraries(opencamera_looks jnigraphics)
