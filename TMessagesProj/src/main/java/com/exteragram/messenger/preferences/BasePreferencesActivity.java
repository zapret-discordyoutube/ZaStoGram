package com.exteragram.messenger.preferences;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * exteraGram's base for settings screens built on {@link UniversalRecyclerView}. DEX plugins
 * (re:extera and its forks) subclass it directly, so the class, its protected fields and the
 * abstract fillItems/onClick/getTitle contract must match exteraGram's binary shape. exteraGram's
 * deep-link registry (SettingsRegistry) has no ZaStoGram counterpart, so long-press links are off.
 */
public abstract class BasePreferencesActivity extends BaseFragment {

    protected LinearLayoutManager layoutManager;
    protected UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        initializeOptionStrings();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getTitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        if (actionBar.menu == null) {
            actionBar.createMenu();
        }

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        listView.setSections();
        if (!hasHeaderCell()) {
            actionBar.setAdaptiveBackground(listView, needHideTitle());
        }
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = frameLayout;
        return frameLayout;
    }

    public abstract void fillItems(ArrayList<UItem> items, UniversalAdapter adapter);

    public abstract String getTitle();

    public abstract void onClick(UItem item, View view, int position, float x, float y);

    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    public boolean hasHeaderCell() {
        return false;
    }

    public boolean hasWhiteActionBar() {
        return false;
    }

    public boolean needHideTitle() {
        return false;
    }

    public void initializeOptionStrings() {
    }

    @Override
    public boolean isLightStatusBar() {
        if (!hasWhiteActionBar()) {
            return super.isLightStatusBar();
        }
        return ColorUtils.calculateLuminance(getThemedColor(Theme.key_windowBackgroundWhite)) > 0.7f;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
            listView.setClipToPadding(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return getBottomInset();
            }

            @Override
            public int getTopOffset(int tag) {
                return hasHeaderCell() ? AndroidUtilities.statusBarHeight : 0;
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        Bulletin.removeDelegate(this);
    }

    public void scrollToItem(int itemId) {
        if (listView == null || listView.adapter == null || layoutManager == null) {
            return;
        }
        int position = listView.findPositionByItemId(itemId);
        if (position >= 0 && position < listView.adapter.getItemCount()) {
            layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(80));
        }
    }

    public void showListDialog(UItem item, CharSequence[] entries, String title, int selected, PopupUtils.OnItemClickListener listener) {
        showListDialog(item, entries, null, title, selected, listener);
    }

    public void showListDialog(UItem item, CharSequence[] entries, int[] icons, String title, int selected, PopupUtils.OnItemClickListener listener) {
        showListDialog(item, entries, icons, title, selected, listener, icons == null, true);
    }

    public void showListDialog(UItem item, CharSequence[] entries, int[] icons, String title, int selected,
                               PopupUtils.OnItemClickListener listener, boolean withRadio, boolean skipSame) {
        if (getParentActivity() == null) {
            return;
        }
        PopupUtils.showDialog(entries, icons, title, selected, getContext(), which -> {
            if (skipSame && selected == which) {
                return;
            }
            if (listener != null) {
                listener.onClick(which);
            }
            if (listView != null) {
                listView.adapter.update(true);
            }
        }, getResourceProvider(), withRadio);
    }

    public void showRestartBulletin() {
        BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                LocaleController.getString(R.string.RestartRequired),
                LocaleController.getString(R.string.BotUnblock),
                () -> {
                    Context context = getContext();
                    if (context == null) {
                        return;
                    }
                    Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                    Intent restart = Intent.makeRestartActivityTask(launch != null ? launch.getComponent() : null);
                    restart.setPackage(context.getPackageName());
                    context.startActivity(restart);
                    Runtime.getRuntime().exit(0);
                }).show();
    }

    public void toggleBooleanSettingAndRefresh(UItem item, Consumer<Boolean> setter) {
        boolean value = !item.checked;
        setter.accept(value);
        item.setChecked(value);
        View view = listView.findViewByItemId(item.id);
        if (view instanceof CheckBoxCell) {
            ((CheckBoxCell) view).setChecked(value, true);
        } else if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
        listView.adapter.update(true);
    }

    public int[] unBox(Collection<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }
}
