# whisper JNI wrapper — CMake entry point invoked by AGP externalNativeBuild.
#
# Pinned toolchain (see whisper/build.gradle.kts):
#   NDK   = 26.1.10909125
#   CMake = 3.22.1
#   ABI   = arm64-v8a (default; override with -PandrollmAbis=arm64-v8a,x86_64)
#   minSdk = 28 (ANDROID_PLATFORM=android-28)
#
# Fresh-Windows prerequisite: the SDK-bundled CMake (3.22.1) is built with
# MSVC and requires the "Microsoft Visual C++ Redistributable (x64)"
# (VCRUNTIME140.dll/MSVCP140.dll). Without it cmake.exe exits with
# 0xC0000135 (STATUS_DLL_NOT_FOUND) and Gradle only reports the wrapper
# error "C++ build system [configure] failed". Install via:
#   winget install --id Microsoft.VCRedist.2015+.x64 --silent
# then delete whisper/.cxx and re-sync. Project must live on a local NTFS
# path with plain ASCII and no OneDrive/sync locking (C:\androllm is fine).
cmake_minimum_required(VERSION 3.22)
project(whisper.cpp C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Repo-relative path to the vendored whisper.cpp + bundled ggml.
# Use CMAKE_CURRENT_LIST_DIR (this file's dir) + ABSOLUTE normalization so
# Windows backslashes and ".." segments resolve deterministically regardless
# of CMAKE_SOURCE_DIR (which AGP sets via -H<project>/whisper/src/main/jni/whisper).
get_filename_component(
    WHISPER_LIB_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/whisper.cpp"
    ABSOLUTE)
if(NOT EXISTS "${WHISPER_LIB_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp checkout not found at ${WHISPER_LIB_DIR}. "
        "Expected repo layout <root>/third_party/whisper.cpp (with ggml/ + src/whisper.cpp).")
endif()
message(STATUS "WHISPER_LIB_DIR: ${WHISPER_LIB_DIR}")
message(STATUS "ANDROID_ABI=${ANDROID_ABI} ANDROID_PLATFORM=${ANDROID_PLATFORM} CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}")

# Read the whisper.cpp version for the WHISPER_VERSION compile definition.
# NOTE: set() without PARENT_SCOPE — this IS the top-level dir, so PARENT_SCOPE
# would be a no-op and leave WHISPER_VERSION empty in this scope.
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION "${CMAKE_MATCH_1}")
else()
    set(WHISPER_VERSION "unknown")
endif()
message(STATUS "Whisper version: ${WHISPER_VERSION}")

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_LIST_DIR}/jni.c
)
foreach(_src IN LISTS SOURCE_FILES)
    if(NOT EXISTS "${_src}")
        message(FATAL_ERROR "Missing native source: ${_src}")
    endif()
endforeach()

find_library(LOG_LIB log REQUIRED)
include(FetchContent)

# Hermetic Android defaults for the bundled ggml. AGP already passes
# -DGGML_OPENMP=OFF; force the rest so stale CMake caches or host defaults
# (CCACHE=ON, tests/examples when standalone) can never leak into the AAR.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

# 16 KB page-size compatibility (Android 15+ / Android 17 ELF alignment check):
#  - ggml is built STATIC and linked into the single libwhisper.so, so the APK
#    no longer ships libggml*.so (which NDK r26 linked with 4 KB p_align);
#  - ANDROID_STL=c++_static (whisper/build.gradle.kts) removes NDK r26's
#    4 KB-aligned libc++_shared.so from the APK entirely;
#  - every shared object linked here gets 16 KB LOAD-segment alignment.
# NDK r28+ does this by default; we stay on the pinned r26.1 toolchain.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
string(APPEND CMAKE_SHARED_LINKER_FLAGS
    " -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

add_library(whisper SHARED ${SOURCE_FILES})
target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3)
    target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(whisper PRIVATE -ffunction-sections -fdata-sections)
    target_link_options(whisper PRIVATE -Wl,--gc-sections)
endif()

# ggml is bundled with whisper.cpp; consume its own CMake build.
FetchContent_Declare(ggml SOURCE_DIR "${WHISPER_LIB_DIR}/ggml")
FetchContent_MakeAvailable(ggml)
if(NOT TARGET ggml)
    message(FATAL_ERROR "ggml CMake target not defined after FetchContent_MakeAvailable(ggml)")
endif()
target_link_libraries(whisper PRIVATE ${LOG_LIB} android ggml)
# Static ggml + static libc++ live inside libwhisper.so: keep their symbols
# private so only the JNI entry points (jni.c) are exported.
target_link_options(whisper PRIVATE -Wl,--exclude-libs,ALL)
# Hidden by default in every build type, and a version script so weak libc++
# typeinfo/vtables instantiated in whisper.cpp are not exported either.
target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(whisper PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/jni.map")
set_property(TARGET whisper APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_LIST_DIR}/jni.map")

# Scoped to this target (not global include_directories) so ggml's own
# configure is unaffected.
target_include_directories(whisper PRIVATE
    "${WHISPER_LIB_DIR}"
    "${WHISPER_LIB_DIR}/src"
    "${WHISPER_LIB_DIR}/include"
    "${WHISPER_LIB_DIR}/ggml/include"
    "${WHISPER_LIB_DIR}/ggml/src"
    "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu")
