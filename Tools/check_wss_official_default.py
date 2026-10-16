#!/usr/bin/env python3
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
SHARED_CONFIG = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java"
PROXY_LIST = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java"
WSS_CPP = ROOT / "TMessagesProj/jni/tgnet/wss/WssSocket.cpp"
CONNECTION_CPP = ROOT / "TMessagesProj/jni/tgnet/Connection.cpp"
SOCKET_CPP = ROOT / "TMessagesProj/jni/tgnet/ConnectionSocket.cpp"
CONNECTIONS_JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java"
FILE_LOADER = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/FileLoadOperation.java"


def require(condition: bool, message: str) -> None:
    if not condition:
        print(f"FAIL: {message}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    shared_config = SHARED_CONFIG.read_text(encoding="utf-8", errors="replace")
    proxy_list = PROXY_LIST.read_text(encoding="utf-8", errors="replace")
    wss_cpp = WSS_CPP.read_text(encoding="utf-8", errors="replace")
    connection_cpp = CONNECTION_CPP.read_text(encoding="utf-8", errors="replace")
    socket_cpp = SOCKET_CPP.read_text(encoding="utf-8", errors="replace")
    connections_java = CONNECTIONS_JAVA.read_text(encoding="utf-8", errors="replace")
    file_loader = FILE_LOADER.read_text(encoding="utf-8", errors="replace")

    require('preferences.getBoolean("wssTransportEnabled", true)' in shared_config
            and "WSS включён по умолчанию" in shared_config,
            "WSS must be enabled by default while keeping an explicit user choice")
    require('preferences.getInt("wssTransportMode"' in shared_config,
            "legacy WSS mode must migrate to the checkbox")
    require('.remove("wssHost")' in shared_config and '.remove("wssPath")' in shared_config,
            "custom gateway keys must be removed during migration")
    require("UseWssTransport" in proxy_list and "wssTransportEnabled" in proxy_list,
            "the official transport must be exposed as a normal checkbox")
    require('prefix = "kws" + std::to_string(dcId)' in wss_cpp,
            "official WSS must generate Telegram DC1-DC5 relay names")
    require("dcId < 1 || dcId > 5" in wss_cpp,
            "official WSS relay catalog must cover exactly DC1-DC5")
    require('mediaConnection ? "-1.web.telegram.org"' in wss_cpp,
            "official WSS must select the media relay variant")
    require("getDatacenterId(), isMediaConnection);" in connection_cpp
            and "wssMediaRoute" not in connection_cpp,
            "WSS relay selection must follow the selected auth realm; uploads use regular kwsN")
    require("forceProxyLikeInitForWss" not in connection_cpp
            and "if (useSecret != 0)" in connection_cpp,
            "direct WSS must not inject the MTProxy-only DC marker")
    require("dcId < 1 || dcId > 5" in wss_cpp and "testBackend" in wss_cpp,
            "unsupported/test DCs must stay on their normal transport")
    require("officialRelayIpsForDc" in wss_cpp
            and '{"149.154.167.220", "149.154.167.99"}' in wss_cpp
            and "result.relayHost = selectRelayAddress(result.network, dcId)" in wss_cpp
            and "result.relayHostFallback = result.domain" in wss_cpp
            and "result.viaFallback = preferFallback(result)" in wss_cpp,
            "official WSS must use direct Telegram ingress with an automatic hostname fallback")
    require("if (!moveToNextRelayAddress(routeConfig))" in wss_cpp
            and "!isCdn(routeConfig) && !moveToNextRelayAddress(routeConfig)" in wss_cpp,
            "a failed relay address must hand the DC to its other ingress before the DNS name")
    require("dcId == kNoRelayDcId" in wss_cpp
            and "return CdnRoute(network, dcId, route) || TunnelRoute(network, dcAddress, route)\n"
                "                || DirectRoute(network, dcId, route) || CdnRoute(network, dcId, route, true);" in wss_cpp,
            "DC203 must go fronts -> tunnel -> guarded direct -> fronts ignoring suppression")
    require("if (CdnRoute(result.network, dcId, route) || TunnelRoute(result.network, dcAddress, route)\n"
            "                || DirectRoute(result.network, dcId, route)) {" in wss_cpp
            and "return CdnRoute(result.network, dcId, route, true);" in wss_cpp,
            "a DC with a closed relay must try guarded direct TCP only after the fronts and the tunnel, "
            "and fall back to the fronts, never to unguarded direct TCP")
    require("if (routeSuppressed(network, name)) {" in wss_cpp
            and '"direct-dc"' in wss_cpp
            and "suppressLocked(route, health, now, failure);" in wss_cpp
            and "kDirectProofBytes" in wss_cpp,
            "direct TCP must be paused after one failure per DC and network and proven only by volume")
    require("selectedWssRoute.direct" in socket_cpp
            and "checkDirectStepTimeout(now)" in socket_cpp
            and "WSS_TCP_CONNECT_TIMEOUT_MS" in socket_cpp
            and "finishDirectStep(reason);" in socket_cpp
            and "noteDirectStepSent((uint32_t) sentLength);" in socket_cpp,
            "the direct step must get the WSS watchdogs and report one verdict when it closes")
    require("if (isCurrentTransportWss())" in connection_cpp
            and "useSecret = 0" in connection_cpp,
            "direct WSS must not inherit a TCP dcOption or MTProxy secret")
    require("supportsCdnFileRedirects()" in connections_java
            and "return !isWssTransportActive()" in connections_java,
            "network layer must disable CDN redirects when WSS cannot route CDN DC ids")
    require("req.cdn_supported = ConnectionsManager.supportsCdnFileRedirects()" in file_loader,
            "file downloads must advertise the active transport's CDN capability")

    print("WSS official route guard passed.")


if __name__ == "__main__":
    main()
