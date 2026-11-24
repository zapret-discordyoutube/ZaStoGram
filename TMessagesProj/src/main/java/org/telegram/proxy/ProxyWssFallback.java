package org.telegram.proxy;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProxyCheckScheduler;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

/**
 * Falls back from a proxy that does not connect to the built-in WSS transport.
 *
 * The user's proxy stays selected and enabled; only the native route changes. When the
 * selected account has been connecting for FALLBACK_DELAY_MS without success, the native
 * layer is switched to "no proxy", where WSS carries the traffic. The proxy is then probed
 * in the background, and the first successful probe routes traffic through it again.
 * A proxy that fails again soon after being restored is probed less and less often.
 *
 * All state is confined to the UI thread.
 */
public final class ProxyWssFallback {

    private static final long FALLBACK_DELAY_MS = 15_000;
    private static final long PROBE_INTERVAL_MIN_MS = 30_000;
    private static final long PROBE_INTERVAL_MAX_MS = 300_000;
    private static final long FLAP_WINDOW_MS = 300_000;

    private static boolean armed;
    private static boolean engaged;
    private static boolean restoring;
    private static boolean probing;
    private static long probeIntervalMs = PROBE_INTERVAL_MIN_MS;
    private static long restoredAt;
    private static int probeGeneration;

    private static final Runnable engageRunnable = ProxyWssFallback::engage;
    private static final Runnable probeRunnable = ProxyWssFallback::probe;
    private static final Object PROBE_OWNER = new Object();

    private ProxyWssFallback() {
    }

    public static boolean isEngaged() {
        return engaged;
    }

    /** Called for every native connection state change, on the UI thread. */
    public static void onConnectionState(int account, int state) {
        if (account != UserConfig.selectedAccount || engaged) {
            return;
        }
        final boolean connecting = state == ConnectionsManager.ConnectionStateConnecting
                || state == ConnectionsManager.ConnectionStateConnectingToProxy;
        if (connecting && isAllowed()) {
            if (!armed) {
                armed = true;
                AndroidUtilities.runOnUIThread(engageRunnable, FALLBACK_DELAY_MS);
            }
        } else if (armed) {
            armed = false;
            AndroidUtilities.cancelRunOnUIThread(engageRunnable);
        }
    }

    /** Called whenever proxy settings are applied; a settings change resets the fallback. */
    public static void onProxySettingsApplied() {
        if (restoring) {
            return;
        }
        AndroidUtilities.runOnUIThread(ProxyWssFallback::reset);
    }

    private static void reset() {
        armed = false;
        AndroidUtilities.cancelRunOnUIThread(engageRunnable);
        AndroidUtilities.cancelRunOnUIThread(probeRunnable);
        ProxyCheckScheduler.cancelOwner(PROBE_OWNER);
        probeGeneration++;
        probing = false;
        probeIntervalMs = PROBE_INTERVAL_MIN_MS;
        restoredAt = 0;
        if (engaged) {
            engaged = false;
            notifyChanged();
        }
    }

    private static boolean isAllowed() {
        return SharedConfig.wssTransportEnabled
                && SharedConfig.isProxyEnabled()
                && SharedConfig.currentProxy != null
                && SharedConfig.currentProxy.settings != null
                && SharedConfig.currentProxy.settings.isValid()
                // A Naive entry fails closed: it never falls back to a direct WSS route that would show the user's own address.
                && SharedConfig.currentProxy.settings.getType() != ProxySettings.Type.NAIVE;
    }

    private static void engage() {
        armed = false;
        final int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        final boolean connecting = state == ConnectionsManager.ConnectionStateConnecting
                || state == ConnectionsManager.ConnectionStateConnectingToProxy;
        if (engaged || !connecting || !isAllowed()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (restoredAt != 0 && now - restoredAt < FLAP_WINDOW_MS) {
            probeIntervalMs = Math.min(probeIntervalMs * 2, PROBE_INTERVAL_MAX_MS);
        } else {
            probeIntervalMs = PROBE_INTERVAL_MIN_MS;
        }
        engaged = true;
        FileLog.d("proxy_wss_fallback engage probe_interval_ms=" + probeIntervalMs);
        ConnectionsManager.applyWssFallbackRoute();
        notifyChanged();
        AndroidUtilities.runOnUIThread(probeRunnable, probeIntervalMs);
    }

    private static void probe() {
        if (!engaged || probing) {
            return;
        }
        if (!isAllowed()) {
            reset();
            return;
        }
        final int generation = probeGeneration;
        final SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
        final ProxySettings settings = info.settings;
        probing = ProxyCheckScheduler.enqueueNow(UserConfig.selectedAccount, info, PROBE_OWNER, new ProxyCheckScheduler.Callback() {
            @Override
            public void onProxyChecked(SharedConfig.ProxyInfo proxyInfo, long time, String diagnostic) {
                AndroidUtilities.runOnUIThread(() -> onProbeResult(generation, settings, time));
            }

            @Override
            public void onProxyCheckQueueFinished() {
            }
        });
        if (!probing) {
            AndroidUtilities.runOnUIThread(probeRunnable, probeIntervalMs);
        }
    }

    private static void onProbeResult(int generation, ProxySettings settings, long time) {
        if (generation != probeGeneration || !engaged) {
            return;
        }
        probing = false;
        if (time == -1 || !isAllowed() || SharedConfig.currentProxy.settings != settings) {
            AndroidUtilities.runOnUIThread(probeRunnable, probeIntervalMs);
            return;
        }
        FileLog.d("proxy_wss_fallback restore ping_ms=" + time);
        engaged = false;
        restoredAt = SystemClock.elapsedRealtime();
        restoring = true;
        try {
            ConnectionsManager.setProxySettings(true, settings);
        } finally {
            restoring = false;
        }
        notifyChanged();
    }

    private static void notifyChanged() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }
}
