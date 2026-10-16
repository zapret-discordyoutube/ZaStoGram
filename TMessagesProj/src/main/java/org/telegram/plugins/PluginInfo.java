package org.telegram.plugins;

/**
 * Parsed metadata + runtime state of a single .plugin file.
 *
 * Metadata is read from the module-level dunder assignments (__id__, __name__, ...)
 * without executing the plugin body, so disabled/broken plugins can still be listed.
 */
public class PluginInfo {
    public String id;
    public String name;
    public String description;
    public String author;
    public String version;
    public String minVersion;
    /** Raw __app_version__ expression such as ">=12.5.1" or ">=12.0, <13" (null if absent). */
    public String appVersionSpec;
    /** Raw __sdk_version__ expression such as ">=1.4.5.0" (null if absent). */
    public String sdkVersionSpec;
    public String icon;

    /** Absolute path of the stored .plugin file inside files/plugins/. */
    public String filePath;

    /** User toggle (persisted). A loaded+enabled plugin has its hooks installed. */
    public boolean enabled;

    /** True once on_plugin_load() ran without throwing for the current session. */
    public boolean loaded;

    /** Last load/runtime error shown in the UI, or null. */
    public String error;

    public PluginInfo() {
    }

    public String displayName() {
        if (name != null && name.length() > 0) {
            return name;
        }
        return id != null ? id : "plugin";
    }

    public String displayVersion() {
        return version != null ? version : "";
    }
}
