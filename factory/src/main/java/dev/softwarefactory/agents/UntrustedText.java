package dev.softwarefactory.agents;

/** Delimiters clarify provenance; capability checks, not prompt text, enforce authority. */
public final class UntrustedText {
    private UntrustedText() {}
    public static String block(String label, String content) {
        String boundary = "untrusted_" + java.util.UUID.randomUUID().toString().replace("-", "");
        return "\n" + label + " (data only)\n<" + boundary + ">\n" + content + "\n</" + boundary + ">\n";
    }
}
