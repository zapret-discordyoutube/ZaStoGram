#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = ROOT / "TMessagesProj_AppStandalone/src/main/java/org/telegram/messenger/ForgejoUpdaterController.java"
MIRRORS = ROOT / "TMessagesProj_AppStandalone/src/main/java/org/telegram/messenger/ZaStoGitMirrors.java"
HTTP_GET_FILE_TASK = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/web/HttpGetFileTask.java"
LOADER = ROOT / "TMessagesProj_AppStandalone/src/main/java/org/telegram/messenger/ApplicationLoaderImpl.java"
BASE_LOADER = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java"
LAYOUT = ROOT / "TMessagesProj_AppStandalone/src/main/java/org/telegram/ui/Components/ForgejoUpdateLayout.java"
ALERT = ROOT / "TMessagesProj_AppStandalone/src/main/java/org/telegram/ui/Components/ForgejoUpdateAlertDialog.java"
LAUNCH = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
BETA_UPDATE = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/BetaUpdate.java"
ANDROID_UTILITIES = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java"
SETTINGS_ACTIVITY = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java"
LIB_GRADLE = ROOT / "TMessagesProj/build.gradle"
APP_GRADLE = ROOT / "TMessagesProj_AppStandalone/build.gradle"
ROOT_GRADLE = ROOT / "build.gradle"
STANDALONE_MANIFEST = ROOT / "TMessagesProj/config/release/AndroidManifest_standalone.xml"
GOOGLE_SERVICES = ROOT / "TMessagesProj_AppStandalone/google-services.json"
WORKFLOW = ROOT / ".forgejo/workflows/build-apk.yml"
PROVIDER_PATHS = ROOT / "TMessagesProj/src/main/res/xml/provider_paths.xml"
MAIN_STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
RU_STRINGS = ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml"


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        print(f"FAIL: missing {path.relative_to(ROOT)}", file=sys.stderr)
        raise SystemExit(1)


def require(text: str, literal: str, description: str, failures: list[str]) -> None:
    if literal not in text:
        failures.append(f"{description}: missing {literal!r}")


def main() -> int:
    controller = read(CONTROLLER)
    mirrors = read(MIRRORS)
    http_get_file_task = read(HTTP_GET_FILE_TASK)
    loader = read(LOADER)
    base_loader = read(BASE_LOADER)
    layout = read(LAYOUT)
    alert = read(ALERT)
    launch = read(LAUNCH)
    beta_update = read(BETA_UPDATE)
    android_utilities = read(ANDROID_UTILITIES)
    settings_activity = read(SETTINGS_ACTIVITY)
    lib_gradle = read(LIB_GRADLE)
    app_gradle = read(APP_GRADLE)
    root_gradle = read(ROOT_GRADLE)
    standalone_manifest = read(STANDALONE_MANIFEST)
    google_services = read(GOOGLE_SERVICES)
    workflow = read(WORKFLOW)
    provider_paths = read(PROVIDER_PATHS)
    main_strings = read(MAIN_STRINGS)
    ru_strings = read(RU_STRINGS)
    failures: list[str] = []

    for literal in (
        "public boolean isCustomUpdate()",
        "return true;",
        "ForgejoUpdaterController.getInstance().checkForUpdate(force, whenDone)",
        "ForgejoUpdaterController.getInstance().downloadUpdate()",
        "new ForgejoUpdateLayout(activity, sideMenuContainer)",
        "ForgejoUpdateAlertDialog.show(context, update)",
        "ForgejoUpdaterController.getInstance().shouldShowUpdatePopup(force)",
        "ForgejoUpdaterController.getInstance().markUpdatePopupShown()",
        "public String getCustomBuildVersionInfo()",
    ):
        require(loader, literal, "Standalone loader must fully own app updates", failures)

    for literal in (
        'org.telegram.messenger.web.R.string.ZastoForgejoRepository',
        'String base = "https://git.zapret.moe/api/v1/repos/" + repository + "/releases";',
        'return isDevChannel() ? base + "?limit=100" : base + "/latest";',
        'release.optBoolean("draft", false)',
        'release.optBoolean("prerelease", false) != expectPrerelease',
        'asset.optString("browser_download_url", "")',
        'for (String abi : Build.SUPPORTED_ABIS)',
        '"ZaStoGram-standalone-" + abi + ".apk"',
        'candidate.releaseTag.equals(getInstalledReleaseTag())',
        'ApplicationLoader.getApplicationId().equals(packageInfo.packageName)',
        'file.length() != assetSize',
        'asset.optLong("id", 0L)',
        '"zastogram-update-" + releaseId + "-" + assetId + ".apk.part"',
        'new File(ApplicationLoader.applicationContext.getFilesDir(), "cache")',
        '"zastogram-update-" + releaseId + "-" + assetId + ".apk"',
        'partialFile.renameTo(completedFile)',
        'REMIND_LATER_INTERVAL = 24L * 60L * 60L * 1000L',
        'prefs.getString("snoozedReleaseTag", null)',
        'prefs.getString("skippedReleaseTag", null)',
        'public boolean shouldShowUpdatePopup(boolean force)',
        'public void markUpdatePopupShown()',
        'public void remindAboutCurrentUpdateLater()',
        'public void skipCurrentUpdate()',
        'candidate.releaseTag.equals(skippedReleaseTag)',
        '.setDestFile(partialFile)',
        '.setResumeExistingFile(true)',
        '.setKeepPartialFileOnCancel(true)',
        'setHeader("Accept", "application/json")',
        'setHeader("User-Agent", "ZaStoGram-Android-Updater")',
    ):
        require(controller, literal, "Forgejo updater channel/asset safety contract", failures)

    # git.zapret.moe is dropped by name in Russia: the updater reaches Forgejo through its mirrors
    # (numbered and, since 2026-10-30, random labels) and, when every known name is silent, learns the
    # names handed out now from the project's entry pages (Apps Script, gist; schema 1 families.git.hosts).
    for literal in (
        'requestReleases(ZaStoGitMirrors.hosts(), 0, false);',
        'ZaStoGitMirrors.fetchEntryNames(hosts, fresh -> {',
        'requestReleases(fresh, 0, true);',
        'ZaStoGitMirrors.rememberGood(host);',
        'downloadUrl = ZaStoGitMirrors.canonical(downloadUrl);',
    ):
        require(controller, literal, "Forgejo updater must try every mirror, then the entry pages", failures)
    for literal in (
        'BUILTIN = {"git2.zapret.moe", "git3.zapret.moe", "git4.zapret.moe", CANONICAL}',
        '"https://script.google.com/macros/s/AKfycbzcEPk67WW6fYC0vPVm397QyDFHd4m-SrfKDBnfvG8jAD-n_HXVv8AqQnEHZ6A42oIh/exec?format=json"',
        '"https://gist.githubusercontent.com/fuckyourkn/ecb9b6a03f53bddcdd47023ebc12d4f9/raw/targets.json"',
        'Pattern.compile("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\\\.zapret\\\\.moe$")',
        'Pattern.compile("^wiki\\\\d*\\\\.zapret\\\\.moe$")',
        'return NAME.matcher(name).matches() && !OTHER_SITE.matcher(name).matches();',
        'document.optInt("schema") != 1',
        'getJSONObject("families").getJSONObject("git").getJSONArray("hosts")',
        '"https://" + host + "/_sites/mirrors.json"',
    ):
        require(mirrors, literal, "Mirror names: built-in seed, entry pages, only *.zapret.moe git names", failures)
    if mirrors.find('"git2.zapret.moe"') > mirrors.find("CANONICAL}"):
        failures.append("The blocked main name git.zapret.moe must be the last built-in name")

    for literal in (
        'urlConnection.setRequestProperty("Range", "bytes=" + downloadedSize + "-")',
        'status == HttpURLConnection.HTTP_PARTIAL',
        'parseContentRangeStart(urlConnection.getHeaderField("Content-Range"))',
        'new FileOutputStream(file, resuming)',
        'downloadedSize != totalSize',
        'keepPartialFileOnCancel',
    ):
        require(http_get_file_task, literal, "HTTP file resume contract", failures)

    require(
        provider_paths,
        '<cache-path name="update_cache" path="."/>',
        "Downloaded update APKs must be shareable with the Android package installer",
        failures,
    )
    require(
        provider_paths,
        '<files-path name="cache" path="/cache/"/>',
        "Updater APKs must remain shareable with FileProvider rules from older releases",
        failures,
    )

    if "authorization" in controller.lower():
        failures.append("Android updater must use the public Forgejo Releases API without embedding credentials")

    custom_branch = launch.find("if (ApplicationLoader.applicationLoaderInstance.isCustomUpdate())")
    telegram_request = launch.find("new TLRPC.TL_help_getAppUpdate()", custom_branch)
    custom_return = launch.find("return;", custom_branch)
    if custom_branch < 0 or custom_return < 0 or telegram_request < 0 or not custom_return < telegram_request:
        failures.append("LaunchActivity must return from the custom updater before Telegram TL_help_getAppUpdate")
    for literal in (
        "public boolean allowCustomUpdateAppPopup(boolean force, boolean updateChanged)",
        "return force || updateChanged;",
    ):
        require(base_loader, literal, "Non-Forgejo custom updaters must retain their existing popup policy", failures)
    require(
        launch,
        "ApplicationLoader.applicationLoaderInstance.allowCustomUpdateAppPopup(force, updateChanged)",
        "Every automatic custom-update popup must respect its persisted snooze",
        failures,
    )

    for literal in (
        'System.getenv("ZASTO_UPDATE_CHANNEL") ?: "stable"',
        "ext.zastoReleaseIdentity",
        "updateChannel: zastoUpdateChannelValue",
    ):
        require(root_gradle, literal, "Build-wide release identity", failures)

    # A stable release must derive its versionCode from its own tag. Passing the
    # number by hand once shipped 1.1.13 with a versionCode BELOW 1.1.12, and
    # Android then refuses the install with nothing but "App not installed".
    for literal in (
        "zastoSemanticTag = (zastoReleaseTagValue =~",
        "zastoResolvedBuildNumber = major * 1000 + minor * 100 + patch",
        "buildNumber: zastoResolvedBuildNumber,",
    ):
        require(
            root_gradle,
            literal,
            "Semantic release tag must derive the versionCode",
            failures,
        )

    for literal in (
        'def zastoApplicationId = zastoUpdateChannel == "dev" ? "${APP_PACKAGE}.dev" : APP_PACKAGE',
        'def zastoApplicationName = zastoUpdateChannel == "dev" ? "ZaStoGram Dev" : "ZaStoGram"',
        "defaultConfig.applicationId = zastoApplicationId",
        'resValue "string", "ZastoApplicationName", zastoApplicationName',
        'resValue "string", "ZastoUpdateChannel", zastoUpdateChannel',
        'resValue "string", "ZastoReleaseTag", zastoReleaseIdentity.releaseTag',
        'resValue "string", "ZastoForgejoRepository", zastoReleaseIdentity.forgejoRepository',
        'resValue "integer", "ZastoBuildNumber", zastoBuildNumber.toString()',
        "buildConfig = false",
    ):
        require(app_gradle, literal, "Standalone package/update identity", failures)

    for literal in (
        "getEmbeddedReleaseTag()",
        "org.telegram.messenger.web.R.string.ZastoReleaseTag",
        "getEmbeddedBuildNumber()",
        "org.telegram.messenger.web.R.integer.ZastoBuildNumber",
    ):
        require(controller, literal, "Cacheable per-release resource identity", failures)

    release_identity_sources = "\n".join((app_gradle, controller, loader, layout, alert))
    for forbidden in (
        'buildConfigField "String", "ZASTO_RELEASE_TAG"',
        'buildConfigField "int", "ZASTO_BUILD_NUMBER"',
        'buildConfigField "String", "ZASTO_UPDATE_CHANNEL"',
        'buildConfigField "String", "ZASTO_FORGEJO_REPOSITORY"',
        "BuildConfig.ZASTO_RELEASE_TAG",
        "BuildConfig.ZASTO_BUILD_NUMBER",
        "BuildConfig.ZASTO_UPDATE_CHANNEL",
        "BuildConfig.ZASTO_FORGEJO_REPOSITORY",
    ):
        if forbidden in release_identity_sources:
            failures.append(f"Per-release value must not invalidate R8 through BuildConfig: {forbidden!r}")

    if "org.telegram.messenger.web.BuildConfig" in release_identity_sources:
        failures.append("Standalone release identity must not import the app BuildConfig")

    if "ZASTO_UPDATE_CHANNEL" in lib_gradle:
        failures.append("Update-channel identity belongs to standalone application resources, not the shared library")
    for literal in (
        'def zastoStandaloneDebugVersion = rootProject.ext.zastoReleaseIdentity.updateChannel == "dev"',
        'buildConfigField "boolean", "DEBUG_VERSION", zastoStandaloneDebugVersion.toString()',
    ):
        require(lib_gradle, literal, "Standalone dev diagnostic identity", failures)
    require(standalone_manifest, 'android:label="@string/ZastoApplicationName"', "Channel-specific Android app label", failures)
    require(google_services, '"package_name": "org.zastogram.messenger.dev"', "Dev Google services package mapping", failures)

    require(loader, "return getPackageName();", "Standalone package id must not depend on BuildConfig", failures)

    require(
        app_gradle,
        "output.versionCodeOverride = defaultConfig.versionCode * 100000 + zastoBuildNumber * 10 + abiVersionDigit",
        "Published APKs need monotonically increasing workflow version codes",
        failures,
    )

    for literal in (
        "python3 Tools/check_forgejo_update_contract.py",
        "ZASTO_UPDATE_CHANNEL: dev",
        "ZASTO_RELEASE_TAG: forgejo-build-${{ forgejo.run_number }}-${{ forgejo.run_attempt }}",
        "ZASTO_BUILD_NUMBER: ${{ forgejo.run_number }}",
        "ZASTO_FORGEJO_REPOSITORY: ${{ forgejo.repository }}",
        "https://data.forgejo.org/forgejo/upload-artifact@v4",
    ):
        require(workflow, literal, "Forgejo Actions background build identity", failures)

    if "${{ github." in workflow or "GITHUB_" in workflow:
        failures.append("Forgejo workflow must not use GitHub compatibility aliases")

    for text, description in (
        (layout, "Forgejo update drawer UI"),
        (alert, "Forgejo update alert UI"),
    ):
        require(text, "ForgejoUpdaterController.isDevChannel()", description, failures)
        require(text, '"ZaStoGram.apk"', description, failures)

    for literal in (
        "updater.remindAboutCurrentUpdateLater()",
        "updater.skipCurrentUpdate()",
        "R.string.AppUpdateSkipVersion",
        ".forceVerticalButtons()",
        "public static boolean show(Context context, BetaUpdate update)",
    ):
        require(alert, literal, "Forgejo update prompt choices", failures)

    require(main_strings, '<string name="AppUpdateSkipVersion">Skip this version</string>', "Skip-version label", failures)
    require(ru_strings, '<string name="AppUpdateSkipVersion">Пропустить эту версию</string>', "Russian skip-version label", failures)
    require(main_strings, 'name="ZaStoGramVersion"', "ZaStoGram build-version label", failures)
    require(ru_strings, 'name="ZaStoGramVersion"', "Russian ZaStoGram build-version label", failures)

    for literal in (
        "pInfo.versionCode / 100000",
        "getCustomBuildVersionInfo()",
        'telegramVersion + "\\n" + customVersion',
    ):
        require(android_utilities, literal, "Version footer must show Telegram and ZaStoGram identities", failures)
    require(
        settings_activity,
        "return AndroidUtilities.getBuildVersionInfo();",
        "Settings footer must use the combined build identity",
        failures,
    )

    for literal in (
        "public final long updateOrder;",
        "updateOrder > update.updateOrder",
        "new BetaUpdate(version, displayVersionCode, changelog, updateOrder)",
    ):
        target = controller if literal.startswith("new BetaUpdate") else beta_update
        require(target, literal, "Release ordering must not depend on Telegram APP_VERSION_CODE", failures)

    if failures:
        print("Forgejo updater contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("Forgejo updater contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
