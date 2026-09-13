package net.programmierecke.radiodroid2.webdav;

public class WebDavException extends Exception {
    public enum Kind { AUTHENTICATION, PERMISSION, NOT_FOUND, PROTOCOL, NETWORK, INVALID_DATA, EMPTY_FILE, LOCAL_DATABASE, STORAGE, CONFIGURATION }
    private final Kind kind;

    public WebDavException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public WebDavException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }
}
