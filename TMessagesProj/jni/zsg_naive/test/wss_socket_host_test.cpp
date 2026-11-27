// Host test of the REAL rewritten tgnet/wss/WssSocket.cpp: the socket is driven like ConnectionSocket drives it
// (level-triggered epoll, EPOLLOUT only while wantsWrite()), against scripts/ws_echo_server.py with real certificate
// verification against the lab CA. Checks: upgrade, the 64-byte opening-frame rule, echo, close.
// Usage: wss_socket_host_test PORT [plain]   (env ZSG_LAB_CA_FILE = lab CA PEM; "plain" = ws:// against ws_echo_server.py --plain)
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/epoll.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "WssSocket.h"

int main(int argc, char **argv) {
    tgnet::wss::Route route;
    route.relayHost = "proxy.test";
    route.connectHost = "127.0.0.1";
    route.relayPort = static_cast<uint16_t>(std::atoi(argv[1]));
    route.domain = "proxy.test";
    route.path = "/apiws";
    route.healthDomain = "proxy.test";
    route.plain = argc > 2 && std::string(argv[2]) == "plain";
    auto socket = tgnet::wss::CreateSocket(route);

    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(route.relayPort);
    inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
    std::string diagnostic;
    if (!socket->open(reinterpret_cast<sockaddr *>(&a), sizeof(a), &diagnostic)) {
        std::printf("open failed: %s\n", diagnostic.c_str());
        return 2;
    }
    int ep = epoll_create1(0);
    epoll_event ev{};
    ev.events = EPOLLOUT | EPOLLIN | EPOLLRDHUP | EPOLLERR;
    ev.data.fd = socket->fd();
    epoll_ctl(ep, EPOLL_CTL_ADD, socket->fd(), &ev);

    const std::string first(10, 'a');                    // shorter than the 64-byte header: must be held back
    const std::string second(70, 'b');
    std::string want = first + second;                   // the relay must see ONE first frame of 80 bytes
    std::string got;
    bool wrote = false;
    for (int round = 0; round < 300 && got.size() < want.size(); ++round) {
        epoll_event events[4];
        const int n = epoll_wait(ep, events, 4, 100);
        uint32_t mask = 0;
        for (int i = 0; i < n; ++i) mask |= events[i].events;
        std::vector<std::vector<uint8_t>> payloads;
        if (n > 0 || round % 5 == 0) {
            if (!socket->onEvent(mask, payloads, &diagnostic)) {
                std::printf("transport ended: %s\n", diagnostic.c_str());
                return 4;
            }
        }
        for (auto &p : payloads) got.append(p.begin(), p.end());
        if (socket->isReady() && !wrote) {
            wrote = true;
            const bool held = socket->write(reinterpret_cast<const uint8_t *>(first.data()), static_cast<uint32_t>(first.size()), &diagnostic);
            std::printf("first write (10 bytes): ok=%d queued=%zu (held back, nothing sent yet)\n", held, socket->queuedOutputBytes());
            socket->write(reinterpret_cast<const uint8_t *>(second.data()), static_cast<uint32_t>(second.size()), &diagnostic);
        }
        ev.events = EPOLLIN | EPOLLRDHUP | EPOLLERR | (socket->wantsWrite() ? EPOLLOUT : 0);
        epoll_ctl(ep, EPOLL_CTL_MOD, socket->fd(), &ev);
    }
    std::printf("wss socket: ready=%d phase=%d got=%zu/%zu match=%s name=%s\n", socket->isReady() ? 1 : 0,
                static_cast<int>(socket->handshakePhase()), got.size(), want.size(), got == want ? "YES" : "NO",
                socket->transportName());
    socket->close();
    return got == want ? 0 : 1;
}
