/* Cloudflare WARP as an optional ENTRY hop (jni/zsg_naive/WarpEntry.cpp). With the setting on, the TCP connections of
 * tgnet (direct, MTProxy, WSS, Naive) go to a loopback port of the zsgnaive library instead of the real address; the
 * library carries the bytes through a WireGuard tunnel to WARP. A tunnel that is not up leaves every connection direct. */
#ifndef ZSG_NAIVE_WARP_ENTRY_H
#define ZSG_NAIVE_WARP_ENTRY_H

#include <jni.h>
#include <netinet/in.h>

namespace tgnet {
namespace naive {

/* If the tunnel is up and |address| is a public IPv4 address, fills |redirected| with 127.0.0.1:<forward port> and
 * returns true; the caller connects to |redirected| instead. Any thread. */
bool WarpRedirect(const sockaddr_in &address, sockaddr_in *redirected);

bool RegisterWarpJni(JNIEnv *env);

} // namespace naive
} // namespace tgnet

#endif
