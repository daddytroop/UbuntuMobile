cmake_minimum_required(VERSION 3.22.1)
project(linxpty CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(linxpty SHARED linx_pty.cpp)

target_compile_options(linxpty PRIVATE -Wall -Wextra -O2 -fvisibility=hidden)

# The native lib is tiny; keep it in the APK's lib dir so System.loadLibrary works.
target_link_libraries(linxpty log)
