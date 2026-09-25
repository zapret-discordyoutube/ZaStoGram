package com.exteragram.messenger.plugins.models;

import android.content.Context;
import android.view.View;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.Plugin;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * exteraGram's {@code Custom(...)} settings row. The row is backed by a prebuilt {@link UItem},
 * a plain View, or a {@link Factory}; the plugin settings screen asks the factory for a UItem
 * and routes clicks back to it.
 */
public class CustomSetting {

    public UItem item;
    public Factory<?> factory;
    public PyObject factoryArgs;
    public String linkAlias;

    public CustomSetting(UItem item, Factory<?> factory, PyObject factoryArgs, String linkAlias) {
        this.item = item;
        this.factory = factory;
        this.factoryArgs = factoryArgs;
        this.linkAlias = linkAlias;
    }

    public UItem getItem() {
        return item;
    }

    public Factory<?> getFactory() {
        return factory;
    }

    public PyObject getFactoryArgs() {
        return factoryArgs;
    }

    /** A UItem factory whose rows live on a plugin settings screen. */
    public abstract static class Factory<V extends View> extends UItem.UItemFactory<V> {

        public boolean isShadowValue;
        public boolean isClickableValue = true;

        public UItem create(Plugin plugin, CustomSetting setting, PyObject args) {
            return null;
        }

        @Override
        public boolean isClickable() {
            return isClickableValue;
        }

        @Override
        public boolean isShadow() {
            return isShadowValue;
        }

        public void onClick(Plugin plugin, UItem item, View view) {
        }

        public void onLongClick(Plugin plugin, UItem item, View view) {
        }

        /** Host hook for long presses: true if the factory consumed it. */
        public boolean performLongClick(Plugin plugin, UItem item, View view) {
            onLongClick(plugin, item, view);
            return false;
        }
    }

    /**
     * The Java side of ui.settings.SimpleSettingFactory: every callback goes to a Python bridge
     * object, which returns None for callbacks the plugin did not supply. Each instance gets its
     * own view type, so one Java class serves any number of plugin-defined row kinds.
     */
    public static class PythonFactory extends Factory<View> {

        private final PyObject bridge;

        public PythonFactory(PyObject bridge, boolean clickable, boolean shadow) {
            this.bridge = bridge;
            this.isClickableValue = clickable;
            this.isShadowValue = shadow;
            UItem.UItemFactory.setupInstance(this);
        }

        private PyObject call(String name, Object... args) {
            try {
                return bridge.callAttr(name, args);
            } catch (Throwable t) {
                FileLog.e("[plugin] SimpleSettingFactory." + name, t);
                return null;
            }
        }

        @Override
        public View createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            PyObject view = call("create_view", context, listView, currentAccount, classGuid, resourcesProvider);
            View result = view != null ? view.toJava(View.class) : null;
            return result != null ? result : new View(context);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            call("bind_view", view, item, divider, adapter, listView);
        }

        @Override
        public void attachedView(RecyclerListView listView, View view, UItem item) {
            call("attached_view", listView, view, item);
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            PyObject result = call("equals", a, b);
            return result != null ? result.toJava(Boolean.class) : super.equals(a, b);
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            PyObject result = call("content_equals", a, b);
            return result != null ? result.toJava(Boolean.class) : super.contentsEquals(a, b);
        }

        @Override
        public UItem create(Plugin plugin, CustomSetting setting, PyObject args) {
            PyObject created = call("create_item", plugin, setting, args);
            UItem item = created != null ? created.toJava(UItem.class) : null;
            if (item == null) {
                item = new UItem(viewType, false);
                item.object = args;
            }
            return item;
        }

        @Override
        public void onClick(Plugin plugin, UItem item, View view) {
            call("on_click", plugin, item, view);
        }

        @Override
        public boolean performLongClick(Plugin plugin, UItem item, View view) {
            PyObject result = call("on_long_click", plugin, item, view);
            return result != null && Boolean.TRUE.equals(result.toJava(Boolean.class));
        }
    }
}
