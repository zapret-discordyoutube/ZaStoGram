/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.proxy.ProxySettings;
import org.telegram.proxy.WebProxyTransport;

import java.io.UnsupportedEncodingException;
import java.net.IDN;
import java.net.URLDecoder;
import java.util.Locale;

public final class ProxyLinkHelper {

    public static final int TYPE_SOCKS5 = 0;
    public static final int TYPE_MTPROTO = 1;
    public static final int TYPE_WEB = 2;

    private static final LinkPrefix[] LINK_PREFIXES = new LinkPrefix[] {
            new LinkPrefix(TYPE_SOCKS5, "t.me/socks?"),
            new LinkPrefix(TYPE_SOCKS5, "telegram.me/socks?"),
            new LinkPrefix(TYPE_SOCKS5, "telegram.dog/socks?"),
            new LinkPrefix(TYPE_SOCKS5, "tg://socks?"),
            new LinkPrefix(TYPE_SOCKS5, "tg://socks/?"),
            new LinkPrefix(TYPE_SOCKS5, "tg:socks?"),
            new LinkPrefix(TYPE_SOCKS5, "tg:socks/?"),
            new LinkPrefix(TYPE_MTPROTO, "t.me/proxy?"),
            new LinkPrefix(TYPE_MTPROTO, "telegram.me/proxy?"),
            new LinkPrefix(TYPE_MTPROTO, "telegram.dog/proxy?"),
            new LinkPrefix(TYPE_MTPROTO, "tg://proxy?"),
            new LinkPrefix(TYPE_MTPROTO, "tg://proxy/?"),
            new LinkPrefix(TYPE_MTPROTO, "tg:proxy?"),
            new LinkPrefix(TYPE_MTPROTO, "tg:proxy/?"),
            new LinkPrefix(TYPE_WEB, "t.me/webproxy?"),
            new LinkPrefix(TYPE_WEB, "telegram.me/webproxy?"),
            new LinkPrefix(TYPE_WEB, "telegram.dog/webproxy?"),
            new LinkPrefix(TYPE_WEB, "tg://webproxy?"),
            new LinkPrefix(TYPE_WEB, "tg://webproxy/?"),
            new LinkPrefix(TYPE_WEB, "tg:webproxy?"),
            new LinkPrefix(TYPE_WEB, "tg:webproxy/?"),
    };

    private ProxyLinkHelper() {
    }

    public static ProxySettings.Type toSettingsType(int type) {
        switch (type) {
            case TYPE_MTPROTO:
                return ProxySettings.Type.MTPROTO;
            case TYPE_WEB:
                return ProxySettings.Type.WEB;
            case TYPE_SOCKS5:
            default:
                return ProxySettings.Type.SOCKS5;
        }
    }

    public static int fromSettingsType(ProxySettings.Type type) {
        if (type == ProxySettings.Type.MTPROTO) {
            return TYPE_MTPROTO;
        } else if (type == ProxySettings.Type.WEB) {
            return TYPE_WEB;
        }
        return TYPE_SOCKS5;
    }

    public static ProxyLink parse(String text) {
        return firstFromText(text);
    }

    public static ProxyLink firstFromText(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String lower = text.toLowerCase(Locale.US);
        LinkPrefix foundPrefix = null;
        int foundIndex = -1;
        for (LinkPrefix prefix : LINK_PREFIXES) {
            int index = lower.indexOf(prefix.value);
            if (index >= 0 && (foundIndex < 0 || index < foundIndex)) {
                foundIndex = index;
                foundPrefix = prefix;
            }
        }
        if (foundPrefix == null) {
            return null;
        }
        int queryStart = foundIndex + foundPrefix.value.length();
        String query = trimQuery(text.substring(queryStart));
        return parseQuery(foundPrefix.type, query);
    }

    public static ProxyLink firstFromClipboard(Context context) {
        if (context == null) {
            return null;
        }
        android.content.ClipboardManager clipboardManager = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
            return null;
        }
        android.content.ClipData clipData = clipboardManager.getPrimaryClip();
        if (clipData == null) {
            return null;
        }
        for (int i = 0; i < clipData.getItemCount(); i++) {
            try {
                CharSequence text = clipData.getItemAt(i).coerceToText(context);
                ProxyLink link = firstFromText(text != null ? text.toString() : null);
                if (link != null) {
                    return link;
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    public static String dedupeKey(ProxyLink link) {
        if (link == null) {
            return "";
        }
        return link.type
                + "|" + normalizeKeyPart(link.address)
                + "|" + link.port
                + "|" + normalizeKeyPart(link.username)
                + "|" + normalizeKeyPart(link.password)
                + "|" + normalizeKeyPart(link.secret);
    }

    private static ProxyLink parseQuery(int type, String query) {
        if (TextUtils.isEmpty(query)) {
            return null;
        }
        String address = "";
        String portString = "";
        String username = "";
        String password = "";
        String secret = "";
        String[] params = query.split("&");
        for (String param : params) {
            String[] pair = param.split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            String key = pair[0].toLowerCase(Locale.US);
            String value = decode(pair[1]);
            switch (key) {
                case "server":
                    address = normalizeAddress(value);
                    break;
                case "host":
                    if (type == TYPE_WEB && TextUtils.isEmpty(address)) {
                        address = normalizeAddress(value);
                    }
                    break;
                case "port":
                    portString = value;
                    break;
                case "user":
                    if (type == TYPE_SOCKS5) {
                        username = value;
                    }
                    break;
                case "pass":
                    if (type == TYPE_SOCKS5) {
                        password = value;
                    }
                    break;
                case "secret":
                    if (type == TYPE_MTPROTO || type == TYPE_WEB) {
                        secret = value;
                    }
                    break;
            }
        }
        if (type == TYPE_WEB) {
            // WEB proxy: an HTTPS host with an optional path plus an MTProxy
            // secret (marked when a path is present); the link carries no port.
            ProxySettings web = ProxySettings.webProxy(address, secret);
            if (web == null) {
                return null;
            }
            return new ProxyLink(type, web.getAddress(), 0, "", "", web.getSecret());
        }
        int port = Utilities.parseInt(portString);
        if (TextUtils.isEmpty(address) || port <= 0 || port > 65535) {
            return null;
        }
        return new ProxyLink(type, address, port, username, password, secret);
    }

    private static String trimQuery(String query) {
        int end = query.length();
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (Character.isWhitespace(ch) || ch == '"' || ch == '\'' || ch == '<' || ch == '>') {
                end = i;
                break;
            }
        }
        return query.substring(0, end);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return value;
        }
    }

    private static String normalizeAddress(String value) {
        try {
            if (AndroidUtilities.checkHostForPunycode(value)) {
                return IDN.toASCII(value, IDN.ALLOW_UNASSIGNED);
            }
        } catch (Exception ignore) {
        }
        return value;
    }

    private static String normalizeKeyPart(String value) {
        return value != null ? value.toLowerCase(Locale.US) : "";
    }

    private static final class LinkPrefix {
        final int type;
        final String value;

        LinkPrefix(int type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class ProxyLink {
        public final int type;
        public final String address;
        public final int port;
        public final String username;
        public final String password;
        public final String secret;

        ProxyLink(int type, String address, int port, String username, String password, String secret) {
            this.type = type;
            this.address = address != null ? address : "";
            this.port = port;
            this.username = username != null ? username : "";
            this.password = password != null ? password : "";
            this.secret = secret != null ? secret : "";
        }

        public ProxySettings toSettings() {
            return ProxySettings.builder()
                    .setType(toSettingsType(type))
                    .setAddress(address)
                    .setPort(port)
                    .setUser(username)
                    .setPassword(password)
                    .setSecret(secret)
                    .build();
        }
    }
}
