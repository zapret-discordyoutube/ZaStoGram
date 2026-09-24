cmake_minimum_required(VERSION 3.16)

set(CMAKE_CXX_FLAGS "-std=c++14 -DANDROID -g")
set(CMAKE_C_FLAGS "-w -std=c11 -DANDROID -D_LARGEFILE_SOURCE=1 -g -Wno-error=implicit-function-declaration")
set(CMAKE_ASM_FLAGS "${CFLAGS} -x assembler-with-cpp")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -flto=full")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden -flto=full")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto=full -Wl,--gc-sections -Wl,--icf=safe -Wl,-Bsymbolic")

if (${ANDROID_ABI} STREQUAL "armeabi-v7a" OR ${ANDROID_ABI} STREQUAL "arm64-v8a")
    enable_language(ASM)
else()
    enable_language(ASM_NASM)
endif()

set(CAN_COMPILE_SME FALSE CACHE INTERNAL "Disable libyuv SME" FORCE)
add_subdirectory(
        third_party/libyuv
        ${CMAKE_CURRENT_BINARY_DIR}/libyuv
        EXCLUDE_FROM_ALL)

add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libavutil.a)

add_library(avformat STATIC IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libavformat.a)

add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libavcodec.a)

add_library(swresample STATIC IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libswresample.a)

add_library(swscale STATIC IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libswscale.a)

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libcrypto.a)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libssl.a)


#aesige
#add_library(aesige STATIC openssl/aes_ige.c)
#target_compile_options(aesige PUBLIC -Wall -frtti -finline-functions -ffast-math -Oz)
#target_include_directories(aesige PUBLIC third_party/boringssl/include openssl/include)
#target_link_libraries(aesige crypto)


add_library(libvpx STATIC IMPORTED)
set_target_properties(libvpx PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libvpx.a)

add_library(libdav1d STATIC IMPORTED)
set_target_properties(libdav1d PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libdav1d.a)

add_library(libopus STATIC IMPORTED)
set_target_properties(libopus PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libopus.a)

add_library(tde2e STATIC IMPORTED)
set_target_properties(tde2e PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libtde2e.a)

add_library(tdutils STATIC IMPORTED)
set_target_properties(tdutils PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libtdutils.a)

#tgnet
#add_library(mozjpeg STATIC
#        mozjpeg/cjpeg.c mozjpeg/cdjpeg.c mozjpeg/rdgif.c mozjpeg/rdppm.c mozjpeg/rdjpeg.c mozjpeg/rdswitch.c mozjpeg/rdbmp.c
#        mozjpeg/rdtarga.c mozjpeg/wrbmp.c mozjpeg/wrtarga.c mozjpeg/djpeg.c mozjpeg/cdjpeg.c mozjpeg/rdcolmap.c mozjpeg/rdswitch.c
#        mozjpeg/wrgif.c mozjpeg/wrppm.c mozjpeg/jpegtran.c mozjpeg/cdjpeg.c mozjpeg/rdswitch.c mozjpeg/transupp.c mozjpeg/turbojpeg.c
#        mozjpeg/jdatadst-tj.c mozjpeg/jdatasrc-tj.c mozjpeg/jcapimin.c mozjpeg/jcapistd.c mozjpeg/jccoefct.c mozjpeg/jccolor.c
#        mozjpeg/jcdctmgr.c mozjpeg/jchuff.c mozjpeg/jcext.c mozjpeg/jcicc.c mozjpeg/jcinit.c mozjpeg/jcmainct.c mozjpeg/jcmarker.c
#        mozjpeg/jcmaster.c mozjpeg/jcomapi.c mozjpeg/jcparam.c mozjpeg/jcphuff.c mozjpeg/jcprepct.c mozjpeg/jcsample.c
#        mozjpeg/jctrans.c mozjpeg/jdapimin.c mozjpeg/jdapistd.c mozjpeg/jdatadst.c mozjpeg/jdatasrc.c
#        mozjpeg/jdcoefct.c mozjpeg/jdcolor.c mozjpeg/jddctmgr.c mozjpeg/jdhuff.c mozjpeg/jdicc.c mozjpeg/jdinput.c
#        mozjpeg/jdmainct.c mozjpeg/jdmarker.c mozjpeg/jdmaster.c mozjpeg/jdmerge.c mozjpeg/jdphuff.c mozjpeg/jdpostct.c mozjpeg/jdsample.c
#        mozjpeg/jdtrans.c mozjpeg/jerror.c mozjpeg/jfdctflt.c mozjpeg/jfdctfst.c mozjpeg/jfdctint.c mozjpeg/jidctflt.c mozjpeg/jidctfst.c
#        mozjpeg/jidctint.c mozjpeg/jidctred.c mozjpeg/jquant1.c mozjpeg/jquant2.c mozjpeg/jutils.c mozjpeg/jmemmgr.c mozjpeg/jmemnobs.c)
#target_compile_options(mozjpeg PUBLIC
#        -Os)
#set_target_properties(mozjpeg PROPERTIES
#        ANDROID_ARM_MODE arm)
#target_compile_definitions(mozjpeg PUBLIC
#        BMP_SUPPORTED GIF_SUPPORTED PPM_SUPPORTED TARGA_SUPPORTED)
#target_include_directories(mozjpeg PUBLIC
#        mozjpeg
#        mozjpeg/simd/nasm)
#
#if (${ANDROID_ABI} STREQUAL "armeabi-v7a")
#    target_sources(mozjpeg PRIVATE
#            mozjpeg/simd/arm/jsimd_neon.S
#            mozjpeg/simd/arm/jsimd.c)
#    target_compile_definitions(mozjpeg PUBLIC
#            SIZEOF_SIZE_T=4)
#elseif(${ANDROID_ABI} STREQUAL "arm64-v8a")
#    target_sources(mozjpeg PRIVATE
#            mozjpeg/simd/arm64/jsimd_neon.S
#            mozjpeg/simd/arm64/jsimd.c)
#    target_compile_definitions(mozjpeg PUBLIC
#            SIZEOF_SIZE_T=8)
#elseif(${ANDROID_ABI} STREQUAL "x86")
#    set(CMAKE_ASM_NASM_COMPILER_ARG1 "${CMAKE_ASM_NASM_COMPILER_ARG1} -DPIC")
#    target_sources(mozjpeg PRIVATE
#            mozjpeg/simd/i386/jsimd.c
#            mozjpeg/simd/i386/jsimdcpu.asm
#            mozjpeg/simd/i386/jfdctflt-3dn.asm
#            mozjpeg/simd/i386/jidctflt-3dn.asm
#            mozjpeg/simd/i386/jquant-3dn.asm
#            mozjpeg/simd/i386/jccolor-mmx.asm
#            mozjpeg/simd/i386/jcgray-mmx.asm
#            mozjpeg/simd/i386/jcsample-mmx.asm
#            mozjpeg/simd/i386/jdcolor-mmx.asm
#            mozjpeg/simd/i386/jdmerge-mmx.asm
#            mozjpeg/simd/i386/jdsample-mmx.asm
#            mozjpeg/simd/i386/jfdctfst-mmx.asm
#            mozjpeg/simd/i386/jfdctint-mmx.asm
#            mozjpeg/simd/i386/jidctfst-mmx.asm
#            mozjpeg/simd/i386/jidctint-mmx.asm
#            mozjpeg/simd/i386/jidctred-mmx.asm
#            mozjpeg/simd/i386/jquant-mmx.asm
#            mozjpeg/simd/i386/jfdctflt-sse.asm
#            mozjpeg/simd/i386/jidctflt-sse.asm
#            mozjpeg/simd/i386/jquant-sse.asm
#            mozjpeg/simd/i386/jccolor-sse2.asm
#            mozjpeg/simd/i386/jcgray-sse2.asm
#            mozjpeg/simd/i386/jchuff-sse2.asm
#            mozjpeg/simd/i386/jcphuff-sse2.asm
#            mozjpeg/simd/i386/jcsample-sse2.asm
#            mozjpeg/simd/i386/jdcolor-sse2.asm
#            mozjpeg/simd/i386/jdmerge-sse2.asm
#            mozjpeg/simd/i386/jdsample-sse2.asm
#            mozjpeg/simd/i386/jfdctfst-sse2.asm
#            mozjpeg/simd/i386/jfdctint-sse2.asm
#            mozjpeg/simd/i386/jidctflt-sse2.asm
#            mozjpeg/simd/i386/jidctfst-sse2.asm
#            mozjpeg/simd/i386/jidctint-sse2.asm
#            mozjpeg/simd/i386/jidctred-sse2.asm
#            mozjpeg/simd/i386/jquantf-sse2.asm
#            mozjpeg/simd/i386/jquanti-sse2.asm
#            mozjpeg/simd/i386/jccolor-avx2.asm
#            mozjpeg/simd/i386/jcgray-avx2.asm
#            mozjpeg/simd/i386/jcsample-avx2.asm
#            mozjpeg/simd/i386/jdcolor-avx2.asm
#            mozjpeg/simd/i386/jdmerge-avx2.asm
#            mozjpeg/simd/i386/jdsample-avx2.asm
#            mozjpeg/simd/i386/jfdctint-avx2.asm
#            mozjpeg/simd/i386/jidctint-avx2.asm
#            mozjpeg/simd/i386/jquanti-avx2.asm)
#    target_compile_definitions(mozjpeg PUBLIC
#            SIZEOF_SIZE_T=4)
#elseif(${ANDROID_ABI} STREQUAL "x86_64")
#    set(CMAKE_ASM_NASM_COMPILER_ARG1 "${CMAKE_ASM_NASM_COMPILER_ARG1} -DPIC")
#    set(CMAKE_ASM_NASM_FLAGS "${CMAKE_ASM_NASM_FLAGS} -DELF")
#    set(CMAKE_ASM_NASM_DEBUG_FORMAT "dwarf2")
#    set(CMAKE_ASM_NASM_FLAGS "${CMAKE_ASM_NASM_FLAGS} -D__x86_64__")
#    target_sources(mozjpeg PRIVATE
#            mozjpeg/simd/x86_64/jsimd.c
#            mozjpeg/simd/x86_64/jsimdcpu.asm
#            mozjpeg/simd/x86_64/jfdctflt-sse.asm
#            mozjpeg/simd/x86_64/jccolor-sse2.asm
#            mozjpeg/simd/x86_64/jcgray-sse2.asm
#            mozjpeg/simd/x86_64/jchuff-sse2.asm
#            mozjpeg/simd/x86_64/jcphuff-sse2.asm
#            mozjpeg/simd/x86_64/jcsample-sse2.asm
#            mozjpeg/simd/x86_64/jdcolor-sse2.asm
#            mozjpeg/simd/x86_64/jdmerge-sse2.asm
#            mozjpeg/simd/x86_64/jdsample-sse2.asm
#            mozjpeg/simd/x86_64/jfdctfst-sse2.asm
#            mozjpeg/simd/x86_64/jfdctint-sse2.asm
#            mozjpeg/simd/x86_64/jidctflt-sse2.asm
#            mozjpeg/simd/x86_64/jidctfst-sse2.asm
#            mozjpeg/simd/x86_64/jidctint-sse2.asm
#            mozjpeg/simd/x86_64/jidctred-sse2.asm
#            mozjpeg/simd/x86_64/jquantf-sse2.asm
#            mozjpeg/simd/x86_64/jquanti-sse2.asm
#            mozjpeg/simd/x86_64/jccolor-avx2.asm
#            mozjpeg/simd/x86_64/jcgray-avx2.asm
#            mozjpeg/simd/x86_64/jcsample-avx2.asm
#            mozjpeg/simd/x86_64/jdcolor-avx2.asm
#            mozjpeg/simd/x86_64/jdmerge-avx2.asm
#            mozjpeg/simd/x86_64/jdsample-avx2.asm
#            mozjpeg/simd/x86_64/jfdctint-avx2.asm
#            mozjpeg/simd/x86_64/jidctint-avx2.asm
#            mozjpeg/simd/x86_64/jquanti-avx2.asm)
#    target_compile_definitions(mozjpeg PUBLIC
#            SIZEOF_SIZE_T=8)
#endif()

# mtproxy_core: self-contained MTProxy/FakeTLS policy engine (see mtproxy/README.md).
# Depends only on BoringSSL; PUBLIC-exports jni/ so consumers include it as
# "mtproxy/MtProxyX.h". Java/socket layers link against this, never vice versa.
add_library(mtproxy_core STATIC
        mtproxy/MtProxyAdaptivePolicy.cpp
        mtproxy/MtProxyClientHelloPolicy.cpp
        mtproxy/MtProxyDataPathShaper.cpp
        mtproxy/MtProxyEndpointRecorder.cpp
        mtproxy/MtProxyEndpointPolicy.cpp
        mtproxy/MtProxyFailureEvidence.cpp
        mtproxy/MtProxyHandshakePlan.cpp
        mtproxy/MtProxyHandshakeScheduler.cpp
        mtproxy/MtProxyProbeCoordinator.cpp
        mtproxy/MtProxyProbeLease.cpp
        mtproxy/MtProxyRecoveryPolicy.cpp
        mtproxy/MtProxyRetryAuthority.cpp
        mtproxy/MtProxySecretDomain.cpp
        mtproxy/MtProxySocketPublisher.cpp
        mtproxy/MtProxyServerFlightParser.cpp
        mtproxy/MtProxyStartupTimeline.cpp
        mtproxy/MtProxyTerminalDiagnostic.cpp)
target_compile_options(mtproxy_core PUBLIC
        -Wall -frtti -finline-functions -ffast-math -Oz)
set_target_properties(mtproxy_core PROPERTIES
        ANDROID_ARM_MODE arm)
target_compile_definitions(mtproxy_core PUBLIC
        HAVE_PTHREAD)
target_include_directories(mtproxy_core PUBLIC
        third_party/boringssl/include
        ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(mtproxy_core
        ssl
        crypto)

#tgnet
add_library(tgnet STATIC
        openssl/aes_ige.c
        tgnet/ApiScheme.cpp
        tgnet/BuffersStorage.cpp
        tgnet/ByteArray.cpp
        tgnet/ByteStream.cpp
        tgnet/Connection.cpp
        tgnet/ConnectionSession.cpp
        tgnet/ConnectionsManager.cpp
        tgnet/TLSHello.cpp
        tgnet/ConnectionSocket.cpp
        tgnet/ConnectionSocketStateMachine.cpp
        tgnet/wss/WssSocket.cpp
        tgnet/wss/WssPool.cpp
        tgnet/Datacenter.cpp
        tgnet/EventObject.cpp
        tgnet/FileLog.cpp
        tgnet/MTProtoScheme.cpp
        tgnet/NativeByteBuffer.cpp
        tgnet/Request.cpp
        tgnet/Timer.cpp
        tgnet/TLObject.cpp
        tgnet/ProxyCheckInfo.cpp
        tgnet/Handshake.cpp
        tgnet/Config.cpp)
target_compile_options(tgnet PUBLIC
        -Wall -frtti -finline-functions -ffast-math -Oz)
set_target_properties(tgnet PROPERTIES
        ANDROID_ARM_MODE arm)
target_compile_definitions(tgnet PUBLIC
        HAVE_PTHREAD)
target_include_directories(tgnet PUBLIC
        third_party/boringssl/include
        openssl/include)
target_include_directories(tgnet PRIVATE
        voip/webrtc)
target_link_libraries(tgnet
        mtproxy_core
        ssl
        crypto)

# tlottie (prebuilt Rust static library)
add_library(tlottie STATIC IMPORTED)
set_target_properties(tlottie PROPERTIES
        IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/lib/${ANDROID_ABI}/libtlottie.a)

#sqlite
add_library(sqlite STATIC
        sqlite/sqlite3.c)
target_compile_options(sqlite PUBLIC
        -fno-strict-aliasing -Oz)
set_target_properties(sqlite PROPERTIES
        ANDROID_ARM_MODE arm)
target_compile_definitions(sqlite PUBLIC
        NULL=0 SOCKLEN_T=socklen_t LOCALE_NOT_USED ANDROID_NDK DISABLE_IMPORTGL AVOID_TABLES ANDROID_TILE_BASED_DECODE HAVE_STRCHRNUL=0 ANDROID_ARMV6_IDCT)

#breakpad
add_library(breakpad STATIC
        third_party/breakpad/src/client/linux/crash_generation/crash_generation_client.cc
        third_party/breakpad/src/client/linux/handler/exception_handler.cc
        third_party/breakpad/src/client/linux/handler/minidump_descriptor.cc
        third_party/breakpad/src/client/linux/log/log.cc
        third_party/breakpad/src/client/linux/dump_writer_common/thread_info.cc
        third_party/breakpad/src/client/linux/dump_writer_common/seccomp_unwinder.cc
        third_party/breakpad/src/client/linux/dump_writer_common/ucontext_reader.cc
        third_party/breakpad/src/client/linux/microdump_writer/microdump_writer.cc
        third_party/breakpad/src/client/linux/minidump_writer/linux_dumper.cc
        third_party/breakpad/src/client/linux/minidump_writer/linux_ptrace_dumper.cc
        third_party/breakpad/src/client/linux/minidump_writer/minidump_writer.cc
        third_party/breakpad/src/client/minidump_file_writer.cc
        third_party/breakpad/src/common/android/breakpad_getcontext.S
        third_party/breakpad/src/common/convert_UTF.c
        third_party/breakpad/src/common/md5.cc
        third_party/breakpad/src/common/string_conversion.cc
        third_party/breakpad/src/common/linux/elfutils.cc
        third_party/breakpad/src/common/linux/file_id.cc
        third_party/breakpad/src/common/linux/guid_creator.cc
        third_party/breakpad/src/common/linux/linux_libc_support.cc
        third_party/breakpad/src/common/linux/memory_mapped_file.cc
        third_party/breakpad/src/common/linux/safe_readlink.cc)
set_target_properties(breakpad PROPERTIES ANDROID_ARM_MODE arm)
set_property(SOURCE third_party/breakpad/src/common/android/breakpad_getcontext.S PROPERTY LANGUAGE C)
target_include_directories(breakpad PUBLIC
        third_party/breakpad/src/common/android/include
        third_party/breakpad/src)


#voip
include(${CMAKE_HOME_DIRECTORY}/voip/CMakeLists.txt)

set(NATIVE_LIB "tmessages.49")

#tmessages
add_library(${NATIVE_LIB} SHARED
        jni.c
        audio.c
        webm_encoder.c
        image.cpp
        intro/IntroRenderer.c
        SqliteWrapper.cpp
        utilities.cpp
        gifvideo.cpp
        lottie.cpp
        TgNetWrapper.cpp
        ffmpeg_jni.cc
        fast-edge.cpp
        genann.c
        secureid_ocr.cpp
        tde2e/bridge.cpp
        tests.cpp
)

target_compile_options(${NATIVE_LIB} PUBLIC
        -ffast-math -Oz -funroll-loops -ffast-math -fno-strict-aliasing -fno-math-errno)

target_compile_definitions(${NATIVE_LIB} PUBLIC
        BSD=1 NULL=0 SOCKLEN_T=socklen_t ANDROID_NDK DISABLE_IMPORTGL AVOID_TABLES ANDROID_TILE_BASED_DECODE __STDC_CONSTANT_MACROS ANDROID_ARMV6_IDCT OPUS_BUILD FIXED_POINT USE_ALLOCA restrict= __EMX__ LOCALE_NOT_USED HAVE_LRINT HAVE_LRINTF)

target_sources(${NATIVE_LIB} PRIVATE

        third_party/xiph/opusfile/src/info.c
        third_party/xiph/opusfile/src/internal.c
        third_party/xiph/opusfile/src/opusfile.c
        third_party/xiph/opusfile/src/stream.c
        third_party/xiph/ogg/src/bitwise.c
        third_party/xiph/ogg/src/framing.c)

target_include_directories(${NATIVE_LIB} PUBLIC
        third_party/xiph/opus/include
        third_party/xiph/opus/silk
        third_party/xiph/opus/silk/fixed
        third_party/xiph/opus/celt
        third_party/xiph/opus
        third_party/xiph/opusfile/include
        third_party/xiph/ogg/include
        third_party/libyuv/include
        third_party/boringssl/include
        openssl/include
        prebuild/include/${ANDROID_ABI}
        third_party/ffmpeg
        third_party/libvpx
        third_party/dav1d/include
        emoji
        exoplayer/include
        intro
        tlottie/include
        tgcalls/
        webrtc/
        td/tde2e
        td/tde2e/td/e2e
)

target_link_options(${NATIVE_LIB} PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(${NATIVE_LIB} PRIVATE "-Wl,-z,common-page-size=16384")
target_link_options(${NATIVE_LIB} PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
target_link_libraries(${NATIVE_LIB}
        -Wl,--whole-archive rnnoise openh264 voipandroid -Wl,--no-whole-archive
        tgcalls
        tgcalls_tp
        tgnet
        tlottie
        sqlite
        swscale
        avformat
        avcodec
        swresample
        libvpx
        libdav1d
        libopus
        tde2e
        tdutils
        yuv
        avutil
        ssl
        crypto
        jnigraphics
        log
        z
        GLESv2
        EGL
        android
        OpenSLES
        cpufeatures
        breakpad)

#if (${ANDROID_ABI} STREQUAL "x86" OR ${ANDROID_ABI} STREQUAL "x86_64")
#    target_link_libraries(${NATIVE_LIB}
#            -Wl,--whole-archive vpxasm -Wl,--no-whole-archive
#            c)
#endif()

include(AndroidNdkModules)
android_ndk_import_module_cpufeatures()
