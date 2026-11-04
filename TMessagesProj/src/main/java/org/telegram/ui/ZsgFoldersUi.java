package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ZsgFolders;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Small dialogs for ZaStoGram's own folders (see ZsgFolders). */
public class ZsgFoldersUi {

    private static void showNameDialog(BaseFragment fragment, String title, String initial, Utilities.Callback<String> done) {
        showTextDialog(fragment, title, initial, false, done);
    }

    private static void showTextDialog(BaseFragment fragment, String title, String initial, boolean allowEmpty, Utilities.Callback<String> done) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        final EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setText(initial);
        input.setSelection(initial.length());
        input.setHint(LocaleController.getString(R.string.ZsgFoldersName));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String text = input.getText().toString().trim();
            if (allowEmpty || !text.isEmpty()) {
                done.run(text);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** Creates a folder (inside parentId, 0 for the top level) and opens it to choose the chats. */
    public static void showCreate(BaseFragment fragment, int parentId) {
        showNameDialog(fragment, LocaleController.getString(R.string.ZsgFoldersNew), "", name -> {
            final int account = fragment.getCurrentAccount();
            final int id = ZsgFolders.getInstance(account).create(name, parentId);
            if (id != 0) {
                final MessagesController controller = fragment.getMessagesController();
                controller.applyLocalFolders();
                final MessagesController.DialogFilter filter = controller.dialogFiltersById.get(id);
                if (filter != null) {
                    fragment.presentFragment(new FilterCreateActivity(filter));
                }
            }
        });
    }

    /** Rename, subfolder, parent, order and synchronization of a folder. */
    public static void showActions(BaseFragment fragment, int folderId) {
        final Context context = fragment.getParentActivity();
        final int account = fragment.getCurrentAccount();
        final ZsgFolders store = ZsgFolders.getInstance(account);
        final ZsgFolders.Folder folder = store.find(folderId);
        if (context == null || folder == null) {
            return;
        }
        final CharSequence[] items = new CharSequence[] {
            LocaleController.getString(R.string.ZsgFoldersRename),
            LocaleController.getString(R.string.ZsgFoldersIcon),
            LocaleController.getString(R.string.ZsgFoldersColor),
            LocaleController.getString(R.string.ZsgFoldersSubfolder),
            LocaleController.getString(R.string.ZsgFoldersParent),
            LocaleController.getString(R.string.ZsgFoldersMoveUp),
            LocaleController.getString(R.string.ZsgFoldersMoveDown),
            LocaleController.getString(folder.sync ? R.string.ZsgFoldersSyncOn : R.string.ZsgFoldersSyncOff)
        };
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(folder.title);
        builder.setItems(items, (dialog, which) -> {
            switch (which) {
                case 0:
                    showNameDialog(fragment, LocaleController.getString(R.string.ZsgFoldersRename), folder.title, name -> store.rename(folderId, name));
                    break;
                case 1:
                    showTextDialog(fragment, LocaleController.getString(R.string.ZsgFoldersIcon), folder.icon, true, value -> store.setIcon(folderId, value));
                    break;
                case 2:
                    showColorChooser(fragment, folderId);
                    break;
                case 3:
                    showCreate(fragment, folderId);
                    break;
                case 4:
                    showParentChooser(fragment, folderId);
                    break;
                case 5:
                    store.moveBy(folderId, -1);
                    break;
                case 6:
                    store.moveBy(folderId, 1);
                    break;
                case 7:
                    store.setSync(folderId, !folder.sync);
                    Toast.makeText(context, LocaleController.getString(!folder.sync ? R.string.ZsgFoldersSyncOn : R.string.ZsgFoldersSyncOff), Toast.LENGTH_SHORT).show();
                    break;
            }
        });
        fragment.showDialog(builder.create());
    }

    private static final String[] COLOR_NAMES = {
        "Red", "Pink", "Purple", "Deep purple", "Indigo", "Blue",
        "Cyan", "Teal", "Green", "Lime", "Orange", "Brown"
    };
    private static final String[] COLOR_VALUES = {
        "#e57373", "#f06292", "#ba68c8", "#9575cd", "#7986cb", "#64b5f6",
        "#4dd0e1", "#4db6ac", "#81c784", "#dce775", "#ffb74d", "#a1887f"
    };

    /** A ready color, no color, or any color typed as #RRGGBB. */
    private static void showColorChooser(BaseFragment fragment, int folderId) {
        final Context context = fragment.getParentActivity();
        final ZsgFolders store = ZsgFolders.getInstance(fragment.getCurrentAccount());
        final ZsgFolders.Folder folder = store.find(folderId);
        if (context == null || folder == null) {
            return;
        }
        final CharSequence[] items = new CharSequence[COLOR_NAMES.length + 2];
        items[0] = LocaleController.getString(R.string.ZsgFoldersColorNone);
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            items[i + 1] = COLOR_NAMES[i] + "  " + COLOR_VALUES[i];
        }
        items[items.length - 1] = LocaleController.getString(R.string.ZsgFoldersColorCustom);
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ZsgFoldersColor));
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                store.setColor(folderId, "");
            } else if (which == items.length - 1) {
                showTextDialog(fragment, LocaleController.getString(R.string.ZsgFoldersColor), folder.color, true, value -> {
                    if (!store.setColor(folderId, value) && !value.trim().isEmpty()) {
                        Toast.makeText(context, "#RRGGBB", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                store.setColor(folderId, COLOR_VALUES[which - 1]);
            }
        });
        fragment.showDialog(builder.create());
    }

    private static void showParentChooser(BaseFragment fragment, int folderId) {
        final Context context = fragment.getParentActivity();
        final ZsgFolders store = ZsgFolders.getInstance(fragment.getCurrentAccount());
        if (context == null) {
            return;
        }
        final ArrayList<Integer> ids = new ArrayList<>();
        final ArrayList<CharSequence> titles = new ArrayList<>();
        ids.add(0);
        titles.add(LocaleController.getString(R.string.ZsgFoldersTopLevel));
        for (ZsgFolders.Folder candidate : store.list()) {
            // A folder cannot be moved into itself or into its own subfolders.
            boolean inside = false;
            int up = candidate.id;
            for (int guard = 0; up != 0 && guard < 1000; guard++, up = store.parentOf(up)) {
                if (up == folderId) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                ids.add(candidate.id);
                titles.add(store.pathTitle(candidate.id));
            }
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ZsgFoldersParent));
        builder.setItems(titles.toArray(new CharSequence[0]), (dialog, which) -> store.move(folderId, ids.get(which)));
        fragment.showDialog(builder.create());
    }
}
