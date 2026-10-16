package com.exteragram.messenger.utils.chats;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ItemOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * exteraGram's configurable main-menu helper. ZaStoGram has no configurable main menu: the
 * helpers resolve to nothing, so DEX plugins that extend it (re:extera) find empty lists and
 * fall back to their own drawer handling.
 */
public abstract class MainMenuHelper {

    public static final class MenuContext {
        private final int currentAccount;
        private final BaseFragment fragment;
        private final Runnable archiveClick;
        private final Map<String, Object> pluginContextData;

        public MenuContext(int currentAccount, BaseFragment fragment, Runnable archiveClick, Map<String, Object> pluginContextData) {
            this.currentAccount = currentAccount;
            this.fragment = fragment;
            this.archiveClick = archiveClick;
            this.pluginContextData = pluginContextData;
        }

        public int currentAccount() {
            return currentAccount;
        }

        public BaseFragment fragment() {
            return fragment;
        }

        public Runnable archiveClick() {
            return archiveClick;
        }

        public Map<String, Object> pluginContextData() {
            return pluginContextData;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MenuContext)) return false;
            MenuContext other = (MenuContext) o;
            return currentAccount == other.currentAccount && fragment == other.fragment;
        }

        @Override
        public int hashCode() {
            return Objects.hash(currentAccount, fragment);
        }
    }

    public static final class MenuItemInfo {
        private final int iconRes;
        private final CharSequence text;
        private final Runnable onClick;
        private final Runnable onLongClick;

        public MenuItemInfo(int iconRes, CharSequence text, Runnable onClick, Runnable onLongClick) {
            this.iconRes = iconRes;
            this.text = text;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
        }

        public int iconRes() {
            return iconRes;
        }

        public CharSequence text() {
            return text;
        }

        public Runnable onClick() {
            return onClick;
        }

        public Runnable onLongClick() {
            return onLongClick;
        }
    }

    public static MenuContext createMenuContext(int currentAccount, BaseFragment fragment) {
        return new MenuContext(currentAccount, fragment, null, createPluginContextData(currentAccount, fragment));
    }

    public static MenuContext createMenuContext(int currentAccount, BaseFragment fragment, Runnable archiveClick, Map<String, Object> data) {
        return new MenuContext(currentAccount, fragment, archiveClick, data);
    }

    public static Map<String, Object> createPluginContextData(int currentAccount, BaseFragment fragment) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("account", currentAccount);
        data.put("fragment", fragment);
        return data;
    }

    public static void addConfiguredItemOptions(ItemOptions options, MenuContext context) {
    }

    public static void addConfiguredItemOptions(ItemOptions options, MenuContext context, IntPredicate filter) {
    }

    public static List<MenuItemInfo> resolveDrawerMenuItems(int id, MenuContext context) {
        return new ArrayList<>();
    }

    public static MenuItemInfo resolveMenuItem(int id, MenuContext context) {
        return null;
    }
}
