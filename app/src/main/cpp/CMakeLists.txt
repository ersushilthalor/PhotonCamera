cmake_minimum_required(VERSION 3.22.1)

project("my-native-lib" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(my-native-lib SHARED
    direct_buffer_jni.cpp
    raw10_unpacker_jni.cpp
    dcp_parser_jni.cpp
    lut_processor_jni.cpp
    gl_transfer_jni.cpp
    raw_demosaic_jni.cpp
    mgc_denoise_jni.cpp
    mgc_guided_upsample_jni.cpp
    mgc_raisr_jni.cpp
    mgc_sharpen_jni.cpp
    spektrafilm_jni.cpp
    raw_legacy_auto_exposure_solver.cpp
    dng_hdrnet_pgtm_jni.cpp
    mgc_eis_jni.cpp
    yuv_processor_jni.cpp
    lossless_jpeg_jni.cpp
    jpeg444_export_jni.cpp
    dncnn_denoise_jni.cpp
    gles_processor_jni.cpp
    mgc_spatial_processor_jni.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(gles3-lib GLESv3)
find_library(egl-lib EGL)
find_library(android-lib android)

target_link_libraries(my-native-lib
    ${log-lib}
    ${jnigraphics-lib}
    ${gles3-lib}
    ${egl-lib}
    ${android-lib}
    m
)
