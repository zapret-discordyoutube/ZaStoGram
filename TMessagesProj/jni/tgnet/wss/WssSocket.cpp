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
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <openssl/x509.h>
#include <linux/sockios.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <map>
#include <mutex>

namespace tgnet {
namespace wss {
namespace {

constexpr const char *kOfficialPath = "/apiws";
// Worker tunnels (zastogram-ws-worker/worker.js), each on its own Cloudflare
// account. A free account serves 100 000 requests a day; past that the worker
// answers 429 (error code: 1027) until 00:00 UTC, and on 08.10.2026 the single
// worker was spent by the evening: DC203 media stood still for everyone. Every
// install starts from a random worker, so the accounts share the load. The
// same list is in the desktop client (mtproto/proxy/wss/socket.cpp) and in
// ZapretGUI (telegram_proxy/proxy/route_catalog.py, TUNNEL_HOSTS).
constexpr const char *kTunnelHosts[] = {
        "edge.amberwick.workers.dev",
};
constexpr size_t kTunnelHostCount = sizeof(kTunnelHosts) / sizeof(kTunnelHosts[0]);
constexpr int32_t kTunnelOnlyDcId = 203;
// Size of the MTProto obfuscation header. Measured against the official relays:
// a first binary frame of 63 bytes never gets a reply, 64 always does.
constexpr size_t kObfuscationHeaderSize = 64;
constexpr uint32_t kMaxFrame = 2 * 1024 * 1024;
constexpr size_t kMaxHttpHeader = 32 * 1024;
constexpr size_t kMaxPendingOutput = 4 * 1024 * 1024;
constexpr size_t kMaxPendingInput = 4 * 1024 * 1024;
constexpr int64_t kFallbackPreferenceTtlMs = 30 * 60 * 1000;

// Cloudflare front domains of tg-ws-proxy (github.com/Flowseal/tg-ws-proxy,
// .github/cfproxy-domains.txt), in the same shifted spelling: each letter of
// the name moved forward by the number of letters, ".co.uk" written ".com".
// kwsN.<domain>/apiws forwards the WebSocket to Telegram Web. Mirrly TG Proxy
// carries MTProto only this way and, unlike our Worker tunnel (frozen after
// ~16 KB on the user's mobile network), works there for text and media.
struct CdnFront {
    const char *encodedDomain;
    // The zone's own Cloudflare addresses (27.09.2026). Any Cloudflare edge
    // serves the zone, so these keep working if DNS moves it, and dialling by
    // address needs no resolve and lets the pool keep a spare ready.
    const char *address[2];
};
constexpr CdnFront kCdnFronts[] = {
        {"virkgj.com", {"104.21.80.254", "172.67.155.165"}},
        {"vmmzovy.com", {"104.21.43.90", "172.67.177.105"}},
        {"mkuosckvso.com", {"104.21.41.25", "172.67.159.17"}},
        {"zaewayzmplad.com", {"104.21.70.196", "172.67.138.236"}},
        {"twdmbzcm.com", {"104.21.21.168", "172.67.199.162"}},
        {"awzwsldi.com", {"104.21.69.145", "172.67.209.89"}},
        {"clngqrflngqin.com", {"104.21.73.83", "172.67.189.26"}},
        {"tjacxbqtj.com", {"104.21.39.36", "172.67.142.232"}},
        {"bxaxtxmrw.com", {"104.21.84.223", "172.67.197.117"}},
        {"dmohrsgmohcrwb.com", {"104.21.48.178", "172.67.155.85"}},
        {"vwbmtmoi.com", {"104.21.33.146", "172.67.146.105"}},
        {"khgrre.com", {"104.21.78.6", "172.67.214.68"}},
        {"ulihssf.com", {"104.21.7.253", "172.67.156.145"}},
        {"tmhqsdqmfpmk.com", {"104.21.25.159", "172.67.134.93"}},
        {"xwuwoqbm.com", {"104.21.44.55", "172.67.195.218"}},
        {"orgcnunpj.com", {"104.21.64.155", "172.67.152.37"}},
        {"zhkuldz.com", {"104.21.51.133", "172.67.180.160"}},
        {"zypoljnslxa.com", {"104.21.37.105", "172.67.207.129"}},
        {"efabnxaowuzs.com", {"104.21.35.206", "172.67.179.145"}},
        {"zaftuzsftqdq.com", {"104.21.78.5", "172.67.214.67"}},
};
constexpr uint32_t kCdnFrontCount = sizeof(kCdnFronts) / sizeof(kCdnFronts[0]);
// A front connection is either served at once or answers 503 to every upgrade
// (host test 27.09: 0-24 refusals before 101, or all 25 refused). Each retry on
// the same TLS connection costs ~25 ms against a new TCP+TLS dial.
constexpr uint32_t kCdnUpgradeRetries = 8;
// About 40% of front connections are refused, so three failures in a row are
// ordinary (6%); six mean the fronts are really out of reach.
constexpr uint32_t kCdnFailuresBeforeSuppress = 6;
// Past the ~16 KB freeze seen on the tunnel: a session that delivered this
// much proves the front carries real traffic on this network.
constexpr uint64_t kCdnProofBytes = 32 * 1024;
// Sessions that answered and then went silent below kCdnProofBytes.
constexpr uint32_t kCdnStallsBeforeSuppress = 3;

struct RelayPreference {
    bool preferFallback = false;
    int64_t until = 0;
};

std::mutex relayPreferencesMutex;
std::map<std::string, RelayPreference> relayPreferences;

// A relay the mobile provider blocks (kws1-1 on the user's phone) works at home
// on Wi-Fi: with one shared state a failure on mobile data kept Wi-Fi in the
// tunnel for up to 30 minutes. Everything below is keyed by network type.
constexpr int32_t kNetworkMobile = 0;
constexpr int32_t kNetworkWifi = 1;
std::atomic<int32_t> currentNetwork{kNetworkWifi};

const char *networkName(int32_t network) {
    return network == kNetworkWifi ? "wifi" : "mobile";
}

std::string networkKey(int32_t network, const std::string &value) {
    return std::string(networkName(network)) + "/" + value;
}

int64_t monotonicMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string relayPreferenceKey(const Route &route) {
    return networkKey(route.network, route.relayHost + ":" + std::to_string(route.relayPort) + ":" + route.domain);
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
// A throttled network freezes TCP to Cloudflare after about 16 KB downstream
// (logs (9) and (10): no tunnel session ever got past 15 KB). Connections are
// replaced after one 8 KB part (ConnectionSocket WSS_TUNNEL_ROTATE_BYTES), so
// a tunnel that delivered this much is working, even if it would freeze later.
constexpr uint64_t kTunnelProofBytes = 6 * 1024;
// A ready tunnel socket closed sooner than this without data was cut by a
// connect timeout, not found silent (desktop log 25.09: the first answer
// comes ~1.1 s after the socket opens).
constexpr int64_t kTunnelSilentAfterMs = 4000;
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
    // Suppressions in a row without a single answer in between. A relay the
    // network blocks outright (kws1 on the user's networks) was probed again
    // every two minutes, and each probe left DC1 without a connection for
    // seconds (desktop log 25.09). Each repeat doubles the suppression.
    uint32_t suppressions = 0;
    // Cloudflare front sessions that answered and then froze (kCdnProofBytes).
    uint32_t stalls = 0;
};
constexpr int64_t kRouteSuppressMaxTtlMs = 30 * 60 * 1000;
// Suppression lived only in memory, so every launch probed a blocked relay
// again: logs (21) spent the first 9 s of DC1 on kws1-1 before the tunnel.
// Kept in a file; what is restored after a restart is capped, since the
// phone may be on another network by then.
constexpr int64_t kRouteSuppressRestoreMaxMs = 10 * 60 * 1000;
std::string routeHealthPath;

int64_t wallMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
}

std::map<std::string, RouteHealth> routeHealth;

// Called with relayPreferencesMutex held.
void saveRouteHealthLocked() {
    if (routeHealthPath.empty()) {
        return;
    }
    FILE *file = fopen(routeHealthPath.c_str(), "w");
    if (file == nullptr) {
        return;
    }
    const int64_t now = monotonicMillis();
    const int64_t wall = wallMillis();
    for (const auto &[domain, health] : routeHealth) {
        if (health.suppressions == 0 || health.suppressedUntil <= now) {
            continue;
        }
        if (domain.find("/cdn-") != std::string::npos) {
            // Fronts are checked again on every launch: a restart with the
            // relay, the fronts and the tunnel all restored as suppressed
            // left DC2 with no route at all for ten minutes (logs (1) (8)).
            continue;
        }
        fprintf(file, "%s %u %lld\n", domain.c_str(), health.suppressions,
                (long long) (wall + (health.suppressedUntil - now)));
    }
    fclose(file);
}

const std::string &healthName(const Route &route) {
    return route.healthDomain.empty() ? route.domain : route.healthDomain;
}

bool isCdn(const Route &route) {
    return route.cdnSlot >= 0;
}

// Relay domains share ingress addresses: kws2, kws4, kws2-1 and kws4-1 all
// dial 149.154.167.220. A TCP timeout does not depend on the SNI, so TCP
// failures also count per address; when an address is suppressed every
// domain on it moves to the fronts at once instead of each timing out three
// times on its own (logs (23): kws1-1 and kws2-1 lost 8-11 s each after kws1
// and kws2 had already proven their addresses dead).
std::string relayAddressHealthName(const std::string &address) {
    return "addr-" + address;
}

bool isRelay(const Route &route) {
    return !route.tunnel && !isCdn(route);
}

// Called with relayPreferencesMutex held.
void suppressLocked(const Route &route, RouteHealth &health, int64_t now, const char *reason) {
    const int64_t ttl = std::min(kRouteSuppressTtlMs << std::min(health.suppressions, 4u), kRouteSuppressMaxTtlMs);
    ++health.suppressions;
    health.suppressedUntil = now + ttl;
    saveRouteHealthLocked();
    if (LOGS_ENABLED) {
        DEBUG_D("wss_route suppressed domain=%s net=%s for_ms=%lld reason=%s next=%s", healthName(route).c_str(),
                networkName(route.network), (long long) ttl, reason,
                route.tunnel ? "direct" : (isCdn(route) ? "tunnel" : "cdn"));
    }
}

std::map<std::string, int64_t> tcpSuccessByAddress;

// Any front reaching TCP recently, whatever its address. Every front attempt
// rotates to a new address, so the per-address rule never matched and each
// lost SYN counted towards suppressing all fronts.
constexpr const char *kAnyCdnAddress = "cdn-any";

void recordTcpConnected(int32_t network, const std::string &address, bool cdn) {
    if (address.empty()) {
        return;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    const int64_t now = monotonicMillis();
    tcpSuccessByAddress[networkKey(network, address)] = now;
    if (cdn) {
        tcpSuccessByAddress[networkKey(network, kAnyCdnAddress)] = now;
    }
}

bool tcpRecentlyConnected(int32_t network, const std::string &address) {
    if (address.empty()) {
        return false;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    auto it = tcpSuccessByAddress.find(networkKey(network, address));
    return it != tcpSuccessByAddress.end() && monotonicMillis() - it->second < kRecentTcpSuccessMs;
}

bool routeSuppressed(int32_t network, const std::string &domain) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    auto it = routeHealth.find(networkKey(network, domain));
    if (it == routeHealth.end() || it->second.suppressedUntil == 0) {
        return false;
    }
    if (it->second.suppressedUntil <= monotonicMillis()) {
        it->second.suppressedUntil = 0;
        it->second.consecutiveFailures = 0;
        it->second.stalls = 0;
        if (LOGS_ENABLED) {
            DEBUG_D("wss_route restored domain=%s net=%s reason=expired", domain.c_str(), networkName(network));
        }
        return false;
    }
    return true;
}

void recordRouteUnreachable(const Route &route) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    RouteHealth &health = routeHealth[networkKey(route.network, healthName(route))];
    const int64_t now = monotonicMillis();
    if (health.suppressedUntil > now) {
        return;
    }
    if (health.lastFailureAt != 0 && now - health.lastFailureAt < kRouteFailureCoalesceMs) {
        return;
    }
    health.lastFailureAt = now;
    // Медиа-релеи kwsN-1 раньше уходили в туннель после первой же неудачи и
    // на 30 минут. На старте такая неудача случается почти всегда (соединения
    // открываются пачкой), а туннель на мобильной сети замерзает после ~16 КБ:
    // в logs (10) все сессии DC1 и DC5 через туннель умерли, а kws2-1 и kws4-1
    // отдали мегабайты. Медиа теперь подчиняется тем же правилам, что и
    // основной релей.
    ++health.consecutiveFailures;
    const uint32_t limit = isCdn(route) ? kCdnFailuresBeforeSuppress : kRouteFailuresBeforeSuppress;
    if (LOGS_ENABLED) {
        DEBUG_D("wss_route failure domain=%s net=%s failures=%u/%u", healthName(route).c_str(),
                networkName(route.network), health.consecutiveFailures, limit);
    }
    if (health.consecutiveFailures >= limit) {
        suppressLocked(route, health, now, "failures");
    }
}

// The worker answered 429: its account is out of requests for the day, and
// every further attempt gets the same answer. Suppressed at once, without
// counting to three, so the next connection goes straight to the next worker.
void recordTunnelQuotaExhausted(const Route &route) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    RouteHealth &health = routeHealth[networkKey(route.network, healthName(route))];
    const int64_t now = monotonicMillis();
    if (health.suppressedUntil > now) {
        return;
    }
    health.lastFailureAt = now;
    health.consecutiveFailures = 0;
    suppressLocked(route, health, now, "quota");
}

// The relay's hardcoded address failed and its DNS fallback failed too, so
// the domain went down: siblings on the same address will fail the same way.
// Their fallbacks resolve elsewhere, so this only follows a domain that was
// already given up on, never a single address failure.
void suppressRelayAddressWithDomain(const Route &route) {
    if (!isRelay(route) || route.relayHost.empty()) {
        return;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    const int64_t now = monotonicMillis();
    auto domain = routeHealth.find(networkKey(route.network, route.domain));
    if (domain == routeHealth.end() || domain->second.suppressedUntil <= now) {
        return;
    }
    Route address = route;
    address.healthDomain = relayAddressHealthName(route.relayHost);
    RouteHealth &health = routeHealth[networkKey(route.network, address.healthDomain)];
    // Only a recent failure of the address itself: a stray SYN loss long ago
    // must not take a sibling off a working relay (logs (9): kws2 down while
    // kws4 on the same address kept working).
    if (health.consecutiveFailures > 0 && health.suppressedUntil <= now
            && health.lastFailureAt != 0 && now - health.lastFailureAt < 2 * 60 * 1000) {
        suppressLocked(address, health, now, "domain");
    }
}

void recordRouteReachable(const Route &route) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    RouteHealth &health = routeHealth[networkKey(route.network, healthName(route))];
    if (isCdn(route)) {
        // The first answer only shows that the front accepts connections. On a
        // network that freezes Cloudflare after ~16 KB every session answers,
        // and resetting the backoff here would cycle three frozen sessions and
        // two minutes of tunnel forever; that is left to recordCdnProven.
        health.consecutiveFailures = 0;
        health.lastFailureAt = 0;
        return;
    }
    if (LOGS_ENABLED && (health.consecutiveFailures != 0 || health.suppressedUntil != 0)) {
        DEBUG_D("wss_route restored domain=%s net=%s reason=data", route.domain.c_str(), networkName(route.network));
    }
    bool persisted = health.suppressions != 0;
    health = RouteHealth();
    // Data through the DNS fallback came from another address: the hardcoded
    // one siblings dial proved nothing.
    if (isRelay(route) && !route.relayHost.empty() && !route.viaFallback) {
        auto address = routeHealth.find(networkKey(route.network, relayAddressHealthName(route.relayHost)));
        if (address != routeHealth.end()) {
            persisted = persisted || address->second.suppressions != 0;
            address->second = RouteHealth();
        }
    }
    if (persisted) {
        saveRouteHealthLocked();
    }
}

void recordCdnProven(const Route &route) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    RouteHealth &health = routeHealth[networkKey(route.network, healthName(route))];
    if (LOGS_ENABLED && (health.stalls != 0 || health.suppressions != 0)) {
        DEBUG_D("wss_route restored domain=%s net=%s reason=proven", healthName(route).c_str(), networkName(route.network));
    }
    // A front carrying DC traffic past the freeze lifts its suppression too:
    // when everything is suppressed the fronts carry the DC anyway, and a
    // suppression left in place sent it into the tunnel the moment the
    // tunnel's own expired (logs (1) (8), 16:54:48).
    health.consecutiveFailures = 0;
    health.lastFailureAt = 0;
    health.stalls = 0;
    health.suppressions = 0;
    health.suppressedUntil = 0;
}

void recordCdnStalled(const Route &route, uint64_t received) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    RouteHealth &health = routeHealth[networkKey(route.network, healthName(route))];
    const int64_t now = monotonicMillis();
    if (health.suppressedUntil > now) {
        return;
    }
    ++health.stalls;
    if (LOGS_ENABLED) {
        DEBUG_D("wss_route stall domain=%s net=%s rx=%llu stalls=%u/%u", healthName(route).c_str(),
                networkName(route.network), (unsigned long long) received, health.stalls, kCdnStallsBeforeSuppress);
    }
    if (health.stalls >= kCdnStallsBeforeSuppress) {
        health.stalls = 0;
        suppressLocked(route, health, now, "stalls");
    }
}

// Every connection starts from this position in kCdnFronts, per network. A
// failure moves it on, so the next connection tries another front and edge;
// a working front is kept. Starting at a random front spreads ZaStoGram users
// over the whole catalog instead of all loading its first domain.
std::map<int32_t, uint32_t> cdnCursor;

uint32_t cdnCursorLocked(int32_t network) {
    auto it = cdnCursor.find(network);
    if (it == cdnCursor.end()) {
        uint32_t start = 0;
        RAND_bytes(reinterpret_cast<uint8_t *>(&start), sizeof(start));
        it = cdnCursor.emplace(network, start % (kCdnFrontCount * 2)).first;
    }
    return it->second;
}

// Address index (kCdnFront::address) that last completed TCP, per network.
// On one Wi-Fi every 104.21.x front failed TCP while 172.67.x worked (logs
// (23)); without this each failure only moved to the next domain on the same
// address, and the working address came up only after ~20 lost attempts.
std::map<int32_t, int32_t> cdnPreferredAddress;
// Counted TCP failures in a row on the preferred address; one lost SYN is
// flow noise and must not throw a working address away.
std::map<int32_t, uint32_t> cdnPreferredAddressFailures;
constexpr uint32_t kCdnAddressFailuresBeforeForget = 2;

// Kept across launches next to the route health file: on a network where one
// Cloudflare range is dead, every launch otherwise lost ~12 s finding the
// other one again. Only the dial order depends on it, never suppression.
std::string cdnPreferencePath() {
    return routeHealthPath.empty() ? std::string() : routeHealthPath + ".front";
}

// Called with relayPreferencesMutex held.
void saveCdnPreferenceLocked() {
    const std::string path = cdnPreferencePath();
    if (path.empty()) {
        return;
    }
    FILE *file = fopen(path.c_str(), "w");
    if (file == nullptr) {
        return;
    }
    for (const auto &[network, address] : cdnPreferredAddress) {
        fprintf(file, "%d %d\n", network, address);
    }
    fclose(file);
}

// Called with relayPreferencesMutex held, once routeHealthPath is set.
void loadCdnPreferenceLocked() {
    const std::string path = cdnPreferencePath();
    FILE *file = path.empty() ? nullptr : fopen(path.c_str(), "r");
    if (file == nullptr) {
        return;
    }
    int network = 0;
    int address = 0;
    while (fscanf(file, "%d %d", &network, &address) == 2) {
        if ((network == kNetworkMobile || network == kNetworkWifi) && (address == 0 || address == 1)) {
            cdnPreferredAddress[network] = address;
        }
    }
    fclose(file);
}

void noteCdnAddress(const Route &route, bool connected) {
    if (route.cdnSlot < 0 || route.cdnAddress < 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    auto it = cdnPreferredAddress.find(route.network);
    if (connected) {
        cdnPreferredAddressFailures[route.network] = 0;
        if (it == cdnPreferredAddress.end() || it->second != route.cdnAddress) {
            cdnPreferredAddress[route.network] = route.cdnAddress;
            saveCdnPreferenceLocked();
        }
    } else if (it != cdnPreferredAddress.end() && it->second == route.cdnAddress
            && ++cdnPreferredAddressFailures[route.network] >= kCdnAddressFailuresBeforeForget) {
        cdnPreferredAddress.erase(it);
        cdnPreferredAddressFailures[route.network] = 0;
        saveCdnPreferenceLocked();
    }
}

void advanceCdn(const Route &route) {
    if (!isCdn(route)) {
        return;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    uint32_t &cursor = cdnCursor[route.network];
    if (cursor == static_cast<uint32_t>(route.cdnSlot)) {
        cursor = (cursor + 1) % (kCdnFrontCount * 2);
        // Interleaved (see CdnRoute): consecutive slots alternate the address.
    }
}

bool cdnSlotCurrent(const Route &route) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    return cdnCursorLocked(route.network) == static_cast<uint32_t>(route.cdnSlot);
}

std::string decodeCdnDomain(const char *encoded) {
    // tg-ws-proxy's decoder: shift each letter back by the letter count.
    std::string name(encoded);
    const size_t suffix = name.rfind(".com");
    if (suffix == std::string::npos || suffix + 4 != name.size()) {
        return name;
    }
    name.resize(suffix);
    int letters = 0;
    for (char c : name) {
        letters += std::isalpha(static_cast<unsigned char>(c)) ? 1 : 0;
    }
    for (char &c : name) {
        if (c >= 'a' && c <= 'z') {
            c = static_cast<char>('a' + ((c - 'a') - letters % 26 + 26) % 26);
        } else if (c >= 'A' && c <= 'Z') {
            c = static_cast<char>('A' + ((c - 'A') - letters % 26 + 26) % 26);
        }
    }
    return name + ".co.uk";
}

void loadRouteHealthFrom(const std::string &path) {
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    if (!routeHealthPath.empty() || path.empty()) {
        return;
    }
    routeHealthPath = path;
    loadCdnPreferenceLocked();
    FILE *file = fopen(path.c_str(), "r");
    if (file == nullptr) {
        return;
    }
    const int64_t now = monotonicMillis();
    const int64_t wall = wallMillis();
    char domain[256];
    unsigned suppressions = 0;
    long long until = 0;
    while (fscanf(file, "%255s %u %lld", domain, &suppressions, &until) == 3) {
        const int64_t left = std::min<int64_t>(until - wall, kRouteSuppressRestoreMaxMs);
        // Lines written before the per-network split carry a bare domain;
        // front suppression written by dev-173 is not restored either.
        if (left <= 0 || suppressions == 0 || strchr(domain, '/') == nullptr || strstr(domain, "/cdn-") != nullptr) {
            continue;
        }
        RouteHealth &health = routeHealth[domain];
        health.suppressions = suppressions;
        health.suppressedUntil = now + left;
        if (LOGS_ENABLED) {
            DEBUG_D("wss_route suppressed key=%s for_ms=%lld restored_from_disk=1", domain, (long long) left);
        }
    }
    fclose(file);
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

// ISRG Root X1 (Let's Encrypt), SHA-256 96BCEC06...BDDF08C6. Eleven of the
// Cloudflare front domains chain to it, and neither the WebRTC root list nor
// Android before 7.1 carries it.
constexpr const char *kIsrgRootX1 =
        "-----BEGIN CERTIFICATE-----\n"
        "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n"
        "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n"
        "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n"
        "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n"
        "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n"
        "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n"
        "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+\n"
        "0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U\n"
        "A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW\n"
        "T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH\n"
        "B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC\n"
        "B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv\n"
        "KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn\n"
        "OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn\n"
        "jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw\n"
        "qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI\n"
        "rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV\n"
        "HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq\n"
        "hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL\n"
        "ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ\n"
        "3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK\n"
        "NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5\n"
        "ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur\n"
        "TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC\n"
        "jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc\n"
        "oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq\n"
        "4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA\n"
        "mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d\n"
        "emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=\n"
        "-----END CERTIFICATE-----\n";

bool loadBundledRoots(SSL_CTX *context) {
    int loaded = 0;
    X509_STORE *store = SSL_CTX_get_cert_store(context);
    if (BIO *bio = BIO_new_mem_buf(kIsrgRootX1, -1)) {
        if (X509 *certificate = PEM_read_bio_X509(bio, nullptr, nullptr, nullptr)) {
            if (X509_STORE_add_cert(store, certificate) != 1) {
                ERR_clear_error();
            }
            X509_free(certificate);
        }
        BIO_free(bio);
        ERR_clear_error();
    }
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

void SetRouteHealthPath(const std::string &path) {
    loadRouteHealthFrom(path);
}

void SetNetworkType(int32_t networkType) {
    const int32_t network = networkType == NETWORK_TYPE_WIFI ? kNetworkWifi : kNetworkMobile;
    if (currentNetwork.exchange(network) != network && LOGS_ENABLED) {
        DEBUG_D("wss_route network=%s", networkName(network));
    }
}

// Which worker this install tries first; the rest follow in a circle.
static size_t TunnelStart() {
    static const size_t start = [] {
        uint32_t value = 0;
        RAND_bytes(reinterpret_cast<uint8_t *>(&value), sizeof(value));
        return static_cast<size_t>(value % kTunnelHostCount);
    }();
    return start;
}

static bool TunnelRoute(int32_t network, const std::string &dcAddress, Route *route) {
    struct in_addr parsed;
    if (inet_pton(AF_INET, dcAddress.c_str(), &parsed) != 1) {
        return false;
    }
    for (size_t step = 0; step < kTunnelHostCount; ++step) {
        const char *host = kTunnelHosts[(TunnelStart() + step) % kTunnelHostCount];
        if (routeSuppressed(network, host)) {
            continue;
        }
        Route result;
        result.relayHost = host;
        result.connectHost = host;
        result.relayPort = 443;
        result.domain = host;
        result.path = std::string(kOfficialPath) + "?dst=" + dcAddress;
        result.tunnel = true;
        result.network = network;
        *route = std::move(result);
        return true;
    }
    return false;
}

static bool CdnRoute(int32_t network, int32_t dcId, Route *route, bool ignoreSuppression = false) {
    Route result;
    result.network = network;
    const std::string prefix = "kws" + std::to_string(dcId);
    result.healthDomain = "cdn-" + prefix;
    if (!ignoreSuppression && routeSuppressed(network, result.healthDomain)) {
        return false;
    }
    uint32_t slot;
    int32_t address;
    bool addressKnown;
    {
        std::lock_guard<std::mutex> lock(relayPreferencesMutex);
        slot = cdnCursorLocked(network);
        auto preferred = cdnPreferredAddress.find(network);
        addressKnown = preferred != cdnPreferredAddress.end();
        address = addressKnown ? preferred->second : static_cast<int32_t>(slot % 2);
    }
    // Without a known-good address consecutive slots alternate the address
    // on the same domain; with one, every slot is the next domain, so a
    // failure never repeats the attempt that just failed.
    const CdnFront &front = kCdnFronts[(addressKnown ? slot : slot / 2) % kCdnFrontCount];
    result.cdnSlot = static_cast<int32_t>(slot);
    result.cdnDcId = dcId;
    // The fronts have no -1 media hosts: media rides kwsN too, as in Mirrly,
    // where DC1 media loads on the user's mobile network.
    result.domain = prefix + "." + decodeCdnDomain(front.encodedDomain);
    result.cdnAddress = address;
    result.relayHost = front.address[address];
    result.relayHostFallback = result.domain;
    result.relayPort = 443;
    result.path = kOfficialPath;
    result.viaFallback = preferFallback(result);
    result.connectHost = result.viaFallback ? result.relayHostFallback : result.relayHost;
    *route = std::move(result);
    return true;
}

bool OfficialRoute(int32_t dcId, bool mediaConnection, bool testBackend, const std::string &dcAddress, Route *route) {
    if (route != nullptr && !testBackend && dcId == kTunnelOnlyDcId) {
        // DC203 отдаёт медиа аккаунтам без Premium и своего kws-релея не имеет.
        return TunnelRoute(currentNetwork.load(), dcAddress, route);
    }
    const char *relayIp = officialRelayIpForDc(dcId);
    if (route == nullptr || testBackend || dcId < 1 || dcId > 5 || relayIp == nullptr) {
        return false;
    }
    Route result;
    result.network = currentNetwork.load();
    result.relayHost = relayIp;
    result.relayPort = 443;
    result.path = kOfficialPath;
    const std::string prefix = "kws" + std::to_string(dcId);
    result.domain = prefix + (mediaConnection ? "-1.web.telegram.org" : ".web.telegram.org");
    result.relayHostFallback = result.domain;
    result.viaFallback = preferFallback(result);
    result.connectHost = result.viaFallback ? result.relayHostFallback : result.relayHost;
    if (routeSuppressed(result.network, result.domain)
            || routeSuppressed(result.network, relayAddressHealthName(result.relayHost))) {
        // Релей этого датацентра недоступен: сначала фронты Cloudflare, затем
        // туннель через Worker, а если недоступен и он, соединение идёт
        // напрямую. Для медиа пробовали и прямой путь первым (logs (13)):
        // 27 попыток к медиа DC1, ни одного TCP-подключения, тогда как
        // задушенный туннель мелкими частями хоть что-то отдаёт.
        if (CdnRoute(result.network, dcId, route) || TunnelRoute(result.network, dcAddress, route)) {
            return true;
        }
        // Everything is suppressed. The direct path this used to fall back to
        // is exactly what these networks block: a tester's DC2 sat in
        // "connecting" for minutes (logs (1) (8)) while the fronts of DC4
        // answered in 100 ms. Twenty fronts on forty addresses are the floor.
        static std::atomic<int64_t> lastAllSuppressedLog{0};
        const int64_t now = monotonicMillis();
        if (LOGS_ENABLED && now - lastAllSuppressedLog.load() > 10000) {
            lastAllSuppressedLog = now;
            DEBUG_D("wss_route all_suppressed dc=%d media=%d net=%s use=cdn", dcId, mediaConnection ? 1 : 0,
                    networkName(result.network));
        }
        return CdnRoute(result.network, dcId, route, true);
    }
    *route = std::move(result);
    return true;
}

bool DatacenterTunneled(int32_t dcId, bool mediaConnection, bool testBackend) {
    Route route;
    // The address only fills the tunnel's ?dst=; any Telegram IPv4 will do.
    return OfficialRoute(dcId, mediaConnection, testBackend, "149.154.175.50", &route) && route.tunnel;
}

bool FollowCdnRoute(const Route &stale, Route *fresh) {
    return isCdn(stale) && stale.network == currentNetwork.load()
            && CdnRoute(stale.network, stale.cdnDcId, fresh, true) && fresh->cdnSlot != stale.cdnSlot;
}

bool RouteUsable(const Route &route) {
    // Front spares stay warm while the fronts are suppressed: with every
    // route suppressed they carry the DC anyway, and a cold dial there loses
    // most TCP connects (logs (1) (8)).
    return route.network == currentNetwork.load()
            && (isCdn(route) || !routeSuppressed(route.network, healthName(route)))
            && (!isRelay(route) || !routeSuppressed(route.network, relayAddressHealthName(route.relayHost)))
            && (!isCdn(route) || cdnSlotCurrent(route))
            && preferFallback(route) == route.viaFallback;
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
    upgradeRetries = 0;
    provenRecorded = false;
    openedAtMs = monotonicMillis();
    summaryTaken = false;
    reachableRecorded = false;
    readyAtMs = 0;
    firstDataAtMs = 0;
    lastDataAtMs = 0;
    bytesOut = 0;
    bytesIn = 0;
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
    recordTcpConnected(routeConfig.network, peerAddress, isCdn(routeConfig));
    if (!routeConfig.viaFallback) {
        noteCdnAddress(routeConfig, true);
    }
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
            readyAtMs = monotonicMillis();
            noteUpgradeSucceeded();
            if (LOGS_ENABLED) {
                DEBUG_D("wss_socket upgrade_ok domain=%s", routeConfig.domain.c_str());
            }
        } else if (state == State::HttpWrite) {
            // A front refused the upgrade and it was asked again on this connection.
            if (!flushPending(diagnostic)) {
                noteAttemptFailed();
                return false;
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
        if (isCdn(routeConfig) && upgradeRetries < kCdnUpgradeRetries
                && (response.compare(0, 12, "HTTP/1.1 503") == 0 || response.compare(0, 12, "HTTP/1.1 429") == 0)) {
            std::string lower = response;
            std::transform(lower.begin(), lower.end(), lower.begin(),
                    [](unsigned char value) { return static_cast<char>(std::tolower(value)); });
            // Only an empty keep-alive answer leaves the connection clean for
            // the next request; Cloudflare's 503 here is exactly that.
            // The header block ends without its last CRLF.
            const std::string emptyBody = "\r\ncontent-length: 0";
            const size_t length = lower.find(emptyBody);
            const size_t after = length == std::string::npos ? 0 : length + emptyBody.size();
            if (length != std::string::npos && (after == lower.size() || lower[after] == '\r')
                    && lower.find("connection: close") == std::string::npos
                    && inputBuffer.empty()) {
                ++upgradeRetries;
                if (LOGS_ENABLED) {
                    DEBUG_D("wss_socket upgrade_retry domain=%s status=%.12s retry=%u",
                            routeConfig.domain.c_str(), response.c_str(), upgradeRetries);
                }
                if (!queueHttpUpgrade(diagnostic)) {
                    return false;
                }
                state = State::HttpWrite;
                return false;
            }
        }
        if (LOGS_ENABLED) {
            DEBUG_D("wss_socket upgrade_refused domain=%s status=%.12s retries=%u",
                    routeConfig.domain.c_str(), response.c_str(), upgradeRetries);
        }
        if (routeConfig.tunnel && !speculative && response.compare(0, 12, "HTTP/1.1 429") == 0) {
            recordTunnelQuotaExhausted(routeConfig);
        }
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
                bytesIn += length;
                payloads.emplace_back(payload, payload + length);
            }
        } else if (opcode == 0x0) {
            if (!fragmentedMessage) {
                setDiagnostic(diagnostic, "wss_unexpected_continuation");
                return false;
            }
            fragmentedMessage = !fin;
            if (length > 0) {
                bytesIn += length;
                payloads.emplace_back(payload, payload + length);
            }
        } else {
            setDiagnostic(diagnostic, "wss_unsupported_opcode");
            return false;
        }
        inputBuffer.erase(inputBuffer.begin(), inputBuffer.begin() + headerLength + static_cast<size_t>(length));
    }
    if (!payloads.empty()) {
        lastDataAtMs = monotonicMillis();
    }
    if (!payloads.empty() && phase == transport::HandshakePhase::WebSocketReady) {
        phase = transport::HandshakePhase::FirstDataReceived;
        firstDataAtMs = monotonicMillis();
    }
    // Только реальные MTProto-данные доказывают, что релей жив: успешный
    // upgrade проходит и у релеев, которые дальше молча глотают трафик. Туннель
    // Cloudflare на мобильной сети отдаёт первые килобайты и замерзает, поэтому
    // его доказательство — объём больше порога заморозки, иначе первый же
    // ответ сбрасывал счётчик заморозок и туннель не отключался никогда.
    if (!reachableRecorded && phase == transport::HandshakePhase::FirstDataReceived
            && (!routeConfig.tunnel || bytesIn >= kTunnelProofBytes)) {
        reachableRecorded = true;
        recordRouteReachable(routeConfig);
    }
    if (!provenRecorded && isCdn(routeConfig) && bytesIn >= kCdnProofBytes) {
        provenRecorded = true;
        recordCdnProven(routeConfig);
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
    bytesOut += size;
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
    } else if (routeConfig.tunnel && !speculative && bytesIn == 0
            && readyAtMs != 0 && monotonicMillis() - readyAtMs >= kTunnelSilentAfterMs) {
        // The tunnel upgraded and then delivered nothing at all. A tunnel that
        // froze after some data is throttled, not dead: suppressing it sent
        // DC1/DC5 media to direct TCP, which the same network blocks outright
        // (desktop log 25.09), and nothing loaded for two minutes.
        recordRouteUnreachable(routeConfig);
        if (LOGS_ENABLED) {
            DEBUG_D("wss_socket tunnel_silent");
        }
    } else if (isCdn(routeConfig) && !speculative && bytesIn > 0 && bytesIn < kCdnProofBytes
            && timeoutMidPacket && outputDrained()) {
        // The front stopped in the middle of an answer: the freeze the tunnel
        // suffers. Silence between packets is not one: the connection timeout
        // also fires for requests pending on another DC, and it falsely
        // counted idle DC2/DC4 fronts that had every answer (logs (23)).
        // Counted apart from refusals; only a session past kCdnProofBytes
        // clears it.
        if (LOGS_ENABLED) {
            DEBUG_D("wss_socket cdn_stalled domain=%s rx=%llu", routeConfig.domain.c_str(), (unsigned long long) bytesIn);
        }
        advanceCdn(routeConfig);
        recordCdnStalled(routeConfig, bytesIn);
    }
}

void Socket::timedOutMidPacket(bool midPacket) {
    timeoutMidPacket = midPacket;
    timedOut();
    timeoutMidPacket = false;
}

bool Socket::outputDrained() const {
    // A photo going out slowly gets no answer until its part is complete, so
    // silence while bytes are still queued is an upload in progress, not a
    // frozen front. Only bytes not yet sent count: SIOCOUTQ also counts sent
    // but unacknowledged ones, and a frozen path drops those ACKs as well.
    int unsent = 0;
    if (socketFd >= 0 && ioctl(socketFd, SIOCOUTQNSD, &unsent) != 0) {
        unsent = 0;
    }
    return pendingOutputBytes == 0 && openingFrame.empty() && unsent == 0;
}

void Socket::setSpeculative(bool value) {
    if (speculative && !value) {
        fromPool = true;
    }
    speculative = value;
}

void Socket::noteAttemptFailed() {
    if (!failureRecorded && state != State::Ready) {
        failureRecorded = true;
        // The next connection tries another front, pool spares included: a
        // spare that met a refusal in the background spares a real one.
        advanceCdn(routeConfig);
        if (speculative) {
            return;
        }
        if (phase == transport::HandshakePhase::None && (tcpRecentlyConnected(routeConfig.network, peerAddress)
                || (isCdn(routeConfig) && tcpRecentlyConnected(routeConfig.network, kAnyCdnAddress)))) {
            // Соседние сокеты к этому адресу только что подключались: провайдер
            // съел SYN одного потока. Новый сокет пройдёт, а переход на запасной
            // адрес или в туннель здесь только навредит.
            if (LOGS_ENABLED) {
                DEBUG_D("wss_socket flow_timeout_ignored domain=%s address=%s",
                        routeConfig.domain.c_str(), peerAddress.c_str());
            }
            return;
        }
        if (!isCdn(routeConfig)) {
            // Fronts never switch to their DNS name: it resolves to the same
            // two addresses, the pool cannot keep it ready, and the address
            // preference and rotation already move away from a dead one.
            recordAttemptFailed(routeConfig);
        }
        if (phase == transport::HandshakePhase::None && !routeConfig.viaFallback) {
            noteCdnAddress(routeConfig, false);
        }
        if (isCdn(routeConfig)) {
            // A front refused at any stage, 503 included: counted, or a
            // Flowseal-wide outage would never reach the tunnel.
            recordRouteUnreachable(routeConfig);
        } else if (phase == transport::HandshakePhase::None) {
            if (isRelay(routeConfig) && !routeConfig.viaFallback) {
                Route address = routeConfig;
                address.healthDomain = relayAddressHealthName(routeConfig.relayHost);
                recordRouteUnreachable(address);
            }
            // Не дошли даже до установленного TCP: адрес релея недоступен, а не
            // протокол сломан. Несколько таких подряд — и датацентр уходит на
            // прямое соединение, вместо того чтобы навсегда остаться без медиа.
            recordRouteUnreachable(routeConfig);
            suppressRelayAddressWithDomain(routeConfig);
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
    advanceCdn(routeConfig);
    if (!isCdn(routeConfig)) {
        recordAttemptFailed(routeConfig);
    }
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
    // Not logged: these transitions happen on every read and write and made
    // up a sizeable share of the network log without explaining anything the
    // wss_session summary does not.
    (void) operation;
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

uint64_t Socket::receivedBytes() const {
    return bytesIn;
}

std::string Socket::takeSessionSummary() {
    if (socketFd < 0 || openedAtMs == 0) {
        return std::string();
    }
    summaryTaken = true;
    // One line per relay socket answers what used to take a script over the
    // whole log: which route it took, how far the handshake got, how much
    // went each way and how long it lived.
    char buffer[512];
    snprintf(buffer, sizeof(buffer),
            "domain=%s relay=%s route=%s pool=%s tx=%llu rx=%llu ready_ms=%lld first_data_ms=%lld last_data_ms=%lld life_ms=%lld",
            routeConfig.domain.c_str(),
            routeConfig.connectHost.c_str(),
            routeConfig.tunnel ? "tunnel" : (isCdn(routeConfig) ? (routeConfig.viaFallback ? "cdn_dns" : "cdn")
                    : (routeConfig.viaFallback ? "dns" : "ip")),
            speculative ? "spare" : (fromPool ? "hit" : "no"),
            (unsigned long long) bytesOut,
            (unsigned long long) bytesIn,
            (long long) (readyAtMs != 0 ? readyAtMs - openedAtMs : -1),
            (long long) (firstDataAtMs != 0 ? firstDataAtMs - openedAtMs : -1),
            (long long) (lastDataAtMs != 0 ? lastDataAtMs - openedAtMs : -1),
            (long long) (monotonicMillis() - openedAtMs));
    return buffer;
}

void Socket::close() {
    if (socketFd >= 0 && !summaryTaken && LOGS_ENABLED) {
        // Pool spares and sockets dropped without a disconnect line.
        DEBUG_D("wss_session %s", takeSessionSummary().c_str());
    }
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
