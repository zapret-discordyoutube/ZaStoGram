package com.exteragram.messenger.plugins;

/**
 * exteraGram's plugin constants (values match exteraGram 12.10). App events are delivered to
 * BasePlugin.on_app_event as these strings, equal to base_plugin.AppEvent.
 */
public final class PluginsConstants {
    public static final String APP_PAUSE = "app_pause";
    public static final String APP_RESUME = "app_resume";
    public static final String APP_START = "app_start";
    public static final String APP_STOP = "app_stop";
    public static final String CREATE_SETTINGS = "create_settings";
    public static final String ERROR = "error";
    public static final String ON_APP_EVENT = "on_app_event";
    public static final String ON_PLUGIN_LOAD = "on_plugin_load";
    public static final String ON_PLUGIN_UNLOAD = "on_plugin_unload";
    public static final String PARAMS = "params";
    public static final String PLUGINS = "plugins";
    public static final String PLUGINS_EXT = ".plugin";
    public static final String PLUGINS_SDK = "plugins-sdk";
    public static final String PYTHON = "python";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String SEND_MESSAGE_HOOK = "send_message_hook";
    public static final String STRATEGY = "strategy";
    public static final String UPDATE = "update";
    public static final String UPDATES = "updates";

    public static final class MenuItemTypes {
        public static final String CHAT_ACTION_MENU = "chat_action_menu";
        public static final String DRAWER_MENU = "drawer_menu";
        public static final String MAIN_MENU = "main_menu";
        public static final String MESSAGE_CONTEXT_MENU = "message_context_menu";
        public static final String PROFILE_ACTION_MENU = "profile_action_menu";

        private MenuItemTypes() {
        }
    }

    public static final class MenuItemProperties {
        public static final String CONDITION = "condition";
        public static final String ICON = "icon";
        public static final String ITEM_ID = "item_id";
        public static final String MENU_TYPE = "menu_type";
        public static final String ON_CLICK = "on_click";
        public static final String PRIORITY = "priority";
        public static final String SUBTEXT = "subtext";
        public static final String TEXT = "text";

        private MenuItemProperties() {
        }
    }

    private PluginsConstants() {
    }
}
