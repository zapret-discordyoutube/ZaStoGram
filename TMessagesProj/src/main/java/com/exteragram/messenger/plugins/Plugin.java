package com.exteragram.messenger.plugins;

import org.telegram.plugins.PluginInfo;

/**
 * Compatibility bridge: exposes exteraGram's {@code com.exteragram.messenger.plugins.Plugin} API
 * over ZaStoGram's own {@link org.telegram.plugins.PluginInfo}, so community library-plugins
 * (e.g. QuantaHut) that import this class resolve and run.
 */
public class Plugin {

    private final PluginInfo info;
    private String id;
    private String name;
    private String version = "1.0";
    private String author;
    private String description;
    private String icon;
    private String engine;

    public Plugin(PluginInfo info) {
        this.info = info;
    }

    /** Synthetic store entry used by plugin managers before a plugin is installed. */
    public Plugin(String id, String name) {
        this.info = null;
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return info != null ? info.id : id;
    }

    public String getName() {
        return info != null ? info.displayName() : (name != null ? name : id);
    }

    public String getVersion() {
        return info != null ? (info.version != null ? info.version : "1.0") : version;
    }

    public String getAuthor() {
        return info != null ? info.author : author;
    }

    public String getDescription() {
        return info != null ? info.description : description;
    }

    public boolean isEnabled() {
        return info != null && info.enabled;
    }

    /** __icon__ is "StickerPackShortName/index"; this returns the pack short name (or null). */
    public String getPack() {
        String icon = info != null ? info.icon : this.icon;
        if (icon == null || icon.length() == 0) {
            return null;
        }
        int slash = icon.indexOf('/');
        return slash > 0 ? icon.substring(0, slash) : icon;
    }

    /** The index part of __icon__ ("pack/index"), or -1 if absent/unparseable. */
    public int getIndex() {
        String icon = info != null ? info.icon : this.icon;
        if (icon == null) {
            return -1;
        }
        int slash = icon.indexOf('/');
        if (slash < 0 || slash + 1 >= icon.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(icon.substring(slash + 1).trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    public String getIcon() {
        return info != null ? info.icon : icon;
    }

    public String getAppVersion() {
        return info != null ? info.appVersionSpec : null;
    }

    public String getSdkVersion() {
        return info != null ? info.sdkVersionSpec : null;
    }

    public java.util.List<String> getRequirements() {
        return new java.util.ArrayList<>();
    }

    public boolean hasError() {
        return info != null && info.error != null;
    }

    /** The last load/runtime error as a Throwable (exteraGram API), or null. */
    public Throwable getError() {
        return hasError() ? new RuntimeException(info.error) : null;
    }

    public void setError(Throwable error) {
        if (info != null) {
            info.error = error == null ? null : String.valueOf(error.getMessage());
        }
    }

    public boolean isNotResponding() {
        return false;
    }

    public void setEnabled(boolean enabled) {
        String pluginId = getId();
        if (pluginId != null) {
            org.telegram.plugins.PluginsController.getInstance().setEnabled(pluginId, enabled);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Plugin && java.util.Objects.equals(((Plugin) o).getId(), getId());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(getId());
    }

    public PluginInfo getInfo() {
        return info;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
