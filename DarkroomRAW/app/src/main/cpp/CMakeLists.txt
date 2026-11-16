cmake_minimum_required(VERSION 3.22.1)
project("darkroom_raw" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/LibRaw)

file(GLOB_RECURSE LIBRAW_SOURCES
    "${LIBRAW_DIR}/src/decoders/*.cpp"
    "${LIBRAW_DIR}/src/demosaic/*.cpp"
    "${LIBRAW_DIR}/src/integration/*.cpp"
    "${LIBRAW_DIR}/src/metadata/*.cpp"
    "${LIBRAW_DIR}/src/postprocessing/*.cpp"
    "${LIBRAW_DIR}/src/preprocessing/*.cpp"
    "${LIBRAW_DIR}/src/tables/*.cpp"
    "${LIBRAW_DIR}/src/utils/*.cpp"
    "${LIBRAW_DIR}/src/write/*.cpp"
    "${LIBRAW_DIR}/src/libraw_c_api.cpp"
    "${LIBRAW_DIR}/src/libraw_datastream.cpp"
)

add_library(raw_static STATIC ${LIBRAW_SOURCES})
target_include_directories(raw_static PUBLIC ${LIBRAW_DIR})
target_compile_definitions(raw_static PUBLIC
    LIBRAW_NODLL
    USE_OPENMP
)
target_compile_options(raw_static PRIVATE
    -O3 -ffast-math -fexceptions -frtti -fopenmp -Wno-deprecated-declarations
)

add_library(darkroom_raw SHARED
    libraw_bridge.cpp
)

target_include_directories(darkroom_raw PRIVATE ${LIBRAW_DIR})
target_compile_options(darkroom_raw PRIVATE -O3 -ffast-math -fopenmp)

find_library(log-lib log)
find_library(GLESv3-lib GLESv3)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(darkroom_raw
    raw_static
    -fopenmp
    -static-openmp
    ${log-lib}
    ${GLESv3-lib}
    ${jnigraphics-lib}
)
