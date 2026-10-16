package com.exteragram.messenger.plugins;

import org.telegram.plugins.PluginContext;
import org.telegram.plugins.PluginInfo;

import java.io.File;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * exteraGram's Python engine facade over ZaStoGram's own engine. Library plugins (ZwyLib,
 * QuantaHut, plugin managers) reach plugin instances, paths and settings through it.
 */
public class PythonPluginsEngine {

    private static org.telegram.plugins.PluginsController host() {
        return org.telegram.plugins.PluginsController.getInstance();
    }

    /** id -> live Python plugin instance. Looked up on every access, never stale. */
    public final Map<String, Object> pluginInstances = new AbstractMap<String, Object>() {
        @Override
        public Object get(Object key) {
            return key == null ? null : host().getPluginInstance(String.valueOf(key));
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
            for (String id : host().getLoadedPluginIds()) {
                Object inst = host().getPluginInstance(id);
                if (inst != null) {
                    snapshot.put(id, inst);
                }
            }
            return snapshot.entrySet();
        }
    };

    public Map<String, Object> getPluginInstances() {
        return pluginInstances;
    }

    public boolean isEngineAvailable() {
        return true;
    }

    public void loadPlugins(Object ignored) {
        host().reloadPluginsFromDisk();
    }

    public void loadPlugins() {
        host().reloadPluginsFromDisk();
    }

    public String getPluginPath(String id) {
        PluginInfo info = host().findById(id);
        return info != null ? info.filePath : null;
    }

    public Object getPluginInstance(String id) {
        return host().getPluginInstance(id);
    }

    public boolean isPluginLoaded(String id) {
        return host().getPluginInstance(id) != null;
    }

    public Map<String, ?> getAllPluginSettings(String id) {
        try {
            return new PluginContext(id).getAllSettings();
        } catch (Throwable t) {
            return new HashMap<>();
        }
    }

    public Object getPluginSetting(String id, String key, Object def) {
        return new PluginContext(id).getSetting(key, def);
    }

    public void setPluginSetting(String id, String key, Object value) {
        new PluginContext(id).setSetting(key, value);
    }

    public void loadPluginSettings(String id) {
        new PluginContext(id).reloadSettings();
    }

    public void openPluginSettings(String id) {
        PluginsController.openPluginSettings(id);
    }

    public void openPluginSetting(String id) {
        PluginsController.openPluginSettings(id);
    }

    /** Install (or update) a plugin file; returns the installed id or null. */
    public String loadPluginFromFile(String path) {
        PluginInfo info = host().installFromFile(new File(path));
        return info != null ? info.id : null;
    }

    public String loadPluginFromFile(String path, Object ignored) {
        return loadPluginFromFile(path);
    }

    public void unloadPlugin(String id) {
        host().setEnabled(id, false);
    }

    public void deletePlugin(String id) {
        host().deletePlugin(id);
    }

    public void deletePlugin(String id, Object ignored) {
        host().deletePlugin(id);
    }
}
