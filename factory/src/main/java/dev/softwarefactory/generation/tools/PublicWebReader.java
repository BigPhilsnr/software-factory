package dev.softwarefactory.generation.tools;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Fetches public HTTPS text. Validates the DNS answers actually used by the socket connection. */
public final class PublicWebReader implements AutoCloseable {
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_TEXT = 14_000;
    private static final int MAX_REDIRECTS = 3;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);
    private static final String HTML = "text/html";
    private static final List<String> READABLE_TYPES = List.of(HTML, "text/plain", "application/json");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(CALL_TIMEOUT)
            .dns(host -> {
                List<InetAddress> addresses = List.of(InetAddress.getAllByName(host));
                if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !PublicAddresses.isPublic(address))) {
                    throw new UnknownHostException("Only public addresses may be browsed");
                }
                return addresses;
            })
            .build();

    /**
     * Fetches an already approved URL. Redirects are followed only to HTTPS locations on the same origin
     * as the approved URL, with any query or fragment removed.
     */
    public String fetch(String approved) throws IOException {
        PublicUrls.validate(approved);
        HttpUrl origin = HttpUrl.parse(approved);
        if (origin == null) throw new IllegalArgumentException("Invalid URL");
        HttpUrl url = origin;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "SoftwareFactoryPrototype/1.0")
                    .header("Accept", "text/html, text/plain, application/json")
                    .build();
            try (var response = client.newCall(request).execute()) {
                if (!response.isRedirect()) return readableText(url, response);
                url = sameOriginRedirect(origin, url, response.header("Location"));
            }
        }
        throw new IllegalStateException("Too many redirects");
    }

    /** The bounded text of a successful HTML, plain-text or JSON response. */
    private static String readableText(HttpUrl url, Response response) throws IOException {
        if (!response.isSuccessful()) throw new IllegalStateException("Page returned HTTP " + response.code());
        MediaType contentType = readableType(response.body().contentType());
        String type = contentType.toString().toLowerCase(Locale.ROOT);
        byte[] bytes = response.body().byteStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Page exceeds 512 KiB");
        String decoded = new String(bytes, charset(contentType));
        return "Source: " + url + "\n" + bounded(type.startsWith(HTML) ? HtmlText.visible(decoded) : decoded);
    }

    private static MediaType readableType(MediaType contentType) {
        if (contentType == null) throw new IllegalArgumentException("Missing text content type");
        String type = contentType.toString().toLowerCase(Locale.ROOT);
        if (READABLE_TYPES.stream().noneMatch(type::startsWith)) {
            throw new IllegalArgumentException("Only HTML, plain text and JSON pages are supported");
        }
        return contentType;
    }

    private static Charset charset(MediaType contentType) {
        Charset declared = contentType.charset(StandardCharsets.UTF_8);
        return declared == null ? StandardCharsets.UTF_8 : declared;
    }

    private static String bounded(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "\n[truncated]" : text;
    }

    static HttpUrl sameOriginRedirect(HttpUrl origin, HttpUrl current, String location) {
        if (location == null) throw new IllegalStateException("Redirect without location");
        HttpUrl target = current.resolve(location);
        if (target == null
                || !target.isHttps()
                || !target.host().equals(origin.host())
                || target.port() != origin.port()) {
            throw new SecurityException("Redirect leaves the approved origin");
        }
        HttpUrl stripped = target.newBuilder().query(null).fragment(null).build();
        PublicUrls.validate(stripped.toString());
        return stripped;
    }

    @Override
    public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
