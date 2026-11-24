/*
 * ZaStoGram Naive transport: an MTProto byte stream carried through a NaiveProxy-compatible server.
 *
 * Fork-only module, kept in this folder so upstream merges never touch it. It implements the transport::Socket
 * interface that tgnet already uses for WebSocket relays; the TLS/HTTP2/Naive logic lives in the separate
 * sans-I/O library libzsgnaive (own BoringSSL, Chrome 158 handshake), reached only through its C API.
 */
#ifndef ZSG_NAIVE_SOCKET_H
#define ZSG_NAIVE_SOCKET_H

#include <stdint.h>
#include <memory>
#include <string>
#include <vector>

#include "../tgnet/transport/TransportSocket.h"

struct zsg_naive;  // opaque handle of libzsgnaive (global scope, as declared by zsg_naive.h)

namespace tgnet {
namespace naive {

struct Config {
    std::string proxyHost;            // Naive server: SNI and :authority
    uint16_t proxyPort = 443;
    std::string user;
    std::string password;
    std::string targetHost;           // the Telegram DC address the tunnel connects to
    uint16_t targetPort = 443;
    std::string caDir = "/system/etc/security/cacerts";
    bool insecureSkipVerify = false;  // lab only
    // Browser identity of the preamble request; must match the TLS profile (Chrome 158, Android).
    std::string userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Mobile Safari/537.36";
    std::string secChUa = "\"Chromium\";v=\"158\", \"Not.A/Brand\";v=\"24\", \"Google Chrome\";v=\"158\"";
    std::string secChUaMobile = "?1";
    std::string secChUaPlatform = "\"Android\"";
};

class Socket final : public transport::Socket {
public:
    explicit Socket(Config config);
    ~Socket() override;

    // |address| is the proxy server's resolved address (the caller resolves DNS).
    bool open(const struct sockaddr *address, socklen_t addressLength, std::string *diagnostic) override;
    int fd() const override { return socketFd; }
    bool onEvent(uint32_t events, std::vector<std::vector<uint8_t>> &payloads, std::string *diagnostic) override;
    bool write(const uint8_t *data, uint32_t size, std::string *diagnostic) override;
    size_t queuedOutputBytes() const override { return pending.size() - pendingOffset; }
    bool isReady() const override;
    bool wantsWrite() const override { return tcpConnecting || pendingOffset < pending.size(); }
    bool canWriteApplicationData() const override { return isReady(); }
    bool isClosed() const override { return closed; }
    transport::HandshakePhase handshakePhase() const override { return phase; }
    const char *transportName() const override { return "naive"; }
    void timedOut() override { close(); }
    uint64_t receivedBytes() const override { return bytesIn; }
    void close() override;

private:
    bool pump(std::string *diagnostic);           // client -> socket
    bool flushSocket(std::string *diagnostic);    // pending -> kernel
    void drainTunnel(std::vector<std::vector<uint8_t>> &payloads);
    bool fail(std::string *diagnostic, const char *why);

    Config config;
    ::zsg_naive *client = nullptr;
    int tunnel = 0;
    int socketFd = -1;
    bool tcpConnecting = false;
    bool closed = true;
    transport::HandshakePhase phase = transport::HandshakePhase::None;
    std::vector<uint8_t> pending;   // network bytes produced by the client, not yet written
    size_t pendingOffset = 0;
    uint64_t bytesIn = 0;
};

std::unique_ptr<transport::Socket> CreateSocket(Config config);

} // namespace naive
} // namespace tgnet

#endif
