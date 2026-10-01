package dev.shortener.links;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class UrlPolicy {
    private final String shortenerHost;

    public UrlPolicy() { this.shortenerHost = null; }
    public UrlPolicy(String baseUrl) { this.shortenerHost = URI.create(baseUrl).getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", ""); }

    public String validate(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 2048 || value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidLinkException("URL must be at most 2048 UTF-8 bytes and contain no control characters");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
                throw new InvalidLinkException("URL must be an absolute HTTP or HTTPS URL without credentials");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            if (host.equals(shortenerHost) || host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal") || (!host.contains(".") && !host.startsWith("["))) {
                throw new InvalidLinkException("Target must not be a local host or this shortener");
            }
            if (host.startsWith("[") || host.matches("[0-9.]+")) {
                if (!host.startsWith("[") && java.util.Arrays.stream(host.split("\\."))
                    .anyMatch(part -> part.length() > 1 && part.startsWith("0"))) {
                    throw new InvalidLinkException("Ambiguous numeric IP address");
                }
                try {
                    // Only numeric literals reach this parser; no DNS lookup is performed.
                    var address = java.net.InetAddress.getByName(host);
                    byte[] bytes = address.getAddress();
                    int a = bytes[0] & 255, b = bytes[1] & 255;
                    boolean nonPublic = address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress();
                    if (bytes.length == 4) nonPublic |= a == 0 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
                        || (a == 192 && b == 0) || (a == 198 && (b == 18 || b == 19));
                    else nonPublic |= (a & 0xe0) != 0x20 || (a == 0x20 && b == 0x02)
                        || (a == 0x20 && b == 0x01 && (bytes[2] & 255) < 2);
                    if (nonPublic) throw new InvalidLinkException("Target must not be a private or reserved IP address");
                } catch (java.net.UnknownHostException invalid) { throw new InvalidLinkException("Invalid IP address"); }
            }
            return value;
        } catch (URISyntaxException ex) {
            throw new InvalidLinkException("Malformed URL", ex);
        }
    }
}
