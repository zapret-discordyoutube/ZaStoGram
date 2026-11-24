#include "WebProxyJni.h"

#include <string>

#include "WebProxy.h"

namespace tgnet {
namespace naive {

namespace {

std::string ToString(JNIEnv *env, jstring s) {
    if (s == nullptr) {
        return std::string();
    }
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(s, chars);
    }
    return result;
}

jint nativeStart(JNIEnv *env, jclass, jstring host, jint port, jstring user, jstring password) {
    WebProxyConfig config;
    config.proxyHost = ToString(env, host);
    config.proxyPort = static_cast<uint16_t>(port > 0 && port < 65536 ? port : 443);
    config.user = ToString(env, user);
    config.password = ToString(env, password);
    if (config.proxyHost.empty()) {
        return 0;
    }
    return StartWebProxy(config);
}

void nativeStop(JNIEnv *, jclass) { StopWebProxy(); }

const JNINativeMethod kMethods[] = {
    {"native_start", "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)I", reinterpret_cast<void *>(nativeStart)},
    {"native_stop", "()V", reinterpret_cast<void *>(nativeStop)},
};

}  // namespace

bool RegisterWebProxyJni(JNIEnv *env) {
    jclass clazz = env->FindClass("org/telegram/zastogram/naive/NaiveWebProxy");
    if (clazz == nullptr) {
        return false;
    }
    return env->RegisterNatives(clazz, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) >= 0;
}

} // namespace naive
} // namespace tgnet
