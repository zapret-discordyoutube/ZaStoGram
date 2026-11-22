package org.telegram.messenger;

import java.util.Collections;
import java.util.ArrayList;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.tgnet.TLRPC;
import android.text.TextUtils;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * ZaStoGram local feature overrides.
 *
 * Runtime toggles backed by a SharedPreferences file. Feature code reads the static
 * fields directly (e.g. {@code if (ZaStoPrivacy.KEEP_DELETED)}); settings screens flip them via
 * {@link #set}. Call {@link #load} once at app startup so saved values are applied. Privacy
 * overrides default to enabled, while opt-in interface changes default to disabled.
 */
public final class ZaStoPrivacy {

    private static final String PREFS = "zasto_settings";

    public static final String KEY_KEEP_DELETED = "KEEP_DELETED";
    public static final String KEY_KEEP_EPHEMERAL = "KEEP_EPHEMERAL";
    public static final String KEY_KEEP_EDIT_HISTORY = "KEEP_EDIT_HISTORY";
    public static final String KEY_ALLOW_SAVE_PROTECTED = "ALLOW_SAVE_PROTECTED";
    public static final String KEY_ALLOW_SCREENSHOTS = "ALLOW_SCREENSHOTS";
    public static final String KEY_MUTE_SCREENSHOT_PING = "MUTE_SCREENSHOT_PING";
    public static final String KEY_DISABLE_ADS = "DISABLE_ADS";
    public static final String KEY_HIDE_ALL_CHATS = "HIDE_ALL_CHATS";
    public static final String KEY_SHOW_ZASTOGRAM_PROMO = "SHOW_ZASTOGRAM_PROMO";
    public static final String KEY_GHOST_NO_READ = "GHOST_NO_READ";
    public static final String KEY_GHOST_NO_STORIES = "GHOST_NO_STORIES";
    public static final String KEY_GHOST_NO_ONLINE = "GHOST_NO_ONLINE";
    public static final String KEY_GHOST_NO_TYPING = "GHOST_NO_TYPING";
    public static final String KEY_LOCAL_PREMIUM_EMOJI = "LOCAL_PREMIUM_EMOJI";

    /** Keep messages that the remote side deletes (anti-delete), marked instead of removed. */
    public static boolean KEEP_DELETED = true;

    /** Keep self-destruct / TTL / view-once media; never run the local destruction. */
    public static boolean KEEP_EPHEMERAL = true;

    /** Keep previous versions of remotely-edited messages so the edit history can be viewed. */
    public static boolean KEEP_EDIT_HISTORY = true;

    /** Allow saving/forwarding of content-protected (noforwards) media and stories. */
    public static boolean ALLOW_SAVE_PROTECTED = true;

    /** Do not apply FLAG_SECURE on viewers of other people's content (allow screenshots). */
    public static boolean ALLOW_SCREENSHOTS = true;

    /** Do not send the "took a screenshot" service message to the other party. */
    public static boolean MUTE_SCREENSHOT_PING = true;

    /** Disable Telegram/client sponsored messages, sponsored peers, video ads, and promo dialogs. */
    public static boolean DISABLE_ADS = true;

    /**
     * Keep the ZaStoGram channel pinned as a card on top of the chat list. When off, the
     * channel is an ordinary dialog again and can be archived, muted or left like any other.
     */
    public static boolean SHOW_ZASTOGRAM_PROMO = true;

    /** Ghost mode: do not tell the server that messages were read. The chat still looks read locally. */
    public static boolean GHOST_NO_READ = false;

    /** Ghost mode: do not tell the server that stories were viewed (read marks and view counters). */
    public static boolean GHOST_NO_STORIES = false;

    /** Ghost mode: never send the online status; the client always stays offline. */
    public static boolean GHOST_NO_ONLINE = false;

    /** Ghost mode: do not send "typing", recording and uploading actions. */
    public static boolean GHOST_NO_TYPING = false;

    /**
     * Any animated emoji without Premium. They stay on this device: the server
     * gets the plain text without them (see {@link #wireEntities}).
     */
    public static boolean LOCAL_PREMIUM_EMOJI = true;

    /** Hide the built-in "All Chats" tab when at least one custom chat folder exists. */
    public static boolean HIDE_ALL_CHATS = false;

    public static boolean shouldHideAllChatsTab(int filterCount) {
        return HIDE_ALL_CHATS && filterCount > 1;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Apply saved values. Safe to call early; on any error fields keep their defaults. */
    public static void load() {
        try {
            SharedPreferences p = prefs();
            KEEP_DELETED = p.getBoolean(KEY_KEEP_DELETED, true);
            KEEP_EPHEMERAL = p.getBoolean(KEY_KEEP_EPHEMERAL, true);
            KEEP_EDIT_HISTORY = p.getBoolean(KEY_KEEP_EDIT_HISTORY, true);
            ALLOW_SAVE_PROTECTED = p.getBoolean(KEY_ALLOW_SAVE_PROTECTED, true);
            ALLOW_SCREENSHOTS = p.getBoolean(KEY_ALLOW_SCREENSHOTS, true);
            MUTE_SCREENSHOT_PING = p.getBoolean(KEY_MUTE_SCREENSHOT_PING, true);
            DISABLE_ADS = p.getBoolean(KEY_DISABLE_ADS, true);
            HIDE_ALL_CHATS = p.getBoolean(KEY_HIDE_ALL_CHATS, false);
            SHOW_ZASTOGRAM_PROMO = p.getBoolean(KEY_SHOW_ZASTOGRAM_PROMO, true);
            GHOST_NO_READ = p.getBoolean(KEY_GHOST_NO_READ, false);
            GHOST_NO_STORIES = p.getBoolean(KEY_GHOST_NO_STORIES, false);
            GHOST_NO_ONLINE = p.getBoolean(KEY_GHOST_NO_ONLINE, false);
            GHOST_NO_TYPING = p.getBoolean(KEY_GHOST_NO_TYPING, false);
            LOCAL_PREMIUM_EMOJI = p.getBoolean(KEY_LOCAL_PREMIUM_EMOJI, true);
        } catch (Exception ignore) {
        }
    }

    public static boolean get(String key) {
        switch (key) {
            case KEY_KEEP_DELETED: return KEEP_DELETED;
            case KEY_KEEP_EPHEMERAL: return KEEP_EPHEMERAL;
            case KEY_KEEP_EDIT_HISTORY: return KEEP_EDIT_HISTORY;
            case KEY_ALLOW_SAVE_PROTECTED: return ALLOW_SAVE_PROTECTED;
            case KEY_ALLOW_SCREENSHOTS: return ALLOW_SCREENSHOTS;
            case KEY_MUTE_SCREENSHOT_PING: return MUTE_SCREENSHOT_PING;
            case KEY_DISABLE_ADS: return DISABLE_ADS;
            case KEY_HIDE_ALL_CHATS: return HIDE_ALL_CHATS;
            case KEY_SHOW_ZASTOGRAM_PROMO: return SHOW_ZASTOGRAM_PROMO;
            case KEY_GHOST_NO_READ: return GHOST_NO_READ;
            case KEY_GHOST_NO_STORIES: return GHOST_NO_STORIES;
            case KEY_GHOST_NO_ONLINE: return GHOST_NO_ONLINE;
            case KEY_GHOST_NO_TYPING: return GHOST_NO_TYPING;
            case KEY_LOCAL_PREMIUM_EMOJI: return LOCAL_PREMIUM_EMOJI;
        }
        return false;
    }

    public static void set(String key, boolean value) {
        switch (key) {
            case KEY_KEEP_DELETED: KEEP_DELETED = value; break;
            case KEY_KEEP_EPHEMERAL: KEEP_EPHEMERAL = value; break;
            case KEY_KEEP_EDIT_HISTORY: KEEP_EDIT_HISTORY = value; break;
            case KEY_ALLOW_SAVE_PROTECTED: ALLOW_SAVE_PROTECTED = value; break;
            case KEY_ALLOW_SCREENSHOTS: ALLOW_SCREENSHOTS = value; break;
            case KEY_MUTE_SCREENSHOT_PING: MUTE_SCREENSHOT_PING = value; break;
            case KEY_DISABLE_ADS: DISABLE_ADS = value; break;
            case KEY_HIDE_ALL_CHATS: HIDE_ALL_CHATS = value; break;
            case KEY_SHOW_ZASTOGRAM_PROMO: SHOW_ZASTOGRAM_PROMO = value; break;
            case KEY_GHOST_NO_READ: GHOST_NO_READ = value; break;
            case KEY_GHOST_NO_STORIES: GHOST_NO_STORIES = value; break;
            case KEY_GHOST_NO_ONLINE: GHOST_NO_ONLINE = value; break;
            case KEY_GHOST_NO_TYPING: GHOST_NO_TYPING = value; break;
            case KEY_LOCAL_PREMIUM_EMOJI: LOCAL_PREMIUM_EMOJI = value; break;
            default: return;
        }
        try {
            prefs().edit().putBoolean(key, value).apply();
        } catch (Exception ignore) {
        }
    }

    private static boolean canSendCustomEmoji(int account, long dialogId, long documentId) {
        TLRPC.Document document = AnimatedEmojiDrawable.findDocument(account, documentId);
        if (MessageObject.isFreeEmoji(document)) {
            return true;
        }
        TLRPC.ChatFull chatFull = MessagesController.getInstance(account).getChatFull(-dialogId);
        if (chatFull != null && chatFull.emojiset != null) {
            TLRPC.TL_messages_stickerSet set = MediaDataController.getInstance(account).getGroupStickerSetById(chatFull.emojiset);
            if (set != null && set.documents != null) {
                for (int a = 0; a < set.documents.size(); a++) {
                    if (set.documents.get(a).id == documentId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Entities for the server: custom emoji that a non-Premium account may not
     * send are dropped. The local message keeps the full list.
     */
    public static ArrayList<TLRPC.MessageEntity> wireEntities(int account, long dialogId, ArrayList<TLRPC.MessageEntity> entities) {
        if (!LOCAL_PREMIUM_EMOJI || entities == null || entities.isEmpty()
                || UserConfig.getInstance(account).isPremium()
                || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return entities;
        }
        ArrayList<TLRPC.MessageEntity> result = null;
        for (int a = 0; a < entities.size(); a++) {
            TLRPC.MessageEntity entity = entities.get(a);
            if (entity instanceof TLRPC.TL_messageEntityCustomEmoji
                    && !canSendCustomEmoji(account, dialogId, ((TLRPC.TL_messageEntityCustomEmoji) entity).document_id)) {
                if (result == null) {
                    result = new ArrayList<>(entities.subList(0, a));
                }
                continue;
            }
            if (result != null) {
                result.add(entity);
            }
        }
        return result != null ? result : entities;
    }

    /**
     * The server's reply comes without the custom emoji it never received.
     * Put the local ones back when the reply is the same text we sent.
     */
    public static ArrayList<TLRPC.MessageEntity> keepLocalEmoji(int account, TLRPC.Message local, String receivedText, ArrayList<TLRPC.MessageEntity> received) {
        if (!LOCAL_PREMIUM_EMOJI || local == null || local.entities == null || local.entities.isEmpty()
                || UserConfig.getInstance(account).isPremium()
                || !TextUtils.equals(local.message, receivedText)) {
            return received;
        }
        ArrayList<TLRPC.MessageEntity> result = received != null ? new ArrayList<>(received) : new ArrayList<>();
        boolean added = false;
        for (int a = 0; a < local.entities.size(); a++) {
            TLRPC.MessageEntity entity = local.entities.get(a);
            if (!(entity instanceof TLRPC.TL_messageEntityCustomEmoji)) {
                continue;
            }
            boolean same = false;
            for (int b = 0; b < result.size(); b++) {
                TLRPC.MessageEntity other = result.get(b);
                if (other instanceof TLRPC.TL_messageEntityCustomEmoji && other.offset == entity.offset && other.length == entity.length) {
                    same = true;
                    break;
                }
            }
            if (!same) {
                result.add(entity);
                added = true;
            }
        }
        if (!added) {
            return received;
        }
        Collections.sort(result, (x, y) -> x.offset != y.offset ? Integer.compare(x.offset, y.offset) : Integer.compare(y.length, x.length));
        return result;
    }

    private ZaStoPrivacy() {
    }
}
