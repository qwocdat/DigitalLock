cmake_minimum_required(VERSION 3.22.1)
project("digitallock_glass" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

add_library(
    digitallock_glass
    SHARED
    src/glass_refraction_engine.cpp
)

find_library(log-lib log)
find_library(gles3-lib GLESv3)

target_link_libraries(
    digitallock_glass
    ${log-lib}
    ${gles3-lib}
)
