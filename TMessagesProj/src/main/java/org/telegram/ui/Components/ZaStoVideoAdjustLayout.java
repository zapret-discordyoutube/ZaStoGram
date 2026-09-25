package org.telegram.ui.Components;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSlider;
import org.telegram.ui.ActionBar.Theme;

// Точная подстройка в меню видео просмотрщика: скорость шагом 0.05x,
// усиление яркости и громкости. Кнопки ± повторяют шаг при удержании,
// тап по значению возвращает исходное значение.
public class ZaStoVideoAdjustLayout extends LinearLayout {

    public static final float SPEED_STEP = 0.05f;

    public static final float BRIGHTNESS_MIN = 0.5f;
    public static final float BRIGHTNESS_MAX = 2.5f;
    public static final float BRIGHTNESS_STEP = 0.1f;

    public static final float VOLUME_MIN = 0.5f;
    public static final float VOLUME_MAX = 4f;
    public static final float VOLUME_STEP = 0.1f;

    private static final String PREFS = "zasto_video_adjust";

    public interface Delegate {
        void onSpeedChanged(float speed, boolean isFinal);
        void onBrightnessChanged(float gain);
        void onVolumeChanged(float gain);
    }

    private final Delegate delegate;
    private final Row speedRow, brightnessRow, volumeRow;
    private float speed = 1f;
    private float brightness, volume;

    public ZaStoVideoAdjustLayout(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        setOrientation(VERTICAL);
        setMinimumWidth(AndroidUtilities.dp(196));

        brightness = getBrightnessGain();
        volume = getVolumeGain();

        speedRow = new Row(context, LocaleController.getString(R.string.ZaStoVideoSpeed)) {
            @Override
            void step(int dir, boolean isFinal) {
                float value = snap(speed + dir * SPEED_STEP, SPEED_STEP, ActionBarMenuSlider.SpeedSlider.MIN_SPEED, ActionBarMenuSlider.SpeedSlider.MAX_SPEED);
                if (value != speed || isFinal) {
                    speed = value;
                    update();
                    delegate.onSpeedChanged(value, isFinal);
                }
            }

            @Override
            void reset() {
                speed = 1f;
                update();
                delegate.onSpeedChanged(1f, true);
            }
        };
        brightnessRow = new Row(context, LocaleController.getString(R.string.ZaStoVideoBrightness)) {
            @Override
            void step(int dir, boolean isFinal) {
                setBrightness(snap(brightness + dir * BRIGHTNESS_STEP, BRIGHTNESS_STEP, BRIGHTNESS_MIN, BRIGHTNESS_MAX));
            }

            @Override
            void reset() {
                setBrightness(1f);
            }
        };
        volumeRow = new Row(context, LocaleController.getString(R.string.ZaStoVideoVolume)) {
            @Override
            void step(int dir, boolean isFinal) {
                setVolume(snap(volume + dir * VOLUME_STEP, VOLUME_STEP, VOLUME_MIN, VOLUME_MAX));
            }

            @Override
            void reset() {
                setVolume(1f);
            }
        };
        addView(speedRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
        addView(brightnessRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
        addView(volumeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
        update();
    }

    public void setSpeed(float speed) {
        this.speed = speed;
        update();
    }

    // Яркость и громкость работают только со своим плеером, не во встроенном веб-плеере.
    public void setGainVisible(boolean visible) {
        brightnessRow.setVisibility(visible ? VISIBLE : GONE);
        volumeRow.setVisibility(visible ? VISIBLE : GONE);
    }

    private void setBrightness(float value) {
        if (value == brightness) {
            return;
        }
        brightness = value;
        prefs().edit().putFloat("brightness", value).apply();
        update();
        delegate.onBrightnessChanged(value);
    }

    private void setVolume(float value) {
        if (value == volume) {
            return;
        }
        volume = value;
        prefs().edit().putFloat("volume", value).apply();
        update();
        delegate.onVolumeChanged(value);
    }

    private void update() {
        speedRow.valueView.setText(formatSpeed(speed) + "x");
        int offset = brightnessOffset(brightness);
        brightnessRow.valueView.setText(offset > 0 ? "+" + offset : "" + offset);
        volumeRow.valueView.setText(formatPercent(volume));
        speedRow.setLimits(speed > ActionBarMenuSlider.SpeedSlider.MIN_SPEED + 0.001f, speed < ActionBarMenuSlider.SpeedSlider.MAX_SPEED - 0.001f);
        brightnessRow.setLimits(brightness > BRIGHTNESS_MIN + 0.001f, brightness < BRIGHTNESS_MAX - 0.001f);
        volumeRow.setLimits(volume > VOLUME_MIN + 0.001f, volume < VOLUME_MAX - 0.001f);
    }

    public static float snap(float value, float step, float min, float max) {
        float snapped = Math.round(value / step) * step;
        snapped = Math.round(snapped * 100f) / 100f;
        return Math.max(min, Math.min(max, snapped));
    }

    public static String formatSpeed(float value) {
        float rounded = Math.round(value * 100f) / 100f;
        if (rounded == (long) rounded) {
            return "" + (long) rounded;
        }
        return "" + rounded;
    }

    private static String formatPercent(float value) {
        return Math.round(value * 100f) + "%";
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    public static float getBrightnessGain() {
        return Math.max(BRIGHTNESS_MIN, Math.min(BRIGHTNESS_MAX, prefs().getFloat("brightness", 1f)));
    }

    public static float getVolumeGain() {
        return Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, prefs().getFloat("volume", 1f)));
    }

    // Сдвиг яркости как Legacy Brightness в Photoshop: ко всем каналам прибавляется
    // одно и то же смещение, поэтому тени поднимаются так же, как света.
    // Шаг 0.1 даёт ±10 из 255: от -50 до +150, как предел Legacy в Photoshop.
    public static int brightnessOffset(float gain) {
        return Math.round((gain - 1f) * 100f);
    }

    // Краска слоя для TextureView: сдвигает RGB на смещение яркости.
    public static Paint createBrightnessPaint(float gain) {
        int offset = brightnessOffset(gain);
        if (offset == 0) {
            return null;
        }
        ColorMatrix matrix = new ColorMatrix(new float[] {
                1, 0, 0, 0, offset,
                0, 1, 0, 0, offset,
                0, 0, 1, 0, offset,
                0, 0, 0, 1, 0
        });
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        return paint;
    }

    private abstract static class Row extends LinearLayout {

        private static final long REPEAT_DELAY = 400, REPEAT_INTERVAL = 90;

        final TextView valueView;
        private final TextView minusView, plusView;
        private final Runnable minusRepeat = () -> repeat(-1);
        private final Runnable plusRepeat = () -> repeat(1);

        Row(Context context, String title) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(8), 0);

            TextView titleView = new TextView(context);
            titleView.setTextColor(0xfffafafa);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setSingleLine();
            titleView.setText(title);
            addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            minusView = createButton(context, "−", -1);
            addView(minusView, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

            valueView = new TextView(context);
            valueView.setTextColor(0xfffafafa);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            valueView.setTypeface(AndroidUtilities.bold());
            valueView.setGravity(Gravity.CENTER);
            valueView.setSingleLine();
            valueView.setBackground(Theme.createRadSelectorDrawable(0x0fffffff, 6, 6));
            valueView.setOnClickListener(v -> reset());
            addView(valueView, LayoutHelper.createLinear(58, 36, Gravity.CENTER_VERTICAL));

            plusView = createButton(context, "+", 1);
            addView(plusView, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));
        }

        @SuppressLint("ClickableViewAccessibility")
        private TextView createButton(Context context, String text, int dir) {
            TextView button = new TextView(context);
            button.setText(text);
            button.setTextColor(0xfffafafa);
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
            button.setTypeface(Typeface.DEFAULT);
            button.setGravity(Gravity.CENTER);
            button.setBackground(Theme.createSelectorDrawable(0x20ffffff, Theme.RIPPLE_MASK_CIRCLE_20DP));
            final Runnable repeat = dir < 0 ? minusRepeat : plusRepeat;
            button.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        step(dir, false);
                        AndroidUtilities.runOnUIThread(repeat, REPEAT_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        AndroidUtilities.cancelRunOnUIThread(repeat);
                        step(0, true);
                        return true;
                }
                return true;
            });
            return button;
        }

        private void repeat(int dir) {
            step(dir, false);
            AndroidUtilities.runOnUIThread(dir < 0 ? minusRepeat : plusRepeat, REPEAT_INTERVAL);
        }

        void setLimits(boolean canDecrease, boolean canIncrease) {
            minusView.setAlpha(canDecrease ? 1f : 0.4f);
            plusView.setAlpha(canIncrease ? 1f : 0.4f);
        }

        abstract void step(int dir, boolean isFinal);

        abstract void reset();

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            AndroidUtilities.cancelRunOnUIThread(minusRepeat);
            AndroidUtilities.cancelRunOnUIThread(plusRepeat);
            minusView.setPressed(false);
            plusView.setPressed(false);
        }
    }
}
