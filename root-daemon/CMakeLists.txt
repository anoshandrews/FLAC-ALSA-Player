cmake_minimum_required(VERSION 3.22)

project(root_audio_daemon)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(
    root_audio_daemon
    src/root_audio_daemon.cpp
    src/tinyalsa_shim.cpp
)

target_include_directories(
    root_audio_daemon
    PRIVATE
    include
)

find_library(log-lib log)

target_link_libraries(
    root_audio_daemon
    ${log-lib}
    dl
)
