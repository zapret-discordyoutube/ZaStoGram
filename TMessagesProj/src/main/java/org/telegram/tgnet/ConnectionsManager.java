package org.telegram.tgnet;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.net.DnsResolver;
import android.os.AsyncTask;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Keep;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.CaptchaController;
import org.telegram.messenger.EmuDetector;
import org.telegram.messenger.FileLoadOperation;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileUploadOperation;
import org.telegram.messenger.KeepAliveJob;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProxyConnectionEvent;
import org.telegram.messenger.ProxyRuntimeStateStore;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.StatsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.proxy.WebProxyConnectionTester;
import org.telegram.proxy.WebProxyFlow;
import org.telegram.proxy.WebProxyTransport;
import org.telegram.proxy.ProxySettings;
import org.telegram.proxy.ProxyWssFallback;
import org.telegram.ui.Components.VideoPlayer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;

@OptIn(markerClass = UnstableApi.class)
public class ConnectionsManager extends BaseController {

    public final static int ConnectionTypeGeneric = 1;
    public final static int ConnectionTypeDownload = 2;
    public final static int ConnectionTypeUpload = 4;
    public final static int ConnectionTypePush = 8;
    public final static int ConnectionTypeDownload2 = ConnectionTypeDownload | (1 << 16);

    public final static int FileTypePhoto = 0x01000000;
    public final static int FileTypeVideo = 0x02000000;
    public final static int FileTypeAudio = 0x03000000;
    public final static int FileTypeFile = 0x04000000;

    public final static int RequestFlagEnableUnauthorized = 1;
    public final static int RequestFlagFailOnServerErrors = 2;
    public final static int RequestFlagCanCompress = 4;
    public final static int RequestFlagWithoutLogin = 8;
    public final static int RequestFlagTryDifferentDc = 16;
    public final static int RequestFlagForceDownload = 32;
    public final static int RequestFlagInvokeAfter = 64;
    public final static int RequestFlagNeedQuickAck = 128;
    public final static int RequestFlagDoNotWaitFloodWait = 1024;
    public final static int RequestFlagListenAfterCancel = 2048;
    public final static int RequestFlagFailOnServerErrorsExceptFloodWait = 65536;

    public final static int ConnectionStateConnecting = 1;
    public final static int ConnectionStateWaitingForNetwork = 2;
    public final static int ConnectionStateConnected = 3;
    public final static int ConnectionStateConnectingToProxy = 4;
    public final static int ConnectionStateUpdating = 5;

    public final static byte USE_IPV4_ONLY = 0;
    public final static byte USE_IPV6_ONLY = 1;
    public final static byte USE_IPV4_IPV6_RANDOM = 2;

    public final static int MT_PROXY_TLS_PROFILE_AUTO = 0;
    public final static int MT_PROXY_TLS_PROFILE_FIREFOX = 1;
    public final static int MT_PROXY_TLS_PROFILE_ANDROID_CHROME = 2;
    public final static int MT_PROXY_TLS_PROFILE_YANDEX = 3;
    public final static int MT_PROXY_TLS_PROFILE_FIREFOX_ANDROID = 4;
    public final static int MT_PROXY_TLS_PROFILE_ANDROID_OKHTTP = 5;
    public final static int MT_PROXY_TLS_PROFILE_AUTO_ROTATE = 6;
    public final static int MT_PROXY_TLS_PROFILE_CHROME_MODERN = 7;
    public final static int MT_PROXY_CLIENT_HELLO_FRAGMENTATION_OFF = 0;
    public final static int MT_PROXY_CLIENT_HELLO_FRAGMENTATION_SOFT = 1;
    public final static int MT_PROXY_RECORD_SIZING_OFF = 0;
    public final static int MT_PROXY_RECORD_SIZING_CONSERVATIVE = 1;
    public final static int MT_PROXY_RECORD_SIZING_VARIED = 2;
    public final static int MT_PROXY_TIMING_OFF = 0;
    public final static int MT_PROXY_TIMING_GENTLE = 1;
    public final static int MT_PROXY_TIMING_BALANCED = 2;
    public final static int MT_PROXY_STARTUP_COVER_OFF = 0;
    public final static int MT_PROXY_STARTUP_COVER_SOFT = 1;
    public final static int MT_PROXY_STARTUP_COVER_STRICT = 2;
    public final static int MT_PROXY_CONNECTION_PATTERN_OFF = 0;
    public final static int MT_PROXY_CONNECTION_PATTERN_SOFT = 1;
    public final static int MT_PROXY_CONNECTION_PATTERN_QUIET = 2;
    public final static int MT_PROXY_CONNECTION_PATTERN_STRICT = 3;
    public final static int MT_PROXY_CONNECTION_PATTERN_BROWSER = 4;
    private static final long TL_UNMAPPED_CONSTRUCTOR_LOG_INTERVAL_MS = 30_000;
    private static final long TL_UNMAPPED_SUMMARY_INTERVAL_MS = 60_000;
    private static final HashMap<Integer, UnmappedConstructorStats> unmappedConstructorStats = new HashMap<>();

    private static class UnmappedConstructorStats {
        long lastLogTime;
        long lastSummaryTime;
        int count;
    }

    private static void logTlDebugUnmappedConstructor(int constructor) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        UnmappedConstructorStats stats = unmappedConstructorStats.get(constructor);
        if (stats == null) {
            stats = new UnmappedConstructorStats();
            unmappedConstructorStats.put(constructor, stats);
        }
        stats.count++;
        if (stats.lastLogTime == 0 || now - stats.lastLogTime >= TL_UNMAPPED_CONSTRUCTOR_LOG_INTERVAL_MS) {
            stats.lastLogTime = now;
            FileLog.d(String.format("tl_debug_unmapped_constructor constructor=0x%x context=java_parser action=ignored", constructor));
        }
        if (stats.lastSummaryTime == 0) {
            stats.lastSummaryTime = now;
            return;
        }
        if (now - stats.lastSummaryTime >= TL_UNMAPPED_SUMMARY_INTERVAL_MS) {
            FileLog.d(String.format("tl_debug_unmapped_summary constructor=0x%x count=%d", constructor, stats.count));
            stats.lastSummaryTime = now;
            stats.count = 0;
        }
    }
    public static final String BACKGROUND_NETWORK_ALWAYS_ON = "backgroundNetworkAlwaysOn";

    private static final String MT_PROXY_TLS_PROFILE_PREFS = "mtproxy_tls_profile";
    private static final String MT_PROXY_TLS_PROFILE_OVERRIDE = "profile_override";
    private static final String DOH_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36";
    private static final String DOH_GOOGLE_QUERY_ENDPOINT = "https://dns.google/resolve";
    private static final String DOH_CLOUDFLARE_QUERY_ENDPOINT = "https://cloudflare-dns.com/dns-query";
    private static final int HOST_RESOLVER_SYSTEM_TIMEOUT_MS = 1500;
    private static final int HOST_RESOLVER_DOH_CONNECT_TIMEOUT_MS = 1000;
    private static final int HOST_RESOLVER_DOH_READ_TIMEOUT_MS = 1500;
    private static final int HOST_RESOLVER_TOTAL_TIMEOUT_MS = 3000;
    private static final long HOST_RESOLVER_MAX_FRESH_TTL_MS = 30 * 60 * 1000L;
    private static final long HOST_RESOLVER_STALE_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final long HOST_RESOLVER_NEGATIVE_TTL_MS = 45 * 1000L;
    private static final String DNS_NEGATIVE_CACHE_NATIVE_SENTINEL = "dns_negative_cache_hit";
    private static final String DNS_BLOCKED_ZERO_NATIVE_SENTINEL = "dns_blocked_zero_address";
    private static final String DNS_REASON_ALL_RESOLVERS_FAILED = "all_resolvers_failed";
    private static final String DNS_REASON_NO_IPV4_ANSWER = "no_ipv4_answer";
    private static final String DNS_REASON_NXDOMAIN = "nxdomain";
    private static final String DNS_REASON_TIMEOUT = "timeout";

    private static long lastDnsRequestTime;

    public final static int DEFAULT_DATACENTER_ID = Integer.MAX_VALUE;

    private long lastPauseTime = System.currentTimeMillis();
    private boolean appPaused = true;
    private boolean isUpdating;
    private int connectionState;
    private AtomicInteger lastRequestToken = new AtomicInteger(1);
    private int appResumeCount;

    private static AsyncTask currentTask;

    private static HashMap<String, ResolveHostByNameTask> resolvingHostnameTasks = new HashMap<>();
    private static final AtomicInteger dnsResolveGeneration = new AtomicInteger(1);

    public static final Executor DNS_THREAD_POOL_EXECUTOR;
    private static final Executor DNS_DIRECT_EXECUTOR = Runnable::run;
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final int KEEP_ALIVE_SECONDS = 30;
    private static final BlockingQueue<Runnable> sPoolWorkQueue = new LinkedBlockingQueue<>(128);
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, "DnsAsyncTask #" + mCount.getAndIncrement());
        }
    };

    private boolean forceTryIpV6;

    static {
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, sPoolWorkQueue, sThreadFactory);
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        DNS_THREAD_POOL_EXECUTOR = threadPoolExecutor;
    }

    public void setForceTryIpV6(boolean forceTryIpV6) {
        if (this.forceTryIpV6 != forceTryIpV6) {
            this.forceTryIpV6 = forceTryIpV6;
            checkConnection();
        }
    }

    public void discardConnection(int dcId, int connectionType) {
        Utilities.stageQueue.postRunnable(() -> {
            native_discardConnection(currentAccount, dcId, connectionType);
        });
    }

    public void failNotRunningRequest(int requestToken) {
        Utilities.stageQueue.postRunnable(() -> {
            native_failNotRunningRequest(currentAccount, requestToken);
        });
    }

    // Насколько быстрый повторный запрос имени считать признаком того, что
    // выданный адрес не сработал.
    private static final long ADDRESS_RETRY_WINDOW_MS = 20_000L;

    private static class ResolvedDomain {

        public final List<String> ipv4;
        public final List<String> ipv6;
        private int preferredIndex;
        private long lastHandOutTime;
        final long expiresAtMs;
        final long staleExpiresAtMs;
        final String source;

        public ResolvedDomain(List<String> ipv4Addresses, List<String> ipv6Addresses, long expiresAtMs, long staleExpiresAtMs, String source) {
            ipv4 = ipv4Addresses != null ? ipv4Addresses : new ArrayList<>();
            ipv6 = ipv6Addresses != null ? ipv6Addresses : new ArrayList<>();
            this.expiresAtMs = expiresAtMs;
            this.staleExpiresAtMs = staleExpiresAtMs;
            this.source = source;
        }

        public boolean isFresh(long now) {
            return now <= expiresAtMs && hasAddresses();
        }

        public boolean isStale(long now) {
            return now <= staleExpiresAtMs && hasAddresses();
        }

        public boolean hasAddresses() {
            return !ipv4.isEmpty() || !ipv6.isEmpty();
        }

        public String getAddress() {
            // IPv6 первым, когда он есть: у веб-релеев Telegram IPv4-адрес
            // обычно единственный и режется целиком, а IPv6 их сразу два и
            // блокируют их заметно реже. Если IPv6 у устройства нет, резолвер
            // его и не вернёт, и остаётся прежнее поведение.
            // Порядок семейств выбирается по тому, что у устройства реально
            // есть. При отключённом IPv4 начинать с него — значит тратить
            // попытку впустую на каждом соединении: диалоги и медиа грузятся
            // ощутимо дольше. При работающем IPv4 он идёт первым, потому что
            // глобальный IPv6-адрес ещё не означает маршрут до релея.
            List<String> addresses;
            if (!ipv6.isEmpty() && !deviceHasGlobalIpv4()) {
                addresses = new ArrayList<>(ipv6);
                addresses.addAll(ipv4);
            } else {
                addresses = new ArrayList<>(ipv4);
                addresses.addAll(ipv6);
            }
            if (addresses.isEmpty()) {
                addresses = ipv4.isEmpty() ? ipv6 : ipv4;
            }
            // Держимся адреса, который работает, и уходим с него только при
            // отказе. Признак отказа — повторный запрос того же имени вскоре
            // после предыдущего: успешное соединение живёт минутами и заново
            // резолвить имя не заставляет, а неудачная попытка возвращается
            // сюда почти сразу.
            long now = SystemClock.elapsedRealtime();
            if (lastHandOutTime != 0 && now - lastHandOutTime < ADDRESS_RETRY_WINDOW_MS) {
                preferredIndex++;
            }
            lastHandOutTime = now;
            int index = Math.abs(preferredIndex) % addresses.size();
            return addresses.get(index);
        }
    }

    private static class NegativeDnsCacheEntry {
        final long expiresAtMs;
        final String reason;

        NegativeDnsCacheEntry(long expiresAtMs, String reason) {
            this.expiresAtMs = expiresAtMs;
            this.reason = TextUtils.isEmpty(reason) ? DNS_REASON_ALL_RESOLVERS_FAILED : reason;
        }

        boolean isFresh(long now) {
            return now <= expiresAtMs;
        }
    }

    private static HashMap<String, ResolvedDomain> dnsCache = new HashMap<>();
    private static HashMap<String, NegativeDnsCacheEntry> negativeDnsCache = new HashMap<>();

    private static int lastClassGuid = 1;

    private static final ConnectionsManager[] Instance = new ConnectionsManager[UserConfig.MAX_ACCOUNT_COUNT];
    public static ConnectionsManager getInstance(int num) {
        ConnectionsManager localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ConnectionsManager.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ConnectionsManager(num);
                }
            }
        }
        return localInstance;
    }

    public ConnectionsManager(int instance) {
        super(instance);
        connectionState = native_getConnectionState(currentAccount);
        String deviceModel;
        String systemLangCode;
        String langCode;
        String appVersion;
        String systemVersion;
        File config = ApplicationLoader.getFilesDirFixed();
        if (instance != 0) {
            config = new File(config, "account" + instance);
            config.mkdirs();
        }
        String configPath = config.toString();
        boolean enablePushConnection = isPushConnectionEnabled();
        try {
            systemLangCode = LocaleController.getSystemLocaleStringIso639().toLowerCase();
            langCode = LocaleController.getLocaleStringIso639().toLowerCase();
            deviceModel = Build.MANUFACTURER + Build.MODEL;
            PackageInfo pInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            appVersion = pInfo.versionName + " (" + pInfo.versionCode + ")";
            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                appVersion += " pbeta";
            } else if (BuildVars.DEBUG_VERSION) {
                appVersion += " beta";
            }
            systemVersion = "SDK " + Build.VERSION.SDK_INT;
        } catch (Exception e) {
            systemLangCode = "en";
            langCode = "";
            deviceModel = "Android unknown";
            appVersion = "App version unknown";
            systemVersion = "SDK " + Build.VERSION.SDK_INT;
        }
        if (systemLangCode.trim().length() == 0) {
            systemLangCode = "en";
        }
        if (deviceModel.trim().length() == 0) {
            deviceModel = "Android unknown";
        }
        if (appVersion.trim().length() == 0) {
            appVersion = "App version unknown";
        }
        if (systemVersion.trim().length() == 0) {
            systemVersion = "SDK Unknown";
        }
        getUserConfig().loadConfig();
        String pushString = getRegId();
        String fingerprint = AndroidUtilities.getCertificateSHA256Fingerprint();

        int timezoneOffset = (TimeZone.getDefault().getRawOffset() + TimeZone.getDefault().getDSTSavings()) / 1000;
        SharedPreferences mainPreferences;
        if (currentAccount == 0) {
            mainPreferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        } else {
            mainPreferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig" + currentAccount, Activity.MODE_PRIVATE);
        }
        forceTryIpV6 = mainPreferences.getBoolean("forceTryIpV6", false);
        boolean userPremium = false;
        if (getUserConfig().getCurrentUser() != null) {
            userPremium = getUserConfig().getCurrentUser().premium;
        }
        init(SharedConfig.buildVersion(), TLRPC.LAYER, BuildVars.APP_ID, deviceModel, systemVersion, appVersion, langCode, systemLangCode, configPath, FileLog.getNetworkLogPath(), pushString, fingerprint, timezoneOffset, getUserConfig().getClientUserId(), userPremium, enablePushConnection);
    }

    private String getRegId() {
        String pushString = SharedConfig.pushString;
        if (!TextUtils.isEmpty(pushString) && SharedConfig.pushType == PushListenerController.PUSH_TYPE_HUAWEI) {
            pushString = "huawei://" + pushString;
        }
        if (TextUtils.isEmpty(pushString) && !TextUtils.isEmpty(SharedConfig.pushStringStatus)) {
            pushString = SharedConfig.pushStringStatus;
        }
        if (TextUtils.isEmpty(pushString)) {
            String tag = SharedConfig.pushType == PushListenerController.PUSH_TYPE_FIREBASE ? "FIREBASE" : SharedConfig.pushType == PushListenerController.PUSH_TYPE_SIMPLE ? "UNIFIEDPUSH" : "HUAWEI";
            pushString = SharedConfig.pushStringStatus = "__" + tag + "_GENERATING_SINCE_" + getCurrentTime() + "__";
        }
        return pushString;
    }

    public boolean isPushConnectionEnabled() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.contains("pushConnection")) {
            return preferences.getBoolean("pushConnection", true);
        } else {
            SharedPreferences legacyPreferences = MessagesController.getNotificationsSettings(UserConfig.selectedAccount);
            if (legacyPreferences.contains("pushConnection")) {
                boolean enabled = legacyPreferences.getBoolean("pushConnection", false);
                // Older builds displayed this as an account setting even though
                // native startup consumed it globally. Preserve that explicit
                // choice once, then use one source of truth for every account.
                preferences.edit().putBoolean("pushConnection", enabled).commit();
                return enabled;
            }
            return MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("backgroundConnection", false);
        }
    }

    public static void applyPushConnectionPolicyForAllAccounts() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager manager = getInstance(a);
                manager.setPushConnectionEnabled(manager.isPushConnectionEnabled());
            }
        }
    }

    public static boolean isBackgroundNetworkAlwaysOn() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        return preferences.getBoolean(BACKGROUND_NETWORK_ALWAYS_ON, false);
    }

    public long getCurrentTimeMillis() {
        return native_getCurrentTimeMillis(currentAccount);
    }

    public int getCurrentTime() {
        return native_getCurrentTime(currentAccount);
    }

    public int getCurrentDatacenterId() {
        return native_getCurrentDatacenterId(currentAccount);
    }

    public String getDatacenterConnectionDiagnostics(int datacenterId) {
        if (datacenterId == 0) {
            return "none";
        }
        String result = native_getDatacenterConnectionDiagnostics(currentAccount, datacenterId);
        return TextUtils.isEmpty(result) ? "none" : result;
    }

    public long getCurrentAuthKeyId() {
        return native_getCurrentAuthKeyId(currentAccount);
    }

    public int getTimeDifference() {
        return native_getTimeDifference(currentAccount);
    }

    public <T extends TLObject> int sendRequestTyped(TLMethod<T> method, Utilities.Callback2<T, TLRPC.TL_error> completionBlock) {
        return sendRequestTyped(method, null, completionBlock);
    }
    public <T extends TLObject> int sendRequestTyped(TLMethod<T> method, Executor executor, Utilities.Callback2<T, TLRPC.TL_error> completionBlock) {
        return sendRequestTyped(method, executor, completionBlock, DEFAULT_DATACENTER_ID, 0);
    }
    public <T extends TLObject> int sendRequestTyped(TLMethod<T> method, Executor executor, Utilities.Callback2<T, TLRPC.TL_error> completionBlock, int requestFlags) {
        return sendRequestTyped(method, executor, completionBlock, DEFAULT_DATACENTER_ID, requestFlags);
    }
    public <T extends TLObject> int sendRequestTyped(TLMethod<T> method, Executor executor, Utilities.Callback2<T, TLRPC.TL_error> completionBlock, int dcId, int requestFlags) {
        return sendRequest(method, (res, err) -> {
            //noinspection unchecked
            T result = (T) res;
            if (executor != null) {
                executor.execute(() -> completionBlock.run(result, err));
            } else {
                completionBlock.run(result, err);
            }
        }, null, null, null, requestFlags, dcId, ConnectionTypeGeneric, true);
    }



    public int sendRequestTypedAndProcessUpdates(TLMethod<TLRPC.Updates> method, Executor executor, Utilities.Callback2<TLRPC.Updates, TLRPC.TL_error> completionBlock) {
        return sendRequestTypedAndProcessUpdates(method, executor, completionBlock, DEFAULT_DATACENTER_ID, 0);
    }

    public int sendRequestTypedAndProcessUpdates(TLMethod<TLRPC.Updates> method, Executor executor, Utilities.Callback2<TLRPC.Updates, TLRPC.TL_error> completionBlock, int dcId, int requestFlags) {
        return sendRequestTyped(method, null, (result, err) -> {
            if (result != null) {
                getMessagesController().processUpdates(result, false);
            }
            if (executor != null) {
                executor.execute(() -> completionBlock.run(result, err));
            } else {
                completionBlock.run(result, err);
            }
        }, dcId, requestFlags);
    }


    public int sendRequest(TLObject object, RequestDelegate completionBlock) {
        return sendRequest(object, completionBlock, null, 0);
    }

    public int sendRequest(TLObject object, RequestDelegate completionBlock, int flags) {
        return sendRequest(object, completionBlock, null, null, null, flags, DEFAULT_DATACENTER_ID, ConnectionTypeGeneric, true);
    }

    public int sendRequest(TLObject object, RequestDelegate completionBlock, int flags, int connectionType) {
        return sendRequest(object, completionBlock, null, null, null, flags, DEFAULT_DATACENTER_ID, connectionType, true);
    }

    public int sendRequest(TLObject object, RequestDelegateTimestamp completionBlock, int flags, int connectionType, int datacenterId) {
        return sendRequest(object, null, completionBlock, null, null, flags, datacenterId, connectionType, true);
    }

    public int sendRequest(TLObject object, RequestDelegate completionBlock, QuickAckDelegate quickAckBlock, int flags) {
        return sendRequest(object, completionBlock, null, quickAckBlock, null, flags, DEFAULT_DATACENTER_ID, ConnectionTypeGeneric, true);
    }

    public int sendRequest(final TLObject object, final RequestDelegate onComplete, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        return sendRequest(object, onComplete, null, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate);
    }

    public int sendRequestSync(final TLObject object, final RequestDelegate onComplete, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        final int requestToken = lastRequestToken.getAndIncrement();
        sendRequestInternal(object, onComplete, null, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
        return requestToken;
    }

    public int sendRequest(final TLObject object, final RequestDelegate onComplete, final RequestDelegateTimestamp onCompleteTimestamp, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        final int requestToken = lastRequestToken.getAndIncrement();
        Utilities.stageQueue.postRunnable(() -> {
            sendRequestInternal(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
        });
        return requestToken;
    }

    private void sendRequestInternal(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket, int flags, int datacenterId, int connectionType, boolean immediate, int requestToken) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("send request " + object + " with token = " + requestToken);
        }
        try {
            NativeByteBuffer buffer = new NativeByteBuffer(object.getObjectSize());
            object.serializeToStream(buffer);
            object.freeResources();

            long startRequestTime = 0;
            if (BuildVars.DEBUG_PRIVATE_VERSION && BuildVars.LOGS_ENABLED || (connectionType & ConnectionTypeDownload) != 0) {
                startRequestTime = System.currentTimeMillis();
            }
            long finalStartRequestTime = startRequestTime;
            listen(requestToken, (response, errorCode, errorText, networkType, timestamp, requestMsgId, dcId) -> {
                try {
                    TLObject resp = null;
                    TLRPC.TL_error error = null;
                    int responseSize = 0;
                    if (response != 0) {
                        NativeByteBuffer buff = NativeByteBuffer.wrap(response);
                        buff.setDataSourceType(TLDataSourceType.NETWORK);
                        buff.reused = true;
                        responseSize = buff.limit();
                        int magic = buff.readInt32(true);
                        try {
                            resp = object.deserializeResponse(buff, magic, true);
                        } catch (Exception e2) {
                            boolean dropAnswer = TLParseException.isRpcDropAnswerConstructor(magic);
                            logTlParseExceptionContext(object, e2, magic, requestToken, requestMsgId, connectionType, dcId, responseSize, dropAnswer);
                            if (dropAnswer) {
                                return;
                            }
                            if (BuildVars.DEBUG_PRIVATE_VERSION) {
                                throw e2;
                            }
                            FileLog.fatal(e2);
                            return;
                        }
                    } else if (errorText != null) {
                        error = new TLRPC.TL_error();
                        error.code = errorCode;
                        error.text = errorText;
                        if (BuildVars.LOGS_ENABLED && error.code != -2000) {
                            FileLog.e(object + " got error " + error.code + " " + error.text);
                        }
                    }
                    if ((connectionType & ConnectionTypeDownload) != 0 && VideoPlayer.activePlayers.isEmpty()) {
                        long ping_time = native_getCurrentPingTime(currentAccount);
                        final long size = responseSize;
                        final long delta = Math.max(0, (System.currentTimeMillis() - finalStartRequestTime) - ping_time);
                        DefaultBandwidthMeter.getSingletonInstance(ApplicationLoader.applicationContext).onTransfer(size, delta);
                    }
                    if (BuildVars.DEBUG_PRIVATE_VERSION && !getUserConfig().isClientActivated() && error != null && error.code == 400 && Objects.equals(error.text, "CONNECTION_NOT_INITED")) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("Cleanup keys for " + currentAccount + " because of CONNECTION_NOT_INITED");
                        }
                        cleanup(true);
                        sendRequest(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate);
                        return;
                    }
                    if (resp != null) {
                        resp.networkType = networkType;
                    }
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("java received " + resp + (error != null ? " error = " + error : "") + " messageId = 0x" + Long.toHexString(requestMsgId));
                        FileLog.dumpResponseAndRequest(currentAccount, object, resp, error, requestMsgId, finalStartRequestTime, requestToken);
                    }
                    final TLObject finalResponse = resp;
                    final TLRPC.TL_error finalError = error;
                    Utilities.stageQueue.postRunnable(() -> {
                        if (onComplete != null) {
                            onComplete.run(finalResponse, finalError);
                        } else if (onCompleteTimestamp != null) {
                            onCompleteTimestamp.run(finalResponse, finalError, timestamp);
                        } else if (finalResponse instanceof TLRPC.Updates) {
                            KeepAliveJob.finishJob();
                            AccountInstance.getInstance(currentAccount).getMessagesController().processUpdates((TLRPC.Updates) finalResponse, false);
                        }
                        if (finalResponse != null) {
                            finalResponse.freeResources();
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }, onQuickAck, onWriteToSocket);
            native_sendRequest(currentAccount, buffer.address, flags, datacenterId, connectionType, immediate, requestToken);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void logTlParseExceptionContext(TLObject request, Throwable error, int constructor, int requestToken, long requestMsgId, int connectionType, int dcId, int responseSize, boolean dropAnswer) {
        StringBuilder builder = new StringBuilder(dropAnswer ? "tl_parse_drop_answer_ignored" : "tl_parse_exception_context");
        builder.append(" raw_constructor=0x").append(Integer.toHexString(constructor));
        builder.append(" expected_response=").append(expectedResponseType(request));
        builder.append(" request=").append(request == null ? "null" : request.getClass().getSimpleName());
        builder.append(" request_token=").append(requestToken);
        builder.append(" request_msg_id=0x").append(Long.toHexString(requestMsgId));
        builder.append(" conType=").append(connectionType);
        builder.append(" dc=").append(dcId);
        builder.append(" response_size=").append(responseSize);
        builder.append(" ").append(fileRequestContext(request));
        builder.append(" error=").append(error == null ? "null" : error.getClass().getSimpleName());
        String message = error == null ? null : error.getMessage();
        if (!TextUtils.isEmpty(message)) {
            builder.append(" message=").append(message.replace(' ', '_'));
        }
        if (dropAnswer) {
            builder.append(" action=ignored");
            FileLog.d(builder.toString());
        } else {
            FileLog.e(builder.toString());
        }
    }

    private static String expectedResponseType(TLObject request) {
        if (request instanceof TLRPC.TL_upload_getFile) {
            return "TLRPC.upload_File{0x96a18d5,0xf18cda44}";
        }
        if (request instanceof TLRPC.TL_upload_getCdnFile) {
            return "TLRPC.upload_CdnFile{0xa99fca4f,0xeea8e46e}";
        }
        if (request instanceof TLRPC.TL_upload_getFileHashes) {
            return "Vector<TL_fileHash>";
        }
        if (request instanceof TLRPC.TL_upload_getCdnFileHashes) {
            return "Vector<TL_fileHash>";
        }
        return request == null ? "unknown" : request.getClass().getName() + ".deserializeResponse";
    }

    private static String fileRequestContext(TLObject request) {
        if (request instanceof TLRPC.TL_upload_getFile) {
            TLRPC.TL_upload_getFile getFile = (TLRPC.TL_upload_getFile) request;
            return "file=" + inputFileLocationContext(getFile.location)
                    + " offset=" + getFile.offset
                    + " limit=" + getFile.limit
                    + " precise=" + getFile.precise
                    + " cdn_supported=" + getFile.cdn_supported;
        }
        if (request instanceof TLRPC.TL_upload_getCdnFile) {
            TLRPC.TL_upload_getCdnFile getCdnFile = (TLRPC.TL_upload_getCdnFile) request;
            return "file=cdn_token:" + (getCdnFile.file_token == null ? 0 : getCdnFile.file_token.length)
                    + " offset=" + getCdnFile.offset
                    + " limit=" + getCdnFile.limit;
        }
        return "file=unknown offset=unknown limit=unknown";
    }

    private static String inputFileLocationContext(TLRPC.InputFileLocation location) {
        if (location == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(location.getClass().getSimpleName());
        if (location.id != 0) {
            builder.append(":id=").append(location.id);
        }
        if (location.volume_id != 0 || location.local_id != 0) {
            builder.append(":volume=").append(location.volume_id).append(":local=").append(location.local_id);
        }
        if (!TextUtils.isEmpty(location.thumb_size)) {
            builder.append(":thumb=").append(location.thumb_size);
        }
        return builder.toString();
    }

    private final ConcurrentHashMap<Integer, RequestCallbacks> requestCallbacks = new ConcurrentHashMap<>();
    private static class RequestCallbacks {
        public RequestDelegateInternal onComplete;
        public QuickAckDelegate onQuickAck;
        public WriteToSocketDelegate onWriteToSocket;
        public Runnable onCancelled;
        public RequestCallbacks(RequestDelegateInternal onComplete, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket) {
            this.onComplete = onComplete;
            this.onQuickAck = onQuickAck;
            this.onWriteToSocket = onWriteToSocket;
        }
    }

    private void listen(int requestToken, RequestDelegateInternal onComplete, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket) {
        requestCallbacks.put(requestToken, new RequestCallbacks(onComplete, onQuickAck, onWriteToSocket));
//        FileLog.d("{rc} listen(" + currentAccount + ", " + requestToken + "): " + requestCallbacks.size() + " requests' callbacks");
    }

    private void listenCancel(int requestToken, Runnable onCancelled) {
        RequestCallbacks callbacks = requestCallbacks.get(requestToken);
        if (callbacks != null) {
            callbacks.onCancelled = onCancelled;
//            FileLog.d("{rc} listenCancel(" + currentAccount + ", " + requestToken + "): " + requestCallbacks.size() + " requests' callbacks");
        } else {
//            FileLog.d("{rc} listenCancel(" + currentAccount + ", " + requestToken + "): callback not found, " + requestCallbacks.size() + " requests' callbacks");
        }
    }

    public static void onRequestClear(int currentAccount, int requestToken, boolean cancelled) {
        ConnectionsManager connectionsManager = getInstance(currentAccount);
        if (connectionsManager == null) return;
        RequestCallbacks callbacks = connectionsManager.requestCallbacks.get(requestToken);
        if (cancelled) {
            if (callbacks != null) {
                if (callbacks.onCancelled != null) {
                    callbacks.onCancelled.run();
                }
                connectionsManager.requestCallbacks.remove(requestToken);
//                FileLog.d("{rc} onRequestClear(" + currentAccount + ", " + requestToken + ", " + cancelled + "): request to cancel is found " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
            } else {
//                FileLog.d("{rc} onRequestClear(" + currentAccount + ", " + requestToken + ", " + cancelled + "): request to cancel is not found " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
            }
        } else if (callbacks != null) {
            connectionsManager.requestCallbacks.remove(requestToken);
//            FileLog.d("{rc} onRequestClear(" + currentAccount + ", " + requestToken + ", " + cancelled + "): " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        }
    }

    public static void onRequestComplete(int currentAccount, int requestToken, long response, int errorCode, String errorText, int networkType, long timestamp, long requestMsgId, int dcId) {
        ConnectionsManager connectionsManager = getInstance(currentAccount);
        if (connectionsManager == null) return;
        RequestCallbacks callbacks = connectionsManager.requestCallbacks.get(requestToken);
        connectionsManager.requestCallbacks.remove(requestToken);
        if (callbacks != null) {
            if (callbacks.onComplete != null) {
                callbacks.onComplete.run(response, errorCode, errorText, networkType, timestamp, requestMsgId, dcId);
            }
//            FileLog.d("{rc} onRequestComplete(" + currentAccount + ", " + requestToken + "): found request " + requestToken + ", " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        } else {
//            FileLog.d("{rc} onRequestComplete(" + currentAccount + ", " + requestToken + "): not found request " + requestToken + "! " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        }
    }

    public static void onRequestQuickAck(int currentAccount, int requestToken) {
        ConnectionsManager connectionsManager = getInstance(currentAccount);
        if (connectionsManager == null) return;
        RequestCallbacks callbacks = connectionsManager.requestCallbacks.get(requestToken);
        if (callbacks != null) {
            if (callbacks.onQuickAck != null) {
                callbacks.onQuickAck.run();
            }
//            FileLog.d("{rc} onRequestQuickAck(" + currentAccount + ", " + requestToken + "): found request " + requestToken + ", " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        } else {
//            FileLog.d("{rc} onRequestQuickAck(" + currentAccount + ", " + requestToken + "): not found request " + requestToken + "! " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        }
    }

    public static void onRequestWriteToSocket(int currentAccount, int requestToken) {
        ConnectionsManager connectionsManager = getInstance(currentAccount);
        if (connectionsManager == null) return;
        RequestCallbacks callbacks = connectionsManager.requestCallbacks.get(requestToken);
        if (callbacks != null) {
            if (callbacks.onWriteToSocket != null) {
                callbacks.onWriteToSocket.run();
            }
//            FileLog.d("{rc} onRequestWriteToSocket(" + currentAccount + ", " + requestToken + "): found request " + requestToken + ", " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        } else {
//            FileLog.d("{rc} onRequestWriteToSocket(" + currentAccount + ", " + requestToken + "): not found request " + requestToken + "! " + connectionsManager.requestCallbacks.size() + " requests' callbacks");
        }
    }

    public void cancelRequest(int token, boolean notifyServer) {
        cancelRequest(token, notifyServer, null);
    }

    public void cancelRequest(int token, boolean notifyServer, Runnable onCancelled) {
        Utilities.stageQueue.postRunnable(() -> {
            if (onCancelled != null) {
                listenCancel(token, () -> {
                    Utilities.stageQueue.postRunnable(onCancelled);
                });
            }
            native_cancelRequest(currentAccount, token, notifyServer);
        });
    }

    public void cleanup(boolean resetKeys) {
        native_cleanUp(currentAccount, resetKeys);
    }

    public void cancelRequestsForGuid(int guid) {
        Utilities.stageQueue.postRunnable(() -> {
            native_cancelRequestsForGuid(currentAccount, guid);
        });
    }

    public void bindRequestToGuid(int requestToken, int guid) {
        if (guid == 0) {
            return;
        }
        native_bindRequestToGuid(currentAccount, requestToken, guid);
    }

    public void applyDatacenterAddress(int datacenterId, String ipAddress, int port) {
        native_applyDatacenterAddress(currentAccount, datacenterId, ipAddress, port);
    }

    public int getConnectionState() {
        if (connectionState == ConnectionStateConnected && isUpdating) {
            return ConnectionStateUpdating;
        }
        return connectionState;
    }

    public void setUserId(long id) {
        native_setUserId(currentAccount, id);
    }

    public void checkConnection() {
        byte selectedStrategy = getIpStrategy();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("selected ip strategy " + selectedStrategy);
        }
        native_setIpStrategy(currentAccount, selectedStrategy);
        native_setWssTransportEnabled(currentAccount, isWssTransportActive());
        native_setNetworkAvailable(currentAccount, ApplicationLoader.isNetworkOnline(), ApplicationLoader.getCurrentNetworkType(), ApplicationLoader.isConnectionSlow());
    }

    public void setPushConnectionEnabled(boolean value) {
        native_setPushConnectionEnabled(currentAccount, value);
    }

    public void setLivePingInterval(int intervalMs) {
        native_setLivePingInterval(currentAccount, intervalMs);
    }

    public void init(int version, int layer, int apiId, String deviceModel, String systemVersion, String appVersion, String langCode, String systemLangCode, String configPath, String logPath, String regId, String cFingerprint, int timezoneOffset, long userId, boolean userPremium, boolean enablePushConnection) {
        final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        final ProxySettings proxySettings = ProxySettings.fromSharedPreferences(preferences);
        String proxyAddress = proxySettings.getAddress();
        String proxyUsername = proxySettings.getUser();
        String proxyPassword = proxySettings.getPassword();
        String proxySecret = proxySettings.getSecret();
        int proxyPort = proxySettings.getPort();

        final boolean legacyProxyEnabled = preferences.getBoolean("proxy_enabled", false) && proxySettings.isValid();
        if (legacyProxyEnabled) {
            int activationGeneration = ProxyRuntimeStateStore.noteProxyStartupRestoreActivation(currentAccount);
            if (proxySettings.getType() == ProxySettings.Type.WEB) {
                // WEB proxy: the browser bridge carries a plain MTProxy stream, so the
                // native side must see an ordinary obfuscated2 proxy on loopback with
                // the proxy's own secret and every ZaStoGram stealth mode disabled;
                // webBridge() also exempts the loopback bridge from MTProxy pacing.
                int localPort = WebProxyTransport.start(proxyAddress, proxySecret);
                native_setProxySettings(currentAccount, "127.0.0.1", localPort != 0 ? localPort : 9, "", "", proxySecret, MtProxyOptions.webBridge(), activationGeneration, ProxyConnectionEvent.Origin.STARTUP_RESTORE.wireName);
            } else {
                native_setProxySettings(currentAccount, proxyAddress, proxyPort, proxyUsername, proxyPassword, proxySecret, MtProxyOptions.resolve(proxyAddress, proxyPort, proxySecret), activationGeneration, ProxyConnectionEvent.Origin.STARTUP_RESTORE.wireName);
            }
        }
        applyWssTransport(legacyProxyEnabled);
        String installer = "";
        try {
            Context context = ApplicationLoader.applicationContext;
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo installSourceInfo = context.getPackageManager().getInstallSourceInfo(context.getPackageName());
                if (installSourceInfo != null) {
                    installer = installSourceInfo.getInitiatingPackageName();
                    if (installer == null) {
                        installer = installSourceInfo.getInstallingPackageName();
                    }
                }
            } else {
                installer = context.getPackageManager().getInstallerPackageName(context.getPackageName());
            }
        } catch (Throwable ignore) {

        }
        if (installer == null) {
            installer = "";
        }
        String packageId = "";
        try {
            packageId = ApplicationLoader.applicationContext.getPackageName();
        } catch (Throwable ignore) {

        }
        if (packageId == null) {
            packageId = "";
        }

        native_init(currentAccount, version, layer, apiId, deviceModel, systemVersion, appVersion, langCode, systemLangCode, configPath, logPath, regId, cFingerprint, installer, packageId, timezoneOffset, userId, userPremium, enablePushConnection, ApplicationLoader.isNetworkOnline(), ApplicationLoader.getCurrentNetworkType(), SharedConfig.measureDevicePerformanceClass());
        // native_init stores the flag but does not create the dedicated type-8
        // push connection. Apply it explicitly so service-only cold starts can
        // receive updates without waiting for an Activity or settings change.
        setPushConnectionEnabled(enablePushConnection);
        // Native initialization deliberately starts paused. Re-apply the persisted
        // policy here as every account is created, including cold starts which do
        // not have an Activity.onResume() callback to cancel the sleep timer.
        applyBackgroundNetworkPolicy();
        checkConnection();
    }

    public static void setLangCode(String langCode) {
        langCode = langCode.replace('_', '-').toLowerCase();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            native_setLangCode(a, langCode);
        }
    }

    public static void setRegId(String regId, @PushListenerController.PushType int type, String status) {
        String pushString = regId;
        if (!TextUtils.isEmpty(pushString) && type == PushListenerController.PUSH_TYPE_HUAWEI) {
            pushString = "huawei://" + pushString;
        }
        if (TextUtils.isEmpty(pushString) && !TextUtils.isEmpty(status)) {
            pushString = status;
        }
        if (TextUtils.isEmpty(pushString)) {
            String tag = type == PushListenerController.PUSH_TYPE_FIREBASE ? "FIREBASE" : type == PushListenerController.PUSH_TYPE_SIMPLE ? "UNIFIEDPUSH" : "HUAWEI";
            pushString = SharedConfig.pushStringStatus = "__" + tag + "_GENERATING_SINCE_" + getInstance(0).getCurrentTime() + "__";
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            native_setRegId(a, pushString);
        }
    }

    private static boolean isMtProxySoftMuxEnabled() {
        if (!SharedConfig.mtProxySoftMux) {
            return false;
        }
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        if (!preferences.getBoolean("proxy_enabled", false)) {
            return false;
        }
        // Soft mux is an MTProto-proxy policy; a WEB proxy keeps stock Telegram behaviour.
        ProxySettings settings = ProxySettings.fromSharedPreferences(preferences);
        return settings.getType() == ProxySettings.Type.MTPROTO
                && !TextUtils.isEmpty(settings.getAddress())
                && !TextUtils.isEmpty(settings.getSecret());
    }

    public static int getMtProxySoftMuxDownloadConnectionType(int requestIndex) {
        if (isMtProxySoftMuxEnabled()) {
            return ConnectionTypeDownload;
        }
        return (requestIndex & 1) == 0 ? ConnectionTypeDownload : ConnectionTypeDownload2;
    }

    public static int getMtProxySoftMuxUploadConnectionType(int requestIndex) {
        if (isMtProxySoftMuxEnabled()) {
            return ConnectionTypeUpload;
        }
        // Through a WEB proxy too: the carrier's adaptive upload window, not
        // the number of upload connections, bounds what waits in front of a
        // chat request (WebProxyEngine).
        return ConnectionTypeUpload | ((requestIndex % 4) << 16);
    }

    // Called by tgnet on its network thread right after a connect() to the WEB
    // proxy's loopback bridge, so the bridge can schedule the stream by class.
    public static void onWebProxyStreamOpened(int bridgePort, int localPort, int streamClass) {
        try {
            WebProxyTransport.registerLocalStream(bridgePort, localPort, streamClass);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // Called by tgnet on its network thread when a WEB bridge connection saw
    // no data for its receive timeout; see WebProxyFlow.decideReceiveWait.
    public static long webProxyReceiveWait(int bridgePort, int localPort, long waitStartedAt) {
        try {
            return WebProxyTransport.receiveWait(bridgePort, localPort, waitStartedAt);
        } catch (Throwable e) {
            FileLog.e(e);
            return -WebProxyFlow.REASON_CARRIER_DOWN;
        }
    }

    public static void setSystemLangCode(String langCode) {
        langCode = langCode.replace('_', '-').toLowerCase();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            native_setSystemLangCode(a, langCode);
        }
    }

    public void switchBackend(boolean restart) {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        preferences.edit().remove("language_showed2").commit();
        native_switchBackend(currentAccount, restart);
    }

    public boolean isTestBackend() {
        return native_isTestBackend(currentAccount) != 0;
    }

    public void resumeNetworkMaybe() {
        native_resumeNetwork(currentAccount, true);
    }

    public void updateDcSettings() {
        native_updateDcSettings(currentAccount);
    }

    public void setDefaultDatacenterId(int dcId) {
        native_moveDatacenter(currentAccount, dcId);
    }

    public long getPauseTime() {
        return lastPauseTime;
    }

    private static final AtomicLong webProxyCheckIds = new AtomicLong();

    public long checkProxy(ProxySettings settings, RequestTimeDelegate requestTimeDelegate) {
        if (settings == null || !settings.isValid()) {
            return 0;
        }
        if (settings.getType() == ProxySettings.Type.WEB) {
            WebProxyConnectionTester.getInstance().checkProxy(settings, requestTimeDelegate, this::checkWebProxyInternal);
            return webProxyCheckIds.decrementAndGet();
        }
        String address = settings.getAddress();
        int port = settings.getPort();
        String username = settings.getUser();
        String password = settings.getPassword();
        String secret = settings.getSecret();
        return native_checkProxy(currentAccount, address, port, username, password, secret, MtProxyOptions.resolve(address, port, secret), requestTimeDelegate);
    }

    private void checkWebProxyInternal(ProxySettings settings, int port, RequestTimeDelegate requestTimeDelegate) {
        native_checkProxy(currentAccount, "127.0.0.1", port, "", "", settings.getSecret(), MtProxyOptions.webBridge(), requestTimeDelegate);
    }

    public void cancelProxyCheck(long pingId) {
        // WEB proxy checks use negative ids: they run in WebProxyConnectionTester
        // and have no native ping to cancel.
        if (pingId > 0) {
            native_cancelProxyCheck(currentAccount, pingId);
        }
    }

    public static int cancelProxyEndpointAttempts(String endpointKey, String reason) {
        return cancelProxyEndpointAttempts(endpointKey, "", reason);
    }

    public static int cancelProxyEndpointAttempts(String endpointKey, String probeKey, String reason) {
        if (TextUtils.isEmpty(endpointKey) && TextUtils.isEmpty(probeKey)) {
            return 0;
        }
        String nativeReason = TextUtils.isEmpty(reason) ? "unknown" : reason;
        String nativeProbeKey = TextUtils.isEmpty(probeKey) ? "" : probeKey;
        int requested = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            native_cancelProxyEndpointAttempts(a, endpointKey, nativeProbeKey, nativeReason);
            requested++;
        }
        return requested;
    }

    public static int cancelProxyEndpointAttemptsForAllAccounts(String endpointKey, String reason) {
        return cancelProxyEndpointAttempts(endpointKey, reason);
    }

    public void setAppPaused(final boolean value, final boolean byScreenState) {
        if (!byScreenState) {
            appPaused = value;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("app paused = " + value);
            }
            if (value) {
                appResumeCount--;
            } else {
                appResumeCount++;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("app resume count " + appResumeCount);
            }
            if (appResumeCount < 0) {
                appResumeCount = 0;
            }
        }
        if (appResumeCount == 0) {
            applyBackgroundNetworkPolicy();
        } else {
            if (appPaused) {
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("reset app pause time");
            }
            if (lastPauseTime != 0 && System.currentTimeMillis() - lastPauseTime > 5000) {
                getContactsController().checkContacts();
            }
            lastPauseTime = 0;
            native_resumeNetwork(currentAccount, false);
        }
    }

    public void applyBackgroundNetworkPolicy() {
        if (appResumeCount != 0) {
            return;
        }
        if (isBackgroundNetworkAlwaysOn()) {
            lastPauseTime = 0;
            native_resumeNetwork(currentAccount, false);
            return;
        }
        if (lastPauseTime == 0) {
            lastPauseTime = System.currentTimeMillis();
        }
        native_pauseNetwork(currentAccount);
    }

    public static void applyBackgroundNetworkPolicyForAllAccounts() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                getInstance(a).applyBackgroundNetworkPolicy();
            }
        }
    }

    public static void onUnparsedMessageReceived(long address, final int currentAccount, long messageId) {
        try {
            NativeByteBuffer buff = NativeByteBuffer.wrap(address);
            buff.setDataSourceType(TLDataSourceType.NETWORK);
            buff.reused = true;
            int constructor = buff.readInt32(true);
            final TLObject message = TLClassStore.Instance().TLdeserialize(buff, constructor, true);
            FileLog.dumpUnparsedMessage(message, messageId, currentAccount);
            if (message instanceof TLRPC.Updates) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("java received " + message);
                }
                KeepAliveJob.finishJob();
                Utilities.stageQueue.postRunnable(() -> AccountInstance.getInstance(currentAccount).getMessagesController().processUpdates((TLRPC.Updates) message, false));
            } else {
                logTlDebugUnmappedConstructor(constructor);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onUpdate(final int currentAccount) {
        Utilities.stageQueue.postRunnable(() -> AccountInstance.getInstance(currentAccount).getMessagesController().updateTimerProc());
    }

    public static void onSessionCreated(final int currentAccount) {
        Utilities.stageQueue.postRunnable(() -> AccountInstance.getInstance(currentAccount).getMessagesController().getDifference());
    }

    public static void onConnectionStateChanged(final int state, final int currentAccount) {
        AndroidUtilities.runOnUIThread(() -> {
            getInstance(currentAccount).connectionState = state;
            ProxyWssFallback.onConnectionState(currentAccount, state);
            AccountInstance.getInstance(currentAccount).getNotificationCenter().postNotificationName(NotificationCenter.didUpdateConnectionState);
        });
    }

    public static void onProxyConnectionStageChanged(final int currentAccount, final String diagnostic, final String endpointKey) {
        onProxyConnectionStageChanged(currentAccount, diagnostic, endpointKey, ProxyConnectionEvent.Origin.ACTIVE_SOCKET.wireName);
    }

    public static void onProxyConnectionStageChanged(final int currentAccount, final String diagnostic, final String endpointKey, final String origin) {
        onProxyConnectionStageChanged(currentAccount, diagnostic, endpointKey, "", origin);
    }

    // UI-thread-confined (every write happens in processProxyConnectionStage): last logged
    // proxy_connection_stage per account, used to log only on an actual stage transition.
    private static final java.util.HashMap<Integer, String> lastLoggedProxyStage = new java.util.HashMap<>();
    private static final java.util.HashMap<Integer, String> lastLoggedProxyDiagnosis = new java.util.HashMap<>();

    // Native MTProxy sockets can publish thousands of stage callbacks per second while the first
    // control/media connections fan out or reconnect. Posting one main-looper Runnable per callback
    // starves input and rendering before the reducer gets a chance to discard telemetry-only events.
    // Keep only the newest copy of an identical semantic event and give the UI looper a frame between
    // bounded batches. remove+put keeps the map ordered by the latest occurrence, which preserves the
    // final success/failure ordering when different phases alternate during a reconnect storm.
    private static final Object proxyStageDispatchLock = new Object();
    private static final LinkedHashMap<String, ProxyConnectionEvent> pendingProxyStageEvents = new LinkedHashMap<>();
    private static final int MAX_PENDING_PROXY_STAGE_EVENTS = 256;
    private static final int MAX_PROXY_STAGE_EVENTS_PER_FRAME = 12;
    private static final long PROXY_STAGE_NEXT_BATCH_DELAY_MS = 16L;
    private static boolean proxyStageDrainScheduled;

    public static void onProxyConnectionStageChanged(final int currentAccount, final String diagnostic, final String endpointKey, final String probeKey, final String origin) {
        onProxyConnectionStageChanged(currentAccount, diagnostic, endpointKey, probeKey, origin, 0);
    }

    public static void onProxyConnectionStageChanged(final int currentAccount, final String diagnostic, final String endpointKey, final String probeKey, final String origin, final int activationGeneration) {
        onProxyConnectionStageChanged(currentAccount, diagnostic, endpointKey, probeKey, origin, "", activationGeneration, 0);
    }

    public static void onProxyConnectionStageChanged(final int currentAccount, final String diagnostic, final String endpointKey, final String probeKey, final String origin, final String socketRole, final int activationGeneration) {
        onProxyConnectionStageChanged(currentAccount, diagnostic, endpointKey, probeKey, origin, socketRole, activationGeneration, 0);
    }

    // Native callback (TgNetWrapper): suggestedHoldMs is the native retry
    // authority's clock (endpoint cooldown / probe coordinator hold) riding
    // along with the event, so the Java layer never re-derives hold windows.
    public static void onProxyConnectionStageChanged(final int currentAccount, final String diagnostic, final String endpointKey, final String probeKey, final String origin, final String socketRole, final int activationGeneration, final int suggestedHoldMs) {
        if (!SharedConfig.isProxyEnabled()) {
            return;
        }
        ProxyConnectionEvent event = ProxyConnectionEvent.nativeStage(currentAccount, diagnostic, endpointKey, probeKey, origin, socketRole, activationGeneration, suggestedHoldMs, android.os.SystemClock.elapsedRealtime());
        enqueueProxyConnectionStage(event);
    }

    private static void enqueueProxyConnectionStage(ProxyConnectionEvent event) {
        boolean scheduleDrain = false;
        synchronized (proxyStageDispatchLock) {
            String key = proxyStageEventKey(event);
            pendingProxyStageEvents.remove(key);
            pendingProxyStageEvents.put(key, event);
            if (pendingProxyStageEvents.size() > MAX_PENDING_PROXY_STAGE_EVENTS) {
                Iterator<Map.Entry<String, ProxyConnectionEvent>> iterator = pendingProxyStageEvents.entrySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            if (!proxyStageDrainScheduled) {
                proxyStageDrainScheduled = true;
                scheduleDrain = true;
            }
        }
        if (scheduleDrain) {
            AndroidUtilities.runOnUIThread(ConnectionsManager::drainProxyConnectionStages);
        }
    }

    private static String proxyStageEventKey(ProxyConnectionEvent event) {
        return event.account + "|" + event.phase + "|" + event.endpointKey + "|" + event.probeKey + "|"
                + event.origin.wireName + "|" + event.socketRole.wireName + "|" + event.activationGeneration;
    }

    private static void drainProxyConnectionStages() {
        ArrayList<ProxyConnectionEvent> batch = new ArrayList<>(MAX_PROXY_STAGE_EVENTS_PER_FRAME);
        synchronized (proxyStageDispatchLock) {
            Iterator<Map.Entry<String, ProxyConnectionEvent>> iterator = pendingProxyStageEvents.entrySet().iterator();
            while (iterator.hasNext() && batch.size() < MAX_PROXY_STAGE_EVENTS_PER_FRAME) {
                batch.add(iterator.next().getValue());
                iterator.remove();
            }
        }
        for (int i = 0; i < batch.size(); i++) {
            processProxyConnectionStage(batch.get(i));
        }
        boolean hasMore;
        synchronized (proxyStageDispatchLock) {
            hasMore = !pendingProxyStageEvents.isEmpty();
            if (!hasMore) {
                proxyStageDrainScheduled = false;
            }
        }
        if (hasMore) {
            AndroidUtilities.runOnUIThread(ConnectionsManager::drainProxyConnectionStages, PROXY_STAGE_NEXT_BATCH_DELAY_MS);
        }
    }

    private static void processProxyConnectionStage(ProxyConnectionEvent event) {
        if (!SharedConfig.isProxyEnabled()) {
            return;
        }
        int currentAccount = event.account;
        String endpointKey = event.endpointKey;
        ProxyRuntimeStateStore.Decision decision = ProxyRuntimeStateStore.onNativeStage(event);
        String normalizedDiagnostic = event.phase;
        if (BuildVars.LOGS_ENABLED && decision != null) {
            long lastSuccessAgeMs = ProxyRuntimeStateStore.lastUsableSuccessAgeMs(SharedConfig.currentProxy, event.timestamp);
            String diagnosisKey = normalizedDiagnostic + "|" + endpointKey + "|" + event.origin.wireName + "|" + event.socketRole.wireName + "|" + event.probeKey + "|" + event.activationGeneration + "|" + decision.decision + "|" + decision.visibleChanged + "|" + decision.rotationTrigger;
            if (!diagnosisKey.equals(lastLoggedProxyDiagnosis.get(currentAccount))) {
                lastLoggedProxyDiagnosis.put(currentAccount, diagnosisKey);
                FileLog.d("proxy_diagnosis owner=ConnectionsManager.onProxyConnectionStageChanged account=" + currentAccount + " origin=" + event.origin.wireName + " role=" + event.socketRole.wireName + " phase=" + normalizedDiagnostic + " layer=" + decision.verdict.layer + " failure_class=" + decision.verdict.failureClass + " action=" + decision.verdict.action + " decision=" + decision.decision + " endpoint=" + endpointKey + " probe=" + event.probeKey + " activation_generation=" + event.activationGeneration + " last_success_age_ms=" + lastSuccessAgeMs + " visible_changed=" + (decision.visibleChanged ? 1 : 0) + " rotation_trigger=" + (decision.rotationTrigger ? 1 : 0) + " shadowed=" + (decision.shadowed ? 1 : 0));
            }
        }
        if (!shouldNotifyProxyConnectionStage(decision)) {
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            String stageKey = normalizedDiagnostic + "|" + endpointKey + "|" + event.origin.wireName + "|" + event.socketRole.wireName + "|" + event.probeKey + "|" + event.activationGeneration;
            if (!stageKey.equals(lastLoggedProxyStage.get(currentAccount))) {
                lastLoggedProxyStage.put(currentAccount, stageKey);
                FileLog.d("proxy_connection_stage owner=ConnectionsManager.onProxyConnectionStageChanged account=" + currentAccount + " origin=" + event.origin.wireName + " role=" + event.socketRole.wireName + " phase=" + normalizedDiagnostic + " endpoint=" + endpointKey + " probe=" + event.probeKey + " activation_generation=" + event.activationGeneration);
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyConnectionStageChanged, normalizedDiagnostic, endpointKey, event.origin.wireName, event.activationGeneration, event.socketRole.wireName, decision.decision, decision.rotationTrigger ? 1 : 0);
        AccountInstance.getInstance(currentAccount).getNotificationCenter().postNotificationName(NotificationCenter.proxyConnectionStageChanged, normalizedDiagnostic, endpointKey, event.origin.wireName, event.activationGeneration, event.socketRole.wireName, decision.decision, decision.rotationTrigger ? 1 : 0);
    }

    private static boolean shouldNotifyProxyConnectionStage(ProxyRuntimeStateStore.Decision decision) {
        return decision != null && (decision.visibleChanged || decision.rotationTrigger);
    }

    public static void onLogout(final int currentAccount) {
        AndroidUtilities.runOnUIThread(() -> {
            AccountInstance accountInstance = AccountInstance.getInstance(currentAccount);
            if (accountInstance.getUserConfig().getClientUserId() != 0) {
                accountInstance.getUserConfig().clearConfig();
                accountInstance.getMessagesController().performLogout(0);
            }
        });
    }

    public static int getInitFlags() {
        int flags = 0;
        EmuDetector detector = EmuDetector.with(ApplicationLoader.applicationContext);
        if (detector.detect()) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("detected emu");
            }
            flags |= 1024;
        }
        return flags;
    }

    public static void onBytesSent(int amount, int networkType, final int currentAccount) {
        try {
            AccountInstance.getInstance(currentAccount).getStatsController().incrementSentBytesCount(networkType, StatsController.TYPE_TOTAL, amount);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onRequestNewServerIpAndPort(final int second, final int currentAccount) {
        Utilities.globalQueue.postRunnable(() -> {
            boolean networkOnline = ApplicationLoader.isNetworkOnline();
            Utilities.stageQueue.postRunnable(() -> {
                FileLog.d("13. currentTask == " + currentTask);
                if (currentTask != null || second == 0 && Math.abs(lastDnsRequestTime - System.currentTimeMillis()) < 10000 || !networkOnline) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("don't start task, current task = " + currentTask + " next task = " + second + " time diff = " + Math.abs(lastDnsRequestTime - System.currentTimeMillis()) + " network = " + ApplicationLoader.isNetworkOnline());
                    }
                    return;
                }
                lastDnsRequestTime = System.currentTimeMillis();
                if (second == 2) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start cloudflare txt task");
                    }
                    CloudflareDnsLoadTask task = new CloudflareDnsLoadTask(currentAccount);
                    task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null, null, null);
                    FileLog.d("9. currentTask = cloudflare");
                    currentTask = task;
                } else {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start google txt task");
                    }
                    GoogleDnsLoadTask task = new GoogleDnsLoadTask(currentAccount);
                    task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null, null, null);
                    FileLog.d("11. currentTask = dnstxt");
                    currentTask = task;
                }
            });
        });
    }

    public static void onProxyError() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needShowAlert, 3));
    }

    private static String normalizeDnsCacheHost(String hostName) {
        if (TextUtils.isEmpty(hostName)) {
            return "";
        }
        return hostName.trim().toLowerCase(Locale.US);
    }

    private static NegativeDnsCacheEntry readNegativeDnsCache(String hostName, long now) {
        String key = normalizeDnsCacheHost(hostName);
        if (key.length() == 0) {
            return null;
        }
        synchronized (negativeDnsCache) {
            NegativeDnsCacheEntry entry = negativeDnsCache.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.isFresh(now)) {
                return entry;
            }
            negativeDnsCache.remove(key);
            return null;
        }
    }

    private static void recordNegativeDnsCache(String hostName, String reason) {
        String key = normalizeDnsCacheHost(hostName);
        if (key.length() == 0) {
            return;
        }
        String safeReason = TextUtils.isEmpty(reason) ? DNS_REASON_ALL_RESOLVERS_FAILED : reason;
        long now = SystemClock.elapsedRealtime();
        synchronized (negativeDnsCache) {
            negativeDnsCache.put(key, new NegativeDnsCacheEntry(now + HOST_RESOLVER_NEGATIVE_TTL_MS, safeReason));
        }
        FileLog.d("dns_resolver negative_cache_store host=" + key + " reason=" + safeReason + " ttl_ms=" + HOST_RESOLVER_NEGATIVE_TTL_MS);
    }

    private static void clearNegativeDnsCache(String hostName) {
        String key = normalizeDnsCacheHost(hostName);
        if (key.length() == 0) {
            return;
        }
        synchronized (negativeDnsCache) {
            negativeDnsCache.remove(key);
        }
    }

    private static void logNegativeDnsCacheHit(String hostName, NegativeDnsCacheEntry entry, long now) {
        String key = normalizeDnsCacheHost(hostName);
        long ttl = Math.max(0, entry.expiresAtMs - now);
        FileLog.d("dns_resolver negative_cache_hit host=" + key + " reason=" + entry.reason + " ttl_ms=" + ttl);
        ProxyRuntimeStateStore.recordDnsNegativeCacheHit(hostName, entry.reason);
    }

    public static boolean isHostResolveNegativeCached(String hostName) {
        long now = SystemClock.elapsedRealtime();
        NegativeDnsCacheEntry entry = readNegativeDnsCache(hostName, now);
        if (entry == null) {
            return false;
        }
        logNegativeDnsCacheHit(hostName, entry, now);
        return true;
    }

    public static void getHostByName(String hostName, long address) {
        AndroidUtilities.runOnUIThread(() -> {
            long now = SystemClock.elapsedRealtime();
            ResolvedDomain resolvedDomain;
            synchronized (dnsCache) {
                resolvedDomain = dnsCache.get(hostName);
            }
            if (resolvedDomain != null && resolvedDomain.isFresh(now)) {
                String cachedAddress = resolvedDomain.getAddress();
                if (isBlockedZeroAddress(cachedAddress)) {
                    synchronized (dnsCache) {
                        dnsCache.remove(hostName);
                    }
                    native_onHostNameResolved(hostName, address, DNS_BLOCKED_ZERO_NATIVE_SENTINEL);
                    return;
                }
                clearNegativeDnsCache(hostName);
                logDnsResult("cache", "success", hostName, resolvedDomain);
                ProxyRuntimeStateStore.recordDnsResolveSuccess(hostName, "cache");
                native_onHostNameResolved(hostName, address, cachedAddress);
            } else {
                NegativeDnsCacheEntry negativeEntry = readNegativeDnsCache(hostName, now);
                if (negativeEntry != null) {
                    logNegativeDnsCacheHit(hostName, negativeEntry, now);
                    native_onHostNameResolved(hostName, address, DNS_NEGATIVE_CACHE_NATIVE_SENTINEL);
                    return;
                }
                ResolveHostByNameTask task = resolvingHostnameTasks.get(hostName);
                if (task == null) {
                    task = new ResolveHostByNameTask(hostName);
                    task.addAddress(address);
                    resolvingHostnameTasks.put(hostName, task);
                    try {
                        task.executeOnExecutor(DNS_THREAD_POOL_EXECUTOR, null, null, null);
                    } catch (Throwable e) {
                        resolvingHostnameTasks.remove(hostName);
                        FileLog.e(e);
                        native_onHostNameResolved(hostName, address, "");
                        return;
                    }
                } else {
                    task.addAddress(address);
                }
            }
        });
    }

    public static void onBytesReceived(int amount, int networkType, final int currentAccount) {
        try {
            StatsController.getInstance(currentAccount).incrementReceivedBytesCount(networkType, StatsController.TYPE_TOTAL, amount);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onUpdateConfig(long address, final int currentAccount) {
        try {
            NativeByteBuffer buff = NativeByteBuffer.wrap(address);
            buff.reused = true;
            final TLRPC.TL_config message = TLRPC.TL_config.TLdeserialize(buff, buff.readInt32(true), true);
            if (message != null) {
                Utilities.stageQueue.postRunnable(() -> AccountInstance.getInstance(currentAccount).getMessagesController().updateConfig(message));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void onInternalPushReceived(final int currentAccount) {
        KeepAliveJob.startJob();
    }

    public static void setProxySettings(boolean enabled, ProxySettings settings) {
        setProxySettings(enabled, settings, ProxyConnectionEvent.Origin.SETTINGS_CHANGE);
    }

    public static void setProxySettings(boolean enabled, ProxySettings settings, ProxyConnectionEvent.Origin origin) {
        String address = "";
        int port = 0;
        String username = "";
        String password = "";
        String secret = "";
        boolean webProxy = false;
        ProxyWssFallback.onProxySettingsApplied();

        if (enabled && settings != null && settings.isValid()) {
            address = settings.getAddress();
            port = settings.getPort();
            username = settings.getUser();
            password = settings.getPassword();
            secret = settings.getSecret();

            if (settings.getType() == ProxySettings.Type.WEB) {
                int localPort = WebProxyTransport.start(address, secret);
                address = "127.0.0.1";
                port = localPort != 0 ? localPort : 9;
                username = "";
                password = "";
                webProxy = true;
            } else {
                WebProxyTransport.stop();
            }
        } else {
            WebProxyTransport.stop();
        }

        boolean hasSelectedProxy = enabled && !TextUtils.isEmpty(address);
        // The WSS toggle is a preference, not a mode: a selected proxy only
        // suspends it, and turning the proxy off falls back to WSS again.
        applyWssTransport(hasSelectedProxy);
        ProxyConnectionEvent.Origin activationOrigin = origin == null ? ProxyConnectionEvent.Origin.SETTINGS_CHANGE : origin;
        int activationGeneration = hasSelectedProxy ? ProxyRuntimeStateStore.noteProxySettingsActivation(activationOrigin) : 0;
        // WEB proxy keeps plain obfuscated2 to the local bridge: no FakeTLS, no
        // fragmentation, no pacing/cover modes on top of the browser carrier.
        // webBridge() also keeps loopback connects out of the MTProxy dial
        // queue, endpoint cooldown and reconnect backoff.
        MtProxyOptions enabledOptions = !hasSelectedProxy ? MtProxyOptions.disabled() : webProxy ? MtProxyOptions.webBridge() : MtProxyOptions.resolve(address, port, secret);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (hasSelectedProxy) {
                native_setProxySettings(a, address, port, username, password, secret, enabledOptions, activationGeneration, activationOrigin.wireName);
            } else {
                native_setProxySettings(a, "", 1080, "", "", "", MtProxyOptions.disabled(), activationGeneration, activationOrigin.wireName);
            }
            AccountInstance accountInstance = AccountInstance.getInstance(a);
            if (accountInstance.getUserConfig().isClientActivated()) {
                accountInstance.getMessagesController().checkPromoInfo(true);
            }
        }
    }

    public static void setWssTransportEnabled() {
        applyWssTransport(SharedConfig.isProxyEnabled() && !ProxyWssFallback.isEngaged());
    }

    // The proxy stays selected in settings; only the native route drops it while
    // ProxyWssFallback probes it in the background.
    public static void applyWssFallbackRoute() {
        WebProxyTransport.stop();
        applyWssTransport(false);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            native_setProxySettings(a, "", 1080, "", "", "", MtProxyOptions.disabled(), 0, ProxyConnectionEvent.Origin.SETTINGS_CHANGE.wireName);
        }
    }

    private static void applyWssTransport(boolean proxyActive) {
        boolean enabled = SharedConfig.wssTransportEnabled
                && !proxyActive
                && !ApplicationLoader.isVpnActive();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            native_setWssTransportEnabled(a, enabled);
        }
    }

    public static boolean isWssTransportActive() {
        return SharedConfig.wssTransportEnabled
                && (!SharedConfig.isProxyEnabled() || ProxyWssFallback.isEngaged())
                && !ApplicationLoader.isVpnActive();
    }

    public static boolean supportsCdnFileRedirects() {
        // Public Telegram WebSocket relays address the five regular DCs, not
        // the separate CDN DC ids. With WSS selected, ask the source DC to
        // serve the file through its media relay instead of redirecting it.
        return !isWssTransportActive();
    }

    static int resolveMtProxyClientHelloFragmentationMode() {
        return SharedConfig.mtProxyClientHelloFragmentation
                ? MT_PROXY_CLIENT_HELLO_FRAGMENTATION_SOFT
                : MT_PROXY_CLIENT_HELLO_FRAGMENTATION_OFF;
    }

    static int resolveMtProxyConnectionPatternMode() {
        int mode = SharedConfig.mtProxyConnectionPatternMode;
        if (mode >= MT_PROXY_CONNECTION_PATTERN_OFF && mode <= MT_PROXY_CONNECTION_PATTERN_BROWSER) {
            return mode;
        }
        return MT_PROXY_CONNECTION_PATTERN_OFF;
    }

    static int resolveMtProxyRecordSizingMode() {
        int mode = SharedConfig.mtProxyRecordSizingMode;
        if (mode >= MT_PROXY_RECORD_SIZING_OFF && mode <= MT_PROXY_RECORD_SIZING_VARIED) {
            return mode;
        }
        return MT_PROXY_RECORD_SIZING_OFF;
    }

    static int resolveMtProxyTimingMode() {
        int mode = SharedConfig.mtProxyTimingMode;
        if (mode >= MT_PROXY_TIMING_OFF && mode <= MT_PROXY_TIMING_BALANCED) {
            return mode;
        }
        return MT_PROXY_TIMING_OFF;
    }

    static int resolveMtProxyStartupCoverMode() {
        int mode = SharedConfig.mtProxyStartupCoverMode;
        if (mode >= MT_PROXY_STARTUP_COVER_OFF && mode <= MT_PROXY_STARTUP_COVER_STRICT) {
            return mode;
        }
        return MT_PROXY_STARTUP_COVER_OFF;
    }

    private static int normalizeMtProxyTlsProfileOverride(int profile) {
        if (profile == MT_PROXY_TLS_PROFILE_AUTO) {
            return MT_PROXY_TLS_PROFILE_AUTO;
        }
        if (profile == MT_PROXY_TLS_PROFILE_AUTO_ROTATE) {
            return MT_PROXY_TLS_PROFILE_AUTO_ROTATE;
        }
        if (profile == MT_PROXY_TLS_PROFILE_CHROME_MODERN
                || profile == MT_PROXY_TLS_PROFILE_ANDROID_CHROME) {
            return MT_PROXY_TLS_PROFILE_AUTO;
        }
        if (profile == MT_PROXY_TLS_PROFILE_FIREFOX
                || profile == MT_PROXY_TLS_PROFILE_YANDEX
                || profile == MT_PROXY_TLS_PROFILE_FIREFOX_ANDROID
                || profile == MT_PROXY_TLS_PROFILE_ANDROID_OKHTTP) {
            return profile;
        }
        return MT_PROXY_TLS_PROFILE_AUTO;
    }

    public static int getMtProxyTlsProfileOverride() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(MT_PROXY_TLS_PROFILE_PREFS, Context.MODE_PRIVATE);
        return normalizeMtProxyTlsProfileOverride(preferences.getInt(MT_PROXY_TLS_PROFILE_OVERRIDE, MT_PROXY_TLS_PROFILE_AUTO));
    }

    public static void setMtProxyTlsProfileOverride(int profile) {
        profile = normalizeMtProxyTlsProfileOverride(profile);
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(MT_PROXY_TLS_PROFILE_PREFS, Context.MODE_PRIVATE);
        preferences.edit().putInt(MT_PROXY_TLS_PROFILE_OVERRIDE, profile).apply();
    }

    static int resolveMtProxyTlsProfile(String address, int port, String secret) {
        int override = getMtProxyTlsProfileOverride();
        if (override != MT_PROXY_TLS_PROFILE_AUTO) {
            return override;
        }
        // Keep Android on the same measured-safe default as tdesktop. Exact
        // Chromium-shaped profiles remain available as legacy IDs only and
        // are withheld by the native wire policy as a second line of defence.
        return MT_PROXY_TLS_PROFILE_YANDEX;
    }

    public static native void native_switchBackend(int currentAccount, boolean restart);
    public static native int native_isTestBackend(int currentAccount);
    public static native void native_pauseNetwork(int currentAccount);
    public static native void native_setIpStrategy(int currentAccount, byte value);
    public static native void native_updateDcSettings(int currentAccount);
    public static native void native_moveDatacenter(int currentAccount, int datacenterId);
    public static native void native_setNetworkAvailable(int currentAccount, boolean value, int networkType, boolean slow);
    public static native void native_resumeNetwork(int currentAccount, boolean partial);
    public static native void native_setProxyActivationContext(int currentAccount, int activationGeneration, String activationOrigin);
    public static native long native_getCurrentTimeMillis(int currentAccount);
    public static native int native_getCurrentTime(int currentAccount);
    public static native int native_getCurrentPingTime(int currentAccount);
    public static native int native_getCurrentDatacenterId(int currentAccount);
    public static native String native_getDatacenterConnectionDiagnostics(int currentAccount, int datacenterId);
    public static native long native_getCurrentAuthKeyId(int currentAccount);
    public static native int native_getTimeDifference(int currentAccount);
    public static native void native_sendRequest(int currentAccount, long object, int flags, int datacenterId, int connectionType, boolean immediate, int requestToken);
    public static native void native_cancelRequest(int currentAccount, int token, boolean notifyServer);
    public static native void native_cleanUp(int currentAccount, boolean resetKeys);
    public static native void native_cancelRequestsForGuid(int currentAccount, int guid);
    public static native void native_bindRequestToGuid(int currentAccount, int requestToken, int guid);
    public static native void native_applyDatacenterAddress(int currentAccount, int datacenterId, String ipAddress, int port);
    public static native int native_getConnectionState(int currentAccount);
    public static native void native_setUserId(int currentAccount, long id);
    public static native void native_init(int currentAccount, int version, int layer, int apiId, String deviceModel, String systemVersion, String appVersion, String langCode, String systemLangCode, String configPath, String logPath, String regId, String cFingerprint, String installer, String packageId, int timezoneOffset, long userId, boolean userPremium, boolean enablePushConnection, boolean hasNetwork, int networkType, int performanceClass);
    public static native void native_setProxySettings(int currentAccount, String address, int port, String username, String password, String secret, MtProxyOptions options, int activationGeneration, String activationOrigin);
    public static native void native_setWssTransportEnabled(int currentAccount, boolean enabled);
    public static native boolean native_isDatacenterTunneled(int currentAccount, int datacenterId, boolean media);
    public static native void native_setLangCode(int currentAccount, String langCode);
    public static native void native_setRegId(int currentAccount, String regId);
    public static native void native_setSystemLangCode(int currentAccount, String langCode);
    public static native void native_setJava(boolean useJavaByteBuffers);
    public static native void native_setPushConnectionEnabled(int currentAccount, boolean value);
    public static native void native_setLivePingInterval(int currentAccount, int intervalMs);
    public static native void native_applyDnsConfig(int currentAccount, long address, String phone, int date);
    public static native long native_checkProxy(int currentAccount, String address, int port, String username, String password, String secret, MtProxyOptions options, RequestTimeDelegate requestTimeDelegate);
    public static native void native_cancelProxyCheck(int currentAccount, long pingId);
    public static native void native_cancelProxyEndpointAttempts(int currentAccount, String endpointKey, String probeKey, String reason);
    public static native void native_onHostNameResolved(String host, long address, String ip);
    public static native void native_discardConnection(int currentAccount, int datacenterId, int connectionType);
    public static native void native_failNotRunningRequest(int currentAccount, int token);
    public static native void native_receivedIntegrityCheckClassic(int currentAccount, int requestToken, String nonce, String token);
    public static native void native_receivedCaptchaResult(int currentAccount, int[] requestTokens, String token);
    public static native boolean native_isGoodPrime(byte[] prime, int g);


    public static boolean testNativeTlScheme(NativeByteBuffer buffer, INativeTlTest test) {
        return test.test(buffer.address);
    }

    public static native boolean native_test_AuthAuthorization(long object);
    public interface INativeTlTest {
        boolean test(long address);
    }


    public static int generateClassGuid() {
        return lastClassGuid++;
    }

    public void setIsUpdating(final boolean value) {
        AndroidUtilities.runOnUIThread(() -> {
            if (isUpdating == value) {
                return;
            }
            isUpdating = value;
            if (connectionState == ConnectionStateConnected) {
                AccountInstance.getInstance(currentAccount).getNotificationCenter().postNotificationName(NotificationCenter.didUpdateConnectionState);
            }
        });
    }

    @SuppressLint("NewApi")
    protected byte getIpStrategy() {
        if (Build.VERSION.SDK_INT < 19) {
            return USE_IPV4_ONLY;
        }
        if (BuildVars.LOGS_ENABLED) {
            try {
                NetworkInterface networkInterface;
                Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
                while (networkInterfaces.hasMoreElements()) {
                    networkInterface = networkInterfaces.nextElement();
                    if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.getInterfaceAddresses().isEmpty()) {
                        continue;
                    }
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("valid interface: " + networkInterface);
                    }
                    List<InterfaceAddress> interfaceAddresses = networkInterface.getInterfaceAddresses();
                    for (int a = 0; a < interfaceAddresses.size(); a++) {
                        InterfaceAddress address = interfaceAddresses.get(a);
                        InetAddress inetAddress = address.getAddress();
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("address: " + inetAddress.getHostAddress());
                        }
                        if (inetAddress.isLinkLocalAddress() || inetAddress.isLoopbackAddress() || inetAddress.isMulticastAddress()) {
                            continue;
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("address is good");
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        try {
            NetworkInterface networkInterface;
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            boolean hasIpv4 = false;
            boolean hasIpv6 = false;
            boolean hasStrangeIpv4 = false;
            while (networkInterfaces.hasMoreElements()) {
                networkInterface = networkInterfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                List<InterfaceAddress> interfaceAddresses = networkInterface.getInterfaceAddresses();
                for (int a = 0; a < interfaceAddresses.size(); a++) {
                    InterfaceAddress address = interfaceAddresses.get(a);
                    InetAddress inetAddress = address.getAddress();
                    if (inetAddress.isLinkLocalAddress() || inetAddress.isLoopbackAddress() || inetAddress.isMulticastAddress()) {
                        continue;
                    }
                    if (inetAddress instanceof Inet6Address) {
                        hasIpv6 = true;
                    } else if (inetAddress instanceof Inet4Address) {
                        String addrr = inetAddress.getHostAddress();
                        if (!addrr.startsWith("192.0.0.")) {
                            hasIpv4 = true;
                        } else {
                            hasStrangeIpv4 = true;
                        }
                    }
                }
            }
            if (hasIpv6) {
                if (forceTryIpV6) {
                    return USE_IPV6_ONLY;
                }
                if (hasStrangeIpv4) {
                    return USE_IPV4_IPV6_RANDOM;
                }
                if (!hasIpv4) {
                    return USE_IPV6_ONLY;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }

        return USE_IPV4_ONLY;
    }

    private static DohJsonResponse loadDohJson(String endpoint, String name, String type, String extraQuery, int connectTimeout, int readTimeout, AsyncTask<?, ?, ?> task) throws Exception {
        if (TextUtils.isEmpty(name)) {
            throw new UnknownHostException("empty_host");
        }
        StringBuilder urlBuilder = new StringBuilder(endpoint);
        urlBuilder.append("?name=").append(URLEncoder.encode(name, "UTF-8")).append("&type=").append(URLEncoder.encode(type, "UTF-8"));
        if (!TextUtils.isEmpty(extraQuery)) {
            urlBuilder.append("&").append(extraQuery);
        }
        URLConnection httpConnection = new URL(urlBuilder.toString()).openConnection();
        httpConnection.addRequestProperty("User-Agent", DOH_USER_AGENT);
        httpConnection.addRequestProperty("Accept", "application/dns-json");
        httpConnection.setConnectTimeout(connectTimeout);
        httpConnection.setReadTimeout(readTimeout);
        httpConnection.connect();

        ByteArrayOutputStream outbuf = new ByteArrayOutputStream();
        InputStream httpConnectionStream = null;
        try {
            httpConnectionStream = httpConnection.getInputStream();
            byte[] data = new byte[1024 * 32];
            while (true) {
                if (task != null && task.isCancelled()) {
                    break;
                }
                int read = httpConnectionStream.read(data);
                if (read > 0) {
                    outbuf.write(data, 0, read);
                } else if (read == -1) {
                    break;
                } else {
                    break;
                }
            }
            return new DohJsonResponse(outbuf.toByteArray(), (int) (httpConnection.getDate() / 1000));
        } finally {
            try {
                if (httpConnectionStream != null) {
                    httpConnectionStream.close();
                }
            } catch (Throwable e) {
                FileLog.e(e, false);
            }
            try {
                outbuf.close();
            } catch (Exception ignore) {

            }
        }
    }

    private static String dohQueryParam(String name, String value) throws Exception {
        return URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8");
    }

    private static void logDohExpectedFailure(String source, String host, String endpoint, Throwable e) {
        FileLog.d("dns_resolver fallback provider=" + source + " host=" + host + " reason=" + e.getClass().getSimpleName() + " endpoint=" + endpoint);
    }

    private static void logHostResolverExpectedFailure(String provider, String host, String reason) {
        FileLog.d("dns_resolver fallback provider=" + provider + " host=" + host + " reason=" + reason);
    }

    private static void logDnsResult(String provider, String result, String host, ResolvedDomain resolvedDomain) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        int ipv4Count = resolvedDomain == null ? 0 : resolvedDomain.ipv4.size();
        int ipv6Count = resolvedDomain == null ? 0 : resolvedDomain.ipv6.size();
        String source = resolvedDomain == null ? "" : resolvedDomain.source;
        FileLog.d("dns_resolver provider=" + provider + " result=" + result + " host=" + host + " ipv4=" + ipv4Count + " ipv6=" + ipv6Count + " source=" + source);
    }

    private static boolean isBlockedZeroAddress(String address) {
        if (TextUtils.isEmpty(address)) {
            return false;
        }
        String normalized = address.trim();
        int zoneIndex = normalized.indexOf('%');
        if (zoneIndex >= 0) {
            normalized = normalized.substring(0, zoneIndex);
        }
        if ("0.0.0.0".equals(normalized)
                || "::".equals(normalized)
                || "0:0:0:0:0:0:0:0".equals(normalized)) {
            return true;
        }
        try {
            return InetAddress.getByName(normalized).isAnyLocalAddress();
        } catch (Exception ignore) {
            return false;
        }
    }

    private static ArrayList<String> filterBlockedZeroIpv4Addresses(List<String> rawAddresses, ResolveContext context) {
        ArrayList<String> addresses = new ArrayList<>();
        if (rawAddresses == null) {
            return addresses;
        }
        for (int a = 0, N = rawAddresses.size(); a < N; a++) {
            String address = rawAddresses.get(a);
            if (TextUtils.isEmpty(address)) {
                continue;
            }
            if (isBlockedZeroAddress(address)) {
                if (context != null) {
                    context.recordBlockedZeroAddress();
                }
                continue;
            }
            if (!addresses.contains(address)) {
                addresses.add(address);
            }
        }
        return addresses;
    }

    private static ArrayList<String> filterBlockedZeroIpv6Addresses(List<String> rawAddresses, ResolveContext context) {
        ArrayList<String> addresses = new ArrayList<>();
        if (rawAddresses == null) {
            return addresses;
        }
        for (int a = 0, N = rawAddresses.size(); a < N; a++) {
            String address = rawAddresses.get(a);
            if (TextUtils.isEmpty(address)) {
                continue;
            }
            if (isBlockedZeroAddress(address)) {
                if (context != null) {
                    context.recordBlockedZeroAddress();
                }
                continue;
            }
            if (!addresses.contains(address)) {
                addresses.add(address);
            }
        }
        return addresses;
    }

    private static ArrayList<String> filterIpv4Addresses(List<InetAddress> inetAddresses, ResolveContext context) {
        ArrayList<String> addresses = new ArrayList<>();
        if (inetAddresses == null) {
            return addresses;
        }
        for (int a = 0, N = inetAddresses.size(); a < N; a++) {
            InetAddress inetAddress = inetAddresses.get(a);
            if (inetAddress instanceof Inet4Address) {
                String hostAddress = inetAddress.getHostAddress();
                if (isBlockedZeroAddress(hostAddress)) {
                    if (context != null) {
                        context.recordBlockedZeroAddress();
                    }
                    continue;
                }
                if (!TextUtils.isEmpty(hostAddress) && !addresses.contains(hostAddress)) {
                    addresses.add(hostAddress);
                }
            }
        }
        return addresses;
    }

    private static ArrayList<String> filterIpv6Addresses(List<InetAddress> inetAddresses, ResolveContext context) {
        ArrayList<String> addresses = new ArrayList<>();
        if (inetAddresses == null) {
            return addresses;
        }
        for (int a = 0, N = inetAddresses.size(); a < N; a++) {
            InetAddress inetAddress = inetAddresses.get(a);
            if (inetAddress instanceof Inet6Address) {
                String hostAddress = inetAddress.getHostAddress();
                if (isBlockedZeroAddress(hostAddress)) {
                    if (context != null) {
                        context.recordBlockedZeroAddress();
                    }
                    continue;
                }
                if (!TextUtils.isEmpty(hostAddress) && !addresses.contains(hostAddress)) {
                    addresses.add(hostAddress);
                }
            }
        }
        return addresses;
    }

    private static ResolvedDomain resolvedDomainFromIpv4Addresses(ArrayList<String> addresses, String source) {
        return resolvedDomainFromAddresses(addresses, new ArrayList<>(), source, HOST_RESOLVER_MAX_FRESH_TTL_MS);
    }

    private static ResolvedDomain resolvedDomainFromAddresses(List<String> ipv4, List<String> ipv6, String source, long ttlMs) {
        ArrayList<String> filteredIpv4 = filterBlockedZeroIpv4Addresses(ipv4, null);
        ArrayList<String> filteredIpv6 = filterBlockedZeroIpv6Addresses(ipv6, null);
        if (filteredIpv4.isEmpty() && filteredIpv6.isEmpty()) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        long freshTtl = Math.max(1, Math.min(ttlMs, HOST_RESOLVER_MAX_FRESH_TTL_MS));
        return new ResolvedDomain(filteredIpv4, filteredIpv6, now + freshTtl, now + freshTtl + HOST_RESOLVER_STALE_TTL_MS, source);
    }

    @SuppressLint("NewApi")
    private static ArrayList<String> queryAndroidDnsResolverAaaa(ResolveContext context) {
        ArrayList<String> empty = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return empty;
        }
        // Без собственного IPv6 у устройства AAAA-адрес недостижим, и выдать
        // его значило бы заменить рабочий путь на заведомо мёртвый.
        if (!deviceHasGlobalIpv6()) {
            return empty;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ArrayList<String>> result = new AtomicReference<>();
        CancellationSignal cancellationSignal = new CancellationSignal();
        try {
            DnsResolver.getInstance().query(null, context.host, DnsResolver.TYPE_AAAA, DnsResolver.FLAG_EMPTY, DNS_DIRECT_EXECUTOR, cancellationSignal, new DnsResolver.Callback<List<InetAddress>>() {
                @Override
                public void onAnswer(List<InetAddress> answer, int rcode) {
                    result.set(filterIpv6Addresses(answer, context));
                    latch.countDown();
                }

                @Override
                public void onError(DnsResolver.DnsException e) {
                    latch.countDown();
                }
            });
            if (!latch.await(HOST_RESOLVER_SYSTEM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                cancellationSignal.cancel();
                logHostResolverExpectedFailure("system", context.host, "android_aaaa_timeout");
                return empty;
            }
        } catch (InterruptedException e) {
            cancellationSignal.cancel();
            Thread.currentThread().interrupt();
            return empty;
        } catch (Throwable e) {
            cancellationSignal.cancel();
            return empty;
        }
        ArrayList<String> addresses = result.get();
        return addresses != null ? addresses : empty;
    }

    private static boolean deviceHasGlobalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress inetAddress = interfaceAddress.getAddress();
                    if (!(inetAddress instanceof Inet4Address)) {
                        continue;
                    }
                    if (inetAddress.isLinkLocalAddress() || inetAddress.isLoopbackAddress()
                            || inetAddress.isMulticastAddress()) {
                        continue;
                    }
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean deviceHasGlobalIpv6() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress inetAddress = interfaceAddress.getAddress();
                    if (!(inetAddress instanceof Inet6Address)) {
                        continue;
                    }
                    if (inetAddress.isLinkLocalAddress() || inetAddress.isLoopbackAddress()
                            || inetAddress.isMulticastAddress() || inetAddress.isSiteLocalAddress()) {
                        continue;
                    }
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    @SuppressLint("NewApi")
    private static ResolvedDomain tryAndroidDnsResolverA(ResolveContext context) {
        String hostName = context.host;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ArrayList<String>> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CancellationSignal cancellationSignal = new CancellationSignal();
        try {
            DnsResolver.getInstance().query(null, hostName, DnsResolver.TYPE_A, DnsResolver.FLAG_EMPTY, DNS_DIRECT_EXECUTOR, cancellationSignal, new DnsResolver.Callback<List<InetAddress>>() {
                @Override
                public void onAnswer(List<InetAddress> answer, int rcode) {
                    result.set(filterIpv4Addresses(answer, context));
                    latch.countDown();
                }

                @Override
                public void onError(DnsResolver.DnsException e) {
                    error.set(e);
                    latch.countDown();
                }
            });
            if (!latch.await(HOST_RESOLVER_SYSTEM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                cancellationSignal.cancel();
                logHostResolverExpectedFailure("system", hostName, "android_timeout");
                return null;
            }
        } catch (InterruptedException e) {
            cancellationSignal.cancel();
            Thread.currentThread().interrupt();
            logHostResolverExpectedFailure("system", hostName, "android_" + e.getClass().getSimpleName());
            return null;
        } catch (Throwable e) {
            cancellationSignal.cancel();
            logHostResolverExpectedFailure("system", hostName, "android_" + e.getClass().getSimpleName());
            return null;
        }
        Throwable resolverError = error.get();
        if (resolverError != null) {
            logHostResolverExpectedFailure("system", hostName, "android_" + resolverError.getClass().getSimpleName());
            return null;
        }
        // AAAA спрашиваем отдельным запросом: без него список IPv6 всегда пуст,
        // и клиент не может воспользоваться шестым протоколом, даже когда он
        // есть у устройства. У веб-релеев Telegram единичные IPv4-адреса,
        // которые режутся целиком, поэтому IPv6-путь часто оказывается
        // единственным рабочим.
        ArrayList<String> ipv6Addresses = queryAndroidDnsResolverAaaa(context);
        ResolvedDomain resolvedDomain = resolvedDomainFromAddresses(
                result.get(), ipv6Addresses, "system", HOST_RESOLVER_MAX_FRESH_TTL_MS);
        if (resolvedDomain == null) {
            logHostResolverExpectedFailure("system", hostName, "android_no_ipv4_answer");
        }
        return resolvedDomain;
    }

    private static ResolvedDomain tryInetAddressA(ResolveContext context) {
        String hostName = context.host;
        try {
            InetAddress[] inetAddresses = InetAddress.getAllByName(hostName);
            ArrayList<String> addresses = new ArrayList<>(inetAddresses.length);
            ArrayList<String> ipv6Addresses = new ArrayList<>(inetAddresses.length);
            for (int a = 0; a < inetAddresses.length; a++) {
                InetAddress inetAddress = inetAddresses[a];
                if (inetAddress instanceof Inet4Address) {
                    String hostAddress = inetAddress.getHostAddress();
                    if (isBlockedZeroAddress(hostAddress)) {
                        context.recordBlockedZeroAddress();
                        continue;
                    }
                    if (!TextUtils.isEmpty(hostAddress) && !addresses.contains(hostAddress)) {
                        addresses.add(hostAddress);
                    }
                } else if (inetAddress instanceof Inet6Address) {
                    String hostAddress = inetAddress.getHostAddress();
                    if (isBlockedZeroAddress(hostAddress)) {
                        context.recordBlockedZeroAddress();
                        continue;
                    }
                    if (!TextUtils.isEmpty(hostAddress) && !ipv6Addresses.contains(hostAddress)) {
                        ipv6Addresses.add(hostAddress);
                    }
                }
            }
            ResolvedDomain resolvedDomain = resolvedDomainFromAddresses(addresses, ipv6Addresses, "system", HOST_RESOLVER_MAX_FRESH_TTL_MS);
            if (resolvedDomain == null) {
                logHostResolverExpectedFailure("system", hostName, "inet_no_answer");
            }
            return resolvedDomain;
        } catch (UnknownHostException e) {
            logHostResolverExpectedFailure("system", hostName, "inet_" + e.getClass().getSimpleName());
        } catch (Exception e) {
            FileLog.e(e, false);
        }
        return null;
    }

    private static ResolvedDomain parseDohHostResponse(byte[] bytes, String source, ResolveContext context) throws Exception {
        JSONObject jsonObject = new JSONObject(new String(bytes));
        JSONArray array = jsonObject.optJSONArray("Answer");
        if (array == null) {
            return null;
        }
        ArrayList<String> ipv4 = new ArrayList<>();
        ArrayList<String> ipv6 = new ArrayList<>();
        long ttlMs = HOST_RESOLVER_MAX_FRESH_TTL_MS;
        for (int a = 0; a < array.length(); a++) {
            JSONObject object = array.getJSONObject(a);
            int type = object.optInt("type");
            String data = object.optString("data");
            if (TextUtils.isEmpty(data)) {
                continue;
            }
            long answerTtl = Math.max(1, object.optLong("TTL", 300)) * 1000L;
            ttlMs = Math.min(ttlMs, answerTtl);
            if (type == 1 && data.indexOf('.') > 0 && !ipv4.contains(data)) {
                if (isBlockedZeroAddress(data)) {
                    if (context != null) {
                        context.recordBlockedZeroAddress();
                    }
                    continue;
                }
                ipv4.add(data);
            } else if (type == 28 && data.indexOf(':') > 0 && !ipv6.contains(data)) {
                if (isBlockedZeroAddress(data)) {
                    if (context != null) {
                        context.recordBlockedZeroAddress();
                    }
                    continue;
                }
                ipv6.add(data);
            }
        }
        return resolvedDomainFromAddresses(ipv4, ipv6, source, ttlMs);
    }

    private static int remainingDnsBudgetMs(ResolveContext context, int requestedMs) {
        long elapsed = SystemClock.elapsedRealtime() - context.startedAtMs;
        long remaining = Math.max(1, context.timeoutMs - elapsed);
        return (int) Math.max(1, Math.min(requestedMs, remaining));
    }

    private static boolean dnsBudgetExpired(ResolveContext context) {
        return SystemClock.elapsedRealtime() - context.startedAtMs >= context.timeoutMs;
    }

    private static ResolvedDomain resolveDohAddress(ResolveContext context, String provider, String endpoint, int type, String extraQuery) throws Exception {
        if (dnsBudgetExpired(context)) {
            return null;
        }
        String typeName = type == 28 ? "AAAA" : "A";
        DohJsonResponse response = loadDohJson(
                endpoint,
                context.host,
                typeName,
                extraQuery,
                remainingDnsBudgetMs(context, HOST_RESOLVER_DOH_CONNECT_TIMEOUT_MS),
                remainingDnsBudgetMs(context, HOST_RESOLVER_DOH_READ_TIMEOUT_MS),
                null
        );
        return parseDohHostResponse(response.bytes, provider, context);
    }

    private static ResolvedDomain resolveFromDnsCache(String host, boolean freshOnly) {
        ResolvedDomain cached;
        synchronized (dnsCache) {
            cached = dnsCache.get(host);
        }
        if (cached == null) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        if (cached.isFresh(now) || (!freshOnly && cached.isStale(now))) {
            return cached;
        }
        return null;
    }

    private static ResolvedDomain resolveHost(String host, int type, ResolveContext context, AsyncTask<?, ?, ?> task) {
        if (TextUtils.isEmpty(host)) {
            logDnsResult("chain", "invalid_host", host, null);
            return null;
        }
        ResolvedDomain stale = resolveFromDnsCache(host, false);
        boolean systemFailed = false;
        boolean googleFailed = false;
        boolean cloudflareFailed = false;
        for (HostResolver resolver : HOST_RESOLVER_CHAIN) {
            if ((task != null && task.isCancelled()) || dnsBudgetExpired(context)) {
                if (dnsBudgetExpired(context)) {
                    context.recordFailureReason(DNS_REASON_TIMEOUT);
                }
                break;
            }
            String resolverName = resolver.name();
            try {
                ResolvedDomain resolved = resolver.resolve(host, type, context);
                if (resolved != null && resolved.hasAddresses()) {
                    clearNegativeDnsCache(host);
                    logDnsResult(resolver.name(), "success", host, resolved);
                    ProxyRuntimeStateStore.recordDnsResolveSuccess(host, resolver.name());
                    return resolved;
                }
                context.recordFailureReason(DNS_REASON_NO_IPV4_ANSWER);
                if (isDnsOutageProvider(resolverName)) {
                    if ("system".equals(resolverName)) {
                        systemFailed = true;
                    } else if ("google_json_doh".equals(resolverName)) {
                        googleFailed = true;
                    } else if ("cloudflare_json_doh".equals(resolverName)) {
                        cloudflareFailed = true;
                    }
                    ProxyRuntimeStateStore.recordDnsResolverProviderFailure(host, resolver.name(), "empty");
                }
            } catch (FileNotFoundException | UnknownHostException | SocketTimeoutException | SSLException e) {
                context.recordFailureReason(dnsFailureReasonForException(e));
                if (isDnsOutageProvider(resolverName)) {
                    if ("system".equals(resolverName)) {
                        systemFailed = true;
                    } else if ("google_json_doh".equals(resolverName)) {
                        googleFailed = true;
                    } else if ("cloudflare_json_doh".equals(resolverName)) {
                        cloudflareFailed = true;
                    }
                    ProxyRuntimeStateStore.recordDnsResolverProviderFailure(host, resolver.name(), e.getClass().getSimpleName());
                }
                FileLog.d("dns_resolver fallback provider=" + resolver.name() + " host=" + host + " reason=" + e.getClass().getSimpleName());
            } catch (Throwable e) {
                FileLog.e(e, false);
            }
        }
        if (stale != null && stale.isStale(SystemClock.elapsedRealtime())) {
            clearNegativeDnsCache(host);
            logDnsResult("cache", "stale_dns_used", host, stale);
            ProxyRuntimeStateStore.recordDnsResolveSuccess(host, "cache_stale");
            return stale;
        }
        if (!context.blockedZeroAddress()) {
            recordNegativeDnsCache(host, context.negativeReason());
        }
        ProxyRuntimeStateStore.recordDnsResolveChainFailure(host, systemFailed, googleFailed, cloudflareFailed);
        logDnsResult("chain", "resolve_failed", host, null);
        return null;
    }

    private static String dnsFailureReasonForException(Throwable e) {
        if (e instanceof SocketTimeoutException) {
            return DNS_REASON_TIMEOUT;
        }
        if (e instanceof UnknownHostException || e instanceof FileNotFoundException) {
            return DNS_REASON_NXDOMAIN;
        }
        return DNS_REASON_ALL_RESOLVERS_FAILED;
    }

    private static boolean isDnsOutageProvider(String provider) {
        return "system".equals(provider)
                || "google_json_doh".equals(provider)
                || "cloudflare_json_doh".equals(provider);
    }

    private interface HostResolver {
        ResolvedDomain resolve(String host, int type, ResolveContext context) throws Exception;
        String name();
    }

    private static class ResolveContext {
        final String host;
        final boolean preferIpv6;
        final long startedAtMs;
        final int generation;
        final int timeoutMs;
        private String failureReason = DNS_REASON_ALL_RESOLVERS_FAILED;
        private boolean blockedZeroAddress;

        ResolveContext(String host, boolean preferIpv6, long startedAtMs, int generation, int timeoutMs) {
            this.host = host;
            this.preferIpv6 = preferIpv6;
            this.startedAtMs = startedAtMs;
            this.generation = generation;
            this.timeoutMs = timeoutMs;
        }

        void recordFailureReason(String reason) {
            if (TextUtils.isEmpty(reason)) {
                return;
            }
            if (DNS_REASON_TIMEOUT.equals(reason)
                    || DNS_REASON_ALL_RESOLVERS_FAILED.equals(failureReason)) {
                failureReason = reason;
            } else if (DNS_REASON_NXDOMAIN.equals(reason)
                    && DNS_REASON_NO_IPV4_ANSWER.equals(failureReason)) {
                failureReason = reason;
            }
        }

        void recordBlockedZeroAddress() {
            blockedZeroAddress = true;
            recordFailureReason(DNS_REASON_NO_IPV4_ANSWER);
        }

        boolean blockedZeroAddress() {
            return blockedZeroAddress;
        }

        String negativeReason() {
            if (dnsBudgetExpired(this)) {
                return DNS_REASON_TIMEOUT;
            }
            return TextUtils.isEmpty(failureReason) ? DNS_REASON_ALL_RESOLVERS_FAILED : failureReason;
        }
    }

    private static class DnsCacheResolver implements HostResolver {
        @Override
        public ResolvedDomain resolve(String host, int type, ResolveContext context) {
            return resolveFromDnsCache(host, true);
        }

        @Override
        public String name() {
            return "cache";
        }
    }

    private static class SystemDnsResolver implements HostResolver {
        @Override
        public ResolvedDomain resolve(String host, int type, ResolveContext context) {
            ResolvedDomain android = tryAndroidDnsResolverA(context);
            if (android != null) {
                return android;
            }
            return tryInetAddressA(context);
        }

        @Override
        public String name() {
            return "system";
        }
    }

    private static class GoogleJsonDohResolver implements HostResolver {
        @Override
        public ResolvedDomain resolve(String host, int type, ResolveContext context) throws Exception {
            return resolveDohAddress(context, name(), DOH_GOOGLE_QUERY_ENDPOINT, type, dohQueryParam("edns_client_subnet", "0.0.0.0/0"));
        }

        @Override
        public String name() {
            return "google_json_doh";
        }
    }

    private static class CloudflareJsonDohResolver implements HostResolver {
        @Override
        public ResolvedDomain resolve(String host, int type, ResolveContext context) throws Exception {
            return resolveDohAddress(context, name(), DOH_CLOUDFLARE_QUERY_ENDPOINT, type, "");
        }

        @Override
        public String name() {
            return "cloudflare_json_doh";
        }
    }

    private static final HostResolver[] HOST_RESOLVER_CHAIN = new HostResolver[] {
            new DnsCacheResolver(),
            new SystemDnsResolver(),
            new GoogleJsonDohResolver(),
            new CloudflareJsonDohResolver()
    };

    private static String randomDohPadding() {
        int len = Utilities.random.nextInt(116) + 13;
        final String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder padding = new StringBuilder(len);
        for (int a = 0; a < len; a++) {
            padding.append(characters.charAt(Utilities.random.nextInt(characters.length())));
        }
        return padding.toString();
    }

    private static String dnsConfigDomain(int currentAccount) {
        if (native_isTestBackend(currentAccount) != 0) {
            return "tapv3.stel.com";
        }
        return AccountInstance.getInstance(currentAccount).getMessagesController().dcDomainName;
    }

    private static NativeByteBuffer parseDnsTxtConfig(byte[] bytes) throws Exception {
        JSONObject jsonObject = new JSONObject(new String(bytes));
        JSONArray array = jsonObject.getJSONArray("Answer");
        int len = array.length();
        ArrayList<String> arrayList = new ArrayList<>(len);
        for (int a = 0; a < len; a++) {
            JSONObject object = array.getJSONObject(a);
            int type = object.getInt("type");
            if (type != 16) {
                continue;
            }
            arrayList.add(object.getString("data"));
        }
        Collections.sort(arrayList, (o1, o2) -> {
            int l1 = o1.length();
            int l2 = o2.length();
            if (l1 > l2) {
                return -1;
            } else if (l1 < l2) {
                return 1;
            }
            return 0;
        });
        StringBuilder builder = new StringBuilder();
        for (int a = 0; a < arrayList.size(); a++) {
            builder.append(arrayList.get(a).replace("\"", ""));
        }
        byte[] decodedBytes = Base64.decode(builder.toString(), Base64.DEFAULT);
        NativeByteBuffer buffer = new NativeByteBuffer(decodedBytes.length);
        buffer.writeBytes(decodedBytes);
        return buffer;
    }

    private static class DohJsonResponse {
        final byte[] bytes;
        final int responseDate;

        DohJsonResponse(byte[] bytes, int responseDate) {
            this.bytes = bytes;
            this.responseDate = responseDate;
        }
    }

    private static class ResolveHostByNameTask extends AsyncTask<Void, Void, ResolvedDomain> {

        private ArrayList<Long> addresses = new ArrayList<>();
        private String currentHostName;
        private boolean blockedZeroAddress;

        public ResolveHostByNameTask(String hostName) {
            super();
            currentHostName = hostName;
        }

        public void addAddress(long address) {
            if (addresses.contains(address)) {
                return;
            }
            addresses.add(address);
        }

        protected ResolvedDomain doInBackground(Void... voids) {
            ResolveContext context = new ResolveContext(currentHostName, false, SystemClock.elapsedRealtime(), dnsResolveGeneration.getAndIncrement(), HOST_RESOLVER_TOTAL_TIMEOUT_MS);
            ResolvedDomain result = resolveHost(currentHostName, DnsResolver.TYPE_A, context, this);
            blockedZeroAddress = context.blockedZeroAddress();
            return result;
        }

        @Override
        protected void onPostExecute(final ResolvedDomain result) {
            if (result != null) {
                clearNegativeDnsCache(currentHostName);
                synchronized (dnsCache) {
                    dnsCache.put(currentHostName, result);
                }
                for (int a = 0, N = addresses.size(); a < N; a++) {
                    native_onHostNameResolved(currentHostName, addresses.get(a), result.getAddress());
                }
            } else {
                for (int a = 0, N = addresses.size(); a < N; a++) {
                    native_onHostNameResolved(currentHostName, addresses.get(a), blockedZeroAddress ? DNS_BLOCKED_ZERO_NATIVE_SENTINEL : "");
                }
            }
            resolvingHostnameTasks.remove(currentHostName);
        }
    }

    private static class GoogleDnsLoadTask extends AsyncTask<Void, Void, NativeByteBuffer> {

        private int currentAccount;
        private int responseDate;

        public GoogleDnsLoadTask(int instance) {
            super();
            currentAccount = instance;
        }

        protected NativeByteBuffer doInBackground(Void... voids) {
            String domain = "";
            try {
                domain = dnsConfigDomain(currentAccount);
                DohJsonResponse response = loadDohJson(DOH_GOOGLE_QUERY_ENDPOINT, domain, "ANY", dohQueryParam("random_padding", randomDohPadding()), 5000, 5000, this);
                responseDate = response.responseDate;
                return parseDnsTxtConfig(response.bytes);
            } catch (FileNotFoundException | UnknownHostException | SocketTimeoutException | SSLException e) {
                logDohExpectedFailure("google_txt", domain, DOH_GOOGLE_QUERY_ENDPOINT, e);
            } catch (Throwable e) {
                FileLog.e(e, false);
            }
            return null;
        }

        @Override
        protected void onPostExecute(final NativeByteBuffer result) {
            Utilities.stageQueue.postRunnable(() -> {
                FileLog.d("3. currentTask = null, result = " + result);
                currentTask = null;
                if (result != null) {
                    native_applyDnsConfig(currentAccount, result.address, AccountInstance.getInstance(currentAccount).getUserConfig().getClientPhone(), responseDate);
                } else {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("failed to get google result");
                        FileLog.d("start cloudflare task");
                    }
                    CloudflareDnsLoadTask task = new CloudflareDnsLoadTask(currentAccount);
                    task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null, null, null);
                    FileLog.d("4. currentTask = cloudflare");
                    currentTask = task;
                }
            });
        }
    }

    private static class CloudflareDnsLoadTask extends AsyncTask<Void, Void, NativeByteBuffer> {

        private int currentAccount;
        private int responseDate;

        public CloudflareDnsLoadTask(int instance) {
            super();
            currentAccount = instance;
        }

        protected NativeByteBuffer doInBackground(Void... voids) {
            String domain = "";
            try {
                domain = dnsConfigDomain(currentAccount);
                DohJsonResponse response = loadDohJson(DOH_CLOUDFLARE_QUERY_ENDPOINT, domain, "TXT", dohQueryParam("random_padding", randomDohPadding()), 5000, 5000, this);
                responseDate = response.responseDate;
                return parseDnsTxtConfig(response.bytes);
            } catch (FileNotFoundException | UnknownHostException | SocketTimeoutException | SSLException e) {
                logDohExpectedFailure("cloudflare_txt", domain, DOH_CLOUDFLARE_QUERY_ENDPOINT, e);
            } catch (Throwable e) {
                FileLog.e(e, false);
            }
            return null;
        }

        @Override
        protected void onPostExecute(final NativeByteBuffer result) {
            Utilities.stageQueue.postRunnable(() -> {
                FileLog.d("5. currentTask = null");
                currentTask = null;
                if (result != null) {
                    native_applyDnsConfig(currentAccount, result.address, AccountInstance.getInstance(currentAccount).getUserConfig().getClientPhone(), responseDate);
                } else {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("failed to get cloudflare txt result");
                    }
                }
            });
        }
    }

    public static long lastPremiumFloodWaitShown = 0;
    @Keep
    public static void onPremiumFloodWait(final int currentAccount, final int requestToken, boolean isUpload) {
        AndroidUtilities.runOnUIThread(() -> {
            if (UserConfig.selectedAccount != currentAccount) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                boolean updated = false;
                if (isUpload) {
                    FileUploadOperation operation = FileLoader.getInstance(currentAccount).findUploadOperationByRequestToken(requestToken);
                    if (operation != null) {
                        updated = !operation.caughtPremiumFloodWait;
                        operation.caughtPremiumFloodWait = true;
                    }
                } else {
                    FileLoadOperation operation = FileLoader.getInstance(currentAccount).findLoadOperationByRequestToken(requestToken);
                    if (operation != null) {
                        updated = !operation.caughtPremiumFloodWait;
                        operation.caughtPremiumFloodWait = true;
                    }
                }
                final boolean finalUpdated = updated;
                if (finalUpdated) {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.premiumFloodWaitReceived);
                }
            });
        });
    }

    @Keep
    public static void onIntegrityCheckClassic(final int currentAccount, final int requestToken, final String project, final String nonce) {
        AndroidUtilities.runOnUIThread(() -> {
            // Fork builds cannot obtain a token for Telegram's Google Cloud project and
            // must also work on devices without Google Play Services. The native request
            // is paused while this callback runs, so always resume it immediately with
            // the protocol's failure marker instead of leaving auth.sendCode spinning.
            FileLog.d("account" + currentAccount + ": Play Integrity disabled, returning fallback for project = " + project);
            native_receivedIntegrityCheckClassic(currentAccount, requestToken, nonce, "PLAYINTEGRITY_FAILED_EXCEPTION_DISABLED");
        });
    }

    @Keep
    public static void onCaptchaCheck(final int currentAccount, final int requestToken, final String action, final String key_id) {
        CaptchaController.request(currentAccount, requestToken, action, key_id);
    }

    public static native byte[] nativeTestGenerateClientHello(String domain);


}
