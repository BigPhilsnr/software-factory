package dev.softwarefactory.platform;

/** Makes operator- or model-supplied text safe to place in a single log line. */
public final class LogText {
    private LogText() {}

    /** Replaces line breaks so a logged value cannot forge additional log entries. */
    public static String singleLine(Object value) {
        return String.valueOf(value).replaceAll("[\r\n]", " ");
    }
}
