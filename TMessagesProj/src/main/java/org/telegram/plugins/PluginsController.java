package org.telegram.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central controller for the ZaStoGram Python plugin engine (exteraGram-compatible).
 *
 * Responsibilities: starting the embedded CPython runtime (Chaquopy), discovering and
 * persisting installed .plugin files, loading/unloading them (which installs/removes their
 * Pine hooks), and routing settings + network-response callbacks between Java and Python.
 *
 * Threading: all Python execution is serialized onto {@link #queue}. The plugin metadata
 * list ({@link #plugins}) is parsed in pure Java so the UI can list plugins even before —
 * or without — the Python runtime being up.
 */
public class PluginsController {

    private static volatile PluginsController instance;

    private Context appContext;
    private DispatchQueue queue;

    private volatile boolean pythonStarted;
    private volatile boolean startupRequested;
    private PyObject loader; // _plugin_loader module

    private final List<PluginInfo> plugins = new ArrayList<>();
    private final Map<String, PluginContext> contexts = new HashMap<>();

    private static final Pattern META_PATTERN =
            Pattern.compile("^__(\\w+)__\\s*=\\s*(?:'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\")");

    public static PluginsController getInstance() {
        PluginsController local = instance;
        if (local == null) {
            synchronized (PluginsController.class) {
                local = instance;
                if (local == null) {
                    instance = local = new PluginsController();
                }
            }
        }
        return local;
    }

    private PluginsController() {
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called once from ApplicationLoader.onCreate(). Discovers plugins without starting Python. */
    public void init(Context context) {
        if (appContext != null) {
            return;
        }
        // Only run the Python engine in the main process — avoid starting CPython in :push etc.
        if (!isMainProcess(context)) {
            return;
        }
        appContext = context.getApplicationContext();
        queue = new DispatchQueue("zasto_plugins");
        registerAppLifecycle();
        // Build the metadata list synchronously (cheap, pure Java) so the UI is ready early.
        try {
            scanInstalled();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /**
     * exteraGram loads plugins once the UI is up, and plugins rely on that: on_plugin_load()
     * commonly calls get_last_fragment().getContext(). Our startup runs from postInitApplication,
     * possibly before the first fragment, so wait for one briefly on the plugin queue (not the
     * UI thread). A process started without UI (push) just proceeds after the timeout.
     */
    private static void awaitFirstFragment() {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (PluginUtils.getLastFragment() == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Starts the runtime after Telegram startup, and only if it has useful work to do. */
    public void startEnabledPlugins() {
        if (appContext == null || queue == null || startupRequested) {
            return;
        }
        startupRequested = true;
        queue.postRunnable(() -> {
            try {
                List<PluginInfo> snapshot;
                synchronized (plugins) {
                    snapshot = new ArrayList<>(plugins);
                }
                boolean hasEnabledCompatiblePlugin = false;
                for (PluginInfo info : snapshot) {
                    if (info.enabled && isCompatible(info)) {
                        hasEnabledCompatiblePlugin = true;
                        break;
                    }
                }
                if (!hasEnabledCompatiblePlugin) {
                    FileLog.d("zasto plugins: python startup skipped, no enabled compatible plugins");
                    refreshRequestHooks();
                    notifyChanged();
                    return;
                }
                ensurePythonStarted();
                awaitFirstFragment();
                for (PluginInfo info : snapshot) {
                    if (info.enabled && isCompatible(info)) {
                        loadPluginInternal(info);
                    }
                }
                refreshRequestHooks();
            } catch (Throwable t) {
                FileLog.e(t);
            }
            notifyChanged();
        });
    }

    private synchronized void ensurePythonStarted() {
        if (pythonStarted) {
            return;
        }
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(appContext));
            }
            loader = Python.getInstance().getModule("_plugin_loader");
            loader.callAttr("configure", new File(appContext.getFilesDir(), "plugin_libs").getAbsolutePath());
            pythonStarted = true;
            PluginCrashGuard.install();
        } catch (Throwable t) {
            FileLog.e("zasto plugins: failed to start python", t);
        }
    }

    public boolean isPythonReady() {
        return pythonStarted && loader != null;
    }

    // ------------------------------------------------------------------ discovery / persistence

    public File pluginsDir() {
        File dir = new File(appContext.getFilesDir(), "plugins");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences("zasto_plugins", Context.MODE_PRIVATE);
    }

    /** Rebuild {@link #plugins} from the files on disk + persisted enabled flags. */
    private void scanInstalled() {
        Set<String> ids = new HashSet<>(prefs().getStringSet("ids", new HashSet<>()));
        List<PluginInfo> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 1) Indexed plugins (carry their persisted enabled flag).
        for (String id : ids) {
            if (!isValidId(id)) {
                continue;
            }
            File f = installedFileForId(id);
            if (f == null) {
                continue;
            }
            PluginInfo info = parseMetadata(f);
            if (info == null) {
                info = new PluginInfo();
            }
            info.id = id;
            info.filePath = f.getAbsolutePath();
            info.enabled = prefs().getBoolean("enabled_" + id, true);
            list.add(info);
            seen.add(id);
        }
        // 2) Adopt any .plugin/.py file on disk that the index lost (e.g. a partial
        //    backup/restore), so the file is the source of truth after wiped prefs.
        File[] files = pluginsDir().listFiles();
        if (files != null) {
            Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
            for (File f : files) {
                String fn = f.getName();
                if (fn.startsWith("_import_") && fn.endsWith(".tmp")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete(); // sweep leftover staging files from an interrupted install (runs once at init)
                    continue;
                }
                boolean pluginExtension = fn.endsWith(".plugin");
                boolean pythonExtension = fn.endsWith(".py") && !fn.endsWith(".tmp.py");
                if (!pluginExtension && !pythonExtension) {
                    continue;
                }
                String extension = pluginExtension ? ".plugin" : ".py";
                String id = fn.substring(0, fn.length() - extension.length());
                if (seen.contains(id) || !isValidId(id)) {
                    continue;
                }
                PluginInfo info = parseMetadata(f);
                if (info == null) {
                    continue;
                }
                info.id = id;
                info.filePath = f.getAbsolutePath();
                info.enabled = prefs().getBoolean("enabled_" + id, true);
                list.add(info);
                seen.add(id);
            }
        }
        synchronized (plugins) {
            plugins.clear();
            plugins.addAll(list);
        }
        persistIndex(); // re-sync the index to match what is actually on disk
    }

    /** Prefer the most recently written extension when a manager leaves both behind. */
    private File installedFileForId(String id) {
        File plugin = new File(pluginsDir(), id + ".plugin");
        File python = new File(pluginsDir(), id + ".py");
        if (plugin.exists() && python.exists()) {
            return python.lastModified() > plugin.lastModified() ? python : plugin;
        }
        if (plugin.exists()) {
            return plugin;
        }
        return python.exists() ? python : null;
    }

    /**
     * Re-discover files written directly by exteraGram-compatible plugin managers.
     * Their Python engine API stores plugins as {@code <id>.py} and then asks the
     * engine to reload; normal ZaStoGram imports continue to use {@code .plugin}.
     */
    public void reloadPluginsFromDisk() {
        if (appContext == null || queue == null) {
            return;
        }
        List<PluginInfo> oldSnapshot;
        synchronized (plugins) {
            oldSnapshot = new ArrayList<>(plugins);
        }
        try {
            scanInstalled();
        } catch (Throwable t) {
            FileLog.e(t);
            return;
        }
        queue.postRunnable(() -> {
            for (PluginInfo old : oldSnapshot) {
                unloadPluginInternal(old);
            }
            List<PluginInfo> newSnapshot;
            synchronized (plugins) {
                newSnapshot = new ArrayList<>(plugins);
            }
            boolean hasEnabled = false;
            for (PluginInfo info : newSnapshot) {
                if (info.enabled && isCompatible(info)) {
                    hasEnabled = true;
                    break;
                }
            }
            if (hasEnabled) {
                ensurePythonStarted();
                for (PluginInfo info : newSnapshot) {
                    if (info.enabled && isCompatible(info)) {
                        loadPluginInternal(info);
                    }
                }
            }
            refreshRequestHooks();
            notifyChanged();
        });
    }

    private void persistIndex() {
        SharedPreferences.Editor e = prefs().edit();
        Set<String> ids = new HashSet<>();
        synchronized (plugins) {
            for (PluginInfo info : plugins) {
                ids.add(info.id);
                e.putBoolean("enabled_" + info.id, info.enabled);
            }
        }
        e.putStringSet("ids", ids);
        e.apply();
    }

    /** Pure-Java parse of the module-level __dunder__ metadata (no Python needed). */
    public static PluginInfo parseMetadata(File file) {
        PluginInfo info = new PluginInfo();
        boolean found = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            int scanned = 0;
            while ((line = r.readLine()) != null && scanned < 200) {
                scanned++;
                if (scanned == 1 && !line.isEmpty() && line.charAt(0) == '﻿') {
                    line = line.substring(1); // strip UTF-8 BOM (mirrors Python _read_source utf-8-sig)
                }
                Matcher m = META_PATTERN.matcher(line); // not trim(): keep the ^ anchor at column 0 (module-level only)
                if (!m.find()) {
                    continue;
                }
                String key = m.group(1);
                String value = unescapePy(m.group(2) != null ? m.group(2) : m.group(3));
                found = true;
                switch (key) {
                    case "id": info.id = value; break;
                    case "name": info.name = value; break;
                    case "description": info.description = value; break;
                    case "author": info.author = value; break;
                    case "version": info.version = value; break;
                    case "min_version": info.minVersion = value; break;
                    case "app_version": info.minVersion = value; break; // ">=X"; compareVersions strips the operator
                    case "icon": info.icon = value; break;
                    default: break;
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return found ? info : null;
    }

    /** Unescape a Python string-literal body (\\', \\", \\\\, \\n, \\t, ...) captured by META_PATTERN. */
    private static String unescapePy(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    default: sb.append(n); break; // \' \" \\ etc -> the literal next char
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ install / enable / delete

    /** Install from a plain file path. Returns the resulting PluginInfo, or null on failure. */
    public PluginInfo installFromFile(File src) {
        try (InputStream in = new FileInputStream(src)) {
            return installFromStream(in);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /** Install from an arbitrary input stream (e.g. a content:// Uri opened by the UI). */
    public PluginInfo installFromStream(InputStream in) {
        try {
            // Stage to a temp file so we can read the id before choosing the final name.
            File tmp = new File(pluginsDir(), "_import_" + System.currentTimeMillis() + ".tmp");
            copy(in, tmp);
            PluginInfo meta = parseMetadata(tmp);
            if (meta == null || TextUtils.isEmpty(meta.id) || !isValidId(meta.id)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            File dest = new File(pluginsDir(), meta.id + ".plugin");
            // The old instance unload AND the file write both run on the queue below, so they are
            // FIFO-ordered with any pending delete (an off-queue file write could be clobbered by a
            // deletePlugin() that was queued just before a same-id reinstall). The staged temp file
            // persists on disk until the queued runnable renames it into place.
            PluginInfo existing = findById(meta.id);
            meta.filePath = dest.getAbsolutePath();
            meta.enabled = true;
            synchronized (plugins) {
                plugins.remove(existing);
                plugins.add(meta);
            }
            persistIndex();
            final PluginInfo toUnload = existing;
            final PluginInfo toLoad = meta;
            queue.postRunnable(() -> {
                ensurePythonStarted();
                if (toUnload != null) {
                    unloadPluginInternal(toUnload); // unload OLD on the queue thread, never off it
                }
                // Commit the file on the queue so it is ordered after any pending delete.
                try {
                    if (dest.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        dest.delete();
                    }
                    if (!tmp.renameTo(dest)) {
                        copyFile(tmp, dest);
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                } finally {
                    if (tmp.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
                if (isCompatible(toLoad)) {
                    loadPluginInternal(toLoad);
                }
                refreshRequestHooks();
                notifyChanged();
            });
            return meta;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    public void setEnabled(String id, boolean enabled) {
        PluginInfo info = findById(id);
        if (info == null || info.enabled == enabled) {
            return;
        }
        info.enabled = enabled;
        persistIndex();
        queue.postRunnable(() -> {
            if (enabled) {
                ensurePythonStarted();
                if (isCompatible(info)) {
                    loadPluginInternal(info);
                }
            } else {
                unloadPluginInternal(info);
            }
            refreshRequestHooks();
            notifyChanged();
        });
    }

    public void deletePlugin(String id) {
        PluginInfo info = findById(id);
        if (info == null) {
            return;
        }
        synchronized (plugins) {
            plugins.remove(info);
        }
        persistIndex();
        prefs().edit().remove("enabled_" + id).apply();
        queue.postRunnable(() -> {
            unloadPluginInternal(info);
            try {
                File f = new File(info.filePath);
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                // Managers may leave the previous extension beside the active file.
                File pluginVariant = new File(pluginsDir(), id + ".plugin");
                File pythonVariant = new File(pluginsDir(), id + ".py");
                if (pluginVariant.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    pluginVariant.delete();
                }
                if (pythonVariant.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    pythonVariant.delete();
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            // Also wipe the plugin's persisted settings.
            try {
                appContext.getSharedPreferences("plugin_settings_" + id, Context.MODE_PRIVATE)
                        .edit().clear().apply();
            } catch (Throwable ignore) {
            }
            refreshRequestHooks();
            notifyChanged();
        });
    }

    // ------------------------------------------------------------------ load / unload (Python)

    private void loadPluginInternal(PluginInfo info) {
        if (!isPythonReady()) {
            info.error = "python runtime unavailable";
            return;
        }
        PluginContext ctx = contexts.get(info.id);
        if (ctx != null) {
            // already loaded
            return;
        }
        ctx = new PluginContext(info.id);
        try {
            loader.callAttr("instantiate", info.filePath, info.id, ctx);
            contexts.put(info.id, ctx);
            info.loaded = true;
            info.error = null;
        } catch (PyException e) {
            ctx.unhookAll(); // roll back hooks installed before on_plugin_load() threw
            info.loaded = false;
            info.error = shortError(e);
            FileLog.e("zasto plugin '" + info.id + "' failed to load", e);
        } catch (Throwable t) {
            ctx.unhookAll();
            info.loaded = false;
            info.error = shortError(t);
            FileLog.e(t);
        }
    }

    private void unloadPluginInternal(PluginInfo info) {
        if (info == null) {
            return;
        }
        try {
            if (isPythonReady()) {
                loader.callAttr("unload", info.id);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        PluginContext ctx = contexts.remove(info.id);
        if (ctx != null) {
            ctx.unhookAll();
        }
        info.loaded = false;
    }

    // ------------------------------------------------------------------ settings bridge (UI)

    /**
     * Returns a render model for a plugin's settings screen as a Java List of Maps so the
     * Telegram UI never has to touch Python objects directly. Each map: type/key/text/
     * subtext/icon/value/options/index. Returns empty list if the plugin has no settings.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSettingsModel(String id) {
        return getSettingsModel(id, null);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getSettingsModel(String id, String screenToken) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!isPythonReady()) {
            return out;
        }
        try {
            PyObject model = TextUtils.isEmpty(screenToken)
                    ? loader.callAttr("get_settings_model", id)
                    : loader.callAttr("get_settings_model_for_screen", id, screenToken);
            if (model == null) {
                return out;
            }
            Object java = model.toJava(List.class);
            if (java instanceof List) {
                for (Object row : (List<Object>) java) {
                    if (row instanceof Map) {
                        out.add((Map<String, Object>) row);
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return out;
    }

    /** Called on the UI thread when the user toggles/edits a setting row. */
    public void onSettingChange(String id, String key, Object value) {
        onSettingChange(id, key, value, null);
    }

    public void onSettingChange(String id, String key, Object value, String screenToken) {
        if (!isPythonReady()) {
            return;
        }
        try {
            loader.callAttr("on_setting_change", id, key, value, screenToken);
        } catch (Throwable t) {
            logError(id, "on_setting_change", t);
        }
    }

    /** Called on the UI thread when the user clicks a Text settings row; view anchors menus. */
    public void onSettingClick(String id, int index, Object view) {
        onSettingClick(id, index, view, null);
    }

    public void onSettingClick(String id, int index, Object view, String screenToken) {
        if (!isPythonReady()) {
            return;
        }
        try {
            loader.callAttr("on_setting_click", id, index, view, screenToken);
        } catch (Throwable t) {
            logError(id, "on_setting_click", t);
        }
    }

    /** Long press on a settings row; true if the plugin's on_long_click consumed it. */
    public boolean onSettingLongClick(String id, int index, Object view, String screenToken) {
        if (!isPythonReady()) {
            return false;
        }
        try {
            PyObject handled = loader.callAttr("on_setting_long_click", id, index, view, screenToken);
            return handled != null && Boolean.TRUE.equals(handled.toJava(Boolean.class));
        } catch (Throwable t) {
            logError(id, "on_setting_long_click", t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createSubSettings(String id, String screenToken, int index) {
        if (!isPythonReady()) {
            return null;
        }
        try {
            PyObject result = loader.callAttr("create_sub_settings", id, screenToken, index);
            Object java = result != null ? result.toJava(Map.class) : null;
            return java instanceof Map ? (Map<String, Object>) java : null;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    // ------------------------------------------------------------------ network hook bridge

    /**
     * Called from {@link PluginRequestInterceptor} on the network delegate thread for every
     * completed request. Returns the response to deliver downstream, or null to drop it
     * (HookStrategy.CANCEL). Runs synchronously — Chaquopy's GIL serializes the call.
     */
    public Object dispatchPostRequest(String requestName, int account, Object response, Object error) {
        if (!isPythonReady()) {
            return response;
        }
        try {
            PyObject out = loader.callAttr("dispatch_post_request", requestName, account, response, error);
            if (out == null) {
                return response; // Python None = no change; deliver the original (may be null on error)
            }
            Object java = out.toJava(Object.class);
            if (PluginRequestInterceptor.CANCEL_SENTINEL.equals(java)) {
                return PluginRequestInterceptor.CANCEL_SENTINEL; // explicit, non-null CANCEL marker
            }
            return java;
        } catch (Throwable t) {
            FileLog.e(t);
            return response;
        }
    }

    /** Pre-send hook: returns the (possibly modified) request, or CANCEL_SENTINEL to drop it. */
    public Object dispatchPreRequest(String requestName, int account, Object request) {
        if (!isPythonReady()) {
            return request;
        }
        try {
            PyObject out = loader.callAttr("dispatch_pre_request", requestName, account, request);
            if (out == null) {
                return request;
            }
            return out.toJava(Object.class); // the (possibly modified) request, or CANCEL_SENTINEL
        } catch (Throwable t) {
            FileLog.e(t);
            return request;
        }
    }

    /** Outgoing-message hook: returns true if a plugin cancelled the send (params mutated in place). */
    public boolean dispatchSendMessage(int account, Object params) {
        if (!isPythonReady()) {
            return false;
        }
        try {
            PyObject out = loader.callAttr("dispatch_on_send_message", account, params);
            return out != null && out.toJava(Boolean.class);
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    /** Updates hook: returns true if a plugin cancelled processing this updates container. */
    public boolean dispatchUpdates(String containerName, int account, Object updates) {
        if (!isPythonReady()) {
            return false;
        }
        try {
            PyObject out = loader.callAttr("dispatch_updates", containerName, account, updates);
            return out != null && out.toJava(Boolean.class);
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public boolean hasRequestHooks() {
        if (!isPythonReady()) {
            return false;
        }
        try {
            PyObject b = loader.callAttr("has_request_hooks");
            return b != null && b.toJava(Boolean.class);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasSendMessageHooks() {
        if (!isPythonReady()) {
            return false;
        }
        try {
            PyObject b = loader.callAttr("has_send_message_hooks");
            return b != null && b.toJava(Boolean.class);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasUpdateHooks() {
        if (!isPythonReady()) {
            return false;
        }
        try {
            PyObject b = loader.callAttr("has_update_hooks");
            return b != null && b.toJava(Boolean.class);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasMenuItems(String menuType) {
        if (!isPythonReady()) {
            return false;
        }
        try {
            PyObject b = loader.callAttr("has_menu_items", menuType);
            return b != null && b.toJava(Boolean.class);
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshRequestHooks() {
        try {
            PluginRequestInterceptor.setActive(hasRequestHooks());
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            PluginSendMessageInterceptor.setActive(hasSendMessageHooks());
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            PluginUpdatesInterceptor.setActive(hasUpdateHooks());
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            PluginMessageMenuInterceptor.setActive(hasMenuItems("message_context_menu"));
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void registerAppLifecycle() {
        try {
            if (appContext instanceof android.app.Application) {
                ((android.app.Application) appContext).registerActivityLifecycleCallbacks(
                        new android.app.Application.ActivityLifecycleCallbacks() {
                            @Override public void onActivityResumed(android.app.Activity a) { dispatchAppEvent("resume"); }
                            @Override public void onActivityPaused(android.app.Activity a) { dispatchAppEvent("pause"); }
                            @Override public void onActivityStarted(android.app.Activity a) { dispatchAppEvent("start"); }
                            @Override public void onActivityStopped(android.app.Activity a) { dispatchAppEvent("stop"); }
                            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { }
                            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
                            @Override public void onActivityDestroyed(android.app.Activity a) { }
                        });
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Deliver an AppEvent (start/stop/pause/resume) to plugins overriding on_app_event. */
    public void dispatchAppEvent(String event) {
        if (!isPythonReady()) {
            return;
        }
        try {
            loader.callAttr("dispatch_app_event", event);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // ------------------------------------------------------------------ menu items

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getMenuItems(String menuType) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!isPythonReady()) {
            return out;
        }
        try {
            PyObject model = loader.callAttr("get_menu_items", menuType);
            if (model != null) {
                Object java = model.toJava(List.class);
                if (java instanceof List) {
                    for (Object row : (List<Object>) java) {
                        if (row instanceof Map) {
                            out.add((Map<String, Object>) row);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return out;
    }

    /** Run a menu item's on_click(context). context may be null or a Java Map of context values. */
    public void onMenuItemClick(String pluginId, String itemId, Object context) {
        if (!isPythonReady()) {
            return;
        }
        try {
            loader.callAttr("invoke_menu_item", pluginId, itemId, context);
        } catch (Throwable t) {
            logError(pluginId, "invoke_menu_item", t);
        }
    }

    /** Remove a plugin's menu item by id; false if the plugin or item is unknown. */
    public boolean removeMenuItem(String pluginId, String itemId) {
        if (!isPythonReady()) {
            return false;
        }
        try {
            PyObject removed = loader.callAttr("remove_menu_item", pluginId, itemId);
            return removed != null && removed.toJava(Boolean.class);
        } catch (Throwable t) {
            logError(pluginId, "remove_menu_item", t);
            return false;
        }
    }

    /** Append plugin DRAWER_MENU items into the drawer's ItemOptions popup. */
    public void addDrawerMenuItems(org.telegram.ui.Components.ItemOptions io) {
        if (io == null || !isPythonReady()) {
            return;
        }
        try {
            for (Map<String, Object> m : getMenuItems("drawer_menu")) {
                final String pluginId = String.valueOf(m.get("plugin_id"));
                final String itemId = String.valueOf(m.get("item_id"));
                final String text = String.valueOf(m.get("text"));
                final int icon = resolveDrawable(m.get("icon"));
                io.add(icon, text, () -> onMenuItemClick(pluginId, itemId, null));
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private int resolveDrawable(Object name) {
        if (!(name instanceof String) || appContext == null) {
            return 0;
        }
        try {
            return appContext.getResources().getIdentifier((String) name, "drawable", appContext.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ helpers

    public List<PluginInfo> getPlugins() {
        synchronized (plugins) {
            return new ArrayList<>(plugins);
        }
    }

    public PluginInfo findById(String id) {
        synchronized (plugins) {
            for (PluginInfo info : plugins) {
                if (info.id.equals(id)) {
                    return info;
                }
            }
        }
        return null;
    }

    /** Whitelist plugin ids so an untrusted __id__ cannot escape files/plugins via path separators. */
    private static boolean isValidId(String id) {
        return id != null && id.matches("^[A-Za-z0-9._-]{1,64}$") && !id.equals(".") && !id.equals("..");
    }

    public boolean isCompatible(PluginInfo info) {
        if (info == null) {
            return false;
        }
        if (TextUtils.isEmpty(info.minVersion)) {
            return true;
        }
        boolean ok = compareVersions(BuildConfig.BUILD_VERSION_STRING, info.minVersion) >= 0;
        if (!ok && info.error == null) {
            info.error = "requires " + info.minVersion + "+";
        }
        return ok;
    }

    /** Numeric dotted-version compare. Returns >0 if a>b, 0 if equal, <0 if a<b. */
    public static int compareVersions(String a, String b) {
        if (a == null) a = "0";
        if (b == null) b = "0";
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? safeInt(pa[i]) : 0;
            int vb = i < pb.length ? safeInt(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int safeInt(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (c >= '0' && c <= '9') {
                    sb.append(c);
                } else if (sb.length() == 0) {
                    continue; // skip a leading operator/sign (">=", "v", whitespace) before digits
                } else {
                    break; // stop at the first trailing non-digit (e.g. "13rc")
                }
            }
            return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String shortError(Throwable t) {
        String msg = t.getMessage();
        if (TextUtils.isEmpty(msg)) {
            msg = t.getClass().getSimpleName();
        }
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "…";
        }
        return msg;
    }

    private void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pluginsDidLoad);
            } catch (Throwable ignore) {
                // pluginsDidLoad may not exist yet during early init; UI also refreshes onResume.
            }
        });
    }

    // ------------------------------------------------------------------ logging (called from Python)

    public static void log(String pluginId, String msg) {
        FileLog.d("[plugin:" + pluginId + "] " + msg);
    }

    public static void logError(String pluginId, String where, Throwable t) {
        FileLog.e("[plugin:" + pluginId + "] " + where, t);
    }

    // ------------------------------------------------------------------ io

    private static void copy(InputStream in, File dest) throws Exception {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void copyFile(File src, File dest) throws Exception {
        try (InputStream in = new FileInputStream(src)) {
            copy(in, dest);
        }
    }

    private static boolean isMainProcess(Context context) {
        try {
            int pid = android.os.Process.myPid();
            android.app.ActivityManager am =
                    (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                        if (p.pid == pid) {
                            return p.processName != null && !p.processName.contains(":");
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return true; // assume main when undetermined
    }
}
