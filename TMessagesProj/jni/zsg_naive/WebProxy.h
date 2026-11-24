/*
 * Loopback HTTP(CONNECT) proxy for the app's WebViews (mini apps, in-app browser), carried through the selected Naive
 * server. Fork-only module (zsg_naive).
 *
 * Why it exists: a WebView has its own Chromium network stack, so tgnet's proxy never sees its traffic. Android's
 * ProxyController can point every WebView of the process at a local proxy; this is that proxy. It accepts only
 * `CONNECT host:port` (every https page, which is all a mini app needs), opens one Naive tunnel per request over a single
 * shared Naive connection (like the real naive client multiplexes), and pipes the bytes. Plain-http requests are refused:
 * nothing may leave through another route (fail closed).
 *
 * The listener is bound to 127.0.0.1 on a random port. Any local app can reach it (ProxyController cannot send
 * credentials); the port is not advertised and the proxy only exists while a Naive entry is selected.
 */
#ifndef ZSG_NAIVE_WEB_PROXY_H
#define ZSG_NAIVE_WEB_PROXY_H

#include <stdint.h>
#include <string>

namespace tgnet {
namespace naive {

struct WebProxyConfig {
    std::string proxyHost;
    uint16_t proxyPort = 443;
    std::string user;
    std::string password;
    std::string caFile;               // tests only; empty = the Android system roots (+ bundled fallback roots)
    bool insecureSkipVerify = false;  // tests only
};

// Starts (or restarts, when the configuration changed) the proxy; returns the loopback port, or 0 on failure.
int StartWebProxy(const WebProxyConfig &config);
void StopWebProxy();

} // namespace naive
} // namespace tgnet

#endif
