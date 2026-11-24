#include "NaiveSocket.h"

#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>

#include "zsg_naive/zsg_naive.h"

namespace tgnet {
namespace naive {

namespace {
constexpr size_t kReadChunk = 16 * 1024;
constexpr size_t kMaxPendingBytes = 4 * 1024 * 1024;
}

Socket::Socket(Config c) : config(std::move(c)) {}

Socket::~Socket() { close(); }

bool Socket::fail(std::string *diagnostic, const char *why) {
    if (diagnostic != nullptr) {
        *diagnostic = why;
    }
    return false;
}

bool Socket::open(const struct sockaddr *address, socklen_t addressLength, std::string *diagnostic) {
    close();
    zsg_naive_config c = {};
    c.host = config.proxyHost.c_str();
    c.port = config.proxyPort;
    c.user = config.user.c_str();
    c.password = config.password.c_str();
    c.ca_dir = config.caDir.empty() ? nullptr : config.caDir.c_str();
    c.insecure_skip_verify = config.insecureSkipVerify ? 1 : 0;
    c.user_agent = config.userAgent.c_str();
    c.sec_ch_ua = config.secChUa.c_str();
    c.sec_ch_ua_mobile = config.secChUaMobile.c_str();
    c.sec_ch_ua_platform = config.secChUaPlatform.c_str();
    c.send_preamble = 1;
    char error[256] = {0};
    client = zsg_naive_new(&c, error, sizeof(error));
    if (client == nullptr) {
        return fail(diagnostic, "naive_client_create_failed");
    }
    socketFd = socket(address->sa_family, SOCK_STREAM, 0);
    if (socketFd < 0) {
        return fail(diagnostic, "naive_socket_create_failed");
    }
    const int flags = fcntl(socketFd, F_GETFL, 0);
    fcntl(socketFd, F_SETFL, flags | O_NONBLOCK);
    const int one = 1;
    setsockopt(socketFd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    closed = false;
    if (connect(socketFd, address, addressLength) != 0 && errno != EINPROGRESS) {
        close();
        return fail(diagnostic, "naive_tcp_connect_failed");
    }
    tcpConnecting = true;
    tunnel = zsg_naive_open_tunnel(client, config.targetHost.c_str(), config.targetPort);  // queued until ready
    if (tunnel == 0) {
        close();
        return fail(diagnostic, "naive_open_tunnel_failed");
    }
    return true;
}

void Socket::close() {
    if (socketFd >= 0) {
        ::close(socketFd);
        socketFd = -1;
    }
    if (client != nullptr) {
        zsg_naive_free(client);
        client = nullptr;
    }
    tunnel = 0;
    tcpConnecting = false;
    closed = true;
    pending.clear();
    pendingOffset = 0;
}

bool Socket::isReady() const {
    return !closed && client != nullptr && tunnel != 0 && zsg_naive_tunnel_state(client, tunnel) == ZSG_TUNNEL_OPEN;
}

bool Socket::flushSocket(std::string *diagnostic) {
    while (pendingOffset < pending.size()) {
        const ssize_t n = send(socketFd, pending.data() + pendingOffset, pending.size() - pendingOffset, MSG_NOSIGNAL);
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
        return fail(diagnostic, "naive_socket_write_failed");
    }
    pending.clear();
    pendingOffset = 0;
    return true;
}

bool Socket::pump(std::string *diagnostic) {
    uint8_t buf[kReadChunk];
    size_t n;
    while ((n = zsg_naive_take_network(client, buf, sizeof(buf))) > 0) {
        pending.insert(pending.end(), buf, buf + n);
        if (pending.size() - pendingOffset > kMaxPendingBytes) {
            return fail(diagnostic, "naive_output_overflow");
        }
    }
    if (!tcpConnecting && !flushSocket(diagnostic)) {
        return false;
    }
    const int state = zsg_naive_state(client);
    if (state == ZSG_NAIVE_FAILED || state == ZSG_NAIVE_CLOSED) {
        return fail(diagnostic, state == ZSG_NAIVE_FAILED ? zsg_naive_error(client) : "naive_closed");
    }
    return true;
}

void Socket::drainTunnel(std::vector<std::vector<uint8_t>> &payloads) {
    uint8_t buf[kReadChunk];
    size_t n;
    while ((n = zsg_naive_tunnel_read(client, tunnel, buf, sizeof(buf))) > 0) {
        payloads.emplace_back(buf, buf + n);
        bytesIn += n;
        phase = transport::HandshakePhase::FirstDataReceived;
    }
}

bool Socket::onEvent(uint32_t events, std::vector<std::vector<uint8_t>> &payloads, std::string *diagnostic) {
    if (closed || client == nullptr) {
        return false;
    }
    if (events & (EPOLLERR | EPOLLHUP)) {
        // A peer close can still carry data: fall through to read it, fail only if reading fails.
        int soError = 0;
        socklen_t len = sizeof(soError);
        getsockopt(socketFd, SOL_SOCKET, SO_ERROR, &soError, &len);
        if (soError != 0) {
            return fail(diagnostic, tcpConnecting ? "naive_tcp_connect_error" : "naive_socket_error");
        }
    }
    if (tcpConnecting && (events & (EPOLLOUT | EPOLLIN))) {
        int soError = 0;
        socklen_t len = sizeof(soError);
        getsockopt(socketFd, SOL_SOCKET, SO_ERROR, &soError, &len);
        if (soError != 0) {
            return fail(diagnostic, "naive_tcp_connect_error");
        }
        tcpConnecting = false;
        phase = transport::HandshakePhase::TcpConnected;
    }
    if (tcpConnecting) {
        return true;
    }
    if (events & EPOLLIN) {
        uint8_t buf[kReadChunk];
        for (;;) {
            const ssize_t n = recv(socketFd, buf, sizeof(buf), 0);
            if (n > 0) {
                zsg_naive_feed_network(client, buf, static_cast<size_t>(n));
                continue;
            }
            if (n == 0) {  // orderly close: deliver what we have, then report the end
                drainTunnel(payloads);
                return fail(diagnostic, "naive_peer_closed");
            }
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                break;
            }
            return fail(diagnostic, "naive_socket_read_failed");
        }
    }
    if (!pump(diagnostic)) {
        drainTunnel(payloads);
        return false;
    }
    if (zsg_naive_state(client) >= ZSG_NAIVE_PREAMBLE && phase < transport::HandshakePhase::TlsReady) {
        phase = transport::HandshakePhase::TlsReady;
    }
    const int tunnelState = zsg_naive_tunnel_state(client, tunnel);
    if (tunnelState == ZSG_TUNNEL_FAILED) {
        return fail(diagnostic, "naive_tunnel_refused");  // e.g. 407 bad credentials, 502 target unreachable
    }
    drainTunnel(payloads);
    if (tunnelState == ZSG_TUNNEL_OPEN && zsg_naive_tunnel_eof(client, tunnel)) {
        return fail(diagnostic, "naive_tunnel_closed");
    }
    return true;
}

bool Socket::write(const uint8_t *data, uint32_t size, std::string *diagnostic) {
    if (!isReady()) {
        return fail(diagnostic, "naive_write_not_ready");
    }
    if (zsg_naive_tunnel_write(client, tunnel, data, size) != size) {
        return fail(diagnostic, "naive_tunnel_write_failed");
    }
    return pump(diagnostic);
}

wss::Route RouteFor(const std::string &proxyHost, uint16_t proxyPort) {
    wss::Route route;
    route.relayHost = proxyHost;
    route.connectHost = proxyHost;
    route.relayPort = proxyPort;
    route.domain = proxyHost;
    route.healthDomain = proxyHost;
    return route;
}

std::unique_ptr<transport::Socket> CreateFor(const std::string &proxyHost, uint16_t proxyPort, const std::string &user,
                                             const std::string &password, const std::string &dcAddress, uint16_t dcPort) {
    Config config;
    config.proxyHost = proxyHost;
    config.proxyPort = proxyPort;
    config.user = user;
    config.password = password;
    config.targetHost = dcAddress;
    config.targetPort = dcPort;
    // Android 14 moved the system roots into the conscrypt APEX; older releases keep them in /system.
    config.caDir = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts";
    return CreateSocket(std::move(config));
}

std::unique_ptr<transport::Socket> CreateSocket(Config config) {
    return std::unique_ptr<transport::Socket>(new Socket(std::move(config)));
}

} // namespace naive
} // namespace tgnet
