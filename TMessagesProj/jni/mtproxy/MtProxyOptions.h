/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 */

#ifndef MTPROXYOPTIONS_H
#define MTPROXYOPTIONS_H

#include <stdint.h>

static constexpr int32_t MT_PROXY_TLS_PROFILE_AUTO = 0;
static constexpr int32_t MT_PROXY_TLS_PROFILE_FIREFOX = 1;
static constexpr int32_t MT_PROXY_TLS_PROFILE_ANDROID_CHROME = 2;
static constexpr int32_t MT_PROXY_TLS_PROFILE_YANDEX = 3;
static constexpr int32_t MT_PROXY_TLS_PROFILE_FIREFOX_ANDROID = 4;
static constexpr int32_t MT_PROXY_TLS_PROFILE_ANDROID_OKHTTP = 5;
static constexpr int32_t MT_PROXY_TLS_PROFILE_AUTO_ROTATE = 6;
static constexpr int32_t MT_PROXY_TLS_PROFILE_CHROME_MODERN = 7;
static constexpr int32_t MT_PROXY_TLS_PROFILE_LEGACY_NO_GREASE = 8;

static constexpr int32_t MT_PROXY_SERVER_HELLO_PARSER_STANDARD = 0;
static constexpr int32_t MT_PROXY_SERVER_HELLO_PARSER_RESERVED = 1;
static constexpr int32_t MT_PROXY_SERVER_HELLO_PARSER_LENIENT_RECORD = 2;
static constexpr int32_t MT_PROXY_SERVER_HELLO_PARSER_EXTRA_RECORDS = 3;
static constexpr int32_t MT_PROXY_SERVER_HELLO_PARSER_CCS_TICKET_ORDERING = 4;
static constexpr int32_t MT_PROXY_SERVER_HELLO_PARSER_FRAGMENTED_SERVER_HELLO = 5;
static constexpr int32_t MT_PROXY_SERVER_HELLO_PARSER_TLS_ALERT_EXACT_DESC = 6;

static constexpr int32_t MT_PROXY_CLIENT_HELLO_FRAGMENTATION_OFF = 0;
static constexpr int32_t MT_PROXY_CLIENT_HELLO_FRAGMENTATION_SOFT = 1;
static constexpr int32_t MT_PROXY_RECORD_SIZING_OFF = 0;
static constexpr int32_t MT_PROXY_RECORD_SIZING_CONSERVATIVE = 1;
static constexpr int32_t MT_PROXY_RECORD_SIZING_VARIED = 2;
static constexpr int32_t MT_PROXY_TIMING_OFF = 0;
static constexpr int32_t MT_PROXY_TIMING_GENTLE = 1;
static constexpr int32_t MT_PROXY_TIMING_BALANCED = 2;
static constexpr int32_t MT_PROXY_STARTUP_COVER_OFF = 0;
static constexpr int32_t MT_PROXY_STARTUP_COVER_SOFT = 1;
static constexpr int32_t MT_PROXY_STARTUP_COVER_STRICT = 2;
static constexpr int32_t MT_PROXY_CONNECTION_PATTERN_OFF = 0;
static constexpr int32_t MT_PROXY_CONNECTION_PATTERN_SOFT = 1;
static constexpr int32_t MT_PROXY_CONNECTION_PATTERN_QUIET = 2;
static constexpr int32_t MT_PROXY_CONNECTION_PATTERN_STRICT = 3;
static constexpr int32_t MT_PROXY_CONNECTION_PATTERN_BROWSER = 4;

// Startup handshake fanout limits: how many concurrent FakeTLS handshakes
// the scheduler may run globally (per connection-pattern mode) and per
// endpoint (by endpoint temperature). Deliberately separate from the
// established-connection counts in tgnet/Defines.h.
static constexpr int32_t MT_PROXY_STARTUP_GLOBAL_HANDSHAKES_SOFT = 2;
static constexpr int32_t MT_PROXY_STARTUP_GLOBAL_HANDSHAKES_BROWSER = 2;
static constexpr int32_t MT_PROXY_STARTUP_GLOBAL_HANDSHAKES_QUIET = 1;
static constexpr int32_t MT_PROXY_STARTUP_GLOBAL_HANDSHAKES_STRICT = 1;
static constexpr int32_t MT_PROXY_STARTUP_ENDPOINT_HANDSHAKES_COLD = 1;
static constexpr int32_t MT_PROXY_STARTUP_ENDPOINT_HANDSHAKES_USABLE = 2;

struct MtProxyOptions {
    int32_t tlsProfile = MT_PROXY_TLS_PROFILE_AUTO;
    int32_t clientHelloFragmentation = MT_PROXY_CLIENT_HELLO_FRAGMENTATION_OFF;
    int32_t connectionPatternMode = MT_PROXY_CONNECTION_PATTERN_OFF;
    int32_t recordSizingMode = MT_PROXY_RECORD_SIZING_OFF;
    int32_t timingMode = MT_PROXY_TIMING_OFF;
    int32_t startupCoverMode = MT_PROXY_STARTUP_COVER_OFF;
    // Set only for the WEB proxy's loopback bridge into the WebView carrier.
    // Such connections bypass the MTProxy dial pacing (per-endpoint TCP
    // connect gate, endpoint cooldown, reconnect backoff): the pacer exists to
    // spare a remote relay under DPI, and loopback has neither.
    bool webBridge = false;
    // Set for a Naive proxy entry (jni/zsg_naive): the connection uses the in-process Naive client.
    bool naive = false;

    bool operator==(const MtProxyOptions &other) const {
        return tlsProfile == other.tlsProfile
               && clientHelloFragmentation == other.clientHelloFragmentation
               && connectionPatternMode == other.connectionPatternMode
               && recordSizingMode == other.recordSizingMode
               && timingMode == other.timingMode
               && startupCoverMode == other.startupCoverMode
               && naive == other.naive
               && webBridge == other.webBridge;
    }

    bool operator!=(const MtProxyOptions &other) const {
        return !(*this == other);
    }
};

static inline int32_t normalizeMtProxyTlsProfileOption(int32_t value) {
    if (value == MT_PROXY_TLS_PROFILE_AUTO || value == MT_PROXY_TLS_PROFILE_AUTO_ROTATE) {
        return value;
    }
    if (value >= MT_PROXY_TLS_PROFILE_FIREFOX && value <= MT_PROXY_TLS_PROFILE_LEGACY_NO_GREASE) {
        return value;
    }
    return MT_PROXY_TLS_PROFILE_ANDROID_CHROME;
}

static inline int32_t normalizeMtProxyServerHelloParserOption(int32_t value) {
    if (value >= MT_PROXY_SERVER_HELLO_PARSER_STANDARD && value <= MT_PROXY_SERVER_HELLO_PARSER_TLS_ALERT_EXACT_DESC) {
        return value;
    }
    return MT_PROXY_SERVER_HELLO_PARSER_STANDARD;
}

static inline int32_t normalizeMtProxyClientHelloFragmentationOption(int32_t value) {
    return value == MT_PROXY_CLIENT_HELLO_FRAGMENTATION_SOFT ? MT_PROXY_CLIENT_HELLO_FRAGMENTATION_SOFT : MT_PROXY_CLIENT_HELLO_FRAGMENTATION_OFF;
}

static inline int32_t normalizeMtProxyConnectionPatternOption(int32_t value) {
    if (value >= MT_PROXY_CONNECTION_PATTERN_OFF && value <= MT_PROXY_CONNECTION_PATTERN_BROWSER) {
        return value;
    }
    return MT_PROXY_CONNECTION_PATTERN_OFF;
}

static inline int32_t normalizeMtProxyRecordSizingOption(int32_t value) {
    if (value >= MT_PROXY_RECORD_SIZING_OFF && value <= MT_PROXY_RECORD_SIZING_VARIED) {
        return value;
    }
    return MT_PROXY_RECORD_SIZING_OFF;
}

static inline int32_t normalizeMtProxyTimingOption(int32_t value) {
    if (value >= MT_PROXY_TIMING_OFF && value <= MT_PROXY_TIMING_BALANCED) {
        return value;
    }
    return MT_PROXY_TIMING_OFF;
}

static inline int32_t normalizeMtProxyStartupCoverOption(int32_t value) {
    if (value >= MT_PROXY_STARTUP_COVER_OFF && value <= MT_PROXY_STARTUP_COVER_STRICT) {
        return value;
    }
    return MT_PROXY_STARTUP_COVER_OFF;
}

static inline MtProxyOptions normalizeMtProxyOptions(const MtProxyOptions &options) {
    MtProxyOptions normalized;
    normalized.tlsProfile = normalizeMtProxyTlsProfileOption(options.tlsProfile);
    normalized.clientHelloFragmentation = normalizeMtProxyClientHelloFragmentationOption(options.clientHelloFragmentation);
    normalized.connectionPatternMode = normalizeMtProxyConnectionPatternOption(options.connectionPatternMode);
    normalized.recordSizingMode = normalizeMtProxyRecordSizingOption(options.recordSizingMode);
    normalized.timingMode = normalizeMtProxyTimingOption(options.timingMode);
    normalized.startupCoverMode = normalizeMtProxyStartupCoverOption(options.startupCoverMode);
    normalized.webBridge = options.webBridge;
    normalized.naive = options.naive;
    return normalized;
}

#endif
