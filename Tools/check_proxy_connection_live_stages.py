#!/usr/bin/env python3
from pathlib import Path
import re
import sys

from mtproxy_phase_contract import java_visible_live_phases, native_phase_names


ROOT = Path(__file__).resolve().parents[1]

FILES = {
    "diagnostics": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyCheckDiagnostics.java",
    "policy": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyPhasePolicy.java",
    "store": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyRuntimeStateStore.java",
    "reducer": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyEventReducer.java",
    "visible_store": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyVisibleStateStore.java",
    "status": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyStatusMirror.java",
    "endpoint_key": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyEndpointKey.java",
    "connections_java": ROOT / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java",
    "notification_center": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java",
    "proxy_list": ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java",
    "launch": ROOT / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java",
    "dialogs": ROOT / "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java",
    "chat_avatar": ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java",
    "profile": ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java",
    "rotation": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyRotationController.java",
    "values": ROOT / "TMessagesProj/src/main/res/values/strings.xml",
    "values_ru": ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml",
    "defines": ROOT / "TMessagesProj/jni/tgnet/Defines.h",
    "wrapper": ROOT / "TMessagesProj/jni/TgNetWrapper.cpp",
    "socket": ROOT / "TMessagesProj/jni/tgnet/ConnectionSocket.cpp",
    "socket_h": ROOT / "TMessagesProj/jni/tgnet/ConnectionSocket.h",
    "collector": ROOT / "Tools/collect_mtproxy_logs.ps1",
    "scheduler": ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyCheckScheduler.java",
}

LIVE_PHASES = sorted(java_visible_live_phases() & native_phase_names())


def text(name: str) -> str:
    return FILES[name].read_text(encoding="utf-8", errors="replace")


def require(condition: bool, message: str) -> None:
    if not condition:
        print(f"FAIL: {message}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    diagnostics = text("diagnostics")
    combined = "\n".join(text(name) for name in FILES)

    for phase in LIVE_PHASES:
        require(phase in diagnostics, f"ProxyCheckDiagnostics must define live phase '{phase}'")
        require(phase in text("socket") or phase in text("connections_java"), f"live phase '{phase}' must be emitted or consumed")
    for phase in sorted(set(re.findall(r'publishProxyConnectionStage\("([^"]+)"\)', text("socket")))):
        require(phase in diagnostics, f"native published phase '{phase}' must be present in ProxyCheckDiagnostics for GUI rendering")

    require(
        "isLivePhase" in diagnostics
        and "hasFreshLivePhase" in diagnostics
        and "ProxyStatusHostResolve" in diagnostics
        and "ProxyStatusClientHelloSent" in diagnostics
        and "ProxyStatusServerHelloOk" in diagnostics,
        "ProxyCheckDiagnostics must map live native stages to user-facing status text",
    )
    header_idx = diagnostics.find("public static String headerStatusText")
    header_checking_idx = diagnostics.find("if (proxyInfo.checking)", header_idx)
    header_live_idx = diagnostics.find("if (hasFreshLivePhase(proxyInfo))", header_idx)
    require(
        header_idx >= 0
        and header_live_idx >= 0
        and header_checking_idx >= 0
        and header_live_idx < header_checking_idx,
        "proxy window header must show fresh live stages before generic checking text",
    )
    status_idx = diagnostics.find("public static String statusText")
    status_live_idx = diagnostics.find("if (hasFreshLivePhase(proxyInfo))", status_idx)
    status_failure_idx = diagnostics.find("if (hasFreshFailure(proxyInfo))", status_idx)
    status_connected_idx = diagnostics.find("currentConnectionIsUsableForStatus(proxyInfo, currentConnectionState)", status_idx)
    status_connecting_idx = diagnostics.find("currentConnectionState == ConnectionsManager.ConnectionStateConnectingToProxy", status_idx)
    inactive_checking_idx = diagnostics.find("if (proxyInfo.checking)", status_connecting_idx)
    inactive_failure_idx = diagnostics.find("if (hasFreshFailure(proxyInfo))", inactive_checking_idx)
    inactive_live_idx = diagnostics.find("hasFreshLivePhase(proxyInfo)", inactive_checking_idx)
    inactive_available_idx = diagnostics.find("if (proxyInfo.available && ProxyCheckScheduler.isFresh(proxyInfo))", inactive_checking_idx)
    header_failure_idx = diagnostics.find("if (hasFreshFailure(proxyInfo))", header_idx)
    header_connected_idx = diagnostics.find("currentConnectionIsUsableForStatus(proxyInfo, currentConnectionState)", header_idx)
    header_connecting_idx = diagnostics.find("currentConnectionState == ConnectionsManager.ConnectionStateConnectingToProxy", header_idx)
    require(
        status_idx >= 0
        and status_live_idx >= 0
        and status_failure_idx >= 0
        and status_failure_idx < status_live_idx
        and header_failure_idx < header_live_idx,
        "current proxy terminal failures must override live stages in row and header text",
    )
    require(
        status_failure_idx >= 0
        and status_connected_idx >= 0
        and status_failure_idx < status_connected_idx
        and status_live_idx >= 0
        and status_connected_idx < status_live_idx
        and header_failure_idx >= 0
        and header_connected_idx >= 0
        and header_failure_idx < header_connected_idx,
        "fresh terminal failures must override Connected, and MTProxy connected/updating status must be data-path gated before unresolved live telemetry",
    )
    require(
        status_failure_idx >= 0
        and status_connecting_idx >= 0
        and status_failure_idx < status_connecting_idx
        and header_failure_idx >= 0
        and header_connecting_idx >= 0
        and header_failure_idx < header_connecting_idx,
        "fresh terminal failures must render before generic ConnectionStateConnectingToProxy text, otherwise the UI shows red 'waiting TCP'",
    )
    require(
        inactive_checking_idx >= 0
        and inactive_failure_idx >= 0
        and inactive_live_idx >= 0
        and inactive_available_idx >= 0
        and inactive_failure_idx < inactive_available_idx
        and inactive_live_idx < inactive_available_idx,
        "fresh concrete proxy phases must override stale Available text for non-current proxy rows",
    )
    color_idx = diagnostics.find("public static int statusColorKey")
    color_failure_idx = diagnostics.find("if (hasFreshFailure(proxyInfo))", color_idx)
    color_live_idx = diagnostics.find("hasFreshLivePhase(proxyInfo)", color_idx)
    color_connected_idx = diagnostics.find("currentConnectionIsUsableForStatus(proxyInfo, currentConnectionState)", color_idx)
    color_inactive_start_idx = diagnostics.find("if (proxyInfo == null)", color_idx)
    color_inactive_failure_idx = diagnostics.find("if (hasFreshFailure(proxyInfo))", color_inactive_start_idx)
    color_inactive_live_idx = diagnostics.find("hasFreshLivePhase(proxyInfo)", color_inactive_start_idx)
    color_inactive_available_idx = diagnostics.find("if (proxyInfo.available && ProxyCheckScheduler.isFresh(proxyInfo))", color_inactive_start_idx)
    require(
        color_idx >= 0
        and color_failure_idx >= 0
        and color_live_idx >= 0
        and color_connected_idx >= 0
        and color_failure_idx < color_connected_idx,
        "current proxy terminal failures must color the row as failure before gated connected blue",
    )
    require(
        color_live_idx >= 0
        and color_connected_idx >= 0
        and color_connected_idx < color_live_idx
        and "isProxyUsableSuccessPhase(proxyInfo.lastCheckDiagnostic)" in diagnostics[color_live_idx:color_inactive_start_idx],
        "data-path-gated connected/updating current proxy must choose row color before unresolved live socket telemetry",
    )
    require(
        color_inactive_start_idx >= 0
        and color_inactive_failure_idx >= 0
        and color_inactive_live_idx >= 0
        and color_inactive_available_idx >= 0
        and color_inactive_failure_idx < color_inactive_available_idx
        and color_inactive_live_idx < color_inactive_available_idx,
        "fresh concrete proxy phases must choose row color before stale Available green for non-current proxy rows",
    )
    has_failure_idx = diagnostics.find("public static boolean hasFreshFailure")
    has_failure_body = diagnostics[has_failure_idx:diagnostics.find("public static String statusText", has_failure_idx)]
    require(
        "lastCheckDiagnosticTime" in has_failure_body
        and "isFailure(proxyInfo.lastCheckDiagnostic)" in has_failure_body,
        "fresh failure phases must use diagnostic timestamp, not only proxy-check availability timestamp",
    )
    store_text = text("store")
    reducer_text = text("reducer")
    visible_store_text = text("visible_store")
    require(
        (
            "ProxyPhasePolicy.isFailure(event.phase)" in reducer_text
            and "!ProxyCheckDiagnostics.UNKNOWN_FAIL.equals(event.phase)" in reducer_text
        )
        or (
            "String normalizedPhase = ProxyCheckDiagnostics.normalize(event.phase)" in reducer_text
            and ("ProxyPhasePolicy.isFailure(normalizedPhase)" in reducer_text or "verdict.isFailure()" in reducer_text)
            and "!ProxyCheckDiagnostics.UNKNOWN_FAIL.equals(normalizedPhase)" in reducer_text
        ),
        "current proxy stage callback must accept concrete failure phases while rejecting unknown_fail noise",
    )
    require(
        "shouldKeepFreshFailure" in diagnostics
        and "isWeakRetryLivePhase" in diagnostics
        and "ProxyCheckDiagnostics.shouldKeepFreshFailure(proxyInfo, event.phase, event.activationGeneration)" in visible_store_text,
        "fresh terminal failures must not be overwritten by early retry phases such as admission_queue or host_resolve_start",
    )
    require(
        "isProxyUsableSuccessPhase" in diagnostics
        and "FIRST_TLS_APP_RECV" in diagnostics
        and "FIRST_MTPROXY_PACKET_RECV" in diagnostics,
        "ProxyCheckDiagnostics must define concrete data-path success phases that prove a proxy is usable again",
    )
    from mtproxy_phase_contract import java_policy
    require(
        java_policy("server_hello_hmac_ok") == ("live", "exact", False, False)
        and java_policy("first_tls_app_recv") == ("success", "exact", False, False)
        and java_policy("first_mtproxy_packet_recv") == ("success", "exact", False, False),
        "server_hello_hmac_ok must remain a handshake live phase, not a data-path usable success",
    )
    require(
        ("ProxyPhasePolicy.isProxyUsableSuccessPhase(event.phase)" in reducer_text or "if (verdict.usableSuccess)" in reducer_text)
        and "ProxyRuntimeStateStore.applyConnectionUsable(currentProxy, event.phase, event.timestamp, event.activationGeneration)" in reducer_text,
        "concrete success phases from native must clear stale Java endpoint backoff and fresh terminal failures",
    )
    require(
        "ProxyRuntimeStateStore.setChecking(proxyInfo, checking);" in text("scheduler")
        and "ProxyRuntimeStateStore.setChecking(listener.proxyInfo, checking);" in text("scheduler")
        and "ProxyStatusMirror.markCheckingIfNoFreshConcretePhase(proxyInfo);" in store_text
        and "hasFreshConcreteProxyPhase(SharedConfig.ProxyInfo proxyInfo)" in text("status"),
        "background proxy-check must not overwrite a fresh live/failure phase of the selected proxy with generic checking",
    )
    cooldown_idx = store_text.find("public static void markEndpointCooldown")
    cooldown_body = store_text[cooldown_idx:store_text.find("public static void markCheckingIfNoFreshConcretePhase", cooldown_idx)]
    require(
        cooldown_idx >= 0
        and "hasFreshConcreteProxyPhase(proxyInfo)" in cooldown_body
        and "ProxyStatusMirror.markEndpointCooldown(proxyInfo, now)" in cooldown_body
        and "ProxyCheckDiagnostics.ENDPOINT_COOLDOWN" in text("status"),
        "endpoint cooldown must not overwrite a fresher concrete proxy phase",
    )
    require(
        "boolean selectedAccountStage = event.account == UserConfig.selectedAccount;" in reducer_text
        and "boolean stageTargetsCurrentProxy = currentProxy != null && concretePhase && ProxyEndpointKey.matchesLiveStage(currentProxy, event.endpointKey);" in reducer_text
        and "if (selectedAccountStage && visibleOwner && verdict.canOverwriteVisible)" in reducer_text,
        "native proxy live stages from background accounts must not overwrite the shared visible proxy diagnostic",
    )
    stage_callback = reducer_text[
        reducer_text.find("static ProxyRuntimeStateStore.Decision reduce"):
        reducer_text.find("private static boolean isActiveProxyEvent", reducer_text.find("static ProxyRuntimeStateStore.Decision reduce"))
    ]
    mark_failure_idx = stage_callback.find("rememberLiveFailure(currentProxy, event.phase, event.timestamp, event.suggestedHoldMs);")
    selected_ui_idx = stage_callback.find("if (selectedAccountStage && visibleOwner && verdict.canOverwriteVisible)")
    require(
        mark_failure_idx >= 0
        and selected_ui_idx >= 0
        and mark_failure_idx > selected_ui_idx,
        "terminal endpoint failures from any account must update shared backoff outside selected-account UI filtering",
    )
    require(
        "final String endpointKey" in text("connections_java")
        and "ProxyConnectionEvent.nativeStage(currentAccount, diagnostic, endpointKey, probeKey, origin, socketRole, activationGeneration, suggestedHoldMs" in text("connections_java")
        and "ProxyEndpointKey.matchesLiveStage(currentProxy, event.endpointKey)" in reducer_text,
        "native proxy live stages from stale endpoint/secret keys must not overwrite the currently selected proxy diagnostic",
    )
    require(
        "proxyConnectionStageChanged" in text("notification_center")
        and "onProxyConnectionStageChanged" in text("connections_java")
        and "NotificationCenter.proxyConnectionStageChanged" in text("connections_java"),
        "Java must expose a NotificationCenter event for current proxy live stages",
    )
    require(
        "NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyConnectionStageChanged" in text("connections_java"),
        "proxy live stages must also be posted globally because SharedConfig.currentProxy is global across accounts",
    )
    require(
        "onProxyConnectionStageChanged" in text("defines")
        and "probeKey" in text("defines")
        and "activationGeneration" in text("defines")
        and "socketRole" in text("defines")
        and "jclass_ConnectionsManager_onProxyConnectionStageChanged" in text("wrapper")
        and 'GetStaticMethodID(jclass_ConnectionsManager, "onProxyConnectionStageChanged", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V")' in text("wrapper"),
        "JNI bridge must forward native proxy live stages with endpoint and probe keys to ConnectionsManager",
    )
    require(
        "publishProxyConnectionStage" in text("socket_h")
        and "publishProxyConnectionStage(" in text("socket")
        and "currentMtProxyNetworkEndpointKey" in text("socket")
        and "currentMtProxyEndpointKey" in text("socket")
        and "isCurrentMtProxyConnection()" in text("socket_h")
        and "markMtProxyFirstPlainDataSent" in text("socket_h")
        and "markMtProxyFirstPlainDataReceived" in text("socket_h")
        and "void ConnectionSocket::markMtProxyFirstPlainDataSent" in text("socket")
        and "void ConnectionSocket::markMtProxyFirstPlainDataReceived" in text("socket")
        and "!isCurrentMtProxyConnection()" in text("socket")
        and "!overrideProxyAddress.empty()" in text("socket")
        and 'publishProxyConnectionStage("host_resolve_start")' in text("socket")
        and 'proxyCheckDiagnostic = "host_resolve_failed"' in text("socket")
        and 'publishProxyConnectionStage("client_hello_sent")' in text("socket")
        and 'publishProxyConnectionStage("admission_hold_after_client_hello_failure")' in text("socket")
        and 'publishProxyConnectionStage("server_hello_hmac_ok")' in text("socket")
        and "observation.phase = MtProxyPhase::FirstTlsAppRecv" in text("socket")
        and "observation.phase = MtProxyPhase::FirstMtproxyPacketRecv" in text("socket")
        and "publishMtProxySocketObservation(observation)" in text("socket"),
        "ConnectionSocket must publish live stages for plain dd/legacy MTProxy too, not only FakeTLS ee",
    )
    require(
        "public static boolean matchesEndpointStageKey" in text("scheduler")
        and "endpointStageKeyForLiveStage" in text("scheduler")
        and "decodedSecretForLiveStage" in text("endpoint_key")
        and "if (args == null || args.length < 2 || !(args[1] instanceof String))" in text("proxy_list")
        and "ProxyCheckScheduler.matchesEndpointStageKey(selectedProxy, endpointKey)" in text("proxy_list")
        and "String endpointKey = (String) args[1];" in text("rotation")
        and "reducerDecision" in text("rotation")
        and "rotationTrigger" in text("rotation")
        and "decision=ignored_stale_endpoint" in text("reducer"),
        "UI and Java lifecycle code must ignore proxy live stages from stale endpoint/secret keys",
    )
    require(
        "postNotificationName(NotificationCenter.proxyConnectionStageChanged, normalizedDiagnostic, endpointKey, event.origin.wireName, event.activationGeneration, event.socketRole.wireName, decision.decision, decision.rotationTrigger ? 1 : 0)" in text("connections_java"),
        "proxy live stage notifications must carry endpoint key and origin so UI/rotation can isolate stale or non-active events",
    )
    require(
        "publishProxyConnectionStage(proxyCheckDiagnostic.c_str())" in text("socket"),
        "ConnectionSocket must publish a concrete terminal diagnostic on failed current-proxy disconnects",
    )
    require(
        "NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.proxyConnectionStageChanged)" in text("proxy_list")
        and "NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.proxyConnectionStageChanged)" in text("proxy_list")
        and "NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyConnectionStageChanged)" not in text("proxy_list")
        and "NotificationCenter.getGlobalInstance().addWeakObserver(this, NotificationCenter.proxyConnectionStageChanged)" not in text("proxy_list")
        and "NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyConnectionStageChanged)" not in text("proxy_list")
        and "id == NotificationCenter.proxyConnectionStageChanged" in text("proxy_list"),
        "Proxy list must refresh header and current row only on current-account live proxy stage updates",
    )
    launch_text = text("launch")
    launch_uses_explicit_observer = (
        "NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.proxyConnectionStageChanged)" in launch_text
        and "NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.proxyConnectionStageChanged)" in launch_text
    )
    launch_uses_observer_group = (
        "observersGroup = NotificationCenter.getInstance(currentAccount)" in launch_text
        and ".createObserversGroup(this)" in launch_text
        and ".add(NotificationCenter.proxyConnectionStageChanged)" in launch_text
        and "observersGroup.removeAllObservers()" in launch_text
    )
    require(
        (launch_uses_explicit_observer or launch_uses_observer_group)
        and "id == NotificationCenter.proxyConnectionStageChanged" in launch_text
        and "updateCurrentConnectionState(account)" in launch_text,
        "main screen proxy title must refresh on live proxy stages even when the generic connection state does not change",
    )
    require(
        ".add(NotificationCenter.proxyConnectionStageChanged)" in text("dialogs")
        and "id == NotificationCenter.proxyConnectionStageChanged" in text("dialogs")
        and "ProxyCheckDiagnostics.headerStatusText" in text("dialogs")
        and "proxyMenuSubItem.setSubtext(proxyStatusText)" in text("dialogs"),
        "dialogs proxy menu must show the same concrete proxy phase as the proxy settings UI",
    )
    require(
        "NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.proxyConnectionStageChanged)" in text("chat_avatar")
        and "NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.proxyConnectionStageChanged)" in text("chat_avatar")
        and "id == NotificationCenter.proxyConnectionStageChanged" in text("chat_avatar")
        and "ProxyCheckDiagnostics.headerStatusText" in text("chat_avatar")
        and "title = getString(R.string.ConnectingToProxy)" not in text("chat_avatar"),
        "chat header must show concrete proxy stages instead of generic ConnectingToProxy",
    )
    require(
        "ProxyCheckDiagnostics.headerStatusText" in text("profile")
        and "LocaleController.getString(R.string.ConnectingToProxy)" not in text("profile"),
        "profile header must use the shared concrete proxy status text instead of generic ConnectingToProxy",
    )
    require(
        "proxy_connection_stage" in text("collector"),
        "live Java proxy stages must be collected into mtproxy marker logs",
    )
    for name in ("values", "values_ru"):
        source = text(name)
        for string_name in (
            "ProxyStatusAdmissionQueue",
            "ProxyStatusHostResolve",
            "ProxyStatusHostResolveFailed",
            "ProxyStatusTcpConnecting",
            "ProxyStatusTcpConnected",
            "ProxyStatusClientHelloSent",
            "ProxyStatusAdmissionHoldAfterClientHelloFailure",
            "ProxyStatusServerHelloOk",
            "ProxyStatusMtprotoStarting",
            "ProxyStatusFirstDataSent",
            "ProxyStatusFirstDataReceived",
            "ProxyStatusFirstMtproxyPacketSent",
            "ProxyStatusFirstMtproxyPacketReceived",
        ):
            require(f'name="{string_name}"' in source, f"{name} must define {string_name}")

    print("Proxy live connection stages guard passed.")


if __name__ == "__main__":
    main()
