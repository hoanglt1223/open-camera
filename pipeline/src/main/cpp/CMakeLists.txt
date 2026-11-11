# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0

cmake_minimum_required(VERSION 3.22.1)
project(opencamera_pipeline CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(OPENCAMERA_PIPELINE_TESTS "Build the native pipeline test executable" OFF)

add_library(opencamera_mf STATIC
    mf/align.cpp
    mf/finish.cpp
    mf/merge.cpp
    mf/pipeline.cpp
    mf/superres.cpp
)
target_include_directories(opencamera_mf PUBLIC mf)
if(MSVC)
    target_compile_options(opencamera_mf PRIVATE /O2 /fp:fast /EHsc)
else()
    target_compile_options(opencamera_mf PRIVATE -O3 -ffast-math -Wall)
endif()
set_target_properties(opencamera_mf PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(OPENCAMERA_PIPELINE_TESTS)
    add_executable(pipeline_test ../../test/cpp/pipeline_test.cpp)
    target_link_libraries(pipeline_test opencamera_mf)
    if(ANDROID)
        target_link_options(pipeline_test PRIVATE -static-libstdc++)
    endif()
else()
    add_library(opencamera_pipeline SHARED pipeline_jni.cpp)
    target_link_libraries(opencamera_pipeline opencamera_mf log)
    # Looks: native still renderer (LUT, tone, vignette, grain).
    add_library(opencamera_looks SHARED looks/look_apply.cpp)
    target_compile_options(opencamera_looks PRIVATE -O3)
    target_link_libraries(opencamera_looks jnigraphics)
endif()
