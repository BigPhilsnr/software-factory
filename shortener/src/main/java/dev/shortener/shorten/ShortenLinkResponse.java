package dev.shortener.shorten;

/** The created link as the client sees it: its canonical code and the full URL to share. */
record ShortenLinkResponse(String code, String shortUrl) {}
