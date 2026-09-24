/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#ifndef CONNECTIONSOCKET_H
#define CONNECTIONSOCKET_H

#include <sys/epoll.h>
#include <netinet/in.h>
#include <memory>
#include <string>
#include "ConnectionSocketStateMachine.h"
#include "mtproxy/MtProxyAdaptivePolicy.h"
#include "mtproxy/MtProxyEndpointRecorder.h"
#include "mtproxy/MtProxyOptions.h"
#include "mtproxy/MtProxyProbeLease.h"

class NativeByteBuffer;
class ConnectionsManager;
class ByteStream;
class EventObject;
class ByteArray;
class Timer;
struct MtProxySocketObservation;
class ConnectionSocket {

public:
    ConnectionSocket(int32_t instance);
    virtual ~ConnectionSocket();

    void writeBuffer(uint8_t *data, uint32_t size);
    void writeBuffer(NativeByteBuffer *buffer);
    void noteWssPacketBoundary(uint32_t size);
    void openConnection(std::string address, uint16_t port, std::string secret, bool ipv6, int32_t networkType, int32_t datacenterId = 0, bool mediaConnection = false);
    void setTimeout(time_t timeout);
    time_t getTimeout();
    int32_t getCurrentNetworkType() const;
    int32_t getDatacenterId() const;
    bool isDisconnected();
    bool isCurrentMtProxyConnection();
    bool isCurrentDirectConnection() const;
    bool hasMtProxyOverride() const;
    // True while this socket dials the WEB proxy's loopback bridge
    // (MtProxyOptions::webBridge). MTProxy pacing never applies to it.
    bool isCurrentWebProxyBridge() const;
    // Class of this connection's stream on the WEB carrier, one of
    // WEB_PROXY_STREAM_CLASS_*; set by Connection before every connect.
    void setWebProxyStreamClass(int32_t streamClass);
    void dropConnection();
    void setOverrideProxy(std::string address, uint16_t port, std::string username, std::string password, std::string secret, const MtProxyOptions &options);
    void onHostNameResolved(std::string host, std::string ip, bool ipv6);
    void setMtProxyHandshakePriority(int32_t priority);
    const char *getProxyCheckDiagnostic();
    bool isProxyCloseDiagnosticSuppressed();
    bool isClosingOrClosedForWrites() const;
    std::string getDiagnosticSnapshot();
    // Remaining coordinator terminal hold (budget backoff / profiles exhausted)
    // captured on the pre-TCP close path; consumed once by the Connection layer
    // so the reconnect timer waits out the coordinator's clock instead of a
    // shorter re-derived backoff. Returns 0 when no hold was suggested.
    uint32_t consumeSuggestedReconnectHoldMs();

protected:
    int32_t instanceNum;
    void onEvent(uint32_t events);
    bool checkTimeout(int64_t now);
    void resetLastEventTime();
    bool hasTlsHashMismatch();
    void publishProxyConnectionStage(const char *diagnostic);
    virtual std::string proxyConnectionStageOrigin();
    virtual std::string proxyConnectionStageSocketRole();
    void markMtProxyFirstPlainDataSent(uint32_t bytes);
    void markMtProxyFirstPlainDataReceived(uint32_t bytes);
    virtual void onReceivedData(NativeByteBuffer *buffer) = 0;
    virtual void onDisconnected(int32_t reason, int32_t error) = 0;
    virtual void onConnected() = 0;
    virtual bool hasPendingRequests() = 0;
    // An incoming MTProto packet is only partly received.
    virtual bool hasPartialIncomingPacket() { return false; }

    std::string overrideProxyUser = "";
    std::string overrideProxyPassword = "";
    std::string overrideProxyAddress = "";
    std::string overrideProxySecret = "";
    uint16_t overrideProxyPort = 1080;
    MtProxyOptions overrideMtProxyOptions;

private:
    using TransportState = ConnectionSocketStateMachine::LifecycleState;
    using TransportMode = ConnectionSocketStateMachine::TransportMode;
    using TransportSocketPolicy = ConnectionSocketStateMachine::TransportSocketPolicy;
    using TransportActionRule = ConnectionSocketStateMachine::ActionRule;

    ConnectionSocketStateMachine stateMachine;
    bool suppressNextProxyCloseDiagnostic = false;
    uint32_t proxyActivationGeneration = 0;
    uint32_t proxyConfigGeneration = 0;
    uint32_t proxySuggestedReconnectHoldMs = 0;
    std::string proxyActivationOrigin = "active_socket";

    int32_t checkSocketError(int32_t *error);
    void closeSocket(int32_t reason, int32_t error);
    // closeSocket is a dispatcher over these pipeline steps, in execution
    // order ([close-step N/8]). Step 3's resolution is the only data that
    // flows between steps; later steps must not re-derive diagnostics.
    struct CloseDiagnosticResolution {
        std::string terminalDiagnostic;
        bool suppress = false;
        bool shadowedSocketFailure = false;
        int64_t shadowedHoldMs = 0;
    };
    bool closeStepReentryGuard(int32_t reason, int32_t error);
    void closeStepTransportGate();
    CloseDiagnosticResolution closeStepResolveDiagnostic(int32_t reason, int32_t error);
    void closeStepLogDisconnect(int32_t reason, int32_t error, const CloseDiagnosticResolution &resolution);
    void closeStepPublishVerdict(int32_t reason, const CloseDiagnosticResolution &resolution);
    void closeStepReleaseResources();
    void closeStepOsTeardown();
    void closeStepResetStateAndNotify(int32_t reason, int32_t error);
    bool matchesMtProxyEndpointKey(const std::string &endpointKey);
    bool matchesMtProxyProbeKey(const std::string &probeKey);
    void cancelMtProxyEndpointAttempt(const char *reason);
    bool resetTransportSocketForOpenConnection();
    void openConnectionInternal(bool ipv6);
    void queueAdjustWriteOpAfterOutboundAppend(const char *reason);
    void adjustWriteOp();
    const char *transportStateName(TransportState state);
    bool isAllowedTransportTransition(TransportState previous, TransportState next);
    void setTransportState(TransportState next, const char *reason);
    const char *proxyAuthStateName(uint8_t state);
    bool isAllowedProxyAuthTransition(uint8_t previous, uint8_t next);
    void setProxyAuthState(uint8_t next, const char *reason);
    const char *tlsStateName(int8_t state);
    bool isAllowedTlsStateTransition(int8_t previous, int8_t next);
    void setTlsState(int8_t next, const char *reason);
    void logTransportSnapshot(const char *event, const char *reason);
    void logTransportInvariant(const char *action, const char *reason);
    const TransportActionRule *findTransportActionRule(const char *action);
    bool isTransportStateAllowedForAction(const char *action);
    bool checkTransportActionRequirements(const char *action);
    void setSocketFd(int fd, const char *reason);
    void setEpollRegistered(bool registered, const char *reason);
    bool canCreateSocket(const char *action);
    bool canUseLiveEpollSocket(const char *action);
    bool canModifyEpollWriteInterest(const char *action);
    bool canSendPendingClientHello();
    bool canSendPendingTlsFrame();
    bool canSendSocksHandshakeFrame(const char *action, uint8_t expectedProxyAuthState);
    bool canSendPlainMtProtoPayload();
    bool canStartTcpConnect();
    bool canRegisterEpollSocket();
    bool canConfigureOpenSocket();
    bool canCheckSocketError();
    bool canProcessEpollEvent();
    void checkCloseSocketAction(const char *action);
    bool canUnregisterEpollSocket();
    bool canCloseNativeSocket();
    void setProxyHandshakeAdmissionState(int8_t queued, int8_t published, int8_t active, int8_t ready, const char *reason);
    void checkProxyHandshakeAdmissionRelease(bool succeeded, const char *reason);
    void setProxyEndpointTcpConnectGateState(int8_t active, int8_t ready, int8_t published, const char *reason);
    void setProxyEndpointBackoffReady(bool ready, const char *reason);
    void setProxyEndpointDnsCoalesceReady(bool ready, const char *reason);
    void setAdjustWriteOpAfterResolve(bool pending, const char *reason);
    void setAdjustWriteOpAfterPreTcpGate(bool pending, const char *reason);
    void setMtProxyTcpConnectAttemptStarted(bool started, const char *reason);
    void setMtProxyDnsResolveAttemptStarted(bool started, const char *reason);
    void setMtProxyPreTcpWaitPhase(MtProxyStartupPhase phase, int64_t deadlineMs, const char *reason);
    void finishMtProxyPreTcpWait(const char *reason);
    bool canRunMtProxyPreTcpTimer(MtProxyStartupTimerKind expectedKind, uint32_t timerGeneration);
    void classifyMtProxyPreTcpTimeoutDiagnostic(const char *reason);
    std::string deriveMtProxyTerminalDiagnostic(int32_t reason, int32_t error);
    MtProxyStartupTimerKind mtProxyStartupTimerKindForMode(int32_t mode);
    void setMtProxySocketConnectedLogged(bool logged, const char *reason);
    bool canStartHostResolve();
    void checkHostResolveCallback(const std::string &host);
    void setWaitingForHostResolve(const std::string &host, const char *reason);
    bool canNotifyConnected(const char *action);
    void setSocketCloseNotified(bool notified, const char *reason);
    void setConnectedNotified(bool sent, const char *reason);
    bool canDeliverReceivedData(const char *action);
    bool canSendWssFrame();
    bool canQueueOutboundBuffer(const char *action);
    bool canSendRawSocketBytes(const char *action);
    bool canReceiveRawSocketBytes();
    void markConnectionDeadForWrites(const char *reason);
    bool isCurrentTransportWss();
    bool isCurrentWssTunnel();
    bool dispatchWssPayloads(std::vector<std::vector<uint8_t>> &payloads);
    bool flushWssStream(std::string *diagnostic);
    bool scheduleProxyHandshakeAdmissionIfNeeded(bool ipv6, int32_t timerMode);
    void scheduleProxyHandshakeAdmissionTimer(uint32_t delay, int32_t mode, bool ipv6);
    void grantProxyHandshakeAdmission(bool ipv6, uint32_t generation, uint32_t delay, int32_t timerMode, const char *reason);
    void requestPendingHostResolve();
    void cancelProxyHandshakeAdmission();
    void releaseProxyHandshakeAdmission(bool succeeded, const char *reason);
    bool scheduleMtProxyEndpointCircuitBreakerIfNeeded(bool ipv6);
    bool mtProxyProbeBeginOrJoin(bool ipv6);
    void mtProxyProbeWaitTimerFire(bool ipv6);
    MtProxyProbeLease mtProxyProbeLease;
    bool scheduleMtProxyEndpointTcpConnectGateIfNeeded(bool ipv6);
    void releaseMtProxyEndpointTcpConnect(const char *reason);
    bool scheduleMtProxyDnsCoalesceIfNeeded(bool ipv6);
    MtProxyEndpointRecorder::Callbacks mtProxyEndpointRecorderCallbacks();
    MtProxyEndpointRecorder::FailureContext mtProxyEndpointFailureContext(const char *diagnostic, const char *reason);
    MtProxyEndpointRecorder::SuccessContext mtProxyEndpointSuccessContext(const char *reason);
    MtProxyEndpointRecorder::ProbeBackoffContext mtProxyEndpointProbeBackoffContext(uint32_t holdMs, uint32_t generation, const std::string &terminalPhase);
    void publishMtProxySocketObservation(const MtProxySocketObservation &observation);
    void publishSanitizedSecretDomainIfNeeded(size_t rawDomainLength);
    void closeMtProxyDnsBlockedZeroAddress(const std::string &host, const std::string &ip, const char *reason);
    bool mtProxyEndpointUseCachedHostAddress(const std::string &host, bool *ipv6, bool *blockedZeroAddress);
    void mtProxyEndpointStoreResolvedAddress(const std::string &host, const std::string &ip);
    MtProxyAdaptivePolicy::RecipeInput currentMtProxyRecipeInput();
    MtProxyAdaptivePolicy::CompatibilityRecipe currentMtProxyCompatibilityRecipe();
    std::string currentMtProxyRecipeId();
    std::string mtProxyRecipeIdForCursor(const MtProxyAdaptivePolicy::RecipeCursor &cursor);
    bool currentMtProxyRecipeUsesGrease();
    bool currentMtProxyRecipeIsGreaseProbe();
    bool mtProxyClassicFallbackAllowed();
    void applyMtProxyPhaseAdaptiveRecipe();
    void rotateMtProxyTlsProfileOnFailureIfNeeded(int32_t reason, int32_t error);
    void logMtProxyTlsAfterClientHello(size_t responseBytes);
    const char *classifyMtProxyPostClientHelloResponse(size_t responseBytes);
    void closeMtProxyPostClientHelloResponse(const char *diagnostic, const char *reason, int32_t error);
    bool didPauseDuringProxyServerHelloWait(int64_t now);
    void markProxyHandshakeClientHelloSent();
    void markProxyHandshakeFreezeIfNeeded();
    void markProxyServerHelloHmacTimeoutIfNeeded();
    void clearPendingClientHello();
    bool buildPendingClientHello(uint32_t size);
    bool sendPendingClientHelloFragment(uint32_t limit);
    bool sendPendingClientHello();
    void clearPendingTlsFrame();
    bool buildPendingTlsFrame(NativeByteBuffer *buffer, uint32_t remaining);
    bool sendPendingTlsFrame();
    bool scheduleMtProxyDataTimingIfNeeded();
    void startMtProxyStartupCover();
    bool mtProxyStartupCoverActive();
    int32_t effectiveMtProxyRecordSizingMode();
    int32_t effectiveMtProxyTimingMode();
    void announceWebProxyStream();
    bool deferWebProxyReceiveTimeout(int64_t now);
    int32_t webProxyStreamClass = 0;

    friend class EventObject;
    friend class ConnectionsManager;
    friend class Connection;
};

#endif
