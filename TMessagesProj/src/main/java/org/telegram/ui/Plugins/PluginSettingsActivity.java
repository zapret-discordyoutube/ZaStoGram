package org.telegram.ui.Plugins;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.models.CustomSetting;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.utils.text.LocaleUtils;
import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.plugins.PluginInfo;
import org.telegram.plugins.PluginsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One plugin's settings screen, built like exteraGram's: rows from create_settings() become
 * UItems in a UniversalRecyclerView. The root screen adds the enable toggle, metadata and the
 * delete action; nested screens (Text/Custom create_sub_fragment) show only their rows.
 *
 * Rows arrive from Python as flat maps (see _plugin_loader.get_settings_model); every callback
 * is routed back by the row's index on its screen, so this class never holds Python callables.
 */
public class PluginSettingsActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ENABLED = 1;
    private static final int ID_DELETE = 2;
    private static final int ID_ROW_BASE = 1000;

    private final String pluginId;
    private final String screenTitle;
    private final String screenToken;

    private List<Map<String, Object>> model = new ArrayList<>();
    /** Row model behind each UItem of the current fill; custom UItems keep their own ids. */
    private final IdentityHashMap<UItem, Map<String, Object>> rowsByItem = new IdentityHashMap<>();
    /** Inline EditText rows survive refills so typing keeps focus and cursor. */
    private final SparseArray<FrameLayout> editTextViews = new SparseArray<>();

    public PluginSettingsActivity(String pluginId) {
        this(pluginId, null, null);
    }

    public PluginSettingsActivity(String pluginId, String screenTitle, String screenToken) {
        this.pluginId = pluginId;
        this.screenTitle = screenTitle;
        this.screenToken = screenToken;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsDidLoad);
        reloadModel();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsDidLoad);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginsDidLoad) {
            reloadModel();
            if (listView != null) {
                listView.adapter.update(true);
            }
        }
    }

    private PluginInfo info() {
        return PluginsController.getInstance().findById(pluginId);
    }

    private boolean isRoot() {
        return screenToken == null;
    }

    private void reloadModel() {
        PluginInfo info = info();
        model = info != null && info.enabled
                ? PluginsController.getInstance().getSettingsModel(pluginId, screenToken)
                : new ArrayList<>();
        editTextViews.clear();
    }

    @Override
    public String getTitle() {
        if (screenTitle != null) {
            return screenTitle;
        }
        PluginInfo info = info();
        return info != null ? info.displayName() : "Плагин";
    }

    // ------------------------------------------------------------------ rows

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        rowsByItem.clear();
        PluginInfo info = info();
        if (isRoot()) {
            items.add(UItem.asCheck(ID_ENABLED, "Включён").setChecked(info != null && info.enabled));
            items.add(UItem.asShadow(metaText(info)));
        }
        for (int i = 0; i < model.size(); i++) {
            Map<String, Object> row = model.get(i);
            UItem item;
            try {
                item = buildItem(row, i);
            } catch (Throwable t) {
                FileLog.e(t);
                item = null;
            }
            if (item != null) {
                rowsByItem.put(item, row);
                items.add(item);
            }
        }
        if (isRoot()) {
            if (!model.isEmpty() && !"divider".equals(str(model.get(model.size() - 1).get("type")))) {
                items.add(UItem.asShadow(null));
            }
            items.add(UItem.asButton(ID_DELETE, R.drawable.msg_delete, "Удалить плагин").red());
            items.add(UItem.asShadow("Удаление убирает плагин и стирает его сохранённые настройки."));
        }
    }

    private UItem buildItem(Map<String, Object> row, int index) {
        int id = ID_ROW_BASE + index;
        int icon = resolveIcon(row.get("icon"));
        switch (str(row.get("type"))) {
            case "header":
                return UItem.asHeader(str(row.get("text")));
            case "divider": {
                String text = str(row.get("text"));
                return UItem.asShadow(text.isEmpty() ? null : LocaleUtils.fullyFormatText(text, this, null));
            }
            case "switch": {
                String subtext = str(row.get("subtext"));
                UItem item = subtext.isEmpty()
                        ? UItem.asCheck(id, str(row.get("text")))
                        : UItem.asCheck(id, str(row.get("text")), subtext, true);
                item.setChecked(asBool(row.get("value")));
                item.iconResId = icon;
                return item;
            }
            case "selector": {
                List<?> options = row.get("options") instanceof List ? (List<?>) row.get("options") : new ArrayList<>();
                int value = asInt(row.get("value"), 0);
                String shown = value >= 0 && value < options.size() ? String.valueOf(options.get(value)) : "";
                UItem item = UItem.asButton(id, icon, str(row.get("text")), shown);
                item.subtext = emptyToNull(str(row.get("subtext")));
                return item;
            }
            case "input": {
                UItem item = UItem.asButton(id, icon, str(row.get("text")), str(row.get("value")));
                item.subtext = emptyToNull(str(row.get("subtext")));
                return item;
            }
            case "edittext": {
                UItem item = UItem.asCustom(id, editTextView(row, index));
                item.intValue = LayoutHelper.WRAP_CONTENT;
                return item;
            }
            case "custom":
                return buildCustomItem(row, index, id);
            default: { // "text"
                UItem item = UItem.asButton(id, icon, str(row.get("text")));
                item.subtext = emptyToNull(str(row.get("subtext")));
                item.accent = asBool(row.get("accent"));
                item.red = asBool(row.get("red"));
                return item;
            }
        }
    }

    private UItem buildCustomItem(Map<String, Object> row, int index, int id) {
        Object item = row.get("item");
        if (item instanceof UItem) {
            return (UItem) item;
        }
        Object factory = row.get("factory");
        if (factory instanceof CustomSetting.Factory) {
            CustomSetting.Factory<?> f = (CustomSetting.Factory<?>) factory;
            Object args = row.get("factory_args");
            CustomSetting setting = new CustomSetting(null, f,
                    args instanceof com.chaquo.python.PyObject ? (com.chaquo.python.PyObject) args : null,
                    emptyToNull(str(row.get("link_alias"))));
            UItem created = f.create(plugin(), setting, setting.factoryArgs);
            if (created != null && created.id == 0) {
                created.id = id;
            }
            return created;
        }
        Object view = row.get("view");
        if (view instanceof View) {
            UItem custom = UItem.asCustom(id, (View) view);
            custom.intValue = LayoutHelper.WRAP_CONTENT;
            return custom;
        }
        return null;
    }

    private View editTextView(Map<String, Object> row, int index) {
        FrameLayout cached = editTextViews.get(index);
        if (cached != null) {
            return cached;
        }
        Context context = getContext();
        String key = str(row.get("key"));
        boolean multiline = asBool(row.get("multiline"));
        int maxLength = asInt(row.get("max_length"), 0);
        String mask = emptyToNull(str(row.get("mask")));

        EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(16);
        edit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        edit.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        edit.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        edit.setBackground(null);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setHint(str(row.get("hint")));
        if (multiline) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setSingleLine(false);
            edit.setMinLines(3);
        } else {
            edit.setInputType(InputType.TYPE_CLASS_TEXT);
            edit.setSingleLine(true);
        }
        ArrayList<InputFilter> filters = new ArrayList<>();
        if (maxLength > 0) {
            filters.add(new InputFilter.LengthFilter(maxLength));
        }
        if (mask != null) {
            try {
                filters.add(maskFilter(Pattern.compile(mask)));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        edit.setFilters(filters.toArray(new InputFilter[0]));
        edit.setText(str(row.get("value")));
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String value = s.toString();
                if (value.equals(str(row.get("value")))) {
                    return;
                }
                row.put("value", value);
                PluginsController.getInstance().onSettingChange(pluginId, key, value, screenToken);
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        frame.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 21, 8, 21, 8));
        editTextViews.put(index, frame);
        return frame;
    }

    /** Accept an edit only while the resulting text still matches, or could still match, the mask. */
    private static InputFilter maskFilter(Pattern pattern) {
        return (source, start, end, dest, dstart, dend) -> {
            String result = dest.subSequence(0, dstart) + source.subSequence(start, end).toString()
                    + dest.subSequence(dend, dest.length());
            Matcher m = pattern.matcher(result);
            return m.matches() || m.hitEnd() ? null : "";
        };
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENABLED && isRoot()) {
            PluginInfo info = info();
            if (info == null) {
                return;
            }
            boolean enabled = !info.enabled;
            PluginsController.getInstance().setEnabled(pluginId, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            reloadModel();
            listView.adapter.update(true);
            return;
        }
        if (item.id == ID_DELETE && isRoot()) {
            confirmDelete();
            return;
        }
        Map<String, Object> row = rowsByItem.get(item);
        if (row == null) {
            return;
        }
        int index = asInt(row.get("index"), -1);
        String key = row.get("key") != null ? row.get("key").toString() : null;
        switch (str(row.get("type"))) {
            case "switch": {
                boolean value = !asBool(row.get("value"));
                row.put("value", value);
                item.setChecked(value);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
                PluginsController.getInstance().onSettingChange(pluginId, key, value, screenToken);
                break;
            }
            case "selector":
                showSelector(row, key);
                break;
            case "input":
                showInputDialog(row, key);
                break;
            case "custom":
                if (row.get("factory") instanceof CustomSetting.Factory) {
                    try {
                        ((CustomSetting.Factory<?>) row.get("factory")).onClick(plugin(), item, view);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                }
                clickAndMaybeOpen(row, index, view);
                break;
            case "text":
                clickAndMaybeOpen(row, index, view);
                break;
            default:
                break;
        }
    }

    private void clickAndMaybeOpen(Map<String, Object> row, int index, View view) {
        if (asBool(row.get("has_click"))) {
            PluginsController.getInstance().onSettingClick(pluginId, index, view, screenToken);
        }
        if (asBool(row.get("has_sub_fragment"))) {
            Map<String, Object> sub = PluginsController.getInstance().createSubSettings(pluginId, screenToken, index);
            if (sub != null && sub.get("token") != null) {
                presentFragment(new PluginSettingsActivity(pluginId, str(sub.get("title")), str(sub.get("token"))));
            }
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        Map<String, Object> row = rowsByItem.get(item);
        if (row == null) {
            return false;
        }
        boolean handled = false;
        if (row.get("factory") instanceof CustomSetting.Factory) {
            try {
                handled = ((CustomSetting.Factory<?>) row.get("factory")).performLongClick(plugin(), item, view);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        if (asBool(row.get("has_long_click"))) {
            handled |= PluginsController.getInstance().onSettingLongClick(pluginId, asInt(row.get("index"), -1), view, screenToken);
        }
        return handled;
    }

    private void showSelector(Map<String, Object> row, String key) {
        if (getParentActivity() == null) {
            return;
        }
        List<?> options = row.get("options") instanceof List ? (List<?>) row.get("options") : new ArrayList<>();
        CharSequence[] labels = new CharSequence[options.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = String.valueOf(options.get(i));
        }
        int selected = asInt(row.get("value"), 0);
        PopupUtils.showDialog(labels, null, str(row.get("text")), selected, getParentActivity(), which -> {
            if (which == selected) {
                return;
            }
            row.put("value", which);
            PluginsController.getInstance().onSettingChange(pluginId, key, which, screenToken);
            listView.adapter.update(true);
        }, getResourceProvider(), true);
    }

    private void showInputDialog(Map<String, Object> row, String key) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        String current = str(row.get("value"));
        EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setText(current);
        edit.setSelection(current.length());
        edit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        edit.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        edit.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        edit.setBackground(Theme.createEditTextDrawable(context, true));
        edit.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        edit.setSingleLine(true);

        FrameLayout container = new FrameLayout(context);
        container.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 24, 4, 24, 4));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(str(row.get("text")));
        builder.setView(container);
        builder.setPositiveButton("OK", (dialog, which) -> {
            String value = edit.getText() != null ? edit.getText().toString() : "";
            row.put("value", value);
            PluginsController.getInstance().onSettingChange(pluginId, key, value, screenToken);
            listView.adapter.update(true);
        });
        builder.setNegativeButton("Отмена", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            edit.requestFocus();
            AndroidUtilities.showKeyboard(edit);
        });
        showDialog(dialog);
    }

    private void confirmDelete() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        PluginInfo info = info();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle("Удалить плагин?");
        builder.setMessage("«" + (info != null ? info.displayName() : pluginId) + "» и его настройки будут удалены.");
        builder.setPositiveButton("Удалить", (dialog, which) -> {
            PluginsController.getInstance().deletePlugin(pluginId);
            finishFragment();
        });
        builder.setNegativeButton("Отмена", null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    // ------------------------------------------------------------------ helpers

    private Plugin plugin() {
        PluginInfo info = info();
        return info != null ? new Plugin(info) : new Plugin(pluginId, pluginId);
    }

    private static String metaText(PluginInfo info) {
        if (info == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (info.description != null && info.description.length() > 0) {
            sb.append(info.description);
        }
        if (info.author != null && info.author.length() > 0) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Автор: ").append(info.author);
        }
        if (info.version != null && info.version.length() > 0) {
            sb.append(sb.length() > 0 ? "\n" : "").append("Версия: ").append(info.version);
        }
        if (info.error != null) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("⚠ ").append(info.error);
        }
        return sb.toString();
    }

    private int resolveIcon(Object name) {
        if (!(name instanceof String) || ((String) name).isEmpty()) {
            return 0;
        }
        Context context = getContext() != null ? getContext() : org.telegram.messenger.ApplicationLoader.applicationContext;
        try {
            return context.getResources().getIdentifier((String) name, "drawable", context.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean asBool(Object o) {
        return o instanceof Boolean ? (Boolean) o : Boolean.parseBoolean(String.valueOf(o));
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Throwable t) {
            return def;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
