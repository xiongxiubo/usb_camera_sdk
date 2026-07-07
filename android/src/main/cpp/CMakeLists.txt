cmake_minimum_required(VERSION 3.22.1)

project(flyphoto_gphoto2_bridge LANGUAGES CXX)

add_library(gphoto2 SHARED IMPORTED)
set_target_properties(gphoto2 PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libgphoto2.so")

add_library(gphoto2_port SHARED IMPORTED)
set_target_properties(gphoto2_port PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libgphoto2_port.so")

add_library(libusb SHARED IMPORTED)
set_target_properties(libusb PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libusb-1.0.so")

add_library(libltdl SHARED IMPORTED)
set_target_properties(libltdl PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libltdl.so")

add_library(flyphoto_gphoto2_bridge SHARED flyphoto_gphoto2_bridge.cpp)

find_library(log-lib log)

target_compile_features(flyphoto_gphoto2_bridge PRIVATE cxx_std_17)

target_include_directories(flyphoto_gphoto2_bridge PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include")

target_link_libraries(flyphoto_gphoto2_bridge PRIVATE
    gphoto2
    gphoto2_port
    libusb
    libltdl
    ${log-lib})
