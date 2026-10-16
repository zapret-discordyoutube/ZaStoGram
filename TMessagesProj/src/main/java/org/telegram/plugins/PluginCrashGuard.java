package org.telegram.plugins;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.chaquo.python.PyException;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps a bug in a plugin from taking the whole app down.
 *
 * Plugins create their own Java proxies (Runnable, listeners, ...) with Chaquopy's
 * dynamic_proxy. A Python exception inside such a callback reaches the main looper as a
 * PyException and, uncaught, kills the process — for a toast called with the wrong
 * number of arguments. The guard runs the main looper inside a try/catch: an exception
 * whose Python frames come from an installed .plugin file is logged, reported to the
 * user and swallowed; a plugin that keeps failing is disabled. Everything else is
 * rethrown unchanged, so app crashes are reported exactly as before.
 *
 * Installed only once the plugin runtime has started, so users without plugins keep the
 * stock main loop.
 */
final class PluginCrashGuard {

    private static final long STRIKE_WINDOW_MS = 60_000;
    private static final int STRIKES_TO_DISABLE = 3;
    private static final String PLUGIN_FILE_SUFFIX = ".plugin";

    private static volatile boolean installed;
    private static final Map<String, ArrayDeque<Long>> strikes = new HashMap<>();

    private PluginCrashGuard() {
    }

    static void install() {
        if (installed) {
            return;
        }
        installed = true;
        new Handler(Looper.getMainLooper()).post(PluginCrashGuard::loopGuarded);
    }

    private static void loopGuarded() {
        while (true) {
            try {
                Looper.loop();
                return; // the main looper quit: the process is going away
            } catch (Throwable t) {
                final PluginInfo plugin = findCulprit(t);
                if (plugin == null) {
                    sneakyThrow(t);
                    return;
                }
                handle(plugin, t);
            }
        }
    }

    /** The installed plugin whose own code raised this exception, or null. */
    static PluginInfo findCulprit(Throwable t) {
        boolean python = false;
        String pluginFile = null;
        String elyxId = null;
        for (Throwable e = t; e != null && e.getCause() != e; e = e.getCause()) {
            if (e instanceof PyException) {
                python = true;
            }
            for (StackTraceElement frame : e.getStackTrace()) {
                final String file = frame.getFileName();
                final String cls = frame.getClassName();
                if (file != null
                        && file.endsWith(PLUGIN_FILE_SUFFIX)
                        && cls.startsWith("<python>")) {
                    pluginFile = file;
                    break;
                }
                // Elyx plugins run as modules named ElyxPlugins.<id>.<...>
                int elyx = cls.indexOf("ElyxPlugins.");
                if (cls.startsWith("<python>") && elyx >= 0) {
                    String rest = cls.substring(elyx + "ElyxPlugins.".length());
                    int dot = rest.indexOf('.');
                    elyxId = dot > 0 ? rest.substring(0, dot) : rest;
                    break;
                }
            }
            if (elyxId != null) {
                break;
            }
            if (pluginFile != null) {
                break;
            }
        }
        if (elyxId != null) { // a <python> frame of the plugin's own module
            return PluginsController.getInstance().findById(elyxId);
        }
        if (!python || pluginFile == null) {
            return null;
        }
        for (PluginInfo info : PluginsController.getInstance().getPlugins()) {
            if (info.filePath != null && new File(info.filePath).getName().equals(pluginFile)) {
                return info;
            }
        }
        return null;
    }

    private static void handle(PluginInfo plugin, Throwable t) {
        FileLog.e("[plugin:" + plugin.id + "] uncaught exception on the main thread", t);
        final String message = shortMessage(t);
        plugin.error = message;

        final boolean disable;
        synchronized (strikes) {
            ArrayDeque<Long> times = strikes.get(plugin.id);
            if (times == null) {
                times = new ArrayDeque<>();
                strikes.put(plugin.id, times);
            }
            final long now = SystemClock.elapsedRealtime();
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > STRIKE_WINDOW_MS) {
                times.removeFirst();
            }
            disable = times.size() >= STRIKES_TO_DISABLE;
            if (disable) {
                times.clear();
            }
        }
        final String name = plugin.displayName();
        if (disable) {
            PluginsController.getInstance().setEnabled(plugin.id, false);
        }
        try {
            BulletinFactory.global().createErrorBulletin(disable
                    ? "Плагин «" + name + "» отключён: он повторно вызывал ошибки"
                    : "Ошибка в плагине «" + name + "»: " + message).show();
        } catch (Throwable ignore) {
        }
    }

    private static String shortMessage(Throwable t) {
        for (Throwable e = t; e != null && e.getCause() != e; e = e.getCause()) {
            if (e instanceof PyException && e.getMessage() != null) {
                final String text = e.getMessage();
                return text.length() > 160 ? text.substring(0, 160) + "…" : text;
            }
        }
        return t.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
