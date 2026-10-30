package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.web.HttpGetFileTask;
import org.telegram.ui.web.HttpGetTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZaStoGram Standalone updater backed by public Forgejo Releases.
 *
 * The update channel is immutable for a built APK: dev builds accept only
 * prereleases, while stable builds accept only regular releases. Drafts and
 * assets for a different CPU architecture are never offered.
 */
public final class ForgejoUpdaterController {

    private static final long CHECK_INTERVAL_PAUSED = 24L * 60L * 60L * 1000L;
    private static final long CHECK_INTERVAL = 20L * 60L * 1000L;
    private static final long REMIND_LATER_INTERVAL = 24L * 60L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 512L * 1024L * 1024L;
    private static final Pattern DEV_RELEASE_TAG = Pattern.compile("^zastogram-apk-(\\d+)-(\\d+)$");

    private static ForgejoUpdaterController instance;

    public static ForgejoUpdaterController getInstance() {
        if (instance == null) {
            instance = new ForgejoUpdaterController();
        }
        return instance;
    }

    private String version;
    private int displayVersionCode;
    private String changelog;
    private String releaseTag;
    private long releaseId;
    private long assetId;
    private long updateOrder;
    private String fileUrl;
    private long assetSize;
    private String path;
    private long lastCheck;
    private String installedReleaseTag;
    private String snoozedReleaseTag;
    private long snoozedUntil;
    private String skippedReleaseTag;

    private boolean checkingForUpdate;
    private boolean showPopupAfterCheck;
    private final ArrayList<Runnable> completionCallbacks = new ArrayList<>();
    private final Runnable scheduledUpdateCheck = () -> checkForUpdate(false, null);

    private boolean downloading;
    private float downloadingProgress;
    private HttpGetFileTask downloadingTask;
    private List<String> downloadHosts = new ArrayList<>();
    private int downloadHostIndex;

    private ForgejoUpdaterController() {
        load();
    }

    private SharedPreferences getSharedPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("forgejo_updater", Activity.MODE_PRIVATE);
    }

    public static boolean isDevChannel() {
        return "dev".equalsIgnoreCase(ApplicationLoader.applicationContext.getString(
                org.telegram.messenger.web.R.string.ZastoUpdateChannel));
    }

    private static String getChannel() {
        return isDevChannel() ? "dev" : "stable";
    }

    private void load() {
        SharedPreferences prefs = getSharedPreferences();
        version = prefs.getString("version", null);
        displayVersionCode = prefs.getInt("displayVersionCode", 0);
        changelog = prefs.getString("changelog", null);
        releaseTag = prefs.getString("releaseTag", null);
        releaseId = prefs.getLong("releaseId", 0L);
        assetId = prefs.getLong("assetId", 0L);
        updateOrder = prefs.getLong("updateOrder", 0L);
        fileUrl = prefs.getString("fileUrl", null);
        assetSize = prefs.getLong("assetSize", 0L);
        path = prefs.getString("path", null);
        lastCheck = prefs.getLong("lastCheck", 0L);
        installedReleaseTag = prefs.getString("installedReleaseTag", null);
        snoozedReleaseTag = prefs.getString("snoozedReleaseTag", null);
        snoozedUntil = prefs.getLong("snoozedUntil", 0L);
        skippedReleaseTag = prefs.getString("skippedReleaseTag", null);

        int currentVersionCode = getCurrentVersionCode();
        int previousInstalledVersionCode = prefs.getInt("installedVersionCode", currentVersionCode);
        String embeddedReleaseTag = getEmbeddedReleaseTag();
        if (!TextUtils.isEmpty(embeddedReleaseTag)) {
            installedReleaseTag = embeddedReleaseTag;
        } else if (currentVersionCode != previousInstalledVersionCode && !TextUtils.isEmpty(releaseTag)) {
            // App data survives an APK update. This also identifies a release installed
            // through the updater when an older build did not embed its release tag.
            installedReleaseTag = releaseTag;
        }

        if (!getChannel().equals(prefs.getString("channel", getChannel()))) {
            clearPendingUpdate(true);
            clearUpdatePromptChoices();
            lastCheck = 0L;
        }
        if (!TextUtils.isEmpty(path) && !new File(path).exists()) {
            path = null;
        }
        File completedFile = getCompletedDownloadFile();
        if (TextUtils.isEmpty(path) && completedFile != null && isDownloadedApkValid(completedFile)) {
            // Recover a download if the process stopped after the APK was moved
            // into place but before the completed path reached SharedPreferences.
            path = completedFile.getAbsolutePath();
        }
        File partialFile = getPartialDownloadFile();
        if (partialFile != null && partialFile.isFile() && assetSize > 0L) {
            if (partialFile.length() > assetSize) {
                deleteFile(partialFile);
            } else if (TextUtils.isEmpty(path) && partialFile.length() == assetSize && isDownloadedApkValid(partialFile)) {
                // Recover a download if the process stopped after the last byte was
                // written but before the temporary file was moved into place.
                File finalizedFile = finalizeDownloadedApk(partialFile);
                if (finalizedFile != null) {
                    path = finalizedFile.getAbsolutePath();
                }
            }
        }
        if (!TextUtils.isEmpty(releaseTag) && releaseTag.equals(getInstalledReleaseTag())) {
            clearPendingUpdate(true);
            clearUpdatePromptChoices();
        }
        save(currentVersionCode);
    }

    private void save() {
        save(getCurrentVersionCode());
    }

    private void save(int installedVersionCode) {
        SharedPreferences.Editor editor = getSharedPreferences().edit();
        putString(editor, "version", version);
        putString(editor, "changelog", changelog);
        putString(editor, "releaseTag", releaseTag);
        putString(editor, "fileUrl", fileUrl);
        putString(editor, "path", path);
        putString(editor, "installedReleaseTag", installedReleaseTag);
        putString(editor, "snoozedReleaseTag", snoozedReleaseTag);
        putString(editor, "skippedReleaseTag", skippedReleaseTag);
        putInt(editor, "displayVersionCode", displayVersionCode);
        putLong(editor, "releaseId", releaseId);
        putLong(editor, "assetId", assetId);
        putLong(editor, "updateOrder", updateOrder);
        putLong(editor, "assetSize", assetSize);
        putLong(editor, "lastCheck", lastCheck);
        putLong(editor, "snoozedUntil", snoozedUntil);
        editor.putString("channel", getChannel());
        editor.putInt("installedVersionCode", installedVersionCode);
        editor.apply();
    }

    private static void putString(SharedPreferences.Editor editor, String key, String value) {
        if (TextUtils.isEmpty(value)) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }

    private static void putInt(SharedPreferences.Editor editor, String key, int value) {
        if (value == 0) {
            editor.remove(key);
        } else {
            editor.putInt(key, value);
        }
    }

    private static void putLong(SharedPreferences.Editor editor, String key, long value) {
        if (value == 0L) {
            editor.remove(key);
        } else {
            editor.putLong(key, value);
        }
    }

    public void checkForUpdate(boolean force, Runnable whenDone) {
        if (whenDone != null) {
            completionCallbacks.add(whenDone);
        } else {
            showPopupAfterCheck = true;
        }
        if (checkingForUpdate) {
            return;
        }

        long interval = ApplicationLoader.mainInterfacePaused ? CHECK_INTERVAL_PAUSED : CHECK_INTERVAL;
        if (!force && System.currentTimeMillis() - lastCheck < interval) {
            FileLog.d("zasto_update check_cached channel=" + getChannel()
                    + " installed=" + getInstalledReleaseTag()
                    + " pending=" + releaseTag
                    + " remaining_ms=" + Math.max(0L, interval - (System.currentTimeMillis() - lastCheck)));
            finishCheck(false);
            scheduleNextCheck();
            return;
        }

        FileLog.d("zasto_update check_start channel=" + getChannel()
                + " installed=" + getInstalledReleaseTag()
                + " force=" + force
                + " url=" + getReleasesUrl());
        checkingForUpdate = true;
        requestReleases(ZaStoGitMirrors.hosts(), 0, false);
    }

    /**
     * Asks the hosts one by one: git.zapret.moe is dropped by name in some countries, its mirrors are not.
     * When every known host is silent, the names handed out now are read once from the entry pages.
     */
    private void requestReleases(List<String> hosts, int index, boolean fromEntry) {
        final String host = hosts.get(index);
        final String url = ZaStoGitMirrors.withHost(getReleasesUrl(), host);
        new HttpGetTask(result -> AndroidUtilities.runOnUIThread(() -> {
            boolean changed = false;
            boolean tryNext = false;
            boolean tryEntry = false;
            try {
                if (result == null) {
                    throw new IllegalStateException("Forgejo returned no response");
                }
                ReleaseCandidate candidate = isDevChannel()
                        ? parseNewestPrerelease(new JSONArray(result))
                        : parseStableRelease(new JSONObject(result));
                changed = applyCandidate(candidate);
                FileLog.d("zasto_update check_result channel=" + getChannel()
                        + " installed=" + getInstalledReleaseTag()
                        + " candidate=" + (candidate == null ? "none" : candidate.releaseTag)
                        + " pending=" + releaseTag
                        + " changed=" + changed
                        + " host=" + host);
                lastCheck = System.currentTimeMillis();
                save();
                ZaStoGitMirrors.rememberGood(host);
            } catch (Exception e) {
                FileLog.e("Failed to check ZaStoGram updates at " + url, e);
                tryNext = index + 1 < hosts.size();
                tryEntry = !tryNext && !fromEntry;
            } finally {
                if (tryNext) {
                    requestReleases(hosts, index + 1, fromEntry);
                } else if (tryEntry) {
                    ZaStoGitMirrors.fetchEntryNames(hosts, fresh -> {
                        if (fresh.isEmpty()) {
                            checkingForUpdate = false;
                            finishCheck(false);
                            scheduleNextCheck();
                        } else {
                            requestReleases(fresh, 0, true);
                        }
                    });
                } else {
                    checkingForUpdate = false;
                    finishCheck(changed);
                    scheduleNextCheck();
                }
            }
        }))
                .setHeader("Accept", "application/json")
                .setHeader("User-Agent", "ZaStoGram-Android-Updater")
                .setTimeouts(ZaStoGitMirrors.CONNECT_TIMEOUT_MS, ZaStoGitMirrors.READ_TIMEOUT_MS)
                .execute(url);
    }

    private static String getReleasesUrl() {
        String repository = ApplicationLoader.applicationContext.getString(
                org.telegram.messenger.web.R.string.ZastoForgejoRepository);
        String base = "https://git.zapret.moe/api/v1/repos/" + repository + "/releases";
        return isDevChannel() ? base + "?limit=100" : base + "/latest";
    }

    private ReleaseCandidate parseNewestPrerelease(JSONArray releases) throws Exception {
        ReleaseCandidate newest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            ReleaseCandidate candidate = parseRelease(release, true);
            if (candidate != null && (newest == null
                    || candidate.updateOrder > newest.updateOrder
                    || candidate.updateOrder == newest.updateOrder && candidate.releaseId > newest.releaseId)) {
                newest = candidate;
            }
        }
        return newest;
    }

    private ReleaseCandidate parseStableRelease(JSONObject release) throws Exception {
        if (release == null || !release.has("tag_name")) {
            if (release != null && "Not Found".equals(release.optString("message", ""))) {
                // Forgejo returns 404 from /releases/latest when a repository has no
                // regular release yet. For a stable build that simply means no update.
                return null;
            }
            throw new IllegalStateException("Forgejo latest-release response has no tag_name");
        }
        return parseRelease(release, false);
    }

    private ReleaseCandidate parseRelease(JSONObject release, boolean expectPrerelease) throws Exception {
        if (release == null || release.optBoolean("draft", false)
                || release.optBoolean("prerelease", false) != expectPrerelease) {
            return null;
        }

        String tag = release.optString("tag_name", "").trim();
        long id = release.optLong("id", 0L);
        if (TextUtils.isEmpty(tag) || id == 0L) {
            return null;
        }

        JSONObject asset = findAssetForDevice(release.optJSONArray("assets"));
        if (asset == null) {
            return null;
        }
        String downloadUrl = asset.optString("browser_download_url", "");
        long assetId = asset.optLong("id", 0L);
        long size = asset.optLong("size", 0L);
        // Mirrors answer with their own host; the stored URL stays the same whichever one replied.
        downloadUrl = ZaStoGitMirrors.canonical(downloadUrl);
        if (!downloadUrl.startsWith("https://") || assetId == 0L || size <= 0L || size > MAX_APK_BYTES) {
            return null;
        }

        String publishedAt = release.optString("published_at", "");
        long order = parsePublishedOrder(publishedAt);
        if (order == 0L) {
            order = id;
        }
        String releaseName = release.optString("name", "").trim();
        if (TextUtils.isEmpty(releaseName)) {
            releaseName = tag;
        }
        String body = release.optString("body", "").trim();

        ReleaseCandidate candidate = new ReleaseCandidate();
        candidate.version = releaseName;
        candidate.displayVersionCode = parseDisplayVersionCode(tag);
        candidate.changelog = TextUtils.isEmpty(body) ? null : body;
        candidate.releaseTag = tag;
        candidate.releaseId = id;
        candidate.assetId = assetId;
        candidate.updateOrder = order;
        candidate.fileUrl = downloadUrl;
        candidate.assetSize = size;
        return candidate;
    }

    private static JSONObject findAssetForDevice(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        for (String abi : Build.SUPPORTED_ABIS) {
            String expectedName = "ZaStoGram-standalone-" + abi + ".apk";
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null || !expectedName.equals(asset.optString("name", ""))) {
                    continue;
                }
                String state = asset.optString("state", "uploaded");
                if (TextUtils.isEmpty(state) || "uploaded".equals(state)) {
                    return asset;
                }
            }
        }
        return null;
    }

    private static long parsePublishedOrder(String publishedAt) {
        if (TextUtils.isEmpty(publishedAt)) {
            return 0L;
        }
        String digits = publishedAt.replaceAll("[^0-9]", "");
        if (digits.length() < 14) {
            return 0L;
        }
        try {
            return Long.parseLong(digits.substring(0, 14));
        } catch (NumberFormatException ignore) {
            return 0L;
        }
    }

    private static int parseDisplayVersionCode(String tag) {
        Matcher matcher = DEV_RELEASE_TAG.matcher(tag);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignore) {
                // Fall through to a compact, non-zero display value.
            }
        }
        return Math.max(1, getEmbeddedBuildNumber() + 1);
    }

    private boolean applyCandidate(ReleaseCandidate candidate) {
        boolean hadPendingUpdate = !TextUtils.isEmpty(releaseTag);
        if (candidate == null) {
            clearPendingUpdate(true);
            return hadPendingUpdate;
        }
        if (candidate.releaseTag.equals(getInstalledReleaseTag())) {
            clearPendingUpdate(true);
            clearUpdatePromptChoices();
            return hadPendingUpdate;
        }
        if (candidate.releaseTag.equals(skippedReleaseTag)) {
            clearPendingUpdate(true);
            return hadPendingUpdate;
        }
        if (!TextUtils.isEmpty(skippedReleaseTag)) {
            skippedReleaseTag = null;
        }
        if (!candidate.releaseTag.equals(snoozedReleaseTag)) {
            snoozedReleaseTag = null;
            snoozedUntil = 0L;
        }

        if (candidate.releaseId == releaseId && candidate.releaseTag.equals(releaseTag)) {
            if ((assetId != 0L && candidate.assetId != assetId)
                    || candidate.assetSize != assetSize
                    || !TextUtils.equals(candidate.fileUrl, fileUrl)) {
                clearDownloadedUpdateFiles();
            }
            version = candidate.version;
            displayVersionCode = candidate.displayVersionCode;
            changelog = candidate.changelog;
            assetId = candidate.assetId;
            updateOrder = candidate.updateOrder;
            fileUrl = candidate.fileUrl;
            assetSize = candidate.assetSize;
            return false;
        }

        clearPendingUpdate(true);
        version = candidate.version;
        displayVersionCode = candidate.displayVersionCode;
        changelog = candidate.changelog;
        releaseTag = candidate.releaseTag;
        releaseId = candidate.releaseId;
        assetId = candidate.assetId;
        updateOrder = candidate.updateOrder;
        fileUrl = candidate.fileUrl;
        assetSize = candidate.assetSize;
        return true;
    }

    private void clearUpdatePromptChoices() {
        snoozedReleaseTag = null;
        snoozedUntil = 0L;
        skippedReleaseTag = null;
    }

    private String getInstalledReleaseTag() {
        String embeddedReleaseTag = getEmbeddedReleaseTag();
        return !TextUtils.isEmpty(embeddedReleaseTag)
                ? embeddedReleaseTag
                : installedReleaseTag;
    }

    private static String getEmbeddedReleaseTag() {
        return ApplicationLoader.applicationContext.getString(org.telegram.messenger.web.R.string.ZastoReleaseTag);
    }

    private static int getEmbeddedBuildNumber() {
        return ApplicationLoader.applicationContext.getResources().getInteger(org.telegram.messenger.web.R.integer.ZastoBuildNumber);
    }

    private void clearPendingUpdate(boolean deleteFile) {
        if (deleteFile) {
            clearDownloadedUpdateFiles();
        }
        version = null;
        displayVersionCode = 0;
        changelog = null;
        releaseTag = null;
        releaseId = 0L;
        assetId = 0L;
        updateOrder = 0L;
        fileUrl = null;
        assetSize = 0L;
        path = null;
    }

    private void clearDownloadedUpdateFiles() {
        File partialFile = getPartialDownloadFile();
        File completedFile = getCompletedDownloadFile();
        if (!TextUtils.isEmpty(path)) {
            File downloadedFile = new File(path);
            deleteFile(downloadedFile);
            if (partialFile != null && downloadedFile.equals(partialFile)) {
                partialFile = null;
            }
            if (completedFile != null && downloadedFile.equals(completedFile)) {
                completedFile = null;
            }
        }
        deleteFile(partialFile);
        deleteFile(completedFile);
        path = null;
    }

    private File getUpdateDirectory() {
        // provider_paths.xml has exposed files/cache since before the Forgejo
        // updater existed. Keeping updater files there makes the updater work
        // with the FileProvider rules retained from those older releases.
        File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            FileLog.e("Failed to create Forgejo update directory " + directory);
        }
        return directory;
    }

    private File getPartialDownloadFile() {
        if (releaseId == 0L || assetId == 0L) {
            return null;
        }
        return new File(getUpdateDirectory(),
                "zastogram-update-" + releaseId + "-" + assetId + ".apk.part");
    }

    private File getCompletedDownloadFile() {
        if (releaseId == 0L || assetId == 0L) {
            return null;
        }
        return new File(getUpdateDirectory(),
                "zastogram-update-" + releaseId + "-" + assetId + ".apk");
    }

    private File finalizeDownloadedApk(File partialFile) {
        if (partialFile == null || !isDownloadedApkValid(partialFile)) {
            return null;
        }
        File completedFile = getCompletedDownloadFile();
        if (completedFile == null || partialFile.equals(completedFile)) {
            return completedFile;
        }
        if (isDownloadedApkValid(completedFile)) {
            deleteFile(partialFile);
            return completedFile;
        }
        deleteFile(completedFile);
        if (!partialFile.renameTo(completedFile)) {
            FileLog.e("Failed to finalize Forgejo update APK " + partialFile);
            return null;
        }
        return completedFile;
    }

    private static void deleteFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            file.delete();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void finishCheck(boolean changed) {
        boolean shouldShowPopup = showPopupAfterCheck && changed && completionCallbacks.isEmpty();
        showPopupAfterCheck = false;
        ArrayList<Runnable> callbacks = new ArrayList<>(completionCallbacks);
        completionCallbacks.clear();

        if (changed) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
        }
        for (Runnable callback : callbacks) {
            callback.run();
        }
        if (shouldShowPopup && !ApplicationLoader.mainInterfacePaused && shouldShowUpdatePopup(false)) {
            Context context = LaunchActivity.instance != null ? LaunchActivity.instance : ApplicationLoader.applicationContext;
            BetaUpdate pendingUpdate = getUpdate();
            if (context != null && pendingUpdate != null) {
                boolean shown = ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(
                        context, pendingUpdate, UserConfig.selectedAccount);
                FileLog.d("zasto_update popup_attempt tag=" + releaseTag + " shown=" + shown);
            }
        }
    }

    private void scheduleNextCheck() {
        AndroidUtilities.cancelRunOnUIThread(scheduledUpdateCheck);
        AndroidUtilities.runOnUIThread(scheduledUpdateCheck,
                ApplicationLoader.mainInterfacePaused ? CHECK_INTERVAL_PAUSED : CHECK_INTERVAL);
    }

    public BetaUpdate getUpdate() {
        if (TextUtils.isEmpty(version) || TextUtils.isEmpty(releaseTag) || releaseId == 0L) {
            return null;
        }
        return new BetaUpdate(version, displayVersionCode, changelog, updateOrder);
    }

    public boolean shouldShowUpdatePopup(boolean force) {
        if (getUpdate() == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (!force && releaseTag.equals(snoozedReleaseTag) && now < snoozedUntil) {
            FileLog.d("zasto_update popup_snoozed tag=" + releaseTag
                    + " remaining_ms=" + (snoozedUntil - now));
            return false;
        }
        return true;
    }

    public void markUpdatePopupShown() {
        if (TextUtils.isEmpty(releaseTag)) {
            return;
        }
        snoozedReleaseTag = releaseTag;
        snoozedUntil = System.currentTimeMillis() + REMIND_LATER_INTERVAL;
        save();
        FileLog.d("zasto_update popup_shown tag=" + releaseTag);
    }

    public void remindAboutCurrentUpdateLater() {
        if (TextUtils.isEmpty(releaseTag)) {
            return;
        }
        snoozedReleaseTag = releaseTag;
        snoozedUntil = System.currentTimeMillis() + REMIND_LATER_INTERVAL;
        save();
        FileLog.d("zasto_update remind_later tag=" + releaseTag);
    }

    public void skipCurrentUpdate() {
        if (TextUtils.isEmpty(releaseTag)) {
            return;
        }
        skippedReleaseTag = releaseTag;
        snoozedReleaseTag = null;
        snoozedUntil = 0L;
        if (downloadingTask != null) {
            downloadingTask.cancel(true);
            downloadingTask = null;
        }
        downloading = false;
        downloadingProgress = 0.0f;
        clearPendingUpdate(true);
        save();
        FileLog.d("zasto_update skip tag=" + skippedReleaseTag);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public void downloadUpdate() {
        downloadHosts = ZaStoGitMirrors.hosts();
        downloadHostIndex = 0;
        downloadUpdate(false);
    }

    private void downloadUpdate(boolean refreshedRelease) {
        if (downloading || !TextUtils.isEmpty(path) || getUpdate() == null) {
            return;
        }
        if (TextUtils.isEmpty(fileUrl)) {
            if (!refreshedRelease) {
                checkForUpdate(true, () -> downloadUpdate(true));
            }
            return;
        }

        File partialFile = getPartialDownloadFile();
        if (partialFile == null) {
            if (!refreshedRelease) {
                checkForUpdate(true, () -> downloadUpdate(true));
            }
            return;
        }
        File completedFile = getCompletedDownloadFile();
        if (isDownloadedApkValid(completedFile)) {
            path = completedFile.getAbsolutePath();
            downloadingProgress = 1.0f;
            save();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            return;
        }
        if (partialFile.isFile() && assetSize > 0L && partialFile.length() >= assetSize) {
            if (partialFile.length() == assetSize && isDownloadedApkValid(partialFile)) {
                File finalizedFile = finalizeDownloadedApk(partialFile);
                if (finalizedFile != null) {
                    path = finalizedFile.getAbsolutePath();
                    downloadingProgress = 1.0f;
                    save();
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                    return;
                }
                downloadingProgress = 1.0f;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                return;
            }
            deleteFile(partialFile);
        }

        downloading = true;
        downloadingProgress = getCachedDownloadProgress();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
        final long requestedReleaseId = releaseId;
        final long requestedAssetId = assetId;
        downloadingTask = new HttpGetFileTask(
                downloadedFile -> AndroidUtilities.runOnUIThread(() ->
                        onDownloadFinished(downloadedFile, requestedReleaseId, requestedAssetId)),
                progress -> {
                    if (!downloading || releaseId != requestedReleaseId || assetId != requestedAssetId) {
                        return;
                    }
                    downloadingProgress = progress;
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
                }
        ).setOverrideExtension("apk")
                .setDestFile(partialFile)
                .setResumeExistingFile(true)
                .setKeepPartialFileOnCancel(true)
                .setTimeouts(ZaStoGitMirrors.CONNECT_TIMEOUT_MS, 20000)
                .setMaxSize(Math.min(MAX_APK_BYTES, assetSize));
        if (downloadHosts.isEmpty()) {
            downloadHosts = ZaStoGitMirrors.hosts();
            downloadHostIndex = 0;
        }
        downloadingTask.execute(ZaStoGitMirrors.withHost(fileUrl, downloadHosts.get(downloadHostIndex)));
    }

    private void onDownloadFinished(File downloadedFile, long requestedReleaseId, long requestedAssetId) {
        downloading = false;
        downloadingTask = null;
        if (releaseId != requestedReleaseId || assetId != requestedAssetId) {
            deleteFile(downloadedFile);
            downloadingProgress = getCachedDownloadProgress();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            return;
        }
        if (downloadedFile != null && isDownloadedApkValid(downloadedFile)) {
            File finalizedFile = finalizeDownloadedApk(downloadedFile);
            if (finalizedFile != null) {
                if (downloadHostIndex < downloadHosts.size()) {
                    ZaStoGitMirrors.rememberGood(downloadHosts.get(downloadHostIndex));
                }
                path = finalizedFile.getAbsolutePath();
                downloadingProgress = 1.0f;
                save();
            } else {
                downloadingProgress = getCachedDownloadProgress();
            }
        } else if (downloadedFile != null && downloadedFile.isFile()
                && assetSize > 0L && downloadedFile.length() < assetSize) {
            // Some HTTP stacks report a clean EOF instead of throwing when the
            // connection disappears. This is still a resumable partial file.
            if (continueOnNextMirror()) {
                return;
            }
            downloadingProgress = getCachedDownloadProgress();
            FileLog.d("Forgejo update download paused at " + (int) (downloadingProgress * 100) + "%");
        } else if (downloadedFile != null) {
            downloadingProgress = 0.0f;
            deleteFile(downloadedFile);
            FileLog.e("Downloaded Forgejo release asset is not a valid ZaStoGram APK");
        } else {
            // Network failures leave the deterministic .part file in cache. A
            // later attempt, including after app restart, resumes that file.
            if (continueOnNextMirror()) {
                return;
            }
            downloadingProgress = getCachedDownloadProgress();
            if (downloadingProgress > 0.0f) {
                FileLog.d("Forgejo update download paused at " + (int) (downloadingProgress * 100) + "%");
            } else {
                FileLog.e("Failed to download Forgejo release asset");
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    /** The partial file is the same on every mirror, so the next one simply resumes it. */
    private boolean continueOnNextMirror() {
        if (downloadHostIndex + 1 >= downloadHosts.size()) {
            return false;
        }
        downloadHostIndex++;
        FileLog.d("zasto_update download_next_mirror host=" + downloadHosts.get(downloadHostIndex));
        downloadUpdate(true);
        return true;
    }

    private boolean isDownloadedApkValid(File file) {
        if (file == null || !file.isFile() || assetSize > 0L && file.length() != assetSize) {
            return false;
        }
        try {
            PackageInfo packageInfo = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageArchiveInfo(file.getAbsolutePath(), 0);
            return packageInfo != null && ApplicationLoader.getApplicationId().equals(packageInfo.packageName);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public void cancelDownloadingUpdate() {
        if (!downloading) {
            return;
        }
        if (downloadingTask != null) {
            downloadingTask.cancel(true);
            downloadingTask = null;
        }
        downloading = false;
        downloadingProgress = getCachedDownloadProgress();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public boolean isDownloading() {
        return downloading;
    }

    public float getDownloadingProgress() {
        return downloadingProgress;
    }

    private float getCachedDownloadProgress() {
        File partialFile = getPartialDownloadFile();
        if (partialFile == null || !partialFile.isFile() || assetSize <= 0L) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, (float) partialFile.length() / assetSize));
    }

    public File getDownloadedFile() {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        File file = new File(path);
        if (!file.exists()) {
            path = null;
            save();
            return null;
        }
        File completedFile = getCompletedDownloadFile();
        if (completedFile != null && !completedFile.equals(file) && isDownloadedApkValid(file)) {
            // Migrate a completed .part left by versions which downloaded into
            // Context.getCacheDir(). New downloads never expose that file.
            File finalizedFile = finalizeDownloadedApk(file);
            if (finalizedFile != null) {
                path = finalizedFile.getAbsolutePath();
                save();
                return finalizedFile;
            }
        }
        return file;
    }

    private int getCurrentVersionCode() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    private static final class ReleaseCandidate {
        private String version;
        private int displayVersionCode;
        private String changelog;
        private String releaseTag;
        private long releaseId;
        private long assetId;
        private long updateOrder;
        private String fileUrl;
        private long assetSize;
    }
}
