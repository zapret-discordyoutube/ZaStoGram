package com.exteragram.messenger.plugins;

import org.telegram.plugins.PluginInfo;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.LinkedHashMap;

/**
 * Compatibility bridge: exposes exteraGram's {@code com.exteragram.messenger.plugins.PluginsController}
 * (notably {@code getInstance().plugins}) over ZaStoGram's own engine. {@link #plugins} is refreshed
 * from the live engine on every getInstance()/getPlugins() call so it always reflects current state.
 */
public class PluginsController {

    private static final PluginsController INSTANCE = new PluginsController();

    /** id -> Plugin, mirroring exteraGram's public field. Kept fresh by refresh(). */
    public final LinkedHashMap<String, Plugin> plugins = new LinkedHashMap<>();
    /** Engine registry used by managers which write plugin files directly. */
    public final LinkedHashMap<String, PythonPluginsEngine> engines = new LinkedHashMap<>();

    private PluginsController() {
        engines.put("python", new PythonPluginsEngine());
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
