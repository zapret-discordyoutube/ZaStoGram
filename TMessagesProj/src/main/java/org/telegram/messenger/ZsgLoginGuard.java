package org.telegram.messenger;

import android.content.Context;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheetTabs;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.bots.BotWebViewAttachedSheet;
import org.telegram.ui.bots.BotWebViewSheet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZaStoGram: protection against the "bot asks for a phone number, then for the login code"
 * account theft. One class, a few one-line hooks elsewhere (same module as on desktop).
 *
 * The scheme: a bot (usually inside a mini app) asks for the phone number and starts a login of
 * that number, Telegram sends the login code to the service chat 777000, and the victim forwards
 * the code, sends it as text or types it on a fake keypad inside the mini app. What this does:
 * <ul>
 * <li>messages of the service chat cannot be forwarded ({@link #blocksForward});</li>
 * <li>a text that contains a login code received in the last hour is never sent anywhere
 * ({@link #checkOutgoing});</li>
 * <li>a login code that arrives while a mini app is open closes every open mini app, offers to
 * block those bots and report them as spam, and leaves a warning in the chat with each of them
 * ({@link #onNewMessages}).</li>
 * </ul>
 */
public final class ZsgLoginGuard {

    public static final long LOGIN_CODE_CHAT_ID = 777000;

    /** A login code is useful to a thief for a short while only, an hour is plenty. */
    private static final long CODE_LIFETIME_MS = 60L * 60L * 1000L;
    /** An old message of the service chat that was just loaded is not a new code. */
    private static final int FRESH_MESSAGE_SECONDS = 5 * 60;

    private static final Pattern CODE = Pattern.compile("(?<!\\d)(\\d{5,6})(?!\\d)");

    private static final ArrayList<String> codeValues = new ArrayList<>();
    private static final ArrayList<Long> codeTimes = new ArrayList<>();

    private ZsgLoginGuard() {
    }

    public static boolean isLoginCodeDialog(long dialogId) {
        return dialogId == LOGIN_CODE_CHAT_ID;
    }

    /** Forwarding anything from the service chat is forbidden. */
    public static boolean blocksForward(MessageObject message) {
        return message != null && isLoginCodeDialog(message.getDialogId());
    }

    public static ArrayList<String> findLoginCodes(CharSequence text) {
        final ArrayList<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        final Matcher matcher = CODE.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    public static synchronized void rememberCode(String code, long nowMs) {
        for (int i = codeValues.size() - 1; i >= 0; i--) {
            if (codeTimes.get(i) + CODE_LIFETIME_MS < nowMs || codeValues.get(i).equals(code)) {
                codeValues.remove(i);
                codeTimes.remove(i);
            }
        }
        codeValues.add(code);
        codeTimes.add(nowMs);
    }

    public static synchronized void forgetCodes() {
        codeValues.clear();
        codeTimes.clear();
    }

    /**
     * Digits may be separated by spaces, dots, commas and dashes; any other character breaks
     * the number: "12345 abc 678" is not a code written with separators.
     */
    private static String onlyDigits(CharSequence text) {
        final StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            if (ch >= '0' && ch <= '9') {
                result.append(ch);
            } else if (!Character.isWhitespace(ch) && ch != '-' && ch != '.' && ch != ',' && ch != '‐' && ch != '‑' && ch != '–') {
                result.append('|');
            }
        }
        return result.toString();
    }

    public static synchronized boolean textHasFreshCode(CharSequence text, long nowMs) {
        if (text == null || text.length() == 0 || codeValues.isEmpty()) {
            return false;
        }
        final String digits = onlyDigits(text);
        for (int i = 0; i < codeValues.size(); i++) {
            if (codeTimes.get(i) + CODE_LIFETIME_MS >= nowMs && digits.contains(codeValues.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** True when sending is cancelled: the text or the caption contains a fresh login code. */
    public static boolean checkOutgoing(CharSequence message, CharSequence caption) {
        final long now = System.currentTimeMillis();
        if (!textHasFreshCode(message, now) && !textHasFreshCode(caption, now)) {
            return false;
        }
        AndroidUtilities.runOnUIThread(() -> {
            final Context context = LaunchActivity.instance;
            if (context == null) {
                return;
            }
            new AlertDialog.Builder(context)
                    .setTitle(pick("Код входа не отправляем", "Login code is not sent"))
                    .setMessage(pick(
                            "В тексте есть ваш код входа в Telegram. Он нужен только для входа в аккаунт, а тому, кто его просит, нужен ваш аккаунт. Сообщение не отправлено.",
                            "The text contains your Telegram login code. It is only for logging in, and whoever asks for it wants your account. The message was not sent."))
                    .setPositiveButton("OK", null)
                    .show();
        });
        return true;
    }

    /** Called for every batch of new messages of a dialog, on the UI thread. */
    public static void onNewMessages(int account, long dialogId, ArrayList<MessageObject> messages) {
        if (!isLoginCodeDialog(dialogId) || messages == null) {
            return;
        }
        final int now = ConnectionsManager.getInstance(account).getCurrentTime();
        boolean found = false;
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject message = messages.get(i);
            if (message == null || message.messageOwner == null || message.isOut()) {
                continue;
            }
            if (Math.abs(now - message.messageOwner.date) > FRESH_MESSAGE_SECONDS) {
                continue;
            }
            for (String code : findLoginCodes(message.messageOwner.message)) {
                rememberCode(code, System.currentTimeMillis());
                found = true;
            }
        }
        if (!found) {
            return;
        }
        final ArrayList<Long> bots = openBotIds(account);
        if (bots.isEmpty()) {
            return;
        }
        closeBotWebViews(account);
        for (long botId : bots) {
            leaveWarning(account, botId);
        }
        askToBlock(account, bots);
    }

    /** Bots whose mini apps are open now, including minimized ones. */
    private static ArrayList<Long> openBotIds(int account) {
        final LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (BotWebViewSheet sheet : new ArrayList<>(BotWebViewSheet.activeSheets)) {
            if (sheet.getBotId() != 0) {
                result.add(sheet.getBotId());
            }
        }
        for (BotWebViewAttachedSheet sheet : new ArrayList<>(BotWebViewAttachedSheet.activeSheets)) {
            if (sheet.getBotId() != 0) {
                result.add(sheet.getBotId());
            }
        }
        final ArrayList<BottomSheetTabs.WebTabData> tabs = BottomSheetTabs.tabs.get(account);
        if (tabs != null) {
            for (BottomSheetTabs.WebTabData tab : new ArrayList<>(tabs)) {
                if (tab.props != null && tab.props.botId != 0) {
                    result.add(tab.props.botId);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static void closeBotWebViews(int account) {
        for (BotWebViewSheet sheet : new ArrayList<>(BotWebViewSheet.activeSheets)) {
            sheet.dismiss(false, null);
        }
        for (BotWebViewAttachedSheet sheet : new ArrayList<>(BotWebViewAttachedSheet.activeSheets)) {
            sheet.dismiss(false, null);
        }
        final ArrayList<BottomSheetTabs.WebTabData> tabs = BottomSheetTabs.tabs.get(account);
        final LaunchActivity activity = LaunchActivity.instance;
        final BottomSheetTabs tabsView = activity != null ? activity.getBottomSheetTabs() : null;
        if (tabs != null && tabsView != null) {
            for (BottomSheetTabs.WebTabData tab : new ArrayList<>(tabs)) {
                if (tab.props != null && tab.props.botId != 0) {
                    tabsView.removeTab(account, tab, true);
                }
            }
        }
    }

    private static void leaveWarning(int account, long botId) {
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.TL_messageService message = new TLRPC.TL_messageService();
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        message.local_id = message.id = UserConfig.getInstance(account).getNewMessageId();
        message.date = ConnectionsManager.getInstance(account).getCurrentTime();
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = botId;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = botId;
        message.dialog_id = botId;
        final TLRPC.TL_messageActionCustomAction action = new TLRPC.TL_messageActionCustomAction();
        action.message = pick(
                "⚠️ ZaStoGram: после запуска этого мини-приложения пришёл код входа в ваш аккаунт, мини-приложение закрыто. Бот, который просит код входа, — мошенник: никому не передавайте такой код.",
                "⚠️ ZaStoGram: a login code for your account arrived while this mini app was open, so it was closed. A bot that asks for a login code is a scammer: never give this code to anyone.");
        message.action = action;
        UserConfig.getInstance(account).saveConfig(false);

        final ArrayList<MessageObject> pushMessages = new ArrayList<>();
        final ArrayList<TLRPC.Message> messagesArr = new ArrayList<>();
        messagesArr.add(message);
        pushMessages.add(new MessageObject(account, message, true, false));
        MessagesStorage.getInstance(account).putMessages(messagesArr, true, true, false, 0, 0, 0);
        // Deferred: this runs inside updateInterfaceWithMessages of the service chat.
        AndroidUtilities.runOnUIThread(() -> controller.updateInterfaceWithMessages(botId, pushMessages, 0));
    }

    private static void askToBlock(int account, ArrayList<Long> bots) {
        final Context context = LaunchActivity.instance;
        if (context == null) {
            // Nobody to ask: the safe part is already done, the bots stay unblocked, so that
            // a legitimate one is not punished silently.
            return;
        }
        final StringBuilder names = new StringBuilder();
        for (long botId : bots) {
            final TLRPC.User user = MessagesController.getInstance(account).getUser(botId);
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(user != null ? ContactsController.formatName(user.first_name, user.last_name) : String.valueOf(botId));
        }
        new AlertDialog.Builder(context)
                .setTitle(pick("Возможная кража аккаунта", "Possible account theft"))
                .setMessage(pick(
                        "Пока было открыто мини-приложение (" + names + "), пришёл код входа в ваш аккаунт Telegram. Так мошенники угоняют аккаунты: бот просит номер, запускает вход и ждёт код. Мини-приложение закрыто, код никому не отправляйте и не вводите.\n\nЗаблокировать бота и пожаловаться на спам?",
                        "A login code for your Telegram account arrived while a mini app (" + names + ") was open. This is how accounts are stolen: the bot asks for a phone number, starts a login and waits for the code. The mini app has been closed; never send or enter this code anywhere.\n\nBlock the bot and report it as spam?"))
                .setPositiveButton(pick("Заблокировать и пожаловаться", "Block and report"), (dialog, which) -> {
                    for (long botId : bots) {
                        blockAndReport(account, botId);
                    }
                })
                .setNegativeButton(pick("Это не мошенник", "It is not a scam"), null)
                .show();
    }

    private static void blockAndReport(int account, long botId) {
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.User user = controller.getUser(botId);
        if (user == null) {
            return;
        }
        controller.blockPeer(botId);
        controller.reportSpam(botId, user, null, null, false);
    }

    private static String pick(String ru, String en) {
        final LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
        final String lang = info != null && info.shortName != null ? info.shortName : "";
        return lang.startsWith("ru") ? ru : en;
    }
}
