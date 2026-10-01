/**
 * The HTTP edge shared by every endpoint: RFC 9457 problem responses, the request body limit and request-id
 * correlation. Story packages report failures by throwing an {@link dev.shortener.platform.http.ApiException}.
 */
package dev.shortener.platform.http;
