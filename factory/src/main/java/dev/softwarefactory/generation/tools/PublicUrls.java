package dev.softwarefactory.generation.tools;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The only URLs an agent may fetch: credential-free HTTPS on port 443 to a public, non-local host. */
final class PublicUrls {
    private static final int MAX_LENGTH = 2048;
    private static final int HTTPS_PORT = 443;
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9.]+");
    private static final List<String> LOCAL_SUFFIXES = List.of(".localhost", ".local", ".internal");

    private PublicUrls() {}

    /**
     * @throws SecurityException for a URL that is not public HTTPS
     * @throws IllegalArgumentException for a URL that is malformed or too long
     */
    static URI validate(String input) {
        if (input.length() > MAX_LENGTH) throw new IllegalArgumentException("URL too long");
        URI uri = URI.create(input);
        if (!isCredentialFreeHttps(uri)) {
            throw new SecurityException("Use public HTTPS URLs on port 443 without credentials");
        }
        requirePublicHost(uri.getHost().toLowerCase(Locale.ROOT));
        return uri;
    }

    private static boolean isCredentialFreeHttps(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == HTTPS_PORT);
    }

    private static void requirePublicHost(String host) {
        if ("localhost".equals(host) || LOCAL_SUFFIXES.stream().anyMatch(host::endsWith)) {
            throw new SecurityException("Local hosts cannot be browsed");
        }
        // OkHttp skips Dns for IP literals, so vet them here as well.
        if (host.contains(":") || IPV4_LITERAL.matcher(host).matches()) requirePublicLiteral(host);
    }

    private static void requirePublicLiteral(String host) {
        boolean publicAddress;
        try {
            publicAddress = PublicAddresses.isPublic(InetAddress.getByName(host));
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException("Invalid address", failure);
        }
        if (!publicAddress) throw new SecurityException("Private IP address");
    }
}
