# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0

cmake_minimum_required(VERSION 3.22.1)
project(opencamera_pipeline CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Reproducible builds: no absolute checkout paths in debug info, so the build id does not
# depend on where the tree was cloned.
get_filename_component(OPENCAMERA_MODULE_DIR "${CMAKE_SOURCE_DIR}/../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${OPENCAMERA_MODULE_DIR}=pipeline" "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build")

option(OPENCAMERA_PIPELINE_TESTS "Build the native pipeline test executable" OFF)

add_library(opencamera_mf STATIC
    mf/align.cpp
    mf/finish.cpp
    mf/merge.cpp
    mf/pipeline.cpp
    mf/raw.cpp
    mf/superres.cpp
    stack/stack.cpp
)
target_include_directories(opencamera_mf PUBLIC mf stack)
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
    # ML feature pixel ops (bokeh, mask refinement, skin smoothing, classical denoise).
    add_library(opencamera_ai SHARED ai/ai_ops.cpp ai/ai_core.cpp)
    target_compile_options(opencamera_ai PRIVATE -O3 -ffast-math -Wall)
    target_link_libraries(opencamera_ai jnigraphics)
    target_link_options(opencamera_ai PRIVATE "-Wl,-z,max-page-size=16384")
    # Editor retouch ops (inpainting, healing, skin, face colour, warp); shares the guided filter.
    add_library(opencamera_retouch SHARED
        retouch/retouch_jni.cpp
        retouch/patchmatch.cpp
        retouch/poisson_blend.cpp
        retouch/freq_sep.cpp
        retouch/face_color.cpp
        retouch/mls_warp.cpp
        ai/ai_core.cpp
    )
    target_compile_options(opencamera_retouch PRIVATE -O3 -ffast-math -Wall)
    target_link_libraries(opencamera_retouch jnigraphics)
    target_link_options(opencamera_retouch PRIVATE "-Wl,-z,max-page-size=16384")
    # 16 KB page devices (Android 15+) refuse 4 KB-aligned LOAD segments.
    target_link_options(opencamera_pipeline PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_options(opencamera_looks PRIVATE "-Wl,-z,max-page-size=16384")
    # Camera-maker RAW: LibRaw (LGPL-2.1 / CDDL-1.0, third_party/LibRaw) is its own shared
    # library so it can be replaced; the Apache-2.0 JNI glue links to it dynamically.
    set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/LibRaw)
    add_library(opencamera_libraw SHARED
        ${LIBRAW_DIR}/src/libraw_datastream.cpp
        ${LIBRAW_DIR}/src/libraw_c_api.cpp
        ${LIBRAW_DIR}/src/tables/cameralist.cpp
        ${LIBRAW_DIR}/src/decoders/fuji_compressed.cpp
        ${LIBRAW_DIR}/src/decoders/crx.cpp
        ${LIBRAW_DIR}/src/decoders/pana8.cpp
        ${LIBRAW_DIR}/src/decoders/fp_dng.cpp
        ${LIBRAW_DIR}/src/decoders/decoders_libraw.cpp
        ${LIBRAW_DIR}/src/decoders/olympus14.cpp
        ${LIBRAW_DIR}/src/decoders/sonycc.cpp
        ${LIBRAW_DIR}/src/decompressors/losslessjpeg.cpp
        ${LIBRAW_DIR}/src/decoders/unpack.cpp
        ${LIBRAW_DIR}/src/decoders/unpack_thumb.cpp
        ${LIBRAW_DIR}/src/integration/rawspeed_glue.cpp
        ${LIBRAW_DIR}/src/integration/dngsdk_glue.cpp
        ${LIBRAW_DIR}/src/tables/colorconst.cpp
        ${LIBRAW_DIR}/src/utils/utils_libraw.cpp
        ${LIBRAW_DIR}/src/utils/init_close_utils.cpp
        ${LIBRAW_DIR}/src/utils/decoder_info.cpp
        ${LIBRAW_DIR}/src/utils/open.cpp
        ${LIBRAW_DIR}/src/utils/phaseone_processing.cpp
        ${LIBRAW_DIR}/src/utils/thumb_utils.cpp
        ${LIBRAW_DIR}/src/write/tiff_writer.cpp
        ${LIBRAW_DIR}/src/preprocessing/subtract_black.cpp
        ${LIBRAW_DIR}/src/postprocessing/postprocessing_utils.cpp
        ${LIBRAW_DIR}/src/postprocessing/dcraw_process.cpp
        ${LIBRAW_DIR}/src/preprocessing/raw2image.cpp
        ${LIBRAW_DIR}/src/postprocessing/mem_image.cpp
        ${LIBRAW_DIR}/src/x3f/x3f_utils_patched.cpp
        ${LIBRAW_DIR}/src/x3f/x3f_parse_process.cpp
        ${LIBRAW_DIR}/src/utils/read_utils.cpp
        ${LIBRAW_DIR}/src/utils/curves.cpp
        ${LIBRAW_DIR}/src/utils/utils_dcraw.cpp
        ${LIBRAW_DIR}/src/tables/colordata.cpp
        ${LIBRAW_DIR}/src/decoders/canon_600.cpp
        ${LIBRAW_DIR}/src/decoders/decoders_dcraw.cpp
        ${LIBRAW_DIR}/src/decoders/decoders_libraw_dcrdefs.cpp
        ${LIBRAW_DIR}/src/decoders/generic.cpp
        ${LIBRAW_DIR}/src/decoders/kodak_decoders.cpp
        ${LIBRAW_DIR}/src/decoders/dng.cpp
        ${LIBRAW_DIR}/src/decoders/smal.cpp
        ${LIBRAW_DIR}/src/decoders/load_mfbacks.cpp
        ${LIBRAW_DIR}/src/metadata/sony.cpp
        ${LIBRAW_DIR}/src/metadata/nikon.cpp
        ${LIBRAW_DIR}/src/metadata/samsung.cpp
        ${LIBRAW_DIR}/src/metadata/cr3_parser.cpp
        ${LIBRAW_DIR}/src/metadata/canon.cpp
        ${LIBRAW_DIR}/src/metadata/epson.cpp
        ${LIBRAW_DIR}/src/metadata/olympus.cpp
        ${LIBRAW_DIR}/src/metadata/leica.cpp
        ${LIBRAW_DIR}/src/metadata/fuji.cpp
        ${LIBRAW_DIR}/src/metadata/adobepano.cpp
        ${LIBRAW_DIR}/src/metadata/pentax.cpp
        ${LIBRAW_DIR}/src/metadata/p1.cpp
        ${LIBRAW_DIR}/src/metadata/makernotes.cpp
        ${LIBRAW_DIR}/src/metadata/exif_gps.cpp
        ${LIBRAW_DIR}/src/metadata/kodak.cpp
        ${LIBRAW_DIR}/src/metadata/tiff.cpp
        ${LIBRAW_DIR}/src/metadata/ciff.cpp
        ${LIBRAW_DIR}/src/metadata/mediumformat.cpp
        ${LIBRAW_DIR}/src/metadata/minolta.cpp
        ${LIBRAW_DIR}/src/metadata/identify_tools.cpp
        ${LIBRAW_DIR}/src/metadata/hasselblad_model.cpp
        ${LIBRAW_DIR}/src/metadata/normalize_model.cpp
        ${LIBRAW_DIR}/src/metadata/identify.cpp
        ${LIBRAW_DIR}/src/metadata/misc_parsers.cpp
        ${LIBRAW_DIR}/src/tables/wblists.cpp
        ${LIBRAW_DIR}/src/postprocessing/postprocessing_aux.cpp
        ${LIBRAW_DIR}/src/postprocessing/postprocessing_utils_dcrdefs.cpp
        ${LIBRAW_DIR}/src/postprocessing/aspect_ratio.cpp
        ${LIBRAW_DIR}/src/demosaic/misc_demosaic.cpp
        ${LIBRAW_DIR}/src/demosaic/xtrans_demosaic.cpp
        ${LIBRAW_DIR}/src/demosaic/ahd_demosaic.cpp
        ${LIBRAW_DIR}/src/demosaic/dht_demosaic.cpp
        ${LIBRAW_DIR}/src/demosaic/aahd_demosaic.cpp
        ${LIBRAW_DIR}/src/demosaic/dcb_demosaic.cpp
        ${LIBRAW_DIR}/src/write/file_write.cpp
        ${LIBRAW_DIR}/src/preprocessing/ext_preprocess.cpp
        ${LIBRAW_DIR}/src/write/apply_profile.cpp
    )
    target_include_directories(opencamera_libraw PUBLIC ${LIBRAW_DIR})
    target_compile_definitions(opencamera_libraw PRIVATE LIBRAW_LIBRARY_BUILD NO_JASPER NO_JPEG NO_LCMS)
    target_compile_options(opencamera_libraw PRIVATE -O2 -fexceptions -frtti -w)
    target_link_options(opencamera_libraw PRIVATE "-Wl,-z,max-page-size=16384")
    add_library(opencamera_rawjni SHARED raw/libraw_jni.cpp)
    target_include_directories(opencamera_rawjni PRIVATE ${LIBRAW_DIR})
    target_link_libraries(opencamera_rawjni opencamera_libraw)
    target_link_options(opencamera_rawjni PRIVATE "-Wl,-z,max-page-size=16384")
endif()
