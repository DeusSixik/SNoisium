cmake_minimum_required(VERSION 3.15)
project(MinecraftDensity LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

add_executable(${PROJECT_NAME} main.cpp)

if(MSVC)
    # Оптимизации для Windows (MSVC)
    target_compile_options(${PROJECT_NAME} PRIVATE /W4 /WX /O2 /Oi /Ot /GL)
    target_link_options(${PROJECT_NAME} PRIVATE /LTCG)
else()
    # Оптимизации для Linux/MinGW (GCC/Clang)
    target_compile_options(${PROJECT_NAME} PRIVATE 
        -Wall -Wextra -Wpedantic -Werror
        -Wshadow -Wconversion -Wnull-dereference -Wcast-align
    )

    # Флаги для "горячего пути" в режиме Release
    target_compile_options(${PROJECT_NAME} PRIVATE
        $<$<CONFIG:Release>:-Ofast>           # Агрессивнее чем -O3, включает -ffast-math
        $<$<CONFIG:Release>:-fno-plt>          # Прямые вызовы функций без таблицы переходов
        $<$<CONFIG:Release>:-funroll-loops>    # Принудительное развертывание циклов
    )

    # Включаем LTO для линковщика
    target_link_options(${PROJECT_NAME} PRIVATE
        $<$<CONFIG:Release>:-flto>
    )
endif()