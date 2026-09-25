#!/usr/bin/env python3
"""WEB proxy must reach tgnet as a plain MTProxy on the local browser bridge.

The WebView bridge carries an ordinary obfuscated2 stream keyed by the WEB
proxy's own plain secret. None of ZaStoGram's MTProxy stealth layers (FakeTLS
profiles, ClientHello fragmentation, connection pattern, record sizing, timing,
startup cover, soft mux) may be put on top of it, and the stream must never be
keyed by the DC secret or an empty secret.

The bridge is also exempt from MTProxy dial pacing. Java marks it with
MtProxyOptions.webBridge(); native code then skips the per-endpoint TCP
connect gate, the endpoint cooldown, the handshake admission, the DNS
coalescing and the Connection reconnect backoff for it. Those exist to spare a
remote relay under DPI; on loopback they only slow connection setup. Real
MTProxy connections keep every one of those gates.

Every tgnet connection through a WEB proxy is one stream on a single shared
carrier. The data path is WebProxyEngine: one thread, a selector over the
loopback sockets, and one flow-control layer (mirroring the desktop client's
policy in WebProxyFlow):
- uplink is pulled: a socket is read only when WebProxyFlow.UplinkScheduler
  grants its stream a frame (interactive first, bulk round-robin, uploads
  capped by an adaptive window of relay credit), straight into one batch
  message for the page; tgnet announces each connection's class;
- downlink is written without blocking, and download streams share an
  adaptive credit budget; both windows follow the carrier's
  bandwidth-delay product (WebProxyFlow.AdaptiveWindow fed by
  WebProxyFlow.DeliverySampler);
- the page boundary is a WebMessagePort when available (the page's frames
  are not dispatched on the UI thread), else the origin-scoped listener; the
  UI thread never takes the engine lock;
- liveness: a WEB bridge connection silent for its receive timeout asks the
  carrier (WebProxyFlow.decideReceiveWait) instead of closing itself, the
  5.5 s MTProxy first-reply probe does not apply to it, and a stalled carrier
  is recovered once by the engine's own watchdog;
- churn: a first -404 on a WEB stream reconnects instead of dropping the temp
  key; uploads keep tgnet's four connections (the carrier windows bound the
  queue, not the number of connections);
- media routing: through an MTProxy-style route a media connection's DC sign
  follows the same rule as the key it uses (Datacenter::hasMediaAddress), so
  a media key never reaches the regular cluster.
Direct, SOCKS and real MTProxy connections never take the WEB paths.
The pure-Java policy and engine are also compiled and run on the host JVM
(Tools/web_proxy_flow_tests).
"""
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
CONNECTIONS = JAVA / "tgnet/ConnectionsManager.java"
PROXY_LIST = JAVA / "ui/ProxyListActivity.java"
LINK_HELPER = JAVA / "messenger/ProxyLinkHelper.java"
SHARED_CONFIG = JAVA / "messenger/SharedConfig.java"
CONNECTION_CPP = ROOT / "TMessagesProj/jni/tgnet/Connection.cpp"
CONNECTION_SOCKET_CPP = ROOT / "TMessagesProj/jni/tgnet/ConnectionSocket.cpp"
MT_PROXY_OPTIONS_JAVA = JAVA / "tgnet/MtProxyOptions.java"
MT_PROXY_OPTIONS_H = ROOT / "TMessagesProj/jni/mtproxy/MtProxyOptions.h"
TGNET_WRAPPER_CPP = ROOT / "TMessagesProj/jni/TgNetWrapper.cpp"
DEFINES_H = ROOT / "TMessagesProj/jni/tgnet/Defines.h"
CONNECTIONS_MANAGER_CPP = ROOT / "TMessagesProj/jni/tgnet/ConnectionsManager.cpp"
WEB_TRANSPORT_JAVA = JAVA / "proxy/WebProxyTransport.java"
WEB_FLOW_JAVA = JAVA / "proxy/WebProxyFlow.java"
WEB_ENGINE_JAVA = JAVA / "proxy/WebProxyEngine.java"
WEB_FLOW_TEST = ROOT / "Tools/web_proxy_flow_tests/WebProxyFlowTest.java"
WEB_ENGINE_TEST = ROOT / "Tools/web_proxy_flow_tests/WebProxyEngineTest.java"


def method_body(text: str, signature: str) -> str:
    start = text.find(signature)
    if start < 0:
        return ""
    brace = text.find("{", start)
    depth = 0
    for index in range(brace, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[brace:index + 1]
    return ""


def check_web_bridge_bypasses_pacing(require) -> None:
    """The WEB loopback bridge must bypass MTProxy pacing; MTProxy keeps it."""
    options_java = MT_PROXY_OPTIONS_JAVA.read_text(encoding="utf-8")
    web_bridge_java = method_body(options_java, "public static MtProxyOptions webBridge()")
    disabled_java = method_body(options_java, "public static MtProxyOptions disabled()")
    resolve_java = method_body(options_java, "public static MtProxyOptions resolve(String address, int port, String secret)")
    require("public final boolean webBridge;" in options_java,
            "Java MtProxyOptions must carry an explicit webBridge marker")
    require("MT_PROXY_STARTUP_COVER_OFF,\n                true);" in web_bridge_java,
            "MtProxyOptions.webBridge() must be the disabled option set with webBridge=true")
    require("true" not in disabled_java and "true" not in resolve_java,
            "MtProxyOptions.disabled() and resolve() must never mark a connection as the WEB bridge")

    options_h = MT_PROXY_OPTIONS_H.read_text(encoding="utf-8")
    require("bool webBridge = false;" in options_h
            and "&& webBridge == other.webBridge;" in options_h
            and "normalized.webBridge = options.webBridge;" in options_h,
            "native MtProxyOptions must default webBridge to false, compare it and keep it through normalization")

    wrapper = TGNET_WRAPPER_CPP.read_text(encoding="utf-8")
    require('env->GetFieldID(jclass_MtProxyOptions, "webBridge", "Z")' in wrapper
            and "nativeOptions.webBridge = env->GetBooleanField(options, jclass_MtProxyOptions_webBridge) == JNI_TRUE;" in wrapper,
            "the JNI bridge must read MtProxyOptions.webBridge into native options")

    socket_cpp = CONNECTION_SOCKET_CPP.read_text(encoding="utf-8")
    open_connection = method_body(socket_cpp, "void ConnectionSocket::openConnection(std::string address")
    require("stateMachine.endpointGate.webProxyBridge = proxyOptions.webBridge && !proxyAddress->empty() && !proxySecret->empty();" in open_connection,
            "openConnection must take the WEB bridge marker from the effective proxy options")
    gates = {
        "bool ConnectionSocket::scheduleMtProxyEndpointTcpConnectGateIfNeeded(bool ipv6)": "MtProxyEndpointPolicy::beginTcpConnect(",
        "bool ConnectionSocket::scheduleMtProxyEndpointCircuitBreakerIfNeeded(bool ipv6)": "MtProxyEndpointPolicy::readCooldown(",
        "bool ConnectionSocket::scheduleProxyHandshakeAdmissionIfNeeded(bool ipv6, int32_t timerMode)": "scheduleProxyHandshakeAdmissionTimer(",
        "bool ConnectionSocket::scheduleMtProxyDnsCoalesceIfNeeded(bool ipv6)": "MtProxyEndpointPolicy::beginDnsCoalesce(",
    }
    for signature, mtproxy_gate in gates.items():
        body = method_body(socket_cpp, signature)
        bypass = body.find("isCurrentWebProxyBridge()")
        gate = body.find(mtproxy_gate)
        require(bypass >= 0 and gate >= 0 and bypass < gate,
                f"{signature} must return early for the WEB bridge before its MTProxy gate {mtproxy_gate}")
    tcp_gate = method_body(socket_cpp, "bool ConnectionSocket::scheduleMtProxyEndpointTcpConnectGateIfNeeded(bool ipv6)")
    require("if (isCurrentWebProxyBridge()) {" in tcp_gate and "return false;" in tcp_gate.split("if (isCurrentWebProxyBridge()) {", 1)[-1].split("}", 1)[0],
            "the per-endpoint TCP connect queue must never hold a WEB bridge connect")
    stage = method_body(socket_cpp, "void ConnectionSocket::publishProxyConnectionStage(const char *diagnostic)")
    require("!isCurrentWebProxyBridge() && MtProxyEndpointPolicy::failureNeedsCooldown(diagnostic)" in stage,
            "a WEB bridge failure must not raise an endpoint-cooldown reconnect hold")
    internal = method_body(socket_cpp, "void ConnectionSocket::openConnectionInternal(bool ipv6)")
    require("isCurrentMtProxyConnection() && scheduleMtProxyEndpointCircuitBreakerIfNeeded(ipv6)" in internal
            and "isCurrentMtProxyConnection() && scheduleMtProxyEndpointTcpConnectGateIfNeeded(ipv6)" in internal,
            "real MTProxy connections must still pass the endpoint cooldown and TCP connect gate")

    connection_cpp = CONNECTION_CPP.read_text(encoding="utf-8")
    pacing = method_body(connection_cpp, "bool Connection::isMtProxyReconnectPacingActive() const")
    require("return !overrideMtProxyOptions.webBridge;" in pacing
            and "proxyMtProxyOptions.webBridge;" in pacing
            and "if (!isMtProxyRouteActive()) {" in pacing,
            "reconnect pacing must be active for MTProxy routes and off for the WEB bridge")
    connect = method_body(connection_cpp, "void Connection::connect()")
    require("if (mtProxyReconnectPacing && connectionType != ConnectionTypeProxy && mtProxyReconnectHoldUntil > now) {" in connect,
            "Connection::connect must honour the MTProxy reconnect hold only while reconnect pacing is active")
    disconnected = method_body(connection_cpp, "void Connection::onDisconnectedInternal(int32_t reason, int32_t error)")
    require("if (mtProxyReconnectPacing && connectionState == TcpConnectionStageIdle && connectionType != ConnectionTypeProxy && !isProxyCloseDiagnosticSuppressed()" in disconnected,
            "the MTProxy reconnect backoff must be skipped for the WEB bridge")
    require("mtProxyRouteActive && connectionState == TcpConnectionStageIdle" not in disconnected,
            "no reconnect backoff branch may key on the bare MTProxy route (it would include the WEB bridge)")


def check_carrier_aware_data_path(require) -> None:
    """Pulled uplink, adaptive windows, non-blocking downlink, carrier liveness."""
    flow = WEB_FLOW_JAVA.read_text(encoding="utf-8")
    engine = WEB_ENGINE_JAVA.read_text(encoding="utf-8")
    transport = WEB_TRANSPORT_JAVA.read_text(encoding="utf-8")

    for name, text in (("WebProxyFlow", flow), ("WebProxyEngine", engine)):
        require("import android." not in text and "import androidx." not in text,
                f"{name} must stay free of Android classes so it runs on the host JVM")

    # Uplink: pulled by the scheduler, straight into the batch.
    pump = method_body(engine, "private void pumpDataLocked(long now)")
    require("scheduler.next()" in pump and "stream.channel.read(batch)" in pump
            and "scheduler.sent(" in pump and "stream.unacked += count;" in pump,
            "DATA frames must be read from a socket only on a scheduler grant, into the batch, and counted as unacked")
    require(engine.count("(byte) FRAME_DATA") == 1 and "(byte) FRAME_DATA" in pump,
            "pumpDataLocked must be the only writer of DATA frames")
    require("channel.read(" not in engine.replace(pump, ""),
            "no code path but the scheduler grant may read a tgnet socket")
    interest = method_body(engine, "private void updateInterestLocked(Stream stream)")
    require("stream.opened && !stream.readable && stream.sendWindow > 0" in interest,
            "a socket is watched for reading only while its stream could send")
    sample = method_body(engine, "private void sampleLocked(long now)")
    require("uplinkSampler.tick(uplinkWindow" in sample and "downlinkSampler.tick(downlinkWindow" in sample
            and "scheduler.setUploadLimit(uplinkWindow.window());" in sample,
            "the upload cap and the download budget must follow the adaptive windows")
    window = engine[engine.find("} else if (type == FRAME_WINDOW"):]
    window = window[:window.find("} else if (type == FRAME_CLOSE")]
    require("creditLocked(stream, amount, now);" in window and "markReadyLocked(stream);" in window,
            "relay credit must acknowledge unacked bytes and make the stream eligible again")
    require("public static final class AdaptiveWindow" in flow and "public static final class DeliverySampler" in flow,
            "the adaptive window and its sampler live in WebProxyFlow")

    # Downlink: never blocks, credit through the download share.
    data = engine[engine.find("if (type == FRAME_DATA) {"):]
    data = data[:data.find("} else if (type == FRAME_WINDOW")]
    require("flushDownLocked(stream, now);" in data and "FRAME_WINDOW" not in data,
            "downlink DATA must be queued for a non-blocking write, never credited back blindly")
    flush = method_body(engine, "private void flushDownLocked(Stream stream, long now)")
    require("stream.channel.write(head)" in flush and "releaseDownlinkCreditLocked(stream);" in flush,
            "downlink bytes go to tgnet without blocking, then release credit")
    require("getOutputStream()" not in engine and "getInputStream()" not in engine,
            "the engine never uses blocking socket streams")
    release = method_body(engine, "private void releaseDownlinkCreditLocked(Stream stream)")
    require("WebProxyFlow.downlinkCreditTarget(stream.streamClass, downloadStreams, downlinkWindow.window())" in release
            and "WebProxyFlow.downlinkCreditRelease(" in release,
            "download credit must be computed by WebProxyFlow from the adaptive budget")

    # Stream classes announced by tgnet.
    require("public static void registerLocalStream(int bridgePort, int localPort, int streamClass)" in transport
            and "pendingClasses.remove(localPort)" in engine,
            "the bridge must classify accepted sockets by the class tgnet announced for their local port")

    # Carrier: HELLO alone, one recovery for all streams.
    inbound = method_body(engine, "private void handleInboundLocked(Inbound item, long now)")
    require("host.postToPage(pageToken, frame(FRAME_HELLO, 0, new byte[]{1}, 0, 1));" in inbound,
            "the page's first binary message must be one HELLO frame")
    pump_all = method_body(engine, "private void pumpLocked(long now)")
    require("if (stopped || pageToken == 0) {" in pump_all and "if (connected) {" in pump_all,
            "no stream frame may go to a page before the relay welcomed it")
    health = method_body(engine, "private void checkHealthLocked(long now)")
    require("WebProxyFlow.carrierStalled(" in health and "failCarrierLocked(failure);" in health
            and "WebProxyFlow.WELCOME_TIMEOUT_MS" in health,
            "a stalled carrier or a page that never welcomes must be recovered once by the engine watchdog")
    fail_carrier = method_body(engine, "private void failCarrierLocked(String reason)")
    require("if (stopped || restartPending) {" in fail_carrier and "scheduler.clear();" in fail_carrier
            and "web_proxy_carrier event=lost reason=" in fail_carrier and "host.carrierFailed(reason);" in fail_carrier,
            "carrier recovery must run once, reset the flow state and log its reason")
    require("item.token != pageToken || pageToken == 0" in inbound,
            "frames of a page that is gone must be ignored")

    # Page boundary.
    on_message = method_body(transport, "private void onWebMessage(")
    require("engine.pageBytes(pageToken, message.getArrayBuffer());" in on_message and "processFrames" not in transport,
            "the UI thread only hands the page's bytes to the engine")
    require("synchronized (lock)" not in transport and "engine.lock" not in transport,
            "the UI thread never takes the engine lock")
    require("Object.defineProperty(globalThis,'TelegramWebProxy'" in transport
            and "Uri.parse(origin)" in method_body(transport, "private void connectPort(WebView view)")
            and "portsFailed = true;" in transport,
            "the port shim stands in for TelegramWebProxy, the port goes only to the exact bridge origin, and a failing port falls back to the listener")
    require(transport.count("androidNonce.equals(object.optString(\"nonce\"))") == 2,
            "both page boundaries must check the nonce of the init message")
    for secret_word in ("bridgeUrl", "secret", "androidNonce", "capability"):
        for line in transport.splitlines() + engine.splitlines():
            if "FileLog.d(" in line or "host.log(" in line:
                require(secret_word not in line, f"WEB carrier diagnostics must not log {secret_word}")

    # JNI glue.
    connections = CONNECTIONS.read_text(encoding="utf-8")
    wrapper = TGNET_WRAPPER_CPP.read_text(encoding="utf-8")
    defines = DEFINES_H.read_text(encoding="utf-8")
    require("public static void onWebProxyStreamOpened(int bridgePort, int localPort, int streamClass)" in connections
            and "public static long webProxyReceiveWait(int bridgePort, int localPort, long waitStartedAt)" in connections,
            "ConnectionsManager must expose the WEB bridge JNI callbacks")
    require('GetStaticMethodID(jclass_ConnectionsManager, "onWebProxyStreamOpened", "(III)V")' in wrapper
            and 'GetStaticMethodID(jclass_ConnectionsManager, "webProxyReceiveWait", "(IIJ)J")' in wrapper,
            "TgNetWrapper must resolve the WEB bridge callbacks with matching signatures")
    require("virtual void onWebProxyStreamOpened(int32_t bridgePort, int32_t localPort, int32_t streamClass, int32_t instanceNum) = 0;" in defines
            and "virtual int64_t webProxyReceiveWait(int32_t bridgePort, int32_t localPort, int64_t waitStartedAt, int32_t instanceNum) = 0;" in defines,
            "the native delegate must declare the WEB bridge callbacks")
    receive_wait_impl = method_body(wrapper, "int64_t webProxyReceiveWait(int32_t bridgePort")
    require("ExceptionCheck()" in receive_wait_impl and "return 0;" in receive_wait_impl,
            "a Java exception in the receive-wait callback must fail closed (plain tgnet timeout)")

    # Class and reason numbers are shared across JNI.
    for name, value in (("INTERACTIVE", 0), ("DOWNLOAD", 1), ("UPLOAD", 2)):
        require(f"#define WEB_PROXY_STREAM_CLASS_{name} {value}" in defines
                and f"public static final int CLASS_{name} = {value};" in flow,
                f"stream class {name} must be {value} on both sides of JNI")
    java_reasons = re.search(r"REASON_NAMES = \{(.*?)\};", flow, re.S)
    socket_cpp = CONNECTION_SOCKET_CPP.read_text(encoding="utf-8")
    native_reasons = re.search(r"kWebProxyReceiveReasonNames\[\] = \{(.*?)\};", socket_cpp, re.S)
    java_list = re.findall(r'"([a-z_]+)"', java_reasons.group(1)) if java_reasons else []
    native_list = re.findall(r'"([a-z_]+)"', native_reasons.group(1)) if native_reasons else []
    require(java_list and java_list == native_list,
            "receive-wait reason names must be identical in WebProxyFlow and ConnectionSocket.cpp")
    for index, reason in enumerate(java_list[1:], start=1):
        constant = "REASON_" + ("QUEUED" if reason == "request_queued" else reason.upper())
        require(f"public static final int {constant} = {index};" in flow,
                f"WebProxyFlow.{constant} must be {index} to match its name table")

    # Native: tgnet announces classes and asks the carrier before closing.
    connection_cpp = CONNECTION_CPP.read_text(encoding="utf-8")
    connect = method_body(connection_cpp, "void Connection::connect()")
    require("setWebProxyStreamClass(" in connect and "WEB_PROXY_STREAM_CLASS_DOWNLOAD" in connect
            and "WEB_PROXY_STREAM_CLASS_UPLOAD" in connect,
            "Connection::connect must tag its socket with the WEB stream class")
    announce = method_body(socket_cpp, "void ConnectionSocket::announceWebProxyStream()")
    require("!isCurrentWebProxyBridge() || hasMtProxyOverride()" in announce
            and "getsockname(" in announce and "onWebProxyStreamOpened(" in announce,
            "only real WEB bridge connections (not proxy checks) announce their class")
    internal = method_body(socket_cpp, "void ConnectionSocket::openConnectionInternal(bool ipv6)")
    require(internal.find("stateMachine.connectNativeSocket(") < internal.find("announceWebProxyStream();"),
            "the class is announced after connect() bound the local port")
    defer = method_body(socket_cpp, "bool ConnectionSocket::deferWebProxyReceiveTimeout(int64_t now)")
    require("if (!isCurrentWebProxyBridge() || gate.webProxyLocalPort == 0 || !onConnectedSent) {" in defer
            and "webProxyReceiveWait(" in defer and "WEB_PROXY_MAX_RECHECK_MS" in defer,
            "the receive-wait question is asked only for connected WEB bridge sockets and bounded per recheck")
    check_timeout = method_body(socket_cpp, "bool ConnectionSocket::checkTimeout(int64_t now)")
    direct = check_timeout[:check_timeout.find("if (isCurrentTransportWss()")]
    require("deferWebProxyReceiveTimeout" not in direct,
            "direct connections keep the plain tgnet timeout")
    require("&& !isCurrentWebProxyBridge()\n        && proxyCheckDiagnostic == \"mtproxy_packet_sent_no_response\"" in check_timeout,
            "the 5.5 s MTProxy first-reply probe must not close WEB bridge streams")
    generic = check_timeout[check_timeout.rfind("if (timeout != 0 && (now - lastEventTime) > (int64_t) timeout * 1000) {"):]
    defer_at = generic.find("if (isCurrentWebProxyBridge() && deferWebProxyReceiveTimeout(now)) {")
    close_at = generic.find("closeSocket(2, 0);")
    require(defer_at >= 0 and close_at >= 0 and defer_at < close_at,
            "a WEB bridge receive timeout must consult the carrier before closing the connection")
    require(check_timeout.count("deferWebProxyReceiveTimeout") == 1,
            "the carrier is consulted from exactly one timeout path")

    # -404 on a WEB stream: reconnect first, believe the second.
    manager_cpp = CONNECTIONS_MANAGER_CPP.read_text(encoding="utf-8")
    received = method_body(manager_cpp, "void ConnectionsManager::onConnectionDataReceived(Connection *connection, NativeByteBuffer *data, uint32_t length)")
    strike = received.find("++connection->webProxyKeyNotFoundStrikes < WEB_PROXY_KEY_NOT_FOUND_STRIKES")
    clear = received.find("datacenter->clearAuthKey(connection->isMediaConnection ? HandshakeTypeMediaTemp : HandshakeTypeTemp);")
    require(strike >= 0 and clear >= 0 and strike < clear
            and "&& connection->isCurrentWebProxyBridge()" in received[:strike],
            "a first -404 on a WEB stream must reconnect before the temp key is dropped")
    require("connection->webProxyKeyNotFoundStrikes = 0;\n        data->position(mark + 24);" in received,
            "a decrypted reply must reset the WEB -404 strikes")
    require("#define WEB_PROXY_KEY_NOT_FOUND_STRIKES 2" in defines,
            "a second -404 before any decrypted reply is believed")

    # Connections: the carrier windows bound the queue, so a WEB proxy keeps
    # tgnet's usual four upload connections (upstream behaviour).
    upload = method_body(connections, "public static int getMtProxySoftMuxUploadConnectionType(int requestIndex)")
    require("WebProxyTransport" not in upload and "requestIndex % 4" in upload,
            "uploads through a WEB proxy must not be cut below tgnet's four connections")
    download = method_body(connections, "public static int getMtProxySoftMuxDownloadConnectionType(int requestIndex)")
    require("ConnectionTypeDownload2" in download,
            "downloads keep at most two connections")


def check_media_routing(require) -> None:
    """Through an MTProxy-style route the DC sign follows the key rule."""
    connection_cpp = CONNECTION_CPP.read_text(encoding="utf-8")
    connect = method_body(connection_cpp, "void Connection::connect()")
    media = connect[connect.find("if (isMediaConnectionType(connectionType)) {"):connect.find("} else if (connectionType == ConnectionTypeTemp) {")]
    rule = media.find("isMediaConnection = currentDatacenter->hasMediaAddress();")
    require(rule >= 0 and "!ConnectionsManager::getInstance(currentDatacenter->instanceNum).proxySecret.empty()" in media[:rule],
            "a proxied media connection must take its media-ness from hasMediaAddress(), the rule of its key")
    datacenter = (ROOT / "TMessagesProj/jni/tgnet/Datacenter.cpp").read_text(encoding="utf-8")
    require("bool media = Connection::isMediaConnectionType(connectionType) && hasMediaAddress();" in datacenter,
            "the key rule this guard relies on must not change unnoticed")


def run_flow_tests(require) -> None:
    """Compiles WebProxyFlow and WebProxyEngine with their tests for Java 8 and runs them."""
    javac = shutil.which("javac")
    java = shutil.which("java")
    if javac is None or java is None:
        print("WEB proxy flow tests SKIPPED: no javac/java on PATH.")
        return
    with tempfile.TemporaryDirectory() as out:
        compile_result = subprocess.run(
            [javac, "--release", "8", "-Xlint:all", "-Werror", "-d", out,
             str(WEB_FLOW_JAVA), str(WEB_ENGINE_JAVA), str(WEB_FLOW_TEST), str(WEB_ENGINE_TEST)],
            capture_output=True, text=True, check=False)
        require(compile_result.returncode == 0,
                "WebProxyFlow, WebProxyEngine and their tests must compile for Java 8 without warnings:\n" + compile_result.stdout + compile_result.stderr)
        if compile_result.returncode != 0:
            return
        for test in ("org.telegram.proxy.WebProxyFlowTest", "org.telegram.proxy.WebProxyEngineTest"):
            run_result = subprocess.run(
                [java, "-ea", "-cp", out, test],
                capture_output=True, text=True, check=False, timeout=120)
            require(run_result.returncode == 0,
                    f"{test} failed:\n" + run_result.stdout + run_result.stderr)
            if run_result.returncode == 0:
                print(run_result.stdout.strip())


def main() -> int:
    failures: list[str] = []

    def require(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    connections = CONNECTIONS.read_text(encoding="utf-8")
    init = method_body(connections, "public void init(int version")
    set_proxy = method_body(connections, "public static void setProxySettings(boolean enabled, ProxySettings settings, ProxyConnectionEvent.Origin origin)")
    check_web = method_body(connections, "private void checkWebProxyInternal(ProxySettings settings, int port, RequestTimeDelegate requestTimeDelegate)")
    soft_mux = method_body(connections, "private static boolean isMtProxySoftMuxEnabled()")

    require(
        'native_setProxySettings(currentAccount, "127.0.0.1", localPort != 0 ? localPort : 9, "", "", proxySecret, MtProxyOptions.webBridge(),' in init,
        "startup restore must dial the WEB bridge with the proxy secret and the webBridge() options",
    )
    require(
        "WebProxyTransport.start(address, secret)" in set_proxy
        and "webProxy = true;" in set_proxy
        and "!hasSelectedProxy ? MtProxyOptions.disabled() : webProxy ? MtProxyOptions.webBridge() : MtProxyOptions.resolve(address, port, secret)" in set_proxy,
        "selecting a WEB proxy must start the bridge and pass MtProxyOptions.webBridge(); other proxies keep resolve()",
    )
    require(
        'native_checkProxy(currentAccount, "127.0.0.1", port, "", "", settings.getSecret(), MtProxyOptions.webBridge(), requestTimeDelegate)' in check_web,
        "WEB proxy checks must use the proxy secret and the webBridge() options",
    )
    require(
        "ProxySettings.Type.MTPROTO" in soft_mux,
        "soft mux must stay an MTProto-proxy policy and skip WEB proxies",
    )
    require(
        "tlsProfileRow = rowCount++" in PROXY_LIST.read_text(encoding="utf-8")
        and "SharedConfig.currentProxy.settings.getType() == ProxySettings.Type.MTPROTO" in PROXY_LIST.read_text(encoding="utf-8"),
        "MTProxy stealth settings rows must be hidden for WEB proxies",
    )

    link_helper = LINK_HELPER.read_text(encoding="utf-8")
    require('"tg://webproxy?"' in link_helper and '"t.me/webproxy?"' in link_helper,
            "tg://webproxy and t.me/webproxy links must be recognised")
    # 12.10.4: a WEB proxy address may carry a path, and its link then marks
    # the secret; ProxySettings.webProxy validates host, path and both secret
    # forms the way upstream does, so the link helper goes through it.
    require("ProxySettings.webProxy(address, secret)" in link_helper,
            "WEB links must carry a valid MTProxy secret (plain, or marked for a path)")

    shared_config = SHARED_CONFIG.read_text(encoding="utf-8")
    require("PROXY_SCHEMA_V5" in shared_config and "version >= PROXY_SCHEMA_V5" in shared_config,
            "the proxy type must be stored in its own schema version, not in ZaStoGram's WSS-era V3")

    connection_cpp = CONNECTION_CPP.read_text(encoding="utf-8")
    require(
        "proxyAddress.empty() && !ConnectionsManager::getInstance(currentDatacenter->instanceNum).proxySecret.empty()) {\n            useSecret = 1;" in connection_cpp,
        "a proxied connection must be keyed by the proxy secret, not the DC secret",
    )
    socket_cpp = CONNECTION_SOCKET_CPP.read_text(encoding="utf-8")
    require(
        "bool shouldUseWss = overrideProxyAddress.empty()\n            && manager.wssEnabled\n            && proxyAddress->empty();" in socket_cpp,
        "WSS substitution must never apply while a proxy (including the WEB bridge) is selected",
    )

    check_web_bridge_bypasses_pacing(require)
    check_carrier_aware_data_path(require)
    check_media_routing(require)
    run_flow_tests(require)

    if failures:
        print("WEB proxy isolation guard failed:")
        for failure in failures:
            print(f" - {failure}")
        return 1
    print("WEB proxy isolation guard passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
