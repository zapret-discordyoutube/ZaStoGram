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
    private static final int STATE_UP = 1;

    private static final Object lock = new Object();
    private static Thread worker;
    private static int generation;
    private static boolean initialized;

    private WarpEntry() {
    }

    private static native String[] native_keypair();

    private static native boolean native_start(String privateKey, String peerKey, String reserved, String address, String endpointHost, int endpointPort);

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
            while (current(mine)) {
                final int state = native_state();
                if (state != last) {
                    FileLog.d("zsg_warp tunnel state " + state);
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
        if (preferences.getString("private_key", "").isEmpty() || preferences.getString("peer_public_key", "").isEmpty()
                || preferences.getString("address", "").isEmpty()) {
            if (!register(preferences)) {
                return false;
            }
        }
        final boolean started = native_start(preferences.getString("private_key", ""), preferences.getString("peer_public_key", ""),
                preferences.getString("reserved", ""), preferences.getString("address", ""),
                preferences.getString("endpoint_host", ""), preferences.getInt("endpoint_port", 2408));
        FileLog.d("zsg_warp tunnel start " + started);
        return started;
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
