# zsg_naive — Naive transport for ZaStoGram Android

Fork-only module. Everything lives in this folder so that merging upstream Telegram never conflicts with it.

## What it is
A NaiveProxy-compatible client that carries the DC connection through an HTTPS proxy and looks like Chrome 158 on
the wire. The protocol work (TLS handshake, HTTP/2, preamble, CONNECT, padding) is the separate sans-I/O library
**naive-core** (`libzsgnaive.so`, own BoringSSL at Chromium 158's revision). `NaiveSocket` adapts it to tgnet's
`transport::Socket` interface (the same seam WSS uses).

## Hook lines outside this folder (a merge must keep every one — `zsg_naive/tests/check_hooks.py` verifies them)
1. `jni/CMakeLists.txt`: `include(zsg_naive/zsg_naive.cmake)` and `zsg_naive_socket` in tgnet's link libraries.
2. `jni/tgnet/ConnectionSocket.cpp`: `#include "../zsg_naive/NaiveSocket.h"`; the `useNaive` block after
   `OfficialRoute(...)`; the `useNaive ? tgnet::naive::CreateFor(...) : tgnet::wss::CreateSocket(...)` creation; the
   `"naive"` guard on the pooled-socket lookup.
3. `jni/mtproxy/MtProxyOptions.h` (+ `jni/TgNetWrapper.cpp`, `tgnet/MtProxyOptions.java`): the `naive` flag.
4. Java: `ProxySettings.Type.NAIVE` and its handling in `ConnectionsManager.setProxySettings`.

## Sources needed to build (not committed yet)
`core/` = naive-core checkout, `third_party/boringssl/` = BoringSSL `ea9f3a34c3977144c45d272be81842fd891a0bdb`
(Chromium 158.0.8127.88). They are expected as git submodules once naive-core is hosted; until then link or copy them
here, or pass `-DZSG_NAIVE_CORE_DIR=... -DZSG_BORINGSSL_DIR=...`.
