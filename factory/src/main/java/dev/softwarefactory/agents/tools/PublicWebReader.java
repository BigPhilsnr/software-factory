package dev.softwarefactory.agents.tools;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/** Fetches public HTTPS text. Validates the DNS answers actually used by the socket connection. */
public final class PublicWebReader implements AutoCloseable {
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_TEXT = 14_000;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_REDIRECTS = 3;
    private static final int HTTPS_PORT = 443;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9.]+");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SPACE_AROUND_NEWLINE = Pattern.compile(" ?\\n ?");
    private static final String HIDDEN_ELEMENTS = "script, style, noscript, template, svg";
    private final OkHttpClient client = new OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(CALL_TIMEOUT)
        .dns(host -> {
            List<InetAddress> addresses = List.of(InetAddress.getAllByName(host));
            if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublic(address))) {
                throw new UnknownHostException("Only public addresses may be browsed");
            }
            return addresses;
        }).build();

    static URI validateUrl(String input) {
        if (input.length() > MAX_URL_LENGTH) throw new IllegalArgumentException("URL too long");
        URI uri = URI.create(input);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
            || (uri.getPort() != -1 && uri.getPort() != HTTPS_PORT)) {
            throw new SecurityException("Use public HTTPS URLs on port 443 without credentials");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            throw new SecurityException("Local hosts cannot be browsed");
        }
        // OkHttp skips Dns for IP literals, so vet them here as well.
        if (host.contains(":") || IPV4_LITERAL.matcher(host).matches()) {
            try {
                if (!isPublic(InetAddress.getByName(host))) throw new SecurityException("Private IP address");
            } catch (UnknownHostException failure) {
                throw new IllegalArgumentException("Invalid address", failure);
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && (b == 168 || b == 0 || (b == 88 && c == 99)))
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113));
        }
        // Global unicast only; exclude transition/tunneling and documentation ranges.
        return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 0x02)
            && !(a == 0x20 && b == 0x01 && ((bytes[2] & 255) < 2
                || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)))
            && !(a == 0x3f && b == 0xff);
    }

    /**
     * Fetches an already approved URL. Redirects are followed only to HTTPS locations on the same origin
     * as the approved URL, with any query or fragment removed.
     */
    public String fetch(String approved) throws IOException {
        validateUrl(approved);
        HttpUrl origin = HttpUrl.parse(approved);
        if (origin == null) throw new IllegalArgumentException("Invalid URL");
        HttpUrl url = origin;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            Request request = new Request.Builder().url(url)
                .header("User-Agent", "SoftwareFactoryPrototype/1.0")
                .header("Accept", "text/html, text/plain, application/json").build();
            try (var response = client.newCall(request).execute()) {
                if (response.isRedirect()) {
                    url = sameOriginRedirect(origin, url, response.header("Location"));
                    continue;
                }
                if (!response.isSuccessful()) throw new IllegalStateException("Page returned HTTP " + response.code());
                var body = response.body();
                var contentType = body.contentType();
                if (contentType == null) throw new IllegalArgumentException("Missing text content type");
                String type = contentType.toString().toLowerCase(Locale.ROOT);
                if (!(type.startsWith("text/html") || type.startsWith("text/plain") || type.startsWith("application/json"))) {
                    throw new IllegalArgumentException("Only HTML, plain text and JSON pages are supported");
                }
                byte[] bytes = body.byteStream().readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Page exceeds 512 KiB");
                String text = new String(bytes, contentType.charset(StandardCharsets.UTF_8));
                if (type.startsWith("text/html")) text = htmlText(text);
                return "Source: " + url + "\n" + text.substring(0, Math.min(text.length(), MAX_TEXT))
                    + (text.length() > MAX_TEXT ? "\n[truncated]" : "");
            }
        }
        throw new IllegalStateException("Too many redirects");
    }

    static HttpUrl sameOriginRedirect(HttpUrl origin, HttpUrl current, String location) {
        if (location == null) throw new IllegalStateException("Redirect without location");
        HttpUrl target = current.resolve(location);
        if (target == null || !target.isHttps() || !target.host().equals(origin.host()) || target.port() != origin.port()) {
            throw new SecurityException("Redirect leaves the approved origin");
        }
        HttpUrl stripped = target.newBuilder().query(null).fragment(null).build();
        validateUrl(stripped.toString());
        return stripped;
    }

    /** Visible text only: scripts, styles and other non-rendered content are dropped, never executed. */
    static String htmlText(String html) {
        Document document = Jsoup.parse(html);
        document.select(HIDDEN_ELEMENTS).remove();
        StringBuilder text = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) text.append(textNode.text()).append(' ');
            }
            @Override public void tail(Node node, int depth) {
                if (node instanceof Element element && element.isBlock()) text.append('\n');
            }
        }, document.body());
        String collapsed = SPACE_AROUND_NEWLINE.matcher(HORIZONTAL_SPACE.matcher(text).replaceAll(" ")).replaceAll("\n");
        return BLANK_LINES.matcher(collapsed).replaceAll("\n").strip();
    }

    @Override public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
