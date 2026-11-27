package org.telegram.zastogram.wss;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Keeps the list of Cloudflare fronts of the WSS transport current without a release.
 *
 * tg-ws-proxy (Flowseal) publishes the working fronts in one text file and replaces them when a network blocks
 * them. The native transport (tgnet/wss/WssSocket.cpp) has a baked copy as the floor and reads
 * {@code wss_fronts.txt} from the config directory, which this class writes: at most once a day, from GitHub or,
 * when that is unreachable, from the jsDelivr mirror of the same file. The native side validates the file again
 * and re-reads it by itself (no restart, no JNI), so a failed or empty download changes nothing.
 */
public final class WssFrontsUpdater {

    static final String FILE_NAME = "wss_fronts.txt";
    private static final String[] SOURCES = {
            "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt",
            "https://cdn.jsdelivr.net/gh/Flowseal/tg-ws-proxy@main/.github/cfproxy-domains.txt",
    };
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final long RETRY_GAP_MS = 30L * 60 * 1000;
    private static final int MAX_BYTES = 16 * 1024;
    private static final int MIN_ENTRIES = 3;
    private static final int MAX_ENTRIES = 64;
    private static final Pattern LINE = Pattern.compile("[A-Za-z0-9.-]{4,80}");

    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile long lastAttempt;

    private WssFrontsUpdater() {
    }

    /** Called when the connection manager starts; cheap when the file is fresh or an attempt is already running. */
    public static void start(String configPath) {
        if (configPath == null || configPath.isEmpty()) {
            return;
        }
        final File target = new File(configPath, FILE_NAME);
        final long now = System.currentTimeMillis();
        if (target.isFile() && now - target.lastModified() < MAX_AGE_MS) {
            return;
        }
        if (now - lastAttempt < RETRY_GAP_MS || !running.compareAndSet(false, true)) {
            return;
        }
        lastAttempt = now;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                refresh(target);
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                running.set(false);
            }
        });
    }

    private static void refresh(File target) {
        for (String source : SOURCES) {
            String text = download(source);
            if (text != null && acceptable(text) && write(target, text)) {
                FileLog.d("wss fronts: updated from " + source);
                return;
            }
        }
        FileLog.d("wss fronts: no source answered, keeping the current list");
    }

    private static String download(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "ZaStoGram");
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_BYTES) {
                        return null;
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignore) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** The shape the native parser accepts: one front per line, comments allowed, MIN..MAX entries. */
    static boolean acceptable(String text) {
        int entries = 0;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!LINE.matcher(line).matches() || line.indexOf('.') < 0) {
                return false;
            }
            entries++;
        }
        return entries >= MIN_ENTRIES && entries <= MAX_ENTRIES;
    }

    private static boolean write(File target, String text) {
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Throwable e) {
            temp.delete();
            return false;
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            return false;
        }
        return true;
    }
}
