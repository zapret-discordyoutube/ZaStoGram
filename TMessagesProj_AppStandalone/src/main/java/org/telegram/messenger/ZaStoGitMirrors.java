package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.web.HttpGetTask;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * git.zapret.moe is dropped by name inside Russia, so the updater has to reach the same Forgejo
 * through its mirrors: numbered (git2, git3...) and, since 2026-10-30, random labels, which are the
 * names handed out. Names are taken in this order: the host that worked last time, the list the
 * mirror controller publishes at /_sites/mirrors.json (cached after every successful check) and
 * the built-in names. When every one of them is silent, the names come from an entry page on a big
 * platform (the project's Apps Script page on script.google.com and its gist, schema 1,
 * families.git.hosts), the same lists ZapretGUI and Zapret KVN read.
 *
 * A name is accepted only from those lists and only as a *.zapret.moe name that is not another
 * site's numbered name (wiki2...), so no list can send the updater outside the project's domain.
 */
final class ZaStoGitMirrors {

    static final String CANONICAL = "git.zapret.moe";
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int READ_TIMEOUT_MS = 10000;

    private static final int MAX_HOSTS = 6;
    private static final Pattern NAME = Pattern.compile("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.zapret\\.moe$");
    private static final Pattern OTHER_SITE = Pattern.compile("^wiki\\d*\\.zapret\\.moe$");
    private static final String[] BUILTIN = {"git2.zapret.moe", "git3.zapret.moe", "git4.zapret.moe", CANONICAL};
    static final String[] ENTRY_LISTS = {
            "https://script.google.com/macros/s/AKfycbzcEPk67WW6fYC0vPVm397QyDFHd4m-SrfKDBnfvG8jAD-n_HXVv8AqQnEHZ6A42oIh/exec?format=json",
            "https://gist.githubusercontent.com/fuckyourkn/ecb9b6a03f53bddcdd47023ebc12d4f9/raw/targets.json",
    };

    private ZaStoGitMirrors() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("forgejo_mirrors", Activity.MODE_PRIVATE);
    }

    static boolean isGitHost(String host) {
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        return NAME.matcher(name).matches() && !OTHER_SITE.matcher(name).matches();
    }

    /** Candidates in the order they should be tried. */
    static List<String> hosts() {
        ArrayList<String> result = new ArrayList<>();
        SharedPreferences prefs = prefs();
        add(result, prefs.getString("good", null));
        String names = prefs.getString("names", "");
        if (!TextUtils.isEmpty(names)) {
            for (String name : names.split("\n")) {
                add(result, name);
            }
        }
        for (String name : BUILTIN) {
            add(result, name);
        }
        while (result.size() > MAX_HOSTS) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static void add(List<String> list, String host) {
        if (host == null) {
            return;
        }
        host = host.trim().toLowerCase(Locale.ROOT);
        if (isGitHost(host) && !list.contains(host)) {
            list.add(host);
        }
    }

    /** The same URL on another host; URLs of other sites stay as they are. */
    static String withHost(String url, String host) {
        try {
            URI uri = new URI(url);
            if (!isGitHost(uri.getHost())) {
                return url;
            }
            return new URI(uri.getScheme(), uri.getUserInfo(), host, uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (Exception e) {
            FileLog.e(e);
            return url;
        }
    }

    /** What is stored: mirrors answer with their own host, the stored URL must not depend on it. */
    static String canonical(String url) {
        return withHost(url, CANONICAL);
    }

    static void rememberGood(String host) {
        if (!isGitHost(host)) {
            return;
        }
        prefs().edit().putString("good", host).apply();
        new HttpGetTask(result -> {
            List<String> names = parseNames(result);
            if (!names.isEmpty()) {
                prefs().edit().putString("names", TextUtils.join("\n", names)).apply();
            }
        }).setTimeouts(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).execute("https://" + host + "/_sites/mirrors.json");
    }

    /**
     * Names from the entry pages, tried one after another; the first list that gives names is
     * remembered in front of the cached ones. The callback runs on the UI thread with the names
     * not tried yet (empty when no list answered).
     */
    static void fetchEntryNames(List<String> tried, Utilities.Callback<List<String>> done) {
        fetchEntryNames(tried, 0, done);
    }

    private static void fetchEntryNames(List<String> tried, int index, Utilities.Callback<List<String>> done) {
        if (index >= ENTRY_LISTS.length) {
            AndroidUtilities.runOnUIThread(() -> done.run(new ArrayList<>()));
            return;
        }
        new HttpGetTask(result -> {
            ArrayList<String> fresh = new ArrayList<>();
            for (String name : parseEntry(result)) {
                if (!tried.contains(name)) {
                    fresh.add(name);
                }
            }
            if (fresh.isEmpty()) {
                fetchEntryNames(tried, index + 1, done);
                return;
            }
            prefs().edit().putString("names", TextUtils.join("\n", fresh)).apply();
            AndroidUtilities.runOnUIThread(() -> done.run(fresh));
        }).setTimeouts(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS).execute(ENTRY_LISTS[index]);
    }

    /** Git names of an entry page (schema 1, families.git.hosts), in its order. */
    static List<String> parseEntry(String json) {
        ArrayList<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return result;
        }
        try {
            JSONObject document = new JSONObject(json);
            if (document.optInt("schema") != 1) {
                return result;
            }
            JSONArray hosts = document.getJSONObject("families").getJSONObject("git").getJSONArray("hosts");
            for (int i = 0; i < hosts.length(); i++) {
                add(result, hosts.optString(i, null));
            }
        } catch (Exception ignore) {
            return new ArrayList<>();
        }
        return result;
    }

    /** Usable names of /_sites/mirrors.json, the preferred one first; blocked ones go last. */
    static List<String> parseNames(String json) {
        ArrayList<String> open = new ArrayList<>();
        ArrayList<String> rest = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return open;
        }
        try {
            JSONObject family = new JSONObject(json).getJSONObject("families").getJSONObject("git");
            String preferred = family.optString("preferred", "").toLowerCase(Locale.ROOT);
            JSONArray names = family.getJSONArray("names");
            for (int i = 0; i < names.length(); i++) {
                JSONObject entry = names.getJSONObject(i);
                String name = entry.optString("name", "").toLowerCase(Locale.ROOT);
                if (!isGitHost(name)) {
                    continue;
                }
                if (entry.optBoolean("blocked", false) || !entry.optBoolean("open", true)) {
                    rest.add(name);
                } else if (name.equals(preferred)) {
                    open.add(0, name);
                } else {
                    open.add(name);
                }
            }
        } catch (Exception ignore) {
            return new ArrayList<>();
        }
        open.addAll(rest);
        return open;
    }
}
