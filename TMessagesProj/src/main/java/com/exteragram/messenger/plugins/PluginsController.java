package com.exteragram.messenger.plugins;

import org.telegram.messenger.MessageObject;
import org.telegram.plugins.PluginInfo;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compatibility bridge: exposes exteraGram's {@code com.exteragram.messenger.plugins.PluginsController}
 * (notably {@code getInstance().plugins}) over ZaStoGram's own engine. {@link #plugins} is refreshed
 * from the live engine on every getInstance()/getPlugins() call so it always reflects current state.
 */
public class PluginsController {

    private static final PluginsController INSTANCE = new PluginsController();

    /** id -> Plugin, mirroring exteraGram's public field. Kept fresh by refresh(). */
    public final LinkedHashMap<String, Plugin> plugins = new LinkedHashMap<>();
    /** Engine registry (exteraGram keeps it static: PluginsController.engines.get(PluginsConstants.PYTHON)). */
    public static final LinkedHashMap<String, PythonPluginsEngine> engines = new LinkedHashMap<>();

    static {
        engines.put(PluginsConstants.PYTHON, new PythonPluginsEngine());
    }

    /** Directory of installed plugin files (exteraGram exposes it as a File field). */
    public final File pluginsDir;

    private PluginsController() {
        File dir = null;
        try {
            dir = org.telegram.plugins.PluginsController.getInstance().pluginsDir();
        } catch (Throwable ignore) {
        }
        pluginsDir = dir;
    }

    public static Map<String, PythonPluginsEngine> getEngines() {
        return engines;
    }

    public PythonPluginsEngine getPluginEngine(String id) {
        return engines.get(PluginsConstants.PYTHON);
    }

    private static org.telegram.plugins.PluginsController host() {
        return org.telegram.plugins.PluginsController.getInstance();
    }

    public File getPluginsDir() {
        return pluginsDir;
    }

    public boolean isInitialized() {
        return true;
    }

    public android.content.SharedPreferences getPreferences() {
        return org.telegram.messenger.ApplicationLoader.applicationContext
                .getSharedPreferences("zasto_plugins", android.content.Context.MODE_PRIVATE);
    }

    public boolean hasPluginSettings(String id) {
        return !new org.telegram.plugins.PluginContext(id).getAllSettings().isEmpty() || host().getPluginInstance(id) != null;
    }

    public java.util.Map<String, ?> getPluginSettingsPreferences(String id) {
        return new org.telegram.plugins.PluginContext(id).getAllSettings();
    }

    public String getPluginSettingString(String id, String key, String def) {
        Object value = new org.telegram.plugins.PluginContext(id).getSetting(key, def);
        return value != null ? String.valueOf(value) : null;
    }

    public boolean getPluginSettingBoolean(String id, String key, boolean def) {
        Object value = new org.telegram.plugins.PluginContext(id).getSetting(key, def);
        return value instanceof Boolean ? (Boolean) value : value != null ? Boolean.parseBoolean(String.valueOf(value)) : def;
    }

    public int getPluginSettingInt(String id, String key, int def) {
        Object value = new org.telegram.plugins.PluginContext(id).getSetting(key, def);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value != null ? Integer.parseInt(String.valueOf(value)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String getPluginPath(String id) {
        PluginInfo info = host().findById(id);
        return info != null ? info.filePath : null;
    }

    /** Re-render an open settings screen of this plugin after its settings changed. */
    public void loadPluginSettings(String id) {
        new org.telegram.plugins.PluginContext(id).reloadSettings();
    }

    public static boolean isPlugin(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        try {
            String name = messageObject.getDocumentName();
            return name != null && (name.toLowerCase().endsWith(".plugin") || name.toLowerCase().endsWith(".py"));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean validatePluginFromFile(String path) {
        try {
            PluginInfo info = org.telegram.plugins.PluginsController.parseMetadata(new File(path));
            return info != null && info.id != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public void loadPluginFromFile(String path, Object callback) {
        host().installFromFile(new File(path));
    }

    public void loadPluginFromFile(String path) {
        host().installFromFile(new File(path));
    }

    public void deletePlugin(String id) {
        host().deletePlugin(id);
    }

    public void deletePlugin(String id, Object ignored) {
        host().deletePlugin(id);
    }

    public void unloadPlugin(String id) {
        host().setEnabled(id, false);
    }

    public void loadPlugin(String id) {
        host().setEnabled(id, true);
    }

    public boolean isPluginEnabled(String id) {
        PluginInfo info = host().findById(id);
        return info != null && info.enabled;
    }

    public boolean isPluginPinned(String id) {
        return false;
    }

    public void runOnPluginsQueue(Runnable runnable) {
        org.telegram.plugins.PluginUtils.runOnQueue("pluginsQueue", runnable, 0);
    }

    public void init() {
        host().reloadPluginsFromDisk();
    }

    public void restart() {
        host().reloadPluginsFromDisk();
    }

    public void shutdown() {
        // ZaStoGram keeps one engine for the process; restart() reloads plugins from disk.
    }

    public void removeMenuItemsByPluginId(String pluginId) {
        // Menu items live on the plugin instance; disabling/reloading the plugin drops them.
    }

    public static PluginsController getInstance() {
        INSTANCE.refresh();
        return INSTANCE;
    }

    private synchronized void refresh() {
        plugins.clear();
        try {
            for (PluginInfo info : org.telegram.plugins.PluginsController.getInstance().getPlugins()) {
                if (info != null && info.id != null) {
                    plugins.put(info.id, new Plugin(info));
                }
            }
        } catch (Throwable ignore) {
        }
    }

    public LinkedHashMap<String, Plugin> getPlugins() {
        refresh();
        return plugins;
    }

    public Plugin getPlugin(String id) {
        refresh();
        return plugins.get(id);
    }

    public void setPluginEnabled(String id, boolean enabled) {
        setPluginEnabled(id, enabled, null);
    }

    public void setPluginEnabled(String id, boolean enabled, Object ignored) {
        org.telegram.plugins.PluginsController.getInstance().setEnabled(id, enabled);
        refresh();
    }

    public boolean removeMenuItem(String pluginId, String itemId) {
        return org.telegram.plugins.PluginsController.getInstance().removeMenuItem(pluginId, itemId);
    }

    public static void openPluginSettings(String id) {
        openPluginSettings(id, org.telegram.plugins.PluginUtils.getLastFragment());
    }

    public static void openPluginSettings(String id, BaseFragment from) {
        if (id == null || id.length() == 0) {
            return;
        }
        BaseFragment fragment = from != null ? from : org.telegram.plugins.PluginUtils.getLastFragment();
        if (fragment != null) {
            fragment.presentFragment(new com.exteragram.messenger.plugins.ui.PluginSettingsActivity(id));
        }
    }

    public static void openPluginSettings(Plugin plugin) {
        openPluginSettings(plugin, org.telegram.plugins.PluginUtils.getLastFragment());
    }

    public static void openPluginSettings(Plugin plugin, BaseFragment from) {
        if (plugin != null) {
            openPluginSettings(plugin.getId(), from);
        }
    }
}
