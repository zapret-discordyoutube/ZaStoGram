/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.messenger;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Records Android's reason for the previous process death in the next session log. */
public final class ProcessExitDiagnostics {

    private static final String PREFS_NAME = "runtime_exit_diagnostics";
    private static final String LAST_REPORTED_PREFIX = "last_reported_";

    // Android records only "java_crash" for a Java crash, and the stack sent
    // to the session log often never reaches the file: the process dies with
    // the async writer mid-line (logs (7): the last line cut in half, no
    // stack). The handler below writes the stack synchronously to its own
    // small file, and the next session prints it next to previous_process_exit.
    private static final String CRASH_FILE = "zasto_last_crash.txt";
    private static final int MAX_CRASH_BYTES = 32 * 1024;
    private static volatile boolean crashRecorderInstalled;

    private ProcessExitDiagnostics() {
    }

    public static void installCrashRecorder(Context context) {
        if (crashRecorderInstalled || context == null) {
            return;
        }
        crashRecorderInstalled = true;
        final File file = new File(context.getFilesDir(), CRASH_FILE);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            try {
                StringWriter text = new StringWriter();
                PrintWriter writer = new PrintWriter(text);
                writer.println("time_ms=" + System.currentTimeMillis() + " thread=" + (thread != null ? thread.getName() : "null"));
                exception.printStackTrace(writer);
                writer.flush();
                byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(bytes, 0, Math.min(bytes.length, MAX_CRASH_BYTES));
                    out.getFD().sync();
                }
            } catch (Throwable ignore) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, exception);
            }
        });
    }

    public static void logPreviousCrashStack(Context context) {
        if (!BuildVars.LOGS_ENABLED || context == null) {
            return;
        }
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.exists()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), MAX_CRASH_BYTES)];
            int read = 0;
            while (read < bytes.length) {
                int count = in.read(bytes, read, bytes.length - read);
                if (count <= 0) {
                    break;
                }
                read += count;
            }
            FileLog.persistDiagnostic("previous_crash_stack " + new String(bytes, 0, read, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            FileLog.e("previous_crash_stack unavailable", t);
        }
        file.delete();
    }

    public static void logPreviousExit(Context context) {
        if (!BuildVars.LOGS_ENABLED || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || context == null) {
            return;
        }
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) {
                return;
            }
            String processName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? Application.getProcessName() : context.getPackageName();
            SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String key = LAST_REPORTED_PREFIX + processName.replace(':', '_');
            long lastReported = preferences.getLong(key, 0);
            ApplicationExitInfo newest = null;
            List<ApplicationExitInfo> exits = activityManager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 16);
            for (ApplicationExitInfo exit : exits) {
                if (exit == null || exit.getTimestamp() <= lastReported
                        || !TextUtils.equals(processName, exit.getProcessName())) {
                    continue;
                }
                if (newest == null || exit.getTimestamp() > newest.getTimestamp()) {
                    newest = exit;
                }
            }
            if (newest == null) {
                return;
            }
            FileLog.persistDiagnostic("previous_process_exit process=" + processName
                    + " reason=" + reasonName(newest.getReason())
                    + " reason_code=" + newest.getReason()
                    + " status=" + newest.getStatus()
                    + " importance=" + newest.getImportance()
                    + " pss_kb=" + newest.getPss()
                    + " rss_kb=" + newest.getRss()
                    + " timestamp_ms=" + newest.getTimestamp()
                    + " description=" + safeDescription(newest.getDescription()));
            preferences.edit().putLong(key, newest.getTimestamp()).commit();
        } catch (Throwable t) {
            FileLog.e("previous_process_exit unavailable", t);
        }
    }

    private static String safeDescription(String description) {
        if (TextUtils.isEmpty(description)) {
            return "none";
        }
        return description.length() > 240 ? description.substring(0, 240) : description;
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "exit_self";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "low_memory";
            case ApplicationExitInfo.REASON_CRASH:
                return "java_crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "native_crash";
            case ApplicationExitInfo.REASON_ANR:
                return "anr";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "initialization_failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "permission_change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "excessive_resource_usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "user_requested";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "user_stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "dependency_died";
            case ApplicationExitInfo.REASON_OTHER:
                return "other";
            case ApplicationExitInfo.REASON_UNKNOWN:
            default:
                return "unknown";
        }
    }
}
