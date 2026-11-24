/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 */

#include "WssSocket.h"
#include "../FileLog.h"
#include "../../zsg_naive/TrustRoots.h"
#include "zsg_naive/zsg_naive.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <openssl/rand.h>
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
        "fuckyourkn.copperbrook.workers.dev",
        "nodeone.brackencombe.workers.dev",
};
constexpr size_t kTunnelHostCount = sizeof(kTunnelHosts) / sizeof(kTunnelHosts[0]);
// DC203 serves non-Premium media and has no kws relay. The Cloudflare fronts
// carry it as kws203.<front> (16.10.2026: resPQ from every front tried), so
// it goes fronts -> tunnel -> direct -> fronts like a DC with a closed relay.
// As a tunnel-only DC it lost every file once the workers ran out of their
// daily quota and the route fell back to unguarded direct TCP (desktop log
// 16.10, 19:20).
constexpr int32_t kNoRelayDcId = 203;
// Size of the MTProto obfuscation header. Measured against the official relays:
// a first binary frame of 63 bytes never gets a reply, 64 always does.
constexpr size_t kObfuscationHeaderSize = 64;
constexpr size_t kMaxPendingOutput = 4 * 1024 * 1024;
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

// One health record per DC and network for direct TCP; media shares it.
std::string directHealthName(int32_t dcId) {
    return "direct-dc" + std::to_string(dcId < 0 ? -dcId : dcId);
}

// Telegram Web ingress addresses, not a proxy: the kwsN hostname stays the TLS
// identity and the last fallback. Each ingress serves only its own DCs. DC2
// and DC4 have two: DNS names 149.154.167.99 (web.telegram.org too), while
// 149.154.167.220 is the one every client hardcoded for months. Both carried
// all four kws2/kws4 names on 16.10.2026, 25 rounds each, with no difference
// beyond noise. A provider blocking one of them by IP (tg-ws-proxy issue
// #1450, 15.10.2026) leaves the other, which unlike the DNS fallback can keep
// pool spares and does not depend on the resolver.
const std::vector<std::string> &officialRelayIpsForDc(int32_t dcId) {
    static const std::vector<std::string> dc13 = {"149.154.174.100"};
    static const std::vector<std::string> dc24 = {"149.154.167.220", "149.154.167.99"};
    static const std::vector<std::string> dc5 = {"149.154.170.100"};
    static const std::vector<std::string> none;
    switch (dcId) {
        case 1:
        case 3:
            return dc13;
        case 2:
        case 4:
            return dc24;
        case 5:
            return dc5;
        default:
            return none;
    }
}

// Which ingress of a DC last delivered data on a network, and when each one
// failed. A dead address hands the DC to the next ingress before the DNS name.
// Guarded by relayPreferencesMutex.
struct RelayAddressState {
    std::string preferred;
    std::map<std::string, int64_t> failedAt;
};
std::map<std::string, RelayAddressState> relayAddressStates;

std::string relayAddressStateKey(int32_t network, int32_t dcId) {
    const auto &addresses = officialRelayIpsForDc(dcId);
    return networkKey(network, "ingress-" + (addresses.empty() ? std::string() : addresses.front()));
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
                route.direct ? "cdn" : (route.tunnel ? "direct" : (isCdn(route) ? "tunnel" : "cdn")));
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

// The ingress OfficialRoute dials: the one that last delivered data on this
// network, else the list order, with addresses that failed in the last two
// minutes moved back and suppressed ones skipped. With every address down it
// returns the first, whose suppression then sends the DC down the ladder.
std::string selectRelayAddress(int32_t network, int32_t dcId) {
    const auto &addresses = officialRelayIpsForDc(dcId);
    if (addresses.empty()) {
        return std::string();
    }
    std::vector<std::string> order;
    {
        std::lock_guard<std::mutex> lock(relayPreferencesMutex);
        auto it = relayAddressStates.find(relayAddressStateKey(network, dcId));
        const std::string preferred = it != relayAddressStates.end() ? it->second.preferred : std::string();
        if (std::find(addresses.begin(), addresses.end(), preferred) != addresses.end()) {
            order.push_back(preferred);
        }
        for (const auto &address : addresses) {
            if (address != preferred) {
                order.push_back(address);
            }
        }
        if (it != relayAddressStates.end()) {
            const int64_t now = monotonicMillis();
            std::stable_partition(order.begin(), order.end(), [&](const std::string &address) {
                auto failed = it->second.failedAt.find(address);
                return failed == it->second.failedAt.end() || now - failed->second >= kRouteSuppressTtlMs;
            });
        }
    }
    for (const auto &address : order) {
        if (!routeSuppressed(network, relayAddressHealthName(address))) {
            return address;
        }
    }
    return order.front();
}

// A relay address failed. If the DC has another ingress that has not failed in
// the last two minutes, the next connection dials it instead of the DNS name,
// and true is returned; otherwise the caller falls back to the name.
bool moveToNextRelayAddress(const Route &route) {
    if (!isRelay(route) || route.viaFallback || route.relayDcId == 0) {
        return false;
    }
    const auto &addresses = officialRelayIpsForDc(route.relayDcId);
    if (addresses.size() < 2) {
        return false;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    const int64_t now = monotonicMillis();
    RelayAddressState &state = relayAddressStates[relayAddressStateKey(route.network, route.relayDcId)];
    state.failedAt[route.relayHost] = now;
    for (const auto &address : addresses) {
        if (address == route.relayHost) {
            continue;
        }
        auto failed = state.failedAt.find(address);
        if (failed != state.failedAt.end() && now - failed->second < kRouteSuppressTtlMs) {
            continue;
        }
        if (state.preferred != address) {
            state.preferred = address;
            if (LOGS_ENABLED) {
                DEBUG_D("wss_route address_switch dc=%d net=%s from=%s to=%s", route.relayDcId,
                        networkName(route.network), route.relayHost.c_str(), address.c_str());
            }
        }
        return true;
    }
    return false;
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
    // Every ingress the DC went through before the name failed: DC2/DC4 try
    // both of theirs first.
    std::vector<std::string> relayAddresses = {route.relayHost};
    if (route.relayDcId != 0) {
        relayAddresses = officialRelayIpsForDc(route.relayDcId);
    }
    for (const auto &relayAddress : relayAddresses) {
        Route address = route;
        address.relayHost = relayAddress;
        address.healthDomain = relayAddressHealthName(relayAddress);
        RouteHealth &health = routeHealth[networkKey(route.network, address.healthDomain)];
        // Only a recent failure of the address itself: a stray SYN loss long ago
        // must not take a sibling off a working relay (logs (9): kws2 down while
        // kws4 on the same address kept working).
        if (health.consecutiveFailures > 0 && health.suppressedUntil <= now
                && health.lastFailureAt != 0 && now - health.lastFailureAt < 2 * 60 * 1000) {
            suppressLocked(address, health, now, "domain");
        }
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
        if (route.relayDcId != 0) {
            RelayAddressState &state = relayAddressStates[relayAddressStateKey(route.network, route.relayDcId)];
            state.preferred = route.relayHost;
            state.failedAt.erase(route.relayHost);
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

void setDiagnostic(std::string *diagnostic, const char *value) {
    if (diagnostic != nullptr) {
        *diagnostic = value;
    }
}

} // namespace

void NoteDirectStepClosed(const Route &route, uint64_t receivedBytes, const char *failure) {
    if (!route.direct) {
        return;
    }
    std::lock_guard<std::mutex> lock(relayPreferencesMutex);
    RouteHealth &health = routeHealth[networkKey(route.network, healthName(route))];
    const int64_t now = monotonicMillis();
    if (receivedBytes >= kDirectProofBytes) {
        if (LOGS_ENABLED && health.suppressions != 0) {
            DEBUG_D("wss_route restored domain=%s net=%s reason=proven", healthName(route).c_str(), networkName(route.network));
        }
        const bool persisted = health.suppressions != 0;
        health = RouteHealth();
        if (persisted) {
            saveRouteHealthLocked();
        }
        return;
    }
    if (failure == nullptr || health.suppressedUntil > now) {
        return;
    }
    health.lastFailureAt = now;
    if (LOGS_ENABLED) {
        DEBUG_D("wss_route failure domain=%s net=%s failures=1/1 received=%llu reason=%s", healthName(route).c_str(),
                networkName(route.network), (unsigned long long) receivedBytes, failure);
    }
    suppressLocked(route, health, now, failure);
}

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

// Direct TCP as the last guarded step. Networks that block the relays close
// direct TCP too, and an unguarded fallback there kept DC2 "connecting" for
// minutes (logs (1) (8)): one failure pauses it like a suppressed route, so a
// blocked network pays a few seconds once per pause, while one where only
// Cloudflare is closed still reaches the DC.
static bool DirectRoute(int32_t network, int32_t dcId, Route *route) {
    const std::string name = directHealthName(dcId);
    if (routeSuppressed(network, name)) {
        return false;
    }
    Route result;
    result.direct = true;
    result.network = network;
    result.domain = name;
    result.healthDomain = name;
    *route = std::move(result);
    return true;
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
    if (route != nullptr && !testBackend && dcId == kNoRelayDcId) {
        // DC203 отдаёт медиа аккаунтам без Premium и своего kws-релея не имеет.
        const int32_t network = currentNetwork.load();
        return CdnRoute(network, dcId, route) || TunnelRoute(network, dcAddress, route)
                || DirectRoute(network, dcId, route) || CdnRoute(network, dcId, route, true);
    }
    if (route == nullptr || testBackend || dcId < 1 || dcId > 5) {
        return false;
    }
    Route result;
    result.network = currentNetwork.load();
    result.relayHost = selectRelayAddress(result.network, dcId);
    if (result.relayHost.empty()) {
        return false;
    }
    result.relayDcId = dcId;
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
        // туннель через Worker, затем прямой TCP, если он не на паузе. Для
        // медиа пробовали и прямой путь первым (logs (13)): 27 попыток к медиа
        // DC1, ни одного TCP-подключения, тогда как задушенный туннель мелкими
        // частями хоть что-то отдаёт.
        if (CdnRoute(result.network, dcId, route) || TunnelRoute(result.network, dcAddress, route)
                || DirectRoute(result.network, dcId, route)) {
            return true;
        }
        // Everything is suppressed, direct TCP included. Unguarded, direct was
        // exactly what these networks block: a tester's DC2 sat in
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
            && (!isRelay(route) || route.relayDcId == 0
                || route.relayHost == selectRelayAddress(route.network, route.relayDcId))
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
    return startClient(diagnostic);
}

// The TLS handshake, the HTTP upgrade and the WebSocket framing are libzsgnaive's: the same Chrome 158 handshake and
// the exact header order of a Chrome WebSocket (see naive-core/SPEC.md). This class only moves bytes.
bool Socket::startClient(std::string *diagnostic) {
    zsg_ws_config config = {};
    config.host = routeConfig.domain.c_str();
    config.port = routeConfig.relayPort;
    config.path = routeConfig.path.c_str();
    config.origin = "https://web.telegram.org";
    config.user_agent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Mobile Safari/537.36";
    config.subprotocol = "binary";
    config.offer_deflate = 1;
    tgnet::naive::FillTrust(&config.ca_dir, &config.extra_roots_pem, &config.extra_roots_der, &config.extra_roots_der_count);
    char error[256] = {0};
    client = zsg_ws_new(&config, error, sizeof(error));
    if (client == nullptr) {
        if (LOGS_ENABLED) {
            DEBUG_E("wss_socket client_create_failed domain=%s error=%s", routeConfig.domain.c_str(), error);
        }
        setDiagnostic(diagnostic, "wss_tls_context_failed");
        return false;
    }
    state = State::Handshake;
    return pumpNetwork(diagnostic);
}

bool Socket::flushNetwork(std::string *diagnostic) {
    while (pendingOffset < pending.size()) {
        const ssize_t n = ::send(socketFd, pending.data() + pendingOffset, pending.size() - pendingOffset, MSG_NOSIGNAL);
        if (n > 0) {
            pendingOffset += static_cast<size_t>(n);
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            return true;
        }
        if (n < 0 && errno == EINTR) {
            continue;
        }
        setDiagnostic(diagnostic, "wss_write_failed");
        return false;
    }
    pending.clear();
    pendingOffset = 0;
    return true;
}

// client -> pending -> kernel
bool Socket::pumpNetwork(std::string *diagnostic) {
    uint8_t buffer[16 * 1024];
    size_t n;
    while ((n = zsg_ws_take_network(client, buffer, sizeof(buffer))) > 0) {
        pending.insert(pending.end(), buffer, buffer + n);
        if (pending.size() - pendingOffset > kMaxPendingOutput) {
            setDiagnostic(diagnostic, "wss_write_queue_full");
            return false;
        }
    }
    return flushNetwork(diagnostic);
}

// kernel -> client. |eof| is set when the peer closed its side; what was read before is still processed.
bool Socket::readNetwork(bool *eof, std::string *diagnostic) {
    uint8_t buffer[16 * 1024];
    while (true) {
        const ssize_t n = ::recv(socketFd, buffer, sizeof(buffer), 0);
        if (n > 0) {
            zsg_ws_feed_network(client, buffer, static_cast<size_t>(n));
            continue;
        }
        if (n == 0) {
            *eof = true;
            return true;
        }
        if (errno == EINTR) {
            continue;
        }
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return true;
        }
        setDiagnostic(diagnostic, "wss_read_failed");
        return false;
    }
}

// Maps the client's state onto the route bookkeeping. False = the connection is finished (diagnostic set).
bool Socket::checkClientState(std::string *diagnostic) {
    const int clientState = zsg_ws_state(client);
    if (clientState >= ZSG_WS_UPGRADING && phase < transport::HandshakePhase::TlsReady) {
        phase = transport::HandshakePhase::TlsReady;
        if (LOGS_ENABLED) {
            DEBUG_D("wss_socket tls_ready domain=%s", routeConfig.domain.c_str());
        }
    }
    if (clientState == ZSG_WS_OPEN && state == State::Handshake) {
        state = State::Ready;
        phase = transport::HandshakePhase::WebSocketReady;
        readyAtMs = monotonicMillis();
        noteUpgradeSucceeded();
        if (LOGS_ENABLED) {
            DEBUG_D("wss_socket upgrade_ok domain=%s deflate=%d", routeConfig.domain.c_str(), zsg_ws_deflate_negotiated(client));
        }
        return true;
    }
    if (clientState == ZSG_WS_REFUSED) {
        // A Cloudflare front that is still warming up answers an empty keep-alive 503/429: the same connection can
        // ask again. Anything else is a refusal.
        if (isCdn(routeConfig) && upgradeRetries < kCdnUpgradeRetries && zsg_ws_upgrade_retryable(client)
                && zsg_ws_retry_upgrade(client)) {
            ++upgradeRetries;
            if (LOGS_ENABLED) {
                DEBUG_D("wss_socket upgrade_retry domain=%s status=%d retry=%u",
                        routeConfig.domain.c_str(), zsg_ws_upgrade_status(client), upgradeRetries);
            }
            return pumpNetwork(diagnostic);
        }
        if (LOGS_ENABLED) {
            DEBUG_D("wss_socket upgrade_refused domain=%s status=%d retries=%u",
                    routeConfig.domain.c_str(), zsg_ws_upgrade_status(client), upgradeRetries);
        }
        if (routeConfig.tunnel && !speculative && zsg_ws_upgrade_status(client) == 429) {
            recordTunnelQuotaExhausted(routeConfig);
        }
        setDiagnostic(diagnostic, "wss_http_upgrade_failed");
        return false;
    }
    if (clientState == ZSG_WS_FAILED) {
        const std::string reason = zsg_ws_error(client);
        const char *name = "wss_ws_failed";
        if (reason.find("certificate verify") != std::string::npos) {
            name = "wss_tls_verify_failed";
        } else if (reason.compare(0, 3, "TLS") == 0) {
            name = "wss_tls_failed";
        } else if (reason.find("accept") != std::string::npos) {
            name = reason.find("missing") != std::string::npos ? "wss_accept_missing" : "wss_accept_mismatch";
        }
        if (LOGS_ENABLED) {
            DEBUG_E("wss_socket client_failed domain=%s reason=%s", routeConfig.domain.c_str(), reason.c_str());
        }
        setDiagnostic(diagnostic, name);
        return false;
    }
    return true;
}

// Delivers every received message and does the accounting that proves a route works.
void Socket::collectMessages(std::vector<std::vector<uint8_t>> &payloads) {
    size_t size;
    while ((size = zsg_ws_next_message_size(client)) > 0) {
        std::vector<uint8_t> message(size);
        zsg_ws_pop_message(client, message.data(), message.size());
        bytesIn += message.size();
        payloads.push_back(std::move(message));
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
    if (state == State::TcpConnecting) {
        return true;
    }
    // On peer close (EPOLLRDHUP) or EOF keep draining and parsing: the final
    // frames may carry the server's MTProto transport error code, and dropping
    // them hides the disconnect reason from the layer above.
    bool eof = false;
    bool readFailed = false;
    std::string readDiagnostic;
    if (events & (EPOLLIN | EPOLLRDHUP | EPOLLOUT)) {
        if (!readNetwork(&eof, &readDiagnostic)) {
            readFailed = true;
            noteAttemptFailed();
        }
    }
    if (!pumpNetwork(diagnostic)) {
        noteAttemptFailed();
        return false;
    }
    if (!checkClientState(diagnostic)) {
        collectMessages(payloads);  // frames drained ahead of a failure may carry the transport error code
        noteAttemptFailed();
        return false;
    }
    collectMessages(payloads);
    if (zsg_ws_state(client) == ZSG_WS_CLOSED) {
        setDiagnostic(diagnostic, "wss_close_frame");
        return false;
    }
    if (readFailed) {
        setDiagnostic(diagnostic, readDiagnostic.empty() ? "wss_read_failed" : readDiagnostic.c_str());
        return false;
    }
    if (eof || (events & EPOLLRDHUP)) {
        setDiagnostic(diagnostic, eof && state != State::Ready ? "wss_recv_eof" : "wss_socket_closed");
        noteAttemptFailed();
        return false;
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
    if (queuedOutputBytes() > kMaxPendingOutput) {
        setDiagnostic(diagnostic, "wss_write_queue_full");
        return false;
    }
    const uint8_t *frameData = data;
    uint32_t frameSize = size;
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
        const bool sent = zsg_ws_send(client, openingFrame.data(), openingFrame.size()) == 1;
        openingFrame.clear();
        openingFrame.shrink_to_fit();
        if (!sent) {
            setDiagnostic(diagnostic, "wss_write_failed");
            return false;
        }
        return pumpNetwork(diagnostic);
    }
    if (zsg_ws_send(client, frameData, frameSize) != 1) {
        setDiagnostic(diagnostic, "wss_write_failed");
        return false;
    }
    return pumpNetwork(diagnostic);
}

size_t Socket::queuedOutputBytes() const {
    return (pending.size() - pendingOffset) + openingFrame.size();
}

bool Socket::isReady() const {
    return state == State::Ready;
}

bool Socket::wantsWrite() const {
    // Nothing waits for input before it can write: the client never blocks a write on a read.
    return state == State::TcpConnecting || pendingOffset < pending.size();
}

bool Socket::canWriteApplicationData() const {
    return state == State::Ready;
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
        DEBUG_D("wss_socket timeout domain=%s state=%s", routeConfig.domain.c_str(), stateName());
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
    return pendingOffset >= pending.size() && openingFrame.empty() && unsent == 0;
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
            // A relay first tries its DC's other ingress, then the name.
            if (!moveToNextRelayAddress(routeConfig)) {
                recordAttemptFailed(routeConfig);
            }
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
    if (!isCdn(routeConfig) && !moveToNextRelayAddress(routeConfig)) {
        recordAttemptFailed(routeConfig);
    }
    recordRouteUnreachable(routeConfig);
    if (LOGS_ENABLED) {
        DEBUG_D("wss_socket appdata_timeout domain=%s relay=%s fallback=%d",
                routeConfig.domain.c_str(), routeConfig.connectHost.c_str(), routeConfig.viaFallback ? 1 : 0);
    }
}

const char *Socket::stateName() const {
    switch (state) {
        case State::TcpConnecting:
            return "tcp_connecting";
        case State::Handshake:
            return "handshake";
        case State::Ready:
            return "ready";
        case State::Closed:
            return "closed";
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
    if (client != nullptr) {
        if (state == State::Ready) {
            zsg_ws_close(client);  // a proper close frame; nothing waits for the answer
            uint8_t buffer[512];
            size_t n;
            while (socketFd >= 0 && (n = zsg_ws_take_network(client, buffer, sizeof(buffer))) > 0) {
                if (::send(socketFd, buffer, n, MSG_NOSIGNAL | MSG_DONTWAIT) <= 0) {
                    break;
                }
            }
        }
        zsg_ws_free(client);
        client = nullptr;
    }
    if (socketFd >= 0) {
        ::close(socketFd);
        socketFd = -1;
    }
    state = State::Closed;
    phase = transport::HandshakePhase::None;
    pending.clear();
    pendingOffset = 0;
    openingFrame.clear();
    openingFrame.shrink_to_fit();
    openingFrameSent = false;
}

const Route &Socket::route() const {
    return routeConfig;
}

std::unique_ptr<transport::Socket> CreateSocket(Route route) {
    return std::unique_ptr<transport::Socket>(new Socket(std::move(route)));
}

} // namespace wss
} // namespace tgnet
