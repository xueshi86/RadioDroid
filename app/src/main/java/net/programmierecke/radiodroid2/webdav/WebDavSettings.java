package net.programmierecke.radiodroid2.webdav;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class WebDavSettings {
    private final String url;
    private final String directory;
    private final String username;
    private final String password;

    public WebDavSettings(String url, String username, String password) {
        this(url, "", username, password);
    }

    public WebDavSettings(String url, String directory, String username, String password) {
        this.url = normalizeUrl(url);
        this.directory = normalizeDirectory(directory);
        this.username = require(username, "username");
        this.password = require(password, "password");
    }

    public String getUrl() { return url; }
    public String getDirectory() { return directory; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }

    /** 完整基础地址 = 服务器地址 + 可选目录（url 以 / 结尾，directory 不带首尾斜杠） */
    public String getBaseUrl() {
        return directory.isEmpty() ? url : url + directory + "/";
    }

    public static String normalizeDirectory(String value) {
        if (value == null) return "";
        String input = value.trim();
        if (input.isEmpty()) return "";
        for (int i = 0; i < input.length(); i++) {
            if (Character.isISOControl(input.charAt(i))) throw new IllegalArgumentException("Invalid directory");
        }
        if (input.contains("\\")) throw new IllegalArgumentException("Invalid directory");

        String normalized = input.replaceAll("/+", "/");
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty()) return "";

        String lower = normalized.toLowerCase(Locale.US);
        if (lower.contains("%2e%2e") || lower.contains("%2f")) {
            throw new IllegalArgumentException("Invalid directory path");
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Invalid directory path");
            }
        }
        return normalized;
    }

    public static String normalizeUrl(String value) {
        String input = require(value, "url").trim();
        for (int i = 0; i < input.length(); i++) {
            if (Character.isISOControl(input.charAt(i))) throw new IllegalArgumentException("Invalid URL");
        }

        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Invalid URL scheme");
        }

        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid URL");
        }

        String path = uri.getPath();
        if (path != null) {
            String lower = path.toLowerCase(Locale.US);
            if (lower.contains("/../") || lower.endsWith("/..") || lower.contains("/./") || lower.endsWith("/.") || lower.contains("%2e%2e") || lower.contains("%2f")) {
                throw new IllegalArgumentException("Invalid URL path");
            }
        }

        return input.endsWith("/") ? input : input + "/";
    }

    private static String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
