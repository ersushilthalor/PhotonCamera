cmake_minimum_required(VERSION 3.22.1)
project("photon-video-native")

find_package(Threads REQUIRED)

# Video-only native layer:
# - MGC gyro/frame reconstruction for video EIS.
# - EGL HardwareBuffer import used by the live video stabilization path.
# - Optional GLES timer queries used by GPU scheduling.
# All RAW/DNG/photo encoders, denoisers and profile engines have been removed.
add_library(my-native-lib SHARED
    gles_gpu_timer_query_jni.cpp
    gles_hardware_buffer_jni.cpp
    mgc_eis_reconstruction_jni.cpp
    mgc_eis_reconstruction/mgc_eis_reconstruction.cpp
    mgc_eis_reconstruction/mgc_type18_engine.cpp
    mgc_eis_reconstruction/mgc_type18_gyro.cpp
    mgc_eis_reconstruction/mgc_type18_lookahead.cpp
    mgc_eis_reconstruction/mgc_type18_projection.cpp
)

target_include_directories(
    my-native-lib
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/mgc_eis_reconstruction"
)
target_compile_features(my-native-lib PRIVATE cxx_std_17)
target_compile_options(my-native-lib PRIVATE -O3 -fno-fast-math -ffp-contract=off)
target_link_libraries(
    my-native-lib
    PRIVATE
    Threads::Threads
    log
    android
    EGL
    GLESv3
)
