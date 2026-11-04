package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * ZaStoGram's own folders: no count limit, any nesting, kept on the device and
 * optionally synchronized through Saved Messages (see ZsgFoldersSync). The JSON
 * format is shared with the Windows client (Data::LocalFolders).
 *
 * Each folder is shown through the ordinary DialogFilter machinery with an id
 * above ID_BASE (Telegram's own filter ids are small), see
 * MessagesController.applyLocalFolders().
 */
public class ZsgFolders extends BaseController {

    public static final int ID_BASE = 0x10000000;
    private static final int FORMAT_VERSION = 1;
    private static final long TOMBSTONE_LIFETIME = 30L * 24 * 60 * 60 * 1000;

    public static boolean isLocalId(int id) {
        return id >= ID_BASE;
    }

    public static class Folder {
        public String uid;
        public int id; // Local to this device.
        public String parentUid = "";
        public String title = "";
        public String icon = "";
        public int order;
        public boolean sync = true;
        public boolean deleted;
        public long rev;
        public LinkedHashSet<String> own = new LinkedHashSet<>(); // Peer keys, see keyOf().
        public ArrayList<String> pinned = new ArrayList<>();
    }

    private static volatile ZsgFolders[] Instance = new ZsgFolders[UserConfig.MAX_ACCOUNT_COUNT];

    public static ZsgFolders getInstance(int num) {
        ZsgFolders localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ZsgFolders.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ZsgFolders(num);
                }
            }
        }
        return localInstance;
    }

    private final ArrayList<Folder> folders = new ArrayList<>();
    private int nextNumber;
    private boolean loaded;

    public ZsgFolders(int num) {
        super(num);
    }

    // The same canonical peer keys as in the Windows client: u<id> for users, c<id> for
    // basic groups and ch<id> for channels.
    public String keyOf(long dialogId) {
        if (dialogId > 0) {
            return "u" + dialogId;
        }
        final long chatId = -dialogId;
        final TLRPC.Chat chat = getMessagesController().getChat(chatId);
        return (chat != null && ChatObject.isChannel(chat) ? "ch" : "c") + chatId;
    }

    public static long dialogIdOf(String key) {
        try {
            if (key.startsWith("ch")) {
                return -Long.parseLong(key.substring(2));
            } else if (key.startsWith("c")) {
                return -Long.parseLong(key.substring(1));
            } else if (key.startsWith("u")) {
                return Long.parseLong(key.substring(1));
            }
        } catch (NumberFormatException ignore) {
        }
        return 0;
    }

    private File file() {
        return new File(ApplicationLoader.getFilesDirFixed(), "zsg_folders_" + currentAccount + ".json");
    }

    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        final File file = file();
        if (!file.exists()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            final ArrayList<Folder> parsed = new ArrayList<>();
            final int[] next = new int[1];
            if (parse(out.toString("UTF-8"), true, parsed, next)) {
                final HashSet<Integer> seen = new HashSet<>();
                folders.clear();
                int max = next[0];
                for (Folder folder : parsed) {
                    if (!isLocalId(folder.id) || !seen.add(folder.id)) {
                        continue;
                    }
                    max = Math.max(max, folder.id - ID_BASE);
                    folders.add(folder);
                }
                nextNumber = max;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void save() {
        final String json = serialize();
        final File file = file();
        Utilities.globalQueue.postRunnable(() -> {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(json.getBytes("UTF-8"));
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void changed() {
        changed(false);
    }

    private void changed(boolean fromRemote) {
        save();
        AndroidUtilities.runOnUIThread(() -> {
            getMessagesController().applyLocalFolders();
            if (!fromRemote) {
                ZsgFoldersSync.getInstance(currentAccount).onFoldersChanged();
            }
        });
    }

    // Queries.

    public synchronized Folder find(int id) {
        for (Folder folder : folders) {
            if (folder.id == id && !folder.deleted) {
                return folder;
            }
        }
        return null;
    }

    private Folder findByUid(String uid) {
        for (Folder folder : folders) {
            if (folder.uid.equals(uid) && !folder.deleted) {
                return folder;
            }
        }
        return null;
    }

    private Folder findAnyByUid(String uid) {
        for (Folder folder : folders) {
            if (folder.uid.equals(uid)) {
                return folder;
            }
        }
        return null;
    }

    private ArrayList<Folder> childrenOfUid(String uid) {
        final ArrayList<Folder> result = new ArrayList<>();
        for (Folder folder : folders) {
            if (folder.deleted) {
                continue;
            }
            final boolean orphan = !folder.parentUid.isEmpty() && findByUid(folder.parentUid) == null;
            if (folder.parentUid.equals(uid) || (uid.isEmpty() && orphan)) {
                result.add(folder);
            }
        }
        Collections.sort(result, new Comparator<Folder>() {
            @Override
            public int compare(Folder a, Folder b) {
                if (a.order != b.order) {
                    return a.order < b.order ? -1 : 1;
                }
                return a.uid.compareTo(b.uid);
            }
        });
        return result;
    }

    /** Alive folders: top-level ones in order, each followed by its descendants. */
    public synchronized ArrayList<Folder> list() {
        final ArrayList<Folder> result = new ArrayList<>();
        walk("", result);
        return result;
    }

    private void walk(String uid, ArrayList<Folder> result) {
        for (Folder child : childrenOfUid(uid)) {
            result.add(child);
            walk(child.uid, result);
        }
    }

    public synchronized ArrayList<Folder> childrenOf(int parentId) {
        String uid = "";
        if (parentId != 0) {
            final Folder parent = find(parentId);
            if (parent == null) {
                return new ArrayList<>();
            }
            uid = parent.uid;
        }
        return childrenOfUid(uid);
    }

    public synchronized int parentOf(int id) {
        final Folder folder = find(id);
        if (folder == null || folder.parentUid.isEmpty()) {
            return 0;
        }
        final Folder parent = findByUid(folder.parentUid);
        return parent != null ? parent.id : 0;
    }

    public synchronized boolean isNested(int id) {
        return parentOf(id) != 0;
    }

    public synchronized int depth(int id) {
        int result = 0;
        for (int parent = parentOf(id); parent != 0 && result <= folders.size(); parent = parentOf(parent)) {
            result++;
        }
        return result;
    }

    /** "Work / Clients / VIP". */
    public synchronized String pathTitle(int id) {
        final ArrayList<String> parts = new ArrayList<>();
        int current = id;
        for (int guard = 0; current != 0 && guard <= folders.size(); guard++) {
            final Folder folder = find(current);
            if (folder == null) {
                break;
            }
            parts.add(0, folder.title);
            current = parentOf(current);
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append(" / ");
            }
            builder.append(parts.get(i));
        }
        return builder.toString();
    }

    /** Own chats and the chats of all subfolders. */
    public synchronized LinkedHashSet<String> peersOf(int id) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        final Folder folder = find(id);
        if (folder != null) {
            collectPeers(folder, result, 0);
        }
        return result;
    }

    private void collectPeers(Folder folder, LinkedHashSet<String> result, int guard) {
        if (guard > folders.size()) {
            return;
        }
        result.addAll(folder.own);
        for (Folder child : childrenOfUid(folder.uid)) {
            collectPeers(child, result, guard + 1);
        }
    }

    public synchronized boolean contains(int id, long dialogId) {
        final Folder folder = find(id);
        return folder != null && folder.own.contains(keyOf(dialogId));
    }

    // Changes.

    public int create(String title, int parentId) {
        final String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        final int id;
        synchronized (this) {
            String parentUid = "";
            if (parentId != 0) {
                final Folder parent = find(parentId);
                if (parent == null) {
                    return 0;
                }
                parentUid = parent.uid;
            }
            int order = 0;
            for (Folder sibling : childrenOfUid(parentUid)) {
                order = Math.max(order, sibling.order + 1);
            }
            final Folder folder = new Folder();
            folder.uid = UUID.randomUUID().toString();
            folder.id = ID_BASE + (++nextNumber);
            folder.parentUid = parentUid;
            folder.title = trimmed;
            folder.order = order;
            folder.rev = System.currentTimeMillis();
            folders.add(folder);
            id = folder.id;
        }
        changed();
        return id;
    }

    public boolean rename(int id, String title) {
        synchronized (this) {
            final Folder folder = find(id);
            final String trimmed = title == null ? "" : title.trim();
            if (folder == null || trimmed.isEmpty() || folder.title.equals(trimmed)) {
                return false;
            }
            folder.title = trimmed;
            folder.rev = System.currentTimeMillis();
        }
        changed();
        return true;
    }

    public boolean move(int id, int newParentId) {
        synchronized (this) {
            final Folder folder = find(id);
            if (folder == null) {
                return false;
            }
            String parentUid = "";
            if (newParentId != 0) {
                if (newParentId == id) {
                    return false;
                }
                final Folder parent = find(newParentId);
                if (parent == null) {
                    return false;
                }
                // The new parent must not be inside the moved subtree.
                int up = newParentId;
                for (int guard = 0; up != 0 && guard <= folders.size(); guard++, up = parentOf(up)) {
                    if (up == id) {
                        return false;
                    }
                }
                parentUid = parent.uid;
            }
            if (folder.parentUid.equals(parentUid)) {
                return false;
            }
            int order = 0;
            for (Folder sibling : childrenOfUid(parentUid)) {
                order = Math.max(order, sibling.order + 1);
            }
            folder.parentUid = parentUid;
            folder.order = order;
            folder.rev = System.currentTimeMillis();
        }
        changed();
        return true;
    }

    private void collectSubtree(String uid, ArrayList<Folder> result, int guard) {
        if (guard > folders.size()) {
            return;
        }
        for (Folder folder : folders) {
            if (!folder.deleted && folder.parentUid.equals(uid)) {
                result.add(folder);
                collectSubtree(folder.uid, result, guard + 1);
            }
        }
    }

    public boolean remove(int id) {
        synchronized (this) {
            final Folder folder = find(id);
            if (folder == null) {
                return false;
            }
            final ArrayList<Folder> subtree = new ArrayList<>();
            subtree.add(folder);
            collectSubtree(folder.uid, subtree, 0);
            final long now = System.currentTimeMillis();
            for (Folder entry : subtree) {
                entry.deleted = true;
                entry.own.clear();
                entry.pinned.clear();
                entry.rev = now;
            }
        }
        changed();
        return true;
    }

    public boolean setSync(int id, boolean sync) {
        synchronized (this) {
            final Folder folder = find(id);
            if (folder == null || folder.sync == sync) {
                return false;
            }
            folder.sync = sync;
            folder.rev = System.currentTimeMillis();
        }
        changed();
        return true;
    }

    public boolean addChat(int id, long dialogId) {
        synchronized (this) {
            final Folder folder = find(id);
            if (folder == null || dialogId == 0 || !folder.own.add(keyOf(dialogId))) {
                return false;
            }
            folder.rev = System.currentTimeMillis();
        }
        changed();
        return true;
    }

    /** From the folder and all its subfolders (a parent shows their chats). */
    public boolean removeChatDeep(int id, long dialogId) {
        boolean any = false;
        synchronized (this) {
            final Folder folder = find(id);
            if (folder == null) {
                return false;
            }
            final ArrayList<Folder> subtree = new ArrayList<>();
            subtree.add(folder);
            collectSubtree(folder.uid, subtree, 0);
            final String key = keyOf(dialogId);
            final long now = System.currentTimeMillis();
            for (Folder entry : subtree) {
                if (entry.own.remove(key)) {
                    entry.pinned.remove(key);
                    entry.rev = now;
                    any = true;
                }
            }
        }
        if (any) {
            changed();
        }
        return any;
    }

    /** From every folder, for example when the chat is left. */
    public boolean removeChatEverywhere(long dialogId) {
        boolean any = false;
        synchronized (this) {
            final String key = keyOf(dialogId);
            final long now = System.currentTimeMillis();
            for (Folder folder : folders) {
                if (!folder.deleted && folder.own.remove(key)) {
                    folder.pinned.remove(key);
                    folder.rev = now;
                    any = true;
                }
            }
        }
        if (any) {
            changed();
        }
        return any;
    }

    /** Replaces the chats added directly to the folder. */
    public boolean setChats(int id, Collection<Long> dialogIds) {
        synchronized (this) {
            final Folder folder = find(id);
            if (folder == null) {
                return false;
            }
            final LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (Long dialogId : dialogIds) {
                keys.add(keyOf(dialogId));
            }
            if (keys.equals(folder.own)) {
                return false;
            }
            folder.own = keys;
            folder.pinned.retainAll(keys);
            folder.rev = System.currentTimeMillis();
        }
        changed();
        return true;
    }

    public boolean setPinned(int id, List<Long> dialogIds) {
        synchronized (this) {
            final Folder folder = find(id);
            if (folder == null) {
                return false;
            }
            final ArrayList<String> keys = new ArrayList<>();
            for (Long dialogId : dialogIds) {
                keys.add(keyOf(dialogId));
            }
            if (keys.equals(folder.pinned)) {
                return false;
            }
            folder.pinned = keys;
            folder.rev = System.currentTimeMillis();
        }
        changed();
        return true;
    }

    public synchronized ArrayList<Long> pinnedOf(int id) {
        final ArrayList<Long> result = new ArrayList<>();
        final Folder folder = find(id);
        if (folder != null) {
            for (String key : folder.pinned) {
                final long dialogId = dialogIdOf(key);
                if (dialogId != 0) {
                    result.add(dialogId);
                }
            }
        }
        return result;
    }

    /** Among siblings: -1 is up, +1 is down. */
    public boolean moveBy(int id, int delta) {
        synchronized (this) {
            final Folder folder = find(id);
            if (folder == null || delta == 0) {
                return false;
            }
            final String parentUid = parentOf(id) != 0 ? folder.parentUid : "";
            final ArrayList<Folder> ordered = childrenOfUid(parentUid);
            final int index = ordered.indexOf(folder);
            final int target = index + delta;
            if (index < 0 || target < 0 || target >= ordered.size()) {
                return false;
            }
            Collections.swap(ordered, index, target);
            applySiblingOrder(ordered);
        }
        changed();
        return true;
    }

    /** Orders the top-level folders as in the list (others keep their place after them). */
    public void reorderTop(List<Integer> ids) {
        synchronized (this) {
            final ArrayList<Folder> ordered = new ArrayList<>();
            for (Integer id : ids) {
                final Folder folder = find(id);
                if (folder != null && parentOf(id) == 0 && !ordered.contains(folder)) {
                    ordered.add(folder);
                }
            }
            for (Folder sibling : childrenOfUid("")) {
                if (!ordered.contains(sibling)) {
                    ordered.add(sibling);
                }
            }
            applySiblingOrder(ordered);
        }
        changed();
    }

    private void applySiblingOrder(ArrayList<Folder> ordered) {
        final long now = System.currentTimeMillis();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).order != i) {
                ordered.get(i).order = i;
                ordered.get(i).rev = now;
            }
        }
    }

    // JSON.

    private static JSONObject toJson(Folder folder, boolean withId) throws JSONException {
        final ArrayList<String> peers = new ArrayList<>(folder.own);
        Collections.sort(peers); // Stable bytes for sync.
        final JSONObject object = new JSONObject();
        object.put("uid", folder.uid);
        if (withId) {
            object.put("id", folder.id);
        }
        object.put("parent", folder.parentUid);
        object.put("title", folder.title);
        object.put("icon", folder.icon);
        object.put("order", folder.order);
        object.put("sync", folder.sync);
        object.put("deleted", folder.deleted);
        object.put("rev", folder.rev);
        object.put("peers", new JSONArray(peers));
        object.put("pinned", new JSONArray(folder.pinned));
        return object;
    }

    private static Folder fromJson(JSONObject object) {
        final Folder folder = new Folder();
        folder.uid = object.optString("uid", "");
        folder.id = object.optInt("id", 0);
        folder.parentUid = object.optString("parent", "");
        folder.title = object.optString("title", "");
        folder.icon = object.optString("icon", "");
        folder.order = object.optInt("order", 0);
        folder.sync = object.optBoolean("sync", true);
        folder.deleted = object.optBoolean("deleted", false);
        folder.rev = (long) object.optDouble("rev", 0);
        final JSONArray peers = object.optJSONArray("peers");
        if (peers != null) {
            for (int i = 0; i < peers.length(); i++) {
                final String key = peers.optString(i, "");
                if (dialogIdOf(key) != 0) {
                    folder.own.add(key);
                }
            }
        }
        final JSONArray pinned = object.optJSONArray("pinned");
        if (pinned != null) {
            for (int i = 0; i < pinned.length(); i++) {
                final String key = pinned.optString(i, "");
                if (dialogIdOf(key) != 0) {
                    folder.pinned.add(key);
                }
            }
        }
        return folder;
    }

    private static boolean parse(String json, boolean withId, ArrayList<Folder> result, int[] next) {
        try {
            final JSONObject root = new JSONObject(json);
            if (root.optInt("v", 0) != FORMAT_VERSION) {
                return false;
            }
            next[0] = root.optInt("next", 0);
            final JSONArray array = root.optJSONArray("folders");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    final Folder folder = fromJson(array.getJSONObject(i));
                    if (!folder.uid.isEmpty()) {
                        result.add(folder);
                    }
                }
            }
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    private synchronized String serialize() {
        try {
            final JSONArray array = new JSONArray();
            for (Folder folder : folders) {
                array.put(toJson(folder, true));
            }
            final JSONObject root = new JSONObject();
            root.put("v", FORMAT_VERSION);
            root.put("next", nextNumber);
            root.put("folders", array);
            return root.toString();
        } catch (JSONException e) {
            FileLog.e(e);
            return "";
        }
    }

    /**
     * Canonical JSON of the synchronized folders: no device-local ids and a fixed order, so
     * equal states give equal text on every device. Entries of {@code remote} that this
     * device does not own (local-only here, or tombstones of folders it never saw) are
     * carried over, so that writing never drops them for the other devices.
     */
    public synchronized String serializeForSync(String remote) {
        try {
            final TreeMap<String, JSONObject> entries = new TreeMap<>();
            for (Folder folder : folders) {
                if (folder.sync) {
                    entries.put(folder.uid, toJson(folder, false));
                }
            }
            if (remote != null && !remote.isEmpty()) {
                final JSONObject root = new JSONObject(remote);
                final JSONArray array = root.optJSONArray("folders");
                final long limit = System.currentTimeMillis() - TOMBSTONE_LIFETIME;
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        final JSONObject object = array.getJSONObject(i);
                        final String uid = object.optString("uid", "");
                        final boolean stale = object.optBoolean("deleted", false)
                            && (long) object.optDouble("rev", 0) < limit;
                        if (!uid.isEmpty() && !stale && !entries.containsKey(uid)) {
                            entries.put(uid, object);
                        }
                    }
                }
            }
            final JSONArray array = new JSONArray();
            for (JSONObject object : entries.values()) {
                array.put(object);
            }
            final JSONObject root = new JSONObject();
            root.put("v", FORMAT_VERSION);
            root.put("folders", array);
            return canonical(root.toString());
        } catch (JSONException e) {
            FileLog.e(e);
            return "";
        }
    }

    /** Re-serializes with sorted keys, so texts produced by different clients compare equal. */
    public static String canonical(String json) {
        try {
            return sorted(new JSONObject(json)).toString();
        } catch (JSONException e) {
            return json;
        }
    }

    private static Object sortedValue(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            return sorted((JSONObject) value);
        } else if (value instanceof JSONArray) {
            final JSONArray source = (JSONArray) value;
            final JSONArray result = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                result.put(sortedValue(source.get(i)));
            }
            return result;
        }
        return value;
    }

    private static JSONObject sorted(JSONObject object) throws JSONException {
        final TreeMap<String, Object> map = new TreeMap<>();
        final java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            map.put(key, sortedValue(object.get(key)));
        }
        // org.json keeps the insertion order of a LinkedHashMap on Android.
        final JSONObject result = new JSONObject();
        for (java.util.Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** Per-folder newest-wins merge, local-only folders are never touched. */
    public boolean mergeRemote(String json) {
        boolean any = false;
        synchronized (this) {
            final ArrayList<Folder> remote = new ArrayList<>();
            if (!parse(json, false, remote, new int[1])) {
                return false;
            }
            for (Folder incoming : remote) {
                final Folder folder = findAnyByUid(incoming.uid);
                if (folder == null) {
                    if (incoming.deleted) {
                        continue; // Nothing to delete here.
                    }
                    incoming.id = ID_BASE + (++nextNumber);
                    incoming.sync = true;
                    folders.add(incoming);
                    any = true;
                } else if (!folder.sync) {
                    continue; // Local-only folders are never touched.
                } else if (incoming.rev > folder.rev) {
                    folder.parentUid = incoming.parentUid;
                    folder.title = incoming.title;
                    folder.icon = incoming.icon;
                    folder.order = incoming.order;
                    folder.own = incoming.own;
                    folder.pinned = incoming.pinned;
                    folder.deleted = incoming.deleted;
                    folder.rev = incoming.rev;
                    any = true;
                }
            }
            // Old tombstones have long been delivered everywhere.
            final long limit = System.currentTimeMillis() - TOMBSTONE_LIFETIME;
            for (int i = folders.size() - 1; i >= 0; i--) {
                if (folders.get(i).deleted && folders.get(i).rev < limit) {
                    folders.remove(i);
                    any = true;
                }
            }
        }
        if (any) {
            changed(true);
        }
        return any;
    }
}
