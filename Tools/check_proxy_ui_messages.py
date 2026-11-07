#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
STRINGS_RU = ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml"
PROXY_LIST = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java"
PROXY_SETTINGS = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ProxySettingsActivity.java"
ANDROID_UTILITIES = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java"
DIAGNOSTICS = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ProxyCheckDiagnostics.java"
DIALOGS = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"
PROXY_DRAWABLE = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ProxyDrawable.java"
FILE_LOG = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/FileLog.java"

checks = [
    (STRINGS, 'name="ProxyStatusConnectingSlow"', "missing slow connecting proxy status string"),
    (STRINGS, 'name="ProxyStatusCheckingConnection"', "missing proxy checking status string"),
    (STRINGS, 'name="ProxyStatusEndpointCooldown"', "missing endpoint cooldown proxy status string"),
    (STRINGS, 'name="ProxyStatusTcpConnectGate"', "missing TCP connect gate proxy status string"),
    (STRINGS, 'name="ProxyStatusConnectionNotStarted"', "missing connection-not-started proxy status string"),
    (STRINGS, 'name="ProxyStatusAdmissionTimeout"', "missing admission timeout proxy status string"),
    (STRINGS, 'name="ProxyStatusEndpointCooldownTimeout"', "missing endpoint cooldown timeout proxy status string"),
    (STRINGS, 'name="ProxyStatusDnsCoalesceTimeout"', "missing DNS coalesce timeout proxy status string"),
    (STRINGS, 'name="ProxyStatusHostResolveTimeout"', "missing host resolve timeout proxy status string"),
    (STRINGS, 'name="ProxyStatusTcpConnectGateTimeout"', "missing TCP connect gate timeout proxy status string"),
    (STRINGS, 'name="ProxyStatusDnsCoalesceWait"', "missing DNS coalescing proxy status string"),
    (STRINGS, 'name="ProxyStatusDnsCacheHit"', "missing DNS cache-hit proxy status string"),
    (STRINGS, 'name="ProxyStatusDnsCacheStore"', "missing DNS cache-store proxy status string"),
    (STRINGS, 'name="ProxyStatusPhaseAdaptiveRecipe"', "missing phase-adaptive recipe proxy status string"),
    (STRINGS, 'name="ProxyStatusWaitingTcp"', "missing current-proxy TCP wait status string"),
    (STRINGS, 'name="ProxyStatusNetworkBlockSuspected"', "missing network-block suspected status string"),
    (STRINGS, 'name="ProxyStatusUnchecked"', "missing passive unchecked proxy status string"),
    (STRINGS, 'name="ProxyStatusNotRespondingNow"', "missing temporary proxy failure string"),
    (STRINGS, 'name="ProxyStatusTcpNotConnected"', "missing TCP failure proxy status string"),
    (STRINGS, 'name="ProxyStatusTcpConnectedNoPong"', "missing post-TCP/no-pong proxy status string"),
    (STRINGS, 'name="ProxyStatusClientHelloNoServerHello"', "missing ClientHello/ServerHello proxy status string"),
    (STRINGS, 'name="ProxyStatusMtproxyPacketSentNoResponse"', "missing dd/plain MTProxy no-response proxy status string"),
    (STRINGS, 'name="ProxyStatusDroppedEarlyAfterAppData"', "missing early post-appdata drop proxy status string"),
    (STRINGS, 'name="ProxyWindowStatusDisabled"', "missing proxy window disabled subtitle"),
    (STRINGS, 'name="ProxyWindowStatusReady"', "missing proxy window ready subtitle"),
    (STRINGS, 'name="ProxyWindowStatusChecking"', "missing proxy window checking subtitle"),
    (STRINGS, 'name="UseProxyTelegramInfoStealth"', "missing MTProto stealth hint string"),
    (STRINGS_RU, 'name="Connected"', "Russian localization missing for proxy connected status"),
    (STRINGS_RU, 'name="Available"', "Russian localization missing for proxy available status"),
    (STRINGS_RU, 'name="Ping"', "Russian localization missing for proxy ping status"),
    (DIAGNOSTICS, "ProxyStatusConnectingSlow", "diagnostic map does not use slow connecting text"),
    (DIAGNOSTICS, "ProxyStatusCheckingConnection", "diagnostic map does not use checking text"),
    (DIAGNOSTICS, "ProxyStatusWaitingTcp", "diagnostic map does not expose current-proxy TCP wait text"),
    (DIAGNOSTICS, "ProxyStatusConnectionNotStarted", "diagnostic map does not expose connection-not-started text"),
    (DIAGNOSTICS, "ProxyStatusAdmissionTimeout", "diagnostic map does not expose admission timeout text"),
    (DIAGNOSTICS, "ProxyStatusEndpointCooldownTimeout", "diagnostic map does not expose endpoint cooldown timeout text"),
    (DIAGNOSTICS, "ProxyStatusDnsCoalesceTimeout", "diagnostic map does not expose DNS coalesce timeout text"),
    (DIAGNOSTICS, "ProxyStatusHostResolveTimeout", "diagnostic map does not expose host resolve timeout text"),
    (DIAGNOSTICS, "ProxyStatusTcpConnectGateTimeout", "diagnostic map does not expose TCP gate timeout text"),
    (DIAGNOSTICS, "ProxyStatusNetworkBlockSuspected", "diagnostic map does not expose network-block suspected text"),
    (DIAGNOSTICS, "network_block_suspected", "diagnostic map must use a readable network-block phase string"),
    (DIAGNOSTICS, "ProxyStatusUnchecked", "diagnostic map must show passive proxy rows as unchecked"),
    (DIAGNOSTICS, "ProxyStatusTcpConnectedNoPong", "diagnostic map does not expose post-TCP/no-pong text"),
    (DIAGNOSTICS, "ProxyStatusDroppedEarlyAfterAppData", "diagnostic map does not expose early post-appdata drop text"),
    (DIAGNOSTICS, "headerStatusText", "diagnostic map must provide proxy-window header status text"),
    (DIAGNOSTICS, "shortDiagnosticText", "diagnostic map must provide compact per-proxy phase text"),
    (PROXY_LIST, "ProxyCheckDiagnostics.statusText", "proxy list must render proxy status through the diagnostic map"),
    (PROXY_LIST, "ProxyCheckDiagnostics.statusColorKey", "proxy list must choose status color through the diagnostic map"),
    (PROXY_LIST, "updateProxyActionBarStatus", "proxy list must keep the window header status in sync"),
    (PROXY_LIST, "simpleActionBar.setSubtitle", "proxy list must surface the current proxy phase in the window header"),
    (PROXY_SETTINGS, "R.string.UseProxyTelegramInfoStealth", "proxy settings does not show MTProto stealth hint"),
    (ANDROID_UTILITIES, "ProxyCheckScheduler.enqueueNow", "bottom-sheet proxy check must use the shared scheduler instead of direct native checkProxy"),
    (ANDROID_UTILITIES, "new SharedConfig.ProxyInfo", "bottom-sheet proxy check must pass through the shared ProxyInfo lifecycle"),
    (ANDROID_UTILITIES, "final Object proxyCheckOwner", "bottom-sheet proxy check must have an owner for lifecycle cancellation"),
    (ANDROID_UTILITIES, "ProxyCheckScheduler.cancelOwner(proxyCheckOwner)", "bottom-sheet proxy check must cancel queued or active work when the sheet is dismissed"),
    (ANDROID_UTILITIES, "ProxyCheckDiagnostics.diagnosticText", "bottom-sheet proxy failures must use the diagnostic map"),
    (ANDROID_UTILITIES, "if (!started)", "bottom-sheet proxy check must fail fast when the scheduler refuses to start"),
    (ANDROID_UTILITIES, "checking[0] = false;", "bottom-sheet proxy check must clear its checking flag on every terminal path"),
    (DIAGNOSTICS, "isProxyShieldConnected", "proxy diagnostics must expose shield connected semantics"),
    (DIAGNOSTICS, "proxyShieldAlertLevel", "proxy diagnostics must expose shield alert severity"),
    (DIAGNOSTICS, "proxyShieldAlertColorKey", "proxy diagnostics must expose shield alert colors"),
    (DIALOGS, "proxyDrawable.setStatus", "dialogs proxy shield must consume explicit connected/degraded status"),
    (DIALOGS, "ProxyCheckDiagnostics.proxyShieldAlertLevel", "dialogs proxy shield must compute degraded severity through diagnostics"),
    (DIALOGS, "ProxyCheckDiagnostics.proxyShieldAlertColorKey", "dialogs proxy shield must compute degraded color through diagnostics"),
    (PROXY_DRAWABLE, "private int alertLevel", "proxy shield drawable must keep degraded alert level"),
    (PROXY_DRAWABLE, "public void setStatus(boolean enabled, boolean value, int alertLevel, int alertColor, boolean animated)", "proxy shield drawable must expose a status API with alert severity"),
    (PROXY_DRAWABLE, "canvas.drawCircle", "proxy shield drawable must render a degraded badge"),
    (FILE_LOG, "proxy_diagnostic_session", "forced log rotation must write a diagnostic session marker"),
]

for _, needle, message in list(checks):
    if needle.startswith('name="ProxyStatus') or needle.startswith('name="ProxyWindowStatus'):
        checks.append((STRINGS_RU, needle, "Russian localization missing: " + message))

android_utilities_text = ANDROID_UTILITIES.read_text(encoding="utf-8")
if "ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy" in android_utilities_text:
    print("Proxy UI message guard failed:")
    print(f" - {ANDROID_UTILITIES.relative_to(ROOT)}: bottom-sheet proxy check must not bypass ProxyCheckScheduler")
    sys.exit(1)

failed = []
proxy_list_text = PROXY_LIST.read_text(encoding="utf-8")
update_rows_start = proxy_list_text.find("private void updateRows")
proxy_add_row_index = proxy_list_text.find("proxyAddRow = rowCount++;", update_rows_start)
proxy_start_row_index = proxy_list_text.find("proxyStartRow = rowCount;", update_rows_start)
if (
    update_rows_start == -1
    or proxy_add_row_index == -1
    or proxy_start_row_index == -1
    or proxy_add_row_index > proxy_start_row_index
):
    failed.append(
        f"{PROXY_LIST.relative_to(ROOT)}: Add Proxy row must be assigned before proxyStartRow so it renders above the proxy list"
    )

for path, needle, message in checks:
    text = path.read_text(encoding="utf-8")
    if needle not in text:
        failed.append(f"{path.relative_to(ROOT)}: {message}")

if failed:
    print("Proxy UI message guard failed:")
    for item in failed:
        print(f" - {item}")
    sys.exit(1)

print("Proxy UI message guard passed.")
