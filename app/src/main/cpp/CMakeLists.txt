cmake_minimum_required(VERSION 3.22.1)
project(fps_core)

add_executable(fps_core fps_core.cpp)

target_compile_options(fps_core PRIVATE
    -O2
    -ffunction-sections
    -fdata-sections
)

target_link_options(fps_core PRIVATE
    -Wl,--gc-sections
    -static-libstdc++
)
