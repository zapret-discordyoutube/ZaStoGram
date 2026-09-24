package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ZaStoPrivacy;
import org.telegram.messenger.ZaStoVideoCompression;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * ZaSto privacy/retention toggles. Everything is ON by default; turning a switch off disables the
 * corresponding ZaSto override at runtime (persisted in {@link ZaStoPrivacy}).
 */
public class ZaStoPrivacySettingsActivity extends BaseFragment {

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_HEADER = 1;
    private static final int VIEW_TYPE_INFO = 2;
    private static final int VIEW_TYPE_VALUE = 3;

    private RecyclerListView listView;

    private int headerRow;
    private int keepDeletedRow;
    private int keepEphemeralRow;
    private int keepEditHistoryRow;
    private int allowSaveRow;
    private int allowScreenshotsRow;
    private int muteScreenshotRow;
    private int disableAdsRow;
    private int zastogramPromoRow;
    private int videoNoRecompressRow;
    private int infoRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        rowCount = 0;
        headerRow = rowCount++;
        keepDeletedRow = rowCount++;
        keepEphemeralRow = rowCount++;
        keepEditHistoryRow = rowCount++;
        allowSaveRow = rowCount++;
        allowScreenshotsRow = rowCount++;
        muteScreenshotRow = rowCount++;
        disableAdsRow = rowCount++;
        zastogramPromoRow = rowCount++;
        videoNoRecompressRow = rowCount++;
        infoRow = rowCount++;
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("ZaSto Приватность");
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
        listView.setAdapter(new ListAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == videoNoRecompressRow) {
                showVideoLimitPicker();
                return;
            }
            if (!(view instanceof TextCheckCell)) {
                return;
            }
            String key = keyForRow(position);
            if (key == null) {
                return;
            }
            boolean newValue = !ZaStoPrivacy.get(key);
            ZaStoPrivacy.set(key, newValue);
            ((TextCheckCell) view).setChecked(newValue);
            if (position == zastogramPromoRow) {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
                    }
                }
            }
        });

        return fragmentView;
    }

    private void showVideoLimitPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final int[] choices = ZaStoVideoCompression.LIMIT_CHOICES_MB;
        final CharSequence[] items = new CharSequence[choices.length];
        for (int a = 0; a < choices.length; a++) {
            items[a] = choices[a] == 0 ? "Выкл (всегда пережимать)" : "Видео до " + choices[a] + " МБ";
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Видео без пережатия");
        builder.setItems(items, (dialog, which) -> {
            ZaStoVideoCompression.setLimitMb(choices[which]);
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyItemChanged(videoNoRecompressRow);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private String keyForRow(int position) {
        if (position == keepDeletedRow) return ZaStoPrivacy.KEY_KEEP_DELETED;
        if (position == keepEphemeralRow) return ZaStoPrivacy.KEY_KEEP_EPHEMERAL;
        if (position == keepEditHistoryRow) return ZaStoPrivacy.KEY_KEEP_EDIT_HISTORY;
        if (position == allowSaveRow) return ZaStoPrivacy.KEY_ALLOW_SAVE_PROTECTED;
        if (position == allowScreenshotsRow) return ZaStoPrivacy.KEY_ALLOW_SCREENSHOTS;
        if (position == muteScreenshotRow) return ZaStoPrivacy.KEY_MUTE_SCREENSHOT_PING;
        if (position == disableAdsRow) return ZaStoPrivacy.KEY_DISABLE_ADS;
        if (position == zastogramPromoRow) return ZaStoPrivacy.KEY_SHOW_ZASTOGRAM_PROMO;
        return null;
    }

    private String labelForRow(int position) {
        if (position == keepDeletedRow) return "Сохранять удалённые сообщения";
        if (position == keepEphemeralRow) return "Сохранять самоуничтожающиеся (view-once)";
        if (position == keepEditHistoryRow) return "Хранить историю редактирования";
        if (position == allowSaveRow) return "Сохранение/пересылка защищённого и историй";
        if (position == allowScreenshotsRow) return "Разрешать скриншоты";
        if (position == muteScreenshotRow) return "Не сообщать о скриншоте (секретные чаты)";
        if (position == disableAdsRow) return "Отключить рекламу";
        if (position == zastogramPromoRow) return "Закреплять канал ZaStoGram в списке чатов";
        return "";
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
            return holder.getItemViewType() == VIEW_TYPE_CHECK || holder.getItemViewType() == VIEW_TYPE_VALUE;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == infoRow) {
                return VIEW_TYPE_INFO;
            } else if (position == videoNoRecompressRow) {
                return VIEW_TYPE_VALUE;
            }
            return VIEW_TYPE_CHECK;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_INFO) {
                view = new TextInfoPrivacyCell(mContext);
            } else if (viewType == VIEW_TYPE_VALUE) {
                view = new TextSettingsCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextCheckCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (getItemViewType(position)) {
                case VIEW_TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText("Функции приватности ZaSto");
                    break;
                case VIEW_TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText("По умолчанию всё включено. Выключение тумблера отменяет соответствующую функцию ZaSto. Без закрепления канал ZaStoGram становится обычным чатом: его можно убрать в архив. Видео без пережатия уходит оригиналом, как с компьютера: быстрее, но больше по объёму; видео с обрезкой, рисованием или фильтрами пережимается всегда.");
                    break;
                case VIEW_TYPE_VALUE:
                    ((TextSettingsCell) holder.itemView).setTextAndValue("Видео без пережатия", ZaStoVideoCompression.describe(ZaStoVideoCompression.getLimitMb()), false);
                    break;
                default:
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    String key = keyForRow(position);
                    boolean last = false;
                    cell.setTextAndCheck(labelForRow(position), key != null && ZaStoPrivacy.get(key), !last);
                    break;
            }
        }
    }
}
