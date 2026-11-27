package org.telegram.zastogram.naive;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Cloudflare WARP as an optional ENTRY hop (never an exit, never a proxy-list entry). Opt-in, off by default.
 * <p>
 * With the setting on, the native WireGuard + userspace TCP/IP engine of libzsgnaive (jni/zsg_naive/WarpEntry.cpp) opens a
 * tunnel to WARP, and every TCP connection of tgnet (direct, MTProxy, WSS, Naive) goes through a loopback port of that
 * engine. A tunnel that is not up leaves the connections direct. The account is registered with Cloudflare on first use and
 * kept in the app's private preferences.
 */
public final class WarpEntry {

    private static final String PREFS = "zsg_warp";
    private static final String REGISTER_URL = "https://api.cloudflareclient.com/v0a2158/reg";
    private static final String API_HOST = "api.cloudflareclient.com";
    private static final String MASQUE_API_PATH = "/v0a4471/reg";
    private static final int STATE_UP = 1;

    private static final Object lock = new Object();
    private static Thread worker;
    private static int generation;
    private static boolean initialized;

    private WarpEntry() {
    }

    private static native String[] native_keypair();

    private static native boolean native_start(String privateKey, String peerKey, String reserved, String address, String endpointHost, int endpointPort,
                                                String masqueKey, String masquePem, String masqueAddress, boolean masqueFirst);

    private static native String[] native_masqueKeypair();

    /** 0 none yet, 1 WireGuard, 2 MASQUE. */
    private static native int native_transport();

    private static native String native_stats();

    private static native int native_state();

    private static native void native_stop();

    private static native void native_reconnect(int account);

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean("enabled", false);
    }

    /** Once at startup, after the native library is loaded. */
    public static synchronized void init() {
        if (initialized) {
            return;  // tgnet calls this once per account
        }
        initialized = true;
        if (isEnabled()) {
            start();
        }
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean("enabled", enabled).apply();
        if (enabled) {
            start();
        } else {
            stop();
        }
    }

    private static void reconnectAll() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a == 0 || UserConfig.getInstance(a).isClientActivated()) {
                try {
                    native_reconnect(a);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        }
    }

    private static void stop() {
        synchronized (lock) {
            generation++;
            worker = null;
        }
        try {
            native_stop();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        reconnectAll();
    }

    private static void start() {
        final int mine;
        synchronized (lock) {
            mine = ++generation;
            worker = new Thread(() -> run(mine), "zsg-warp");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static boolean current(int mine) {
        synchronized (lock) {
            return generation == mine;
        }
    }

    private static void run(int mine) {
        try {
            while (current(mine) && !startTunnel()) {
                // Registration or start failed (no network yet, Cloudflare unreachable): try again later, connections stay direct.
                for (int i = 0; i < 300 && current(mine); i++) {
                    Thread.sleep(1000);
                }
            }
            int last = -2;
            long startedAt = System.currentTimeMillis();
            int registrations = 1;
            while (current(mine)) {
                final int state = native_state();
                if (state == STATE_UP) {
                    registrations = 1;
                    final boolean masque = native_transport() == 2;  // remember which transport works on this network
                    if (masque != prefs().getBoolean("prefer_masque", false)) {
                        prefs().edit().putBoolean("prefer_masque", masque).apply();
                    }
                } else if (state != STATE_UP && state != -1 && registrations < 4 && System.currentTimeMillis() - startedAt > 60000) {
                    // A fresh account sometimes never gets an answer from the WARP edge (about 1 in 5); a new one usually
                    // does. Accounts are throwaway, so drop this one and register again.
                    FileLog.d("zsg_warp no answer from any edge in 60 s, registering a new account");
                    registrations++;
                    native_stop();
                    FileLog.d("zsg_warp stats: " + native_stats());
                    prefs().edit().remove("private_key").remove("peer_public_key").remove("address").remove("masque_key").remove("masque_pem").remove("masque_address").apply();
                    while (current(mine) && !startTunnel()) {
                        for (int i = 0; i < 30 && current(mine); i++) {
                            Thread.sleep(1000);
                        }
                    }
                    startedAt = System.currentTimeMillis();
                    last = -2;
                    continue;
                }
                if (state != last) {
                    FileLog.d("zsg_warp tunnel state " + state + " transport " + native_transport() + " " + native_stats());
                    if (state == STATE_UP || last == STATE_UP) {
                        reconnectAll();  // connections made before the tunnel was up went out directly
                    }
                    last = state;
                }
                Thread.sleep(last == STATE_UP ? 2000 : 500);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static boolean startTunnel() {
        final SharedPreferences preferences = prefs();
        final boolean haveWireGuard = !preferences.getString("private_key", "").isEmpty() && !preferences.getString("peer_public_key", "").isEmpty()
                && !preferences.getString("address", "").isEmpty();
        boolean wireGuardOk = haveWireGuard || register(preferences);
        // The MASQUE account (a second device with an ECDSA key) is the fallback for networks that drop WireGuard's UDP.
        boolean masqueOk = !preferences.getString("masque_key", "").isEmpty() || enrollMasque(preferences);
        if (!wireGuardOk && !masqueOk) {
            return false;
        }
        final boolean started = native_start(
                wireGuardOk ? preferences.getString("private_key", "") : "", wireGuardOk ? preferences.getString("peer_public_key", "") : "",
                preferences.getString("reserved", ""), wireGuardOk ? preferences.getString("address", "") : "",
                preferences.getString("endpoint_host", ""), preferences.getInt("endpoint_port", 2408),
                masqueOk ? preferences.getString("masque_key", "") : "", masqueOk ? preferences.getString("masque_pem", "") : "",
                masqueOk ? preferences.getString("masque_address", "") : "", preferences.getBoolean("prefer_masque", false));
        FileLog.d("zsg_warp tunnel start " + started + " wireguard=" + wireGuardOk + " masque=" + masqueOk);
        return started;
    }

    /**
     * A throw-away WARP device is registered like the WireGuard one and then enrolled with an ECDSA P-256 key (a PATCH with the
     * device token). HttpURLConnection has no PATCH, so both calls go through a small HTTPS client.
     */
    private static boolean enrollMasque(SharedPreferences preferences) {
        try {
            final String[] masqueKeys = native_masqueKeypair();
            final String[] wireGuardKeys = native_keypair();
            if (masqueKeys == null || masqueKeys.length != 2 || wireGuardKeys == null || wireGuardKeys.length != 2) {
                return false;
            }
            final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            final JSONObject body = new JSONObject();
            body.put("key", wireGuardKeys[1]);
            body.put("install_id", "");
            body.put("fcm_token", "");
            body.put("tos", format.format(new Date()));
            body.put("model", "Android");
            body.put("serial_number", Long.toHexString(new java.security.SecureRandom().nextLong()));
            body.put("os_version", "");
            body.put("key_type", "curve25519");
            body.put("tunnel_type", "wireguard");
            body.put("locale", "en-US");
            String[] answer = httpsRequest("POST", MASQUE_API_PATH, null, body.toString());
            if (answer == null || !answer[0].startsWith("2")) {
                FileLog.e("zsg_warp masque device registration failed: " + (answer == null ? "no answer" : answer[0]));
                return false;
            }
            final JSONObject device = new JSONObject(answer[1]);
            final String id = device.optString("id", "");
            final String token = device.optString("token", "");
            if (id.isEmpty() || token.isEmpty()) {
                return false;
            }
            final JSONObject patch = new JSONObject();
            patch.put("key", masqueKeys[1]);
            patch.put("key_type", "secp256r1");
            patch.put("tunnel_type", "masque");
            patch.put("name", "Android");
            answer = httpsRequest("PATCH", MASQUE_API_PATH + "/" + id, token, patch.toString());
            if (answer == null || !answer[0].startsWith("2")) {
                FileLog.e("zsg_warp masque enrollment failed: " + (answer == null ? "no answer" : answer[0]));
                return false;
            }
            final JSONObject config = new JSONObject(answer[1]).getJSONObject("config");
            final JSONArray peers = config.getJSONArray("peers");
            final String serverKey = peers.getJSONObject(0).optString("public_key", "");
            final String address = config.getJSONObject("interface").getJSONObject("addresses").optString("v4", "");
            if (serverKey.isEmpty() || address.isEmpty()) {
                FileLog.e("zsg_warp masque enrollment answer is incomplete");
                return false;
            }
            preferences.edit().putString("masque_key", masqueKeys[0]).putString("masque_pem", serverKey).putString("masque_address", address).apply();
            FileLog.d("zsg_warp masque account enrolled");
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    /** A minimal HTTPS/1.1 client (any method, bearer token, JSON body): {status line, body} or null. */
    private static String[] httpsRequest(String method, String path, String bearer, String body) throws Exception {
        final javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) factory.createSocket()) {
            socket.connect(new java.net.InetSocketAddress(API_HOST, 443), 20000);
            socket.setSoTimeout(20000);
            socket.startHandshake();
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(API_HOST, socket.getSession())) {
                throw new java.io.IOException("host name mismatch");
            }
            final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            final StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: ").append(API_HOST).append("\r\n");
            head.append("User-Agent: WARP for Android\r\nCF-Client-Version: a-6.35-4471\r\nContent-Type: application/json\r\n");
            if (bearer != null) {
                head.append("Authorization: Bearer ").append(bearer).append("\r\n");
            }
            head.append("Content-Length: ").append(payload.length).append("\r\nConnection: close\r\n\r\n");
            final OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            final java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            final InputStream in = socket.getInputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0 && raw.size() < (1 << 20)) {
                raw.write(buffer, 0, read);
            }
            final byte[] bytes = raw.toByteArray();
            final String text = new String(bytes, StandardCharsets.ISO_8859_1);
            final int split = text.indexOf("\r\n\r\n");
            if (split < 0) {
                return null;
            }
            final String[] headerLines = text.substring(0, split).split("\r\n");
            final String status = headerLines[0].length() > 9 ? headerLines[0].substring(9) : "";
            boolean chunked = false;
            for (String line : headerLines) {
                if (line.toLowerCase(Locale.US).startsWith("transfer-encoding:") && line.toLowerCase(Locale.US).contains("chunked")) {
                    chunked = true;
                }
            }
            byte[] content = java.util.Arrays.copyOfRange(bytes, split + 4, bytes.length);
            if (chunked) {
                final java.io.ByteArrayOutputStream decoded = new java.io.ByteArrayOutputStream();
                int pos = 0;
                while (pos < content.length) {
                    int lineEnd = pos;
                    while (lineEnd + 1 < content.length && !(content[lineEnd] == '\r' && content[lineEnd + 1] == '\n')) {
                        lineEnd++;
                    }
                    final int size = Integer.parseInt(new String(content, pos, lineEnd - pos, StandardCharsets.ISO_8859_1).trim().split(";")[0], 16);
                    if (size == 0) {
                        break;
                    }
                    decoded.write(content, lineEnd + 2, Math.min(size, content.length - lineEnd - 2));
                    pos = lineEnd + 2 + size + 2;
                }
                content = decoded.toByteArray();
            }
            return new String[]{status, new String(content, StandardCharsets.UTF_8)};
        }
    }

    private static boolean register(SharedPreferences preferences) {
        HttpURLConnection connection = null;
        try {
            final String[] keys = native_keypair();
            if (keys == null || keys.length != 2) {
                return false;
            }
            final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            final JSONObject body = new JSONObject();
            body.put("key", keys[1]);
            body.put("install_id", "");
            body.put("fcm_token", "");
            body.put("tos", format.format(new Date()));
            body.put("model", "Android");
            body.put("type", "Android");
            body.put("locale", "en_US");
            connection = (HttpURLConnection) new URL(REGISTER_URL).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "okhttp/3.12.1");
            connection.setRequestProperty("CF-Client-Version", "a-6.3-2158");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            final int code = connection.getResponseCode();
            if (code / 100 != 2) {
                FileLog.e("zsg_warp registration http " + code);
                return false;
            }
            final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                final byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    bytes.write(buffer, 0, read);
                }
            }
            final JSONObject config = new JSONObject(bytes.toString("UTF-8")).getJSONObject("config");
            final JSONArray peers = config.getJSONArray("peers");
            final JSONObject peer = peers.getJSONObject(0);
            final JSONObject endpoint = peer.getJSONObject("endpoint");
            String host = endpoint.optString("v4", "");
            if (host.isEmpty()) {
                host = endpoint.optString("host", "");
            }
            final int colon = host.lastIndexOf(':');
            if (colon > 0) {
                host = host.substring(0, colon);
            }
            int port = 2408;
            final JSONArray ports = endpoint.optJSONArray("ports");
            if (ports != null && ports.length() > 0) {
                port = ports.optInt(0, 2408);
            }
            final String address = config.getJSONObject("interface").getJSONObject("addresses").optString("v4", "");
            final String peerKey = peer.optString("public_key", "");
            if (host.isEmpty() || address.isEmpty() || peerKey.isEmpty()) {
                FileLog.e("zsg_warp registration answer is incomplete");
                return false;
            }
            preferences.edit()
                    .putString("private_key", keys[0])
                    .putString("public_key", keys[1])
                    .putString("peer_public_key", peerKey)
                    .putString("endpoint_host", host)
                    .putInt("endpoint_port", port)
                    .putString("address", address)
                    .putString("reserved", config.optString("client_id", ""))
                    .apply();
            FileLog.d("zsg_warp account registered, endpoint " + host + ":" + port);
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
