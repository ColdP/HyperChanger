cmake_minimum_required(VERSION 3.22.1)
project(HyperChangerDock LANGUAGES CXX ASM)
add_library(hyperchanger_dock SHARED
    dock_entry.cpp
    targets/home/dock_native_hooks.cpp
    targets/home/dock_native_motion.cpp
    targets/home/dock_native_motion_arm64.S)
target_include_directories(hyperchanger_dock PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(hyperchanger_dock PRIVATE cxx_std_20)
target_compile_options(hyperchanger_dock PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(hyperchanger_dock PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(hyperchanger_dock PRIVATE android binder_ndk log dl)
