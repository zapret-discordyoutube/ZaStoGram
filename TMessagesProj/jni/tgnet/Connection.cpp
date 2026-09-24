/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#include <openssl/rand.h>
#include <stdlib.h>
#include <cstring>
#include <openssl/sha.h>
#include <algorithm>
#include <atomic>
#include "Connection.h"
#include "ConnectionsManager.h"
#include "BuffersStorage.h"
#include "FileLog.h"
#include "Timer.h"
#include "Datacenter.h"
#include "NativeByteBuffer.h"
#include "ByteArray.h"
#include "mtproxy/MtProxyHandshakeScheduler.h"
#include "mtproxy/MtProxyPhaseContract.h"
#include "mtproxy/MtProxyRetryAuthority.h"

thread_local static uint32_t lastConnectionToken = 1;

namespace NetworkDebugCounters {
std::atomic<uint64_t> partialPackets{0};
std::atomic<uint64_t> partialPacketBytes{0};

static void recordPartialPacket(uint32_t readBytes) {
    NetworkDebugCounters::partialPackets.fetch_add(1, std::memory_order_relaxed);
    NetworkDebugCounters::partialPacketBytes.fetch_add(readBytes, std::memory_order_relaxed);
}

static bool verboseNetworkDebugEnabled() {
    return NETWORK_DEBUG_LOGS_ENABLED;
}
}

static MtProxyRequestClass mtProxyRequestClassForConnectionType(ConnectionType type) {
    switch ((int32_t) type & 0x0000ffff) {
        case ConnectionTypeGeneric:
        case ConnectionTypeTemp:
            return MtProxyRequestClass::Generic;
        case ConnectionTypeGenericMedia:
            return MtProxyRequestClass::Media;
        case ConnectionTypePush:
            return MtProxyRequestClass::Push;
        case ConnectionTypeDownload:
            return MtProxyRequestClass::Download;
        case ConnectionTypeUpload:
            return MtProxyRequestClass::Upload;
        case ConnectionTypeProxy:
            return MtProxyRequestClass::ProxyCheck;
        default:
            return MtProxyRequestClass::ProxyCheck;
    }
}

static bool mtProxyDiagnosticNeedsReconnectBackoff(const char *diagnostic) {
    // The phase set is generated from Tools/mtproxy_phase_contract.py
    // (reconnect_backoff=True) into MtProxyPhaseClassification.h.
    return MtProxyPhase::needsReconnectBackoff(diagnostic);
}

static MtProxyRetry::TrafficClass mtProxyTrafficClassFor(ConnectionType type) {
    // Backoff base/max per class live in mtproxy/MtProxyRetryAuthority.cpp,
    // the single owner of retry-hold computation.
    switch ((int32_t) type & 0x0000ffff) {
        case ConnectionTypeGeneric:
        case ConnectionTypeTemp:
            return MtProxyRetry::TrafficClass::Generic;
        case ConnectionTypeGenericMedia:
            return MtProxyRetry::TrafficClass::GenericMedia;
        case ConnectionTypePush:
            return MtProxyRetry::TrafficClass::Push;
        case ConnectionTypeDownload:
            return MtProxyRetry::TrafficClass::Download;
        case ConnectionTypeUpload:
            return MtProxyRetry::TrafficClass::Upload;
        default:
            return MtProxyRetry::TrafficClass::Other;
    }
}

std::string Connection::proxyConnectionStageOrigin() {
    return ((int32_t) connectionType & 0x0000ffff) == ConnectionTypeProxy ? "proxy_check" : "active_socket";
}

std::string Connection::proxyConnectionStageSocketRole() {
    switch ((int32_t) connectionType & 0x0000ffff) {
        case ConnectionTypeGeneric:
            return "control_main";
        case ConnectionTypeTemp:
            return "control_secondary";
        case ConnectionTypeGenericMedia:
        case ConnectionTypeDownload:
        case ConnectionTypeUpload:
            return "media_visible";
        case ConnectionTypePush:
            return "background_keepalive";
        case ConnectionTypeProxy:
            return "proxy_check";
        default:
            return "proxy_check";
    }
}

Connection::Connection(Datacenter *datacenter, ConnectionType type, int8_t num) : ConnectionSession(datacenter->instanceNum), ConnectionSocket(datacenter->instanceNum) {
    currentDatacenter = datacenter;
    connectionNum = num;
    connectionType = type;
    genereateNewSessionId();
    connectionState = TcpConnectionStageIdle;
    reconnectTimer = new Timer(datacenter->instanceNum, [&] {
        reconnectTimer->stop();
        waitForReconnectTimer = false;
        connect();
    });
}

Connection::~Connection() {
    if (reconnectTimer != nullptr) {
        reconnectTimer->stop();
        delete reconnectTimer;
        reconnectTimer = nullptr;
    }
}

void Connection::suspendConnection() {
    suspendConnection(false);
}

void Connection::suspendConnection(bool idle) {
    reconnectTimer->stop();
    waitForReconnectTimer = false;
    mtProxyReconnectBackoffMs = 0;
    mtProxyReconnectHoldUntil = 0;
    if (connectionState == TcpConnectionStageIdle || connectionState == TcpConnectionStageSuspended) {
        return;
    }
    if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) suspend", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType);
    connectionState = idle ? TcpConnectionStageIdle : TcpConnectionStageSuspended;
    dropConnection();
    notifyConnectionClosedOnce(0, "suspendConnection");
    generation++;
    firstPacketSent = false;
    if (restOfTheData != nullptr) {
        restOfTheData->reuse();
        restOfTheData = nullptr;
    }
    lastPacketLength = 0;
    connectionToken = 0;
    wasConnected = false;
}

void Connection::onReceivedData(NativeByteBuffer *buffer) {
    AES_ctr128_encrypt(buffer->bytes(), buffer->bytes(), buffer->limit(), &decryptKey, decryptIv, decryptCount, &decryptNum);

    failedConnectionCount = 0;
    mtProxyReconnectBackoffMs = 0;
    mtProxyReconnectHoldUntil = 0;

    if (connectionType == ConnectionTypeGeneric || connectionType == ConnectionTypeTemp || connectionType == ConnectionTypeGenericMedia) {
        receivedDataAmount += buffer->limit();
        if (receivedDataAmount >= 512 * 1024) {
            if (currentTimeout > 4) {
                currentTimeout -= 2;
                setTimeout(currentTimeout);
            }
            receivedDataAmount = 0;
        }
    }

    NativeByteBuffer *parseLaterBuffer = nullptr;
    if (restOfTheData != nullptr) {
        if (lastPacketLength == 0) {
            if (restOfTheData->capacity() - restOfTheData->position() >= buffer->limit()) {
                restOfTheData->limit(restOfTheData->position() + buffer->limit());
                restOfTheData->writeBytes(buffer);
                buffer = restOfTheData;
            } else {
                NativeByteBuffer *newBuffer = BuffersStorage::getInstance().getFreeBuffer(restOfTheData->limit() + buffer->limit());
                restOfTheData->rewind();
                newBuffer->writeBytes(restOfTheData);
                newBuffer->writeBytes(buffer);
                buffer = newBuffer;
                restOfTheData->reuse();
                restOfTheData = newBuffer;
            }
        } else {
            uint32_t len;
            if (lastPacketLength - restOfTheData->position() <= buffer->limit()) {
                len = lastPacketLength - restOfTheData->position();
            } else {
                len = buffer->limit();
            }
            uint32_t oldLimit = buffer->limit();
            buffer->limit(len);
            restOfTheData->writeBytes(buffer);
            buffer->limit(oldLimit);
            if (restOfTheData->position() == lastPacketLength) {
                parseLaterBuffer = buffer->hasRemaining() ? buffer : nullptr;
                buffer = restOfTheData;
            } else {
                return;
            }
        }
    }

    buffer->rewind();

    NativeByteBuffer *reuseLater = nullptr;
    while (buffer->hasRemaining()) {
        if (!hasSomeDataSinceLastConnect) {
            currentDatacenter->storeCurrentAddressAndPortNum();
            isTryingNextPort = false;
            if (connectionType == ConnectionTypeProxy) {
                setTimeout(5);
            } else if (connectionType == ConnectionTypePush) {
                setTimeout(60 * 15);
            } else if (connectionType == ConnectionTypeUpload) {
                if (ConnectionsManager::getInstance(currentDatacenter->instanceNum).networkSlow) {
                    setTimeout(40);
                } else {
                    setTimeout(25);
                }
            } else if (connectionType == ConnectionTypeDownload) {
                setTimeout(25);
            } else {
                setTimeout(currentTimeout);
            }
        }
        hasSomeDataSinceLastConnect = true;

        uint32_t currentPacketLength = 0;
        uint32_t mark = buffer->position();
        uint32_t len;

        if (currentProtocolType == ProtocolTypeEF) {
            uint8_t fByte = buffer->readByte(nullptr);

            if ((fByte & (1 << 7)) != 0) {
                buffer->position(mark);
                if (buffer->remaining() < 4) {
                    reuseLater = restOfTheData;
                    restOfTheData = BuffersStorage::getInstance().getFreeBuffer(16384);
                    restOfTheData->writeBytes(buffer);
                    restOfTheData->limit(restOfTheData->position());
                    lastPacketLength = 0;
                    break;
                }
                int32_t ackId = buffer->readBigInt32(nullptr) & (~(1 << 31));
                ConnectionsManager::getInstance(currentDatacenter->instanceNum).onConnectionQuickAckReceived(this, ackId);
                continue;
            }

            if (fByte != 0x7f) {
                currentPacketLength = ((uint32_t) fByte) * 4;
            } else {
                buffer->position(mark);
                if (buffer->remaining() < 4) {
                    if (restOfTheData == nullptr || (restOfTheData != nullptr && restOfTheData->position() != 0)) {
                        reuseLater = restOfTheData;
                        restOfTheData = BuffersStorage::getInstance().getFreeBuffer(16384);
                        restOfTheData->writeBytes(buffer);
                        restOfTheData->limit(restOfTheData->position());
                        lastPacketLength = 0;
                    } else {
                        restOfTheData->position(restOfTheData->limit());
                    }
                    break;
                }
                currentPacketLength = ((uint32_t) buffer->readInt32(nullptr) >> 8) * 4;
            }

            len = currentPacketLength + (fByte != 0x7f ? 1 : 4);
        } else {
            if (buffer->remaining() < 4) {
                if (restOfTheData == nullptr || (restOfTheData != nullptr && restOfTheData->position() != 0)) {
                    reuseLater = restOfTheData;
                    restOfTheData = BuffersStorage::getInstance().getFreeBuffer(16384);
                    restOfTheData->writeBytes(buffer);
                    restOfTheData->limit(restOfTheData->position());
                    lastPacketLength = 0;
                } else {
                    restOfTheData->position(restOfTheData->limit());
                }
                break;
            }

            uint32_t fInt = buffer->readUint32(nullptr);

            if ((fInt & (0x80000000)) != 0) {
                ConnectionsManager::getInstance(currentDatacenter->instanceNum).onConnectionQuickAckReceived(this, fInt & (~(1 << 31)));
                continue;
            }

            currentPacketLength = fInt;
            len = currentPacketLength + 4;
        }

        if (currentProtocolType != ProtocolTypeDD && currentProtocolType != ProtocolTypeTLS && currentPacketLength % 4 != 0 || currentPacketLength > 2 * 1024 * 1024) {
            if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) received invalid packet length", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType);
            reconnect();
            break;
        }

        if (currentPacketLength < buffer->remaining()) {
            if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) received message len %u but packet larger %u", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, currentPacketLength, buffer->remaining());
        } else if (currentPacketLength == buffer->remaining()) {
            if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) received message len %u equal to packet size", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, currentPacketLength);
        } else {
            uint32_t assembledBytes = buffer->remaining();
            NetworkDebugCounters::recordPartialPacket(assembledBytes);
            if (LOGS_ENABLED && NetworkDebugCounters::verboseNetworkDebugEnabled()) {
                DEBUG_D("mtproto_partial_packet assembled=%u expected=%u connection=%p account=%u dc=%u type=%d", assembledBytes, currentPacketLength, this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType);
            }

            if (restOfTheData != nullptr && restOfTheData->capacity() < len) {
                reuseLater = restOfTheData;
                restOfTheData = nullptr;
            }
            if (restOfTheData == nullptr) {
                buffer->position(mark);
                restOfTheData = BuffersStorage::getInstance().getFreeBuffer(len);
                restOfTheData->writeBytes(buffer);
            } else {
                restOfTheData->position(restOfTheData->limit());
                restOfTheData->limit(len);
            }
            lastPacketLength = len;
            break;
        }

        uint32_t old = buffer->limit();
        buffer->limit(buffer->position() + currentPacketLength);
        uint32_t current_generation = generation;
        ConnectionsManager::getInstance(currentDatacenter->instanceNum).onConnectionDataReceived(this, buffer, currentPacketLength);
        if (current_generation != generation) {
          break;
        }
        buffer->position(buffer->limit());
        buffer->limit(old);

        if (restOfTheData != nullptr) {
            if ((lastPacketLength != 0 && restOfTheData->position() == lastPacketLength) || (lastPacketLength == 0 && !restOfTheData->hasRemaining())) {
                reuseLater = restOfTheData;
                restOfTheData = nullptr;
            } else {
                restOfTheData->compact();
                restOfTheData->limit(restOfTheData->position());
                restOfTheData->position(0);
            }
        }

        if (parseLaterBuffer != nullptr) {
            buffer = parseLaterBuffer;
            parseLaterBuffer = nullptr;
        }
    }
    if (reuseLater != nullptr) {
        reuseLater->reuse();
    }
}

void Connection::connect() {
    if (waitForReconnectTimer) {
        return;
    }
    connectionCloseNotified = false;
    if (!ConnectionsManager::getInstance(currentDatacenter->instanceNum).isNetworkAvailable()) {
        notifyConnectionClosedOnce(0, "network_unavailable_connect");
        return;
    }
    if (connectionState == TcpConnectionStageConnected || connectionState == TcpConnectionStageConnecting) {
        return;
    }
    int64_t now = ConnectionsManager::getInstance(currentDatacenter->instanceNum).getCurrentTimeMonotonicMillis();
    const bool mtProxyRouteActive = isMtProxyRouteActive();
    const bool mtProxyReconnectPacing = isMtProxyReconnectPacingActive();
    if (mtProxyReconnectPacing && connectionType != ConnectionTypeProxy && mtProxyReconnectHoldUntil > now) {
        uint32_t delay = (uint32_t) (mtProxyReconnectHoldUntil - now);
        waitForReconnectTimer = true;
        reconnectTimer->setTimeout(delay, false);
        reconnectTimer->start();
        if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) mtproxy_startup reconnect_hold phase=%s delay_ms=%u", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, getProxyCheckDiagnostic(), delay);
        return;
    } else if (mtProxyReconnectHoldUntil != 0) {
        mtProxyReconnectHoldUntil = 0;
        mtProxyReconnectBackoffMs = 0;
    }
    connectionInProcess = true;
    connectionState = TcpConnectionStageConnecting;
    isMediaConnection = false;
    uint8_t strategy = ConnectionsManager::getInstance(currentDatacenter->instanceNum).getIpStratagy();
    uint32_t ipv6;
    if (strategy == USE_IPV6_ONLY) {
        ipv6 = TcpAddressFlagIpv6;
    } else if (strategy == USE_IPV4_IPV6_RANDOM) {
        if (ConnectionsManager::getInstance(currentDatacenter->instanceNum).lastProtocolUsefullData) {
            ipv6 = ConnectionsManager::getInstance(currentDatacenter->instanceNum).lastProtocolIsIpv6 ? TcpAddressFlagIpv6 : 0;
        } else {
            uint8_t value;
            RAND_bytes(&value, 1);
            ipv6 = value % 3 == 0 ? TcpAddressFlagIpv6 : 0;
            ConnectionsManager::getInstance(currentDatacenter->instanceNum).lastProtocolIsIpv6 = ipv6 != 0;
        }
        if (connectionType == ConnectionTypeGeneric) {
            ConnectionsManager::getInstance(currentDatacenter->instanceNum).lastProtocolUsefullData = false;
        }
    } else {
        ipv6 = 0;
    }
    uint32_t isStatic = connectionType == ConnectionTypeProxy || !ConnectionsManager::getInstance(currentDatacenter->instanceNum).proxyAddress.empty() ? TcpAddressFlagStatic : 0;
    TcpAddress *tcpAddress = nullptr;
    if (isMediaConnectionType(connectionType)) {
        currentAddressFlags = TcpAddressFlagDownload | isStatic;
        tcpAddress = currentDatacenter->getCurrentAddress(currentAddressFlags | ipv6);
        if (tcpAddress == nullptr) {
            currentAddressFlags = isStatic;
            tcpAddress = currentDatacenter->getCurrentAddress(currentAddressFlags | ipv6);
        } else {
            isMediaConnection = true;
        }
        if (tcpAddress == nullptr && ipv6) {
            ipv6 = 0;
            currentAddressFlags = TcpAddressFlagDownload | isStatic;
            tcpAddress = currentDatacenter->getCurrentAddress(currentAddressFlags);
            if (tcpAddress == nullptr) {
                currentAddressFlags = isStatic;
                tcpAddress = currentDatacenter->getCurrentAddress(currentAddressFlags);
            } else {
                isMediaConnection = true;
            }
        }
        // Through an MTProxy-style route (a real MTProxy or the WEB bridge)
        // the address above is never dialled: the relay picks the cluster
        // from the DC id in the obfuscated header, whose sign follows
        // isMediaConnection. The key this connection signs with is chosen by
        // Datacenter::getAuthKey from hasMediaAddress(), which looks at the
        // IPv4 list (IPv6 only for USE_IPV6_ONLY). Deciding media-ness from
        // the address list of a random IPv6 pick instead would send the media
        // temp key to the regular cluster (or the reverse): -404 right after
        // every new key, and a key re-creation loop. Keep both on one rule.
        if (isStatic != 0 && !ConnectionsManager::getInstance(currentDatacenter->instanceNum).proxyAddress.empty()
                && !ConnectionsManager::getInstance(currentDatacenter->instanceNum).proxySecret.empty()) {
            isMediaConnection = currentDatacenter->hasMediaAddress();
        }
    } else if (connectionType == ConnectionTypeTemp) {
        currentAddressFlags = TcpAddressFlagTemp;
        tcpAddress = currentDatacenter->getCurrentAddress(currentAddressFlags);
        ipv6 = 0;
    } else {
        currentAddressFlags = isStatic;
        tcpAddress = currentDatacenter->getCurrentAddress(currentAddressFlags | ipv6);
        if (tcpAddress == nullptr && ipv6) {
            ipv6 = 0;
            tcpAddress = currentDatacenter->getCurrentAddress(currentAddressFlags);
        }
    }
    if (tcpAddress == nullptr) {
        hostAddress = "";
    } else {
        hostAddress = tcpAddress->address;
        secret = tcpAddress->secret;
    }
    if (tcpAddress != nullptr && isStatic) {
        hostPort = (uint16_t) tcpAddress->port;
    } else {
        hostPort = (uint16_t) currentDatacenter->getCurrentPort(currentAddressFlags);
    }

    reconnectTimer->stop();

    if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) connecting (%s:%hu)", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, hostAddress.c_str(), hostPort);

    generation++;
    firstPacketSent = false;
    if (restOfTheData != nullptr) {
        restOfTheData->reuse();
        restOfTheData = nullptr;
    }
    lastPacketLength = 0;
    wasConnected = false;
    hasSomeDataSinceLastConnect = false;
    if (mtProxyRouteActive) {
        int32_t mtProxyHandshakePriority = mtProxyHandshakePriorityForRequestClass(mtProxyRequestClassForConnectionType(connectionType));
        if (((int32_t) connectionType & 0x0000ffff) == ConnectionTypeProxy) {
            mtProxyHandshakePriority = MT_PROXY_HANDSHAKE_PRIORITY_BYPASS;
        }
        setMtProxyHandshakePriority(mtProxyHandshakePriority);
    }
    // Class of this connection's stream on a WEB proxy carrier; unused on
    // every other route (see ConnectionSocket::announceWebProxyStream).
    const int32_t baseConnectionType = (int32_t) connectionType & 0x0000ffff;
    setWebProxyStreamClass(baseConnectionType == ConnectionTypeDownload
            ? WEB_PROXY_STREAM_CLASS_DOWNLOAD
            : baseConnectionType == ConnectionTypeUpload
            ? WEB_PROXY_STREAM_CLASS_UPLOAD
            : WEB_PROXY_STREAM_CLASS_INTERACTIVE);
    // The WSS hostname must follow the authorization realm, not the amount or
    // direction of file traffic. Upload connections use the regular temp key
    // and therefore belong on kwsN; only connections that actually selected a
    // media address/key belong on kwsN-1. Routing uploads by their file-lane
    // classification makes the media relay reject the regular key with -404.
    openConnection(hostAddress, hostPort, secret, ipv6 != 0, ConnectionsManager::getInstance(currentDatacenter->instanceNum).currentNetworkType, currentDatacenter->getDatacenterId(), isMediaConnection);
    if (connectionType == ConnectionTypeProxy) {
        setTimeout(5);
    } else if (connectionType == ConnectionTypePush) {
        if (isTryingNextPort) {
            setTimeout(20);
        } else {
            setTimeout(30);
        }
    } else if (connectionType == ConnectionTypeUpload) {
        if (ConnectionsManager::getInstance(currentDatacenter->instanceNum).networkSlow) {
            setTimeout(40);
        } else {
            setTimeout(25);
        }
    } else {
        if (isTryingNextPort) {
            setTimeout(8);
        } else {
            setTimeout(12);
        }
    }
    connectionInProcess = false;
}

void Connection::reconnect() {
    if (connectionType == ConnectionTypeProxy) {
        suspendConnection(false);
    } else {
        forceNextPort = true;
        suspendConnection(true);
        connect();
    }
}

bool Connection::hasUsefullData() {
    int64_t time = ConnectionsManager::getInstance(currentDatacenter->instanceNum).getCurrentTimeMonotonicMillis();
    if (usefullData && llabs(time - usefullDataReceiveTime) < 4 * 1000L) {
        return false;
    }
    return usefullData;
}

bool Connection::isSuspended() {
    return connectionState == TcpConnectionStageSuspended;
}

bool Connection::isMediaConnectionType(ConnectionType type) {
    return (type & ConnectionTypeGenericMedia) != 0 || (type & ConnectionTypeDownload) != 0;
}

void Connection::setHasUsefullData() {
    if (!usefullData) {
        usefullDataReceiveTime = ConnectionsManager::getInstance(currentDatacenter->instanceNum).getCurrentTimeMonotonicMillis();
        usefullData = true;
        lastReconnectTimeout = 50;
    }
}

bool Connection::allowsCustomPadding() {
    return currentProtocolType == ProtocolTypeTLS || currentProtocolType == ProtocolTypeDD || currentProtocolType == ProtocolTypeEF;
}

bool Connection::isMtProxyRouteActive() const {
    if (((int32_t) connectionType & 0x0000ffff) == ConnectionTypeProxy) {
        return hasMtProxyOverride();
    }
    const ConnectionsManager &manager = ConnectionsManager::getInstance(currentDatacenter->instanceNum);
    return !manager.proxyAddress.empty() && !manager.proxySecret.empty();
}

bool Connection::isMtProxyReconnectPacingActive() const {
    if (!isMtProxyRouteActive()) {
        return false;
    }
    // The WEB proxy reaches tgnet as a plain MTProxy on a loopback bridge into
    // the WebView carrier. Reconnect holds exist to spare a remote relay under
    // DPI; on loopback they only delay recovery, so the bridge keeps the plain
    // tgnet reconnect timer like any non-MTProxy route.
    if (((int32_t) connectionType & 0x0000ffff) == ConnectionTypeProxy) {
        return !overrideMtProxyOptions.webBridge;
    }
    return !ConnectionsManager::getInstance(currentDatacenter->instanceNum).proxyMtProxyOptions.webBridge;
}

bool Connection::canSendRequestData(const char *reason) {
    // Match tdesktop's transport boundary: MTProxy policy exists only while an
    // MTProxy route is selected. Direct tgnet keeps its original send path and
    // must never inherit proxy write gates or closing-state bookkeeping.
    if (!isMtProxyRouteActive()) {
        if (connectionState == TcpConnectionStageIdle
                || connectionState == TcpConnectionStageReconnecting
                || connectionState == TcpConnectionStageSuspended) {
            connect();
        }
        return !isDisconnected();
    }
    const char *safeReason = reason != nullptr ? reason : "unknown";
    if (connectionState == TcpConnectionStageSuspended) {
        if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) mtproxy_startup write_gate_closed reason=%s state=%d dead_for_writes=%d", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, safeReason, (int) connectionState, isClosingOrClosedForWrites() ? 1 : 0);
        return false;
    }
    if (connectionState == TcpConnectionStageIdle || connectionState == TcpConnectionStageReconnecting) {
        connect();
    }
    if (isDisconnected() || isClosingOrClosedForWrites()) {
        if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) mtproxy_startup write_gate_disconnected reason=%s", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, safeReason);
        return false;
    }
    return true;
}

bool Connection::sendData(NativeByteBuffer *buff, bool reportAck, bool encrypted) {
    if (buff == nullptr) {
        return false;
    }
    buff->rewind();
    if (!canSendRequestData("sendData")) {
        buff->reuse();
        return false;
    }

    uint32_t bufferLen = 0;
    uint32_t packetLength;

    uint8_t useSecret = 0;
    if (!firstPacketSent) {
        if (isCurrentTransportWss()) {
            // WSS has its own official route and never inherits the secret of
            // the TCP dcOption that happened to be selected before transport
            // dispatch. Otherwise a direct WSS connection is accidentally
            // encoded as MTProxy and media auth fails on every affected DC.
            useSecret = 0;
        } else if (!overrideProxyAddress.empty()) {
            if (!overrideProxySecret.empty()) {
                useSecret = 1;
            } else if (!secret.empty()) {
                useSecret = 2;
            }
        } else if (!ConnectionsManager::getInstance(currentDatacenter->instanceNum).proxyAddress.empty() && !ConnectionsManager::getInstance(currentDatacenter->instanceNum).proxySecret.empty()) {
            useSecret = 1;
        } else if (!secret.empty()) {
            useSecret = 2;
        }
        if (useSecret != 0) {
            std::string *currentSecret = getCurrentSecret(useSecret);
            if (currentSecret->length() >= 17 && (*currentSecret)[0] == '\xdd') {
                currentProtocolType = ProtocolTypeDD;
            } else if (currentSecret->length() > 17 && (*currentSecret)[0] == '\xee') {
                currentProtocolType = ProtocolTypeTLS;
            } else {
                currentProtocolType = ProtocolTypeEF;
            }
        } else {
            currentProtocolType = ProtocolTypeEF;
        }
    }

    uint32_t additinalPacketSize = 0;
    if (currentProtocolType == ProtocolTypeEF) {
        packetLength = buff->limit() / 4;
        if (packetLength < 0x7f) {
            bufferLen++;
        } else {
            bufferLen += 4;
        }
    } else {
        packetLength = buff->limit();
        if (currentProtocolType == ProtocolTypeDD || currentProtocolType == ProtocolTypeTLS) {
            RAND_bytes((uint8_t *) &additinalPacketSize, 4);
            if (!encrypted) {
                additinalPacketSize = additinalPacketSize % 257;
            } else {
                additinalPacketSize = additinalPacketSize % 16;
            }
            packetLength += additinalPacketSize;
        } else {
            RAND_bytes((uint8_t *) &additinalPacketSize, 4);
            if (!encrypted) {
                additinalPacketSize = additinalPacketSize % 257;
                uint32_t additionalSize = additinalPacketSize % 4;
                if (additionalSize != 0) {
                    additinalPacketSize += (4 - additionalSize);
                }
            }
            packetLength += additinalPacketSize;
        }
        bufferLen += 4;
    }

    if (!firstPacketSent) {
        bufferLen += 64;
    }

    NativeByteBuffer *buffer = BuffersStorage::getInstance().getFreeBuffer(bufferLen);
    NativeByteBuffer *buffer2;
    if (additinalPacketSize > 0) {
        buffer2 = BuffersStorage::getInstance().getFreeBuffer(additinalPacketSize);
        RAND_bytes(buffer2->bytes(), additinalPacketSize);
    } else {
        buffer2 = nullptr;
    }
    uint8_t *bytes = buffer->bytes();

    if (!firstPacketSent) {
        buffer->position(64);
        while (true) {
            RAND_bytes(bytes, 64);
            uint32_t val = (bytes[3] << 24) | (bytes[2] << 16) | (bytes[1] << 8) | (bytes[0]);
            uint32_t val2 = (bytes[7] << 24) | (bytes[6] << 16) | (bytes[5] << 8) | (bytes[4]);
            if (currentProtocolType == ProtocolTypeTLS || bytes[0] != 0xef && val != 0x44414548 && val != 0x54534f50 && val != 0x20544547 && val != 0x4954504f && val != 0xeeeeeeee && val != 0xdddddddd && val != 0x02010316 && val2 != 0x00000000) {
                if (currentProtocolType == ProtocolTypeEF) {
                    bytes[56] = bytes[57] = bytes[58] = bytes[59] = 0xef;
                } else if (currentProtocolType == ProtocolTypeDD || currentProtocolType == ProtocolTypeTLS) {
                    bytes[56] = bytes[57] = bytes[58] = bytes[59] = 0xdd;
                } else if (currentProtocolType == ProtocolTypeEE) {
                    bytes[56] = bytes[57] = bytes[58] = bytes[59] = 0xee;
                }

                // The official WebSocket hostname already selects both the
                // datacenter and the traffic class (kwsN / kwsN-1). Match
                // Telegram Web and keep bytes 60..61 random for direct WSS;
                // a DC marker belongs only to MTProxy secret transports and to
                // the Worker tunnel, which reaches the DC over plain TCP.
                if (useSecret != 0 || isCurrentWssTunnel()) {
                    int16_t datacenterId;
                    if (isMediaConnection) {
                        if (ConnectionsManager::getInstance(currentDatacenter->instanceNum).testBackend) {
                            datacenterId = -(int16_t) (10000 + currentDatacenter->getDatacenterId());
                        } else {
                            datacenterId = -(int16_t) currentDatacenter->getDatacenterId();
                        }
                    } else {
                        if (ConnectionsManager::getInstance(currentDatacenter->instanceNum).testBackend) {
                            datacenterId = (int16_t) (10000 + currentDatacenter->getDatacenterId());
                        } else {
                            datacenterId = (int16_t) currentDatacenter->getDatacenterId();
                        }
                    }
                    bytes[60] = (uint8_t) (datacenterId & 0xff);
                    bytes[61] = (uint8_t) ((datacenterId >> 8) & 0xff);
                }
                break;
            }
        }

        encryptNum = decryptNum = 0;
        memset(encryptCount, 0, 16);
        memset(decryptCount, 0, 16);

        for (int32_t a = 0; a < 48; a++) {
            temp[a] = bytes[a + 8];
        }
        encryptKeyWithSecret(temp, useSecret);
        if (AES_set_encrypt_key(temp, 256, &encryptKey) < 0) {
            if (LOGS_ENABLED) DEBUG_E("unable to set encryptKey");
            exit(1);
        }
        memcpy(encryptIv, temp + 32, 16);

        for (int32_t a = 0; a < 48; a++) {
            temp[a] = bytes[55 - a];
        }
        encryptKeyWithSecret(temp, useSecret);
        if (AES_set_encrypt_key(temp, 256, &decryptKey) < 0) {
            if (LOGS_ENABLED) DEBUG_E("unable to set decryptKey");
            exit(1);
        }
        memcpy(decryptIv, temp + 32, 16);

        AES_ctr128_encrypt(bytes, temp, 64, &encryptKey, encryptIv, encryptCount, &encryptNum);
        memcpy(bytes + 56, temp + 56, 8);

        firstPacketSent = true;
    }
    if (currentProtocolType == ProtocolTypeEF) {
        if (packetLength < 0x7f) {
            if (reportAck) {
                packetLength |= (1 << 7);
            }
            buffer->writeByte((uint8_t) packetLength);
            bytes += (buffer->limit() - 1);
            AES_ctr128_encrypt(bytes, bytes, 1, &encryptKey, encryptIv, encryptCount, &encryptNum);
        } else {
            packetLength = (packetLength << 8) + 0x7f;
            if (reportAck) {
                packetLength |= (1 << 7);
            }
            buffer->writeInt32(packetLength);
            bytes += (buffer->limit() - 4);
            AES_ctr128_encrypt(bytes, bytes, 4, &encryptKey, encryptIv, encryptCount, &encryptNum);
        }
    } else {
        if (reportAck) {
            packetLength |= 0x80000000;
        }
        buffer->writeInt32(packetLength);
        bytes += (buffer->limit() - 4);
        AES_ctr128_encrypt(bytes, bytes, 4, &encryptKey, encryptIv, encryptCount, &encryptNum);
    }

    buffer->rewind();
    buff->rewind();
    AES_ctr128_encrypt(buff->bytes(), buff->bytes(), buff->limit(), &encryptKey, encryptIv, encryptCount, &encryptNum);
    if (buffer2 != nullptr) {
        AES_ctr128_encrypt(buffer2->bytes(), buffer2->bytes(), buffer2->limit(), &encryptKey, encryptIv, encryptCount, &encryptNum);
    }
    // The bytes go into the shared outgoing stream like on any TCP transport,
    // but WSS additionally needs to know where this packet ends: the relay
    // parses only the first MTProto packet of a WebSocket frame and silently
    // drops the rest, so frames must be cut exactly here. The 64-byte init
    // prefix belongs to the same frame as the packet that carries it.
    const uint32_t packetBytes = buffer->remaining() + buff->remaining()
            + (buffer2 != nullptr ? buffer2->remaining() : 0);
    noteWssPacketBoundary(packetBytes);
    writeBuffer(buffer);
    writeBuffer(buff);
    if (buffer2 != nullptr) {
        writeBuffer(buffer2);
    }
    return !isClosingOrClosedForWrites();
}

inline std::string *Connection::getCurrentSecret(uint8_t secretType) {
    if (secretType == 2) {
        return &secret;
    } else if (!overrideProxySecret.empty()) {
        return &overrideProxySecret;
    } else {
        return &ConnectionsManager::getInstance(currentDatacenter->instanceNum).proxySecret;
    }
}

void Connection::notifyConnectionClosedOnce(int32_t reason, const char *source) {
    if (connectionCloseNotified) {
        if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) mtproxy_startup connection_close_ignored_already_notified source=%s reason=%d", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, source != nullptr ? source : "unknown", reason);
        return;
    }
    connectionCloseNotified = true;
    ConnectionsManager::getInstance(currentDatacenter->instanceNum).onConnectionClosed(this, reason);
}

inline void Connection::encryptKeyWithSecret(uint8_t *bytes, uint8_t secretType) {
    if (secretType == 0) {
        return;
    }
    std::string *currentSecret = getCurrentSecret(secretType);
    size_t a = 0;
    size_t size = std::min((size_t) 16, currentSecret->length());
    if (currentSecret->length() >= 17 && ((*currentSecret)[0] == '\xdd' || (*currentSecret)[0] == '\xee')) {
        a = 1;
        size = 17;
    }

    SHA256_CTX sha256Ctx;
    SHA256_Init(&sha256Ctx);
    SHA256_Update(&sha256Ctx, bytes, 32);
    char b[1];
    for (; a < size; a++) {
        b[0] = (char) (*currentSecret)[a];
        SHA256_Update(&sha256Ctx, b, 1);
    }
    SHA256_Final(bytes, &sha256Ctx);
}

void Connection::onDisconnectedInternal(int32_t reason, int32_t error) {
    reconnectTimer->stop();
    if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) disconnected with reason %d", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, reason);
    bool switchToNextPort = reason == 2 && wasConnected && (!hasSomeDataSinceLastConnect || currentDatacenter->isCustomPort(currentAddressFlags)) || forceNextPort;
    if (connectionType == ConnectionTypeGeneric || connectionType == ConnectionTypeTemp || connectionType == ConnectionTypeGenericMedia) {
        if (wasConnected && reason == 2 && currentTimeout < 16) {
            currentTimeout += 2;
        }
    }
    generation++;
    firstPacketSent = false;
    if (restOfTheData != nullptr) {
        restOfTheData->reuse();
        restOfTheData = nullptr;
    }
    lastPacketLength = 0;
    receivedDataAmount = 0;
    wasConnected = false;
    if (connectionState != TcpConnectionStageSuspended && connectionState != TcpConnectionStageIdle) {
        connectionState = TcpConnectionStageIdle;
    }
    notifyConnectionClosedOnce(reason, "onDisconnectedInternal");
    connectionToken = 0;

    const bool mtProxyRouteActive = isMtProxyRouteActive();
    const bool mtProxyReconnectPacing = isMtProxyReconnectPacingActive();
    const char *mtProxyReconnectDiagnostic = mtProxyRouteActive ? getProxyCheckDiagnostic() : "";
    uint32_t mtProxyReconnectDelay = 0;
    uint32_t mtProxySuggestedHoldMs = mtProxyRouteActive ? consumeSuggestedReconnectHoldMs() : 0;
    if (mtProxyReconnectPacing && connectionState == TcpConnectionStageIdle && connectionType != ConnectionTypeProxy && !isProxyCloseDiagnosticSuppressed() && mtProxyDiagnosticNeedsReconnectBackoff(mtProxyReconnectDiagnostic)) {
        int64_t now = ConnectionsManager::getInstance(currentDatacenter->instanceNum).getCurrentTimeMonotonicMillis();
        MtProxyRetry::ReconnectHoldInput holdInput;
        holdInput.diagnostic = mtProxyReconnectDiagnostic;
        holdInput.trafficClass = mtProxyTrafficClassFor(connectionType);
        holdInput.previousBackoffMs = mtProxyReconnectBackoffMs;
        holdInput.coordinatorHoldMs = mtProxySuggestedHoldMs;
        MtProxyRetry::ReconnectHoldDecision holdDecision = MtProxyRetry::nextReconnectHold(holdInput);
        mtProxyReconnectBackoffMs = holdDecision.nextBackoffMs;
        mtProxyReconnectDelay = holdDecision.delayMs;
        mtProxyReconnectHoldUntil = now + mtProxyReconnectDelay;
        if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) mtproxy_startup reconnect_backoff phase=%s delay_ms=%u coordinator_hold_ms=%u failed=%u", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, mtProxyReconnectDiagnostic, mtProxyReconnectDelay, mtProxySuggestedHoldMs, failedConnectionCount + 1);
    } else if (mtProxyReconnectPacing && connectionState == TcpConnectionStageIdle && connectionType != ConnectionTypeProxy && isProxyCloseDiagnosticSuppressed() && mtProxyDiagnosticNeedsReconnectBackoff(mtProxyReconnectDiagnostic)) {
        if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) mtproxy_startup reconnect_backoff_suppressed phase=%s", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, mtProxyReconnectDiagnostic);
    }
    if (mtProxyRouteActive && strcmp(mtProxyReconnectDiagnostic, "ignored_cancelled_generation") == 0) {
        waitForReconnectTimer = false;
        usefullData = false;
        if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) mtproxy_startup reconnect_cancelled_generation", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType);
        return;
    }

    uint32_t datacenterId = currentDatacenter->getDatacenterId();
    if (connectionState == TcpConnectionStageIdle) {
        connectionState = TcpConnectionStageReconnecting;
        failedConnectionCount++;
        if (failedConnectionCount == 1) {
            if (hasUsefullData()) {
                willRetryConnectCount = 3;
            } else {
                willRetryConnectCount = 1;
            }
        }
        if (ConnectionsManager::getInstance(currentDatacenter->instanceNum).isNetworkAvailable() && connectionType != ConnectionTypeProxy) {
            isTryingNextPort = true;
            if (failedConnectionCount > willRetryConnectCount || switchToNextPort) {
                currentDatacenter->nextAddressOrPort(currentAddressFlags);
                if (currentDatacenter->isRepeatCheckingAddresses() && (ConnectionsManager::getInstance(currentDatacenter->instanceNum).getIpStratagy() == USE_IPV4_ONLY || ConnectionsManager::getInstance(currentDatacenter->instanceNum).getIpStratagy() == USE_IPV6_ONLY)) {
                    if (LOGS_ENABLED) DEBUG_D("started retrying connection, set ipv4 ipv6 random strategy");
                    ConnectionsManager::getInstance(currentDatacenter->instanceNum).setIpStrategy(USE_IPV4_IPV6_RANDOM);
                }
                failedConnectionCount = 0;
            }
        }
        if (error == 0x68 || error == 0x71) {
            if (connectionType != ConnectionTypeProxy) {
                waitForReconnectTimer = true;
                reconnectTimer->setTimeout(mtProxyReconnectDelay != 0 ? mtProxyReconnectDelay : lastReconnectTimeout, false);
                if (mtProxyReconnectDelay == 0) {
                    lastReconnectTimeout *= 2;
                    if (lastReconnectTimeout > 400) {
                        lastReconnectTimeout = 400;
                    }
                }
                reconnectTimer->start();
            }
        } else {
            waitForReconnectTimer = false;
            if (connectionType == ConnectionTypeGenericMedia && currentDatacenter->isHandshaking(true) || connectionType == ConnectionTypeGeneric && (currentDatacenter->isHandshaking(false) || datacenterId == ConnectionsManager::getInstance(currentDatacenter->instanceNum).currentDatacenterId || datacenterId == ConnectionsManager::getInstance(currentDatacenter->instanceNum).movingToDatacenterId)) {
                if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) reconnect %s:%hu", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, hostAddress.c_str(), hostPort);
                reconnectTimer->setTimeout(mtProxyReconnectDelay != 0 ? mtProxyReconnectDelay : 1000, false);
                reconnectTimer->start();
            }
        }
    }
    usefullData = false;
}

void Connection::onDisconnected(int32_t reason, int32_t error) {
    if (connectionInProcess) {
        ConnectionsManager::getInstance(currentDatacenter->instanceNum).scheduleTask([&, reason, error] {
            onDisconnectedInternal(reason, error);
        });
    } else {
        onDisconnectedInternal(reason, error);
    }
}

void Connection::onConnected() {
    connectionState = TcpConnectionStageConnected;
    connectionToken = lastConnectionToken++;
    wasConnected = true;
    if (LOGS_ENABLED) DEBUG_D("connection(%p, account%u, dc%u, type %d) connected to %s:%hu", this, currentDatacenter->instanceNum, currentDatacenter->getDatacenterId(), connectionType, hostAddress.c_str(), hostPort);
    ConnectionsManager::getInstance(currentDatacenter->instanceNum).onConnectionConnected(this);
}

bool Connection::hasPartialIncomingPacket() {
    return restOfTheData != nullptr || lastPacketLength != 0;
}

bool Connection::hasPendingRequests() {
    return ConnectionsManager::getInstance(currentDatacenter->instanceNum).hasPendingRequestsForConnection(this);
}

Datacenter *Connection::getDatacenter() {
    return currentDatacenter;
}

ConnectionType Connection::getConnectionType() {
    return connectionType;
}

int8_t Connection::getConnectionNum() {
    return connectionNum;
}

uint32_t Connection::getConnectionToken() {
    return connectionToken;
}
