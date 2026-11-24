#include "WebProxy.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "TrustRoots.h"
#include "zsg_naive/zsg_naive.h"

namespace tgnet {
namespace naive {

namespace {

constexpr size_t kChunk = 16 * 1024;
constexpr size_t kMaxHeader = 16 * 1024;
constexpr size_t kMaxClients = 512;
constexpr size_t kMaxBuffered = 1024 * 1024;  // per direction, per client: beyond it we stop reading the other side
constexpr int64_t kTunnelTimeoutMs = 30000;
constexpr int64_t kUpstreamIdleMs = 30000;

int64_t NowMs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

void SetNonBlocking(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    const int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
}

struct Buffer {
    std::vector<uint8_t> data;
    size_t offset = 0;
    size_t size() const { return data.size() - offset; }
    bool empty() const { return offset >= data.size(); }
    void append(const uint8_t *p, size_t n) { data.insert(data.end(), p, p + n); }
    void append(const std::string &s) { append(reinterpret_cast<const uint8_t *>(s.data()), s.size()); }
    void consume(size_t n) {
        offset += n;
        if (offset >= data.size()) {
            data.clear();
            offset = 0;
        }
    }
};

enum class State { ReadHeader, WaitTunnel, Pipe, Closing, Dead };

struct Client {
    int fd = -1;
    State state = State::ReadHeader;
    std::string header;
    int tunnel = 0;
    Buffer toClient;
    Buffer toTunnel;
    bool clientEof = false;
    int64_t since = 0;
};

class Proxy {
public:
    int start(const WebProxyConfig &c) {
        config = c;
        listenFd = socket(AF_INET, SOCK_STREAM, 0);
        if (listenFd < 0) {
            return 0;
        }
        const int one = 1;
        setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        sockaddr_in a = {};
        a.sin_family = AF_INET;
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        a.sin_port = 0;
        socklen_t len = sizeof(a);
        if (bind(listenFd, reinterpret_cast<sockaddr *>(&a), sizeof(a)) != 0 || listen(listenFd, 64) != 0 ||
            getsockname(listenFd, reinterpret_cast<sockaddr *>(&a), &len) != 0 || pipe(wake) != 0) {
            closeAll();
            return 0;
        }
        SetNonBlocking(listenFd);
        SetNonBlocking(wake[0]);
        port = ntohs(a.sin_port);
        running = true;
        worker = std::thread([this] { run(); });
        return port;
    }

    void stop() {
        running = false;
        if (wake[1] >= 0) {
            const char b = 1;
            (void) !write(wake[1], &b, 1);
        }
        if (worker.joinable()) {
            worker.join();
        }
        closeAll();
    }

    const WebProxyConfig &cfg() const { return config; }
    int boundPort() const { return port; }

private:
    void closeAll() {
        for (auto &c : clients) {
            dropClient(*c);
        }
        clients.clear();
        teardownUpstream();
        for (int *fd : {&listenFd, &wake[0], &wake[1]}) {
            if (*fd >= 0) {
                ::close(*fd);
                *fd = -1;
            }
        }
    }

    // ---------------------------------------------------------------- upstream (one shared Naive connection)
    bool upstreamAlive() const {
        if (naive == nullptr) {
            return false;
        }
        const int s = zsg_naive_state(naive);
        return s != ZSG_NAIVE_FAILED && s != ZSG_NAIVE_CLOSED;
    }

    bool ensureUpstream() {
        if (upstreamAlive()) {
            return true;
        }
        teardownUpstream();
        addrinfo hints = {};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo *res = nullptr;
        if (getaddrinfo(config.proxyHost.c_str(), std::to_string(config.proxyPort).c_str(), &hints, &res) != 0 || res == nullptr) {
            return false;
        }
        zsg_naive_config c = {};
        c.host = config.proxyHost.c_str();
        c.port = config.proxyPort;
        c.user = config.user.c_str();
        c.password = config.password.c_str();
        if (config.insecureSkipVerify) {
            c.insecure_skip_verify = 1;
        } else if (!config.caFile.empty()) {
            c.ca_file = config.caFile.c_str();
        } else {
            FillTrust(&c.ca_dir, &c.extra_roots_pem, &c.extra_roots_der, &c.extra_roots_der_count);
        }
        c.user_agent = kUserAgent;
        c.sec_ch_ua = kSecChUa;
        c.sec_ch_ua_mobile = "?1";
        c.sec_ch_ua_platform = "\"Android\"";
        c.send_preamble = 1;
        char error[256] = {0};
        naive = zsg_naive_new(&c, error, sizeof(error));
        if (naive == nullptr) {
            freeaddrinfo(res);
            return false;
        }
        upFd = socket(res->ai_family, SOCK_STREAM, 0);
        if (upFd < 0) {
            freeaddrinfo(res);
            teardownUpstream();
            return false;
        }
        SetNonBlocking(upFd);
        upConnecting = true;
        if (connect(upFd, res->ai_addr, res->ai_addrlen) != 0 && errno != EINPROGRESS) {
            freeaddrinfo(res);
            teardownUpstream();
            return false;
        }
        freeaddrinfo(res);
        upLastUse = NowMs();
        return true;
    }

    void teardownUpstream() {
        if (upFd >= 0) {
            ::close(upFd);
            upFd = -1;
        }
        if (naive != nullptr) {
            zsg_naive_free(naive);
            naive = nullptr;
        }
        upConnecting = false;
        upOut = Buffer();
        // Every tunnel died with the connection.
        for (auto &c : clients) {
            if (c->tunnel != 0) {
                c->tunnel = 0;
                if (c->state == State::WaitTunnel) {
                    c->toClient.append("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n");
                }
                if (c->state == State::WaitTunnel || c->state == State::Pipe) {
                    c->state = State::Closing;
                }
            }
        }
    }

    void pumpUpstream() {
        if (naive == nullptr) {
            return;
        }
        uint8_t buf[kChunk];
        size_t n;
        while ((n = zsg_naive_take_network(naive, buf, sizeof(buf))) > 0) {
            upOut.append(buf, n);
        }
        if (!upConnecting) {
            while (!upOut.empty()) {
                const ssize_t w = send(upFd, upOut.data.data() + upOut.offset, upOut.size(), MSG_NOSIGNAL);
                if (w > 0) {
                    upOut.consume(static_cast<size_t>(w));
                } else if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                    break;
                } else if (w < 0 && errno == EINTR) {
                    continue;
                } else {
                    teardownUpstream();
                    return;
                }
            }
        }
        if (!upstreamAlive()) {
            teardownUpstream();
        }
    }

    void onUpstreamEvents(short events) {
        if (naive == nullptr) {
            return;
        }
        if (upConnecting && (events & (POLLOUT | POLLIN | POLLERR | POLLHUP))) {
            int soError = 0;
            socklen_t len = sizeof(soError);
            getsockopt(upFd, SOL_SOCKET, SO_ERROR, &soError, &len);
            if (soError != 0) {
                teardownUpstream();
                return;
            }
            upConnecting = false;
        }
        if (upConnecting) {
            return;
        }
        if (events & (POLLIN | POLLHUP | POLLERR)) {
            uint8_t buf[kChunk];
            for (;;) {
                const ssize_t n = recv(upFd, buf, sizeof(buf), 0);
                if (n > 0) {
                    zsg_naive_feed_network(naive, buf, static_cast<size_t>(n));
                    continue;
                }
                if (n == 0) {
                    stepClients();  // hand over what arrived before the close
                    teardownUpstream();
                    return;
                }
                if (errno == EINTR) {
                    continue;
                }
                if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    teardownUpstream();
                }
                return;
            }
        }
    }

    // ---------------------------------------------------------------- clients
    void dropClient(Client &c) {
        if (c.tunnel != 0 && naive != nullptr) {
            zsg_naive_close_tunnel(naive, c.tunnel);
        }
        c.tunnel = 0;
        if (c.fd >= 0) {
            ::close(c.fd);
            c.fd = -1;
        }
        c.state = State::Dead;
    }

    static bool ParseConnect(const std::string &head, std::string *host, uint16_t *port) {
        const size_t eol = head.find("\r\n");
        const std::string line = head.substr(0, eol);
        if (line.compare(0, 8, "CONNECT ") != 0) {
            return false;
        }
        const size_t sp = line.find(' ', 8);
        const std::string target = line.substr(8, sp == std::string::npos ? std::string::npos : sp - 8);
        const size_t colon = target.rfind(':');
        if (colon == std::string::npos || colon == 0 || colon + 1 >= target.size()) {
            return false;
        }
        std::string h = target.substr(0, colon);
        if (h.size() > 2 && h.front() == '[' && h.back() == ']') {
            h = h.substr(1, h.size() - 2);
        }
        const long p = strtol(target.c_str() + colon + 1, nullptr, 10);
        if (h.empty() || h.size() > 253 || p <= 0 || p > 65535) {
            return false;
        }
        *host = h;
        *port = static_cast<uint16_t>(p);
        return true;
    }

    void refuse(Client &c, const char *status) {
        c.toClient.append(std::string("HTTP/1.1 ") + status + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
        c.state = State::Closing;
    }

    void stepClient(Client &c) {
        switch (c.state) {
            case State::ReadHeader: {
                const size_t end = c.header.find("\r\n\r\n");
                if (end == std::string::npos) {
                    if (c.header.size() > kMaxHeader) {
                        refuse(c, "431 Request Header Fields Too Large");
                    }
                    return;
                }
                std::string host;
                uint16_t port = 0;
                if (!ParseConnect(c.header, &host, &port)) {
                    refuse(c, "501 Not Implemented");  // plain http (and anything but CONNECT) must not leave another way
                    return;
                }
                const std::string rest = c.header.substr(end + 4);
                if (!rest.empty()) {
                    c.toTunnel.append(rest);
                }
                c.header.clear();
                if (!ensureUpstream()) {
                    refuse(c, "502 Bad Gateway");
                    return;
                }
                upLastUse = NowMs();
                c.tunnel = zsg_naive_open_tunnel(naive, host.c_str(), port);
                if (c.tunnel == 0) {
                    refuse(c, "502 Bad Gateway");
                    return;
                }
                c.state = State::WaitTunnel;
                c.since = NowMs();
                return;
            }
            case State::WaitTunnel: {
                if (naive == nullptr || c.tunnel == 0) {
                    refuse(c, "502 Bad Gateway");
                    return;
                }
                const int ts = zsg_naive_tunnel_state(naive, c.tunnel);
                if (ts == ZSG_TUNNEL_OPEN) {
                    c.toClient.append("HTTP/1.1 200 Connection Established\r\n\r\n");
                    c.state = State::Pipe;
                } else if (ts == ZSG_TUNNEL_FAILED || ts == ZSG_TUNNEL_CLOSED) {
                    refuse(c, "502 Bad Gateway");
                } else if (NowMs() - c.since > kTunnelTimeoutMs) {
                    refuse(c, "504 Gateway Timeout");
                }
                return;
            }
            case State::Pipe: {
                if (naive == nullptr || c.tunnel == 0) {
                    c.state = State::Closing;
                    return;
                }
                if (!c.toTunnel.empty()) {
                    const size_t n = zsg_naive_tunnel_write(naive, c.tunnel, c.toTunnel.data.data() + c.toTunnel.offset, c.toTunnel.size());
                    c.toTunnel.consume(n);
                    upLastUse = NowMs();
                }
                uint8_t buf[kChunk];
                size_t n;
                while (c.toClient.size() < kMaxBuffered && (n = zsg_naive_tunnel_read(naive, c.tunnel, buf, sizeof(buf))) > 0) {
                    c.toClient.append(buf, n);
                    upLastUse = NowMs();
                }
                const int ts = zsg_naive_tunnel_state(naive, c.tunnel);
                if (ts == ZSG_TUNNEL_FAILED || ts == ZSG_TUNNEL_CLOSED ||
                    (zsg_naive_tunnel_eof(naive, c.tunnel) && c.toClient.size() < kMaxBuffered)) {
                    c.state = State::Closing;
                } else if (c.clientEof && c.toTunnel.empty()) {
                    c.state = State::Closing;  // the browser is done; close our side of the tunnel
                }
                return;
            }
            default:
                return;
        }
    }

    void stepClients() {
        for (auto &c : clients) {
            stepClient(*c);
        }
    }

    bool flushClient(Client &c) {
        while (!c.toClient.empty()) {
            const ssize_t w = send(c.fd, c.toClient.data.data() + c.toClient.offset, c.toClient.size(), MSG_NOSIGNAL);
            if (w > 0) {
                c.toClient.consume(static_cast<size_t>(w));
            } else if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                return true;
            } else if (w < 0 && errno == EINTR) {
                continue;
            } else {
                return false;
            }
        }
        return true;
    }

    void onClientReadable(Client &c) {
        uint8_t buf[kChunk];
        const ssize_t n = recv(c.fd, buf, sizeof(buf), 0);
        if (n > 0) {
            if (c.state == State::ReadHeader) {
                c.header.append(reinterpret_cast<char *>(buf), static_cast<size_t>(n));
            } else if (c.state == State::Pipe || c.state == State::WaitTunnel) {
                c.toTunnel.append(buf, static_cast<size_t>(n));
            }
        } else if (n == 0) {
            c.clientEof = true;
            if (c.state == State::ReadHeader || c.state == State::WaitTunnel) {
                c.state = State::Closing;
            }
        } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
            c.clientEof = true;
            c.toClient = Buffer();
            c.state = State::Closing;
        }
    }

    void acceptClients() {
        for (;;) {
            const int fd = accept(listenFd, nullptr, nullptr);
            if (fd < 0) {
                return;
            }
            if (clients.size() >= kMaxClients) {
                ::close(fd);
                continue;
            }
            SetNonBlocking(fd);
            auto c = std::make_unique<Client>();
            c->fd = fd;
            c->since = NowMs();
            clients.push_back(std::move(c));
        }
    }

    // ---------------------------------------------------------------- loop
    void run() {
        while (running) {
            std::vector<pollfd> fds;
            fds.push_back({wake[0], POLLIN, 0});
            fds.push_back({listenFd, POLLIN, 0});
            const bool haveUp = naive != nullptr && upFd >= 0;
            if (haveUp) {
                fds.push_back({upFd, static_cast<short>(POLLIN | (upConnecting || !upOut.empty() ? POLLOUT : 0)), 0});
            }
            const size_t base = fds.size();
            for (auto &c : clients) {
                short ev = 0;
                const bool wantRead = c->state == State::ReadHeader ||
                                      ((c->state == State::Pipe || c->state == State::WaitTunnel) && !c->clientEof &&
                                       c->toTunnel.size() < kMaxBuffered);
                if (wantRead) {
                    ev |= POLLIN;
                }
                if (!c->toClient.empty()) {
                    ev |= POLLOUT;
                }
                fds.push_back({c->fd, ev, 0});
            }
            poll(fds.data(), fds.size(), 100);
            if (!running) {
                break;
            }
            if (fds[0].revents & POLLIN) {
                char b[16];
                while (read(wake[0], b, sizeof(b)) > 0) {
                }
            }
            if (fds[1].revents & POLLIN) {
                acceptClients();
            }
            if (haveUp) {
                onUpstreamEvents(fds[2].revents);
            }
            for (size_t i = 0; i < clients.size() && base + i < fds.size(); ++i) {
                Client &c = *clients[i];
                const short re = fds[base + i].revents;
                if (c.state == State::Dead) {
                    continue;
                }
                if (re & POLLIN) {
                    onClientReadable(c);
                }
                if (re & (POLLERR | POLLNVAL)) {
                    c.toClient = Buffer();
                    c.state = State::Closing;
                }
            }
            stepClients();
            pumpUpstream();
            for (auto &c : clients) {
                if (c->state == State::Dead) {
                    continue;
                }
                if (!flushClient(*c)) {
                    dropClient(*c);
                } else if (c->state == State::Closing && c->toClient.empty()) {
                    dropClient(*c);
                }
            }
            clients.erase(std::remove_if(clients.begin(), clients.end(), [](const std::unique_ptr<Client> &c) { return c->state == State::Dead; }),
                          clients.end());
            pumpUpstream();  // tunnel closes queued by dropClient
            if (clients.empty() && naive != nullptr && NowMs() - upLastUse > kUpstreamIdleMs) {
                teardownUpstream();
            }
        }
    }

    static constexpr const char *kUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Mobile Safari/537.36";
    static constexpr const char *kSecChUa = "\"Chromium\";v=\"158\", \"Not.A/Brand\";v=\"24\", \"Google Chrome\";v=\"158\"";

    WebProxyConfig config;
    int listenFd = -1;
    int wake[2] = {-1, -1};
    int port = 0;
    std::atomic<bool> running{false};
    std::thread worker;
    std::vector<std::unique_ptr<Client>> clients;
    ::zsg_naive *naive = nullptr;
    int upFd = -1;
    bool upConnecting = false;
    Buffer upOut;
    int64_t upLastUse = 0;
};

std::mutex gMutex;
std::unique_ptr<Proxy> gProxy;

}  // namespace

int StartWebProxy(const WebProxyConfig &config) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gProxy != nullptr) {
        const WebProxyConfig &old = gProxy->cfg();
        if (old.proxyHost == config.proxyHost && old.proxyPort == config.proxyPort && old.user == config.user &&
            old.password == config.password && old.caFile == config.caFile && old.insecureSkipVerify == config.insecureSkipVerify) {
            return gProxy->boundPort();
        }
        gProxy->stop();
        gProxy.reset();
    }
    auto proxy = std::make_unique<Proxy>();
    const int port = proxy->start(config);
    if (port == 0) {
        return 0;
    }
    gProxy = std::move(proxy);
    return port;
}

void StopWebProxy() {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gProxy != nullptr) {
        gProxy->stop();
        gProxy.reset();
    }
}

} // namespace naive
} // namespace tgnet
