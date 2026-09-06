# Минимальный нативный слой приложения: фронтенд libretro + JNI.
# Ядро (libcore_nes.so) собирается ОТДЕЛЬНО скриптом scripts/build_core_fceumm.sh
# и подгружается в рантайме через dlopen.
cmake_minimum_required(VERSION 3.22)
project(nesoid CXX)

add_library(nesoid SHARED nesoid.cpp)

target_compile_features(nesoid PRIVATE cxx_std_17)
target_compile_options(nesoid PRIVATE -O2 -Wall -Wextra)
target_link_libraries(nesoid PRIVATE log dl)
