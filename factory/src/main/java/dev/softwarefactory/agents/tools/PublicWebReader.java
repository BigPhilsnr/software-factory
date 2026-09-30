package dev.softwarefactory.agents.tools;

import java.io.StringReader;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Fetches public HTTPS text. Validates the DNS answers actually used by the socket connection. */
public final class PublicWebReader implements AutoCloseable {
    private static final int MAX_BYTES = 512 * 1024;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(Duration.ofSeconds(15))
        .dns(host -> {
            List<InetAddress> addresses = List.of(InetAddress.getAllByName(host));
            if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublic(address))) {
                throw new UnknownHostException("Only public addresses may be browsed");
            }
            return addresses;
        }).build();

    static URI validateUrl(String input) {
        if (input.length() > 2048) throw new IllegalArgumentException("URL too long");
        URI uri = URI.create(input);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
            || (uri.getPort() != -1 && uri.getPort() != 443)) throw new SecurityException("Use public HTTPS URLs on port 443 without credentials");
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            throw new SecurityException("Local hosts cannot be browsed");
        }
        // OkHttp skips Dns for IP literals, so vet them here as well.
        if (host.contains(":") || host.matches("[0-9.]+")) {
            try { if (!isPublic(InetAddress.getByName(host))) throw new SecurityException("Private IP address"); }
            catch (UnknownHostException failure) { throw new IllegalArgumentException("Invalid address"); }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
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

    public String fetch(String input) throws Exception {
        URI url = validateUrl(input);
        for (int redirects = 0; redirects <= 3; redirects++) {
            Request request = new Request.Builder().url(url.toString())
                .header("User-Agent", "SoftwareFactoryPrototype/1.0")
                .header("Accept", "text/html, text/plain, application/json").build();
            try (var response = client.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    if (location == null) throw new IllegalStateException("Redirect without location");
                    url = validateUrl(url.resolve(location).toString());
                    continue;
                }
                if (!response.isSuccessful()) throw new IllegalStateException("Page returned HTTP " + response.code());
                var body = response.body();
                if (body == null || body.contentType() == null) throw new IllegalArgumentException("Missing text content type");
                String type = body.contentType().toString().toLowerCase(java.util.Locale.ROOT);
                if (!(type.startsWith("text/html") || type.startsWith("text/plain") || type.startsWith("application/json"))) {
                    throw new IllegalArgumentException("Only HTML, plain text and JSON pages are supported");
                }
                byte[] bytes = body.byteStream().readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Page exceeds 512 KiB");
                String text = new String(bytes, body.contentType().charset(StandardCharsets.UTF_8));
                if (type.startsWith("text/html")) text = htmlText(text);
                return "UNTRUSTED WEB CONTENT\nSource: " + url + "\n" + text.substring(0, Math.min(text.length(), 14000))
                    + (text.length() > 14000 ? "\n[truncated]" : "");
            }
        }
        throw new IllegalStateException("Too many redirects");
    }

    static String htmlText(String html) throws Exception {
        StringBuilder text = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private int hidden;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hidden++;
            }
            @Override public void handleEndTag(HTML.Tag tag, int position) {
                if ((tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) && hidden > 0) hidden--;
                if (hidden == 0) text.append('\n');
            }
            @Override public void handleText(char[] data, int position) { if (hidden == 0) text.append(data).append(' '); }
        }, true);
        return text.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n", "\n").strip();
    }

    @Override public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
