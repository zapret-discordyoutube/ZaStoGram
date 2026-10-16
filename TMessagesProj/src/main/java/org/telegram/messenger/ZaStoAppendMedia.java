package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * "Добавить медиа" к уже отправленному сообщению с фото/видео (или к своему альбому).
 *
 * Протокол Telegram не умеет добавлять сообщение в существующий альбом: grouped_id назначает сервер при
 * messages.sendMultiMedia, а messages.editMessage меняет медиа только одного сообщения. Поэтому клиент
 * отправляет НОВЫЙ альбом: сначала исходные медиа по ссылке (InputMediaPhoto / InputMediaDocument по
 * id + access_hash + file_reference, без повторной загрузки), затем новые файлы (обычная загрузка через
 * SendMessagesHelper.prepareSendingMedia). Все элементы идут в одну группу и уходят одним
 * TL_messages_sendMultiMedia — SendMessagesHelper сам смешивает готовые и загружаемые элементы группы.
 * После того как сервер принял весь новый альбом, исходные сообщения удаляются у всех.
 *
 * Если что-то пошло не так (ошибка отправки, приложение закрыли до ответа сервера), исходные сообщения НЕ
 * удаляются: в худшем случае остаётся дубль, но не потеря данных.
 */
public final class ZaStoAppendMedia {

    public static final int MAX_ALBUM_ITEMS = 10;
    private static final long TRACK_TIMEOUT_MS = 30 * 60 * 1000L;

    private ZaStoAppendMedia() {
    }

    /** Состояние одной операции «добавить медиа», живёт в ChatActivity от подтверждения до отправки. */
    public static final class Session {
        public final int account;
        public final long dialogId;
        /** Исходные сообщения по возрастанию id (порядок альбома). */
        public final ArrayList<MessageObject> sources;
        public final MessageObject replyTo;
        public final MessageObject replyToTop;
        public final int topicId;
        /** Дополнить исходное сообщение на месте, а не отправлять новый альбом. */
        public boolean inPlace;

        private Session(int account, long dialogId, ArrayList<MessageObject> sources, MessageObject replyTo, MessageObject replyToTop, int topicId) {
            this.account = account;
            this.dialogId = dialogId;
            this.sources = sources;
            this.replyTo = replyTo;
            this.replyToTop = replyToTop;
            this.topicId = topicId;
        }

        /** Сколько новых элементов ещё влезает в альбом. */
        public int maxNew() {
            return Math.max(0, MAX_ALBUM_ITEMS - sources.size());
        }
    }

    /** Фото или обычное видео (не гифка, не кружок): то, что можно показать в одном альбоме. */
    public static boolean isPhotoOrVideo(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return media.photo instanceof TLRPC.TL_photo;
        } else if (media instanceof TLRPC.TL_messageMediaDocument) {
            final TLRPC.Document document = media.document;
            return document instanceof TLRPC.TL_document
                    && MessageObject.isVideoDocument(document)
                    && !MessageObject.isRoundVideoDocument(document)
                    && !MessageObject.isGifDocument(document);
        }
        return false;
    }

    /**
     * Проверяет исходные сообщения. Возвращает id строки с причиной отказа или 0, если всё хорошо.
     * Лимит альбома тут не проверяется на «не влезет», только «уже полон».
     */
    public static int validateSources(int account, TLRPC.Chat chat, boolean encrypted, int chatMode, ArrayList<MessageObject> sources) {
        if (sources == null || sources.isEmpty() || encrypted || chatMode != 0) {
            return R.string.ZaStoAppendMediaUnsupported;
        }
        if (ChatObject.isMonoForum(chat)) {
            return R.string.ZaStoAppendMediaUnsupported;
        }
        if (sources.size() >= MAX_ALBUM_ITEMS) {
            return R.string.ZaStoAppendMediaFull;
        }
        for (int a = 0, N = sources.size(); a < N; a++) {
            final MessageObject m = sources.get(a);
            if (m == null || m.messageOwner == null || m.getId() <= 0 || m.isSending() || m.isEditing()) {
                return R.string.ZaStoAppendMediaUnsupported;
            }
            if (!m.isOutOwner() || !MessageObject.canDeleteMessage(account, false, m.messageOwner, chat)) {
                return R.string.ZaStoAppendMediaNoDelete;
            }
            if (!isPhotoOrVideo(m)) {
                return R.string.ZaStoAppendMediaUnsupported;
            }
            if (m.isForwarded() || m.needResendWhenEdit() || m.messageOwner.via_bot_id != 0 || m.messageOwner.suggested_post != null) {
                return R.string.ZaStoAppendMediaUnsupported;
            }
            final TLRPC.MessageMedia media = MessageObject.getMedia(m.messageOwner);
            if (media.ttl_seconds != 0 || media.live_photo) {
                return R.string.ZaStoAppendMediaUnsupported;
            }
            if (media.photo != null) {
                final TLRPC.Photo photo = media.photo;
                if (photo.access_hash == 0 || photo.sizes.isEmpty() || photo.sizes.get(photo.sizes.size() - 1).location == null) {
                    return R.string.ZaStoAppendMediaUnsupported;
                }
            } else if (media.document == null || media.document.access_hash == 0) {
                return R.string.ZaStoAppendMediaUnsupported;
            }
        }
        return 0;
    }

    /** Проверка выбранных пользователем новых файлов перед отправкой. 0 — всё хорошо. */
    public static int validateNew(Session session, int newCount, boolean asFiles, boolean scheduled, boolean hasTtl) {
        if (session == null || newCount <= 0) {
            return R.string.ZaStoAppendMediaUnsupported;
        }
        if (asFiles) {
            return R.string.ZaStoAppendMediaMix;
        }
        if (scheduled) {
            return R.string.ZaStoAppendMediaNoSchedule;
        }
        if (hasTtl) {
            return R.string.ZaStoAppendMediaUnsupported;
        }
        if (newCount > session.maxNew()) {
            return R.string.ZaStoAppendMediaTooMany;
        }
        return 0;
    }

    /** Текст для показа пользователю по коду из validate*(). */
    public static String message(int resId, Session session) {
        if (resId == R.string.ZaStoAppendMediaTooMany) {
            return LocaleController.formatString(resId, session != null ? session.maxNew() : 0);
        }
        return LocaleController.getString(resId);
    }

    /**
     * Собирает сессию: сортирует исходные сообщения и определяет, на что отвечал оригинал.
     * Возвращает null, если ответ нельзя воспроизвести (ответ в другой чат, не загруженное сообщение и т.п.).
     */
    public static Session createSession(int account, long dialogId, ArrayList<MessageObject> sources, MessageObject threadMessage, int topicId) {
        final ArrayList<MessageObject> sorted = new ArrayList<>(sources);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.getId(), b.getId()));

        MessageObject replyTo = null;
        for (int a = 0, N = sorted.size(); a < N; a++) {
            final MessageObject m = sorted.get(a);
            final TLRPC.MessageReplyHeader header = m.messageOwner.reply_to;
            if (header == null) {
                continue;
            }
            if (!(header instanceof TLRPC.TL_messageReplyHeader)) {
                return null;
            }
            final TLRPC.TL_messageReplyHeader h = (TLRPC.TL_messageReplyHeader) header;
            if (h.reply_to_peer_id != null || h.reply_from != null || h.reply_to_ephemeral || h.reply_to_scheduled) {
                return null;
            }
            if (h.reply_to_msg_id == 0) {
                continue;
            }
            if (threadMessage != null && threadMessage.getId() == h.reply_to_msg_id) {
                replyTo = threadMessage;
            } else if (m.replyMessageObject != null && m.replyMessageObject.getId() == h.reply_to_msg_id) {
                replyTo = m.replyMessageObject;
            } else {
                return null;
            }
            break;
        }
        return new Session(account, dialogId, sorted, replyTo, threadMessage, topicId);
    }

    /**
     * Отправляет новый альбом: исходные медиа по ссылке + новые файлы.
     * Вызывать из UI-потока. Возвращает id строки с причиной отказа или 0, если отправка запущена.
     */
    public static int send(AccountInstance accountInstance, Session session, ArrayList<SendMessagesHelper.SendingMediaInfo> infos,
                           boolean notify, long effectId, boolean invertMedia, long payStars, long monoForumPeerId,
                           SendMessageChatArguments chatArguments) {
        if (accountInstance == null || session == null || infos == null || infos.isEmpty()) {
            return R.string.ZaStoAppendMediaUnsupported;
        }
        boolean hasTtl = false;
        for (int a = 0, N = infos.size(); a < N; a++) {
            if (infos.get(a).ttl > 0) {
                hasTtl = true;
                break;
            }
        }
        final int error = validateNew(session, infos.size(), false, false, hasTtl);
        if (error != 0) {
            return error;
        }

        long groupId = Utilities.random.nextLong();
        while (groupId == 0) {
            groupId = Utilities.random.nextLong();
        }

        final int total = session.sources.size() + infos.size();
        final ArrayList<Integer> sourceIds = new ArrayList<>();
        for (int a = 0, N = session.sources.size(); a < N; a++) {
            sourceIds.add(session.sources.get(a).getId());
        }
        new Tracker(session.account, session.dialogId, groupId, total, sourceIds, session.topicId).start();

        // 1) Исходные медиа по ссылке. Идут первыми (в порядке UI-потока), поэтому окажутся в начале альбома.
        for (int a = 0, N = session.sources.size(); a < N; a++) {
            final MessageObject source = session.sources.get(a);
            final TLRPC.MessageMedia media = MessageObject.getMedia(source.messageOwner);
            final HashMap<String, String> params = new HashMap<>();
            params.put("groupId", "" + groupId);
            final SendMessagesHelper.SendMessageParams sendParams;
            if (media.photo instanceof TLRPC.TL_photo) {
                sendParams = SendMessagesHelper.SendMessageParams.of((TLRPC.TL_photo) media.photo, null, session.dialogId, session.replyTo, session.replyToTop, null, null, null, params, notify, 0, 0, 0, source, false, media.spoiler);
            } else {
                sendParams = SendMessagesHelper.SendMessageParams.of((TLRPC.TL_document) media.document, null, null, session.dialogId, session.replyTo, session.replyToTop, null, null, null, params, notify, 0, 0, 0, source, null, false, media.spoiler);
            }
            sendParams.sendMessageChatArguments = chatArguments;
            sendParams.invert_media = invertMedia;
            sendParams.payStars = payStars;
            sendParams.monoForumPeer = monoForumPeerId;
            accountInstance.getSendMessagesHelper().sendMessage(sendParams);
        }

        // 2) Новые файлы: обычная подготовка и загрузка, но в уже открытую группу (и с «final» на последнем).
        infos.get(0).zaForcedGroupId = groupId;
        SendMessagesHelper.prepareSendingMedia(accountInstance, infos, session.dialogId, session.replyTo, session.replyToTop, null, null,
                false, true, null, notify, 0, 0, 0, false, null, chatArguments, effectId, invertMedia, payStars, monoForumPeerId, null);
        return 0;
    }

    /** Зарегистрированные отслеживания по id группы (локальному, которым отправляется новый альбом). Только UI-поток. */
    private static final HashMap<Long, Tracker> TRACKERS = new HashMap<>();

    /**
     * Вызывается из SendMessagesHelper.sendMessage для каждого сообщения группы: запоминаем локальные id,
     * по ним потом узнаём сообщения нового альбома в messageReceivedByServer (grouped_id у объекта к тому
     * моменту уже заменяет ChatActivity, поэтому по нему сопоставлять нельзя).
     */
    public static void onGroupMessageCreated(long groupId, int localId) {
        final Tracker tracker = TRACKERS.get(groupId);
        if (tracker != null) {
            tracker.localIds.add(localId);
        }
    }

    /**
     * Ждёт, пока сервер примет все сообщения нового альбома, и только потом удаляет исходные у всех.
     * Если приём не подтверждён за TRACK_TIMEOUT_MS (ошибка отправки, приложение закрыли), исходные остаются.
     */
    private static final class Tracker implements NotificationCenter.NotificationCenterDelegate {
        private final int account;
        private final long dialogId;
        private final long groupId;
        private final int expected;
        private final ArrayList<Integer> sourceIds;
        private final int topicId;
        private final HashSet<Integer> localIds = new HashSet<>();
        private final HashSet<Integer> received = new HashSet<>();
        private final Runnable timeout = this::stop;
        private boolean finished;

        Tracker(int account, long dialogId, long groupId, int expected, ArrayList<Integer> sourceIds, int topicId) {
            this.account = account;
            this.dialogId = dialogId;
            this.groupId = groupId;
            this.expected = expected;
            this.sourceIds = sourceIds;
            this.topicId = topicId;
        }

        void start() {
            TRACKERS.put(groupId, this);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messageReceivedByServer);
            AndroidUtilities.runOnUIThread(timeout, TRACK_TIMEOUT_MS);
        }

        private void stop() {
            if (finished) {
                return;
            }
            finished = true;
            TRACKERS.remove(groupId);
            AndroidUtilities.cancelRunOnUIThread(timeout);
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messageReceivedByServer);
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (finished || id != NotificationCenter.messageReceivedByServer || account != this.account || args == null || args.length < 1) {
                return;
            }
            if (!(args[0] instanceof Integer) || !localIds.contains((Integer) args[0])) {
                return;
            }
            // Событие приходит дважды на каждое сообщение — считаем по локальному id без дублей.
            received.add((Integer) args[0]);
            if (received.size() >= expected) {
                stop();
                MessagesController.getInstance(this.account).deleteMessages(new ArrayList<>(sourceIds), null, null, dialogId, topicId, true, 0);
            }
        }
    }

    // ---- Дополнить на месте: исходное сообщение правкой становится богатым с галереей ----
    //
    // Альбом нельзя расширить, но богатое сообщение (Telegram 13.x) можно отредактировать: messages.editMessage
    // с rich_message. Галерея pageBlockCollage рисуется той же раскладкой, что и альбом (RichCollageBlock →
    // MessageObject.computeGrouped). Сообщение остаётся тем же: дата, реакции, ответы и пересылки сохраняются.
    // Публиковать богатые сообщения сервер разрешает не всем (app config rich_message_posting, по умолчанию
    // premium), поэтому при любом отказе ChatActivity предлагает прежний путь — новый альбом.

    /** Новый файл для дополнения на месте: то, что нужно загрузчику богатых сообщений. */
    public static final class NewItem {
        final String path;
        final boolean video;
        final int width;
        final int height;
        final int duration;
        final boolean spoiler;
        final boolean edited;

        public NewItem(String path, boolean video, int width, int height, int duration, boolean spoiler, VideoEditedInfo editedInfo) {
            this.path = path;
            this.video = video;
            this.width = width;
            this.height = height;
            this.duration = duration;
            this.spoiler = spoiler;
            this.edited = video && isEditedVideo(editedInfo);
        }
    }

    public interface InPlaceListener {
        /** Файлы загружены, правка ушла на сервер. */
        void onEditSent();

        /** Сервер принял правку. */
        void onDone();

        /** Загрузка или правка не удалась; локальное состояние сообщения уже откатано. */
        void onFailed(String reason);
    }

    /**
     * Причина, по которой сообщение нельзя дополнить на месте, или 0. Проверяется после validateSources.
     * Альбом — несколько отдельных сообщений, одно из них правкой галереей не станет.
     */
    public static int inPlaceBlocker(Session session, TLRPC.Chat chat) {
        if (session == null || session.sources.size() != 1) {
            return R.string.ZaStoAppendInPlaceAlbum;
        }
        final MessageObject m = session.sources.get(0);
        if (m.getGroupId() != 0) {
            return R.string.ZaStoAppendInPlaceAlbum;
        }
        if (!MessagesController.getInstance(session.account).richEditorAllowed()) {
            return R.string.ZaStoAppendInPlacePremium;
        }
        if (m.messageOwner.rich_message != null || !m.canEditMessage(chat)) {
            return R.string.ZaStoAppendInPlaceNotEditable;
        }
        return 0;
    }

    /** Проверка выбранных файлов для дополнения на месте: 0 — можно. */
    public static int validateInPlace(ArrayList<NewItem> items) {
        if (items == null || items.isEmpty()) {
            return R.string.ZaStoAppendMediaUnsupported;
        }
        for (int a = 0, N = items.size(); a < N; a++) {
            final NewItem item = items.get(a);
            if (item.path == null || item.path.isEmpty()) {
                return R.string.ZaStoAppendMediaUnsupported;
            }
            // Загрузчик богатых сообщений шлёт видео файлом как есть: обрезку, фильтры и рисунок
            // он не применит, поэтому такое видео идёт только новым альбомом.
            if (item.video && (item.edited || !item.path.toLowerCase(java.util.Locale.US).endsWith(".mp4"))) {
                return R.string.ZaStoAppendInPlaceEditedVideo;
            }
        }
        return 0;
    }

    /** Вызывать из UI-потока после validateInPlace. */
    public static void appendInPlace(Session session, ArrayList<NewItem> items, CharSequence caption, InPlaceListener listener) {
        new InPlace(session, items, caption, listener).start();
    }

    /** Правки на месте, ждущие ответа сервера, по объекту сообщения. Только UI-поток. */
    private static final java.util.IdentityHashMap<MessageObject, InPlace> PENDING_EDITS = new java.util.IdentityHashMap<>();

    /** Из SendMessagesHelper.sendEditRichMessageRequest: ответ сервера на правку богатого сообщения (UI-поток). */
    public static void onRichEditResult(MessageObject messageObject, TLRPC.TL_error error) {
        final InPlace job = PENDING_EDITS.remove(messageObject);
        if (job != null) {
            job.onEditResult(error);
        }
    }

    private static boolean isEditedVideo(VideoEditedInfo info) {
        return info != null && (info.roundVideo || info.muted
                || info.cropState != null || info.paintPath != null || info.blurPath != null
                || info.filterState != null || info.mediaEntities != null
                || !info.mixedSoundInfos.isEmpty() || info.videoOffset != 0 || info.startTime > 0
                || (info.endTime > 0 && info.endTime < info.estimatedDuration * 1000));
    }

    private static void ensureCaption(org.telegram.tgnet.tl.TL_iv.PageBlock block) {
        if (block.caption == null) {
            block.caption = new org.telegram.tgnet.tl.TL_iv.PageCaption();
        }
        if (block.caption.text == null) {
            block.caption.text = new org.telegram.tgnet.tl.TL_iv.textEmpty();
        }
        if (block.caption.credit == null) {
            block.caption.credit = new org.telegram.tgnet.tl.TL_iv.textEmpty();
        }
    }

    private static org.telegram.tgnet.tl.TL_iv.PageBlock mediaBlock(boolean video, long id, boolean spoiler) {
        final org.telegram.tgnet.tl.TL_iv.PageBlock block;
        if (video) {
            final org.telegram.tgnet.tl.TL_iv.pageBlockVideo v = new org.telegram.tgnet.tl.TL_iv.pageBlockVideo();
            v.video_id = id;
            v.spoiler = spoiler;
            block = v;
        } else {
            final org.telegram.tgnet.tl.TL_iv.pageBlockPhoto p = new org.telegram.tgnet.tl.TL_iv.pageBlockPhoto();
            p.photo_id = id;
            p.spoiler = spoiler;
            block = p;
        }
        ensureCaption(block);
        return block;
    }

    private static final class InPlace {
        private final Session session;
        private final MessageObject target;
        private final ArrayList<NewItem> items;
        private final CharSequence caption;
        private final InPlaceListener listener;
        private final org.telegram.ui.iv.RichMediaUploader[] uploaders;
        private final TLRPC.Photo[] photos;
        private final TLRPC.Document[] videos;
        private int left;
        private boolean failed;

        InPlace(Session session, ArrayList<NewItem> items, CharSequence caption, InPlaceListener listener) {
            this.session = session;
            this.target = session.sources.get(0);
            this.items = items;
            this.caption = caption;
            this.listener = listener;
            this.uploaders = new org.telegram.ui.iv.RichMediaUploader[items.size()];
            this.photos = new TLRPC.Photo[items.size()];
            this.videos = new TLRPC.Document[items.size()];
        }

        void start() {
            left = items.size();
            for (int a = 0, N = items.size(); a < N; a++) {
                final int index = a;
                final NewItem item = items.get(a);
                uploaders[a] = new org.telegram.ui.iv.RichMediaUploader(session.account, item.path, item.video, item.width, item.height, item.duration, new org.telegram.ui.iv.RichMediaUploader.Listener() {
                    @Override
                    public void onPhotoUploaded(TLRPC.Photo photo) {
                        photos[index] = photo;
                        uploaded();
                    }

                    @Override
                    public void onVideoUploaded(TLRPC.Document document) {
                        videos[index] = document;
                        uploaded();
                    }

                    @Override
                    public void onError() {
                        fail(LocaleController.getString(R.string.ZaStoAppendInPlaceUploadFailed));
                    }
                });
                uploaders[a].start();
            }
        }

        private void uploaded() {
            if (failed || --left > 0) {
                return;
            }
            final TLRPC.MessageMedia media = MessageObject.getMedia(target.messageOwner);
            if (media == null) {
                fail(LocaleController.getString(R.string.ZaStoAppendMediaUnsupported));
                return;
            }
            final ArrayList<TLRPC.Photo> richPhotos = new ArrayList<>();
            final ArrayList<TLRPC.Document> richDocuments = new ArrayList<>();
            final org.telegram.tgnet.tl.TL_iv.pageBlockCollage collage = new org.telegram.tgnet.tl.TL_iv.pageBlockCollage();
            collage.items = new ArrayList<>();
            if (media.photo instanceof TLRPC.TL_photo) {
                collage.items.add(mediaBlock(false, media.photo.id, media.spoiler));
                richPhotos.add(media.photo);
            } else {
                collage.items.add(mediaBlock(true, media.document.id, media.spoiler));
                richDocuments.add(media.document);
            }
            for (int a = 0, N = items.size(); a < N; a++) {
                if (photos[a] != null) {
                    collage.items.add(mediaBlock(false, photos[a].id, items.get(a).spoiler));
                    richPhotos.add(photos[a]);
                } else if (videos[a] != null) {
                    collage.items.add(mediaBlock(true, videos[a].id, items.get(a).spoiler));
                    richDocuments.add(videos[a]);
                }
            }
            ensureCaption(collage);
            final ArrayList<org.telegram.tgnet.tl.TL_iv.PageBlock> blocks = new ArrayList<>();
            blocks.add(collage);
            if (caption != null && caption.toString().trim().length() > 0) {
                blocks.addAll(org.telegram.ui.iv.RichMessageConvert.blocksFromCharSequence(caption));
            }
            PENDING_EDITS.put(target, this);
            // fragment = null: об отказе сообщает listener (с предложением отправить альбомом), не AlertsCreator.
            SendMessagesHelper.prepareEditingArticle(AccountInstance.getInstance(session.account), target, blocks,
                    richPhotos, richDocuments, null, false, null);
            listener.onEditSent();
        }

        private void fail(String reason) {
            if (failed) {
                return;
            }
            failed = true;
            for (org.telegram.ui.iv.RichMediaUploader uploader : uploaders) {
                if (uploader != null) {
                    uploader.cancel();
                }
            }
            listener.onFailed(reason);
        }

        void onEditResult(TLRPC.TL_error error) {
            if (error == null) {
                listener.onDone();
                return;
            }
            // editRichMessage кладёт rich_message в сообщение до ответа сервера и при отказе его не убирает.
            target.messageOwner.rich_message = null;
            final ArrayList<MessageObject> changed = new ArrayList<>();
            changed.add(target);
            NotificationCenter.getInstance(session.account).postNotificationName(NotificationCenter.replaceMessagesObjects, target.getDialogId(), changed);
            failed = true;
            listener.onFailed(error.text);
        }
    }
}
