package com.exteragram.messenger.utils.text;

import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.URLSpan;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LinkifyPort;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.URLSpanReplacement;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * exteraGram's text helpers that DEX plugins call for their settings copy: linkify URLs,
 * [markdown](links) and @usernames, then apply Telegram's **bold** tags.
 */
public abstract class LocaleUtils {

    private static final Pattern MARKDOWN_LINK_PATTERN =
            Pattern.compile("\\[([^]]+?)]\\(" + LinkifyPort.WEB_URL_REGEX + "\\)");

    public static String ensureUrlHasHttps(String url) {
        if (url == null || LinkifyPort.WEB_URL == null || !LinkifyPort.WEB_URL.matcher(url).matches()) {
            return url;
        }
        if (url.startsWith("http://") || url.startsWith("https://") || url.contains("://")) {
            return url;
        }
        return "https://" + url;
    }

    public static CharSequence formatWithURLs(CharSequence text) {
        if (TextUtils.isEmpty(text) || LinkifyPort.WEB_URL == null) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        Matcher matcher = LinkifyPort.WEB_URL.matcher(text);
        while (matcher.find()) {
            try {
                builder.setSpan(new URLSpanNoUnderline(ensureUrlHasHttps(matcher.group(0))),
                        matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return builder;
    }

    public static CharSequence formatWithUsernames(CharSequence text) {
        return formatWithUsernames(text, LaunchActivity.getSafeLastFragment());
    }

    public static CharSequence formatWithUsernames(CharSequence text, BaseFragment fragment) {
        return formatWithUsernames(text, fragment, null);
    }

    public static CharSequence formatWithUsernames(CharSequence text, BaseFragment fragment, Runnable onClick) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '@') {
                start = i;
                continue;
            }
            if (start == -1) {
                continue;
            }
            int next = i + 1;
            if (next != text.length() && (Character.isLetterOrDigit(text.charAt(next)) || text.charAt(next) == '_')) {
                continue;
            }
            if (next - start > 1) {
                URLSpan[] existing = builder.getSpans(start, next, URLSpan.class);
                if (existing == null || existing.length == 0) {
                    String username = text.subSequence(start, next).toString();
                    try {
                        builder.setSpan(new URLSpanNoUnderline(username) {
                            @Override
                            public void onClick(View widget) {
                                if (onClick != null) {
                                    onClick.run();
                                }
                                if (fragment != null && fragment.getMessagesController() != null) {
                                    fragment.getMessagesController().openByUserName(username.substring(1), fragment, 1);
                                }
                            }
                        }, start, next, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
            start = -1;
        }
        return builder;
    }

    public static void parseMarkdownLinks(CharSequence[] text) {
        parseMarkdownLinks(text, null);
    }

    public static void parseMarkdownLinks(CharSequence[] text, Runnable onClick) {
        if (text == null || text.length == 0 || text[0] == null) {
            return;
        }
        Spannable spannable = text[0] instanceof Spannable
                ? (Spannable) text[0] : Spannable.Factory.getInstance().newSpannable(text[0].toString());
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(spannable);
        ArrayList<String> sources = new ArrayList<>();
        ArrayList<CharSequence> replacements = new ArrayList<>();
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (start < 0 || end < start || end > spannable.length()) {
                continue;
            }
            SpannableStringBuilder label = new SpannableStringBuilder(spannable.subSequence(start, end));
            label.setSpan(new URLSpanReplacement(ensureUrlHasHttps(matcher.group(2))) {
                @Override
                public void onClick(View widget) {
                    if (onClick != null) {
                        onClick.run();
                    }
                    super.onClick(widget);
                }
            }, 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sources.add(matcher.group(0));
            replacements.add(label);
        }
        if (!sources.isEmpty()) {
            text[0] = TextUtils.replace(text[0], sources.toArray(new String[0]), replacements.toArray(new CharSequence[0]));
        }
    }

    public static CharSequence fromHtml(String html) {
        return new SpannableString(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
    }

    public static CharSequence fullyFormatText(CharSequence text) {
        return fullyFormatText(text, null, null);
    }

    public static CharSequence fullyFormatText(CharSequence text, BaseFragment fragment, Runnable onClick) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        CharSequence[] holder = new CharSequence[]{formatWithURLs(text)};
        parseMarkdownLinks(holder, onClick);
        CharSequence result = fragment != null && onClick != null
                ? formatWithUsernames(holder[0], fragment, onClick)
                : formatWithUsernames(holder[0]);
        return AndroidUtilities.replaceTags(result instanceof SpannableStringBuilder
                ? (SpannableStringBuilder) result : new SpannableStringBuilder(result));
    }
}
