package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Last-resort route for an update that the Forgejo/GitHub hosts do not deliver (they are throttled or dropped by name in
 * some countries): the release APKs are also posted to the project channel, and a Telegram document is downloaded through
 * the app's own connection, so it works wherever Telegram itself works (WSS, Naive, MTProxy included).
 * <p>
 * The channel post that carries the release has "Версия: &lt;tag&gt;" in its caption; the APKs of that post (an album) are
 * documents named like the release assets, so the document is found by the tag and the asset name. The downloaded copy is
 * handed to the caller, which validates it exactly like a file from a mirror (size and package name).
 */
final class ZaStoChannelUpdate implements NotificationCenter.NotificationCenterDelegate {

    interface Callback {
        /** Fraction 0..1 of the document. */
        void onProgress(float progress);

        /** The finished copy in |dest|, or null when the route did not work. */
        void onDone(File dest);
    }

    private static final String CHANNEL = "zastogram";
    private static final int HISTORY_LIMIT = 60;
    private static ZaStoChannelUpdate active;

    private final int account;
    private final String tag;
    private final String assetName;
    private final File dest;
    private final Callback callback;
    private TLRPC.Document document;
    private String fileName;
    private boolean finished;
    private int requestId;

    private ZaStoChannelUpdate(int account, String tag, String assetName, File dest, Callback callback) {
        this.account = account;
        this.tag = tag;
        this.assetName = assetName;
        this.dest = dest;
        this.callback = callback;
    }

    /** Starts the download; false when the route cannot be used at all (no signed-in account, no tag). */
    static boolean download(String tag, String assetName, File dest, Callback callback) {
        if (TextUtils.isEmpty(tag) || TextUtils.isEmpty(assetName) || dest == null) {
            return false;
        }
        final int account = UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return false;
        }
        if (active != null && !active.finished) {
            return true;
        }
        active = new ZaStoChannelUpdate(account, tag, assetName, dest, callback);
        active.resolveChannel();
        return true;
    }

    static void cancel() {
        if (active != null) {
            active.finish(null);
        }
    }

    private void resolveChannel() {
        FileLog.d("zasto_update channel_route resolve tag=" + tag);
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = CHANNEL;
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (finished) {
                return;
            }
            if (!(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                FileLog.e("zasto_update channel_route resolve failed");
                finish(null);
                return;
            }
            TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
            MessagesController controller = MessagesController.getInstance(account);
            controller.putUsers(resolved.users, false);
            controller.putChats(resolved.chats, false);
            if (resolved.peer == null || resolved.peer.channel_id == 0) {
                finish(null);
                return;
            }
            loadHistory(controller.getInputPeer(-resolved.peer.channel_id));
        }));
    }

    private void loadHistory(TLRPC.InputPeer peer) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.limit = HISTORY_LIMIT;
        requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (finished) {
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                FileLog.e("zasto_update channel_route history failed");
                finish(null);
                return;
            }
            findAndLoad(((TLRPC.messages_Messages) response).messages);
        }));
    }

    private static String documentName(TLRPC.Document doc) {
        for (int i = 0; i < doc.attributes.size(); i++) {
            TLRPC.DocumentAttribute attribute = doc.attributes.get(i);
            if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                return attribute.file_name;
            }
        }
        return null;
    }

    private void findAndLoad(ArrayList<TLRPC.Message> messages) {
        // The caption with the tag sits on one message of the album; the APKs are the documents with the same grouped id.
        long groupId = 0;
        boolean tagged = false;
        for (TLRPC.Message message : messages) {
            if (message.message != null && message.message.contains(tag)) {
                tagged = true;
                groupId = message.grouped_id;
                break;
            }
        }
        if (!tagged) {
            FileLog.e("zasto_update channel_route no post for " + tag);
            finish(null);
            return;
        }
        TLRPC.Message found = null;
        for (TLRPC.Message message : messages) {
            boolean sameAlbum = groupId != 0 ? message.grouped_id == groupId : message.message != null && message.message.contains(tag);
            if (!sameAlbum || !(message.media instanceof TLRPC.TL_messageMediaDocument) || message.media.document == null) {
                continue;
            }
            if (assetName.equals(documentName(message.media.document))) {
                found = message;
                break;
            }
        }
        if (found == null) {
            FileLog.e("zasto_update channel_route no document " + assetName + " in the post of " + tag);
            finish(null);
            return;
        }
        document = found.media.document;
        fileName = FileLoader.getAttachFileName(document);
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.fileLoaded);
        center.addObserver(this, NotificationCenter.fileLoadFailed);
        center.addObserver(this, NotificationCenter.fileLoadProgressChanged);
        File cached = FileLoader.getInstance(account).getPathToAttach(document);
        if (cached != null && cached.isFile() && cached.length() == document.size) {
            copyAndFinish(cached);
            return;
        }
        FileLog.d("zasto_update channel_route download size=" + document.size);
        FileLoader.getInstance(account).loadFile(document, found, FileLoader.PRIORITY_NORMAL, 1);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (finished || fileName == null || account != this.account) {
            return;
        }
        if (id == NotificationCenter.fileLoadProgressChanged) {
            if (fileName.equals(args[0]) && args.length >= 3 && args[1] instanceof Long && args[2] instanceof Long) {
                long total = (Long) args[2];
                if (total > 0) {
                    callback.onProgress(Math.min(1f, (float) ((Long) args[1]) / total));
                }
            }
        } else if (id == NotificationCenter.fileLoaded) {
            if (fileName.equals(args[0])) {
                File file = args.length > 1 && args[1] instanceof File ? (File) args[1] : FileLoader.getInstance(account).getPathToAttach(document);
                copyAndFinish(file);
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            if (fileName.equals(args[0])) {
                FileLog.e("zasto_update channel_route download failed");
                finish(null);
            }
        }
    }

    private void copyAndFinish(File source) {
        if (source == null || !source.isFile()) {
            finish(null);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File result = null;
            try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(dest, false)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                result = dest;
            } catch (Exception e) {
                FileLog.e(e);
            }
            final File done = result;
            AndroidUtilities.runOnUIThread(() -> finish(done));
        });
    }

    private void finish(File result) {
        if (finished) {
            return;
        }
        finished = true;
        if (requestId != 0) {
            ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
        }
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        if (active == this) {
            active = null;
        }
        callback.onDone(result);
    }
}
