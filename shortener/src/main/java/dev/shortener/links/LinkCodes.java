package dev.shortener.links;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** The single definition of a valid, canonical short code. */
public final class LinkCodes {
    /** Canonical codes are lowercase; lookups and aliases are case-insensitive. */
    private static final Pattern CANONICAL = Pattern.compile("[a-z0-9-]{4,32}");

    /**
     * Words that collide with framework, browser or operational paths. Shorter words such as {@code api}
     * need no entry: the 4-character minimum already makes them impossible codes.
     */
    public static final Set<String> RESERVED = Set.of(
        "error", "logout", "login", "actuator", "health", "favicon", "robots", "info");

    private LinkCodes() {}

    /** Returns the canonical form of a usable code, or empty when it can never name a link. */
    public static Optional<String> canonical(String code) {
        if (code == null) return Optional.empty();
        String normalized = code.toLowerCase(Locale.ROOT);
        if (!CANONICAL.matcher(normalized).matches() || RESERVED.contains(normalized)) return Optional.empty();
        return Optional.of(normalized);
    }
}
