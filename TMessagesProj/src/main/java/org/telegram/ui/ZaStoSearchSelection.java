package org.telegram.ui;

import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ZaSto: выделение сообщений в списке результатов поиска чата (поиск по хэштегу, «Мои сообщения»,
 * «Публичные посты», посты канала по тегу и обычный поиск «списком»).
 *
 * Список результатов рисует {@code MessagesSearchAdapter} (строки DialogCell), поэтому штатный механизм
 * выделения сообщений чата ({@code ChatActivity.selectedMessagesIds}) сюда не подходит: он завязан на
 * ChatMessageCell и на один диалог, а результаты поиска по хэштегу приходят из разных чатов. Здесь тот же
 * приём, что и в глобальном поиске ({@code SearchViewPager}): ключ {@link FilteredSearchView.MessageHashId},
 * отдельный action mode ActionBar с тегом, пересылка через DialogsActivity. Один экземпляр на корневой
 * ChatActivity (или на HashtagActivity) обслуживает все страницы результатов.
 *
 * Логика вынесена сюда, в ChatActivity/MessagesSearchAdapter/HashtagActivity оставлены только короткие вызовы.
 */
public class ZaStoSearchSelection {

    /** Столько же, сколько в штатном выделении ChatActivity и в SearchViewPager; и ровно один запрос forwardMessages. */
    public static final int MAX_SELECTED = 100;

    private static final int ITEM_SELECT_ALL = 9501;
    private static final int ITEM_FORWARD = 9502;
    private static final String ACTION_MODE_TAG = "zasto_search_selection";
    /** Если страница результатов не пришла за это время, «выбрать все» перестаёт ждать. */
    private static final long SELECT_ALL_TIMEOUT_MS = 15000;

    private final ChatActivity owner;
    private final LinkedHashMap<FilteredSearchView.MessageHashId, MessageObject> selected = new LinkedHashMap<>();

    private ChatActivity page;
    private boolean active;
    private boolean selectAllPending;
    private boolean loadMoreScheduled;

    private NumberTextView counterView;
    private ActionBarMenuItem selectAllItem;

    private final Runnable selectAllTimeout = () -> {
        selectAllPending = false;
        updateSelectAllItem();
    };

    public ZaStoSearchSelection(ChatActivity owner) {
        this.owner = owner;
    }

    private BaseFragment host() {
        return owner.zaSearchSelectionHostFragment();
    }

    private static FilteredSearchView.MessageHashId key(MessageObject m) {
        // у результатов поиска по хэштегу id подменён на сгенерированный, настоящий лежит в realId
        return new FilteredSearchView.MessageHashId(m.getRealId(), m.getDialogId());
    }

    public boolean isRowSelected(MessageObject m) {
        return m != null && !selected.isEmpty() && selected.containsKey(key(m));
    }

    /** Выделение активно (и action mode ещё на экране). Если action mode погасили снаружи, выделение сбрасывается. */
    public boolean isActive() {
        if (!active) {
            return false;
        }
        final ActionBar actionBar = host().getActionBar();
        if (actionBar == null || !actionBar.isActionModeShowed(ACTION_MODE_TAG)) {
            clear();
            return false;
        }
        return true;
    }

    /** Долгое нажатие на строку результата. Возвращает true, если нажатие обработано. */
    public boolean onItemLongClick(ChatActivity fromPage, View view, Object item) {
        if (!(item instanceof MessageObject)) {
            return false;
        }
        if (isActive() && page != fromPage) {
            clear();
        }
        if (!isActive() && !start(fromPage)) {
            return false;
        }
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
        toggle(fromPage, (MessageObject) item);
        return true;
    }

    /** Обычный тап по строке результата. Пока выделение активно, он отмечает/снимает строку. */
    public boolean onItemClick(ChatActivity fromPage, Object item) {
        if (!isActive() || page != fromPage) {
            return false;
        }
        if (item instanceof MessageObject) {
            toggle(fromPage, (MessageObject) item);
        }
        return true;
    }

    /** Результаты страницы изменились (пришла следующая порция): дорабатываем «выбрать все». */
    public void onResultsChanged(ChatActivity changedPage) {
        if (!selectAllPending || !active || changedPage != page) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(selectAllTimeout);
        AndroidUtilities.runOnUIThread(selectAllTimeout, SELECT_ALL_TIMEOUT_MS);
        addLoadedResults();
        updateCounter(true);
        page.zaRefreshSearchSelectionRows(true);
        continueSelectAll();
    }

    /** Клики по кнопкам action mode (и по «назад»). Возвращает true, если клик обработан. */
    public boolean onActionBarItemClick(int id) {
        if (!isActive()) {
            return false;
        }
        if (id == -1) {
            clear();
            return true;
        } else if (id == ITEM_SELECT_ALL) {
            selectAll();
            return true;
        } else if (id == ITEM_FORWARD) {
            forward();
            return true;
        }
        return false;
    }

    /** Сбросить выделение и закрыть action mode. */
    public void clear() {
        final boolean wasActive = active;
        final ChatActivity wasPage = page;
        active = false;
        selectAllPending = false;
        loadMoreScheduled = false;
        AndroidUtilities.cancelRunOnUIThread(selectAllTimeout);
        selected.clear();
        if (wasActive) {
            final ActionBar actionBar = host().getActionBar();
            if (actionBar != null && actionBar.isActionModeShowed(ACTION_MODE_TAG)) {
                actionBar.hideActionMode();
            }
        }
        if (wasPage != null) {
            wasPage.zaRefreshSearchSelectionRows(true);
        }
        page = null;
    }

    // ---------------------------------------------------------------------------------------------

    private boolean start(ChatActivity fromPage) {
        final BaseFragment host = host();
        final ActionBar actionBar = host.getActionBar();
        if (actionBar == null || host.getParentActivity() == null) {
            return false;
        }
        if (actionBar.isActionModeShowed() && !actionBar.isActionModeShowed(ACTION_MODE_TAG)) {
            // уже идёт другое выделение (например, сообщений самого чата)
            return false;
        }
        createActionModeIfNeeded(host, actionBar);
        page = fromPage;
        active = true;
        AndroidUtilities.hideKeyboard(host.getParentActivity().getCurrentFocus());
        actionBar.showActionMode();
        updateSelectAllItem();
        return true;
    }

    private void createActionModeIfNeeded(BaseFragment host, ActionBar actionBar) {
        if (actionBar.actionModeIsExist(ACTION_MODE_TAG) && counterView != null) {
            return;
        }
        if (host instanceof ChatActivity) {
            // action mode с тегом заменяет штатный action mode чата: пусть чат пересоздаст свой при следующем выделении
            ((ChatActivity) host).zaOnActionModeReplaced();
        }
        final ActionBarMenu actionMode = actionBar.createActionMode(true, ACTION_MODE_TAG);

        counterView = new NumberTextView(actionMode.getContext());
        counterView.setTextSize(18);
        counterView.setTypeface(AndroidUtilities.bold());
        counterView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        counterView.setOnTouchListener((v, event) -> true);
        actionMode.addView(counterView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, host instanceof ChatActivity ? 85 : 72, 0, 0, 0));

        selectAllItem = actionMode.addItemWithWidth(ITEM_SELECT_ALL, R.drawable.msg_select, AndroidUtilities.dp(54), LocaleController.getString(R.string.SelectAll));
        actionMode.addItemWithWidth(ITEM_FORWARD, R.drawable.msg_forward, AndroidUtilities.dp(54), LocaleController.getString(R.string.Forward));
    }

    private void updateCounter(boolean animated) {
        if (counterView != null) {
            counterView.setNumber(selected.size(), animated);
        }
    }

    private void updateSelectAllItem() {
        if (selectAllItem != null) {
            selectAllItem.setEnabled(!selectAllPending);
            selectAllItem.setAlpha(selectAllPending ? 0.5f : 1.0f);
        }
    }

    /** Сообщение вместе с остальными частями альбома (в списке они могут быть скрыты). */
    private ArrayList<MessageObject> withGroup(ChatActivity fromPage, MessageObject m) {
        final ArrayList<MessageObject> result = new ArrayList<>();
        result.add(m);
        final long groupId = m.getGroupId();
        if (groupId != 0) {
            final ArrayList<MessageObject> source = fromPage.zaGetSearchResults();
            if (source != null) {
                for (int i = 0, n = source.size(); i < n; i++) {
                    final MessageObject other = source.get(i);
                    if (other != m && other.getGroupId() == groupId && other.getDialogId() == m.getDialogId()) {
                        result.add(other);
                    }
                }
            }
        }
        return result;
    }

    private void toggle(ChatActivity fromPage, MessageObject m) {
        final ArrayList<MessageObject> group = withGroup(fromPage, m);
        if (isRowSelected(m)) {
            for (int i = 0; i < group.size(); i++) {
                selected.remove(key(group.get(i)));
            }
        } else {
            int need = 0;
            for (int i = 0; i < group.size(); i++) {
                if (!selected.containsKey(key(group.get(i)))) {
                    need++;
                }
            }
            if (selected.size() + need > MAX_SELECTED) {
                if (counterView != null) {
                    AndroidUtilities.shakeView(counterView);
                }
                showInfo(LocaleController.formatString(R.string.ZaStoSearchSelectLimit, MAX_SELECTED));
                return;
            }
            for (int i = 0; i < group.size(); i++) {
                selected.put(key(group.get(i)), group.get(i));
            }
        }
        if (selected.isEmpty()) {
            clear();
            return;
        }
        updateCounter(true);
        fromPage.zaRefreshSearchSelectionRows(true);
    }

    // ---------------------------------------------------------------------------------------------
    // «Выбрать все»: сначала всё уже загруженное, затем страница за страницей догружаем результаты
    // поиска (тем же запросом, что и прокрутка списка) до MAX_SELECTED или до конца выдачи.

    private void selectAll() {
        if (!active || page == null) {
            return;
        }
        selectAllPending = true;
        AndroidUtilities.cancelRunOnUIThread(selectAllTimeout);
        AndroidUtilities.runOnUIThread(selectAllTimeout, SELECT_ALL_TIMEOUT_MS);
        addLoadedResults();
        updateCounter(true);
        page.zaRefreshSearchSelectionRows(true);
        updateSelectAllItem();
        continueSelectAll();
    }

    /** Добавляет в выделение всё загруженное в списке, пока не упрёмся в MAX_SELECTED. */
    private void addLoadedResults() {
        final ArrayList<MessageObject> source = page.zaGetSearchResults();
        if (source == null) {
            return;
        }
        for (int i = 0, n = source.size(); i < n && selected.size() < MAX_SELECTED; i++) {
            final MessageObject m = source.get(i);
            if (m == null || m.messageOwner == null) {
                continue;
            }
            final FilteredSearchView.MessageHashId k = key(m);
            if (!selected.containsKey(k)) {
                selected.put(k, m);
            }
        }
    }

    private void continueSelectAll() {
        if (!selectAllPending) {
            return;
        }
        final boolean limitHit = selected.size() >= MAX_SELECTED;
        if (!limitHit && page.zaSearchHasMore()) {
            if (!loadMoreScheduled) {
                loadMoreScheduled = true;
                AndroidUtilities.runOnUIThread(() -> {
                    loadMoreScheduled = false;
                    if (selectAllPending && active && page != null) {
                        page.zaLoadMoreSearchResults();
                    }
                });
            }
            return;
        }
        selectAllPending = false;
        AndroidUtilities.cancelRunOnUIThread(selectAllTimeout);
        updateSelectAllItem();
        final int total = page.zaSearchTotalCount();
        if (limitHit && total > selected.size()) {
            showInfo(LocaleController.formatString(R.string.ZaStoSearchSelectPartial, selected.size(), total, MAX_SELECTED));
        }
    }

    private void showInfo(CharSequence text) {
        final BaseFragment host = host();
        if (host == null || host.getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(host).createSimpleBulletin(R.raw.chats_infotip, text).show();
    }

    // ---------------------------------------------------------------------------------------------
    // Пересылка

    private boolean canForward(MessageObject m) {
        return m.canForwardMessage() && !MessagesController.getInstance(m.currentAccount).isPeerNoForwards(m.getDialogId());
    }

    /**
     * У результатов поиска по хэштегу в messageOwner.id лежит сгенерированный номер (настоящий id в realId),
     * а SendMessagesHelper пересылает по getId(). Для таких сообщений пересылаем копию с настоящим id.
     */
    private MessageObject toForwardable(MessageObject m) {
        final TLRPC.Message src = m.messageOwner;
        if (src == null) {
            return null;
        }
        final int realId = m.getRealId();
        if (realId <= 0) {
            return null;
        }
        if (realId == src.id) {
            return m;
        }
        try {
            final TLRPC.Message copy = TLObject.deepCopy(src, TLRPC.Message::TLdeserialize);
            if (copy == null) {
                return null;
            }
            copy.id = realId;
            copy.realId = 0;
            copy.dialog_id = m.getDialogId();
            return new MessageObject(m.currentAccount, copy, false, false);
        } catch (Throwable e) {
            return null;
        }
    }

    private void forward() {
        final BaseFragment host = host();
        if (host == null || host.getParentActivity() == null || selected.isEmpty()) {
            return;
        }
        final ArrayList<MessageObject> candidates = new ArrayList<>(selected.values());
        // от старых к новым, как при пересылке из чата
        Collections.sort(candidates, new Comparator<MessageObject>() {
            @Override
            public int compare(MessageObject a, MessageObject b) {
                if (a.messageOwner.date != b.messageOwner.date) {
                    return Integer.compare(a.messageOwner.date, b.messageOwner.date);
                }
                return Integer.compare(a.getRealId(), b.getRealId());
            }
        });
        // сообщения из одного чата идут подряд: SendMessagesHelper отправляет один запрос на каждую такую серию
        final LinkedHashMap<Long, ArrayList<MessageObject>> byDialog = new LinkedHashMap<>();
        int skipped = 0;
        for (int i = 0; i < candidates.size(); i++) {
            final MessageObject m = candidates.get(i);
            final MessageObject f = canForward(m) ? toForwardable(m) : null;
            if (f == null) {
                skipped++;
                continue;
            }
            ArrayList<MessageObject> list = byDialog.get(f.getDialogId());
            if (list == null) {
                byDialog.put(f.getDialogId(), list = new ArrayList<>());
            }
            list.add(f);
        }
        final ArrayList<MessageObject> fmessages = new ArrayList<>();
        for (Map.Entry<Long, ArrayList<MessageObject>> entry : byDialog.entrySet()) {
            fmessages.addAll(entry.getValue());
        }
        if (fmessages.isEmpty()) {
            showInfo(LocaleController.getString(R.string.ZaStoSearchSelectNothingToForward));
            return;
        }
        if (skipped > 0) {
            showInfo(LocaleController.formatString(R.string.ZaStoSearchSelectSkipped, skipped));
        }

        int hasPoll = 0;
        boolean hasInvoice = false;
        for (int i = 0; i < fmessages.size(); i++) {
            final MessageObject m = fmessages.get(i);
            if (m.isTodo()) {
                hasPoll = 3;
            } else if (m.isPoll()) {
                hasPoll = m.isPublicPoll() ? 2 : 1;
                if (hasPoll == 2) {
                    break;
                }
            } else if (m.isInvoice()) {
                hasInvoice = true;
            }
        }

        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putInt("messagesCount", fmessages.size());
        args.putInt("hasPoll", hasPoll);
        args.putBoolean("hasInvoice", hasInvoice);
        args.putBoolean("canSelectTopics", true);
        final DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            final int account = host.getCurrentAccount();
            final MessagesController messagesController = MessagesController.getInstance(account);
            for (int j = 0; j < dids.size(); j++) {
                final TLRPC.Chat chat = messagesController.getChat(-dids.get(j).dialogId);
                if (chat != null) {
                    for (int i = 0; i < fmessages.size(); i++) {
                        final int sendError = SendMessagesHelper.canSendMessageToChat(chat, fmessages.get(i));
                        if (sendError != 0) {
                            AlertsCreator.showSendMediaAlert(sendError, fragment1, null);
                            return false;
                        }
                    }
                }
            }
            clear();
            if (dids.size() > 1 || dids.get(0).dialogId == UserConfig.getInstance(account).getClientUserId() || message != null) {
                for (int a = 0; a < dids.size(); a++) {
                    final long did = dids.get(a).dialogId;
                    if (message != null) {
                        host.getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did, null, null, null, true, null, null, null, notify, scheduleDate, scheduleRepeatPeriod, null, false));
                    }
                    host.getSendMessagesHelper().sendMessage(fmessages, did, false, false, notify, scheduleDate, scheduleRepeatPeriod, null, -1, 0, 0, null);
                }
                fragment1.finishFragment();
                final Bulletin bulletin = BulletinFactory.createForwardedBulletin(host.getParentActivity(),
                        host, null, dids.size(), dids.size() == 1 ? dids.get(0).dialogId : 0, fmessages.size(),
                        Theme.getColor(Theme.key_undo_background), Theme.getColor(Theme.key_undo_infoColor), Bulletin.DURATION_SHORT);
                bulletin.show();
            } else {
                final long did = dids.get(0).dialogId;
                final Bundle args1 = new Bundle();
                args1.putBoolean("scrollToTopOnResume", true);
                if (DialogObject.isEncryptedDialog(did)) {
                    args1.putInt("enc_id", DialogObject.getEncryptedChatId(did));
                } else {
                    if (DialogObject.isUserDialog(did)) {
                        args1.putLong("user_id", did);
                    } else {
                        args1.putLong("chat_id", -did);
                    }
                    if (!messagesController.checkCanOpenChat(args1, fragment1)) {
                        return true;
                    }
                }
                final ChatActivity chatActivity = new ChatActivity(args1);
                ForumUtilities.applyTopic(chatActivity, dids.get(0));
                fragment1.presentFragment(chatActivity, true);
                chatActivity.showFieldPanelForForward(true, fmessages);
            }
            return true;
        });
        host.presentFragment(fragment);
    }
}
