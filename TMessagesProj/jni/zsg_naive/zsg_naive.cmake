# ZaStoGram Naive transport: builds libzsgnaive.so (own BoringSSL, Chrome 158 handshake) in a NESTED CMake run and
# exposes it as the imported target `zsgnaive`. A nested build is required because this project already defines
# imported targets named `ssl` and `crypto` (the app's older BoringSSL), which BoringSSL's own CMake would also define.
#
# Inputs (override with -D... if the sources live elsewhere):
#   ZSG_NAIVE_CORE_DIR   the naive-core checkout (sans-IO library, CMake project)
#   ZSG_BORINGSSL_DIR    BoringSSL at the pinned Chrome revision (see core/README.md)
include(ExternalProject)

set(ZSG_NAIVE_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/core" CACHE PATH "naive-core checkout")
set(ZSG_BORINGSSL_DIR "${CMAKE_CURRENT_LIST_DIR}/third_party/boringssl" CACHE PATH "BoringSSL at Chrome 158's revision")
if(NOT EXISTS "${ZSG_NAIVE_CORE_DIR}/CMakeLists.txt" OR NOT EXISTS "${ZSG_BORINGSSL_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "zsg_naive: set ZSG_NAIVE_CORE_DIR (${ZSG_NAIVE_CORE_DIR}) and ZSG_BORINGSSL_DIR (${ZSG_BORINGSSL_DIR}); see jni/zsg_naive/README.md")
endif()

set(ZSG_NAIVE_BINARY_DIR "${CMAKE_BINARY_DIR}/zsg_naive_core")
set(ZSG_NAIVE_LIBRARY "${ZSG_NAIVE_BINARY_DIR}/libzsgnaive.so")
ExternalProject_Add(zsg_naive_ext
        SOURCE_DIR "${ZSG_NAIVE_CORE_DIR}"
        BINARY_DIR "${ZSG_NAIVE_BINARY_DIR}"
        CMAKE_GENERATOR "${CMAKE_GENERATOR}"
        CMAKE_ARGS
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DANDROID_STL=c++_static
            -DCMAKE_BUILD_TYPE=Release
            -DZSG_BORINGSSL_DIR=${ZSG_BORINGSSL_DIR}
            -DZSG_NAIVE_TOOLS=OFF
        # Always run the nested (incremental) build: ExternalProject would otherwise never notice core changes.
        BUILD_ALWAYS 1
        BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target zsgnaive
        INSTALL_COMMAND ""
        BUILD_BYPRODUCTS "${ZSG_NAIVE_LIBRARY}")

add_library(zsgnaive SHARED IMPORTED GLOBAL)
set_target_properties(zsgnaive PROPERTIES
        IMPORTED_LOCATION "${ZSG_NAIVE_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${ZSG_NAIVE_CORE_DIR}/include")
add_dependencies(zsgnaive zsg_naive_ext)

add_library(zsg_naive_socket STATIC ${CMAKE_CURRENT_LIST_DIR}/NaiveSocket.cpp ${CMAKE_CURRENT_LIST_DIR}/TrustRoots.cpp)
target_link_libraries(zsg_naive_socket PUBLIC zsgnaive)
target_include_directories(zsg_naive_socket PUBLIC ${CMAKE_CURRENT_LIST_DIR}/.. ${CMAKE_CURRENT_LIST_DIR}/../third_party/boringssl/include ${CMAKE_CURRENT_LIST_DIR}/../voip/webrtc)
