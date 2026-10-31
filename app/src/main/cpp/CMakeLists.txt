cmake_minimum_required(VERSION 3.18)
project(saprobe CXX)

add_library(saprobe SHARED vulkan_probe.cpp)

# libvulkan.so ships with the NDK sysroot (API 24+).
target_link_libraries(saprobe vulkan log)
