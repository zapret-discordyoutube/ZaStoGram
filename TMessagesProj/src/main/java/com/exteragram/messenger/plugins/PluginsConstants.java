package com.exteragram.messenger.plugins;

/**
 * exteraGram's plugin constants. Event values equal the strings ZaStoGram passes to
 * BasePlugin.on_app_event (base_plugin.AppEvent), so comparisons with either spelling work.
 */
public final class PluginsConstants {
    public static final String PYTHON = "python";

    public static final String ON_APP_EVENT = "on_app_event";
    public static final String ON_SEND_MESSAGE = "on_send_message_hook";
    public static final String PRE_REQUEST = "pre_request_hook";
    public static final String POST_REQUEST = "post_request_hook";
    public static final String ON_UPDATE = "on_update_hook";
    public static final String ON_UPDATES = "on_updates_hook";

    public static final String APP_START = "start";
    public static final String APP_STOP = "stop";
    public static final String APP_PAUSE = "pause";
    public static final String APP_RESUME = "resume";

    public static final String PLUGIN_EXTENSION = ".plugin";

    private PluginsConstants() {
    }
}
