/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 */

#ifndef TGNET_WSS_POOL_H
#define TGNET_WSS_POOL_H

#include "WssSocket.h"

#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

class EventObject;

namespace tgnet {
namespace wss {

// Keeps WebSocket relay sockets that already finished TCP, TLS and the HTTP
// upgrade, so a connection that needs a relay starts sending at once instead
// of paying the handshake again — and instead of losing seconds to a provider
// that swallows the SYN of a single flow. One pool per ConnectionsManager: it
// lives on that account's network thread and its epoll, so nothing is shared
// across threads.
//
// Only routes a connection actually asked for are warmed, one spare each, and
// only for a while after the last request. A pooled socket has not sent the
// 64-byte obfuscation header yet, so the relay has not bound it to anything
// and the taker's first frame still carries the whole init.
class Pool {
public:
    Pool();
    ~Pool();

    void attach(int epollFd, std::function<int64_t()> clock);

    // A ready socket for exactly this route, or nullptr. Every call also marks
    // the route as wanted, so the pool keeps a spare for the next one.
    std::unique_ptr<Socket> take(const Route &route, int64_t now);

    // Handshake timeouts, expiry and refill. With allowed == false the pool is
    // emptied and stops opening sockets (paused, offline, WSS or proxy off).
    void tick(int64_t now, bool allowed);

    void clear(const char *reason);

    // Frees event objects of retired sockets. Call only outside of an epoll
    // batch: a stale event of the current batch may still point at them.
    void collectGarbage();

    struct Entry;
    void onEntryEvent(Entry *entry, uint32_t events);

private:
    struct Demand {
        Route route;
        int64_t lastWanted = 0;
        int64_t nextOpenAt = 0;
        int64_t backoffMs = 0;
    };

    void open(const std::string &key, Demand &demand, int64_t now);
    void retire(Entry *entry, bool backoff, const char *reason);
    void updateInterest(Entry *entry);
    size_t countFor(const std::string &key) const;

    int epollFd = -1;
    std::function<int64_t()> clock;
    std::vector<std::unique_ptr<Entry>> entries;
    std::map<std::string, Demand> demands;
    std::vector<EventObject *> graveyard;
};

// Called by EventObject for EventObjectTypeWssPool.
void DispatchPoolEvent(void *entry, uint32_t events);

} // namespace wss
} // namespace tgnet

#endif
