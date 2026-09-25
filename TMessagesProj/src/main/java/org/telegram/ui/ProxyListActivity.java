/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProxyConnectionEvent;
import org.telegram.messenger.ProxyCheckDiagnostics;
import org.telegram.messenger.ProxyCheckScheduler;
import org.telegram.messenger.ProxyGeoIp;
import org.telegram.messenger.ProxyRotationController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.StatsController;
import org.telegram.proxy.ProxySettings;
import org.telegram.proxy.ProxyWssFallback;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProxyListActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final static boolean IS_PROXY_ROTATION_AVAILABLE = true;
    private static final int MENU_DELETE = 0;
    private static final int MENU_SHARE = 1;
    private static final int[] MT_PROXY_TLS_PROFILE_OPTIONS = new int[] {
            ConnectionsManager.MT_PROXY_TLS_PROFILE_AUTO,
            ConnectionsManager.MT_PROXY_TLS_PROFILE_AUTO_ROTATE,
            ConnectionsManager.MT_PROXY_TLS_PROFILE_FIREFOX_ANDROID,
            ConnectionsManager.MT_PROXY_TLS_PROFILE_YANDEX,
            ConnectionsManager.MT_PROXY_TLS_PROFILE_ANDROID_OKHTTP,
            ConnectionsManager.MT_PROXY_TLS_PROFILE_FIREFOX,
    };
    private static final int[] MT_PROXY_RECORD_SIZING_OPTIONS = new int[] {
            ConnectionsManager.MT_PROXY_RECORD_SIZING_OFF,
            ConnectionsManager.MT_PROXY_RECORD_SIZING_CONSERVATIVE,
            ConnectionsManager.MT_PROXY_RECORD_SIZING_VARIED,
    };
    private static final int[] MT_PROXY_TIMING_OPTIONS = new int[] {
            ConnectionsManager.MT_PROXY_TIMING_OFF,
            ConnectionsManager.MT_PROXY_TIMING_GENTLE,
            ConnectionsManager.MT_PROXY_TIMING_BALANCED,
    };
    private static final int[] MT_PROXY_STARTUP_COVER_OPTIONS = new int[] {
            ConnectionsManager.MT_PROXY_STARTUP_COVER_OFF,
            ConnectionsManager.MT_PROXY_STARTUP_COVER_SOFT,
            ConnectionsManager.MT_PROXY_STARTUP_COVER_STRICT,
    };
    private static final int[] MT_PROXY_CONNECTION_PATTERN_OPTIONS = new int[] {
            ConnectionsManager.MT_PROXY_CONNECTION_PATTERN_OFF,
            ConnectionsManager.MT_PROXY_CONNECTION_PATTERN_SOFT,
            ConnectionsManager.MT_PROXY_CONNECTION_PATTERN_BROWSER,
            ConnectionsManager.MT_PROXY_CONNECTION_PATTERN_QUIET,
            ConnectionsManager.MT_PROXY_CONNECTION_PATTERN_STRICT,
    };
    private ListAdapter listAdapter;
    private RecyclerListView listView;
    @SuppressWarnings("FieldCanBeLocal")
    private LinearLayoutManager layoutManager;

    private int currentConnectionState;

    private boolean useProxySettings;

    private int rowCount;
    @Keep
    private int useProxyRow;
    private int useProxyShadowRow;
    private int connectionsHeaderRow;
    private int proxyStartRow;
    private int proxyEndRow;
    @Keep
    private int proxyAddRow;
    private int proxyShadowRow;
    private int rotationRow;
    private int rotationTimeoutRow;
    private int rotationTimeoutInfoRow;
    private int tlsProfileRow;
    private int tlsProfileInfoRow;
    private int clientHelloFragmentationRow;
    private int clientHelloFragmentationInfoRow;
    private int mtProxySoftMuxRow;
    private int mtProxySoftMuxInfoRow;
    private int mtProxyConnectionPatternRow;
    private int mtProxyConnectionPatternInfoRow;
    private int mtProxyRecordSizingRow;
    private int mtProxyRecordSizingInfoRow;
    private int mtProxyTimingRow;
    private int mtProxyTimingInfoRow;
    private int mtProxyStartupCoverRow;
    private int mtProxyStartupCoverInfoRow;
    private int wssTransportHeaderRow;
    private int wssTransportRow;
    private int callRelayTcpTlsRow;
    private int wssTransportInfoRow;
    private int deleteAllRow;

    private ItemTouchHelper itemTouchHelper;
    private NumberTextView selectedCountTextView;
    private ActionBarMenuItem shareMenuItem;
    private ActionBarMenuItem deleteMenuItem;

    private List<SharedConfig.ProxyInfo> selectedItems = new ArrayList<>();
    private List<SharedConfig.ProxyInfo> proxyList = new ArrayList<>();
    private boolean wasCheckedAllList;
    private boolean skipNextProxySettingsChangedLayout;

    public class TextDetailProxyCell extends FrameLayout {

        private TextView textView;
        private TextView valueTextView;
        private ImageView checkImageView;
        private ImageView moveImageView;
        private SharedConfig.ProxyInfo currentInfo;
        private Drawable checkDrawable;
        private boolean reorderAllowed;

        private CheckBox2 checkBox;
        private boolean isSelected;
        private boolean isSelectionEnabled;

        private int color;

        public TextDetailProxyCell(Context context) {
            super(context);

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, (LocaleController.isRTL ? 104 : 21), 10, (LocaleController.isRTL ? 21 : 104), 0));

            valueTextView = new TextView(context);
            valueTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            valueTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            valueTextView.setLines(1);
            valueTextView.setMaxLines(1);
            valueTextView.setSingleLine(true);
            valueTextView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
            valueTextView.setEllipsize(TextUtils.TruncateAt.END);
            valueTextView.setPadding(0, 0, 0, 0);
            addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, (LocaleController.isRTL ? 104 : 21), 35, (LocaleController.isRTL ? 21 : 104), 0));

            checkImageView = new ImageView(context);
            checkImageView.setImageResource(R.drawable.msg_info);
            checkImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), PorterDuff.Mode.MULTIPLY));
            checkImageView.setScaleType(ImageView.ScaleType.CENTER);
            checkImageView.setContentDescription(getString(R.string.Edit));
            addView(checkImageView, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, LocaleController.isRTL ? 56 : 8, 8, LocaleController.isRTL ? 8 : 56, 0));
            checkImageView.setOnClickListener(v -> presentFragment(new ProxySettingsActivity(currentInfo)));

            moveImageView = new ImageView(context);
            moveImageView.setImageResource(R.drawable.msg_reorder);
            moveImageView.setScaleType(ImageView.ScaleType.CENTER);
            moveImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), PorterDuff.Mode.MULTIPLY));
            moveImageView.setContentDescription(getString(R.string.FilterReorder));
            addView(moveImageView, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 8, 8, 8, 0));

            checkBox = new CheckBox2(context, 21);
            checkBox.setColor(Theme.key_checkbox, Theme.key_radioBackground, Theme.key_checkboxCheck);
            checkBox.setDrawBackgroundAsArc(14);
            checkBox.setVisibility(GONE);
            addView(checkBox, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 16, 0, 8, 0));

            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + 1, MeasureSpec.EXACTLY));
        }

        public void setProxy(SharedConfig.ProxyInfo proxyInfo) {
            textView.setText(proxyInfo.settings.getType() == ProxySettings.Type.WEB
                    ? proxyInfo.settings.getAddress() + " (WEB)"
                    : proxyInfo.settings.getAddress() + ":" + proxyInfo.settings.getPort());
            currentInfo = proxyInfo;
        }

        public void updateStatus() {
            boolean currentProxyEnabled = isProxyActiveForCurrentMode(currentInfo);
            int colorKey = ProxyCheckDiagnostics.statusColorKey(currentInfo, currentProxyEnabled, currentConnectionState);
            valueTextView.setText(ProxyCheckDiagnostics.statusText(currentInfo, currentProxyEnabled, currentConnectionState));
            color = Theme.getColor(colorKey);
            valueTextView.setTag(colorKey);
            valueTextView.setTextColor(color);
            if (checkDrawable != null) {
                checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
            }
        }

        public void setSelectionEnabled(boolean enabled, boolean animated) {
            if (isSelectionEnabled == enabled && animated) {
                return;
            }
            isSelectionEnabled = enabled;

            float fromX = 0, toX = LocaleController.isRTL ? -AndroidUtilities.dp(32) : AndroidUtilities.dp(32);
            if (!animated) {
                float x = enabled ? toX : fromX;
                textView.setTranslationX(x);
                valueTextView.setTranslationX(x);
                checkImageView.setTranslationX(x);
                checkBox.setTranslationX((LocaleController.isRTL ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32)) + x);
                checkImageView.setVisibility(enabled ? GONE : VISIBLE);
                checkImageView.setAlpha(1f);
                checkImageView.setScaleX(1f);
                checkImageView.setScaleY(1f);
                moveImageView.setTranslationX(x);
                moveImageView.setVisibility(enabled || !reorderAllowed ? GONE : VISIBLE);
                moveImageView.setAlpha(1f);
                moveImageView.setScaleX(1f);
                moveImageView.setScaleY(1f);
                checkBox.setVisibility(enabled ? VISIBLE : GONE);
                checkBox.setAlpha(1f);
                checkBox.setScaleX(1f);
                checkBox.setScaleY(1f);
            } else {
                ValueAnimator animator = ValueAnimator.ofFloat(enabled ? 0 : 1, enabled ? 1 : 0).setDuration(200);
                animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
                animator.addUpdateListener(animation -> {
                    float val = (float) animation.getAnimatedValue();
                    float x = AndroidUtilities.lerp(fromX, toX, val);
                    textView.setTranslationX(x);
                    valueTextView.setTranslationX(x);
                    checkImageView.setTranslationX(x);
                    checkBox.setTranslationX((LocaleController.isRTL ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32)) + x);

                    float scale = 0.5f + val * 0.5f;
                    checkBox.setScaleX(scale);
                    checkBox.setScaleY(scale);
                    checkBox.setAlpha(val);

                    scale = 0.5f + (1f - val) * 0.5f;
                    checkImageView.setScaleX(scale);
                    checkImageView.setScaleY(scale);
                    checkImageView.setAlpha(1f - val);
                    moveImageView.setTranslationX(x);
                    if (reorderAllowed) {
                        moveImageView.setScaleX(scale);
                        moveImageView.setScaleY(scale);
                        moveImageView.setAlpha(1f - val);
                    }
                });
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        if (enabled) {
                            checkBox.setAlpha(0f);
                            checkBox.setVisibility(VISIBLE);
                        } else {
                            checkImageView.setAlpha(0f);
                            checkImageView.setVisibility(VISIBLE);
                            if (reorderAllowed) {
                                moveImageView.setAlpha(0f);
                                moveImageView.setVisibility(VISIBLE);
                            }
                        }
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (enabled) {
                            checkImageView.setVisibility(GONE);
                            moveImageView.setVisibility(GONE);
                        } else {
                            checkBox.setVisibility(GONE);
                        }
                    }
                });
                animator.start();
            }
        }

        public void setItemSelected(boolean selected, boolean animated) {
            if (selected == isSelected && animated) {
                return;
            }
            isSelected = selected;
            checkBox.setChecked(selected, animated);
        }

        public void setReorderAllowed(boolean allowed) {
            reorderAllowed = allowed;
            if (!isSelectionEnabled) {
                moveImageView.setVisibility(allowed ? VISIBLE : GONE);
            }
        }

        public void setReorderTouchListener(View.OnTouchListener listener) {
            moveImageView.setOnTouchListener(listener);
        }

        public void setChecked(boolean checked) {
            if (checked) {
                if (checkDrawable == null) {
                    checkDrawable = getResources().getDrawable(R.drawable.proxy_check).mutate();
                }
                if (checkDrawable != null) {
                    checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                }
                if (LocaleController.isRTL) {
                    valueTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, checkDrawable, null);
                } else {
                    valueTextView.setCompoundDrawablesWithIntrinsicBounds(checkDrawable, null, null, null);
                }
            } else {
                valueTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            }
        }

        public void setValue(CharSequence value) {
            valueTextView.setText(value);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateStatus();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    private void swapProxies(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= proxyList.size() || toIndex >= proxyList.size()) {
            return;
        }
        SharedConfig.ProxyInfo a = proxyList.get(fromIndex);
        SharedConfig.ProxyInfo b = proxyList.get(toIndex);
        Collections.swap(proxyList, fromIndex, toIndex);
        int sa = SharedConfig.proxyList.indexOf(a);
        int sb = SharedConfig.proxyList.indexOf(b);
        if (sa >= 0 && sb >= 0) {
            Collections.swap(SharedConfig.proxyList, sa, sb);
        }
    }

    private class TouchHelperCallback extends ItemTouchHelper.Callback {
        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            if (viewHolder.getItemViewType() != ListAdapter.VIEW_TYPE_PROXY_DETAIL || !selectedItems.isEmpty()) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
            int from = source.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from < proxyStartRow || from >= proxyEndRow || to < proxyStartRow || to >= proxyEndRow) {
                return false;
            }
            swapProxies(from - proxyStartRow, to - proxyStartRow);
            listAdapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public boolean canDropOver(RecyclerView recyclerView, RecyclerView.ViewHolder current, RecyclerView.ViewHolder target) {
            int pos = target.getAdapterPosition();
            return pos >= proxyStartRow && pos < proxyEndRow;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (viewHolder != null && actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                viewHolder.itemView.setPressed(true);
            }
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
            SharedConfig.saveProxyList();
        }
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();

        SharedConfig.loadProxyList();
        currentConnectionState = ConnectionsManager.getInstance(currentAccount).getConnectionState();

        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyChangedByRotation);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.proxyConnectionStageChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);

        final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        useProxySettings = preferences.getBoolean("proxy_enabled", false) && !SharedConfig.proxyList.isEmpty();
        markConnectedCurrentProxyIfNeeded();

        updateRows(true);

        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        stopProxySpeedSampler();
        ConnectionsManager.getInstance(currentAccount).setLivePingInterval(0);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyChangedByRotation);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.proxyConnectionStageChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        ProxyCheckScheduler.cancelOwner(this);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ProxySettings));
        updateProxyActionBarStatus();
        if (parentLayout != null && parentLayout.isLayersLayout()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        ((DefaultItemAnimator) listView.getItemAnimator()).setDelayAnimations(false);
        ((DefaultItemAnimator) listView.getItemAnimator()).setTranslationInterpolator(CubicBezierInterpolator.DEFAULT);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter);
        itemTouchHelper = new ItemTouchHelper(new TouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(listView);
        listView.setOnItemClickListener((view, position) -> {
            if (position == useProxyRow) {
                if (SharedConfig.currentProxy == null) {
                    if (!proxyList.isEmpty()) {
                        SharedConfig.currentProxy = proxyList.get(0);

                        if (!useProxySettings) {
                            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                            SharedConfig.currentProxy.settings.toSharedPreferences(editor);
                            editor.commit();
                        }
                    } else {
                        presentFragment(new ProxySettingsActivity());
                        return;
                    }
                }
                useProxySettings = !useProxySettings;
                updateRows(true);

                SharedPreferences preferences = MessagesController.getGlobalMainSettings();

                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(useProxySettings);

                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putBoolean("proxy_enabled", useProxySettings);
                editor.commit();

                if (useProxySettings) {
                    ProxyCheckScheduler.markConnectionStarting(SharedConfig.currentProxy, ProxyConnectionEvent.Origin.SETTINGS_CHANGE);
                }
                ConnectionsManager.setProxySettings(useProxySettings, SharedConfig.currentProxy.settings, ProxyConnectionEvent.Origin.SETTINGS_CHANGE);
                NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);

                for (int a = proxyStartRow; a < proxyEndRow; a++) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.updateStatus();
                    }
                }
            } else if (position == rotationRow) {
                SharedConfig.proxyRotationEnabled = !SharedConfig.proxyRotationEnabled;
                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(SharedConfig.proxyRotationEnabled);
                SharedConfig.saveConfig();

                updateRows(true);
            } else if (position == clientHelloFragmentationRow) {
                SharedConfig.mtProxyClientHelloFragmentation = !SharedConfig.mtProxyClientHelloFragmentation;
                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(SharedConfig.mtProxyClientHelloFragmentation);
                SharedConfig.saveConfig();
                reapplyCurrentProxySettings();
            } else if (position == mtProxySoftMuxRow) {
                SharedConfig.mtProxySoftMux = !SharedConfig.mtProxySoftMux;
                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(SharedConfig.mtProxySoftMux);
                SharedConfig.saveConfig();
                reapplyCurrentProxySettings();
            } else if (position == wssTransportRow) {
                boolean enabled = !isWssTransportSelected();
                if (enabled) {
                    disableLegacyProxyForWss();
                }
                SharedConfig.setWssTransportEnabled(enabled);
                ((TextCheckCell) view).setChecked(enabled);
                ConnectionsManager.setWssTransportEnabled();
                updateRows(true);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            } else if (position == callRelayTcpTlsRow) {
                boolean enabled = !SharedConfig.callRelayTcpTlsEnabled;
                SharedConfig.setCallRelayTcpTlsEnabled(enabled);
                ((TextCheckCell) view).setChecked(enabled);
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                if (!selectedItems.isEmpty()) {
                    listAdapter.toggleSelected(position);
                    return;
                }
                SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                useProxySettings = true;
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                info.settings.toSharedPreferences(editor);
                editor.putBoolean("proxy_enabled", useProxySettings);
                editor.commit();
                SharedConfig.currentProxy = info;
                ProxyCheckScheduler.markConnectionStarting(SharedConfig.currentProxy, ProxyConnectionEvent.Origin.USER_SELECT);
                for (int a = proxyStartRow; a < proxyEndRow; a++) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.setChecked(cell.currentInfo == info);
                        cell.updateStatus();
                    }
                }
                updateRows(false);
                RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(useProxyRow);
                if (holder != null) {
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    textCheckCell.setChecked(true);
                }
                ConnectionsManager.setProxySettings(useProxySettings, SharedConfig.currentProxy.settings, ProxyConnectionEvent.Origin.USER_SELECT);
            } else if (position == proxyAddRow) {
                presentFragment(new ProxySettingsActivity());
            } else if (position == deleteAllRow) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setMessage(getString(R.string.DeleteAllProxiesConfirm));
                builder.setNegativeButton(getString(R.string.Cancel), null);
                builder.setTitle(getString(R.string.DeleteProxyTitle));
                builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    for (SharedConfig.ProxyInfo info : proxyList) {
                        SharedConfig.deleteProxy(info);
                    }
                    useProxySettings = false;
                    NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    updateRows(true);
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(useProxyRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                        listAdapter.clearSelected();
                    }
                });
                AlertDialog dialog = builder.create();
                showDialog(dialog);
                TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (button != null) {
                    button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= proxyStartRow && position < proxyEndRow) {
                listAdapter.toggleSelected(position);
                return true;
            }
            return false;
        });

        ActionBarMenu actionMode = actionBar.createActionMode();
        selectedCountTextView = new NumberTextView(actionMode.getContext());
        selectedCountTextView.setTextSize(18);
        selectedCountTextView.setTypeface(AndroidUtilities.bold());
        selectedCountTextView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(selectedCountTextView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        selectedCountTextView.setOnTouchListener((v, event) -> true);

        shareMenuItem = actionMode.addItemWithWidth(MENU_SHARE, R.drawable.msg_share, AndroidUtilities.dp(54));
        shareMenuItem.setContentDescription(getString(R.string.StickersShare));
        deleteMenuItem = actionMode.addItemWithWidth(MENU_DELETE, R.drawable.msg_delete, AndroidUtilities.dp(54));
        deleteMenuItem.setContentDescription(getString(R.string.Delete));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                switch (id) {
                    case -1:
                        if (selectedItems.isEmpty()) {
                            finishFragment();
                        } else {
                            listAdapter.clearSelected();
                        }
                        break;
                    case MENU_DELETE:
                        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                        builder.setMessage(getString(selectedItems.size() > 1 ? R.string.DeleteProxyMultiConfirm : R.string.DeleteProxyConfirm));
                        builder.setNegativeButton(getString(R.string.Cancel), null);
                        builder.setTitle(getString(R.string.DeleteProxyTitle));
                        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                            for (SharedConfig.ProxyInfo info : selectedItems) {
                                SharedConfig.deleteProxy(info);
                            }
                            if (SharedConfig.currentProxy == null) {
                                useProxySettings = false;
                            }
                            NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                            NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                            updateRows(true);
                            if (listAdapter != null) {
                                if (SharedConfig.currentProxy == null) {
                                    listAdapter.notifyItemChanged(useProxyRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                                }
                                listAdapter.clearSelected();
                            }
                        });
                        AlertDialog dialog = builder.create();
                        showDialog(dialog);
                        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                        if (button != null) {
                            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                        }
                        break;
                    case MENU_SHARE:
                        StringBuilder links = new StringBuilder();
                        for (SharedConfig.ProxyInfo info : selectedItems) {
                            if (links.length() > 0) {
                                links.append("\n\n");
                            }
                            links.append(info.settings.getLink());
                        }

                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, links.toString());
                        Intent chooserIntent = Intent.createChooser(shareIntent, getString(selectedItems.size() > 1 ? R.string.ShareLinks : R.string.ShareLink));
                        chooserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(chooserIntent);

                        if (listAdapter != null) {
                            listAdapter.clearSelected();
                        }
                        break;
                }
            }
        });

        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selectedItems.isEmpty()) {
            if (invoked) listAdapter.clearSelected();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private int getMtProxyTlsProfileOptionIndex() {
        int profile = ConnectionsManager.getMtProxyTlsProfileOverride();
        for (int i = 0; i < MT_PROXY_TLS_PROFILE_OPTIONS.length; i++) {
            if (MT_PROXY_TLS_PROFILE_OPTIONS[i] == profile) {
                return i;
            }
        }
        return 0;
    }

    private void reapplyCurrentProxySettings() {
        if (useProxySettings && SharedConfig.currentProxy != null) {
            ProxyCheckScheduler.markConnectionStarting(SharedConfig.currentProxy, ProxyConnectionEvent.Origin.SETTINGS_CHANGE);
            updateCurrentProxyStatusCell();
            ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.settings, ProxyConnectionEvent.Origin.SETTINGS_CHANGE);
        }
    }

    private void disableLegacyProxyForWss() {
        if (!useProxySettings) {
            return;
        }
        useProxySettings = false;
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", false);
        editor.apply();
        ConnectionsManager.setProxySettings(false, null, ProxyConnectionEvent.Origin.SETTINGS_CHANGE);
    }

    private boolean isWssTransportSelected() {
        return SharedConfig.wssTransportEnabled && !useProxySettings;
    }

    private boolean isProxySelectedForCurrentMode(SharedConfig.ProxyInfo info) {
        return SharedConfig.currentProxy == info;
    }

    private boolean isProxyActiveForCurrentMode(SharedConfig.ProxyInfo info) {
        return SharedConfig.currentProxy == info && useProxySettings;
    }

    private String[] getMtProxyTlsProfileOptionLabels() {
        return new String[] {
                getString(R.string.MtProxyTlsProfileAuto),
                getString(R.string.MtProxyTlsProfileAutoRotate),
                getString(R.string.MtProxyTlsProfileFirefoxAndroid),
                getString(R.string.MtProxyTlsProfileYandex),
                getString(R.string.MtProxyTlsProfileAndroidOkHttp),
                getString(R.string.MtProxyTlsProfileFirefox),
        };
    }

    private int getMtProxyRecordSizingIndex() {
        for (int i = 0; i < MT_PROXY_RECORD_SIZING_OPTIONS.length; i++) {
            if (MT_PROXY_RECORD_SIZING_OPTIONS[i] == SharedConfig.mtProxyRecordSizingMode) {
                return i;
            }
        }
        return 0;
    }

    private String[] getMtProxyRecordSizingLabels() {
        return new String[] {
                getString(R.string.MtProxyRecordSizingOff),
                getString(R.string.MtProxyRecordSizingConservative),
                getString(R.string.MtProxyRecordSizingVaried),
        };
    }

    private int getMtProxyTimingIndex() {
        for (int i = 0; i < MT_PROXY_TIMING_OPTIONS.length; i++) {
            if (MT_PROXY_TIMING_OPTIONS[i] == SharedConfig.mtProxyTimingMode) {
                return i;
            }
        }
        return 0;
    }

    private String[] getMtProxyTimingLabels() {
        return new String[] {
                getString(R.string.MtProxyTimingOff),
                getString(R.string.MtProxyTimingGentle),
                getString(R.string.MtProxyTimingBalanced),
        };
    }

    private int getMtProxyStartupCoverIndex() {
        for (int i = 0; i < MT_PROXY_STARTUP_COVER_OPTIONS.length; i++) {
            if (MT_PROXY_STARTUP_COVER_OPTIONS[i] == SharedConfig.mtProxyStartupCoverMode) {
                return i;
            }
        }
        return 0;
    }

    private String[] getMtProxyStartupCoverLabels() {
        return new String[] {
                getString(R.string.MtProxyStartupCoverOff),
                getString(R.string.MtProxyStartupCoverSoft),
                getString(R.string.MtProxyStartupCoverStrict),
        };
    }

    private int getMtProxyConnectionPatternIndex() {
        for (int i = 0; i < MT_PROXY_CONNECTION_PATTERN_OPTIONS.length; i++) {
            if (MT_PROXY_CONNECTION_PATTERN_OPTIONS[i] == SharedConfig.mtProxyConnectionPatternMode) {
                return i;
            }
        }
        return 0;
    }

    private String[] getMtProxyConnectionPatternLabels() {
        return new String[] {
                getString(R.string.MtProxyConnectionPatternOff),
                getString(R.string.MtProxyConnectionPatternSoft),
                getString(R.string.MtProxyConnectionPatternBrowser),
                getString(R.string.MtProxyConnectionPatternQuiet),
                getString(R.string.MtProxyConnectionPatternStrict),
        };
    }

    private void updateRows(boolean notify) {
        rowCount = 0;
        useProxyRow = rowCount++;
        if (useProxySettings && SharedConfig.currentProxy != null && SharedConfig.currentProxy.settings.getType() != ProxySettings.Type.WEB && SharedConfig.proxyList.size() > 1 && IS_PROXY_ROTATION_AVAILABLE) {
            rotationRow = rowCount++;
            if (SharedConfig.proxyRotationEnabled) {
                rotationTimeoutRow = rowCount++;
                rotationTimeoutInfoRow = rowCount++;
            } else {
                rotationTimeoutRow = -1;
                rotationTimeoutInfoRow = -1;
            }
        } else {
            rotationRow = -1;
            rotationTimeoutRow = -1;
            rotationTimeoutInfoRow = -1;
        }
        if (useProxySettings && SharedConfig.currentProxy != null && SharedConfig.currentProxy.settings.getType() == ProxySettings.Type.MTPROTO) {
            tlsProfileRow = rowCount++;
            tlsProfileInfoRow = rowCount++;
            clientHelloFragmentationRow = rowCount++;
            clientHelloFragmentationInfoRow = rowCount++;
            mtProxySoftMuxRow = rowCount++;
            mtProxySoftMuxInfoRow = rowCount++;
            mtProxyConnectionPatternRow = rowCount++;
            mtProxyConnectionPatternInfoRow = rowCount++;
            mtProxyRecordSizingRow = rowCount++;
            mtProxyRecordSizingInfoRow = rowCount++;
            mtProxyTimingRow = rowCount++;
            mtProxyTimingInfoRow = rowCount++;
            mtProxyStartupCoverRow = rowCount++;
            mtProxyStartupCoverInfoRow = rowCount++;
        } else {
            tlsProfileRow = -1;
            tlsProfileInfoRow = -1;
            clientHelloFragmentationRow = -1;
            clientHelloFragmentationInfoRow = -1;
            mtProxySoftMuxRow = -1;
            mtProxySoftMuxInfoRow = -1;
            mtProxyConnectionPatternRow = -1;
            mtProxyConnectionPatternInfoRow = -1;
            mtProxyRecordSizingRow = -1;
            mtProxyRecordSizingInfoRow = -1;
            mtProxyTimingRow = -1;
            mtProxyTimingInfoRow = -1;
            mtProxyStartupCoverRow = -1;
            mtProxyStartupCoverInfoRow = -1;
        }
        if (rotationTimeoutInfoRow == -1 && tlsProfileInfoRow == -1 && clientHelloFragmentationInfoRow == -1 && mtProxySoftMuxInfoRow == -1 && mtProxyConnectionPatternInfoRow == -1 && mtProxyRecordSizingInfoRow == -1 && mtProxyTimingInfoRow == -1 && mtProxyStartupCoverInfoRow == -1) {
            useProxyShadowRow = rowCount++;
        } else {
            useProxyShadowRow = -1;
        }
        wssTransportHeaderRow = rowCount++;
        wssTransportRow = rowCount++;
        callRelayTcpTlsRow = rowCount++;
        wssTransportInfoRow = rowCount++;
        connectionsHeaderRow = rowCount++;

        if (notify) {
            proxyList.clear();
            proxyList.addAll(SharedConfig.proxyList);
            ProxyCheckScheduler.clearDetachedCheckStates(proxyList, "proxy_list_passive");

            // ZaStoGram: ручной порядок прокси — авто-сортировка по пингу убрана, список идёт как в SharedConfig.proxyList
        }

        proxyAddRow = rowCount++;
        if (!proxyList.isEmpty()) {
            proxyStartRow = rowCount;
            rowCount += proxyList.size();
            proxyEndRow = rowCount;
        } else {
            proxyStartRow = -1;
            proxyEndRow = -1;
        }
        proxyShadowRow = rowCount++;
        if (proxyList.size() >= 10) {
            deleteAllRow = rowCount++;
        } else {
            deleteAllRow = -1;
        }
        updateProxyActionBarStatus();
        if (notify && listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void updateProxyActionBarStatus() {
        if (actionBar == null) {
            return;
        }
        if (isWssTransportSelected()) {
            actionBar.setSubtitle(getString(R.string.WssTransportHeader) + ": " + getString(R.string.WssTransportOfficial));
            return;
        }
        if (useProxySettings && ProxyWssFallback.isEngaged()) {
            actionBar.setSubtitle(getString(R.string.WssTransportFallbackActive));
            return;
        }
        actionBar.setSubtitle(ProxyCheckDiagnostics.headerStatusText(SharedConfig.currentProxy, useProxySettings, currentConnectionState));
    }

    private void updateCurrentProxyStatusCell() {
        updateProxyActionBarStatus();
        SharedConfig.ProxyInfo selectedProxy = SharedConfig.currentProxy;
        if (listView == null || selectedProxy == null || proxyStartRow < 0) {
            return;
        }
        int idx = proxyList.indexOf(selectedProxy);
        if (idx < 0) {
            return;
        }
        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(idx + proxyStartRow);
        if (holder != null && holder.itemView instanceof TextDetailProxyCell) {
            TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
            cell.updateStatus();
        }
    }

    // ----- Live download-speed sampling for the active proxy -----
    // Reads the cumulative traffic counters and shows the delta as a throughput estimate.
    // Passive (no extra data is sent); only the currently connected proxy is measured.
    private static final long PROXY_SPEED_SAMPLE_INTERVAL_MS = 2000L;
    // While the proxy list is in the foreground, ping the active DC more often so the live ping
    // updates quickly; reverted to the default (~19s) cadence on pause.
    private static final int LIVE_PING_FOREGROUND_INTERVAL_MS = 4000;

    private Runnable proxySpeedSampler;
    private long proxySpeedLastBytes;
    private long proxySpeedLastTime;
    private SharedConfig.ProxyInfo proxySpeedActive;

    private long proxyTrafficBytes() {
        StatsController stats = StatsController.getInstance(currentAccount);
        long total = 0;
        for (int networkType = 0; networkType < 3; networkType++) {
            total += stats.getReceivedBytesCount(networkType, StatsController.TYPE_TOTAL);
            total += stats.getSentBytesCount(networkType, StatsController.TYPE_TOTAL);
        }
        return total;
    }

    private void startProxySpeedSampler() {
        stopProxySpeedSampler();
        proxySpeedLastBytes = proxyTrafficBytes();
        proxySpeedLastTime = SystemClock.elapsedRealtime();
        proxySpeedSampler = new Runnable() {
            @Override
            public void run() {
                sampleProxySpeedOnce();
                AndroidUtilities.runOnUIThread(this, PROXY_SPEED_SAMPLE_INTERVAL_MS);
            }
        };
        AndroidUtilities.runOnUIThread(proxySpeedSampler, PROXY_SPEED_SAMPLE_INTERVAL_MS);
    }

    private void stopProxySpeedSampler() {
        if (proxySpeedSampler != null) {
            AndroidUtilities.cancelRunOnUIThread(proxySpeedSampler);
            proxySpeedSampler = null;
        }
    }

    private void sampleProxySpeedOnce() {
        SharedConfig.ProxyInfo active = useProxySettings ? SharedConfig.currentProxy : null;
        long now = SystemClock.elapsedRealtime();
        long bytes = proxyTrafficBytes();
        if (active != proxySpeedActive) {
            // Active proxy changed: rebase the counters so the previous proxy's traffic is not
            // attributed to the new one as a spurious first-sample speed spike.
            proxySpeedActive = active;
            proxySpeedLastBytes = bytes;
            proxySpeedLastTime = now;
            if (active != null) {
                active.downloadSpeed = 0;
            }
            updateCurrentProxyStatusCell();
            return;
        }
        long dt = now - proxySpeedLastTime;
        long deltaBytes = Math.max(0, bytes - proxySpeedLastBytes);
        proxySpeedLastBytes = bytes;
        proxySpeedLastTime = now;
        if (active == null || dt < 500) {
            return;
        }
        boolean connected = currentConnectionState == ConnectionsManager.ConnectionStateConnected
                || currentConnectionState == ConnectionsManager.ConnectionStateUpdating;
        if (!connected) {
            if (active.downloadSpeed != 0) {
                active.downloadSpeed = 0;
                updateCurrentProxyStatusCell();
            }
            return;
        }
        long bytesPerSecond = deltaBytes * 1000L / dt;
        long previous = active.downloadSpeed;
        if (bytesPerSecond < 1024) {
            // Near-zero traffic (keepalive/ping only) counts as idle, so the speed segment clears.
            active.downloadSpeed = 0;
        } else {
            // Light smoothing so the number does not jump wildly between samples.
            active.downloadSpeed = previous <= 0 ? bytesPerSecond : (previous + bytesPerSecond) / 2;
        }
        active.speedUpdateTime = now;
        // Repaint every tick so the live ping (currentPingTimeLive) stays current, not only on speed change.
        updateCurrentProxyStatusCell();
    }

    private void refreshProxyRow(SharedConfig.ProxyInfo info) {
        if (listView == null || info == null || proxyStartRow < 0) {
            return;
        }
        int idx = proxyList.indexOf(info);
        if (idx < 0) {
            return;
        }
        RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(idx + proxyStartRow);
        if (holder != null && holder.itemView instanceof TextDetailProxyCell) {
            ((TextDetailProxyCell) holder.itemView).updateStatus();
        }
    }

    // Looks up the proxy's country + network owner from the offline GeoIP table (once per proxy).
    private void ensureProxyGeoResolved(SharedConfig.ProxyInfo info) {
        if (info == null || info.geoResolved || TextUtils.isEmpty(info.settings.getAddress())) {
            return;
        }
        info.geoResolved = true;
        final SharedConfig.ProxyInfo target = info;
        ProxyGeoIp.resolveAsync(info.settings.getAddress(), result -> {
            if (result == null) {
                return;
            }
            boolean changed = false;
            if (!TextUtils.isEmpty(result.country) && !result.country.equals(target.geoCountry)) {
                target.geoCountry = result.country;
                changed = true;
            }
            if (!TextUtils.isEmpty(result.owner) && !result.owner.equals(target.geoOwner)) {
                target.geoOwner = result.owner;
                changed = true;
            }
            if (changed) {
                refreshProxyRow(target);
                SharedConfig.ProxyInfo active = useProxySettings ? SharedConfig.currentProxy : null;
                if (target == active) {
                    updateProxyActionBarStatus();
                }
            }
        });
    }

    private void markConnectedCurrentProxyIfNeeded() {
        SharedConfig.ProxyInfo selectedProxy = SharedConfig.currentProxy;
        if (!useProxySettings || selectedProxy == null) {
            return;
        }
        if (currentConnectionState == ConnectionsManager.ConnectionStateConnected || currentConnectionState == ConnectionsManager.ConnectionStateUpdating) {
            ProxyCheckScheduler.markConnected(selectedProxy);
            checkCurrentProxyPingIfNeeded(selectedProxy);
        }
    }

    private void checkCurrentProxyPingIfNeeded(SharedConfig.ProxyInfo selectedProxy) {
        if (selectedProxy == null || selectedProxy.checking || ProxyCheckScheduler.isFresh(selectedProxy)) {
            return;
        }
        boolean started = ProxyCheckScheduler.enqueueNow(currentAccount, selectedProxy, this, new ProxyCheckScheduler.Callback() {
            @Override
            public void onProxyChecked(SharedConfig.ProxyInfo proxyInfo, long time, String diagnostic) {
                updateCurrentProxyStatusCell();
            }

            @Override
            public void onProxyCheckQueueFinished() {
                updateCurrentProxyStatusCell();
            }
        });
        if (started) {
            updateCurrentProxyStatusCell();
        }
    }

    @Override
    protected void onDialogDismiss(Dialog dialog) {
        DownloadController.getInstance(currentAccount).checkAutodownloadSettings();
    }

    @Override
    public void onResume() {
        super.onResume();
        markConnectedCurrentProxyIfNeeded();
        updateCurrentProxyStatusCell();
        startProxySpeedSampler();
        ConnectionsManager.getInstance(currentAccount).setLivePingInterval(LIVE_PING_FOREGROUND_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopProxySpeedSampler();
        ConnectionsManager.getInstance(currentAccount).setLivePingInterval(0);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyChangedByRotation) {
            skipNextProxySettingsChangedLayout = true;
            listView.forAllChild(view -> {
                RecyclerView.ViewHolder holder = listView.getChildViewHolder(view);
                if (holder.itemView instanceof TextDetailProxyCell) {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    cell.setChecked(isProxySelectedForCurrentMode(cell.currentInfo));
                    cell.updateStatus();
                }
            });

            updateRows(false);
            updateCurrentProxyStatusCell();
        } else if (id == NotificationCenter.proxySettingsChanged) {
            if (skipNextProxySettingsChangedLayout) {
                skipNextProxySettingsChangedLayout = false;
                updateRows(false);
                updateCurrentProxyStatusCell();
                return;
            }
            updateRows(true);
        } else if (id == NotificationCenter.proxyConnectionStageChanged) {
            SharedConfig.ProxyInfo selectedProxy = SharedConfig.currentProxy;
            if (args == null || args.length < 2 || !(args[1] instanceof String)) {
                return;
            }
            String endpointKey = (String) args[1];
            if (!ProxyCheckScheduler.matchesEndpointStageKey(selectedProxy, endpointKey)) {
                return;
            }
            updateCurrentProxyStatusCell();
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (currentConnectionState != state) {
                currentConnectionState = state;
                markConnectedCurrentProxyIfNeeded();
                updateCurrentProxyStatusCell();
            }
        } else if (id == NotificationCenter.proxyCheckDone) {
            if (listView != null) {
                SharedConfig.ProxyInfo proxyInfo = (SharedConfig.ProxyInfo) args[0];
                SharedConfig.ProxyInfo selectedProxy = SharedConfig.currentProxy;
                int idx = proxyList.indexOf(proxyInfo);
                if (idx >= 0) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(idx + proxyStartRow);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.updateStatus();
                    }
                }
                if (proxyInfo == selectedProxy) {
                    updateProxyActionBarStatus();
                }

                boolean checking = false;
                if (!wasCheckedAllList) {
                    for (SharedConfig.ProxyInfo info : proxyList) {
                        if (info.checking || !ProxyCheckScheduler.isFresh(info)) {
                            checking = true;
                            break;
                        }
                    }
                    if (!checking) {
                        wasCheckedAllList = true;
                    }
                }
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final static int VIEW_TYPE_SHADOW = 0,
            VIEW_TYPE_TEXT_SETTING = 1,
            VIEW_TYPE_HEADER = 2,
            VIEW_TYPE_TEXT_CHECK = 3,
            VIEW_TYPE_INFO = 4,
            VIEW_TYPE_PROXY_DETAIL = 5,
            VIEW_TYPE_SLIDE_CHOOSER = 6;

        public static final int PAYLOAD_CHECKED_CHANGED = 0;
        public static final int PAYLOAD_SELECTION_CHANGED = 1;
        public static final int PAYLOAD_SELECTION_MODE_CHANGED = 2;

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;

            setHasStableIds(true);
        }

        public void toggleSelected(int position) {
            if (position < proxyStartRow || position >= proxyEndRow) {
                return;
            }
            SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
            if (selectedItems.contains(info)) {
                selectedItems.remove(info);
            } else {
                selectedItems.add(info);
            }
            notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED);
            checkActionMode();
        }

        public void clearSelected() {
            selectedItems.clear();
            notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_CHANGED);
            checkActionMode();
        }

        private void checkActionMode() {
            int selectedCount = selectedItems.size();
            boolean actionModeShowed = actionBar.isActionModeShowed();
            if (selectedCount > 0) {
                selectedCountTextView.setNumber(selectedCount, actionModeShowed);
                if (!actionModeShowed) {
                    actionBar.showActionMode();
                    notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
                }
            } else if (actionModeShowed) {
                actionBar.hideActionMode();
                notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
            }
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SHADOW: {
                    break;
                }
                case VIEW_TYPE_TEXT_SETTING: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == proxyAddRow) {
                        textCell.setText(getString(R.string.AddProxy), proxyStartRow != -1);
                    } else if (position == deleteAllRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                        textCell.setText(getString(R.string.DeleteAllProxies), false);
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == connectionsHeaderRow) {
                        headerCell.setText(getString(R.string.ProxyConnections));
                    } else if (position == wssTransportHeaderRow) {
                        headerCell.setText(getString(R.string.WssTransportHeader));
                    }
                    break;
                }
                case VIEW_TYPE_TEXT_CHECK: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == useProxyRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxySettings), useProxySettings, rotationRow != -1);
                    } else if (position == rotationRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyRotation), SharedConfig.proxyRotationEnabled, true);
                    } else if (position == clientHelloFragmentationRow) {
                        checkCell.setTextAndCheck(getString(R.string.MtProxyClientHelloFragmentation), SharedConfig.mtProxyClientHelloFragmentation, true);
                    } else if (position == mtProxySoftMuxRow) {
                        checkCell.setTextAndCheck(getString(R.string.MtProxySoftMux), SharedConfig.mtProxySoftMux, true);
                    } else if (position == wssTransportRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseWssTransport), isWssTransportSelected(), true);
                    } else if (position == callRelayTcpTlsRow) {
                        checkCell.setTextAndCheck(getString(R.string.CallRelayTcpTls), SharedConfig.callRelayTcpTlsEnabled, false);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == rotationTimeoutInfoRow) {
                        cell.setText(getString(R.string.ProxyRotationTimeoutInfo));
                    } else if (position == tlsProfileInfoRow) {
                        cell.setText(getString(R.string.MtProxyTlsProfile) + "\n" + getString(R.string.MtProxyTlsProfileInfo));
                    } else if (position == clientHelloFragmentationInfoRow) {
                        cell.setText(getString(R.string.MtProxyClientHelloFragmentationInfo));
                    } else if (position == mtProxySoftMuxInfoRow) {
                        cell.setText(getString(R.string.MtProxySoftMuxInfo));
                    } else if (position == mtProxyConnectionPatternInfoRow) {
                        cell.setText(getString(R.string.MtProxyConnectionPatternInfo));
                    } else if (position == mtProxyRecordSizingInfoRow) {
                        cell.setText(getString(R.string.MtProxyRecordSizingInfo));
                    } else if (position == mtProxyTimingInfoRow) {
                        cell.setText(getString(R.string.MtProxyTimingInfo));
                    } else if (position == mtProxyStartupCoverInfoRow) {
                        cell.setText(getString(R.string.MtProxyStartupCoverInfo));
                    } else if (position == wssTransportInfoRow) {
                        cell.setText(getString(R.string.WssTransportInfo) + "\n\n" + getString(R.string.CallRelayTcpTlsInfo));
                    }
                    break;
                }
                case VIEW_TYPE_PROXY_DETAIL: {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                    cell.setProxy(info);
                    ensureProxyGeoResolved(info);
                    cell.setChecked(isProxySelectedForCurrentMode(info));
                    cell.setItemSelected(selectedItems.contains(proxyList.get(position - proxyStartRow)), false);
                    boolean canReorder = true;
                    cell.setReorderAllowed(canReorder);
                    cell.setReorderTouchListener(canReorder ? (v, event) -> {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            itemTouchHelper.startDrag(holder);
                        }
                        return false;
                    } : null);
                    cell.setSelectionEnabled(!selectedItems.isEmpty(), false);
                    break;
                }
                case VIEW_TYPE_SLIDE_CHOOSER: {
                    if (position == rotationTimeoutRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        ArrayList<Integer> options = new ArrayList<>(ProxyRotationController.ROTATION_TIMEOUTS);
                        String[] values = new String[options.size()];
                        for (int i = 0; i < options.size(); i++) {
                            values[i] = LocaleController.formatString(R.string.ProxyRotationTimeoutSeconds, options.get(i));
                        }
                        chooseView.setCallback(i -> {
                            SharedConfig.proxyRotationTimeout = i;
                            SharedConfig.saveConfig();
                        });
                        chooseView.setOptions(SharedConfig.proxyRotationTimeout, values);
                    } else if (position == tlsProfileRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_TLS_PROFILE_OPTIONS.length) {
                                return;
                            }
                            ConnectionsManager.setMtProxyTlsProfileOverride(MT_PROXY_TLS_PROFILE_OPTIONS[i]);
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyTlsProfileOptionIndex(), getMtProxyTlsProfileOptionLabels());
                    } else if (position == mtProxyConnectionPatternRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_CONNECTION_PATTERN_OPTIONS.length) {
                                return;
                            }
                            SharedConfig.mtProxyConnectionPatternMode = MT_PROXY_CONNECTION_PATTERN_OPTIONS[i];
                            SharedConfig.saveConfig();
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyConnectionPatternIndex(), getMtProxyConnectionPatternLabels());
                    } else if (position == mtProxyRecordSizingRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_RECORD_SIZING_OPTIONS.length) {
                                return;
                            }
                            SharedConfig.mtProxyRecordSizingMode = MT_PROXY_RECORD_SIZING_OPTIONS[i];
                            SharedConfig.saveConfig();
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyRecordSizingIndex(), getMtProxyRecordSizingLabels());
                    } else if (position == mtProxyTimingRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_TIMING_OPTIONS.length) {
                                return;
                            }
                            SharedConfig.mtProxyTimingMode = MT_PROXY_TIMING_OPTIONS[i];
                            SharedConfig.saveConfig();
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyTimingIndex(), getMtProxyTimingLabels());
                    } else if (position == mtProxyStartupCoverRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        chooseView.setCallback(i -> {
                            if (i < 0 || i >= MT_PROXY_STARTUP_COVER_OPTIONS.length) {
                                return;
                            }
                            SharedConfig.mtProxyStartupCoverMode = MT_PROXY_STARTUP_COVER_OPTIONS[i];
                            SharedConfig.saveConfig();
                            reapplyCurrentProxySettings();
                        });
                        chooseView.setOptions(getMtProxyStartupCoverIndex(), getMtProxyStartupCoverLabels());
                    }
                    break;
                }
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List payloads) {
            if (holder.getItemViewType() == VIEW_TYPE_PROXY_DETAIL && !payloads.isEmpty()) {
                TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                if (payloads.contains(PAYLOAD_SELECTION_CHANGED)) {
                    cell.setItemSelected(selectedItems.contains(proxyList.get(position - proxyStartRow)), true);
                }
                if (payloads.contains(PAYLOAD_SELECTION_MODE_CHANGED)) {
                    cell.setSelectionEnabled(!selectedItems.isEmpty(), true);
                }
            } else if (holder.getItemViewType() == VIEW_TYPE_TEXT_CHECK && payloads.contains(PAYLOAD_CHECKED_CHANGED)) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                } else if (position == wssTransportRow) {
                    checkCell.setChecked(isWssTransportSelected());
                } else if (position == callRelayTcpTlsRow) {
                    checkCell.setChecked(SharedConfig.callRelayTcpTlsEnabled);
                }
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            if (viewType == VIEW_TYPE_TEXT_CHECK) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                int position = holder.getAdapterPosition();
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                } else if (position == clientHelloFragmentationRow) {
                    checkCell.setChecked(SharedConfig.mtProxyClientHelloFragmentation);
                } else if (position == mtProxySoftMuxRow) {
                    checkCell.setChecked(SharedConfig.mtProxySoftMux);
                } else if (position == wssTransportRow) {
                    checkCell.setChecked(isWssTransportSelected());
                } else if (position == callRelayTcpTlsRow) {
                    checkCell.setChecked(SharedConfig.callRelayTcpTlsEnabled);
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == useProxyRow || position == rotationRow || position == tlsProfileRow || position == clientHelloFragmentationRow || position == mtProxySoftMuxRow || position == mtProxyConnectionPatternRow || position == mtProxyRecordSizingRow || position == mtProxyTimingRow || position == mtProxyStartupCoverRow || position == wssTransportRow || position == callRelayTcpTlsRow || position == proxyAddRow || position == deleteAllRow || position >= proxyStartRow && position < proxyEndRow;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_TEXT_SETTING:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_TEXT_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_SLIDE_CHOOSER:
                    view = new SlideChooseView(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_PROXY_DETAIL:
                default:
                    view = new TextDetailProxyCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public long getItemId(int position) {
            // Random stable ids, could be anything non-repeating
            if (position == useProxyShadowRow) {
                return -1;
            } else if (position == proxyShadowRow) {
                return -2;
            } else if (position == proxyAddRow) {
                return -3;
            } else if (position == useProxyRow) {
                return -4;
            } else if (position == connectionsHeaderRow) {
                return -6;
            } else if (position == deleteAllRow) {
                return -8;
            } else if (position == rotationRow) {
                return -9;
            } else if (position == rotationTimeoutRow) {
                return -10;
            } else if (position == rotationTimeoutInfoRow) {
                return -11;
            } else if (position == tlsProfileRow) {
                return -12;
            } else if (position == tlsProfileInfoRow) {
                return -13;
            } else if (position == clientHelloFragmentationRow) {
                return -14;
            } else if (position == clientHelloFragmentationInfoRow) {
                return -15;
            } else if (position == mtProxySoftMuxRow) {
                return -16;
            } else if (position == mtProxySoftMuxInfoRow) {
                return -17;
            } else if (position == mtProxyConnectionPatternRow) {
                return -18;
            } else if (position == mtProxyConnectionPatternInfoRow) {
                return -19;
            } else if (position == mtProxyRecordSizingRow) {
                return -20;
            } else if (position == mtProxyRecordSizingInfoRow) {
                return -21;
            } else if (position == mtProxyTimingRow) {
                return -22;
            } else if (position == mtProxyTimingInfoRow) {
                return -23;
            } else if (position == mtProxyStartupCoverRow) {
                return -29;
            } else if (position == mtProxyStartupCoverInfoRow) {
                return -30;
            } else if (position == wssTransportHeaderRow) {
                return -24;
            } else if (position == wssTransportRow) {
                return -25;
            } else if (position == callRelayTcpTlsRow) {
                return -31;
            } else if (position == wssTransportInfoRow) {
                return -26;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                return proxyList.get(position - proxyStartRow).hashCode();
            } else {
                return -7;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == useProxyShadowRow || position == proxyShadowRow) {
                return VIEW_TYPE_SHADOW;
            } else if (position == proxyAddRow || position == deleteAllRow) {
                return VIEW_TYPE_TEXT_SETTING;
            } else if (position == useProxyRow || position == rotationRow || position == clientHelloFragmentationRow || position == mtProxySoftMuxRow || position == wssTransportRow || position == callRelayTcpTlsRow) {
                return VIEW_TYPE_TEXT_CHECK;
            } else if (position == connectionsHeaderRow || position == wssTransportHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == rotationTimeoutRow || position == tlsProfileRow || position == mtProxyConnectionPatternRow || position == mtProxyRecordSizingRow || position == mtProxyTimingRow || position == mtProxyStartupCoverRow) {
                return VIEW_TYPE_SLIDE_CHOOSER;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                return VIEW_TYPE_PROXY_DETAIL;
            } else {
                return VIEW_TYPE_INFO;
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, TextCheckCell.class, HeaderCell.class, TextDetailProxyCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));

//        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextDetailProxyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText6));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGreenText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_text_RedRegular));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"checkImageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));

        return themeDescriptions;
    }
}
