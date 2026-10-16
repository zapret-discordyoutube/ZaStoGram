package com.exteragram.messenger.drawer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** exteraGram's drawer row (icon + title + unread counter), used by DEX plugins that add rows. */
public class DrawerMenuItemView extends FrameLayout {

    private final ImageView imageView;
    private final TextView textView;
    private final TextView counterView;
    private int itemId;
    private int currentAccount;

    public DrawerMenuItemView(Context context) {
        super(context);
        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        addView(imageView, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 19, 0, 0, 0));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setLines(1);
        textView.setMaxLines(1);
        textView.setSingleLine(true);
        textView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 72, 0, 60, 0));

        counterView = new TextView(context);
        counterView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        counterView.setTypeface(AndroidUtilities.bold());
        counterView.setGravity(Gravity.CENTER);
        counterView.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
        counterView.setVisibility(GONE);
        addView(counterView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 20, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 22, 0));

        setBackground(Theme.getSelectorDrawable(false));
        updateColors();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48), MeasureSpec.EXACTLY));
    }

    public void setMenuItem(int id, int account, int iconRes, CharSequence text) {
        itemId = id;
        currentAccount = account;
        imageView.setImageResource(iconRes);
        textView.setText(text);
        setTag(id);
    }

    public int getItemId() {
        return itemId;
    }

    public void updateColors() {
        textView.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));
        imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_menuItemIcon), PorterDuff.Mode.MULTIPLY));
        counterView.setTextColor(Theme.getColor(Theme.key_chats_unreadCounterText));
        counterView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), Theme.getColor(Theme.key_chats_unreadCounter)));
    }

    public void updateUnreadCounter(int count) {
        if (count > 0) {
            counterView.setText(String.valueOf(count));
            counterView.setVisibility(VISIBLE);
        } else {
            counterView.setVisibility(GONE);
        }
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }
}
