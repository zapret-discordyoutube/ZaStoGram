package org.telegram.zastogram.naive;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.proxy.ProxySettings;

/**
 * Routes the app's WebViews (mini apps, in-app browser) through the selected Naive proxy.
 * <p>
 * A WebView has its own network stack, so tgnet's proxy never sees its traffic. While a Naive entry is selected, a loopback
 * proxy (jni/zsg_naive/WebProxy.cpp) tunnels CONNECT requests through the Naive server, and {@link ProxyController} points
 * every WebView of the process at it. There is no direct rule: if the loopback proxy is down the pages fail (fail closed).
 * Fork-only; the single call site is ConnectionsManager (see jni/zsg_naive/README.md).
 */
public final class NaiveWebProxy {

    private static boolean overrideSet;

    private NaiveWebProxy() {
    }

    private static native int native_start(String host, int port, String user, String password);

    private static native void native_stop();

    /**
     * The Naive entry the WebViews use when the chats do not: the first valid one of the proxy list, but only while the
     * "mini apps through Naive" setting is on.
     */
    private static ProxySettings webAppsEntry() {
        if (!SharedConfig.naiveWebAppsEnabled) {
            return null;
        }
        SharedConfig.loadProxyList();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.settings.getType() == ProxySettings.Type.NAIVE && info.settings.isValid()) {
                return info.settings;
            }
        }
        return null;
    }

    /**
     * @param selected the selected proxy entry, or null when none is selected. A selected Naive entry carries the
     *                 WebViews; otherwise the "mini apps through Naive" setting may pick a Naive entry from the list;
     *                 with neither the loopback proxy stops and the WebView override is cleared.
     */
    public static synchronized void apply(ProxySettings selected) {
        ProxySettings naive = selected != null && selected.getType() == ProxySettings.Type.NAIVE ? selected : webAppsEntry();
        try {
            if (naive == null || naive.getType() != ProxySettings.Type.NAIVE) {
                clear();
                return;
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                FileLog.e("naive_web_proxy: this WebView cannot take a proxy override; mini apps stay direct");
                clear();
                return;
            }
            final int port = native_start(naive.getAddress(), naive.getPort(), naive.getUser(), naive.getPassword());
            if (port == 0) {
                FileLog.e("naive_web_proxy: loopback proxy did not start");
                clear();
                return;
            }
            final ProxyConfig config = new ProxyConfig.Builder().addProxyRule("127.0.0.1:" + port).build();
            ProxyController.getInstance().setProxyOverride(config, Runnable::run, () -> {
            });
            overrideSet = true;
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void clear() {
        try {
            if (overrideSet) {
                ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> {
                });
                overrideSet = false;
            }
            native_stop();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
