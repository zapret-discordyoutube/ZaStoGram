#include "WarpEntry.h"

#include <arpa/inet.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>

#include "zsg_naive/zsg_naive.h"
#include "../tgnet/ConnectionsManager.h"

namespace tgnet {
namespace naive {

namespace {

std::mutex warpMutex;
zsg_warp_t *warpHandle = nullptr;  // guarded by warpMutex; the pointer is only freed under it
std::atomic<int> warpStateCache{ZSG_WARP_FAILED};

bool IsPublicIpv4(const sockaddr_in &address) {
    if (address.sin_family != AF_INET) {
        return false;
    }
    const uint32_t ip = ntohl(address.sin_addr.s_addr);
    const uint8_t a = ip >> 24;
    const uint8_t b = (ip >> 16) & 255;
    if (ip == 0 || a == 10 || a == 127 || a >= 224) {
        return false;
    }
    if (a == 172 && b >= 16 && b < 32) {
        return false;
    }
    if (a == 192 && b == 168) {
        return false;
    }
    if (a == 169 && b == 254) {
        return false;
    }
    if (a == 100 && b >= 64 && b < 128) {
        return false;
    }
    return true;
}

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

jobjectArray nativeKeypair(JNIEnv *env, jclass) {
    char priv[64] = {0};
    char pub[64] = {0};
    if (!zsg_warp_keypair(priv, pub, sizeof(priv))) {
        return nullptr;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(2, stringClass, nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(priv));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(pub));
    return result;
}

jboolean nativeStart(JNIEnv *env, jclass, jstring privateKey, jstring peerKey, jstring reserved, jstring address,
                     jstring endpointHost, jint endpointPort, jstring masqueKey, jstring masquePem, jstring masqueAddress,
                     jboolean masqueFirst) {
    const std::string priv = ToString(env, privateKey);
    const std::string peer = ToString(env, peerKey);
    const std::string reservedBytes = ToString(env, reserved);
    const std::string interfaceAddress = ToString(env, address);
    const std::string host = ToString(env, endpointHost);
    const std::string mKey = ToString(env, masqueKey);
    const std::string mPem = ToString(env, masquePem);
    const std::string mAddress = ToString(env, masqueAddress);
    std::lock_guard<std::mutex> lock(warpMutex);
    if (warpHandle != nullptr) {
        zsg_warp_stop(warpHandle);
        warpHandle = nullptr;
    }
    warpStateCache = ZSG_WARP_CONNECTING;
    const bool haveWireGuard = !priv.empty() && !peer.empty() && !interfaceAddress.empty() && !host.empty();
    const bool haveMasque = !mKey.empty() && !mPem.empty() && !mAddress.empty();
    zsg_warp_config config = {};
    config.private_key = priv.c_str();
    config.peer_public_key = peer.c_str();
    config.reserved = reservedBytes.empty() ? nullptr : reservedBytes.c_str();
    config.address = interfaceAddress.c_str();
    config.endpoint_host = host.c_str();
    config.endpoint_port = static_cast<uint16_t>(endpointPort > 0 && endpointPort < 65536 ? endpointPort : 2408);
    config.junk_count = 3;
    zsg_warp_masque_config masque = {};
    masque.private_key = mKey.c_str();
    masque.server_key_pem = mPem.c_str();
    masque.address = mAddress.c_str();
    masque.edges = nullptr;  // the built-in list of HTTP/2 edges
    masque.first = masqueFirst ? 1 : 0;
    uint16_t socksPort = 0;
    char error[256] = {0};
    if (haveWireGuard || haveMasque) {
        warpHandle = zsg_warp_start2(haveWireGuard ? &config : nullptr, haveMasque ? &masque : nullptr, &socksPort, error,
                                     sizeof(error));
    }
    if (warpHandle == nullptr) {
        warpStateCache = ZSG_WARP_FAILED;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jobjectArray nativeMasqueKeypair(JNIEnv *env, jclass) {
    char priv[512] = {0};
    char pub[512] = {0};
    if (!zsg_warp_masque_keypair(priv, pub, sizeof(priv))) {
        return nullptr;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(2, stringClass, nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(priv));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(pub));
    return result;
}

jint nativeTransport(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(warpMutex);
    return warpHandle == nullptr ? 0 : zsg_warp_transport(warpHandle);
}

jstring nativeStats(JNIEnv *env, jclass) {
    char text[300] = {0};
    {
        std::lock_guard<std::mutex> lock(warpMutex);
        if (warpHandle != nullptr) {
            zsg_warp_stats(warpHandle, text, sizeof(text));
        }
    }
    return env->NewStringUTF(text);
}

jint nativeState(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(warpMutex);
    if (warpHandle == nullptr) {
        warpStateCache = ZSG_WARP_FAILED;
        return -1;
    }
    const int state = zsg_warp_state(warpHandle);
    warpStateCache = state;
    return state;
}

void nativeStop(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(warpMutex);
    warpStateCache = ZSG_WARP_FAILED;
    if (warpHandle != nullptr) {
        zsg_warp_stop(warpHandle);
        warpHandle = nullptr;
    }
}

void nativeReconnect(JNIEnv *, jclass, jint account) {
    if (account >= 0 && account < MAX_ACCOUNT_COUNT) {
        ConnectionsManager::getInstance(account).warpEntryChanged();
    }
}

const JNINativeMethod kMethods[] = {
    {"native_reconnect", "(I)V", reinterpret_cast<void *>(nativeReconnect)},
    {"native_keypair", "()[Ljava/lang/String;", reinterpret_cast<void *>(nativeKeypair)},
    {"native_start",
     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;"
     "Ljava/lang/String;Z)Z",
     reinterpret_cast<void *>(nativeStart)},
    {"native_masqueKeypair", "()[Ljava/lang/String;", reinterpret_cast<void *>(nativeMasqueKeypair)},
    {"native_transport", "()I", reinterpret_cast<void *>(nativeTransport)},
    {"native_stats", "()Ljava/lang/String;", reinterpret_cast<void *>(nativeStats)},
    {"native_state", "()I", reinterpret_cast<void *>(nativeState)},
    {"native_stop", "()V", reinterpret_cast<void *>(nativeStop)},
};

}  // namespace

bool WarpRedirect(const sockaddr_in &address, sockaddr_in *redirected) {
    // The state cache keeps the hot path (every connect) off the mutex while the tunnel is down or the setting is off.
    if (warpStateCache.load() != ZSG_WARP_UP || !IsPublicIpv4(address)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(warpMutex);
    if (warpHandle == nullptr || zsg_warp_state(warpHandle) != ZSG_WARP_UP) {
        return false;
    }
    const uint16_t port = zsg_warp_forward_port(warpHandle, reinterpret_cast<const uint8_t *>(&address.sin_addr.s_addr),
                                                ntohs(address.sin_port));
    if (port == 0) {
        return false;
    }
    std::memset(redirected, 0, sizeof(*redirected));
    redirected->sin_family = AF_INET;
    redirected->sin_port = htons(port);
    redirected->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    return true;
}

bool RegisterWarpJni(JNIEnv *env) {
    jclass clazz = env->FindClass("org/telegram/zastogram/naive/WarpEntry");
    if (clazz == nullptr) {
        return false;
    }
    return env->RegisterNatives(clazz, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) >= 0;
}

} // namespace naive
} // namespace tgnet
