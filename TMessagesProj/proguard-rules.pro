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
# (get_private_field / getDeclaredMethod / hooks). Names must stay as they are
# everywhere, libraries included: exteraGram DEX cores are compiled against
# unrenamed AndroidX/Kotlin, and with obfuscation on (the upstream default; this
# file only claimed -dontobfuscate) re_extera crashed with NoSuchMethodError on
# androidx.collection.LongSparseArray.get(J) (27.09.2026).
-dontobfuscate
# R8 still *removes* members that only a plugin references (fullMode tree-shakes
# per member). -keep is a shrink root and prevents that across the whole Telegram
# surface. Cost: larger DEX — the trade-off for an open plugin engine.
-keep class org.telegram.** { *; }
# Collections used by plugin cores directly (LongSparseArray.get and friends).
-keep class androidx.collection.** { *; }
# Engine bridge classes are called from Python by name — keep fully.
-keep class org.telegram.plugins.** { *; }
# exteraGram-compatibility bridge classes imported by community plugins (com.exteragram.messenger.*).
-keep class com.exteragram.messenger.** { *; }
# Embedded Python runtime (Chaquopy) and the Pine / Xposed hooking engine use JNI + reflection.
-keep class com.chaquo.python.** { *; }
-keep class top.canyie.pine.** { *; }
-keep class de.robv.android.xposed.** { *; }
# DexMaker generates plugin proxy classes at runtime and reflects on its own/dx classes.
-keep class com.android.dx.** { *; }
-dontwarn com.android.dx.**
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

-keep class org.telegram.utils.code.highlight.PrismaHighlighter {
    private static native <methods>;
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