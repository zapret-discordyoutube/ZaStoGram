/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 */

#ifndef TGNET_WSS_SOCKET_H
#define TGNET_WSS_SOCKET_H

#include "../transport/TransportSocket.h"

#include <openssl/ssl.h>
#include <deque>
#include <memory>
#include <string>
#include <vector>

namespace tgnet {
namespace wss {

struct Route {
    std::string relayHost;
    std::string relayHostFallback;
    std::string connectHost;
    uint16_t relayPort = 443;
    std::string domain;
    std::string path = "/apiws";
    bool viaFallback = false;
    // The Worker tunnel reaches the DC over plain TCP, where bytes 60..61 of
    // the obfuscation header must name the DC and traffic class.
    bool tunnel = false;
};

// Telegram's public web relays cover production DC1-DC5. Media connections
// use the corresponding -1 relay, matching Telegram Web's transport catalog.
// While a DC's relay is suppressed as unreachable, the route switches to the
// ZaStoGram Cloudflare Worker, which opens dcAddress (IPv4) over TCP itself.
bool OfficialRoute(int32_t dcId, bool mediaConnection, bool testBackend, const std::string &dcAddress, Route *route);

// Whether OfficialRoute would still hand out this exact route: not suppressed
// and not switched to the relay's DNS name.
bool RouteUsable(const Route &route);

// Whether OfficialRoute would carry this DC through the Cloudflare tunnel now.
bool DatacenterTunneled(int32_t dcId, bool mediaConnection, bool testBackend);

// Keeps relay suppression across launches in this file (read once).
void SetRouteHealthPath(const std::string &path);

class Socket final : public transport::Socket {
public:
    explicit Socket(Route route);
    ~Socket() override;

    bool open(const struct sockaddr *address, socklen_t addressLength, std::string *diagnostic) override;
    int fd() const override;
    bool onEvent(uint32_t events, std::vector<std::vector<uint8_t>> &payloads, std::string *diagnostic) override;
    bool write(const uint8_t *data, uint32_t size, std::string *diagnostic) override;
    size_t queuedOutputBytes() const override;
    bool isReady() const override;
    bool wantsWrite() const override;
    bool canWriteApplicationData() const override;
    bool isClosed() const override;
    transport::HandshakePhase handshakePhase() const override;
    const char *transportName() const override;
    void timedOut() override;
    void noteAppDataTimeout() override;
    std::string takeSessionSummary() override;
    uint64_t receivedBytes() const override;
    void close() override;

    const Route &route() const;

    // A pool spare nobody is waiting for: its failures say too little about
    // the relay to move real connections to another address or the tunnel.
    void setSpeculative(bool value);

private:
    enum class State : uint8_t {
        TcpConnecting,
        TlsHandshake,
        HttpWrite,
        HttpRead,
        Ready,
        Closed,
    };

    enum class IoWait : uint8_t {
        None,
        Read,
        Write,
    };

    bool finishTcpConnect(std::string *diagnostic);
    bool startTls(std::string *diagnostic);
    bool pumpTls(std::string *diagnostic);
    bool queueHttpUpgrade(std::string *diagnostic);
    bool flushPending(std::string *diagnostic);
    bool readIntoBuffer(std::string *diagnostic);
    bool parseHttpResponse(std::string *diagnostic);
    bool parseFrames(std::vector<std::vector<uint8_t>> &payloads, std::string *diagnostic);
    bool queueFrame(uint8_t opcode, const uint8_t *data, uint32_t size, std::string *diagnostic);
    void setIoWait(IoWait wait, const char *operation);
    bool writesWaitForRead() const;
    void noteAttemptFailed();
    void noteUpgradeSucceeded();
    const char *stateName() const;
    const char *ioWaitName() const;
    Route routeConfig;
    // Numeric address this socket dialled, to tell a dropped flow from a dead relay.
    std::string peerAddress;
    SSL *ssl = nullptr;
    int socketFd = -1;
    State state = State::Closed;
    IoWait ioWait = IoWait::None;
    transport::HandshakePhase phase = transport::HandshakePhase::None;
    // Keep every TLS write retry in a stable, immutable allocation. OpenSSL
    // requires the same buffer and length after WANT_READ/WANT_WRITE; a single
    // appendable vector violated that contract under bursty uploads.
    std::deque<std::vector<uint8_t>> pendingOutput;
    size_t pendingOutputOffset = 0;
    size_t pendingOutputBytes = 0;
    std::vector<uint8_t> inputBuffer;
    std::string secWebSocketKey;
    // The relay parses the 64-byte obfuscation header out of the payload of the
    // FIRST binary frame alone — not out of the reassembled message and not out
    // of the TCP stream. A shorter first frame is fatal and, worse, silent: the
    // relay simply never answers. Hold the opening bytes back until the header
    // is complete so no caller chunking can violate that.
    std::vector<uint8_t> openingFrame;
    bool openingFrameSent = false;
    bool fragmentedMessage = false;
    bool failureRecorded = false;
    bool speculative = false;
    bool fromPool = false;
    // For the wss_session summary logged on close.
    int64_t openedAtMs = 0;
    int64_t readyAtMs = 0;
    int64_t firstDataAtMs = 0;
    uint64_t bytesOut = 0;
    uint64_t bytesIn = 0;
    bool summaryTaken = false;
    bool reachableRecorded = false;
    // SSL_write returned WANT_READ: the record can only continue after input.
    bool writeBlockedOnRead = false;
};

std::unique_ptr<transport::Socket> CreateSocket(Route route);

} // namespace wss
} // namespace tgnet

#endif
