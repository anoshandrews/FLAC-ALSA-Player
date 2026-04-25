cmake_minimum_required(VERSION 3.22.1)

project(music_player_native)

add_library(
    music_player_native
    SHARED
    PCMBuffer.cpp
    UsbAudioEngine.cpp
)

find_library(log-lib log)

target_link_libraries(
    music_player_native
    ${log-lib}
)
