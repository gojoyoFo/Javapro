cmake_minimum_required(VERSION 3.22.1)
project(fps_core)
add_executable(fps_core fps_core.cpp)
target_link_libraries(fps_core log)