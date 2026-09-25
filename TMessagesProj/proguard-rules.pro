-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

-keep class org.webrtc.* { *; }
-keep class org.webrtc.audio.* { *; }
-keep class org.webrtc.voiceengine.* { *; }
-keep class org.telegram.messenger.* { *; }
-keep class org.telegram.messenger.camera.* { *; }
-keep class org.telegram.messenger.secretmedia.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.time.* { *; }
-keep class org.telegram.messenger.video.* { *; }
-keep class org.telegram.messenger.voip.* { *; }
-keep class org.telegram.SQLite.** { *; }
-keep class org.telegram.tgnet.ConnectionsManager { *; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.tgnet.RequestDelegate { *; }

# ===== ZaStoGram plugin engine =====
# Plugins import Telegram classes by full name and reflect on members by name
# (get_private_field / getDeclaredMethod / hooks). The project already sets -dontobfuscate,
# so names are preserved; the real risk is R8 *removing* members that only a plugin
# references reflectively (R8 fullMode tree-shakes per-member). -keep is a shrink root and
# prevents that across the whole Telegram surface. Cost: larger DEX — the trade-off for an
# open plugin engine.
-keep class org.telegram.** { *; }
# Engine bridge classes are called from Python by name — keep fully.
-keep class org.telegram.plugins.** { *; }
# exteraGram-compatibility bridge classes imported by community plugins (com.exteragram.messenger.*).
-keep class com.exteragram.messenger.** { *; }
# Embedded Python runtime (Chaquopy) and the Pine / Xposed hooking engine use JNI + reflection.
-keep class com.chaquo.python.** { *; }
-keep class top.canyie.pine.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keep class com.android.internal.util.** { *; }
-dontwarn com.chaquo.python.**
-dontwarn top.canyie.pine.**
-dontwarn de.robv.android.xposed.**
# ===== end ZaStoGram plugin engine =====

-keep class org.telegram.ui.Stories.recorder.FfmpegAudioWaveformLoader { *; }
-keep class androidx.mediarouter.app.MediaRouteButton { *; }
-keepclassmembers class ** {
    @android.webkit.JavascriptInterface <methods>;
}

# https://developers.google.com/ml-kit/known-issues#android_issues
-keep class com.google.mlkit.nl.languageid.internal.LanguageIdentificationJni { *; }

# Huawei Services
-keep class com.huawei.hianalytics.**{ *; }
-keep class com.huawei.updatesdk.**{ *; }
-keep class com.huawei.hms.**{ *; }

# Don't warn about checkerframework and Kotlin annotations
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

-keep class io.nano.tex.** {*;}

-keep class org.telegram.tgnet.** { *; }

# JLatexMath: macro/atom classes are loaded reflectively by Class.forName
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Use -keep to explicitly keep any other classes shrinking would remove
#-dontoptimize
#-dontobfuscate