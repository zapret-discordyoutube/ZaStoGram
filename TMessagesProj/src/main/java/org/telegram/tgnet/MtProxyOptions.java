package org.telegram.tgnet;

public final class MtProxyOptions {
    public final int tlsProfile;
    public final int clientHelloFragmentation;
    public final int connectionPatternMode;
    public final int recordSizingMode;
    public final int timingMode;
    public final int startupCoverMode;
    // True only for the WEB proxy's loopback bridge (127.0.0.1 -> WebView
    // carrier). Native code keeps such connections outside MTProxy pacing:
    // no per-endpoint connect queue, no endpoint cooldown and no reconnect
    // backoff, because there is no remote relay or DPI on loopback.
    public final boolean webBridge;
    // True for a Naive proxy entry (zsg_naive module): the native side carries the DC connection through the
    // in-process Naive client instead of SOCKS5/MTProxy, so every MTProxy stealth mode stays off.
    public final boolean naive;

    public MtProxyOptions(int tlsProfile, int clientHelloFragmentation, int connectionPatternMode, int recordSizingMode, int timingMode, int startupCoverMode) {
        this(tlsProfile, clientHelloFragmentation, connectionPatternMode, recordSizingMode, timingMode, startupCoverMode, false);
    }

    public MtProxyOptions(int tlsProfile, int clientHelloFragmentation, int connectionPatternMode, int recordSizingMode, int timingMode, int startupCoverMode, boolean webBridge) {
        this(tlsProfile, clientHelloFragmentation, connectionPatternMode, recordSizingMode, timingMode, startupCoverMode, webBridge, false);
    }

    public MtProxyOptions(int tlsProfile, int clientHelloFragmentation, int connectionPatternMode, int recordSizingMode, int timingMode, int startupCoverMode, boolean webBridge, boolean naive) {
        this.tlsProfile = tlsProfile;
        this.clientHelloFragmentation = clientHelloFragmentation;
        this.connectionPatternMode = connectionPatternMode;
        this.recordSizingMode = recordSizingMode;
        this.timingMode = timingMode;
        this.startupCoverMode = startupCoverMode;
        this.webBridge = webBridge;
        this.naive = naive;
    }

    public static MtProxyOptions resolve() {
        return resolve("", 1080, "");
    }

    public static MtProxyOptions resolve(String address, int port, String secret) {
        return new MtProxyOptions(
                ConnectionsManager.resolveMtProxyTlsProfile(address, port, secret),
                ConnectionsManager.resolveMtProxyClientHelloFragmentationMode(),
                ConnectionsManager.resolveMtProxyConnectionPatternMode(),
                ConnectionsManager.resolveMtProxyRecordSizingMode(),
                ConnectionsManager.resolveMtProxyTimingMode(),
                ConnectionsManager.resolveMtProxyStartupCoverMode());
    }

    public static MtProxyOptions disabled() {
        return new MtProxyOptions(
                ConnectionsManager.MT_PROXY_TLS_PROFILE_AUTO,
                ConnectionsManager.MT_PROXY_CLIENT_HELLO_FRAGMENTATION_OFF,
                ConnectionsManager.MT_PROXY_CONNECTION_PATTERN_OFF,
                ConnectionsManager.MT_PROXY_RECORD_SIZING_OFF,
                ConnectionsManager.MT_PROXY_TIMING_OFF,
                ConnectionsManager.MT_PROXY_STARTUP_COVER_OFF);
    }

    // WEB proxy bridge: every stealth mode off, like disabled(), plus the
    // marker that exempts the loopback bridge from MTProxy pacing.
    public static MtProxyOptions webBridge() {
        return new MtProxyOptions(
                ConnectionsManager.MT_PROXY_TLS_PROFILE_AUTO,
                ConnectionsManager.MT_PROXY_CLIENT_HELLO_FRAGMENTATION_OFF,
                ConnectionsManager.MT_PROXY_CONNECTION_PATTERN_OFF,
                ConnectionsManager.MT_PROXY_RECORD_SIZING_OFF,
                ConnectionsManager.MT_PROXY_TIMING_OFF,
                ConnectionsManager.MT_PROXY_STARTUP_COVER_OFF,
                true);
    }

    // Naive proxy: every stealth mode off, plus the marker the native side uses to pick the Naive transport.
    public static MtProxyOptions naive() {
        return new MtProxyOptions(
                ConnectionsManager.MT_PROXY_TLS_PROFILE_AUTO,
                ConnectionsManager.MT_PROXY_CLIENT_HELLO_FRAGMENTATION_OFF,
                ConnectionsManager.MT_PROXY_CONNECTION_PATTERN_OFF,
                ConnectionsManager.MT_PROXY_RECORD_SIZING_OFF,
                ConnectionsManager.MT_PROXY_TIMING_OFF,
                ConnectionsManager.MT_PROXY_STARTUP_COVER_OFF,
                false,
                true);
    }
}
