package org.telegram.proxy;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ProxyCheckDiagnostics;
import org.telegram.tgnet.RequestTimeDelegate;

import java.util.ArrayDeque;

public final class WebProxyConnectionTester {

    private static final long READY_TIMEOUT = 10_000;
    private static final long NATIVE_CHECK_TIMEOUT = 20_000;

    private static volatile WebProxyConnectionTester instance;

    public static WebProxyConnectionTester getInstance() {
        WebProxyConnectionTester local = instance;
        if (local == null) {
            synchronized (WebProxyConnectionTester.class) {
                local = instance;
                if (local == null) {
                    instance = local = new WebProxyConnectionTester();
                }
            }
        }
        return local;
    }

    private static final class Request {

        final TestImplementation testImplementation;
        final ProxySettings settings;
        final RequestTimeDelegate delegate;

        int port;

        Request(
                TestImplementation testImplementation,
                ProxySettings settings,
                RequestTimeDelegate delegate
        ) {
            this.testImplementation = testImplementation;
            this.settings = settings;
            this.delegate = delegate;
        }
    }

    private final ArrayDeque<Request> queue = new ArrayDeque<>();

    private Request currentRequest;

    private Runnable readyTimeoutRunnable;
    private Runnable nativeCheckTimeoutRunnable;

    private WebProxyConnectionTester() {
    }

    public void checkProxy(
            ProxySettings settings,
            RequestTimeDelegate delegate,
            TestImplementation testImplementation
    ) {
        if (testImplementation == null
                || settings == null
                || settings.getType() != ProxySettings.Type.WEB
                || !settings.isValid()
                || delegate == null) {
            if (delegate != null) {
                delegate.run(-1, ProxyCheckDiagnostics.START_FAILED);
            }
            return;
        }

        AndroidUtilities.runOnUIThread(() -> {
            queue.addLast(new Request(
                    testImplementation,
                    settings,
                    delegate
            ));

            processNext();
        });
    }

    private void processNext() {
        if (currentRequest != null) {
            return;
        }

        currentRequest = queue.pollFirst();
        if (currentRequest == null) {
            return;
        }

        Request request = currentRequest;

        readyTimeoutRunnable = () -> {
            if (currentRequest != request) {
                return;
            }

            readyTimeoutRunnable = null;

            failCurrent(request, ProxyCheckDiagnostics.CONNECTING_TIMEOUT);
        };

        AndroidUtilities.runOnUIThread(
                readyTimeoutRunnable,
                READY_TIMEOUT
        );

        request.port = WebProxyTransport.startConnectionCheck(
                request.settings.getAddress(),
                request.settings.getSecret(),
                () -> onTransportReady(request)
        );

        if (request.port == 0) {
            failCurrent(request, ProxyCheckDiagnostics.START_FAILED);
        }
    }

    private void onTransportReady(Request request) {
        AndroidUtilities.runOnUIThread(() -> {
            if (currentRequest != request) {
                return;
            }

            cancelReadyTimeout();

            int port = request.port;
            if (port == 0) {
                failCurrent(request, ProxyCheckDiagnostics.START_FAILED);
                return;
            }

            nativeCheckTimeoutRunnable = () -> {
                if (currentRequest != request) {
                    return;
                }

                nativeCheckTimeoutRunnable = null;

                failCurrent(request, ProxyCheckDiagnostics.CONNECTING_TIMEOUT);
            };

            AndroidUtilities.runOnUIThread(
                    nativeCheckTimeoutRunnable,
                    NATIVE_CHECK_TIMEOUT
            );

            request.testImplementation.doCheck(
                    request.settings,
                    port,
                    (time, diagnostic) -> AndroidUtilities.runOnUIThread(
                            () -> onNativeCheckFinished(request, time, diagnostic)
                    )
            );
        }, 500);
    }

    private void onNativeCheckFinished(Request request, long time, String diagnostic) {
        if (currentRequest != request) {
            return;
        }

        cancelNativeCheckTimeout();

        WebProxyTransport.stopConnectionCheck();

        currentRequest = null;

        request.delegate.run(time, diagnostic);

        processNext();
    }

    private void failCurrent(Request request, String diagnostic) {
        if (currentRequest != request) {
            return;
        }

        cancelReadyTimeout();
        cancelNativeCheckTimeout();

        WebProxyTransport.stopConnectionCheck();

        currentRequest = null;

        request.delegate.run(-1, diagnostic);

        processNext();
    }

    private void cancelReadyTimeout() {
        if (readyTimeoutRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(readyTimeoutRunnable);
            readyTimeoutRunnable = null;
        }
    }

    private void cancelNativeCheckTimeout() {
        if (nativeCheckTimeoutRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(nativeCheckTimeoutRunnable);
            nativeCheckTimeoutRunnable = null;
        }
    }

    public interface TestImplementation {
        void doCheck(
                ProxySettings settings,
                int port,
                RequestTimeDelegate requestTimeDelegate
        );
    }
}