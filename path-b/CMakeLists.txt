# Path B, stage B0: PCSX2's GS core + Vulkan renderer for Android ARM64.
#
# Standalone build (not part of the shipping game's CMake). Modelled on
# thirdparty/pcsx2-gs/CMakeLists.txt (the Windows/DX build of the same GS at
# the same upstream commit), with these deltas:
#   - Vulkan renderer instead of DX11/DX12; no D3D12MA, no WIL, no DirectX headers.
#   - No software renderer and no xbyak: SW sources are excluded; the shared
#     vertex-conversion machinery they carried lives in compat/GSVertexSWCompat.cpp.
#   - POSIX common/ sources (Linux/) instead of Windows/ ones.
#   - OVERRIDE_HOST_PAGE_SIZE=0x1000 / cache line 64: upstream's ARM64 default
#     is Apple Silicon's 16 KB page / 128 B line (common/Pcsx2Defs.h); the
#     POCO F5 is a 4 KB-page device. This is the spike's named trap.
#   - shaderc headers come from the NDK's bundled shaderc sources (copied to
#     shaderc-src/); the matching libshaderc_shared.so is built from them with
#     ndk-build (see PATH-B-B0.md). VKShaderCache only dlopens it at run time.
#
# GPL-3.0: the GS sources are PCSX2's (see MANIFEST.md); the glue is ours.
cmake_minimum_required(VERSION 3.21)
project(pcsx2_gs_pathb LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID)
    message(FATAL_ERROR "Path B B0 builds for Android only (arm64-v8a, android-26, c++_static).")
endif()

# Extraction root: mirrors upstream layout (pcsx2/, common/, 3rdparty/).
set(P "${CMAKE_CURRENT_SOURCE_DIR}/src")
set(SHADERC_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/shaderc-src/libshaderc/include")

add_library(pathb_flags INTERFACE)
target_compile_features(pathb_flags INTERFACE cxx_std_20)
target_compile_definitions(pathb_flags INTERFACE
    OVERRIDE_HOST_PAGE_SIZE=0x1000
    OVERRIDE_HOST_CACHE_LINE_SIZE=64
    ENABLE_VULKAN
    IMGUI_USER_CONFIG="pcsx2gs_imconfig.h")
target_include_directories(pathb_flags INTERFACE
    "${P}" "${P}/pcsx2" "${CMAKE_CURRENT_SOURCE_DIR}/compat"
    "${P}/3rdparty/fmt/include" "${P}/3rdparty/imgui/include"
    "${P}/3rdparty/fast_float/include" "${P}/3rdparty/include"
    "${P}/3rdparty/vulkan/include" "${P}/3rdparty/cpuinfo/include"
    "${P}/3rdparty/vixl/include"
    "${SHADERC_INCLUDE_DIR}")

# Upstream code is not warning-clean under clang; the DX build used /W0.
add_library(pathb_warnings INTERFACE)
target_compile_options(pathb_warnings INTERFACE -w)

# --- third party -------------------------------------------------------------
add_library(pathb_fmt STATIC "${P}/3rdparty/fmt/src/format.cc" "${P}/3rdparty/fmt/src/os.cc")
target_link_libraries(pathb_fmt PRIVATE pathb_flags pathb_warnings)

add_library(pathb_imgui STATIC
    "${P}/3rdparty/imgui/src/imgui.cpp" "${P}/3rdparty/imgui/src/imgui_draw.cpp"
    "${P}/3rdparty/imgui/src/imgui_tables.cpp" "${P}/3rdparty/imgui/src/imgui_widgets.cpp"
    "${P}/3rdparty/imgui/src/imgui_stdlib.cpp")
target_link_libraries(pathb_imgui PRIVATE pathb_flags pathb_warnings)

set(CPUINFO_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_MOCK_TESTS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_PKG_CONFIG OFF CACHE BOOL "" FORCE)
set(CPUINFO_LIBRARY_TYPE static CACHE STRING "" FORCE)
add_subdirectory("${P}/3rdparty/cpuinfo" "${CMAKE_CURRENT_BINARY_DIR}/cpuinfo" EXCLUDE_FROM_ALL)

# --- common/ (PCSX2's utility library), POSIX variant ------------------------
# Same subset as the pcsx2-gs build, with Linux/ sources replacing Windows/ ones.
set(COMMON_SOURCES
    AlignedMalloc.cpp Assertions.cpp Console.cpp DynamicLibrary.cpp Error.cpp FileSystem.cpp
    HostSys.cpp MemorySettingsInterface.cpp MD5Digest.cpp Perf.cpp ProgressCallback.cpp
    ReadbackSpinManager.cpp Semaphore.cpp SettingsWrapper.cpp SmallString.cpp StringUtil.cpp
    TextureDecompress.cpp Timer.cpp WindowInfo.cpp
    Linux/LnxHostSys.cpp Linux/LnxThreads.cpp)
list(TRANSFORM COMMON_SOURCES PREPEND "${P}/common/")
# Ours: LnxMisc.cpp is not compiled (it needs D-Bus and X11); AndroidMisc.cpp
# provides its portable symbols and stubs the desktop-only ones. AndroidShm.cpp
# backs the shm_open/shm_unlink that Bionic's headers do not declare.
list(APPEND COMMON_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/compat/AndroidMisc.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/compat/AndroidShm.cpp")
add_library(pathb_common STATIC ${COMMON_SOURCES})
set_source_files_properties("${P}/common/Linux/LnxHostSys.cpp" PROPERTIES
    COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/compat/android_shm.h")
target_link_libraries(pathb_common PRIVATE pathb_flags pathb_warnings cpuinfo)

# --- B1: bake the GS shaders into C++ ----------------------------------------
# GSDevice::ReadShaderSource has a BAKE_SHADERS_IN_CPP path in this snapshot
# (the fork's embedded-resources mechanism): shader sources become byte arrays
# in generated .cpp files that GSDevice.cpp #includes. Baking means
# libpcsx2gs.so needs no resources folder on the device. Generation mirrors
# upstream's cmake/ShaderToCpp.cmake and tools/shader_to_cpp.py exactly for
# the vulkan + common shaders GSDevice.cpp lists; the opengl_* arrays it also
# #includes are generated empty because path B compiles no GL renderer and
# ReadShaderSource is never asked for an opengl path (VK device only).
set(PATHB_SHADERS_CPP_DIR "${CMAKE_CURRENT_BINARY_DIR}/shaders_cpp")
file(MAKE_DIRECTORY "${PATHB_SHADERS_CPP_DIR}")
find_program(PATHB_PYTHON3 NAMES python3 python REQUIRED)
set(PATHB_BAKED_SHADERS "")
foreach(pair
    "common/fxaa.fx common_fxaa"
    "common/ffx_a.h common_ffx_a"
    "common/ffx_cas.h common_ffx_cas"
    "vulkan/cas.glsl vulkan_cas"
    "vulkan/convert.glsl vulkan_convert"
    "vulkan/imgui.glsl vulkan_imgui"
    "vulkan/interlace.glsl vulkan_interlace"
    "vulkan/merge.glsl vulkan_merge"
    "vulkan/present.glsl vulkan_present"
    "vulkan/shadeboost.glsl vulkan_shadeboost"
    "vulkan/tfx.glsl vulkan_tfx")
  string(REPLACE " " ";" pair_list "${pair}")
  list(GET pair_list 0 shader_rel)
  list(GET pair_list 1 shader_var)
  set(shader_out "${PATHB_SHADERS_CPP_DIR}/${shader_var}.cpp")
  add_custom_command(OUTPUT "${shader_out}"
    COMMAND "${PATHB_PYTHON3}" "${CMAKE_CURRENT_SOURCE_DIR}/upstream/tools/shader_to_cpp.py"
            "${P}/bin/resources/shaders/${shader_rel}" "${shader_out}" "${shader_var}"
    DEPENDS "${P}/bin/resources/shaders/${shader_rel}"
            "${CMAKE_CURRENT_SOURCE_DIR}/upstream/tools/shader_to_cpp.py"
    COMMENT "Baking shader ${shader_rel} -> ${shader_var}.cpp"
    VERBATIM)
  list(APPEND PATHB_BAKED_SHADERS "${shader_out}")
endforeach()
foreach(shader_var opengl_cas opengl_convert opengl_imgui opengl_interlace
    opengl_merge opengl_present opengl_shadeboost opengl_tfx_fs opengl_tfx_vgs)
  file(WRITE "${PATHB_SHADERS_CPP_DIR}/${shader_var}.cpp"
    "// Path B: no OpenGL renderer is compiled, so this baked array is never read.\n\nstatic constexpr unsigned char ${shader_var}[1] = { 0 };\n")
endforeach()
add_custom_target(pathb_baked_shaders DEPENDS ${PATHB_BAKED_SHADERS})
target_compile_definitions(pathb_flags INTERFACE BAKE_SHADERS_IN_CPP=1)
target_include_directories(pathb_flags INTERFACE "${PATHB_SHADERS_CPP_DIR}")

# --- GS core + HW renderer + Vulkan renderer ---------------------------------
# From upstream pcsx2/CMakeLists.txt at the pinned commit: pcsx2GSSources plus
# the USE_VULKAN append, minus GSCapture/GSDump/GSLzma/GSPng (stubbed in
# compat/HostStubs.cpp, as in the pcsx2-gs build), minus the SW renderer.
set(GS_SOURCES
    GS/GS.cpp GS/GSClut.cpp GS/GSDrawingContext.cpp GS/GSDrawingEnvironment.cpp GS/GSLocalMemory.cpp
    GS/GSPerfMon.cpp GS/GSRingHeap.cpp GS/GSState.cpp GS/GSTables.cpp GS/GSUtil.cpp GS/GSVector.cpp
    GS/MultiISA.cpp
    GS/Renderers/Common/GSDevice.cpp GS/Renderers/Common/GSDirtyRect.cpp
    GS/Renderers/Common/GSFunctionMap.cpp GS/Renderers/Common/GSRenderer.cpp
    GS/Renderers/Common/GSTexture.cpp GS/Renderers/Common/GSVertexTrace.cpp
    GS/Renderers/Null/GSRendererNull.cpp
    GS/Renderers/HW/GSHwHack.cpp GS/Renderers/HW/GSRendererHW.cpp GS/Renderers/HW/GSTextureCache.cpp
    GS/Renderers/HW/GSTextureReplacements.cpp
    # pcsx2GSSourcesUnshared, minus the three SW-renderer files
    GS/GSBlock.cpp GS/GSLocalMemoryMultiISA.cpp GS/GSXXH.cpp
    GS/Renderers/Common/GSVertexTraceFMM.cpp GS/Renderers/HW/GSRendererHWMultiISA.cpp
    # Vulkan renderer (the USE_VULKAN append in upstream's list)
    GS/Renderers/Vulkan/GSDeviceVK.cpp GS/Renderers/Vulkan/GSTextureVK.cpp
    GS/Renderers/Vulkan/vk_mem_alloc.cpp GS/Renderers/Vulkan/VKBuilders.cpp
    GS/Renderers/Vulkan/VKLoader.cpp GS/Renderers/Vulkan/VKShaderCache.cpp
    GS/Renderers/Vulkan/VKStreamBuffer.cpp GS/Renderers/Vulkan/VKSwapChain.cpp)
list(TRANSFORM GS_SOURCES PREPEND "${P}/pcsx2/")
# Ours: vertex-conversion carve + SW stub, and the texture-loader stub.
list(APPEND GS_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/compat/GSVertexSWCompat.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/compat/TextureReplacementLoadersStub.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/compat/BuildVersion.cpp")
add_library(pathb_gs STATIC ${GS_SOURCES})
add_dependencies(pathb_gs pathb_baked_shaders)
# GS.cpp's POSIX wrapped-memory allocation also uses shm_open (see pathb_common).
set_source_files_properties("${P}/pcsx2/GS/GS.cpp" PROPERTIES
    COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/compat/android_shm.h")
target_link_libraries(pathb_gs PRIVATE pathb_flags pathb_warnings)
target_link_libraries(pathb_gs PUBLIC pathb_common pathb_fmt pathb_imgui cpuinfo)

# --- host side: the stubs/config the GS calls (from the pcsx2-gs port) -------
add_library(pathb_gs_host STATIC compat/HostStubs.cpp compat/Pcsx2ConfigGS.cpp)
target_link_libraries(pathb_gs_host PRIVATE pathb_flags pathb_warnings)
target_include_directories(pathb_gs_host PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/compat")

# --- B0 harness: surfaceless Vulkan device-create test -----------------------
add_executable(vk_device_test test/vk_device_test.cpp)
target_link_libraries(vk_device_test PRIVATE pathb_flags pathb_gs pathb_gs_host)

# --- B1: libpcsx2gs.so, the Vulkan/surfaceless bridge -------------------------
# compat/BridgeVK.cpp implements the C interface of compat/pcsx2gs_api.h
# (plus pcsx2gs_grab_frame) against the Vulkan renderer with a surfaceless
# window; the game's GSPcsx2Backend dlopens this library on Android when
# [video] Renderer=pcsx2. compat/BridgeLinkStubs.cpp carries the link closure
# the B1-prep vktest proved: two real upstream helpers the GS closure needs,
# plus inert placeholders for the excluded software renderer (the HW
# renderer's SW-prim path that references them is master-gated off by
# UserHacks_CPUSpriteRenderBW = 0, the upstream default; see the file header).
# --no-undefined keeps the B0 closure discipline: a missing symbol fails here,
# not on the device.
add_library(pcsx2gs SHARED compat/BridgeVK.cpp compat/BridgeLinkStubs.cpp)
target_link_libraries(pcsx2gs PRIVATE pathb_flags pathb_warnings pathb_gs pathb_gs_host
    -Wl,--no-undefined dl log)
