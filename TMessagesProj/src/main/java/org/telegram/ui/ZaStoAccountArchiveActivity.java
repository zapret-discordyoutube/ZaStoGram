package org.telegram.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ZaStoAccountArchive;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Archive of accounts that Telegram deleted, terminated or froze: the local database and a readable
 * report are saved at that moment (see {@link ZaStoAccountArchive}). Here they can be viewed,
 * exported as a ZIP file or deleted.
 */
public class ZaStoAccountArchiveActivity extends BaseFragment {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_BUTTON = 1;
    private static final int VIEW_TYPE_ENTRY = 2;
    private static final int VIEW_TYPE_INFO = 3;
    private static final int REQUEST_EXPORT = 20771;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private List<ZaStoAccountArchive.Entry> entries = new ArrayList<>();
    private ZaStoAccountArchive.Entry exporting;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        ZaStoAccountArchive.markSeen();
        reload();
        return true;
    }

    private void reload() {
        entries = ZaStoAccountArchive.list();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // Rows: 0 header, 1 "save now", 2 info, then one row per archive.
    private int entryStart() {
        return 3;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Архив аккаунтов");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == 1) {
                saveNow();
            } else if (position >= entryStart()) {
                showEntryMenu(entries.get(position - entryStart()));
            }
        });
        return fragmentView;
    }

    private void saveNow() {
        if (getParentActivity() == null) {
            return;
        }
        if (ZaStoAccountArchive.isRunning()) {
            Toast.makeText(getParentActivity(), "Сохранение уже идёт", Toast.LENGTH_SHORT).show();
            return;
        }
        int account = UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()) {
            Toast.makeText(getParentActivity(), "Нет активного аккаунта", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getParentActivity(), "Сохраняю текущий аккаунт, это может занять время", Toast.LENGTH_LONG).show();
        ZaStoAccountArchive.snapshot(account, ZaStoAccountArchive.REASON_MANUAL, false, () -> {
            if (adapter != null) {
                reload();
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), "Готово", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void showEntryMenu(ZaStoAccountArchive.Entry entry) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(entry.name.isEmpty() ? "Аккаунт " + entry.userId : entry.name);
        final CharSequence[] items = entry.ready
                ? new CharSequence[]{"Открыть отчёт", "Сохранить ZIP в папку…", "Удалить"}
                : new CharSequence[]{"Удалить"};
        builder.setItems(items, (dialog, which) -> {
            int action = entry.ready ? which : 2;
            if (action == 0) {
                presentFragment(new ViewerFragment(entry.report()));
            } else if (action == 1) {
                exportZip(entry);
            } else {
                confirmDelete(entry);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void confirmDelete(ZaStoAccountArchive.Entry entry) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Удалить архив?");
        builder.setMessage("Копия базы, отчёт и сохранённые файлы этого аккаунта будут стёрты с устройства. Это нельзя отменить.");
        builder.setPositiveButton("Удалить", (dialog, which) -> Utilities.globalQueue.postRunnable(() -> {
            ZaStoAccountArchive.delete(entry.dir);
            AndroidUtilities.runOnUIThread(this::reload);
        }));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void exportZip(ZaStoAccountArchive.Entry entry) {
        if (getParentActivity() == null) {
            return;
        }
        exporting = entry;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "zastogram-archive-" + entry.dir.getName() + ".zip");
        try {
            getParentActivity().startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(getParentActivity(), "Не удалось открыть выбор папки", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null || exporting == null || getParentActivity() == null) {
            return;
        }
        final Uri uri = data.getData();
        final ZaStoAccountArchive.Entry entry = exporting;
        final Context context = getParentActivity();
        Toast.makeText(context, "Пишу ZIP…", Toast.LENGTH_SHORT).show();
        Utilities.globalQueue.postRunnable(() -> {
            String result;
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                ZaStoAccountArchive.zip(entry.dir, out);
                result = "ZIP сохранён";
            } catch (Throwable e) {
                FileLog.e(e);
                result = "Не удалось сохранить ZIP";
            }
            final String message = result;
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
        });
    }

    private static String describe(ZaStoAccountArchive.Entry entry) {
        String when = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(entry.created));
        StringBuilder builder = new StringBuilder();
        builder.append(when).append(" · ").append(ZaStoAccountArchive.describeReason(entry.reason));
        if (entry.ready) {
            builder.append("\nЧатов: ").append(entry.chats).append(", сообщений: ").append(entry.messages).append(", файлов: ").append(entry.files);
        } else {
            builder.append("\nОтчёт собирается или не удался; копия базы сохранена");
        }
        return builder.toString();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return entryStart() + entries.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_TYPE_BUTTON || type == VIEW_TYPE_ENTRY;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_TYPE_HEADER;
            } else if (position == 1) {
                return VIEW_TYPE_BUTTON;
            } else if (position == 2) {
                return VIEW_TYPE_INFO;
            }
            return VIEW_TYPE_ENTRY;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_BUTTON:
                    view = new TextSettingsCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                default:
                    TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
                    cell.setMultilineDetail(true);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (getItemViewType(position)) {
                case VIEW_TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(entries.isEmpty() ? "Сохранённых аккаунтов нет" : "Сохранённые аккаунты");
                    break;
                case VIEW_TYPE_BUTTON:
                    ((TextSettingsCell) holder.itemView).setText("Сохранить текущий аккаунт сейчас", false);
                    break;
                case VIEW_TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText("Если Telegram удалит, заблокирует или заморозит аккаунт, приложение перед стиранием кэша копирует локальную базу и собирает отчёт: чаты, каналы, группы, ссылки t.me, сообщения и скачанные файлы. Сохраняется только то, что уже было загружено на это устройство.");
                    break;
                default:
                    ZaStoAccountArchive.Entry entry = entries.get(position - entryStart());
                    String title = entry.name.isEmpty() ? "Аккаунт " + entry.userId : entry.name;
                    if (!entry.username.isEmpty()) {
                        title += " (@" + entry.username + ")";
                    }
                    ((TextDetailSettingsCell) holder.itemView).setTextAndValue(title, describe(entry), position + 1 < getItemCount());
                    break;
            }
        }
    }

    /** Shows the saved HTML report; relative links between its pages work because it is loaded from disk. */
    public static class ViewerFragment extends BaseFragment {

        private final java.io.File index;
        private WebView webView;

        public ViewerFragment(java.io.File index) {
            this.index = index;
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        public View createView(Context context) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle("Отчёт");
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        if (webView != null && webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            finishFragment();
                        }
                    }
                }
            });
            webView = new WebView(context);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(false);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(false);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    if ("file".equals(uri.getScheme())) {
                        return false;
                    }
                    // t.me and other web links go to the system, not into the offline viewer.
                    try {
                        view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    return true;
                }
            });
            webView.loadUrl(Uri.fromFile(index).toString());
            FrameLayout frameLayout = new FrameLayout(context);
            frameLayout.addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            fragmentView = frameLayout;
            return fragmentView;
        }

        @Override
        public boolean onBackPressed(boolean invoked) {
            if (webView != null && webView.canGoBack()) {
                if (invoked) {
                    webView.goBack();
                }
                return false;
            }
            return super.onBackPressed(invoked);
        }

        @Override
        public void onFragmentDestroy() {
            super.onFragmentDestroy();
            if (webView != null) {
                webView.destroy();
                webView = null;
            }
        }
    }
}
