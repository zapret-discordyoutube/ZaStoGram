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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    // The stable build ships with logging off, so a tester whose app dies at
    // launch had nothing to send (report of 13.10.2026). The same death
    // records are therefore kept without the log switch, in one small file in
    // the logs folder - the "Logs" screen lists it and can send it. Only
    // crash-like exits are written; a normal swipe-away or an update is not.
    private static final String CRASH_REPORT_FILE = "zasto_crash_report.txt";
    private static final int MAX_CRASH_REPORT_BYTES = 96 * 1024;
    private static final int MAX_EXITS_PER_LAUNCH = 4;

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
        if (context == null) {
            return;
        }
        logCrashStackFile(context, new File(context.getFilesDir(), CRASH_FILE));
    }

    private static void logCrashStackFile(Context context, File file) {
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
            emit(context, "previous_crash_stack " + new String(bytes, 0, read, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            FileLog.e("previous_crash_stack unavailable", t);
        }
        file.delete();
    }

    /**
     * Logging is off in a stable build, so the log has nowhere to carry the previous death.
     * Called from Application.onCreate before the new crash recorder is installed: the old
     * stack file is moved aside at once (a launch that dies within milliseconds would
     * overwrite it), and the rest runs on a worker thread to keep the start path short.
     */
    public static void recordPreviousDeathWithoutLogs(final Context context) {
        if (context == null || BuildVars.LOGS_ENABLED) {
            return;
        }
        File moved = null;
        try {
            File file = new File(context.getFilesDir(), CRASH_FILE);
            if (file.exists()) {
                File target = new File(context.getFilesDir(), CRASH_FILE + ".prev");
                if (file.renameTo(target)) {
                    moved = target;
                }
            }
        } catch (Throwable ignore) {
        }
        final File stackFile = moved;
        Thread worker = new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    logPreviousExit(context);
                }
                if (stackFile != null) {
                    logCrashStackFile(context, stackFile);
                }
            } catch (Throwable ignore) {
            }
        }, "zasto_death_report");
        worker.setDaemon(true);
        worker.start();
    }

    private static void emit(Context context, String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.persistDiagnostic(message);
        } else {
            appendCrashReport(context, message);
        }
    }

    private static boolean headerWritten;

    private static synchronized void appendCrashReport(Context context, String message) {
        try {
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) {
                return;
            }
            File file = new File(dir, CRASH_REPORT_FILE);
            SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss.SSS", Locale.US);
            StringBuilder text = new StringBuilder();
            if (!headerWritten) {
                headerWritten = true;
                String version = "?";
                try {
                    android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                    version = info.versionName + " (" + info.versionCode + ")";
                } catch (Throwable ignore) {
                }
                text.append("----- launch ").append(format.format(new Date()))
                        .append(" app=").append(context.getPackageName()).append(' ').append(version)
                        .append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(" android=").append(Build.VERSION.RELEASE).append('/').append(Build.VERSION.SDK_INT)
                        .append(" abi=").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                        .append(" -----\n");
            }
            text.append(format.format(new Date())).append(' ').append(message).append('\n');
            byte[] add = text.toString().getBytes(StandardCharsets.UTF_8);
            byte[] keep = new byte[0];
            if (file.exists() && file.length() + add.length > MAX_CRASH_REPORT_BYTES) {
                // Keep the newer half and start from a whole line.
                int tail = MAX_CRASH_REPORT_BYTES / 2;
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                    long length = raf.length();
                    int size = (int) Math.min(length, tail);
                    byte[] buffer = new byte[size];
                    raf.seek(length - size);
                    raf.readFully(buffer);
                    int start = 0;
                    if (length > size) {
                        while (start < size && buffer[start] != '\n') {
                            start++;
                        }
                        start = Math.min(start + 1, size);
                    }
                    keep = java.util.Arrays.copyOfRange(buffer, start, size);
                }
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(keep);
                    out.write(add, 0, Math.min(add.length, MAX_CRASH_REPORT_BYTES / 2));
                }
            } else {
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(add, 0, Math.min(add.length, MAX_CRASH_REPORT_BYTES));
                }
            }
        } catch (Throwable ignore) {
        }
    }

    public static boolean isCrashReportFile(File file) {
        return file != null && CRASH_REPORT_FILE.equals(file.getName());
    }

    // Low-memory and signal kills of a background process are normal Android life, so they stay
    // out of the report; with logging on they are still logged as the newest exit.
    private static boolean isCrashLike(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return true;
            default:
                return false;
        }
    }

    public static void logPreviousExit(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || context == null) {
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
            long newestTimestamp = 0;
            ApplicationExitInfo newest = null;
            java.util.ArrayList<ApplicationExitInfo> crashes = new java.util.ArrayList<>();
            List<ApplicationExitInfo> exits = activityManager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 16);
            for (ApplicationExitInfo exit : exits) {
                if (exit == null || exit.getTimestamp() <= lastReported
                        || !TextUtils.equals(processName, exit.getProcessName())) {
                    continue;
                }
                newestTimestamp = Math.max(newestTimestamp, exit.getTimestamp());
                if (newest == null || exit.getTimestamp() > newest.getTimestamp()) {
                    newest = exit;
                }
                if (isCrashLike(exit.getReason())) {
                    crashes.add(exit);
                }
            }
            if (newest == null) {
                return;
            }
            // With logging on, the log carries the newest exit as before. Without it, a crash
            // may be followed by a force stop or an update, so every crash-like exit is kept.
            java.util.ArrayList<ApplicationExitInfo> report = new java.util.ArrayList<>();
            if (BuildVars.LOGS_ENABLED) {
                report.add(newest);
            } else {
                java.util.Collections.sort(crashes, (x, y) -> Long.compare(x.getTimestamp(), y.getTimestamp()));
                int from = Math.max(0, crashes.size() - MAX_EXITS_PER_LAUNCH);
                report.addAll(crashes.subList(from, crashes.size()));
            }
            for (ApplicationExitInfo exit : report) {
                emit(context, "previous_process_exit process=" + processName
                        + " reason=" + reasonName(exit.getReason())
                        + " reason_code=" + exit.getReason()
                        + " status=" + exit.getStatus()
                        + " importance=" + exit.getImportance()
                        + " pss_kb=" + exit.getPss()
                        + " rss_kb=" + exit.getRss()
                        + " timestamp_ms=" + exit.getTimestamp()
                        + " description=" + safeDescription(exit.getDescription()));
                if (exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    logNativeCrashStack(context, exit);
                }
            }
            preferences.edit().putLong(key, newestTimestamp).commit();
        } catch (Throwable t) {
            FileLog.e("previous_process_exit unavailable", t);
        }
    }

    // A native crash (SIGSEGV in tgcalls during a call, logs (1) (6)) leaves
    // only "native_crash status=11" above. Since Android 12 the system keeps
    // the tombstone as a protobuf (frameworks tombstone.proto); the signal,
    // the abort message and the crashing thread's backtrace are taken from it.
    private static final int MAX_NATIVE_FRAMES = 32;

    private static void logNativeCrashStack(Context context, ApplicationExitInfo exit) {
        try (java.io.InputStream in = exit.getTraceInputStream()) {
            if (in == null) {
                emit(context, "previous_native_crash_stack unavailable=no_trace");
                return;
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int count;
            while ((count = in.read(chunk)) > 0 && buffer.size() < 4 * 1024 * 1024) {
                buffer.write(chunk, 0, count);
            }
            emit(context, "previous_native_crash_stack " + formatTombstone(buffer.toByteArray()));
        } catch (Throwable t) {
            FileLog.e("previous_native_crash_stack unavailable", t);
        }
    }

    private static String formatTombstone(byte[] data) {
        long tid = -1;
        String signal = "";
        String abort = "";
        byte[] crashingThread = null;
        java.util.ArrayList<byte[]> threads = new java.util.ArrayList<>();
        Proto top = new Proto(data, 0, data.length);
        while (top.next()) {
            if (top.field == 6 && top.wire == 0) {
                tid = top.varint;
            } else if (top.field == 10 && top.wire == 2) {
                signal = formatSignal(top.bytes());
            } else if (top.field == 14 && top.wire == 2) {
                abort = new String(top.bytes(), StandardCharsets.UTF_8);
            } else if (top.field == 16 && top.wire == 2) {
                Proto entry = new Proto(top.bytes());
                long key = -1;
                byte[] value = null;
                while (entry.next()) {
                    if (entry.field == 1 && entry.wire == 0) {
                        key = entry.varint;
                    } else if (entry.field == 2 && entry.wire == 2) {
                        value = entry.bytes();
                    }
                }
                if (value != null) {
                    if (key == tid) {
                        crashingThread = value;
                    }
                    threads.add(value);
                }
            }
        }
        if (crashingThread == null && !threads.isEmpty()) {
            crashingThread = threads.get(0);
        }
        StringBuilder result = new StringBuilder();
        result.append("tid=").append(tid).append(" signal=").append(signal.isEmpty() ? "none" : signal);
        if (!abort.isEmpty()) {
            result.append(" abort=").append(abort.length() > 300 ? abort.substring(0, 300) : abort);
        }
        if (crashingThread != null) {
            Proto thread = new Proto(crashingThread);
            int frame = 0;
            while (thread.next()) {
                if (thread.field == 2 && thread.wire == 2) {
                    result.append(" thread=").append(new String(thread.bytes(), StandardCharsets.UTF_8));
                } else if (thread.field == 4 && thread.wire == 2 && frame < MAX_NATIVE_FRAMES) {
                    result.append("\n  #").append(frame++).append(' ').append(formatFrame(thread.bytes()));
                }
            }
        }
        return result.toString();
    }

    private static String formatSignal(byte[] data) {
        String name = "";
        String code = "";
        long address = 0;
        Proto signal = new Proto(data);
        while (signal.next()) {
            if (signal.field == 2 && signal.wire == 2) {
                name = new String(signal.bytes(), StandardCharsets.UTF_8);
            } else if (signal.field == 4 && signal.wire == 2) {
                code = new String(signal.bytes(), StandardCharsets.UTF_8);
            } else if (signal.field == 9 && signal.wire == 0) {
                address = signal.varint;
            }
        }
        return name + "/" + code + " fault_addr=0x" + Long.toHexString(address);
    }

    private static String formatFrame(byte[] data) {
        long relPc = 0;
        String function = "";
        long functionOffset = 0;
        String file = "";
        Proto frame = new Proto(data);
        while (frame.next()) {
            if (frame.field == 1 && frame.wire == 0) {
                relPc = frame.varint;
            } else if (frame.field == 4 && frame.wire == 2) {
                function = new String(frame.bytes(), StandardCharsets.UTF_8);
            } else if (frame.field == 5 && frame.wire == 0) {
                functionOffset = frame.varint;
            } else if (frame.field == 6 && frame.wire == 2) {
                file = new String(frame.bytes(), StandardCharsets.UTF_8);
            }
        }
        int slash = file.lastIndexOf('/');
        String shortFile = slash >= 0 ? file.substring(slash + 1) : file;
        return "pc=0x" + Long.toHexString(relPc) + " " + shortFile
                + (function.isEmpty() ? "" : " (" + function + "+" + functionOffset + ")");
    }

    // Minimal protobuf reader: varint and length-delimited fields, the only
    // wire types the tombstone fields above use; others are skipped.
    private static final class Proto {
        private final byte[] data;
        private int position;
        private final int end;
        int field;
        int wire;
        long varint;
        private int start;
        private int length;

        Proto(byte[] data) {
            this(data, 0, data.length);
        }

        Proto(byte[] data, int offset, int length) {
            this.data = data;
            this.position = offset;
            this.end = offset + length;
        }

        boolean next() {
            while (position < end) {
                long tag = readVarint();
                if (tag < 0) {
                    return false;
                }
                field = (int) (tag >>> 3);
                wire = (int) (tag & 7);
                if (wire == 0) {
                    varint = readVarint();
                    return true;
                } else if (wire == 2) {
                    long size = readVarint();
                    if (size < 0 || position + size > end) {
                        return false;
                    }
                    start = position;
                    length = (int) size;
                    position += (int) size;
                    return true;
                } else if (wire == 1) {
                    position += 8;
                } else if (wire == 5) {
                    position += 4;
                } else {
                    return false;
                }
            }
            return false;
        }

        byte[] bytes() {
            byte[] result = new byte[length];
            System.arraycopy(data, start, result, 0, length);
            return result;
        }

        private long readVarint() {
            long result = 0;
            int shift = 0;
            while (position < end && shift < 64) {
                byte b = data[position++];
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            return -1;
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
