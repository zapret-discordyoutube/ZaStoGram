/*
 * ZaStoGram — просмотр одного файла лога.
 */

package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;

public class LogViewerActivity extends BaseFragment {

    private static final long MAX_BYTES = 1_500_000L;
    // One selectable TextView for a whole log made Android build display
    // lists for every line on first draw: 750 KB froze the UI for seconds
    // (ANR in logs (13)). The text is shown as a list of small blocks instead,
    // so only the visible ones are laid out and drawn.
    private static final int CHUNK_CHARS = 8 * 1024;

    private static final int MENU_SHARE = 1;
    private static final int MENU_COPY = 2;

    private final File file;
    private final ArrayList<String> chunks = new ArrayList<>();
    private ChunkAdapter adapter;
    private String loadedContent = "";

    public LogViewerActivity(Bundle args) {
        super(args);
        String path = args != null ? args.getString("path") : null;
        file = path != null ? new File(path) : null;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(file != null ? file.getName() : LocaleController.getString(R.string.ZaLogsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SHARE) {
                    if (file != null && file.exists()) {
                        ArrayList<File> files = new ArrayList<>();
                        files.add(file);
                        LogsActivity.shareFiles(getParentActivity(), files);
                    }
                } else if (id == MENU_COPY) {
                    copyToClipboard();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_SHARE, LocaleController.getString(R.string.ZaLogsShare));
        other.addSubItem(MENU_COPY, LocaleController.getString(R.string.ZaLogsCopyAll));

        RecyclerView listView = new RecyclerView(context);
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        listView.setClipToPadding(false);
        chunks.clear();
        chunks.add(LocaleController.getString(R.string.Loading));
        adapter = new ChunkAdapter(context);
        listView.setAdapter(adapter);

        fragmentView = listView;

        loadContent();
        return fragmentView;
    }

    private void loadContent() {
        if (file == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String content;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long len = raf.length();
                long start = len > MAX_BYTES ? len - MAX_BYTES : 0;
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                content = new String(buf, "UTF-8");
                if (start > 0) {
                    content = LocaleController.getString(R.string.ZaLogsTruncated) + "\n\n" + content;
                }
            } catch (Throwable e) {
                content = String.valueOf(e);
            }
            final String finalContent = content;
            final ArrayList<String> parts = splitIntoChunks(finalContent.length() == 0 ? LocaleController.getString(R.string.ZaLogsEmpty) : finalContent);
            AndroidUtilities.runOnUIThread(() -> {
                loadedContent = finalContent;
                chunks.clear();
                chunks.addAll(parts);
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private static ArrayList<String> splitIntoChunks(String text) {
        ArrayList<String> result = new ArrayList<>();
        int start = 0;
        final int length = text.length();
        while (start < length) {
            int end = Math.min(length, start + CHUNK_CHARS);
            if (end < length) {
                // Cut at a line break so no log line is split across blocks.
                int newline = text.lastIndexOf('\n', end);
                if (newline > start) {
                    end = newline + 1;
                }
            }
            String part = text.substring(start, end);
            if (part.endsWith("\n")) {
                part = part.substring(0, part.length() - 1);
            }
            result.add(part);
            start = end;
        }
        return result;
    }

    private class ChunkAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final Context context;

        ChunkAdapter(Context context) {
            this.context = context;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView textView = new TextView(context);
            textView.setTextIsSelectable(true);
            textView.setTypeface(Typeface.MONOSPACE);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setGravity(Gravity.TOP | Gravity.LEFT);
            textView.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(textView) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((TextView) holder.itemView).setText(chunks.get(position));
        }

        @Override
        public int getItemCount() {
            return chunks.size();
        }
    }

    private void copyToClipboard() {
        try {
            Context context = getParentActivity();
            if (context == null) {
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("log", loadedContent));
                Toast.makeText(context, LocaleController.getString(R.string.ZaLogsCopied), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignore) {
        }
    }
}
