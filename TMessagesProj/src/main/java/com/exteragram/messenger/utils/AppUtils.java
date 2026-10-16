package com.exteragram.messenger.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.telegram.messenger.FileLog;

/** exteraGram's AppUtils subset used by plugins (JSON helper, internal logging entry point). */
public final class AppUtils {

    private static volatile Gson gson;

    private AppUtils() {
    }

    public static Gson getGson() {
        Gson value = gson;
        if (value == null) {
            synchronized (AppUtils.class) {
                if (gson == null) {
                    gson = new GsonBuilder().disableHtmlEscaping().create();
                }
                value = gson;
            }
        }
        return value;
    }

    /** Plugins hook this to mirror exteraGram's internal log; ZaStoGram routes it to FileLog. */
    public static void logInternal(String tag, String message) {
        FileLog.d("[" + tag + "] " + message);
    }

    public static void logInternal(String message) {
        FileLog.d(message);
    }
}
