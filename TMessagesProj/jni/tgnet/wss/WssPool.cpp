/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 */

#include "WssPool.h"
#include "../EventObject.h"
#include "../FileLog.h"
#include "../../zsg_naive/WarpEntry.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/epoll.h>

#include <algorithm>
#include <cstring>

namespace tgnet {
namespace wss {
namespace {

constexpr size_t kSparePerRoute = 1;
// Keep warming a route only while connections keep asking for it.
constexpr int64_t kDemandTtlMs = 2 * 60 * 1000;
// The first spare waits: at startup every account opens its connections at
// once, and adding more sockets to that burst is what loses SYNs.
constexpr int64_t kFirstOpenDelayMs = 3000;
// kws relays close a socket that stays silent after the upgrade: measured
// 2026-09-24, 90 s idle still answered, 110 s was already closed.
constexpr int64_t kMaxIdleMs = 70 * 1000;
// Same limits as ConnectionSocket uses for its own handshakes.
constexpr int64_t kTcpConnectTimeoutMs = 2500;
constexpr int64_t kHandshakeTimeoutMs = 8000;
constexpr int64_t kMinBackoffMs = 2000;
constexpr int64_t kMaxBackoffMs = 60 * 1000;
// A download opens two connections to a DC at once (Download/Download2 by
// request parity), so one spare leaves the second waiting for its own dial.
// Two asks for the same front target this close together are a burst, and
// the second spare is kept warm for a while after it, not permanently: the
// fronts are a free third-party service.
constexpr int64_t kCdnBurstWindowMs = 3000;
constexpr int64_t kCdnBurstSpareMs = 30 * 1000;
constexpr size_t kCdnBurstSpares = 2;

std::string keyFor(const Route &route) {
    return route.connectHost + ":" + std::to_string(route.relayPort) + "|" + route.domain + route.path;
}

// Any ready front for the same DC serves as well as the one the cursor points
// at now: with four accounts' pools warming several DCs, each refused spare
// moves the shared cursor and would strand every other ready spare.
bool sameCdnTarget(const Route &spare, const Route &wanted) {
    return spare.cdnSlot >= 0 && wanted.cdnSlot >= 0
            && spare.network == wanted.network
            && spare.cdnDcId == wanted.cdnDcId
            && spare.path == wanted.path;
}

std::string cdnTargetKey(const Route &route) {
    return std::to_string(route.network) + "/" + std::to_string(route.cdnDcId) + route.path;
}

bool poolable(const Route &route) {
    struct in_addr parsed;
    // The tunnel carries a per-DC destination and freezes on throttled
    // networks anyway; a DNS-named relay would need a resolve on this thread.
    return !route.tunnel
            && !route.viaFallback
            && inet_pton(AF_INET, route.connectHost.c_str(), &parsed) == 1;
}

} // namespace

struct Pool::Entry {
    Pool *pool = nullptr;
    std::string key;
    std::unique_ptr<Socket> socket;
    EventObject *eventObject = nullptr;
    int64_t openedAt = 0;
    int64_t readyAt = 0;
    bool registered = false;
    bool writeInterest = false;
};

void DispatchPoolEvent(void *entry, uint32_t events) {
    auto poolEntry = static_cast<Pool::Entry *>(entry);
    poolEntry->pool->onEntryEvent(poolEntry, events);
}

Pool::Pool() = default;

Pool::~Pool() {
    clear("destroyed");
    collectGarbage();
}

void Pool::attach(int fd, std::function<int64_t()> monotonicClock) {
    epollFd = fd;
    clock = std::move(monotonicClock);
}

size_t Pool::countFor(const std::string &key, const Route &route) const {
    // Front spares are interchangeable for a DC (sameCdnTarget), and a spare
    // left on a front the cursor moved away from still serves it: counting by
    // key alone kept two to four warm per DC unaccounted.
    const bool cdn = route.cdnSlot >= 0;
    // Spares still dialing count per key only: about half of them end in a
    // 503, and counting them for the whole DC starved the refill.
    return static_cast<size_t>(std::count_if(entries.begin(), entries.end(), [&](const std::unique_ptr<Entry> &entry) {
        return entry->key == key
                || (cdn && entry->readyAt != 0 && entry->socket != nullptr
                        && sameCdnTarget(entry->socket->route(), route));
    }));
}

size_t Pool::sparesWanted(const Route &route, int64_t now) const {
    if (route.cdnSlot < 0) {
        return kSparePerRoute;
    }
    auto it = cdnBurstUntil.find(cdnTargetKey(route));
    return it != cdnBurstUntil.end() && now < it->second ? kCdnBurstSpares : kSparePerRoute;
}

std::unique_ptr<Socket> Pool::take(const Route &route, int64_t now) {
    if (!poolable(route)) {
        return nullptr;
    }
    const std::string key = keyFor(route);
    auto demandIt = demands.find(key);
    const bool newDemand = demandIt == demands.end();
    if (newDemand) {
        Demand demand;
        demand.route = route;
        demand.nextOpenAt = now + kFirstOpenDelayMs;
        demandIt = demands.emplace(key, std::move(demand)).first;
    }
    demandIt->second.lastWanted = now;
    // A burst is a second ask right after a hit (Download and Download2 of
    // one file). Misses do not count: a failing connection reconnecting every
    // ~1.3 s would otherwise keep two spares warm for the whole outage.
    const std::string target = route.cdnSlot >= 0 ? cdnTargetKey(route) : std::string();
    if (!target.empty()) {
        auto last = lastCdnTake.find(target);
        if (last != lastCdnTake.end() && last->second.hit && now - last->second.at < kCdnBurstWindowMs) {
            if (LOGS_ENABLED && cdnBurstUntil[target] <= now) {
                DEBUG_D("wss_pool burst dc=%d net=%d", route.cdnDcId, route.network);
            }
            cdnBurstUntil[target] = now + kCdnBurstSpareMs;
        }
        lastCdnTake[target] = {now, false};
    }
    for (auto &entry : entries) {
        if (entry->readyAt == 0 || !entry->socket->isReady()
                || (entry->key != key && !sameCdnTarget(entry->socket->route(), route))) {
            continue;
        }
        if (entry->registered) {
            epoll_ctl(epollFd, EPOLL_CTL_DEL, entry->socket->fd(), nullptr);
            entry->registered = false;
        }
        std::unique_ptr<Socket> socket = std::move(entry->socket);
        socket->setSpeculative(false);
        if (LOGS_ENABLED) {
            DEBUG_D("wss_pool hit domain=%s relay=%s idle_ms=%lld",
                    socket->route().domain.c_str(), socket->route().connectHost.c_str(), (long long) (now - entry->readyAt));
        }
        retire(entry.get(), false, nullptr);
        if (!target.empty()) {
            lastCdnTake[target].hit = true;
        }
        return socket;
    }
    // A miss on a front: the connection dials itself now, and a spare opened
    // at the same time is an independent second chance against the ~50% of
    // front connections refused with 503 (it serves the next connection of
    // this DC). Not for a demand seen for the first time, which is the start
    // burst kFirstOpenDelayMs keeps quiet, and not while backing off.
    Demand &demand = demandIt->second;
    if (route.cdnSlot >= 0 && !newDemand && demand.backoffMs == 0
            && countFor(key, demand.route) < sparesWanted(demand.route, now)) {
        open(key, demand, now);
    }
    return nullptr;
}

void Pool::tick(int64_t now, bool allowed) {
    if (!allowed) {
        if (!entries.empty()) {
            clear("not_allowed");
        }
        return;
    }
    std::vector<Entry *> expired;
    for (auto &entry : entries) {
        if (entry->readyAt == 0) {
            const bool tcpPending = entry->socket->handshakePhase() == transport::HandshakePhase::None;
            if (now - entry->openedAt > (tcpPending ? kTcpConnectTimeoutMs : kHandshakeTimeoutMs)) {
                expired.push_back(entry.get());
            }
        } else if (now - entry->readyAt > kMaxIdleMs) {
            expired.push_back(entry.get());
        }
    }
    for (Entry *entry : expired) {
        if (entry->readyAt == 0) {
            entry->socket->timedOut();
            retire(entry, true, "handshake_timeout");
        } else {
            retire(entry, false, "idle_expired");
        }
    }
    for (auto it = demands.begin(); it != demands.end();) {
        Demand &demand = it->second;
        if (now - demand.lastWanted > kDemandTtlMs) {
            it = demands.erase(it);
            continue;
        }
        // A failed front spare moves the catalog on; warm the front the next
        // connection will ask for instead of waiting for it to miss first.
        Route followed;
        if (!RouteUsable(demand.route) && FollowCdnRoute(demand.route, &followed)) {
            const std::string followedKey = keyFor(followed);
            if (demands.find(followedKey) == demands.end()) {
                Demand moved = demand;
                moved.route = followed;
                // The next spare goes to another front and edge, so the
                // doubling meant for a dead relay only delays it (25 s after
                // four refusals in the host test).
                moved.backoffMs = 0;
                moved.nextOpenAt = std::min(demand.nextOpenAt, now + kMinBackoffMs);
                demands.emplace(followedKey, std::move(moved));
            }
            it = demands.erase(it);
            continue;
        }
        // A route that is suppressed or switched to its DNS name will not be
        // asked for in this form, so a spare for it would just idle out.
        if (now >= demand.nextOpenAt && countFor(it->first, demand.route) < sparesWanted(demand.route, now)
                && RouteUsable(demand.route)) {
            open(it->first, demand, now);
        }
        ++it;
    }
}

void Pool::open(const std::string &key, Demand &demand, int64_t now) {
    if (epollFd < 0) {
        return;
    }
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons(demand.route.relayPort);
    if (inet_pton(AF_INET, demand.route.connectHost.c_str(), &address.sin_addr) != 1) {
        return;
    }
    auto entry = std::make_unique<Entry>();
    entry->pool = this;
    entry->key = key;
    entry->openedAt = now;
    entry->socket = std::make_unique<Socket>(demand.route);
    entry->socket->setSpeculative(true);
    std::string diagnostic;
    struct sockaddr_in warpAddress;
    if (tgnet::naive::WarpRedirect(address, &warpAddress)) {  // ZaStoGram WARP entry hop (opt-in)
        address = warpAddress;
    }
    if (!entry->socket->open(reinterpret_cast<const struct sockaddr *>(&address), sizeof(address), &diagnostic)) {
        demand.backoffMs = std::min(kMaxBackoffMs, std::max(kMinBackoffMs, demand.backoffMs * 2));
        demand.nextOpenAt = now + demand.backoffMs;
        if (LOGS_ENABLED) {
            DEBUG_D("wss_pool open_failed domain=%s diagnostic=%s", demand.route.domain.c_str(), diagnostic.c_str());
        }
        return;
    }
    entry->eventObject = new EventObject(entry.get(), EventObjectTypeWssPool);
    struct epoll_event event;
    memset(&event, 0, sizeof(event));
    // Level-triggered like ConnectionSocket: OpenSSL flips between wanting to
    // read and to write during the handshake.
    event.events = EPOLLIN | EPOLLOUT | EPOLLRDHUP | EPOLLERR;
    event.data.ptr = entry->eventObject;
    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, entry->socket->fd(), &event) != 0) {
        entry->socket->close();
        delete entry->eventObject;
        demand.nextOpenAt = now + kMinBackoffMs;
        return;
    }
    entry->registered = true;
    entry->writeInterest = true;
    // Until this spare is ready or gone, do not open another for the route.
    demand.nextOpenAt = now + kMaxBackoffMs;
    if (LOGS_ENABLED) {
        DEBUG_D("wss_pool open domain=%s relay=%s", demand.route.domain.c_str(), demand.route.connectHost.c_str());
    }
    entries.push_back(std::move(entry));
}

void Pool::onEntryEvent(Entry *entry, uint32_t events) {
    std::vector<std::vector<uint8_t>> payloads;
    std::string diagnostic;
    const bool alive = entry->socket->onEvent(events, payloads, &diagnostic);
    if (!alive || !payloads.empty()) {
        // Before the init frame the relay has nothing to say; data or a close
        // here means the socket is no longer a clean spare. Back off either
        // way, or a relay that drops idle sockets would be redialled in a loop.
        retire(entry, true, !alive ? (diagnostic.empty() ? "closed" : diagnostic.c_str()) : "unexpected_data");
        return;
    }
    if (entry->readyAt == 0 && entry->socket->isReady()) {
        entry->readyAt = clock();
        auto demandIt = demands.find(entry->key);
        if (demandIt != demands.end()) {
            demandIt->second.backoffMs = 0;
            demandIt->second.nextOpenAt = entry->readyAt;
        }
        if (LOGS_ENABLED) {
            DEBUG_D("wss_pool ready domain=%s handshake_ms=%lld",
                    entry->socket->route().domain.c_str(), (long long) (entry->readyAt - entry->openedAt));
        }
    }
    updateInterest(entry);
}

void Pool::updateInterest(Entry *entry) {
    const bool wantWrite = entry->socket->wantsWrite();
    if (!entry->registered || wantWrite == entry->writeInterest) {
        return;
    }
    struct epoll_event event;
    memset(&event, 0, sizeof(event));
    event.events = EPOLLIN | EPOLLRDHUP | EPOLLERR | (wantWrite ? EPOLLOUT : 0);
    event.data.ptr = entry->eventObject;
    if (epoll_ctl(epollFd, EPOLL_CTL_MOD, entry->socket->fd(), &event) == 0) {
        entry->writeInterest = wantWrite;
    }
}

void Pool::retire(Entry *entry, bool backoff, const char *reason) {
    auto demandIt = demands.find(entry->key);
    if (entry->socket != nullptr) {
        if (entry->registered) {
            epoll_ctl(epollFd, EPOLL_CTL_DEL, entry->socket->fd(), nullptr);
        }
        entry->socket->close();
        if (LOGS_ENABLED && reason != nullptr) {
            DEBUG_D("wss_pool drop domain=%s reason=%s", entry->socket->route().domain.c_str(), reason);
        }
    }
    if (demandIt != demands.end()) {
        Demand &demand = demandIt->second;
        const int64_t base = entry->readyAt != 0 ? entry->readyAt : entry->openedAt;
        if (backoff) {
            demand.backoffMs = std::min(kMaxBackoffMs, std::max(kMinBackoffMs, demand.backoffMs * 2));
            demand.nextOpenAt = base + demand.backoffMs;
        } else {
            demand.nextOpenAt = 0;
        }
    }
    if (entry->eventObject != nullptr) {
        // An event for this socket may still sit later in the current epoll
        // batch; the object stays valid and inert until collectGarbage().
        entry->eventObject->eventObject = nullptr;
        graveyard.push_back(entry->eventObject);
        entry->eventObject = nullptr;
    }
    entries.erase(std::remove_if(entries.begin(), entries.end(), [entry](const std::unique_ptr<Entry> &candidate) {
        return candidate.get() == entry;
    }), entries.end());
}

void Pool::clear(const char *reason) {
    while (!entries.empty()) {
        retire(entries.back().get(), false, reason);
    }
    if (reason != nullptr && strcmp(reason, "network_changed") == 0) {
        demands.clear();
    }
}

void Pool::collectGarbage() {
    for (EventObject *eventObject : graveyard) {
        delete eventObject;
    }
    graveyard.clear();
}

} // namespace wss
} // namespace tgnet
