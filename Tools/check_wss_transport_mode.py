#!/usr/bin/env python3
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
SHARED_CONFIG = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java"
CONNECTIONS_JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java"
PROXY_LIST = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java"
PROXY_SETTINGS = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ProxySettingsActivity.java"
BOT_WEBVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/web/BotWebViewContainer.java"
MINI_BRIDGE = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/WssMiniAppProxyBridge.java"
WRAPPER_CPP = ROOT / "TMessagesProj/jni/TgNetWrapper.cpp"
MANAGER_CPP = ROOT / "TMessagesProj/jni/tgnet/ConnectionsManager.cpp"
MANAGER_H = ROOT / "TMessagesProj/jni/tgnet/ConnectionsManager.h"
SOCKET_CPP = ROOT / "TMessagesProj/jni/tgnet/ConnectionSocket.cpp"
SOCKET_H = ROOT / "TMessagesProj/jni/tgnet/ConnectionSocket.h"
SOCKET_STATE_H = ROOT / "TMessagesProj/jni/tgnet/ConnectionSocketStateMachine.h"
CONNECTION_CPP = ROOT / "TMessagesProj/jni/tgnet/Connection.cpp"
TRANSPORT_H = ROOT / "TMessagesProj/jni/tgnet/transport/TransportSocket.h"
WSS_H = ROOT / "TMessagesProj/jni/tgnet/wss/WssSocket.h"
WSS_CPP = ROOT / "TMessagesProj/jni/tgnet/wss/WssSocket.cpp"
OLD_WSS_H = ROOT / "TMessagesProj/jni/tgnet/WssTransport.h"
OLD_WSS_CPP = ROOT / "TMessagesProj/jni/tgnet/WssTransport.cpp"
CMAKE = ROOT / "TMessagesProj/jni/CMakeLists.txt"
STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
STRINGS_RU = ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def require(condition: bool, message: str) -> None:
    if not condition:
        print(f"FAIL: {message}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    shared_config = text(SHARED_CONFIG)
    connections = text(CONNECTIONS_JAVA)
    proxy_list = text(PROXY_LIST)
    proxy_settings = text(PROXY_SETTINGS)
    bot_webview = text(BOT_WEBVIEW)
    wrapper = text(WRAPPER_CPP)
    manager_cpp = text(MANAGER_CPP)
    manager_h = text(MANAGER_H)
    socket_cpp = text(SOCKET_CPP)
    socket_h = text(SOCKET_H)
    socket_state_h = text(SOCKET_STATE_H)
    socket_state_cpp = text(ROOT / "TMessagesProj/jni/tgnet/ConnectionSocketStateMachine.cpp")
    connection_cpp = text(CONNECTION_CPP)
    transport_h = text(TRANSPORT_H)
    wss_h = text(WSS_H)
    wss_cpp = text(WSS_CPP)
    cmake = text(CMAKE)

    require("boolean wssTransportEnabled" in shared_config,
            "SharedConfig must persist only the client WSS checkbox")
    require("setWssTransportEnabled(boolean enabled)" in shared_config,
            "SharedConfig must expose the WSS checkbox setter")
    require("PROXY_SCHEMA_V4" in shared_config,
            "proxy schema V4 must keep proxy rows independent from WSS")
    require("Consume obsolete per-row WSS metadata" in shared_config,
            "schema V3 WSS metadata must be migrated, not reused")
    for forbidden in ("currentWssSocksProxy", "saveWssSocksProxy", "clearWssSocksProxy", "isWssTransport()"):
        require(forbidden not in shared_config, f"SharedConfig must not retain {forbidden}")

    require("public static void setWssTransportEnabled()" in connections,
            "Java must expose a boolean-only WSS setter")
    require("native_setWssTransportEnabled(int currentAccount, boolean enabled)" in connections,
            "Java JNI declaration must carry only account and enabled")
    require("setWssTransportSettings" not in connections,
            "Java must not carry gateway or upstream-proxy settings")
    require('native_setWssTransportEnabled", "(IZ)V"' in wrapper,
            "JNI registration must use the boolean-only WSS signature")
    require("void setWssTransportEnabled(bool enabled)" in manager_h,
            "native manager must expose the boolean-only WSS setting")
    require("bool wssEnabled" in manager_h,
            "native manager must store only the WSS toggle")
    require("setWssTransportSettings" not in manager_cpp,
            "native manager must not store WSS gateway/proxy configuration")

    require("R.string.UseWssTransport" in proxy_list and "TextCheckCell" in proxy_list,
            "proxy UI must expose WSS as a checkbox")
    require("disableLegacyProxyForWss" in proxy_list
            and "ConnectionsManager.setProxySettings(false" in proxy_list,
            "enabling WSS must disable the legacy proxy")
    require("SharedConfig.setWssTransportEnabled(false)" not in proxy_list,
            "selecting a proxy must only suspend WSS, not clear the user's WSS preference")
    require("SharedConfig.wssTransportEnabled && !useProxySettings" in proxy_list,
            "the WSS checkbox must show WSS as off while a proxy is in use")
    for forbidden in ("TYPE_WSS", "createWssGateway", "WSS_TRANSPORT_OPTIONS", "wssCustomGatewayRow"):
        require(forbidden not in proxy_settings + proxy_list,
                f"proxy screens must not retain {forbidden}")

    require("class Socket" in transport_h and "virtual bool open" in transport_h,
            "tgnet must expose an abstract transport socket boundary")
    require("class Socket final : public transport::Socket" in wss_h,
            "WSS must be its own transport socket module")
    require("int socketFd = -1" in wss_h and "::socket(" in wss_cpp and "::connect(" in wss_cpp,
            "WSS module must own its native descriptor and TCP connect")
    require("SSL_VERIFY_PEER" in wss_cpp and "SSL_set1_host" in wss_cpp,
            "WSS TLS must verify the peer and hostname")
    require("Sec-WebSocket-Protocol: binary" in wss_cpp and "Sec-WebSocket-Key" in wss_cpp,
            "WSS module must perform a real binary WebSocket upgrade")
    require("kws2.web.telegram.org" not in wss_cpp or '"kws2"' in wss_cpp,
            "official DC2 route must be generated inside the WSS module")
    require('prefix = "kws" + std::to_string(dcId)' in wss_cpp and '"/apiws"' in wss_cpp,
            "WSS module must use the official DC1-DC5 relay catalog")
    require("officialRelayIpsForDc" in wss_cpp
            and "result.relayHostFallback = result.domain" in wss_cpp
            and "result.connectHost = result.viaFallback" in wss_cpp
            and "bool viaFallback = false" in wss_h,
            "WSS module must own direct Telegram ingress and hostname fallback routing")
    for forbidden in ("buildSocks5Greeting", "upstreamSocksEnabled", "customRoute"):
        require(forbidden not in wss_cpp + wss_h,
                f"WSS socket must not contain proxy/gateway feature {forbidden}")

    require('#include "wss/WssSocket.h"' in socket_h + socket_state_h,
            "ConnectionSocket state must depend on the dedicated WSS socket")
    require("std::unique_ptr<tgnet::transport::Socket>" in socket_h + socket_state_h,
            "ConnectionSocket state must hold WSS through the transport abstraction")
    require("tgnet::wss::OfficialRoute" in socket_cpp and "tgnet::wss::CreateSocket" in socket_cpp,
            "ConnectionSocket must select and instantiate the WSS module")
    require("manager.wssEnabled" in socket_cpp and "proxyAddress->empty()" in socket_cpp,
            "WSS selection must be independent and must not stack over a proxy")
    require("currentWssTransport->open" in socket_cpp
            and "currentWssTransport->onEvent" in socket_cpp
            and "currentWssTransport->write" in socket_cpp,
            "ConnectionSocket must delegate WSS I/O to the socket module")
    require("if (!isCurrentTransportWss())" in socket_cpp
            and "eventMask.events |= EPOLLET" in socket_cpp,
            "WSS must stay level-triggered while TCP transports keep edge-triggered epoll")
    require("enum class IoWait" in wss_h
            and "SSL_ERROR_WANT_READ" in wss_cpp
            and "SSL_ERROR_WANT_WRITE" in wss_cpp
            and "ioWait == IoWait::Write" in wss_cpp,
            "WSS must preserve OpenSSL read/write wait direction")
    require("wss_socket tcp_connected" in wss_cpp
            and "wss_socket tls_ready" in wss_cpp
            and "wss_socket timeout" in wss_cpp,
            "WSS diagnostics must identify TCP, TLS, and timeout phases")
    require("getDiagnosticSnapshot" in socket_h
            and 'append("wss_url"' in socket_cpp
            and 'append("wss_relay"' in socket_cpp
            and "collectConnectionDiagnostics" in manager_h + manager_cpp
            and "native_getDatacenterConnectionDiagnostics" in connections
            and 'native_getDatacenterConnectionDiagnostics", "(II)Ljava/lang/String;"' in wrapper,
            "message diagnostics must expose the live native WSS route through a bounded JNI snapshot")
    require('append("connect"' in socket_cpp
            and 'append("target"' in socket_cpp
            and "currentSecret=" not in socket_cpp[socket_cpp.index("std::string ConnectionSocket::getDiagnosticSnapshot"):socket_cpp.index("bool ConnectionSocket::matchesMtProxyEndpointKey")]
            and 'append("endpoint_key"' not in socket_cpp
            and 'append("probe_key"' not in socket_cpp,
            "connection snapshots must identify endpoints without exposing proxy secrets")
    # Измерено 2026-08-09: релей разбирает только ПЕРВЫЙ MTProto-пакет кадра и
    # молча выбрасывает остальные. msgs_ack, склеенный со следующим шагом
    # рукопожатия, оставлял датацентр без ключа навсегда.
    require("flushWssStream" in socket_cpp
            and "outgoingByteStream->get" in socket_cpp
            and "outgoingWssPacketSizes" in socket_cpp
            and "noteWssPacketBoundary" in socket_cpp
            and "noteWssPacketBoundary" in connection_cpp,
            "WSS frames must be cut exactly at MTProto packet boundaries")
    require("queuedOutputBytes" in wss_cpp
            and "readFailed" in wss_cpp
            and "EPOLLRDHUP" in wss_cpp
            and "dispatchWssPayloads(payloads)" in socket_cpp
            and socket_cpp.index("dispatchWssPayloads(payloads)") < socket_cpp.index("wss_transport_failed"),
            "WSS must deliver frames drained before EOF so transport errors reach MTProto")
    # Measured against the official relays: a first binary frame of 63 bytes is
    # never answered, 64 always is, and the failure is completely silent.
    require("kObfuscationHeaderSize = 64" in wss_cpp
            and "openingFrameSent" in wss_cpp
            and "openingFrame.size() < kObfuscationHeaderSize" in wss_cpp
            and "openingFrame" in wss_h,
            "WSS must never emit a first frame shorter than the 64-byte obfuscation header")
    require("std::deque<std::vector<uint8_t>> pendingOutput" in wss_h
            and "pendingOutput.front()" in wss_cpp
            and "pendingOutput.push_back(std::move(frame))" in wss_cpp,
            "WSS TLS retries must keep immutable frame buffers during upload bursts")
    require("The relay owns the socket address" in socket_cpp
            and "ipv6 = false;" in socket_cpp,
            "WSS relay address family must not inherit the ignored DC target family")
    require("lastPushPingTime != 0" in manager_cpp,
            "push ping timeout must not spin after clearing its timestamp")
    for forbidden in ("wssSocksHost", "wssFallbackProxy", "WssRouteConfig"):
        require(forbidden not in socket_cpp + socket_h,
                f"ConnectionSocket must not contain obsolete WSS proxy field {forbidden}")

    require("tgnet/wss/WssSocket.cpp" in cmake and "tgnet/WssTransport.cpp" not in cmake,
            "CMake must compile only the dedicated WSS socket module")
    require(not OLD_WSS_H.exists() and not OLD_WSS_CPP.exists(),
            "the old mixed WssTransport implementation must be removed")
    require(not MINI_BRIDGE.exists() and "WssMiniAppProxyBridge" not in bot_webview,
            "WSS must not create a MiniApp/local proxy bridge")

    for path in (STRINGS, STRINGS_RU):
        source = text(path)
        require('name="UseWssTransport"' in source, f"{path.name} must define UseWssTransport")
        require('name="WssTransportInfo"' in source, f"{path.name} must define WssTransportInfo")

    print("WSS transport architecture guard passed.")


if __name__ == "__main__":
    main()
