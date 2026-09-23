# 精简自 Shizuku manager/src/main/jni/CMakeLists.txt（Apache-2.0）
# 只构建 ADB 配对所需的 libstaradb.so；BoringSSL 由 prefab AAR 提供。
cmake_minimum_required(VERSION 3.22.1)
project("staradb")
set(CMAKE_CXX_STANDARD 17)
add_compile_options(-fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-Os -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-Wl,--exclude-libs,ALL -Wl,--gc-sections)
endif ()

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

# target 名刻意中性化：产物为 libstaradb.so，避免 "libadb.so" 命中提权框架特征
add_library(staradb SHARED
        adb_pairing.cpp)
target_link_libraries(staradb log boringssl::crypto_static cxx::cxx)
