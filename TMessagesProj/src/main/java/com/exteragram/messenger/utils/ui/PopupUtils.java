package com.exteragram.messenger.utils.ui;

import android.content.Context;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;

import java.util.ArrayList;

/** exteraGram's single-choice popup helpers, used by BasePreferencesActivity and DEX plugins. */
public abstract class PopupUtils {

    public interface OnItemClickListener {
        void onClick(int which);
    }

    public static void showDialog(CharSequence[] entries, String title, int selected, Context context, OnItemClickListener listener) {
        showDialog(entries, null, title, selected, context, listener, null, true);
    }

    public static void showDialog(CharSequence[] entries, int[] icons, String title, int selected, Context context, OnItemClickListener listener) {
        showDialog(entries, icons, title, selected, context, listener, null, true);
    }

    public static void showDialog(CharSequence[] entries, int[] icons, String title, int selected, Context context,
                                  OnItemClickListener listener, Theme.ResourcesProvider resourcesProvider, boolean withRadio) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);
        if (withRadio) {
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            builder.setView(layout);
            for (int i = 0; i < entries.length; i++) {
                RadioColorCell cell = new RadioColorCell(context);
                cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
                cell.setTag(i);
                cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, resourcesProvider),
                        Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
                cell.setTextAndValue(entries[i], selected == i);
                cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
                layout.addView(cell);
                cell.setOnClickListener(v -> {
                    builder.getDismissRunnable().run();
                    listener.onClick((Integer) v.getTag());
                });
            }
        } else {
            if (icons != null) {
                builder.setItems(entries, icons, (dialog, which) -> listener.onClick(which));
            } else {
                builder.setItems(entries, (dialog, which) -> listener.onClick(which));
            }
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    public static void showDialogWithoutRadio(ArrayList<? extends CharSequence> entries, String title, Context context, OnItemClickListener listener) {
        showDialog(entries.toArray(new CharSequence[0]), null, title, -1, context, listener, null, false);
    }
}
