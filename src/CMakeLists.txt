cmake_minimum_required(VERSION 3.10)

project(blixou_lib VERSION 0.0.1 LANGUAGES CXX)

add_library(blixou_lib SHARED
    blixou_lib.cpp
    beauty_filter.cpp
)

set_target_properties(blixou_lib PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
)

if(ANDROID)
    target_link_libraries(blixou_lib
        GLESv2
        EGL
        log
    )
endif()
