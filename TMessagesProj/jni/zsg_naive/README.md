# zsg_naive — Naive transport for ZaStoGram Android

Fork-only module. Everything lives in this folder so that merging upstream Telegram never conflicts with it.

## What it covers
1. **Naive proxy** (`NaiveSocket`): a NaiveProxy-compatible server entry in the proxy list (`ProxySettings.Type.NAIVE`).
2. **WSS** (`tgnet/wss/WssSocket.cpp`): the WebSocket relay transport. Its old hand-made TLS / HTTP-upgrade / frame
   code was **deleted** and replaced by the same library (`zsg_ws_*`), so both transports show the same Chrome 158
   handshake. Route selection and health tracking in that file are unchanged.

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
4. Java: `ProxySettings.Type.NAIVE` and its handling in `ConnectionsManager.setProxySettings`; `ProxyWssFallback.isAllowed()` excludes NAIVE (a Naive entry fails closed, never falls back to a direct WSS route).

## Tests
- `tests/check_hooks.py` — the hook lines above are all present.
- `test/naive_socket_host_test.cpp` — NaiveSocket through an epoll loop against a real Caddy + forwardproxy@naive.
- `test/build_wss_host_test.sh` + `wss_socket_host_test.cpp` — the REAL `WssSocket.cpp` (stub logger, lab trust root)
  against a TLS WebSocket echo server, including the "first frame is at least 64 bytes" rule and fail-closed
  certificate checking. The lab servers and captures live in `~/zastogram/naive-ref-lab`.

## Sources needed to build (git submodules)
- `core/` — **naive-core**, the sans-I/O protocol library (private repo `zastogram/naive-core`). It is a private
  submodule on purpose: clone access needs a Forgejo account with access to that repo, so a plain public clone (and
  the push-triggered Actions build) cannot fetch it. Local release builds work because the build host has the access.
- `third_party/boringssl/` — BoringSSL `ea9f3a34c3977144c45d272be81842fd891a0bdb` (Chromium 158.0.8127.88), public.
  Separate from the app's own older `jni/third_party/boringssl`, which stays untouched.
After a fresh clone: `git submodule update --init TMessagesProj/jni/zsg_naive/core TMessagesProj/jni/zsg_naive/third_party/boringssl`.
