package dev.softwarefactory.agents.tools;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fetch targets come from provider search evidence, never from model-composed URLs. */
public final class WebAccessPolicy {
    private final Set<String> sources = ConcurrentHashMap.newKeySet();

    public void registerSource(String source) {
        try { sources.add(canonical(source)); }
        catch (RuntimeException invalid) { /* Non-public/non-HTTPS search results are not fetch capabilities. */ }
    }

    public String approved(String requested) {
        String url = canonical(requested);
        if (!sources.contains(url)) throw new SecurityException("Fetch a URL returned by search evidence first");
        return url;
    }

    private static String canonical(String value) {
        URI uri = PublicWebReader.validateUrl(value);
        // Strip queries and fragments without decoding/re-encoding the path.
        String raw = uri.toString();
        int query = raw.indexOf('?'), fragment = raw.indexOf('#');
        int end = query < 0 ? raw.length() : query;
        if (fragment >= 0) end = Math.min(end, fragment);
        return raw.substring(0, end);
    }
}
