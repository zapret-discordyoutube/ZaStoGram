// Host test: drives tgnet::naive::Socket the way ConnectionSocket drives a transport (epoll, level-triggered,
// EPOLLOUT only while the socket wants to write), through a real Naive server, and checks an echo.
// Usage: naive_socket_host_test PORT TARGET_HOST TARGET_PORT   (127.0.0.1 proxy "proxy.test", labuser:labpass)
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/epoll.h>
#include <time.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "../NaiveSocket.h"

int main(int argc, char **argv) {
    tgnet::naive::Config config;
    config.proxyHost = "proxy.test";
    config.proxyPort = static_cast<uint16_t>(std::atoi(argv[1]));
    config.user = "labuser";
    config.password = "labpass";
    config.targetHost = argv[2];
    config.targetPort = static_cast<uint16_t>(std::atoi(argv[3]));
    config.insecureSkipVerify = true;
    config.caDir.clear();
    auto socket = tgnet::naive::CreateSocket(config);

    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(config.proxyPort);
    inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
    std::string diagnostic;
    if (!socket->open(reinterpret_cast<sockaddr *>(&a), sizeof(a), &diagnostic)) {
        std::printf("open failed: %s\n", diagnostic.c_str());
        return 2;
    }
    int ep = epoll_create1(0);
    epoll_event ev{};
    ev.events = EPOLLIN | EPOLLOUT;
    ev.data.fd = socket->fd();
    epoll_ctl(ep, EPOLL_CTL_ADD, socket->fd(), &ev);

    std::string want, got;
    std::vector<std::string> messages;
    for (int i = 0; i < 12; ++i) {
        messages.emplace_back(static_cast<size_t>(40 + 7 * i), static_cast<char>('A' + i));
        want += messages.back();
    }
    bool written = false;
    bool outArmed = true;
    for (int round = 0; round < 400 && got.size() < want.size(); ++round) {
        epoll_event events[4];
        const int n = epoll_wait(ep, events, 4, 100);
        uint32_t mask = 0;
        for (int i = 0; i < n; ++i) mask |= events[i].events;
        std::vector<std::vector<uint8_t>> payloads;
        if (n > 0 || round % 5 == 0) {
            if (!socket->onEvent(mask, payloads, &diagnostic)) {
                for (auto &p : payloads) got.append(p.begin(), p.end());
                std::printf("transport ended: %s (got %zu/%zu)\n", diagnostic.c_str(), got.size(), want.size());
                break;
            }
        }
        for (auto &p : payloads) got.append(p.begin(), p.end());
        if (socket->isReady() && !written) {
            written = true;
            for (auto &m : messages) {
                if (!socket->write(reinterpret_cast<const uint8_t *>(m.data()), static_cast<uint32_t>(m.size()), &diagnostic)) {
                    std::printf("write failed: %s\n", diagnostic.c_str());
                    return 3;
                }
            }
        }
        const bool wantOut = socket->wantsWrite();
        if (wantOut != outArmed) {  // like adjustWriteOp()
            ev.events = EPOLLIN | (wantOut ? EPOLLOUT : 0);
            epoll_ctl(ep, EPOLL_CTL_MOD, socket->fd(), &ev);
            outArmed = wantOut;
        }
    }
    std::printf("naive socket: ready=%d phase=%d got=%zu/%zu match=%s\n", socket->isReady() ? 1 : 0,
                static_cast<int>(socket->handshakePhase()), got.size(), want.size(), got == want ? "YES" : "NO");
    socket->close();
    return got == want ? 0 : 1;
}
