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
2b. `jni/tgnet/Connection.cpp`: in `Connection::connect`, a Naive route forces `ipv6 = 0` (the server's egress family is unknown; an IPv4-only server cannot reach Telegram's IPv6 addresses).
3. `jni/mtproxy/MtProxyOptions.h` (+ `jni/TgNetWrapper.cpp`, `tgnet/MtProxyOptions.java`): the `naive` flag.
4. Java: `ProxySettings.Type.NAIVE` and its handling in `ConnectionsManager.setProxySettings`; `ProxyWssFallback.isAllowed()` excludes NAIVE (a Naive entry fails closed, never falls back to a direct WSS route).

## WebViews (mini apps, in-app browser)
`WebProxy.cpp` is a loopback HTTP proxy (127.0.0.1, random port) that accepts only `CONNECT` and tunnels it through the
selected Naive server over ONE shared Naive connection; `org.telegram.zastogram.naive.NaiveWebProxy` starts it and points
every WebView of the process at it with androidx.webkit `ProxyController` (no direct rule: fail closed). It runs while a
Naive entry is selected, or while the setting "Mini apps through Naive" (`SharedConfig.naiveWebAppsEnabled`, a row in the proxy list screen) is on and the proxy list holds a Naive entry (the first valid one is used). Limits: plain http is refused; any local app can reach the loopback port (ProxyController cannot
send credentials); a WebView without PROXY_OVERRIDE support stays direct (logged). Hooks: `ConnectionsManager.java`
(`NaiveWebProxy.apply(...)` in `setProxySettings` and in the startup restore) and `TgNetWrapper.cpp` (`RegisterWebProxyJni`).

## Tests
- `test/build_webproxy_host_test.sh` + `webproxy_host_test.cpp` — the REAL `WebProxy.cpp` against a real Caddy + forwardproxy:
  one tunnel, 8 concurrent tunnels over one Naive connection, plain http refused, dead target.
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
