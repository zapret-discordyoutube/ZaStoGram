/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#ifndef FILELOG_H
#define FILELOG_H

#include "Defines.h"

class FileLog {
public:
    FileLog();
    void init(std::string path);
    static void fatal(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void e(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void w(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void d(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void ref(const char *message, ...) __attribute__((format (printf, 1, 2)));
    static void delref(const char *message, ...) __attribute__((format (printf, 1, 2)));

    static FileLog &getInstance();

private:
    static void writeNativeLogLine(int androidPriority, const char *fileSeverity, const char *stdoutSeverity, const char *message, va_list args);
    void rotateNativeLogIfNeededLocked();
    FILE *logFile = nullptr;
    std::string logPath;
    size_t logBytesWritten = 0;
    int64_t lastFlushMs = 0;
    pthread_mutex_t mutex;
};

extern bool LOGS_ENABLED;
extern bool NETWORK_DEBUG_LOGS_ENABLED;

#define DEBUG_FATAL FileLog::getInstance().fatal
#define DEBUG_E FileLog::getInstance().e
#define DEBUG_W FileLog::getInstance().w
#define DEBUG_D FileLog::getInstance().d

// typeid(...).name() is mangled ("14TL_api_request"); logs only need the
// readable part. Returns a pointer into the same static string.
inline const char *logTypeName(const char *mangled) {
    while (*mangled >= '0' && *mangled <= '9') {
        ++mangled;
    }
    return mangled;
}

#define DEBUG_REF FileLog::getInstance().ref
#define DEBUG_DELREF FileLog::getInstance().delref

#endif
