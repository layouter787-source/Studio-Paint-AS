cmake_minimum_required(VERSION 3.22.1)
project(studiopaint_native C CXX)

# libmypaint (licença ISC) é descarregado no momento da compilação a partir da release oficial,
# que já inclui os ficheiros gerados (mypaint-brush-settings-gen.h). Para builds reprodutíveis,
# fixe o hash acrescentando URL_HASH SHA256=... depois de o verificar.
include(FetchContent)
FetchContent_Declare(libmypaint
    URL https://github.com/mypaint/libmypaint/releases/download/v1.6.1/libmypaint-1.6.1.tar.xz)
FetchContent_GetProperties(libmypaint)
if(NOT libmypaint_POPULATED)
    FetchContent_Populate(libmypaint)
endif()
set(MP ${libmypaint_SOURCE_DIR})

add_library(mypaint_core STATIC
    ${MP}/mypaint.c
    ${MP}/mypaint-brush.c
    ${MP}/mypaint-brush-settings.c
    ${MP}/mypaint-mapping.c
    ${MP}/mypaint-rectangle.c
    ${MP}/mypaint-surface.c
    ${MP}/helpers.c
    ${MP}/rng-double.c)

# O shim vem ANTES do código da libmypaint: o nosso config.h/mypaint-config.h desliga GLib e gettext,
# que não existem no NDK (erros 'glib.h' e 'libintl.h' not found).
target_include_directories(mypaint_core BEFORE PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/shim)
target_include_directories(mypaint_core PUBLIC ${MP})
target_compile_definitions(mypaint_core PRIVATE HAVE_CONFIG_H=1 ENABLE_NLS=0 MYPAINT_CONFIG_USE_GLIB=0)
target_compile_options(mypaint_core PRIVATE -w -fPIC)

add_library(studiopaint_native SHARED native_bridge.cpp)
target_link_libraries(studiopaint_native mypaint_core jnigraphics log m)
