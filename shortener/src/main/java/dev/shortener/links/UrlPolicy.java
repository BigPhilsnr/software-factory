package dev.shortener.links;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Accepts only public absolute HTTP(S) targets. Decisions are purely syntactic: no DNS lookup or fetch
 * happens, so a public-looking DNS name that resolves to a private address is not detected.
 */
public final class UrlPolicy {
    public static final int MAX_URL_BYTES = 2048;

    private static final Set<String> WEB_SCHEMES = Set.of("http", "https");
    private static final Pattern TRAILING_DOT = Pattern.compile("\\.$");
    private static final Pattern LABEL_SEPARATOR = Pattern.compile(".", Pattern.LITERAL);
    /** A DNS name must end in a TLD-like label; anything numeric-looking there is an IP literal form. */
    private static final Pattern TOP_LEVEL_LABEL = Pattern.compile("[a-z][a-z0-9-]*");
    /** The only accepted IPv4 spelling: four dotted decimal octets without leading zeros. */
    private static final Pattern DOTTED_DECIMAL_IPV4 =
        Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final List<String> LOCAL_NAMES = List.of(
        "localhost", "local", "internal", "intranet", "lan", "home.arpa");
    private static final List<Cidr> NON_PUBLIC_IPV4 = Cidr.all(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
        "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4");
    /** Within global unicast 2000::/3: IETF protocol space (incl. Teredo), documentation and 6to4. */
    private static final Cidr GLOBAL_UNICAST_IPV6 = Cidr.parse("2000::/3");
    private static final List<Cidr> NON_PUBLIC_IPV6 = Cidr.all("2001::/23", "2001:db8::/32", "2002::/16");

    private final String shortenerHost;

    public UrlPolicy(URI shortenerBaseUrl) {
        this.shortenerHost = canonicalHost(shortenerBaseUrl.getHost());
    }

    /** Returns the normalized ASCII form of an acceptable target or throws {@link InvalidLinkException}. */
    public String validate(String value) {
        if (value == null || value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidLinkException("URL must not contain control characters");
        }
        URI uri = parse(value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!WEB_SCHEMES.contains(scheme) || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
            throw new InvalidLinkException("URL must be an absolute HTTP or HTTPS URL without credentials");
        }
        rejectNonPublicHost(canonicalHost(uri.getHost()));
        String normalized = uri.toASCIIString();
        if (normalized.getBytes(StandardCharsets.US_ASCII).length > MAX_URL_BYTES) {
            throw new InvalidLinkException("URL must be at most " + MAX_URL_BYTES + " bytes once encoded");
        }
        return normalized;
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException malformed) {
            throw new InvalidLinkException("Malformed URL", malformed);
        }
    }

    private void rejectNonPublicHost(String host) {
        if (host.equals(shortenerHost) || host.endsWith("." + shortenerHost)) {
            throw new InvalidLinkException("Target must not be this shortener");
        }
        if (host.startsWith("[")) {
            rejectNonPublic(ipLiteral(host));
            return;
        }
        String[] labels = LABEL_SEPARATOR.split(host, -1);
        for (String label : labels) {
            if (label.isEmpty()) throw new InvalidLinkException("Malformed host name");
        }
        if (!TOP_LEVEL_LABEL.matcher(labels[labels.length - 1]).matches()) {
            // Decimal, octal, hex and shortened IPv4 spellings all end in a numeric-looking label.
            if (!DOTTED_DECIMAL_IPV4.matcher(host).matches()) throw new InvalidLinkException("Ambiguous numeric host");
            rejectNonPublic(ipLiteral(host));
            return;
        }
        if (labels.length == 1 || LOCAL_NAMES.stream().anyMatch(local -> host.equals(local) || host.endsWith("." + local))) {
            throw new InvalidLinkException("Target must not be a local host name");
        }
    }

    private static void rejectNonPublic(byte[] address) {
        boolean nonPublic = address.length == Cidr.IPV4_BYTES
            ? NON_PUBLIC_IPV4.stream().anyMatch(range -> range.contains(address))
            : !GLOBAL_UNICAST_IPV6.contains(address) || NON_PUBLIC_IPV6.stream().anyMatch(range -> range.contains(address));
        if (nonPublic) throw new InvalidLinkException("Target must not be a private or reserved IP address");
    }

    /** Only syntactically validated literals reach here, so the JDK parses them without DNS. */
    private static byte[] ipLiteral(String literal) {
        try {
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException invalid) {
            throw new InvalidLinkException("Invalid IP address", invalid);
        }
    }

    private static String canonicalHost(String host) {
        return TRAILING_DOT.matcher(host.toLowerCase(Locale.ROOT)).replaceFirst("");
    }

    private static final class Cidr {
        static final int IPV4_BYTES = 4;
        private static final int BITS_PER_BYTE = 8;
        private static final int BYTE_MASK = 0xff;
        private final byte[] network;
        private final int prefixLength;

        private Cidr(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static List<Cidr> all(String... ranges) {
            return Arrays.stream(ranges).map(Cidr::parse).toList();
        }

        static Cidr parse(String range) {
            int slash = range.indexOf('/');
            return new Cidr(ipLiteral(range.substring(0, slash)), Integer.parseInt(range.substring(slash + 1)));
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int fullBytes = prefixLength / BITS_PER_BYTE;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }
            int remainingBits = prefixLength % BITS_PER_BYTE;
            if (remainingBits == 0) return true;
            int mask = (BYTE_MASK << (BITS_PER_BYTE - remainingBits)) & BYTE_MASK;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
