package dev.softwarefactory.generation.tools;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fetch targets come from provider search evidence, never from model-composed URLs. */
public final class WebAccessPolicy {
    private static final Logger LOG = LoggerFactory.getLogger(WebAccessPolicy.class);
    private final Set<String> sources = ConcurrentHashMap.newKeySet();

    public void registerSource(String source) {
        try {
            sources.add(canonical(source));
        } catch (IllegalArgumentException | SecurityException notFetchable) {
            // Non-public or non-HTTPS search results are not fetch capabilities.
            LOG.debug("Search result is not a fetchable source: {}", notFetchable.getMessage());
        }
    }

    public String approved(String requested) {
        String url = canonical(requested);
        if (!sources.contains(url)) throw new SecurityException("Fetch a URL returned by search evidence first");
        return url;
    }

    /**
     * Canonical form used for both registration and lookup: lower-case host, default port omitted,
     * query and fragment removed so model-supplied parameters cannot carry data out.
     */
    static String canonical(String value) {
        PublicUrls.validate(value);
        HttpUrl url = HttpUrl.parse(value);
        if (url == null) throw new IllegalArgumentException("Invalid URL");
        return url.newBuilder().query(null).fragment(null).build().toString();
    }
}
