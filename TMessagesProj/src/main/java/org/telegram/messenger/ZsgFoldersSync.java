package org.telegram.messenger;

import android.util.Base64;
import android.widget.Toast;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Synchronizes ZaStoGram's own folders between devices through Saved Messages. The data is
 * ZsgFolders.serializeForSync(), compressed the way Qt's qCompress does it (a 4 byte big-endian
 * length of the original and a zlib stream), base64 encoded and split into text messages of at
 * most CHUNK_CHARS characters. Each message is
 *
 *   ZSG-FOLDERS v1 {rev} {index}/{count}\n{base64 part}
 *
 * where {rev} is the same for all parts of one version and {index} starts from 1. A reader takes
 * the newest complete version. The parts are edited in place when the data changes, so the chat
 * does not fill with messages. The Windows client uses the same format.
 */
public class ZsgFoldersSync extends BaseController {

    private static final String MARKER = "ZSG-FOLDERS";
    private static final int CHUNK_CHARS = 3500;
    private static final int MAX_CHUNKS = 60;
    private static final int SEARCH_LIMIT = 100;
    private static final long DEBOUNCE = 4000;
    private static final long START_DELAY = 8000;
    private static final long POLL_EVERY = 15 * 60 * 1000;
    private static final long RETRY_DELAY = 60 * 1000;
    private static final long INCOMPLETE_RETRY_DELAY = 5000;
    private static final int INCOMPLETE_RETRIES = 3;

    private static volatile ZsgFoldersSync[] Instance = new ZsgFoldersSync[UserConfig.MAX_ACCOUNT_COUNT];

    public static ZsgFoldersSync getInstance(int num) {
        ZsgFoldersSync localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ZsgFoldersSync.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ZsgFoldersSync(num);
                }
            }
        }
        return localInstance;
    }

    private static class Chunk {
        int id;
        long rev;
        int index;
        int count;
        String text;
        String payload;
    }

    private boolean started;
    private boolean syncing;
    private boolean dirty;
    private int incompleteRetries;
    private int notifiedTooLargeKb;
    private final Runnable syncRunnable = this::sync;
    private boolean missedPoll;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (ApplicationLoader.mainInterfacePaused) {
                // In the background a periodic request would wake the radio every 15 minutes
                // for nothing: nobody looks at the folders. Catch up when the app comes back.
                missedPoll = true;
            } else {
                sync();
            }
            AndroidUtilities.runOnUIThread(this, POLL_EVERY);
        }
    };

    public ZsgFoldersSync(int num) {
        super(num);
    }

    /** Called once the account's folders are loaded. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        ZsgFolders.getInstance(currentAccount).load();
        getMessagesController().applyLocalFolders();
        schedule(START_DELAY);
        AndroidUtilities.runOnUIThread(pollRunnable, POLL_EVERY);
    }

    /** Called when the app returns to the foreground: runs the poll that was skipped in the background. */
    public void onAppForeground() {
        if (started && missedPoll) {
            missedPoll = false;
            schedule(START_DELAY / 4);
        }
    }

    public void onFoldersChanged() {
        if (started) {
            schedule(DEBOUNCE);
        }
    }

    private void schedule(long delay) {
        AndroidUtilities.cancelRunOnUIThread(syncRunnable);
        AndroidUtilities.runOnUIThread(syncRunnable, delay);
    }

    private static Chunk parseChunk(int id, String text) {
        if (text == null) {
            return null;
        }
        final int newline = text.indexOf('\n');
        if (newline < 0) {
            return null;
        }
        final String[] header = text.substring(0, newline).split(" ");
        if (header.length != 4 || !MARKER.equals(header[0]) || !"v1".equals(header[1])) {
            return null;
        }
        final String[] position = header[3].split("/");
        if (position.length != 2) {
            return null;
        }
        try {
            final Chunk chunk = new Chunk();
            chunk.id = id;
            chunk.rev = Long.parseLong(header[2]);
            chunk.index = Integer.parseInt(position[0]);
            chunk.count = Integer.parseInt(position[1]);
            if (chunk.index < 1 || chunk.count < 1 || chunk.index > chunk.count) {
                return null;
            }
            chunk.text = text;
            chunk.payload = text.substring(newline + 1);
            return chunk;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // qCompress / qUncompress compatible.
    private static String pack(String json) {
        try {
            final byte[] raw = json.getBytes("UTF-8");
            final Deflater deflater = new Deflater(9);
            deflater.setInput(raw);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(ByteBuffer.allocate(4).putInt(raw.length).array());
            final byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                final int count = deflater.deflate(buffer);
                out.write(buffer, 0, count);
            }
            deflater.end();
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String unpack(String packed) {
        try {
            final byte[] data = Base64.decode(packed, Base64.DEFAULT);
            if (data.length < 5) {
                return null;
            }
            final Inflater inflater = new Inflater();
            inflater.setInput(data, 4, data.length - 4);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                final int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, count);
            }
            inflater.end();
            return out.toString("UTF-8");
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void sync() {
        if (!started) {
            return;
        }
        if (syncing) {
            dirty = true;
            return;
        }
        syncing = true;
        final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = new TLRPC.TL_inputPeerSelf();
        req.q = MARKER;
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        req.limit = SEARCH_LIMIT;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                fail(error);
                return;
            }
            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            getMessagesController().putUsers(res.users, false);
            getMessagesController().putChats(res.chats, false);
            final ArrayList<Chunk> chunks = new ArrayList<>();
            for (TLRPC.Message message : res.messages) {
                final Chunk chunk = parseChunk(message.id, message.message);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
            gotChunks(chunks);
        }));
    }

    private void gotChunks(ArrayList<Chunk> chunks) {
        final TreeMap<Long, ArrayList<Chunk>> groups = new TreeMap<>();
        for (Chunk chunk : chunks) {
            ArrayList<Chunk> group = groups.get(chunk.rev);
            if (group == null) {
                groups.put(chunk.rev, group = new ArrayList<>());
            }
            group.add(chunk);
        }
        // The newest complete version.
        ArrayList<Chunk> best = new ArrayList<>();
        for (Long rev : groups.descendingKeySet()) {
            final ArrayList<Chunk> group = groups.get(rev);
            final int count = group.get(0).count;
            final HashSet<Integer> indices = new HashSet<>();
            boolean consistent = true;
            for (Chunk chunk : group) {
                consistent = consistent && chunk.count == count;
                indices.add(chunk.index);
            }
            if (consistent && group.size() == count && indices.size() == count) {
                best = new ArrayList<>(group);
                Collections.sort(best, new Comparator<Chunk>() {
                    @Override
                    public int compare(Chunk a, Chunk b) {
                        return Integer.compare(a.index, b.index);
                    }
                });
                break;
            }
        }
        if (best.isEmpty() && !chunks.isEmpty() && incompleteRetries < INCOMPLETE_RETRIES) {
            // Somebody is in the middle of writing, let them finish.
            incompleteRetries++;
            syncing = false;
            schedule(INCOMPLETE_RETRY_DELAY);
            return;
        }
        incompleteRetries = 0;

        final ZsgFolders folders = ZsgFolders.getInstance(currentAccount);
        String remoteJson = "";
        if (!best.isEmpty()) {
            final StringBuilder packed = new StringBuilder();
            for (Chunk chunk : best) {
                packed.append(chunk.payload);
            }
            final String unpacked = unpack(packed.toString());
            if (unpacked != null && !unpacked.isEmpty()) {
                remoteJson = unpacked;
                folders.mergeRemote(remoteJson);
            }
        }

        final ArrayList<Integer> stale = new ArrayList<>();
        for (Chunk chunk : chunks) {
            boolean inBest = false;
            for (Chunk b : best) {
                if (b.id == chunk.id) {
                    inBest = true;
                    break;
                }
            }
            if (!inBest) {
                stale.add(chunk.id);
            }
        }

        final String desired = folders.serializeForSync(remoteJson);
        final boolean hasFolders = desired.contains("\"uid\"");
        final boolean same = !remoteJson.isEmpty() && desired.equals(ZsgFolders.canonical(remoteJson));
        final int[] requests = new int[1];

        if (same || (!hasFolders && best.isEmpty())) {
            deleteIds(stale, requests);
            if (requests[0] == 0) {
                finish();
            }
            return;
        }

        final String packed = pack(desired);
        if (packed == null) {
            finish();
            return;
        }
        final int count = (packed.length() + CHUNK_CHARS - 1) / CHUNK_CHARS;
        if (count > MAX_CHUNKS) {
            final int kb = packed.length() / 1024;
            if (notifiedTooLargeKb != kb) {
                notifiedTooLargeKb = kb;
                notify(LocaleController.formatString(R.string.ZsgFoldersSyncTooLarge, kb, MAX_CHUNKS * CHUNK_CHARS / 1024));
            }
            deleteIds(stale, requests);
            if (requests[0] == 0) {
                finish();
            }
            return;
        }
        notifiedTooLargeKb = 0;

        final long rev = System.currentTimeMillis();
        final long selfId = getUserConfig().getClientUserId();
        for (int i = 0; i < count; i++) {
            final String text = MARKER + " v1 " + rev + " " + (i + 1) + "/" + count + "\n"
                + packed.substring(i * CHUNK_CHARS, Math.min(packed.length(), (i + 1) * CHUNK_CHARS));
            if (i < best.size()) {
                if (!best.get(i).text.equals(text)) {
                    final TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
                    req.peer = new TLRPC.TL_inputPeerSelf();
                    req.id = best.get(i).id;
                    req.message = text;
                    req.flags |= 2048;
                    track(req, requests);
                }
            } else {
                final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, selfId);
                params.notify = false;
                getSendMessagesHelper().sendMessage(params);
            }
        }
        for (int i = count; i < best.size(); i++) {
            stale.add(best.get(i).id);
        }
        deleteIds(stale, requests);
        if (requests[0] == 0) {
            finish();
        }
    }

    private void deleteIds(ArrayList<Integer> ids, int[] requests) {
        if (ids.isEmpty()) {
            return;
        }
        final TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
        req.id.addAll(ids);
        track(req, requests);
    }

    private void track(TLObject request, int[] requests) {
        requests[0]++;
        getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                requests[0] = 0;
                fail(error);
                return;
            }
            if (--requests[0] <= 0) {
                finish();
            }
        }));
    }

    private void finish() {
        syncing = false;
        if (dirty) {
            dirty = false;
            schedule(DEBOUNCE);
        }
    }

    private void fail(TLRPC.TL_error error) {
        syncing = false;
        final String text = error != null ? error.text : "";
        if (text != null && text.startsWith("FLOOD_WAIT_")) {
            int seconds = 1;
            try {
                seconds = Math.max(1, Integer.parseInt(text.substring(11)));
            } catch (NumberFormatException ignore) {
            }
            notify(LocaleController.formatString(R.string.ZsgFoldersSyncFlood, seconds));
            schedule((seconds + 2) * 1000L);
        } else if ("MESSAGE_NOT_MODIFIED".equals(text)) {
            finish();
        } else {
            FileLog.e("ZaStoGram folders sync failed: " + text);
            schedule(RETRY_DELAY);
        }
    }

    private void notify(String text) {
        AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show());
    }
}
