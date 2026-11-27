package org.telegram.zastogram.naive;

import android.text.Spannable;
import android.text.Spanned;
import android.text.style.URLSpan;

import org.telegram.messenger.ProxyLinkHelper;
import org.telegram.ui.Components.URLSpanReplacement;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fork-only: makes a NaiveProxy link, naive+https://user:pass@host:port#name, one clickable span in a message.
 * The generic link detection does not know the scheme (and takes the host after user:pass@ for an e-mail domain),
 * so whatever links it made of the pieces are replaced. A click ends in ProxyLinkHelper / AndroidUtilities.showProxyAlert.
 */
public final class NaiveLinks {

    private static final Pattern PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}_$%=.+\\-])naive\\+https://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final String TRAILING = ".,;:!?)]}";

    private NaiveLinks() {
    }

    /** @return true when the text now has at least one such link */
    public static boolean apply(Spannable text) {
        if (text == null || text.length() < 16) {
            return false;
        }
        String string = text.toString();
        if (!string.toLowerCase(java.util.Locale.US).contains("naive+https://")) {
            return false;
        }
        boolean found = false;
        Matcher matcher = PATTERN.matcher(string);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            while (end > start + 1 && TRAILING.indexOf(string.charAt(end - 1)) >= 0) {
                end--;
            }
            String url = string.substring(start, end);
            if (ProxyLinkHelper.parseNaiveUrl(url) == null) {
                continue;
            }
            URLSpan[] spans = text.getSpans(start, end, URLSpan.class);
            boolean explicitLink = false;
            for (URLSpan span : spans) {
                int spanStart = text.getSpanStart(span);
                int spanEnd = text.getSpanEnd(span);
                // a link the sender made to somewhere else on purpose keeps its target
                if (span instanceof URLSpanReplacement && ((URLSpanReplacement) span).getTextStyleRun() != null
                        && !ProxyLinkHelper.isNaiveUrl(span.getURL())) {
                    explicitLink = true;
                    break;
                }
                if (spanStart < end && spanEnd > start) {
                    text.removeSpan(span);
                }
            }
            if (explicitLink) {
                continue;
            }
            text.setSpan(new URLSpanReplacement(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            found = true;
        }
        return found;
    }
}
