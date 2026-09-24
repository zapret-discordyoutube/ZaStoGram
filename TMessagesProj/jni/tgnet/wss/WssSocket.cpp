/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 */

#include "WssSocket.h"
#include "../FileLog.h"
#include "rtc_base/ssl_roots.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <openssl/err.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <openssl/x509.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <map>
#include <mutex>

namespace tgnet {
namespace wss {
namespace {

constexpr const char *kOfficialPath = "/apiws";
constexpr const char *kTunnelHost = "edge.amberwick.workers.dev";
constexpr int32_t kTunnelOnlyDcId = 203;
// Size of the MTProto obfuscation header. Measured against the official relays:
// a first binary frame of 63 bytes never gets a reply, 64 always does.
constexpr size_t kObfuscationHeaderSize = 64;
constexpr uint32_t kMaxFrame = 2 * 1024 * 1024;
constexpr size_t kMaxHttpHeader = 32 * 1024;
constexpr size_t kMaxPendingOutput = 4 * 1024 * 1024;
constexpr size_t kMaxPendingInput = 4 * 1024 * 1024;
constexpr int64_t kFallbackPreferenceTtlMs = 30 * 60 * 1000;

struct RelayPreference {
    bool preferFallback = false;
    int64_t until = 0;
};

std::mutex relayPreferencesMutex;
std::map<std::string, RelayPreference> relayPreferences;

int64_t monotonicMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string relayPreferenceKey(const Route &route) {
    return route.relayHost + ":" + std::to_string(route.relayPort) + ":" + route.domain;
}

bool hasFallback(const Route &route) {
    return !route.relayHostFallback.empty() && route.relayHostFallback != route.relayHost;
}

bool preferFallback(const Route &route) {
    if (!hasFallback(route)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    auto it = relayPreferences.find(relayPreferenceKey(route));
    if (it == relayPreferences.end()) {
        return false;
    }
    if (it->second.until <= monotonicMillis()) {
        relayPreferences.erase(it);
        return false;
    }
    return it->second.preferFallback;
}

// Отдельный от выбора адреса учёт: у датацентра может не открываться ни один
// адрес релея (у DC1 порт 443 закрыт целиком у части провайдеров), либо релей
// пропускает TLS и WebSocket-upgrade, но молча глотает MTProto-байты (DPI).
// Держать такой датацентр в вечных попытках бессмысленно — данные оттуда не
// придут никогда, хотя прямое соединение может работать. После нескольких
// подряд попыток, ни одна из которых не принесла ни байта MTProto-данных,
// маршрут WSS для этого датацентра временно отключается, и клиент идёт к нему
// обычным путём. Счётчик сбрасывается только реально полученными данными.
constexpr uint32_t kRouteFailuresBeforeSuppress = 3;
// Туннель медленнее релея, поэтому держать в нём основной датацентр дольше
// пары минут дороже, чем лишний раз проверить релей.
constexpr int64_t kRouteSuppressTtlMs = 2 * 60 * 1000;
constexpr int64_t kMediaRouteSuppressTtlMs = 30 * 60 * 1000;
// На старте десятки соединений всех аккаунтов открываются разом, и их
// таймауты приходят пачкой. Одна пачка — один провал, а не «три подряд».
constexpr int64_t kRouteFailureCoalesceMs = 2000;
// Провайдер глотает SYN отдельного потока, а соседний сокет к тому же адресу
// проходит. Пока адрес недавно принимал TCP, таймаут соединения — шум потока,
// а не недоступный релей.
constexpr int64_t kRecentTcpSuccessMs = 30 * 1000;

struct RouteHealth {
    uint32_t consecutiveFailures = 0;
    int64_t suppressedUntil = 0;
    int64_t lastFailureAt = 0;
};

std::map<std::string, RouteHealth> routeHealth;
std::map<std::string, int64_t> tcpSuccessByAddress;

void recordTcpConnected(const std::string &address) {
    if (address.empty()) {
        return;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    tcpSuccessByAddress[address] = monotonicMillis();
}

bool tcpRecentlyConnected(const std::string &address) {
    if (address.empty()) {
        return false;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    auto it = tcpSuccessByAddress.find(address);
    return it != tcpSuccessByAddress.end() && monotonicMillis() - it->second < kRecentTcpSuccessMs;
}

bool routeSuppressed(const std::string &domain) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    auto it = routeHealth.find(domain);
    if (it == routeHealth.end() || it->second.suppressedUntil == 0) {
        return false;
    }
    if (it->second.suppressedUntil <= monotonicMillis()) {
        it->second.suppressedUntil = 0;
        it->second.consecutiveFailures = 0;
        return false;
    }
    return true;
}

void recordRouteUnreachable(const Route &route) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    RouteHealth &health = routeHealth[route.domain];
    const int64_t now = monotonicMillis();
    if (health.suppressedUntil > now) {
        return;
    }
    if (health.lastFailureAt != 0 && now - health.lastFailureAt < kRouteFailureCoalesceMs) {
        return;
    }
    health.lastFailureAt = now;
    // Медиа-релеи kwsN-1 провайдеры режут целиком, пока основной kwsN жив:
    // ждать три таймаута по 8 с значит полминуты без медиа, поэтому медиа
    // уходит в туннель после первой же неудачи и остаётся там дольше.
    const bool media = route.domain.find("-1.web.telegram.org") != std::string::npos;
    const uint32_t threshold = media ? 1 : kRouteFailuresBeforeSuppress;
    if (++health.consecutiveFailures >= threshold) {
        health.suppressedUntil = now + (media ? kMediaRouteSuppressTtlMs : kRouteSuppressTtlMs);
    }
}

void recordRouteReachable(const Route &route) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    RouteHealth &health = routeHealth[route.domain];
    health.consecutiveFailures = 0;
    health.suppressedUntil = 0;
    health.lastFailureAt = 0;
}

void recordAttemptFailed(const Route &route) {
    if (!hasFallback(route)) {
        return;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    if (route.viaFallback) {
        relayPreferences.erase(relayPreferenceKey(route));
    } else {
        relayPreferences[relayPreferenceKey(route)] = {
                true,
                monotonicMillis() + kFallbackPreferenceTtlMs,
        };
    }
}

void recordUpgradeSucceeded(const Route &route) {
    if (!hasFallback(route)) {
        return;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    if (route.viaFallback) {
        relayPreferences[relayPreferenceKey(route)] = {
                true,
                monotonicMillis() + kFallbackPreferenceTtlMs,
        };
    } else {
        relayPreferences.erase(relayPreferenceKey(route));
    }
}

std::string base64Encode(const uint8_t *data, size_t length) {
    static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    result.reserve(((length + 2) / 3) * 4);
    for (size_t i = 0; i < length; i += 3) {
        uint32_t chunk = static_cast<uint32_t>(data[i]) << 16;
        if (i + 1 < length) {
            chunk |= static_cast<uint32_t>(data[i + 1]) << 8;
        }
        if (i + 2 < length) {
            chunk |= data[i + 2];
        }
        result.push_back(alphabet[(chunk >> 18) & 0x3f]);
        result.push_back(alphabet[(chunk >> 12) & 0x3f]);
        result.push_back(i + 1 < length ? alphabet[(chunk >> 6) & 0x3f] : '=');
        result.push_back(i + 2 < length ? alphabet[chunk & 0x3f] : '=');
    }
    return result;
}

bool loadBundledRoots(SSL_CTX *context) {
    int loaded = 0;
    X509_STORE *store = SSL_CTX_get_cert_store(context);
    for (size_t i = 0; i < sizeof(kSSLCertCertificateList) / sizeof(kSSLCertCertificateList[0]); ++i) {
        const unsigned char *cursor = kSSLCertCertificateList[i];
        X509 *certificate = d2i_X509(nullptr, &cursor, static_cast<long>(kSSLCertCertificateSizeList[i]));
        if (certificate == nullptr) {
            ERR_clear_error();
            continue;
        }
        if (X509_STORE_add_cert(store, certificate) == 1) {
            ++loaded;
        } else {
            // Duplicate roots are harmless when the Android store was loaded.
            ERR_clear_error();
        }
        X509_free(certificate);
    }
    return loaded > 0;
}

SSL_CTX *wssSslContext() {
    static SSL_CTX *context = [] {
        SSL_CTX *created = SSL_CTX_new(TLS_client_method());
        if (created == nullptr) {
            return static_cast<SSL_CTX *>(nullptr);
        }
        SSL_CTX_set_min_proto_version(created, TLS1_2_VERSION);
        SSL_CTX_set_verify(created, SSL_VERIFY_PEER, nullptr);
        const bool androidRoots = SSL_CTX_load_verify_locations(
                created,
                nullptr,
                "/system/etc/security/cacerts") == 1;
        ERR_clear_error();
        const bool bundledRoots = loadBundledRoots(created);
        if (!androidRoots && !bundledRoots) {
            SSL_CTX_free(created);
            return static_cast<SSL_CTX *>(nullptr);
        }
        return created;
    }();
    return context;
}

void setDiagnostic(std::string *diagnostic, const char *value) {
    if (diagnostic != nullptr) {
        *diagnostic = value;
    }
}

const char *officialRelayIpForDc(int32_t dcId) {
    // These are direct Telegram Web ingress addresses, not a proxy. The
    // kwsN hostname remains the TLS identity and the automatic fallback.
    switch (dcId) {
        case 1:
        case 3:
            return "149.154.174.100";
        case 2:
        case 4:
            return "149.154.167.220";
        case 5:
            return "149.154.170.100";
        default:
            return nullptr;
    }
}

} // namespace

static bool TunnelRoute(const std::string &dcAddress, Route *route) {
    struct in_addr parsed;
    if (inet_pton(AF_INET, dcAddress.c_str(), &parsed) != 1) {
        return false;
    }
    Route result;
    result.relayHost = kTunnelHost;
    result.connectHost = kTunnelHost;
    result.relayPort = 443;
    result.domain = kTunnelHost;
    result.path = std::string(kOfficialPath) + "?dst=" + dcAddress;
    result.tunnel = true;
    if (routeSuppressed(result.domain)) {
        return false;
    }
    *route = std::move(result);
    return true;
}

bool OfficialRoute(int32_t dcId, bool mediaConnection, bool testBackend, const std::string &dcAddress, Route *route) {
    if (route != nullptr && !testBackend && dcId == kTunnelOnlyDcId) {
        // DC203 отдаёт медиа аккаунтам без Premium и своего kws-релея не имеет.
        return TunnelRoute(dcAddress, route);
    }
    const char *relayIp = officialRelayIpForDc(dcId);
    if (route == nullptr || testBackend || dcId < 1 || dcId > 5 || relayIp == nullptr) {
        return false;
    }
    Route result;
    result.relayHost = relayIp;
    result.relayPort = 443;
    result.path = kOfficialPath;
    const std::string prefix = "kws" + std::to_string(dcId);
    result.domain = prefix + (mediaConnection ? "-1.web.telegram.org" : ".web.telegram.org");
    result.relayHostFallback = result.domain;
    result.viaFallback = preferFallback(result);
    result.connectHost = result.viaFallback ? result.relayHostFallback : result.relayHost;
    if (routeSuppressed(result.domain)) {
        // Релей этого датацентра недоступен: сначала туннель через Worker,
        // а если недоступен и он, соединение идёт напрямую.
        return TunnelRoute(dcAddress, route);
    }
    *route = std::move(result);
    return true;
}

bool RouteUsable(const Route &route) {
    return !routeSuppressed(route.domain) && preferFallback(route) == route.viaFallback;
}

Socket::Socket(Route route) : routeConfig(std::move(route)) {
}

Socket::~Socket() {
    close();
}

bool Socket::open(const struct sockaddr *address, socklen_t addressLength, std::string *diagnostic) {
    close();
    if (address == nullptr || (address->sa_family != AF_INET && address->sa_family != AF_INET6)) {
        setDiagnostic(diagnostic, "wss_invalid_address");
        return false;
    }
    char addressText[INET6_ADDRSTRLEN] = {0};
    const void *rawAddress = address->sa_family == AF_INET
            ? static_cast<const void *>(&reinterpret_cast<const struct sockaddr_in *>(address)->sin_addr)
            : static_cast<const void *>(&reinterpret_cast<const struct sockaddr_in6 *>(address)->sin6_addr);
    peerAddress = inet_ntop(address->sa_family, rawAddress, addressText, sizeof(addressText)) != nullptr
            ? addressText
            : std::string();
    socketFd = ::socket(address->sa_family, SOCK_STREAM, 0);
    if (socketFd < 0) {
        setDiagnostic(diagnostic, "wss_socket_create_failed");
        return false;
    }
    int yes = 1;
    setsockopt(socketFd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
    if (fcntl(socketFd, F_SETFL, O_NONBLOCK) == -1) {
        setDiagnostic(diagnostic, "wss_socket_nonblocking_failed");
        close();
        return false;
    }
    state = State::TcpConnecting;
    phase = transport::HandshakePhase::None;
    failureRecorded = false;
    const int result = ::connect(socketFd, address, addressLength);
    if (result == 0) {
        const bool connected = finishTcpConnect(diagnostic);
        if (!connected) {
            noteAttemptFailed();
        }
        return connected;
    }
    if (errno != EINPROGRESS) {
        setDiagnostic(diagnostic, "wss_tcp_connect_failed");
        noteAttemptFailed();
        close();
        return false;
    }
    if (LOGS_ENABLED) {
        DEBUG_D("wss_socket connect relay=%s:%u domain=%s fallback=%d",
                routeConfig.connectHost.c_str(),
                static_cast<uint32_t>(routeConfig.relayPort),
                routeConfig.domain.c_str(),
                routeConfig.viaFallback ? 1 : 0);
    }
    return true;
}

int Socket::fd() const {
    return socketFd;
}

bool Socket::finishTcpConnect(std::string *diagnostic) {
    if (state != State::TcpConnecting) {
        return true;
    }
    int error = 0;
    socklen_t length = sizeof(error);
    if (getsockopt(socketFd, SOL_SOCKET, SO_ERROR, &error, &length) != 0 || error != 0) {
        setDiagnostic(diagnostic, "wss_tcp_connect_failed");
        noteAttemptFailed();
        return false;
    }
    phase = transport::HandshakePhase::TcpConnected;
    recordTcpConnected(peerAddress);
    if (LOGS_ENABLED) {
        DEBUG_D("wss_socket tcp_connected domain=%s", routeConfig.domain.c_str());
    }
    return startTls(diagnostic);
}

bool Socket::startTls(std::string *diagnostic) {
    SSL_CTX *context = wssSslContext();
    if (context == nullptr) {
        setDiagnostic(diagnostic, "wss_tls_context_failed");
        return false;
    }
    ssl = SSL_new(context);
    if (ssl == nullptr) {
        setDiagnostic(diagnostic, "wss_tls_create_failed");
        return false;
    }
    if (SSL_set_tlsext_host_name(ssl, routeConfig.domain.c_str()) != 1
            || SSL_set1_host(ssl, routeConfig.domain.c_str()) != 1
            || SSL_set_fd(ssl, socketFd) != 1) {
        setDiagnostic(diagnostic, "wss_tls_identity_failed");
        return false;
    }
    SSL_set_connect_state(ssl);
    state = State::TlsHandshake;
    return pumpTls(diagnostic);
}

bool Socket::onEvent(uint32_t events, std::vector<std::vector<uint8_t>> &payloads, std::string *diagnostic) {
    if (state == State::Closed || socketFd < 0) {
        setDiagnostic(diagnostic, "wss_closed");
        return false;
    }
    if (events & (EPOLLERR | EPOLLHUP)) {
        setDiagnostic(diagnostic, "wss_socket_closed");
        noteAttemptFailed();
        return false;
    }
    if (state == State::TcpConnecting && (events & (EPOLLIN | EPOLLOUT))) {
        if (!finishTcpConnect(diagnostic)) {
            noteAttemptFailed();
            return false;
        }
    }
    if (state == State::TlsHandshake && !pumpTls(diagnostic)) {
        noteAttemptFailed();
        return false;
    }
    if ((events & (EPOLLIN | EPOLLOUT)) && !flushPending(diagnostic)) {
        noteAttemptFailed();
        return false;
    }
    // On peer close (EPOLLRDHUP) or EOF keep draining and parsing: the final
    // frames may carry the server's MTProto transport error code, and dropping
    // them hides the disconnect reason from the layer above.
    bool readFailed = false;
    std::string readDiagnostic;
    const bool retryReadOnWrite = ioWait == IoWait::Write && (events & EPOLLOUT);
    if (((events & (EPOLLIN | EPOLLRDHUP)) || retryReadOnWrite)
            && (state == State::HttpRead || state == State::Ready)) {
        if (!readIntoBuffer(&readDiagnostic)) {
            readFailed = true;
            noteAttemptFailed();
        }
    }
    if (state == State::HttpRead) {
        std::string parseDiagnostic;
        if (parseHttpResponse(&parseDiagnostic)) {
            state = State::Ready;
            phase = transport::HandshakePhase::WebSocketReady;
            noteUpgradeSucceeded();
            if (LOGS_ENABLED) {
                DEBUG_D("wss_socket upgrade_ok domain=%s", routeConfig.domain.c_str());
            }
        } else if (!parseDiagnostic.empty()) {
            if (diagnostic != nullptr) {
                *diagnostic = parseDiagnostic;
            }
            noteAttemptFailed();
            return false;
        }
    }
    if (state == State::Ready && !parseFrames(payloads, diagnostic)) {
        return false;
    }
    if (readFailed) {
        setDiagnostic(diagnostic, readDiagnostic.empty() ? "wss_read_failed" : readDiagnostic.c_str());
        return false;
    }
    if (events & EPOLLRDHUP) {
        setDiagnostic(diagnostic, "wss_socket_closed");
        noteAttemptFailed();
        return false;
    }
    return true;
}

bool Socket::pumpTls(std::string *diagnostic) {
    if (state != State::TlsHandshake) {
        return true;
    }
    const int result = SSL_connect(ssl);
    if (result == 1) {
        setIoWait(IoWait::None, "tls_ready");
        if (SSL_get_verify_result(ssl) != X509_V_OK) {
            setDiagnostic(diagnostic, "wss_tls_verify_failed");
            return false;
        }
        phase = transport::HandshakePhase::TlsReady;
        if (LOGS_ENABLED) {
            DEBUG_D("wss_socket tls_ready domain=%s", routeConfig.domain.c_str());
        }
        if (!queueHttpUpgrade(diagnostic)) {
            return false;
        }
        state = State::HttpWrite;
        return true;
    }
    const int error = SSL_get_error(ssl, result);
    if (error == SSL_ERROR_WANT_READ) {
        setIoWait(IoWait::Read, "tls_handshake");
        return true;
    }
    if (error == SSL_ERROR_WANT_WRITE) {
        setIoWait(IoWait::Write, "tls_handshake");
        return true;
    }
    setDiagnostic(diagnostic, "wss_tls_failed");
    return false;
}

bool Socket::queueHttpUpgrade(std::string *diagnostic) {
    uint8_t randomKey[16];
    if (RAND_bytes(randomKey, sizeof(randomKey)) != 1) {
        setDiagnostic(diagnostic, "wss_random_failed");
        return false;
    }
    secWebSocketKey = base64Encode(randomKey, sizeof(randomKey));
    std::string host = routeConfig.domain;
    if (routeConfig.relayPort != 443) {
        host += ":" + std::to_string(static_cast<uint32_t>(routeConfig.relayPort));
    }
    const std::string request =
            "GET " + routeConfig.path + " HTTP/1.1\r\n"
            "Host: " + host + "\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: " + secWebSocketKey + "\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "Sec-WebSocket-Protocol: binary\r\n"
            "Origin: https://web.telegram.org\r\n"
            "User-Agent: Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n"
            "\r\n";
    pendingOutput.clear();
    pendingOutput.emplace_back(request.begin(), request.end());
    pendingOutputOffset = 0;
    pendingOutputBytes = request.size();
    return true;
}

bool Socket::flushPending(std::string *diagnostic) {
    while (!pendingOutput.empty()) {
        const std::vector<uint8_t> &output = pendingOutput.front();
        while (pendingOutputOffset < output.size()) {
            const int result = SSL_write(
                    ssl,
                    output.data() + pendingOutputOffset,
                    static_cast<int>(output.size() - pendingOutputOffset));
            if (result > 0) {
                writeBlockedOnRead = false;
                pendingOutputOffset += static_cast<size_t>(result);
                continue;
            }
            const int error = SSL_get_error(ssl, result);
            if (error == SSL_ERROR_WANT_READ) {
                writeBlockedOnRead = true;
                setIoWait(IoWait::Read, "write");
                return true;
            }
            if (error == SSL_ERROR_WANT_WRITE) {
                setIoWait(IoWait::Write, "write");
                return true;
            }
            setDiagnostic(diagnostic, "wss_write_failed");
            return false;
        }
        pendingOutputBytes -= output.size();
        pendingOutput.pop_front();
        pendingOutputOffset = 0;
        writeBlockedOnRead = false;
        setIoWait(IoWait::None, "write_complete");
        if (state == State::HttpWrite) {
            state = State::HttpRead;
        }
    }
    return true;
}

bool Socket::readIntoBuffer(std::string *diagnostic) {
    uint8_t buffer[16 * 1024];
    while (true) {
        const int result = SSL_read(ssl, buffer, sizeof(buffer));
        if (result > 0) {
            inputBuffer.insert(inputBuffer.end(), buffer, buffer + result);
            if (inputBuffer.size() > kMaxPendingInput) {
                setDiagnostic(diagnostic, "wss_read_buffer_full");
                return false;
            }
            continue;
        }
        const int error = SSL_get_error(ssl, result);
        if (error == SSL_ERROR_WANT_READ) {
            setIoWait(IoWait::Read, "read");
            return true;
        }
        if (error == SSL_ERROR_WANT_WRITE) {
            setIoWait(IoWait::Write, "read");
            return true;
        }
        setDiagnostic(diagnostic, result == 0 || error == SSL_ERROR_ZERO_RETURN
                ? "wss_recv_eof"
                : "wss_read_failed");
        return false;
    }
}

bool Socket::parseHttpResponse(std::string *diagnostic) {
    static const uint8_t delimiter[] = {'\r', '\n', '\r', '\n'};
    auto end = std::search(inputBuffer.begin(), inputBuffer.end(), delimiter, delimiter + sizeof(delimiter));
    if (end == inputBuffer.end()) {
        if (inputBuffer.size() > kMaxHttpHeader) {
            setDiagnostic(diagnostic, "wss_http_response_too_large");
        }
        return false;
    }
    const std::string response(inputBuffer.begin(), end);
    inputBuffer.erase(inputBuffer.begin(), end + sizeof(delimiter));
    if (response.compare(0, 12, "HTTP/1.1 101") != 0 && response.compare(0, 12, "HTTP/1.0 101") != 0) {
        setDiagnostic(diagnostic, "wss_http_upgrade_failed");
        return false;
    }
    const std::string acceptInput = secWebSocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    uint8_t acceptHash[SHA_DIGEST_LENGTH];
    SHA1(reinterpret_cast<const uint8_t *>(acceptInput.data()), acceptInput.size(), acceptHash);
    const std::string expectedAccept = base64Encode(acceptHash, sizeof(acceptHash));
    std::string lowerResponse = response;
    std::transform(lowerResponse.begin(), lowerResponse.end(), lowerResponse.begin(),
            [](unsigned char value) { return static_cast<char>(std::tolower(value)); });
    const std::string marker = "sec-websocket-accept:";
    const size_t position = lowerResponse.find(marker);
    if (position == std::string::npos) {
        setDiagnostic(diagnostic, "wss_accept_missing");
        return false;
    }
    const size_t valueStart = position + marker.size();
    size_t valueEnd = response.find("\r\n", valueStart);
    if (valueEnd == std::string::npos) {
        valueEnd = response.size();
    }
    std::string value = response.substr(valueStart, valueEnd - valueStart);
    const size_t first = value.find_first_not_of(" \t");
    const size_t last = value.find_last_not_of(" \t\r");
    value = first == std::string::npos ? std::string() : value.substr(first, last - first + 1);
    if (value != expectedAccept) {
        setDiagnostic(diagnostic, "wss_accept_mismatch");
        return false;
    }
    return true;
}

bool Socket::parseFrames(std::vector<std::vector<uint8_t>> &payloads, std::string *diagnostic) {
    while (inputBuffer.size() >= 2) {
        const uint8_t first = inputBuffer[0];
        const uint8_t second = inputBuffer[1];
        const bool fin = (first & 0x80) != 0;
        const uint8_t opcode = first & 0x0f;
        const bool control = (opcode & 0x08) != 0;
        if ((first & 0x70) != 0 || (second & 0x80) != 0) {
            setDiagnostic(diagnostic, "wss_invalid_frame_flags");
            return false;
        }
        uint64_t length = second & 0x7f;
        size_t headerLength = 2;
        if (length == 126) {
            if (inputBuffer.size() < 4) {
                return true;
            }
            length = (static_cast<uint64_t>(inputBuffer[2]) << 8) | inputBuffer[3];
            headerLength = 4;
        } else if (length == 127) {
            if (inputBuffer.size() < 10) {
                return true;
            }
            length = 0;
            for (int i = 0; i < 8; ++i) {
                length = (length << 8) | inputBuffer[2 + i];
            }
            headerLength = 10;
        }
        if (length > kMaxFrame || (control && (!fin || length > 125))) {
            setDiagnostic(diagnostic, "wss_frame_too_large");
            return false;
        }
        if (inputBuffer.size() < headerLength + static_cast<size_t>(length)) {
            return true;
        }
        const uint8_t *payload = inputBuffer.data() + headerLength;
        if (opcode == 0x8) {
            setDiagnostic(diagnostic, "wss_close_frame");
            return false;
        } else if (opcode == 0x9) {
            if (!queueFrame(0xA, payload, static_cast<uint32_t>(length), diagnostic)) {
                return false;
            }
        } else if (opcode == 0xA) {
            // Pong.
        } else if (opcode == 0x2) {
            if (fragmentedMessage) {
                setDiagnostic(diagnostic, "wss_unexpected_binary_frame");
                return false;
            }
            fragmentedMessage = !fin;
            if (length > 0) {
                payloads.emplace_back(payload, payload + length);
            }
        } else if (opcode == 0x0) {
            if (!fragmentedMessage) {
                setDiagnostic(diagnostic, "wss_unexpected_continuation");
                return false;
            }
            fragmentedMessage = !fin;
            if (length > 0) {
                payloads.emplace_back(payload, payload + length);
            }
        } else {
            setDiagnostic(diagnostic, "wss_unsupported_opcode");
            return false;
        }
        inputBuffer.erase(inputBuffer.begin(), inputBuffer.begin() + headerLength + static_cast<size_t>(length));
    }
    if (!payloads.empty() && phase == transport::HandshakePhase::WebSocketReady) {
        phase = transport::HandshakePhase::FirstDataReceived;
        // Только реальные MTProto-данные доказывают, что релей жив: успешный
        // upgrade проходит и у релеев, которые дальше молча глотают трафик.
        recordRouteReachable(routeConfig);
    }
    return true;
}

bool Socket::queueFrame(uint8_t opcode, const uint8_t *data, uint32_t size, std::string *diagnostic) {
    if (size > 0 && data == nullptr) {
        setDiagnostic(diagnostic, "wss_invalid_payload");
        return false;
    }
    if (size > kMaxFrame) {
        setDiagnostic(diagnostic, "wss_frame_too_large");
        return false;
    }
    uint8_t mask[4];
    if (RAND_bytes(mask, sizeof(mask)) != 1) {
        setDiagnostic(diagnostic, "wss_random_failed");
        return false;
    }
    const size_t frameOverhead = size < 126 ? 6 : (size <= 0xffff ? 8 : 14);
    const size_t frameSize = frameOverhead + size;
    if (pendingOutputBytes > kMaxPendingOutput - frameSize) {
        setDiagnostic(diagnostic, "wss_write_queue_full");
        return false;
    }
    const bool outputWasEmpty = pendingOutput.empty();
    std::vector<uint8_t> frame;
    frame.reserve(frameSize);
    frame.push_back(static_cast<uint8_t>(0x80 | opcode));
    if (size < 126) {
        frame.push_back(static_cast<uint8_t>(0x80 | size));
    } else if (size <= 0xffff) {
        frame.push_back(0x80 | 126);
        frame.push_back(static_cast<uint8_t>((size >> 8) & 0xff));
        frame.push_back(static_cast<uint8_t>(size & 0xff));
    } else {
        frame.push_back(0x80 | 127);
        for (int i = 7; i >= 0; --i) {
            frame.push_back(static_cast<uint8_t>((static_cast<uint64_t>(size) >> (i * 8)) & 0xff));
        }
    }
    frame.insert(frame.end(), mask, mask + sizeof(mask));
    for (uint32_t i = 0; i < size; ++i) {
        frame.push_back(data[i] ^ mask[i % sizeof(mask)]);
    }
    pendingOutputBytes += frame.size();
    pendingOutput.push_back(std::move(frame));
    if (outputWasEmpty) {
        setIoWait(IoWait::None, "frame_queued");
    }
    return true;
}

bool Socket::write(const uint8_t *data, uint32_t size, std::string *diagnostic) {
    if (state != State::Ready) {
        setDiagnostic(diagnostic, "wss_not_ready");
        return false;
    }
    if (size == 0) {
        return true;
    }
    if (!openingFrameSent) {
        openingFrame.insert(openingFrame.end(), data, data + size);
        if (openingFrame.size() < kObfuscationHeaderSize) {
            // Not enough for the relay to identify the datacenter yet. Emitting
            // it now would strand the connection with no error at all.
            if (LOGS_ENABLED) {
                DEBUG_D("wss_socket opening_frame_held domain=%s buffered=%u",
                        routeConfig.domain.c_str(), (unsigned int) openingFrame.size());
            }
            return true;
        }
        openingFrameSent = true;
        const bool queued = queueFrame(
                0x2, openingFrame.data(), static_cast<uint32_t>(openingFrame.size()), diagnostic);
        openingFrame.clear();
        openingFrame.shrink_to_fit();
        return queued;
    }
    return queueFrame(0x2, data, size, diagnostic);
}

size_t Socket::queuedOutputBytes() const {
    return pendingOutputBytes + openingFrame.size();
}

bool Socket::isReady() const {
    return state == State::Ready;
}

bool Socket::writesWaitForRead() const {
    // SSL_read ends every drained read with WANT_READ, which only means "no
    // more input yet"; EPOLLIN stays armed for that. Treating it as a block on
    // writing parked every outgoing frame until the server sent something
    // else (2.1 s for a msgs_ack in logs (9)), which throttled uploads.
    // Writes wait for input only when SSL_write itself asked for it, or while
    // the TLS handshake is still reading.
    return writeBlockedOnRead || (state == State::TlsHandshake && ioWait == IoWait::Read);
}

bool Socket::wantsWrite() const {
    return state == State::TcpConnecting
            || ioWait == IoWait::Write
            || (!pendingOutput.empty() && !writesWaitForRead());
}

bool Socket::canWriteApplicationData() const {
    return state == State::Ready && !writesWaitForRead();
}

bool Socket::isClosed() const {
    return state == State::Closed;
}

transport::HandshakePhase Socket::handshakePhase() const {
    return phase;
}

const char *Socket::transportName() const {
    return "WSS";
}

void Socket::timedOut() {
    if (LOGS_ENABLED) {
        DEBUG_D("wss_socket timeout domain=%s state=%s wait=%s", routeConfig.domain.c_str(), stateName(), ioWaitName());
    }
    if (!isReady()) {
        noteAttemptFailed();
    }
}

void Socket::setSpeculative(bool value) {
    speculative = value;
}

void Socket::noteAttemptFailed() {
    if (!failureRecorded && state != State::Ready) {
        failureRecorded = true;
        if (speculative) {
            return;
        }
        if (phase == transport::HandshakePhase::None && tcpRecentlyConnected(peerAddress)) {
            // Соседние сокеты к этому адресу только что подключались: провайдер
            // съел SYN одного потока. Новый сокет пройдёт, а переход на запасной
            // адрес или в туннель здесь только навредит.
            if (LOGS_ENABLED) {
                DEBUG_D("wss_socket flow_timeout_ignored domain=%s address=%s",
                        routeConfig.domain.c_str(), peerAddress.c_str());
            }
            return;
        }
        recordAttemptFailed(routeConfig);
        if (phase == transport::HandshakePhase::None) {
            // Не дошли даже до установленного TCP: адрес релея недоступен, а не
            // протокол сломан. Несколько таких подряд — и датацентр уходит на
            // прямое соединение, вместо того чтобы навсегда остаться без медиа.
            recordRouteUnreachable(routeConfig);
            if (LOGS_ENABLED) {
                DEBUG_D("wss_socket route_unreachable domain=%s relay=%s",
                        routeConfig.domain.c_str(), routeConfig.connectHost.c_str());
            }
        }
    }
}

void Socket::noteUpgradeSucceeded() {
    failureRecorded = false;
    // Upgrade подтверждает лишь достижимость хоста (выбор primary/fallback);
    // здоровье маршрута для подавления сбрасывает только первый MTProto-ответ.
    recordUpgradeSucceeded(routeConfig);
}

void Socket::noteAppDataTimeout() {
    // Рукопожатие прошло, а ответ на первые MTProto-байты так и не пришёл:
    // релей «жив» для TLS/HTTP, но данные съедает миддлбокс. Без учёта таких
    // исходов клиент вечно переподключается к той же чёрной дыре и никогда не
    // уходит на прямое соединение.
    failureRecorded = true;
    recordAttemptFailed(routeConfig);
    recordRouteUnreachable(routeConfig);
    if (LOGS_ENABLED) {
        DEBUG_D("wss_socket appdata_timeout domain=%s relay=%s fallback=%d",
                routeConfig.domain.c_str(), routeConfig.connectHost.c_str(), routeConfig.viaFallback ? 1 : 0);
    }
}

void Socket::setIoWait(IoWait wait, const char *operation) {
    if (ioWait == wait) {
        return;
    }
    ioWait = wait;
    if (LOGS_ENABLED) {
        DEBUG_D("wss_socket io_wait domain=%s state=%s wait=%s operation=%s",
                routeConfig.domain.c_str(), stateName(), ioWaitName(), operation != nullptr ? operation : "unknown");
    }
}

const char *Socket::stateName() const {
    switch (state) {
        case State::TcpConnecting:
            return "tcp_connecting";
        case State::TlsHandshake:
            return "tls_handshake";
        case State::HttpWrite:
            return "http_write";
        case State::HttpRead:
            return "http_read";
        case State::Ready:
            return "ready";
        case State::Closed:
            return "closed";
    }
    return "unknown";
}

const char *Socket::ioWaitName() const {
    switch (ioWait) {
        case IoWait::None:
            return "none";
        case IoWait::Read:
            return "read";
        case IoWait::Write:
            return "write";
    }
    return "unknown";
}

void Socket::close() {
    if (ssl != nullptr) {
        SSL_free(ssl);
        ssl = nullptr;
    }
    if (socketFd >= 0) {
        ::close(socketFd);
        socketFd = -1;
    }
    state = State::Closed;
    phase = transport::HandshakePhase::None;
    ioWait = IoWait::None;
    writeBlockedOnRead = false;
    pendingOutput.clear();
    pendingOutputOffset = 0;
    pendingOutputBytes = 0;
    inputBuffer.clear();
    openingFrame.clear();
    openingFrame.shrink_to_fit();
    openingFrameSent = false;
    fragmentedMessage = false;
}

const Route &Socket::route() const {
    return routeConfig;
}

std::unique_ptr<transport::Socket> CreateSocket(Route route) {
    return std::unique_ptr<transport::Socket>(new Socket(std::move(route)));
}

} // namespace wss
} // namespace tgnet
