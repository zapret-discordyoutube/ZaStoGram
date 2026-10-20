package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.util.JsonWriter;

import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Keeps a local copy of an account that Telegram has deleted, terminated or frozen.
 *
 * When the server drops the authorization the client wipes {@code cache4.db}. Everything that was
 * ever loaded is still there until that moment, so before the wipe we copy the database, then
 * build a readable report from the copy (chats, channels, groups, links, messages, files).
 * The archive lives outside the account's own storage and is never touched by a logout.
 */
public final class ZaStoAccountArchive {

    public static final String DIR_NAME = "zasto_archive";
    public static final String REASON_FORCED_LOGOUT = "forced_logout";
    public static final String REASON_FROZEN = "frozen";
    public static final String REASON_MANUAL = "manual";

    private static final int FORMAT = 1;
    private static final long MEDIA_BUDGET = 4L << 30;
    private static final String PREFS = "zasto_archive";
    private static final String KEY_UNSEEN = "unseen";

    private static final Set<Integer> running = new HashSet<>();

    private ZaStoAccountArchive() {
    }

    public static class Entry {
        public File dir;
        public long userId;
        public String name = "";
        public String phone = "";
        public String username = "";
        public String reason = "";
        public long created;
        public boolean ready;
        public int chats;
        public int messages;
        public int files;

        public File report() {
            return new File(dir, "report/index.html");
        }
    }

    public static File root() {
        Context context = ApplicationLoader.applicationContext;
        File base = null;
        try {
            base = context.getExternalFilesDir(null);
        } catch (Exception ignore) {
        }
        if (base == null) {
            base = ApplicationLoader.getFilesDirFixed();
        }
        File root = new File(base, DIR_NAME);
        root.mkdirs();
        return root;
    }

    public static List<Entry> list() {
        ArrayList<Entry> result = new ArrayList<>();
        File[] dirs = root().listFiles();
        if (dirs == null) {
            return result;
        }
        for (File dir : dirs) {
            File metaFile = new File(dir, "meta.json");
            if (!dir.isDirectory() || !metaFile.exists()) {
                continue;
            }
            try {
                JSONObject meta = new JSONObject(readText(metaFile));
                Entry entry = new Entry();
                entry.dir = dir;
                entry.userId = meta.optLong("user_id");
                entry.name = meta.optString("name");
                entry.phone = meta.optString("phone");
                entry.username = meta.optString("username");
                entry.reason = meta.optString("reason");
                entry.created = meta.optLong("created");
                entry.ready = meta.optBoolean("ready");
                entry.chats = meta.optInt("chats");
                entry.messages = meta.optInt("messages");
                entry.files = meta.optInt("files");
                result.add(entry);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        Collections.sort(result, Comparator.comparingLong((Entry e) -> e.created).reversed());
        return result;
    }

    public static boolean isRunning() {
        synchronized (running) {
            return !running.isEmpty();
        }
    }

    /** True while there is an archive of a lost account the user has not looked at yet. */
    public static boolean hasUnseen() {
        return prefs().getBoolean(KEY_UNSEEN, false);
    }

    public static void markSeen() {
        prefs().edit().putBoolean(KEY_UNSEEN, false).apply();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String describeReason(String reason) {
        if (REASON_FORCED_LOGOUT.equals(reason)) {
            return "Telegram завершил сессию (аккаунт удалён или заблокирован)";
        } else if (REASON_FROZEN.equals(reason)) {
            return "Аккаунт заморожен";
        }
        return "Сохранено вручную";
    }

    /** Called from the native "unauthorized" callback, before the account is wiped. */
    public static void onForcedLogout(int account) {
        snapshot(account, REASON_FORCED_LOGOUT, true, () -> notifySaved("Telegram завершил сессию аккаунта. Копия данных сохранена: Настройки → Архив аккаунтов"));
    }

    /** Called when the server config says the account became frozen; once per freeze. */
    public static void onFrozen(int account, long freezeSince) {
        String key = "frozen_" + account;
        if (prefs().getLong(key, 0) == freezeSince) {
            return;
        }
        prefs().edit().putLong(key, freezeSince).apply();
        snapshot(account, REASON_FROZEN, true, () -> notifySaved("Аккаунт заморожен. Копия данных сохранена: Настройки → Архив аккаунтов"));
    }

    private static void notifySaved(String text) {
        try {
            android.widget.Toast.makeText(ApplicationLoader.applicationContext, text, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void snapshot(int account, String reason, boolean markUnseen, Runnable done) {
        UserConfig config = UserConfig.getInstance(account);
        long userId = config.getClientUserId();
        if (userId == 0) {
            return;
        }
        TLRPC.User self = config.getCurrentUser();
        final String name = self != null ? userName(self) : "";
        final String phone = self != null && self.phone != null ? self.phone : "";
        final String username = self != null ? publicUsername(self) : "";
        synchronized (running) {
            if (!running.add(account)) {
                return;
            }
        }
        MessagesStorage storage = MessagesStorage.getInstance(account);
        // The storage queue is FIFO, so this runs before the cleanup that logout posts afterwards.
        storage.getStorageQueue().postRunnable(() -> {
            File dir = null;
            try {
                long now = System.currentTimeMillis();
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now));
                dir = new File(root(), userId + "_" + stamp);
                dir.mkdirs();
                File dbCopy = new File(dir, "cache4.db");
                copyDatabase(account, storage, dbCopy);

                JSONObject meta = new JSONObject();
                meta.put("format", FORMAT);
                meta.put("user_id", userId);
                meta.put("name", name);
                meta.put("phone", phone);
                meta.put("username", username);
                meta.put("reason", reason);
                meta.put("created", now);
                meta.put("ready", false);
                writeText(new File(dir, "meta.json"), meta.toString(2));
                if (markUnseen) {
                    prefs().edit().putBoolean(KEY_UNSEEN, true).apply();
                }
                final File archiveDir = dir;
                Thread thread = new Thread(() -> buildReport(account, userId, archiveDir, meta, done), "ZaStoArchive");
                thread.setPriority(Thread.MIN_PRIORITY);
                thread.start();
            } catch (Throwable e) {
                FileLog.e(e);
                synchronized (running) {
                    running.remove(account);
                }
                if (done != null) {
                    AndroidUtilities.runOnUIThread(done);
                }
            }
        });
    }

    private static void copyDatabase(int account, MessagesStorage storage, File target) throws Exception {
        SQLiteDatabase database = storage.getDatabase();
        if (database != null) {
            try {
                database.executeFast("PRAGMA wal_checkpoint(FULL)").stepThis().dispose();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        File filesDir = ApplicationLoader.getFilesDirFixed();
        if (account != 0) {
            filesDir = new File(filesDir, "account" + account + "/");
        }
        File source = new File(filesDir, "cache4.db");
        copyFile(source, target, null);
        File wal = new File(filesDir, "cache4.db-wal");
        if (wal.exists() && wal.length() > 0) {
            copyFile(wal, new File(target.getParentFile(), "cache4.db-wal"), null);
        }
    }

    // ---------------------------------------------------------------- report

    private static class ChatInfo {
        long did;
        String title = "";
        String kind = "";
        String username = "";
        String inviteLink = "";
        String about = "";
        int messages;
        int own;
        int files;
        String fileName;
    }

    private static void buildReport(int account, long selfId, File dir, JSONObject meta, Runnable done) {
        SQLiteDatabase db = null;
        try {
            File report = new File(dir, "report");
            report.mkdirs();
            File mediaDir = new File(dir, "media");
            db = new SQLiteDatabase(new File(dir, "cache4.db").getPath());

            Map<Long, TLRPC.User> users = new HashMap<>();
            Map<Long, TLRPC.Chat> chats = new HashMap<>();
            Map<Long, Long> secretPeers = new HashMap<>();
            Map<Long, TLRPC.ChatFull> fulls = new HashMap<>();
            Map<Long, String> userAbout = new HashMap<>();
            loadPeers(db, users, chats, secretPeers, fulls, userAbout);

            LinkedHashMap<Long, ChatInfo> infos = new LinkedHashMap<>();
            SQLiteCursor cursor = db.queryFinalized("SELECT did FROM dialogs ORDER BY date DESC");
            while (cursor.next()) {
                infos.put(cursor.longValue(0), null);
            }
            cursor.dispose();
            cursor = db.queryFinalized("SELECT DISTINCT uid FROM messages_v2");
            while (cursor.next()) {
                long uid = cursor.longValue(0);
                if (!infos.containsKey(uid)) {
                    infos.put(uid, null);
                }
            }
            cursor.dispose();

            long[] budget = {MEDIA_BUDGET};
            int totalMessages = 0;
            int totalFiles = 0;
            ArrayList<ChatInfo> finished = new ArrayList<>();
            for (Long did : new ArrayList<>(infos.keySet())) {
                ChatInfo info = describeChat(did, users, chats, secretPeers, fulls, userAbout);
                writeChat(db, selfId, info, users, chats, report, mediaDir, budget, account);
                totalMessages += info.messages;
                totalFiles += info.files;
                infos.put(did, info);
                finished.add(info);
            }

            writeIndex(report, meta, finished);
            writeLinks(report, finished);
            writeChatsJson(report, finished);

            meta.put("chats", finished.size());
            meta.put("messages", totalMessages);
            meta.put("files", totalFiles);
            meta.put("ready", true);
            writeText(new File(dir, "meta.json"), meta.toString(2));
        } catch (Throwable e) {
            FileLog.e(e);
            try {
                meta.put("error", String.valueOf(e));
                writeText(new File(dir, "meta.json"), meta.toString(2));
            } catch (Throwable ignore) {
            }
        } finally {
            if (db != null) {
                db.close();
            }
            synchronized (running) {
                running.remove(account);
            }
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        }
    }

    private static void loadPeers(SQLiteDatabase db, Map<Long, TLRPC.User> users, Map<Long, TLRPC.Chat> chats, Map<Long, Long> secretPeers, Map<Long, TLRPC.ChatFull> fulls, Map<Long, String> userAbout) throws Exception {
        SQLiteCursor cursor = db.queryFinalized("SELECT uid, data FROM users");
        while (cursor.next()) {
            NativeByteBuffer data = cursor.byteBufferValue(1);
            if (data != null) {
                TLRPC.User user = TLRPC.User.TLdeserialize(data, data.readInt32(false), false);
                data.reuse();
                if (user != null) {
                    users.put(cursor.longValue(0), user);
                }
            }
        }
        cursor.dispose();
        cursor = db.queryFinalized("SELECT uid, data FROM chats");
        while (cursor.next()) {
            NativeByteBuffer data = cursor.byteBufferValue(1);
            if (data != null) {
                TLRPC.Chat chat = TLRPC.Chat.TLdeserialize(data, data.readInt32(false), false);
                data.reuse();
                if (chat != null) {
                    chats.put(cursor.longValue(0), chat);
                }
            }
        }
        cursor.dispose();
        try {
            cursor = db.queryFinalized("SELECT uid, user FROM enc_chats");
            while (cursor.next()) {
                secretPeers.put(cursor.longValue(0), cursor.longValue(1));
            }
            cursor.dispose();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            cursor = db.queryFinalized("SELECT uid, info FROM chat_settings_v2");
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data != null) {
                    TLRPC.ChatFull full = TLRPC.ChatFull.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();
                    if (full != null) {
                        fulls.put(cursor.longValue(0), full);
                    }
                }
            }
            cursor.dispose();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            cursor = db.queryFinalized("SELECT uid, about FROM users_data");
            while (cursor.next()) {
                userAbout.put(cursor.longValue(0), cursor.stringValue(1));
            }
            cursor.dispose();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static ChatInfo describeChat(long did, Map<Long, TLRPC.User> users, Map<Long, TLRPC.Chat> chats, Map<Long, Long> secretPeers, Map<Long, TLRPC.ChatFull> fulls, Map<Long, String> userAbout) {
        ChatInfo info = new ChatInfo();
        info.did = did;
        if (DialogObject.isEncryptedDialog(did)) {
            int encId = DialogObject.getEncryptedChatId(did);
            Long peer = secretPeers.get((long) encId);
            TLRPC.User user = peer != null ? users.get(peer) : null;
            info.kind = "secret";
            info.title = "Секретный чат" + (user != null ? " с " + userName(user) : " " + encId);
        } else if (did > 0) {
            TLRPC.User user = users.get(did);
            info.kind = user != null && user.bot ? "bot" : "user";
            info.title = user != null ? userName(user) : "Пользователь " + did;
            if (user != null) {
                info.username = publicUsername(user);
            }
            String about = userAbout.get(did);
            if (about != null) {
                info.about = about;
            }
        } else {
            long chatId = -did;
            TLRPC.Chat chat = chats.get(chatId);
            if (chat != null) {
                info.kind = ChatObject.isChannel(chat) ? (chat.megagroup ? "supergroup" : "channel") : "group";
                info.title = chat.title != null ? chat.title : "";
                info.username = publicUsername(chat);
            } else {
                info.kind = "group";
                info.title = "Чат " + chatId;
            }
            TLRPC.ChatFull full = fulls.get(chatId);
            if (full != null) {
                if (full.about != null) {
                    info.about = full.about;
                }
                if (full.exported_invite != null && full.exported_invite.link != null) {
                    info.inviteLink = full.exported_invite.link;
                }
            }
        }
        if (info.title.isEmpty()) {
            info.title = "Диалог " + did;
        }
        info.fileName = "chat_" + (did < 0 ? "m" + (-did) : String.valueOf(did));
        return info;
    }

    private static String chatLink(ChatInfo info) {
        if (!info.username.isEmpty()) {
            return "https://t.me/" + info.username;
        }
        if (info.did < 0 && !DialogObject.isEncryptedDialog(info.did) && ("channel".equals(info.kind) || "supergroup".equals(info.kind))) {
            return "https://t.me/c/" + (-info.did);
        }
        return "";
    }

    private static String messageLink(ChatInfo info, int mid) {
        if (!info.username.isEmpty() && !"user".equals(info.kind) && !"bot".equals(info.kind)) {
            return "https://t.me/" + info.username + "/" + mid;
        }
        if (info.did < 0 && !DialogObject.isEncryptedDialog(info.did) && ("channel".equals(info.kind) || "supergroup".equals(info.kind))) {
            return "https://t.me/c/" + (-info.did) + "/" + mid;
        }
        return "";
    }

    private static void writeChat(SQLiteDatabase db, long selfId, ChatInfo info, Map<Long, TLRPC.User> users, Map<Long, TLRPC.Chat> chats, File report, File mediaDir, long[] budget, int account) throws Exception {
        File jsonFile = new File(report, info.fileName + ".json");
        File htmlFile = new File(report, info.fileName + ".html");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        try (JsonWriter json = new JsonWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(jsonFile)), StandardCharsets.UTF_8));
             Writer html = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(htmlFile)), StandardCharsets.UTF_8)) {
            json.setIndent(" ");
            json.beginObject();
            json.name("id").value(info.did);
            json.name("title").value(info.title);
            json.name("type").value(info.kind);
            json.name("username").value(info.username);
            json.name("link").value(chatLink(info));
            json.name("invite_link").value(info.inviteLink);
            json.name("about").value(info.about);
            json.name("messages").beginArray();

            html.write("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>");
            html.write(esc(info.title));
            html.write("</title>" + STYLE + "</head><body><p><a href=\"index.html\">← Все чаты</a></p><h1>");
            html.write(esc(info.title));
            html.write("</h1>");
            String link = chatLink(info);
            if (!link.isEmpty()) {
                html.write("<p><a href=\"" + esc(link) + "\">" + esc(link) + "</a></p>");
            }
            if (!info.inviteLink.isEmpty()) {
                html.write("<p>Ссылка-приглашение: <a href=\"" + esc(info.inviteLink) + "\">" + esc(info.inviteLink) + "</a></p>");
            }
            if (!info.about.isEmpty()) {
                html.write("<p class=\"about\">" + esc(info.about).replace("\n", "<br>") + "</p>");
            }

            SQLiteCursor cursor = db.queryFinalized("SELECT mid, date, data, out FROM messages_v2 WHERE uid = " + info.did + " ORDER BY date ASC, mid ASC");
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(2);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message != null) {
                    message.readAttachPath(data, selfId);
                }
                data.reuse();
                if (message == null) {
                    continue;
                }
                boolean out = cursor.intValue(3) != 0;
                info.messages++;
                if (out) {
                    info.own++;
                }
                long fromId = message.from_id != null ? MessageObject.getPeerId(message.from_id) : (out ? selfId : 0);
                String from = peerName(fromId, users, chats, selfId);
                String text = message.message != null ? message.message : "";
                String media = describeMedia(message);
                String saved = saveMedia(account, message, info, mediaDir, budget);
                if (saved != null) {
                    info.files++;
                }
                String msgLink = messageLink(info, message.id);
                String when = format.format(new Date(message.date * 1000L));

                json.beginObject();
                json.name("id").value(message.id);
                json.name("date").value(message.date);
                json.name("time").value(when);
                json.name("out").value(out);
                json.name("from_id").value(fromId);
                json.name("from").value(from);
                json.name("text").value(text);
                if (media != null) {
                    json.name("media").value(media);
                }
                if (saved != null) {
                    json.name("file").value("../media/" + saved);
                }
                if (message.fwd_from != null) {
                    json.name("forwarded").value(true);
                }
                if (message.reply_to != null && message.reply_to.reply_to_msg_id != 0) {
                    json.name("reply_to").value(message.reply_to.reply_to_msg_id);
                }
                if (!msgLink.isEmpty()) {
                    json.name("link").value(msgLink);
                }
                json.endObject();

                html.write("<div class=\"m" + (out ? " out" : "") + "\"><div class=\"h\"><b>" + esc(from) + "</b> <span>" + when + "</span>");
                if (!msgLink.isEmpty()) {
                    html.write(" <a href=\"" + esc(msgLink) + "\">#" + message.id + "</a>");
                }
                html.write("</div>");
                if (message.fwd_from != null) {
                    html.write("<div class=\"f\">Пересланное сообщение</div>");
                }
                if (!text.isEmpty()) {
                    html.write("<div class=\"t\">" + linkify(esc(text)).replace("\n", "<br>") + "</div>");
                }
                if (media != null) {
                    html.write("<div class=\"d\">" + esc(media) + "</div>");
                }
                if (saved != null) {
                    html.write("<div class=\"d\"><a href=\"../media/" + esc(saved).replace(" ", "%20") + "\">" + esc(saved.substring(saved.lastIndexOf('/') + 1)) + "</a></div>");
                }
                html.write("</div>");
            }
            cursor.dispose();
            json.endArray();
            json.endObject();
            html.write("</body></html>");
        }
    }

    private static String describeMedia(TLRPC.Message message) {
        if (message.action != null) {
            return "Служебное сообщение: " + message.action.getClass().getSimpleName();
        }
        TLRPC.MessageMedia media = message.media;
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty) {
            return null;
        }
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return "Фото";
        } else if (media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document document = media.document;
            String name = null;
            if (document != null) {
                for (TLRPC.DocumentAttribute attribute : document.attributes) {
                    if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                        name = attribute.file_name;
                    }
                }
                return "Файл" + (name != null ? ": " + name : "") + (document.mime_type != null ? " (" + document.mime_type + ")" : "");
            }
            return "Файл";
        } else if (media instanceof TLRPC.TL_messageMediaWebPage) {
            TLRPC.WebPage page = media.webpage;
            return page != null && page.url != null ? "Ссылка: " + page.url : "Ссылка";
        } else if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive || media instanceof TLRPC.TL_messageMediaVenue) {
            if (media.geo != null) {
                return "Место: " + media.geo.lat + ", " + media.geo._long;
            }
            return "Место";
        } else if (media instanceof TLRPC.TL_messageMediaContact) {
            return "Контакт: " + media.first_name + " " + media.last_name + " " + media.phone_number;
        } else if (media instanceof TLRPC.TL_messageMediaPoll) {
            return "Опрос";
        }
        return media.getClass().getSimpleName();
    }

    /** Puts the downloaded file of a message into the archive; returns its path relative to media/. */
    private static String saveMedia(int account, TLRPC.Message message, ChatInfo info, File mediaDir, long[] budget) {
        try {
            if (message.media == null || message.media instanceof TLRPC.TL_messageMediaEmpty || message.media instanceof TLRPC.TL_messageMediaWebPage) {
                return null;
            }
            File source = null;
            if (message.attachPath != null && !message.attachPath.isEmpty()) {
                File attach = new File(message.attachPath);
                if (attach.exists()) {
                    source = attach;
                }
            }
            if (source == null) {
                File path = FileLoader.getInstance(account).getPathToMessage(message, false);
                if (path != null && path.exists()) {
                    source = path;
                }
            }
            if (source == null || !source.isFile()) {
                return null;
            }
            File folder = new File(mediaDir, info.fileName);
            folder.mkdirs();
            String name = message.id + "_" + source.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
            File target = new File(folder, name);
            copyFile(source, target, budget);
            return target.exists() ? info.fileName + "/" + name : null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static final String STYLE = "<style>body{font:15px/1.4 sans-serif;max-width:860px;margin:0 auto;padding:16px;background:#f4f4f5;color:#111}"
            + "a{color:#2a7ae4}.m{background:#fff;border-radius:10px;padding:8px 12px;margin:8px 0;max-width:85%}.m.out{margin-left:auto;background:#e3f2fd}"
            + ".h span{color:#888;font-size:12px}.f,.d{color:#555;font-size:13px;margin-top:2px}.t{margin-top:4px;word-wrap:break-word}"
            + "table{border-collapse:collapse;width:100%;background:#fff}td,th{padding:6px 8px;border-bottom:1px solid #eee;text-align:left;vertical-align:top}"
            + ".about{white-space:pre-wrap;color:#444}</style>";

    private static void writeIndex(File report, JSONObject meta, List<ChatInfo> infos) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        try (Writer html = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(new File(report, "index.html"))), StandardCharsets.UTF_8)) {
            html.write("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Архив аккаунта</title>" + STYLE + "</head><body>");
            html.write("<h1>Архив аккаунта " + esc(meta.optString("name")) + "</h1>");
            html.write("<p>ID: " + meta.optLong("user_id"));
            if (!meta.optString("phone").isEmpty()) {
                html.write(", телефон +" + esc(meta.optString("phone")));
            }
            if (!meta.optString("username").isEmpty()) {
                html.write(", <a href=\"https://t.me/" + esc(meta.optString("username")) + "\">@" + esc(meta.optString("username")) + "</a>");
            }
            html.write("<br>Сохранено: " + format.format(new Date(meta.optLong("created"))) + "<br>Причина: " + esc(describeReason(meta.optString("reason"))) + "</p>");
            html.write("<p>Сохранено то, что к этому моменту уже было в локальном кэше приложения: сообщения, которые вы открывали или которые подгрузились, и скачанные файлы. То, чего в кэше не было, на устройстве отсутствует.</p>");
            html.write("<table><tr><th>Чат</th><th>Тип</th><th>Ссылка</th><th>Сообщений</th><th>Ваших</th><th>Файлов</th></tr>");
            for (ChatInfo info : infos) {
                String link = chatLink(info);
                html.write("<tr><td><a href=\"" + info.fileName + ".html\">" + esc(info.title) + "</a></td><td>" + esc(info.kind) + "</td><td>");
                if (!link.isEmpty()) {
                    html.write("<a href=\"" + esc(link) + "\">" + esc(link) + "</a>");
                }
                if (!info.inviteLink.isEmpty()) {
                    html.write("<br><a href=\"" + esc(info.inviteLink) + "\">" + esc(info.inviteLink) + "</a>");
                }
                html.write("</td><td>" + info.messages + "</td><td>" + info.own + "</td><td>" + info.files + "</td></tr>");
            }
            html.write("</table></body></html>");
        }
    }

    private static void writeLinks(File report, List<ChatInfo> infos) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (ChatInfo info : infos) {
            String link = chatLink(info);
            if (link.isEmpty() && info.inviteLink.isEmpty()) {
                continue;
            }
            builder.append(info.title).append(" [").append(info.kind).append("]\n");
            if (!link.isEmpty()) {
                builder.append("  ").append(link).append('\n');
            }
            if (!info.inviteLink.isEmpty()) {
                builder.append("  ").append(info.inviteLink).append('\n');
            }
        }
        writeText(new File(report, "links.txt"), builder.toString());
    }

    private static void writeChatsJson(File report, List<ChatInfo> infos) throws Exception {
        try (JsonWriter json = new JsonWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(new File(report, "chats.json"))), StandardCharsets.UTF_8))) {
            json.setIndent(" ");
            json.beginArray();
            for (ChatInfo info : infos) {
                json.beginObject();
                json.name("id").value(info.did);
                json.name("title").value(info.title);
                json.name("type").value(info.kind);
                json.name("username").value(info.username);
                json.name("link").value(chatLink(info));
                json.name("invite_link").value(info.inviteLink);
                json.name("about").value(info.about);
                json.name("messages").value(info.messages);
                json.name("own_messages").value(info.own);
                json.name("files").value(info.files);
                json.name("file").value(info.fileName + ".json");
                json.endObject();
            }
            json.endArray();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String peerName(long id, Map<Long, TLRPC.User> users, Map<Long, TLRPC.Chat> chats, long selfId) {
        if (id == 0) {
            return "";
        }
        if (id > 0) {
            TLRPC.User user = users.get(id);
            return user != null ? userName(user) : "Пользователь " + id;
        }
        TLRPC.Chat chat = chats.get(-id);
        return chat != null && chat.title != null ? chat.title : "Чат " + (-id);
    }

    private static String userName(TLRPC.User user) {
        StringBuilder builder = new StringBuilder();
        if (user.first_name != null) {
            builder.append(user.first_name);
        }
        if (user.last_name != null && !user.last_name.isEmpty()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(user.last_name);
        }
        if (builder.length() == 0) {
            String username = publicUsername(user);
            return username.isEmpty() ? "Пользователь " + user.id : "@" + username;
        }
        return builder.toString();
    }

    private static String publicUsername(TLRPC.User user) {
        String value = UserObject.getPublicUsername(user);
        return value == null ? "" : value;
    }

    private static String publicUsername(TLRPC.Chat chat) {
        String value = ChatObject.getPublicUsername(chat);
        return value == null ? "" : value;
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String linkify(String escaped) {
        return escaped.replaceAll("(https?://[^\\s<]+)", "<a href=\"$1\">$1</a>");
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Hard-links when possible (free), copies otherwise; a non-null budget caps the copied bytes. */
    private static void copyFile(File source, File target, long[] budget) throws Exception {
        if (target.exists()) {
            return;
        }
        if (budget != null) {
            try {
                Os.link(source.getAbsolutePath(), target.getAbsolutePath());
                return;
            } catch (Throwable ignore) {
            }
            if (source.length() > budget[0]) {
                return;
            }
            budget[0] -= source.length();
        }
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
    }

    public static void delete(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    delete(child);
                } else {
                    child.delete();
                }
            }
        }
        dir.delete();
    }

    public static void zip(File dir, OutputStream target) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(target))) {
            zipDir(zip, dir, dir.getName());
        }
    }

    private static void zipDir(ZipOutputStream zip, File dir, String prefix) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        byte[] buffer = new byte[64 * 1024];
        for (File child : children) {
            String path = prefix + "/" + child.getName();
            if (child.isDirectory()) {
                zipDir(zip, child, path);
            } else {
                zip.putNextEntry(new ZipEntry(path));
                try (InputStream in = new FileInputStream(child)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        zip.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
    }
}
