package com.exteragram.messenger.utils.system;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.ViewGroup;

import com.exteragram.messenger.ExteraConfig;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/** exteraGram's haptics helpers; getType() is what DEX plugins pass to performHapticFeedback. */
public abstract class VibratorUtils {

    private static Vibrator vibrator() {
        return (Vibrator) ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public static void disableHapticFeedback(View view) {
        if (view == null) {
            return;
        }
        view.setHapticFeedbackEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableHapticFeedback(group.getChildAt(i));
            }
        }
    }

    /** The haptic constant if in-app vibration is on, otherwise -1 (no feedback). */
    public static int getType(int type) {
        return ExteraConfig.getInAppVibration() ? type : -1;
    }

    private static boolean isVibrationAllowed() {
        Vibrator vibrator = vibrator();
        return ExteraConfig.getInAppVibration() && vibrator != null && vibrator.hasVibrator();
    }

    public static void vibrate() {
        vibrate(200L);
    }

    public static void vibrate(long millis) {
        if (!isVibrationAllowed()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator().vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator().vibrate(millis);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void vibrateEffect(VibrationEffect effect) {
        if (Build.VERSION.SDK_INT < 26 || !isVibrationAllowed()) {
            return;
        }
        try {
            vibrator().cancel();
            vibrator().vibrate(effect);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
