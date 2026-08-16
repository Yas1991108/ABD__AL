cmake_minimum_required(VERSION 3.22.1)
project(lamda_llama_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp is NOT vendored in this repo — it's pulled in as a git submodule
# so this repo doesn't have to carry (and constantly rebase against) upstream
# C++ sources. Set it up once with:
#
#   git submodule add https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp
#   git submodule update --init --recursive
#
# Pin to a known-good tag rather than tracking a moving branch, since
# llama.cpp's C API does change between releases:
#
#   cd app/src/main/cpp/llama.cpp && git checkout b4200 && cd -
#   (pick whatever recent release tag you like — b4200 is just an example
#   anchor; if the JNI code below fails to compile against a newer tag,
#   the fix is almost always a renamed function in llama.h, not a logic bug)
# ---------------------------------------------------------------------------

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp submodule not found at app/src/main/cpp/llama.cpp.\n"
        "Run:\n"
        "  git submodule add https://github.com/ggerganov/llama.cpp app/src/main/cpp/llama.cpp\n"
        "  git submodule update --init --recursive")
endif()

# We only need the library, not llama.cpp's own examples/tests/server.
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# CPU-only build — no CUDA/Metal/Vulkan on Android, but ggml still picks up
# NEON automatically on arm64-v8a for a solid speed-up over generic C.
add_subdirectory(llama.cpp)

add_library(llama-jni SHARED
    llama-jni.cpp
)

target_include_directories(llama-jni PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)

target_link_libraries(llama-jni
    llama
    ggml
    android
    log
)
