package org.telegram.proxy;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebMessagePortCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.ForegroundDetector;

import java.io.ByteArrayInputStream;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WEB proxy: tgnet connects to a loopback port as to a plain MTProxy, and
 * the bytes travel to the relay through a private WebView running the
 * relay's bridge page, so the TLS/HTTP/WebSocket connection to the relay is
 * made by the browser engine.
 *
 * <p>This class is the Android side only: the WebView, its hardening, the
 * page authentication and the message boundary to the page. The data path
 * (loopback sockets, stream mux, flow control, liveness) is
 * {@link WebProxyEngine}, which runs on its own thread and hands this class
 * whole batches of frames.
 *
 * <p>The boundary is a WebMessagePort when the WebView supports it: a
 * document-start shim gives the page the same {@code TelegramWebProxy}
 * object the listener would, backed by a port whose messages reach a
 * background thread, so the page's per-frame messages are not parsed and
 * dispatched on the UI thread. Otherwise, or after a port page failed to
 * come up, the origin-scoped WebMessage listener is used; its messages
 * arrive on the UI thread, which then only takes the bytes and enqueues
 * them, and batches to the page are posted one message per batch (merged
 * while the UI thread was busy). Either way the relay connection is made by
 * the WebView, and the page, the relay protocol and the nonce check are the
 * same.
 */
public final class WebProxyTransport implements ForegroundDetector.Listener, WebProxyEngine.Host {
    private static final String BRIDGE_OBJECT = "TelegramWebProxy";
    // Batches that queued up while the UI thread was busy go out as one
    // message up to this size (see WebProxyEngine.BATCH_LIMIT for why not
    // more).
    private static final int POST_MERGE_LIMIT = 128 * 1024;

    private static final Object staticLock = new Object();
    private static volatile WebProxyTransport instance;
    private static WebProxyTransport connectionTestInstance;

    private final String address;
    private final String host;
    private final String path;
    private final String bridgePath;
    private final String secret;
    private final String origin;
    private final String bridgeUrl;
    private final String androidNonce;
    private final WebProxyEngine engine;
    private volatile boolean stopped;
    private ReadyCallback readyCallback;

    // UI thread only.
    private WebView webView;
    private JavaScriptReplyProxy replyProxy;
    private int pageToken;
    // The current page talks through a port (see PORT_SHIM).
    private boolean pageUsesPort;
    // A port page failed before the relay welcomed it: use the listener.
    private boolean portsFailed;

    // The port of the current page; replaced under portLock.
    private final Object portLock = new Object();
    private PagePort pagePort;
    private volatile boolean carrierWelcomed;

    // Engine thread -> UI thread.
    private final Object outboxLock = new Object();
    private final ArrayList<byte[]> outbox = new ArrayList<>();
    private int outboxToken;
    private boolean outboxScheduled;
    private final Runnable drainOutboxRunnable = this::drainOutbox;

    private static final class PagePort {
        final WebMessagePortCompat port;
        int token;

        PagePort(WebMessagePortCompat port) {
            this.port = port;
        }
    }

    // Stands in for the injected TelegramWebProxy object: the same
    // postMessage/onmessage contract, over the port Android transfers to the
    // main frame once the page has loaded. Messages the page sends before
    // that are queued. Only the first port delivered by the app is taken.
    private static final String PORT_SHIM = "(()=>{'use strict';"
            + "let port=null;const queue=[];"
            + "const bridge={onmessage:null,postMessage(value){"
            + "if(port)port.postMessage(value,value instanceof ArrayBuffer?[value]:[]);else queue.push(value)}};"
            + "addEventListener('message',event=>{"
            + "if(port||event.data!=='tproxy-android-port'||event.ports.length!==1)return;"
            + "event.stopImmediatePropagation();port=event.ports[0];"
            + "port.onmessage=message=>{if(bridge.onmessage)bridge.onmessage({data:message.data})};"
            + "for(const value of queue.splice(0))bridge.postMessage(value)},true);"
            + "Object.defineProperty(globalThis,'TelegramWebProxy',{value:bridge,configurable:false,writable:false})"
            + "})();";
    private static final String PORT_HELLO = "tproxy-android-port";
    private static final long PORT_INIT_TIMEOUT_MS = 15_000;
    private static HandlerThread portThread;
    private static Handler portHandler;

    private static synchronized Handler portHandler() {
        if (portHandler == null) {
            portThread = new HandlerThread("WebProxyPort");
            portThread.start();
            portHandler = new Handler(portThread.getLooper());
        }
        return portHandler;
    }

    private static boolean supportsPorts() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_CALLBACK_ON_MESSAGE)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static int start(String address, String secret) {
        Address normalized = normalizeAddress(address);
        byte[] secretBytes = decodeSecret(secret);
        if (normalized == null || secretBytes == null || !isSupported()) {
            return 0;
        }
        synchronized (staticLock) {
            if (instance != null && instance.address.equals(normalized.value) && instance.secret.equals(secret)) {
                return instance.engine.port();
            }
            if (instance != null) {
                instance.stopInternal();
                instance = null;
            }
            try {
                instance = new WebProxyTransport(normalized, secret, secretBytes);
                instance.startInternal();
                return instance.engine.port();
            } catch (Exception e) {
                FileLog.e(e);
                if (instance != null) {
                    instance.stopInternal();
                    instance = null;
                }
                return 0;
            }
        }
    }

    public static int getActiveLocalPort(String address, String secret) {
        Address normalized = normalizeAddress(address);
        if (normalized == null) {
            return 0;
        }
        synchronized (staticLock) {
            if (instance != null && instance.address.equals(normalized.value) && instance.secret.equals(secret)) {
                return instance.engine.port();
            }
        }
        return 0;
    }

    public static void stop() {
        synchronized (staticLock) {
            if (instance != null) {
                instance.stopInternal();
                instance = null;
            }
        }
    }

    private static WebProxyTransport findByBridgePort(int bridgePort) {
        synchronized (staticLock) {
            if (instance != null && instance.engine.port() == bridgePort) {
                return instance;
            }
            if (connectionTestInstance != null && connectionTestInstance.engine.port() == bridgePort) {
                return connectionTestInstance;
            }
        }
        return null;
    }

    /**
     * tgnet announces the class of a bridge connection right after its
     * connect(), keyed by the socket's local port, which is the remote port
     * of the socket the bridge accepts. Called on the tgnet network thread.
     */
    public static void registerLocalStream(int bridgePort, int localPort, int streamClass) {
        WebProxyTransport target = findByBridgePort(bridgePort);
        if (target != null) {
            target.engine.registerLocalStream(localPort, streamClass);
        }
    }

    /**
     * tgnet asks whether a bridge connection silent for its receive timeout
     * may keep waiting. Returns the WebProxyFlow.Decision JNI encoding:
     * positive to wait, negative to fail. Called on the tgnet network thread.
     */
    public static long receiveWait(int bridgePort, int localPort, long waitStartedAt) {
        WebProxyTransport target = findByBridgePort(bridgePort);
        if (target == null) {
            return -WebProxyFlow.REASON_CARRIER_DOWN;
        }
        return target.engine.receiveWait(localPort, waitStartedAt);
    }

    public static int startConnectionCheck(String address, String secret, ReadyCallback readyCallback) {
        Address normalized = normalizeAddress(address);
        byte[] secretBytes = decodeSecret(secret);
        if (normalized == null || secretBytes == null || !isSupported()) {
            return 0;
        }
        synchronized (staticLock) {
            if (connectionTestInstance != null) {
                connectionTestInstance.stopInternal();
                connectionTestInstance = null;
            }
            try {
                connectionTestInstance = new WebProxyTransport(normalized, secret, secretBytes);
                connectionTestInstance.readyCallback = readyCallback;
                connectionTestInstance.startInternal();
                return connectionTestInstance.engine.port();
            } catch (Exception e) {
                FileLog.e(e);
                if (connectionTestInstance != null) {
                    connectionTestInstance.stopInternal();
                    connectionTestInstance = null;
                }
                return 0;
            }
        }
    }

    public static void stopConnectionCheck() {
        synchronized (staticLock) {
            if (connectionTestInstance != null) {
                connectionTestInstance.stopInternal();
                connectionTestInstance = null;
            }
        }
    }

    public interface ReadyCallback {
        void onReady();
    }

    public static boolean isSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean isValidSecret(String value) {
        return decodeSecret(value) != null;
    }

    public static String normalizeHost(String value) {
        if (value == null) {
            return "";
        }
        value = value.trim();
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
        if (value.length() > 253 || value.indexOf('.') <= 0 || value.contains(":") || value.matches("[0-9.]+")) {
            return "";
        }
        String[] labels = value.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return "";
            }
        }
        return value;
    }

    private static Address normalizeAddress(String value) {
        if (value == null) {
            return null;
        }
        int slash = value.indexOf('/');
        String host = normalizeHost(slash >= 0 ? value.substring(0, slash) : value);
        String path = slash >= 0 ? value.substring(slash + 1) : "";
        if (TextUtils.isEmpty(host) || !isValidPath(path)) {
            return null;
        }
        return new Address(host, path);
    }

    private static boolean isValidPath(String value) {
        if (value.length() > 128) {
            return false;
        }
        if (value.isEmpty()) {
            return true;
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || !Character.isLetterOrDigit(segment.charAt(0))) {
                return false;
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (!(c >= 'A' && c <= 'Z')
                        && !(c >= 'a' && c <= 'z')
                        && !(c >= '0' && c <= '9')
                        && c != '_'
                        && c != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private WebProxyTransport(Address address, String secret, byte[] secretBytes) throws Exception {
        this.address = address.value;
        this.host = address.host;
        this.path = address.path;
        bridgePath = path.isEmpty() ? "/" : "/" + path + "/";
        this.secret = secret;
        origin = "https://" + host;
        androidNonce = randomToken(32);
        String context = path.isEmpty()
                ? "tdesktop-web-proxy-bridge-v1\n" + host
                : "tdesktop-web-proxy-bridge-v2\n" + host + "\n" + path;
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
        String capability = Base64.encodeToString(
                hmac.doFinal(context.getBytes(StandardCharsets.UTF_8)),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        bridgeUrl = origin + bridgePath + "?bridge=" + capability + "#android=" + androidNonce;
        engine = new WebProxyEngine(this);
    }

    private void startInternal() {
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null) {
            detector.addListener(this);
        }
        engine.start();
        AndroidUtilities.runOnUIThread(this::createWebView);
    }

    private void stopInternal() {
        if (stopped) {
            return;
        }
        stopped = true;
        engine.stop();
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null) {
            detector.removeListener(this);
        }
        synchronized (outboxLock) {
            outbox.clear();
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (replyProxy != null) {
                    replyProxy.postMessage("{\"t\":\"close\"}");
                }
                PagePort current = currentPort();
                if (current != null) {
                    current.port.postMessage(new WebMessageCompat("{\"t\":\"close\"}"));
                }
            } catch (Exception ignore) {
            }
            destroyWebView();
        });
    }

    // ---- WebProxyEngine.Host (engine thread). ----

    private PagePort currentPort() {
        synchronized (portLock) {
            return pagePort;
        }
    }

    @Override
    public void postToPage(int token, byte[] batch) {
        WebMessagePortCompat port = null;
        synchronized (portLock) {
            if (pagePort != null && pagePort.token == token && token != 0) {
                port = pagePort.port;
            }
        }
        if (port != null) {
            // The WebView hands the message to its own thread; no UI hop of
            // ours is needed.
            try {
                port.postMessage(new WebMessageCompat(batch));
            } catch (Exception e) {
                FileLog.e(e);
                engine.pageFailed(token, "post_failed");
            }
            return;
        }
        synchronized (outboxLock) {
            if (stopped) {
                return;
            }
            if (token != outboxToken) {
                // Anything still queued belongs to a page that is gone.
                outbox.clear();
                outboxToken = token;
            }
            outbox.add(batch);
            if (outboxScheduled) {
                return;
            }
            outboxScheduled = true;
        }
        AndroidUtilities.runOnUIThread(drainOutboxRunnable);
    }

    @Override
    public void carrierFailed(String reason) {
        boolean welcomed = carrierWelcomed;
        carrierWelcomed = false;
        // The engine already closed every stream; rebuild the page, which
        // opens a fresh relay session.
        AndroidUtilities.runOnUIThread(() -> {
            if (pageUsesPort && !welcomed && !portsFailed) {
                // Never came up through the port: the next page uses the
                // listener.
                portsFailed = true;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("web_proxy_carrier event=port_fallback reason=" + reason);
                }
            }
            destroyWebView();
            if (!stopped) {
                AndroidUtilities.runOnUIThread(this::createWebView, 1000);
            }
        });
    }

    @Override
    public void carrierReady() {
        carrierWelcomed = true;
        ReadyCallback callback = readyCallback;
        if (callback != null) {
            AndroidUtilities.runOnUIThread(callback::onReady);
        }
    }

    @Override
    public long now() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public boolean logsEnabled() {
        return BuildVars.LOGS_ENABLED;
    }

    @Override
    public void log(String line) {
        FileLog.d(line);
    }

    @Override
    public void logError(Throwable error) {
        FileLog.e(error);
    }

    // ---- UI thread. ----

    // Posts what the engine produced since the last run. Batches that queued
    // up while the UI thread was busy are merged, so a busy UI thread costs
    // one message, not one per batch.
    private void drainOutbox() {
        ArrayList<byte[]> items;
        int token;
        synchronized (outboxLock) {
            items = new ArrayList<>(outbox);
            outbox.clear();
            token = outboxToken;
            outboxScheduled = false;
        }
        JavaScriptReplyProxy target = replyProxy;
        if (stopped || target == null || token != pageToken || items.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < items.size()) {
            byte[] message = items.get(index);
            int total = message.length;
            int next = index + 1;
            while (next < items.size() && total + items.get(next).length <= POST_MERGE_LIMIT) {
                total += items.get(next).length;
                next++;
            }
            if (next - index > 1) {
                message = new byte[total];
                int offset = 0;
                for (int i = index; i < next; i++) {
                    byte[] item = items.get(i);
                    System.arraycopy(item, 0, message, offset, item.length);
                    offset += item.length;
                }
            }
            try {
                target.postMessage(message);
            } catch (Exception e) {
                FileLog.e(e);
                engine.pageFailed(token, "post_failed");
                return;
            }
            index = next;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        if (stopped || webView != null) {
            return;
        }
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null && detector.isBackground()) {
            // onBecameForeground() builds it.
            return;
        }
        // Starts the welcome timeout, and lets a failure below restart the
        // page again.
        engine.pageStarting();
        WebView view;
        try {
            view = new WebView(ApplicationLoader.applicationContext);
        } catch (Exception e) {
            FileLog.e(e);
            engine.pageFailed(0, "webview_create_failed");
            return;
        }
        webView = view;
        view.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView current, WebResourceRequest request) {
                return !request.isForMainFrame() || !isBridgeNavigation(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView current, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                        && !isAllowedNetworkRequest(url)) {
                    return new WebResourceResponse("text/plain", "UTF-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onReceivedSslError(WebView current, SslErrorHandler handler, SslError error) {
                handler.cancel();
                failWebView(current);
            }

            @Override
            public void onReceivedError(WebView current, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    failWebView(current);
                }
            }

            @Override
            public void onReceivedHttpError(WebView current, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    failWebView(current);
                }
            }

            @Override
            public void onPageFinished(WebView current, String url) {
                if (current == webView && pageUsesPort) {
                    connectPort(current);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView current, RenderProcessGoneDetail detail) {
                failWebView(current);
                return true;
            }
        });
        Set<String> rules = new HashSet<>();
        rules.add(origin);
        pageUsesPort = !portsFailed && supportsPorts();
        try {
            if (pageUsesPort) {
                WebViewCompat.addDocumentStartJavaScript(view, PORT_SHIM, rules);
            } else {
                WebViewCompat.addWebMessageListener(view, BRIDGE_OBJECT, rules, this::onWebMessage);
            }
        } catch (Exception e) {
            FileLog.e(e);
            if (pageUsesPort) {
                portsFailed = true;
            }
            engine.pageFailed(0, "boundary_failed");
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("web_proxy_carrier event=page boundary=" + (pageUsesPort ? "port" : "listener"));
        }
        view.loadUrl(bridgeUrl);
        if (pageUsesPort) {
            // The page authenticates itself as soon as it has the port. If
            // that never happens (a WebView that runs the shim but never
            // delivers the port), fall back to the listener well before the
            // engine's welcome timeout.
            AndroidUtilities.runOnUIThread(() -> {
                if (webView != view || stopped) {
                    return;
                }
                PagePort current = currentPort();
                boolean authenticated;
                synchronized (portLock) {
                    authenticated = current != null && current.token != 0;
                }
                if (!authenticated) {
                    portsFailed = true;
                    engine.pageFailed(0, "port_silent");
                }
            }, PORT_INIT_TIMEOUT_MS);
        }
    }

    private void failWebView(WebView source) {
        if (source == webView) {
            engine.pageFailed(pageToken, "webview_error");
        }
    }

    private boolean isBridgeNavigation(Uri value) {
        return value != null && bridgeUrl.equals(value.toString());
    }

    private boolean isAllowedNetworkRequest(Uri value) {
        String requestPath = value.getPath();
        return "https".equalsIgnoreCase(value.getScheme())
                && host.equalsIgnoreCase(value.getHost())
                && value.getUserInfo() == null
                && (value.getPort() == -1 || value.getPort() == 443)
                && requestPath != null
                && requestPath.startsWith(bridgePath);
    }

    private boolean isCurrentBridgeDocument(WebView source) {
        String currentUrl = source.getUrl();
        if (currentUrl == null) {
            return false;
        }
        Uri value = Uri.parse(currentUrl);
        return "https".equalsIgnoreCase(value.getScheme())
                && host.equalsIgnoreCase(value.getHost())
                && value.getUserInfo() == null
                && value.getPort() == -1
                && bridgePath.equals(value.getPath());
    }

    private void onWebMessage(
            WebView sourceView,
            WebMessageCompat message,
            Uri sourceOrigin,
            boolean isMainFrame,
            JavaScriptReplyProxy sourceReplyProxy) {
        if (stopped
                || sourceView != webView
                || !isMainFrame
                || sourceOrigin == null
                || !origin.equals(sourceOrigin.toString())
                || !isCurrentBridgeDocument(sourceView)) {
            return;
        }
        if (message.getType() == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            if (replyProxy == null || replyProxy != sourceReplyProxy) {
                return;
            }
            // The only work on the UI thread: take the bytes and wake the
            // engine.
            engine.pageBytes(pageToken, message.getArrayBuffer());
        } else if (message.getType() == WebMessageCompat.TYPE_STRING) {
            handleControl(message.getData(), sourceReplyProxy);
        }
    }

    // Transfers one end of a new channel to the bridge document; only the
    // exact bridge origin can receive it.
    private void connectPort(WebView view) {
        synchronized (portLock) {
            if (pagePort != null) {
                return;
            }
        }
        try {
            WebMessagePortCompat[] ports = WebViewCompat.createWebMessageChannel(view);
            PagePort created = new PagePort(ports[0]);
            ports[0].setWebMessageCallback(portHandler(), new WebMessagePortCompat.WebMessageCallbackCompat() {
                @Override
                public void onMessage(@NonNull WebMessagePortCompat port, @Nullable WebMessageCompat message) {
                    onPortMessage(created, message);
                }
            });
            synchronized (portLock) {
                pagePort = created;
            }
            WebViewCompat.postWebMessage(view, new WebMessageCompat(PORT_HELLO, new WebMessagePortCompat[]{ports[1]}), Uri.parse(origin));
        } catch (Exception e) {
            FileLog.e(e);
            portsFailed = true;
            engine.pageFailed(0, "port_failed");
        }
    }

    // Port thread.
    private void onPortMessage(PagePort source, WebMessageCompat message) {
        if (stopped || message == null) {
            return;
        }
        synchronized (portLock) {
            if (pagePort != source) {
                return;
            }
        }
        if (message.getType() == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            int token;
            synchronized (portLock) {
                token = source.token;
            }
            if (token != 0) {
                engine.pageBytes(token, message.getArrayBuffer());
            }
            return;
        }
        String data = message.getData();
        if (isRoutineControl(data)) {
            return;
        }
        try {
            JSONObject object = new JSONObject(data);
            String type = object.optString("t");
            if ("tproxy-android-init".equals(type)
                    && object.optInt("v") == 1
                    && androidNonce.equals(object.optString("nonce"))) {
                synchronized (portLock) {
                    if (source.token == 0) {
                        // Under portLock: the HELLO the engine posts for this
                        // token waits until the port knows its token.
                        source.token = engine.pageInit();
                    }
                }
                return;
            }
            if ("close".equals(type) || "failed".equals(object.optString("state"))) {
                int token;
                synchronized (portLock) {
                    token = source.token;
                }
                engine.pageFailed(token, "page_" + ("close".equals(type) ? "close" : "failed"));
            }
        } catch (Exception ignore) {
        }
    }

    // The page's per-batch byte counters and its "connected" status arrive
    // with every carrier message: diagnostics only, not worth a JSON parse.
    private static boolean isRoutineControl(String data) {
        return data == null
                || data.startsWith("{\"t\":\"traffic\"")
                || data.equals("{\"t\":\"status\",\"state\":\"connected\"}");
    }

    private void handleControl(String data, JavaScriptReplyProxy sourceReplyProxy) {
        if (isRoutineControl(data)) {
            return;
        }
        try {
            JSONObject object = new JSONObject(data);
            String type = object.optString("t");
            if ("tproxy-android-init".equals(type)
                    && object.optInt("v") == 1
                    && androidNonce.equals(object.optString("nonce"))) {
                if (replyProxy != null) {
                    return;
                }
                replyProxy = sourceReplyProxy;
                pageToken = engine.pageInit();
                return;
            }
            if (replyProxy != null && replyProxy != sourceReplyProxy) {
                return;
            }
            if ("close".equals(type) || "failed".equals(object.optString("state"))) {
                engine.pageFailed(pageToken, "page_" + ("close".equals(type) ? "close" : "failed"));
            }
        } catch (Exception ignore) {
        }
    }

    @Override
    public void onBecameForeground() {
        AndroidUtilities.runOnUIThread(this::createWebView);
    }

    @Override
    public void onBecameBackground() {
    }

    private void destroyWebView() {
        WebView value = webView;
        webView = null;
        replyProxy = null;
        pageToken = 0;
        PagePort port;
        synchronized (portLock) {
            port = pagePort;
            pagePort = null;
        }
        if (port != null) {
            try {
                port.port.close();
            } catch (Exception ignore) {
            }
        }
        if (value != null) {
            try {
                value.stopLoading();
                value.loadUrl("about:blank");
                value.removeAllViews();
                value.destroy();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static byte[] decodeSecret(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        byte[] result;
        if ((value.length() == 32 || value.length() == 34) && value.matches("[0-9a-fA-F]+")) {
            result = new byte[value.length() / 2];
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
        } else {
            try {
                result = Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP);
            } catch (Exception e) {
                return null;
            }
        }
        if (result.length == 16 || result.length == 17 && (result[0] & 0xff) == 0xdd) {
            return result;
        }
        return null;
    }

    private static String randomToken(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static final class Address {
        private final String host;
        private final String path;
        private final String value;

        private Address(String host, String path) {
            this.host = host;
            this.path = path;
            value = path.isEmpty() ? host : host + "/" + path;
        }
    }
}
