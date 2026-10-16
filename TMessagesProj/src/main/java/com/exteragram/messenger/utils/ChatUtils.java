package com.exteragram.messenger.utils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;

/** exteraGram's ChatUtils subset used by plugins (file path of a message, text, channel lookup). */
public class ChatUtils {

    private static final ChatUtils[] instances = new ChatUtils[UserConfig.MAX_ACCOUNT_COUNT];

    protected final int currentAccount;

    protected ChatUtils(int account) {
        currentAccount = account;
    }

    public static ChatUtils getInstance() {
        return getInstance(UserConfig.selectedAccount);
    }

    public static synchronized ChatUtils getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = UserConfig.selectedAccount;
        }
        if (instances[account] == null) {
            instances[account] = new ChatUtils(account);
        }
        return instances[account];
    }

    /** Absolute path of a message's media file if it is on disk, else null. */
    public String getPathToMessage(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        try {
            if (messageObject.messageOwner != null && messageObject.messageOwner.attachPath != null
                    && messageObject.messageOwner.attachPath.length() > 0) {
                File attach = new File(messageObject.messageOwner.attachPath);
                if (attach.exists()) {
                    return attach.getAbsolutePath();
                }
            }
            File file = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
            if (file != null && file.exists()) {
                return file.getAbsolutePath();
            }
            TLRPC.Document document = messageObject.getDocument();
            if (document != null) {
                File attach = FileLoader.getInstance(currentAccount).getPathToAttach(document, true);
                if (attach != null && attach.exists()) {
                    return attach.getAbsolutePath();
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    public CharSequence getMessageText(MessageObject messageObject, Object ignored) {
        if (messageObject == null) {
            return null;
        }
        if (messageObject.caption != null && messageObject.caption.length() > 0) {
            return messageObject.caption;
        }
        if (messageObject.messageText != null) {
            return messageObject.messageText;
        }
        return messageObject.messageOwner != null ? messageObject.messageOwner.message : null;
    }

    public CharSequence getMessageText(MessageObject messageObject) {
        return getMessageText(messageObject, null);
    }

    /** Resolve @username to its TLRPC.Chat (or TLRPC.User); the callback gets null on failure. */
    public void resolveChannel(String username, Utilities.Callback<Object> callback) {
        if (username == null) {
            if (callback != null) {
                callback.run(null);
            }
            return;
        }
        String name = username.startsWith("@") ? username.substring(1) : username;
        MessagesController controller = MessagesController.getInstance(currentAccount);
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = name;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            Object result = null;
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer peer = (TLRPC.TL_contacts_resolvedPeer) response;
                controller.putUsers(peer.users, false);
                controller.putChats(peer.chats, false);
                AccountInstance.getInstance(currentAccount).getMessagesStorage()
                        .putUsersAndChats(peer.users, peer.chats, false, true);
                if (!peer.chats.isEmpty()) {
                    result = peer.chats.get(0);
                } else if (!peer.users.isEmpty()) {
                    result = peer.users.get(0);
                }
            }
            if (callback != null) {
                callback.run(result);
            }
        }));
    }
}
