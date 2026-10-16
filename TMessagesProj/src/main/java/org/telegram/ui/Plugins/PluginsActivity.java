package org.telegram.ui.Plugins;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.plugins.PluginInfo;
import org.telegram.plugins.PluginsController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists installed Python plugins. Tap a plugin to open its screen (enable toggle + settings +
 * delete); use the action-bar "+" to install a .plugin file from storage.
 */
public class PluginsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_ADD = 1;
    private static final int REQUEST_PICK = 49321;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_PLUGIN = 1;
    private static final int VIEW_TYPE_INFO = 2;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private List<PluginInfo> plugins = new ArrayList<>();

    private int headerRow;
    private int pluginsStartRow;
    private int pluginsEndRow;
    private int infoRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsDidLoad);
        updateRows();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsDidLoad);
    }

    private void updateRows() {
        plugins = PluginsController.getInstance().getPlugins();
        rowCount = 0;
        headerRow = rowCount++;
        if (plugins.isEmpty()) {
            pluginsStartRow = pluginsEndRow = -1;
        } else {
            pluginsStartRow = rowCount;
            rowCount += plugins.size();
            pluginsEndRow = rowCount;
        }
        infoRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Плагины");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_ADD) {
                    pickPluginFile();
                }
            }
        });
        actionBar.createMenu().addItem(MENU_ADD, R.drawable.msg_add);

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position >= pluginsStartRow && position < pluginsEndRow) {
                PluginInfo info = plugins.get(position - pluginsStartRow);
                presentFragment(new PluginSettingsActivity(info.id));
            }
        });

        return fragmentView;
    }

    private void pickPluginFile() {
        if (getParentActivity() == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            getParentActivity().startActivityForResult(
                    Intent.createChooser(intent, "Выберите файл плагина (.plugin, .elyx)"), REQUEST_PICK);
        } catch (Throwable t) {
            FileLog.e(t);
            BulletinFactory.of(this).createErrorBulletin("Не удалось открыть выбор файла").show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            importUri(data.getData());
        }
    }

    private void importUri(Uri uri) {
        Utilities.globalQueue.postRunnable(() -> {
            PluginInfo info = null;
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    info = PluginsController.getInstance().installFromStream(in);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            final PluginInfo result = info;
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) {
                    BulletinFactory.of(this).createErrorBulletin("Это не похоже на корректный файл плагина (.plugin, .elyx)").show();
                } else {
                    updateRows();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    BulletinFactory.of(this).createSuccessBulletin("Плагин «" + result.displayName() + "» установлен").show();
                }
            });
        });
    }

    /**
     * Shows a review-and-install dialog for a .plugin file opened inside the app (e.g. tapped in a
     * chat) instead of handing it to an external viewer/browser. Returns true if handled.
     * Called on the UI thread from AndroidUtilities.openForView.
     */
    public static boolean offerInstall(android.app.Activity activity, File file) {
        if (activity == null || file == null || !file.exists()) {
            return false;
        }
        PluginInfo meta = PluginsController.parseMetadata(file);
        if (meta == null || TextUtils.isEmpty(meta.id)) {
            return false; // not a recognizable plugin — let the normal open proceed
        }
        PluginInfo existing = PluginsController.getInstance().findById(meta.id);
        final boolean isUpdate = existing != null;

        StringBuilder sb = new StringBuilder();
        sb.append(meta.displayName());
        if (!TextUtils.isEmpty(meta.version)) {
            sb.append("  v").append(meta.version);
        }
        if (!TextUtils.isEmpty(meta.author)) {
            sb.append("\n").append(meta.author);
        }
        if (isUpdate) {
            String cur = TextUtils.isEmpty(existing.version) ? "?" : existing.version;
            String next = TextUtils.isEmpty(meta.version) ? "?" : meta.version;
            sb.append("\n\nУже установлен: v").append(cur).append("  →  v").append(next);
        }
        if (!TextUtils.isEmpty(meta.description)) {
            sb.append("\n\n").append(meta.description);
        }
        String appSpec = meta.appVersionSpec != null ? meta.appVersionSpec
                : (TextUtils.isEmpty(meta.minVersion) ? null : ">=" + meta.minVersion);
        if (!PluginsController.versionMatches(org.telegram.messenger.BuildConfig.BUILD_VERSION_STRING, appSpec)) {
            sb.append("\n\n⚠ Требуется версия приложения ").append(appSpec);
        }
        if (!PluginsController.versionMatches(PluginsController.SDK_VERSION, meta.sdkVersionSpec)) {
            sb.append("\n\n⚠ Требуется SDK плагинов ").append(meta.sdkVersionSpec)
                    .append(" (в приложении ").append(PluginsController.SDK_VERSION).append(")");
        }
        org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(activity);
        builder.setTitle(isUpdate ? "Обновить плагин?" : "Установить плагин?");
        builder.setMessage(sb.toString());
        builder.setPositiveButton(isUpdate ? "Обновить" : "Установить", (dialog, which) -> {
            dialog.dismiss();
            PluginInfo installed = PluginsController.getInstance().installFromFile(file);
            BaseFragment last = org.telegram.plugins.PluginUtils.getLastFragment();
            if (installed != null && last != null) {
                last.presentFragment(new PluginSettingsActivity(installed.id));
            } else if (installed != null) {
                BulletinFactory.global().createSuccessBulletin(
                        "Плагин «" + installed.displayName() + "» установлен").show();
            } else {
                BulletinFactory.global().createErrorBulletin("Не удалось установить плагин").show();
            }
        });
        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss());
        builder.show();
        return true;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginsDidLoad) {
            updateRows();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_PLUGIN;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == infoRow) {
                return VIEW_TYPE_INFO;
            }
            return VIEW_TYPE_PLUGIN;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_INFO) {
                view = new TextInfoPrivacyCell(mContext);
            } else {
                view = new TextCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (getItemViewType(position)) {
                case VIEW_TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText("Установленные плагины");
                    break;
                case VIEW_TYPE_INFO:
                    String hint = plugins.isEmpty()
                            ? "Плагинов пока нет. Нажмите + и выберите .plugin файл (Python, совместим с exteraGram)."
                            : "Нажмите на плагин, чтобы включить/выключить его, открыть настройки или удалить.";
                    ((TextInfoPrivacyCell) holder.itemView).setText(hint);
                    break;
                default:
                    TextCell cell = (TextCell) holder.itemView;
                    PluginInfo info = plugins.get(position - pluginsStartRow);
                    boolean dividerBelow = (position - pluginsStartRow) < plugins.size() - 1;
                    String status;
                    if (info.error != null) {
                        status = "ошибка";
                    } else if (!info.enabled) {
                        status = "выкл";
                    } else {
                        status = info.version != null ? "v" + info.version : "вкл";
                    }
                    cell.setTextAndValue(info.displayName(), status, dividerBelow);
                    int textKey = info.enabled
                            ? Theme.key_windowBackgroundWhiteBlackText
                            : Theme.key_windowBackgroundWhiteGrayText;
                    cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, textKey);
                    break;
            }
        }
    }
}
