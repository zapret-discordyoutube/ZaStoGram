package org.telegram.proxy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Plain-JVM tests of WebProxyEngine over real loopback sockets, with an
 * in-process relay standing in for the bridge page and tproxy-server.
 * Compiled and run by Tools/check_web_proxy_isolation.py.
 */
public final class WebProxyEngineTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                check(false, message);
            }
            Thread.sleep(2);
        }
        checks++;
    }

    /** The relay side of the carrier: parses what the engine posts. */
    private static final class Relay implements WebProxyEngine.Host {
        static final class RelayStream {
            final ByteArrayOutputStream received = new ByteArrayOutputStream();
            long credit = WebProxyEngine.INITIAL_WINDOW;
            long unacked;
            boolean closed;
        }

        WebProxyEngine engine;
        volatile int token;
        volatile boolean autoCredit = true;
        final Map<Integer, RelayStream> streams = new HashMap<>();
        final List<byte[]> messages = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final AtomicInteger ready = new AtomicInteger();
        String badMessage;
        long maxUploadUnacked;

        @Override
        public synchronized void postToPage(int token, byte[] batch) {
            if (batch.length == 0) {
                badMessage = "empty message";
                return;
            }
            messages.add(batch);
            int offset = 0;
            while (offset < batch.length) {
                if (batch.length - offset < 8) {
                    badMessage = "truncated header";
                    return;
                }
                int type = batch[offset] & 0xff;
                int id = (batch[offset + 1] & 0xff) << 16 | (batch[offset + 2] & 0xff) << 8 | (batch[offset + 3] & 0xff);
                int length = (batch[offset + 4] & 0xff) << 24 | (batch[offset + 5] & 0xff) << 16 | (batch[offset + 6] & 0xff) << 8 | (batch[offset + 7] & 0xff);
                if (offset + 8 + length > batch.length) {
                    badMessage = "truncated frame";
                    return;
                }
                handle(token, type, id, batch, offset + 8, length);
                offset += 8 + length;
            }
        }

        private void handle(int page, int type, int id, byte[] data, int offset, int length) {
            if (type == WebProxyEngine.FRAME_HELLO) {
                // Answered as the page the HELLO was posted to, like a real
                // page does. The engine posts HELLO from its own thread as soon
                // as pageInit() queues it, which can be before the test thread
                // has stored the returned token: replying with this.token then
                // carried the previous page's token, the engine dropped the
                // WELCOME and the carrier never became ready (a loaded host
                // lost this race in preflight on 09.10.2026).
                engine.pageBytes(page, WebProxyEngine.frame(WebProxyEngine.FRAME_WELCOME, 0, null, 0, 0));
            } else if (type == WebProxyEngine.FRAME_OPEN) {
                streams.put(id, new RelayStream());
            } else if (type == WebProxyEngine.FRAME_DATA) {
                RelayStream stream = streams.get(id);
                if (stream == null) {
                    badMessage = "DATA before OPEN";
                    return;
                }
                stream.received.write(data, offset, length);
                stream.unacked += length;
                if (autoCredit) {
                    creditLocked(id, stream.unacked);
                }
            } else if (type == WebProxyEngine.FRAME_WINDOW) {
                long amount = (data[offset] & 0xffL) << 24 | (data[offset + 1] & 0xffL) << 16 | (data[offset + 2] & 0xffL) << 8 | (data[offset + 3] & 0xffL);
                streams.get(id).credit += amount;
            } else if (type == WebProxyEngine.FRAME_CLOSE) {
                RelayStream stream = streams.get(id);
                if (stream != null) {
                    stream.closed = true;
                }
            }
        }

        synchronized long totalUnacked(int... ids) {
            long total = 0;
            for (int id : ids) {
                total += streams.get(id).unacked;
            }
            return total;
        }

        synchronized void credit(int id, long amount) {
            creditLocked(id, amount);
        }

        private void creditLocked(int id, long amount) {
            RelayStream stream = streams.get(id);
            stream.unacked -= amount;
            reply(WebProxyEngine.frame(WebProxyEngine.FRAME_WINDOW, id, new byte[]{
                    (byte) (amount >> 24), (byte) (amount >> 16), (byte) (amount >> 8), (byte) amount}, 0, 4));
        }

        /** Relay DATA to the client, within the credit the client gave. */
        synchronized void send(int id, int bytes) {
            RelayStream stream = streams.get(id);
            check(stream.credit >= bytes, "the relay never sends beyond client credit");
            stream.credit -= bytes;
            while (bytes > 0) {
                int chunk = Math.min(bytes, 64 * 1024);
                byte[] payload = new byte[chunk];
                for (int i = 0; i < chunk; i++) {
                    payload[i] = (byte) i;
                }
                reply(WebProxyEngine.frame(WebProxyEngine.FRAME_DATA, id, payload, 0, chunk));
                bytes -= chunk;
            }
        }

        void reply(byte[] frame) {
            engine.pageBytes(token, frame);
        }

        synchronized int lastStreamId() {
            int result = 0;
            for (int id : streams.keySet()) {
                result = Math.max(result, id);
            }
            return result;
        }

        synchronized RelayStream stream(int id) {
            return streams.get(id);
        }

        @Override
        public synchronized void carrierFailed(String reason) {
            failures.add(reason);
            token = 0;
        }

        @Override
        public void carrierReady() {
            ready.incrementAndGet();
        }

        @Override
        public long now() {
            return System.nanoTime() / 1_000_000;
        }

        @Override
        public boolean logsEnabled() {
            return false;
        }

        @Override
        public void log(String line) {
        }

        @Override
        public void logError(Throwable error) {
            error.printStackTrace();
        }
    }

    private static Relay startCarrier() throws Exception {
        Relay relay = new Relay();
        relay.engine = new WebProxyEngine(relay);
        relay.engine.start();
        relay.engine.pageStarting();
        relay.token = relay.engine.pageInit();
        await(() -> relay.ready.get() == 1, "the relay welcomes the carrier");
        synchronized (relay) {
            check(relay.messages.size() == 1 && relay.messages.get(0).length == 9 && relay.messages.get(0)[0] == WebProxyEngine.FRAME_HELLO,
                    "the first message to the page is one HELLO frame alone");
        }
        return relay;
    }

    private static Socket connect(Relay relay, int streamClass, int receiveBuffer) throws Exception {
        Socket socket = new Socket();
        if (receiveBuffer > 0) {
            socket.setReceiveBufferSize(receiveBuffer);
        }
        socket.connect(new InetSocketAddress("127.0.0.1", relay.engine.port()));
        socket.setTcpNoDelay(true);
        relay.engine.registerLocalStream(socket.getLocalPort(), streamClass);
        return socket;
    }

    private static int openedStream(Relay relay, int previous) throws InterruptedException {
        await(() -> relay.lastStreamId() > previous, "the engine opens a stream for the new connection");
        return relay.lastStreamId();
    }

    private static byte[] readFully(InputStream input, int length) throws Exception {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            check(count > 0, "the stream stays open while reading");
            offset += count;
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        echo();
        interactiveNotBehindCappedUpload();
        slowReaderDoesNotBlockOthers();
        downloadCreditIsShared();
        closePropagates();
        carrierFailureRecovers();
        receiveWait();
        System.out.println("WebProxyEngineTest passed " + checks + " checks.");
    }

    private static void echo() throws Exception {
        Relay relay = startCarrier();
        Socket socket = connect(relay, WebProxyFlow.CLASS_INTERACTIVE, 0);
        int id = openedStream(relay, 0);
        socket.getOutputStream().write("hello".getBytes("UTF-8"));
        await(() -> relay.stream(id).received.size() == 5, "tgnet bytes reach the relay as DATA");
        relay.send(id, 70_000);
        byte[] got = readFully(socket.getInputStream(), 70_000);
        check(got[69_999] == (byte) (69_999 % 65_536), "relay DATA reaches tgnet in order");
        await(() -> relay.stream(id).credit == WebProxyEngine.INITIAL_WINDOW, "an interactive stream returns all consumed credit");
        check(relay.badMessage == null, "every message is a batch of complete frames: " + relay.badMessage);
        socket.close();
        relay.engine.stop();
    }

    private static void interactiveNotBehindCappedUpload() throws Exception {
        Relay relay = startCarrier();
        relay.autoCredit = false;
        Socket upload = connect(relay, WebProxyFlow.CLASS_UPLOAD, 0);
        int up = openedStream(relay, 0);
        Socket chat = connect(relay, WebProxyFlow.CLASS_INTERACTIVE, 0);
        int interactive = openedStream(relay, up);
        Thread writer = new Thread(() -> {
            try {
                OutputStream out = upload.getOutputStream();
                out.write(new byte[3 << 20]);
            } catch (Exception ignore) {
            }
        });
        writer.start();
        await(() -> relay.totalUnacked(up) >= WebProxyFlow.UPLINK_UPLOAD_IN_FLIGHT, "an upload fills the in-flight window");
        Thread.sleep(100);
        check(relay.totalUnacked(up) <= WebProxyFlow.UPLINK_UPLOAD_IN_FLIGHT + WebProxyFlow.UPLINK_FRAME_SIZE,
                "an upload without relay credit stops at the upload window");
        chat.getOutputStream().write(new byte[100]);
        await(() -> relay.stream(interactive).received.size() == 100, "a chat request passes an upload that is out of window");
        relay.autoCredit = true;
        relay.credit(up, relay.totalUnacked(up));
        await(() -> relay.stream(up).received.size() == 3 << 20, "credit resumes the upload to the end");
        writer.join();
        check(relay.badMessage == null, "every message is a batch of complete frames: " + relay.badMessage);
        upload.close();
        chat.close();
        relay.engine.stop();
    }

    private static void slowReaderDoesNotBlockOthers() throws Exception {
        Relay relay = startCarrier();
        Socket stuck = connect(relay, WebProxyFlow.CLASS_INTERACTIVE, 8 * 1024);
        int a = openedStream(relay, 0);
        Socket live = connect(relay, WebProxyFlow.CLASS_INTERACTIVE, 0);
        int b = openedStream(relay, a);
        // tgnet never reads `stuck`: 3 MiB for it cannot all fit its socket.
        relay.send(a, 3 << 20);
        Thread.sleep(200);
        long started = System.nanoTime();
        relay.send(b, 1000);
        readFully(live.getInputStream(), 1000);
        check(System.nanoTime() - started < 2_000_000_000L, "a stream whose reader is stuck does not hold up the others");
        byte[] all = readFully(stuck.getInputStream(), 3 << 20);
        check(all.length == 3 << 20, "the stuck stream still gets all of its bytes once read");
        stuck.close();
        live.close();
        relay.engine.stop();
    }

    private static void downloadCreditIsShared() throws Exception {
        Relay relay = startCarrier();
        Socket download = connect(relay, WebProxyFlow.CLASS_DOWNLOAD, 0);
        int id = openedStream(relay, 0);
        Thread.sleep(50);
        relay.send(id, (int) WebProxyEngine.INITIAL_WINDOW);
        readFully(download.getInputStream(), (int) WebProxyEngine.INITIAL_WINDOW);
        await(() -> relay.stream(id).credit == WebProxyFlow.DOWNLINK_DOWNLOAD_BUDGET, "a lone download gets back exactly the download budget");
        Thread.sleep(100);
        check(relay.stream(id).credit == WebProxyFlow.DOWNLINK_DOWNLOAD_BUDGET, "credit beyond the budget is withheld");
        download.close();
        relay.engine.stop();
    }

    private static void closePropagates() throws Exception {
        Relay relay = startCarrier();
        Socket first = connect(relay, WebProxyFlow.CLASS_INTERACTIVE, 0);
        int a = openedStream(relay, 0);
        Socket second = connect(relay, WebProxyFlow.CLASS_INTERACTIVE, 0);
        int b = openedStream(relay, a);
        first.close();
        await(() -> relay.stream(a).closed, "a closed tgnet socket sends CLOSE");
        relay.reply(WebProxyEngine.frame(WebProxyEngine.FRAME_CLOSE, b, null, 0, 0));
        second.setSoTimeout(5000);
        check(second.getInputStream().read() == -1, "a relay CLOSE closes the tgnet socket");
        check(relay.failures.isEmpty(), "closing streams never fails the carrier");
        relay.engine.stop();
    }

    private static void carrierFailureRecovers() throws Exception {
        Relay relay = startCarrier();
        Socket socket = connect(relay, WebProxyFlow.CLASS_INTERACTIVE, 0);
        int id = openedStream(relay, 0);
        int oldToken = relay.token;
        // A DATA frame beyond the credit the client granted.
        byte[] huge = new byte[8];
        huge[0] = WebProxyEngine.FRAME_DATA;
        huge[3] = (byte) id;
        huge[4] = 0x7f;
        relay.reply(huge);
        await(() -> relay.failures.size() == 1, "a malformed frame fails the carrier");
        socket.setSoTimeout(5000);
        check(socket.getInputStream().read() == -1, "a failed carrier closes every stream");
        relay.engine.pageBytes(oldToken, WebProxyEngine.frame(WebProxyEngine.FRAME_WELCOME, 0, null, 0, 0));
        relay.engine.pageFailed(oldToken, "late");
        Thread.sleep(100);
        check(relay.ready.get() == 1 && relay.failures.size() == 1, "frames and failures of the old page are ignored");
        synchronized (relay) {
            relay.messages.clear();
        }
        relay.engine.pageStarting();
        relay.token = relay.engine.pageInit();
        await(() -> relay.ready.get() == 2, "a new page gets a new carrier");
        Socket again = connect(relay, WebProxyFlow.CLASS_INTERACTIVE, 0);
        int next = openedStream(relay, id);
        again.getOutputStream().write(1);
        await(() -> relay.stream(next).received.size() == 1, "streams work on the new carrier");
        again.close();
        relay.engine.stop();
    }

    private static void receiveWait() throws Exception {
        Relay relay = startCarrier();
        Socket socket = connect(relay, WebProxyFlow.CLASS_INTERACTIVE, 0);
        int id = openedStream(relay, 0);
        long now = relay.now();
        long unknown = relay.engine.receiveWait(1, now - 1000);
        check(unknown < 0 && WebProxyFlow.decodeReason(unknown) == WebProxyFlow.REASON_STREAM_CLOSED, "an unknown connection is closed");
        long verdict = relay.engine.receiveWait(socket.getLocalPort(), now - 1000);
        check(verdict > 0, "a fresh stream on a live carrier may wait");
        relay.autoCredit = false;
        socket.getOutputStream().write(new byte[10]);
        await(() -> relay.stream(id).received.size() == 10, "the request reaches the relay");
        long queued = relay.engine.receiveWait(socket.getLocalPort(), relay.now() - 1000);
        check(queued > 0 && WebProxyFlow.decodeReason(queued) == WebProxyFlow.REASON_QUEUED, "an uncredited request is still queued");
        socket.close();
        relay.engine.stop();
    }
}
