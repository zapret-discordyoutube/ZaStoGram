package com.exteragram.messenger.utils.chats;

import org.telegram.messenger.UserConfig;

/** Newer exteraGram package location of ChatUtils (same API). */
public class ChatUtils extends com.exteragram.messenger.utils.ChatUtils {

    private static final ChatUtils[] instances = new ChatUtils[UserConfig.MAX_ACCOUNT_COUNT];

    private ChatUtils(int account) {
        super(account);
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
}
