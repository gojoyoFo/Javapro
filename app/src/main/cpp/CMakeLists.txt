cmake_minimum_required(VERSION 3.22.1)
project(javapro_native)

# ── fps_core: standalone executable (dijalankan via su) ──────────────────────
add_executable(fps_core fps_core.cpp)

set_target_properties(fps_core PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)

target_compile_options(fps_core PRIVATE
    -O2
    -ffunction-sections
    -fdata-sections
)

target_link_options(fps_core PRIVATE
    -Wl,--gc-sections
)

# ── system_info: JNI shared library (dipanggil dari Kotlin) ──────────────────
add_library(
    system_info
    SHARED
    system_info.cpp
)

target_link_libraries(
    system_info
    android
    log
)
