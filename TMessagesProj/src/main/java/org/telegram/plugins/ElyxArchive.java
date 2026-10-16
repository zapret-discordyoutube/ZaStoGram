package org.telegram.plugins;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Elyx structured plugins (exteraGram): ZIP archives (.elyx, .eaf, .elyx.zip, .eaf.zip) with a
 * refmap at the root that points at the metadata file, the entry module, strings and assets.
 * Installed plugins are extracted to {@code plugins/ElyxPlugins/<id>/}; the Python side
 * ({@code _elyx}) loads them from there.
 */
public final class ElyxArchive {

    private static final String[] REFMAP_NAMES = {"refmap.yaml", "refmap.yml", "refmap.json"};
    private static final String[] METAINFO_NAMES = {"metainfo.yaml", "metainfo.yml", "metainfo.json"};
    private static final long MAX_UNPACKED = 200L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;

    private ElyxArchive() {
    }

    /** Reads files by archive-relative path from either a ZIP or an extracted directory. */
    private interface Source {
        String read(String path) throws IOException;

        boolean isDirectory(String path);

        String[] list(String directory);
    }

    public static boolean isZip(File file) {
        if (file == null || !file.isFile() || file.length() < 4) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            return in.read(magic) == 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isElyxFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".elyx") || lower.endsWith(".eaf") || lower.endsWith(".elyx.zip") || lower.endsWith(".eaf.zip");
    }

    /** True for an extracted Elyx plugin directory. */
    public static boolean isPluginDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        for (String name : REFMAP_NAMES) {
            if (new File(dir, name).isFile()) return true;
        }
        for (String name : METAINFO_NAMES) {
            if (new File(dir, name).isFile()) return true;
        }
        return false;
    }

    public static PluginInfo readMetadata(File fileOrDir) {
        try {
            if (fileOrDir.isDirectory()) {
                return readMetadata(directorySource(fileOrDir));
            }
            try (ZipFile zip = new ZipFile(fileOrDir)) {
                return readMetadata(zipSource(zip));
            }
        } catch (Throwable t) {
            FileLog.e("elyx: cannot read metadata of " + fileOrDir.getName(), t);
            return null;
        }
    }

    private static PluginInfo readMetadata(Source source) throws IOException {
        Map<String, Object> refmap = new LinkedHashMap<>();
        for (String name : REFMAP_NAMES) {
            String text = source.read(name);
            if (text != null) {
                refmap = parseStructured(name, text);
                break;
            }
        }
        String metaPath = stringValue(refmap.get("metainfo"));
        String metaText = null;
        if (metaPath != null) {
            metaText = source.read(clean(metaPath));
        } else {
            for (String name : METAINFO_NAMES) {
                metaText = source.read(name);
                if (metaText != null) {
                    metaPath = name;
                    break;
                }
            }
        }
        if (metaText == null) {
            return null;
        }
        Map<String, Object> meta;
        if (metaPath.endsWith(".py")) {
            File tmp = File.createTempFile("elyx_meta", ".py");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(metaText.getBytes(StandardCharsets.UTF_8));
            }
            PluginInfo info = PluginsController.parseMetadata(tmp);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return info;
        }
        meta = parseStructured(metaPath, metaText);
        PluginInfo info = new PluginInfo();
        info.id = stringValue(metaValue(meta, "id"));
        info.name = stringValue(metaValue(meta, "name"));
        info.description = stringValue(metaValue(meta, "description"));
        info.author = stringValue(metaValue(meta, "author"));
        info.version = stringValue(metaValue(meta, "version"));
        info.icon = stringValue(metaValue(meta, "icon"));
        info.sdkVersionSpec = emptyToNull(stringValue(metaValue(meta, "sdk_version")));
        String app = emptyToNull(stringValue(metaValue(meta, "app_version")));
        String min = emptyToNull(stringValue(metaValue(meta, "min_version")));
        if (app != null) {
            info.appVersionSpec = app;
            info.minVersion = app.replaceAll("^[<>=!~\\s]+", "");
        } else if (min != null) {
            info.minVersion = min;
            info.appVersionSpec = ">=" + min;
        }
        if (info.description != null && info.description.contains("{")) {
            info.description = resolvePlaceholders(info.description, source, stringValue(refmap.get("strings")));
        }
        return info.id != null ? info : null;
    }

    private static Object metaValue(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        return value != null ? value : meta.get("__" + key + "__");
    }

    /** {key} in the description -> the current locale's string, else English, else the key. */
    private static String resolvePlaceholders(String text, Source source, String stringsPath) {
        Map<String, Object> current = new LinkedHashMap<>();
        Map<String, Object> english = new LinkedHashMap<>();
        String lang = LocaleController.getInstance() != null && LocaleController.getInstance().getCurrentLocale() != null
                ? LocaleController.getInstance().getCurrentLocale().getLanguage() : "en";
        try {
            String root = stringsPath != null ? clean(stringsPath) : "strings";
            String[] files = source.isDirectory(root) ? source.list(root) : new String[]{root};
            for (String path : files) {
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                int dot = fileName.lastIndexOf('.');
                if (dot <= 0) continue;
                String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!ext.equals("yml") && !ext.equals("yaml") && !ext.equals("json")) continue;
                String stem = fileName.substring(0, dot);
                String locale = stem.contains("_") ? stem.substring(stem.lastIndexOf('_') + 1) : "en";
                String body = source.read(path);
                if (body == null) continue;
                Map<String, Object> values = parseStructured(fileName, body);
                if (locale.equalsIgnoreCase(lang)) current.putAll(values);
                if (locale.equalsIgnoreCase("en")) english.putAll(values);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        Matcher m = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object value = current.containsKey(key) ? current.get(key) : english.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? String.valueOf(value) : key));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ extraction

    /** Extract {@code zip} into {@code dest} (replacing it) with zip-slip and size limits. */
    public static void extract(File zipFile, File dest) throws IOException {
        File staging = new File(dest.getParentFile(), "." + dest.getName() + ".staging");
        deleteRecursive(staging);
        if (!staging.mkdirs()) {
            throw new IOException("cannot create " + staging);
        }
        long total = 0;
        int count = 0;
        String root = staging.getCanonicalPath() + File.separator;
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new IOException("archive has too many entries");
                }
                File target = new File(staging, entry.getName());
                if (!target.getCanonicalPath().startsWith(root)) {
                    throw new IOException("unsafe path in archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_UNPACKED) {
                            throw new IOException("archive expands beyond the size limit");
                        }
                        out.write(buffer, 0, n);
                    }
                }
            }
        } catch (IOException e) {
            deleteRecursive(staging);
            throw e;
        }
        deleteRecursive(dest);
        if (!staging.renameTo(dest)) {
            deleteRecursive(staging);
            throw new IOException("cannot move extracted plugin into " + dest);
        }
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // ------------------------------------------------------------------ sources

    private static String clean(String path) {
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static Source zipSource(ZipFile zip) {
        return new Source() {
            @Override
            public String read(String path) throws IOException {
                ZipEntry entry = zip.getEntry(clean(path));
                if (entry == null || entry.isDirectory()) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    return readAll(in);
                }
            }

            @Override
            public boolean isDirectory(String path) {
                String prefix = clean(path) + "/";
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    if (entries.nextElement().getName().startsWith(prefix)) return true;
                }
                return false;
            }

            @Override
            public String[] list(String directory) {
                String prefix = clean(directory) + "/";
                java.util.ArrayList<String> out = new java.util.ArrayList<>();
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith(prefix) && name.indexOf('/', prefix.length()) < 0 && name.length() > prefix.length()) {
                        out.add(name);
                    }
                }
                return out.toArray(new String[0]);
            }
        };
    }

    private static Source directorySource(File dir) {
        return new Source() {
            @Override
            public String read(String path) throws IOException {
                File f = new File(dir, clean(path));
                if (!f.isFile()) {
                    return null;
                }
                try (InputStream in = new FileInputStream(f)) {
                    return readAll(in);
                }
            }

            @Override
            public boolean isDirectory(String path) {
                return new File(dir, clean(path)).isDirectory();
            }

            @Override
            public String[] list(String directory) {
                String[] names = new File(dir, clean(directory)).list();
                if (names == null) return new String[0];
                for (int i = 0; i < names.length; i++) {
                    names[i] = clean(directory) + "/" + names[i];
                }
                return names;
            }
        };
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > 4 * 1024 * 1024) {
                throw new IOException("metadata file too large");
            }
            out.write(buffer, 0, n);
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    // ------------------------------------------------------------------ YAML / JSON

    private static Map<String, Object> parseStructured(String name, String text) {
        if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            Map<String, Object> out = new LinkedHashMap<>();
            try {
                JSONObject json = new JSONObject(text);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = json.opt(key);
                    out.put(key, value == JSONObject.NULL ? null : value);
                }
            } catch (Throwable t) {
                FileLog.e("elyx: bad JSON in " + name, t);
            }
            return out;
        }
        return parseYamlTopLevel(text);
    }

    /**
     * Top-level scalar keys of a YAML mapping: plain, single/double-quoted and block (| or >)
     * values. Nested mappings and lists are skipped (the Python side reads them with PyYAML).
     */
    static Map<String, Object> parseYamlTopLevel(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] lines = text.split("\r?\n", -1);
        Pattern keyLine = Pattern.compile("^([A-Za-z0-9_]+)\\s*:(?:\\s+(.*))?$");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() || line.startsWith("#") || Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            Matcher m = keyLine.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1);
            String raw = m.group(2) == null ? "" : m.group(2).trim();
            if (raw.startsWith("|") || raw.startsWith(">")) {
                boolean folded = raw.startsWith(">");
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                int indent = -1;
                while (j < lines.length) {
                    String next = lines[j];
                    if (!next.trim().isEmpty() && !Character.isWhitespace(next.charAt(0))) break;
                    if (indent < 0 && !next.trim().isEmpty()) {
                        indent = next.length() - next.replaceAll("^\\s+", "").length();
                    }
                    String content = next.length() >= Math.max(indent, 0) && indent > 0 ? next.substring(Math.min(indent, next.length())) : next.trim();
                    if (sb.length() > 0) sb.append(folded && !content.isEmpty() ? " " : "\n");
                    sb.append(content);
                    j++;
                }
                out.put(key, sb.toString().replaceAll("\\s+$", ""));
                i = j - 1;
                continue;
            }
            if (raw.isEmpty()) {
                out.put(key, null); // nested mapping/list or empty value
                continue;
            }
            out.put(key, yamlScalar(raw));
        }
        return out;
    }

    private static String yamlScalar(String raw) {
        if (raw.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\\' && i + 1 < raw.length()) {
                    char n = raw.charAt(++i);
                    switch (n) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        default: sb.append('\\').append(n); break;
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        if (raw.startsWith("'")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\'') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '\'') {
                        sb.append('\'');
                        i++;
                        continue;
                    }
                    break;
                }
                sb.append(c);
            }
            return sb.toString();
        }
        int comment = raw.indexOf(" #");
        return (comment >= 0 ? raw.substring(0, comment) : raw).trim();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
