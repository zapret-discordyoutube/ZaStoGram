package com.exteragram.messenger.preferences.chats.components;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.exteragram.messenger.preferences.components.AltSeekbar;
import com.exteragram.messenger.preferences.components.CustomPreferenceCell;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

/** exteraGram's slider preference row: a title, the current value and a seek bar. */
public class SliderPreviewCell extends FrameLayout implements CustomPreferenceCell {

    public interface OnSliderChangedListener {
        void onChanged(float value);
    }

    public final AltSeekbar seekBar;
    private final TextView titleView;
    private final TextView valueView;
    private final int min;
    private final int max;
    private final String suffix;
    private OnSliderChangedListener listener;

    public SliderPreviewCell(INavigationLayout layout, Context context, int step, int min, int max, float value,
                             String title, String startLabel, String endLabel, boolean showValue) {
        super(context);
        this.min = min;
        this.max = Math.max(max, min + 1);
        this.suffix = "";
        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setText(title);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 21, 12, 70, 0));

        valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
        valueView.setVisibility(showValue ? VISIBLE : GONE);
        addView(valueView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.TOP, 0, 12, 21, 0));

        seekBar = new AltSeekbar(context);
        seekBar.setReportChanges(true);
        seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                float current = SliderPreviewCell.this.min + progress * (SliderPreviewCell.this.max - SliderPreviewCell.this.min);
                if (step > 0) {
                    current = Math.round(current / step) * step;
                }
                valueView.setText(String.valueOf(Math.round(current)));
                if (listener != null) {
                    listener.onChanged(current);
                }
            }
        });
        seekBar.setProgress((value - min) / (float) (this.max - min));
        valueView.setText(String.valueOf(Math.round(value)));
        addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.TOP, 6, 40, 6, 0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(88), MeasureSpec.EXACTLY));
    }

    public SliderPreviewCell setListener(OnSliderChangedListener listener) {
        this.listener = listener;
        return this;
    }

    public OnSliderChangedListener getListener() {
        return listener;
    }
}
