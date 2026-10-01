package dev.shortener.links;

import dev.shortener.ShortenerProperties;
import dev.shortener.ratelimit.CreationRateLimitExceededException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every API failure is an RFC 9457 problem document carrying a stable machine-readable {@code error} code.
 * Framework failures (405, 415, 404, 400, ...) keep their own status via {@link ResponseEntityExceptionHandler}.
 */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    /** Problem-document extension member holding the stable error code. */
    private static final String ERROR_PROPERTY = "error";
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    private static final Map<Integer, String> CODES = Map.of(
        HttpStatus.BAD_REQUEST.value(), "invalid_request",
        HttpStatus.NOT_FOUND.value(), "not_found",
        HttpStatus.METHOD_NOT_ALLOWED.value(), "method_not_allowed",
        HttpStatus.NOT_ACCEPTABLE.value(), "not_acceptable",
        HttpStatus.CONFLICT.value(), "alias_conflict",
        HttpStatus.CONTENT_TOO_LARGE.value(), "request_too_large",
        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "unsupported_media_type",
        HttpStatus.TOO_MANY_REQUESTS.value(), "rate_limited",
        HttpStatus.SERVICE_UNAVAILABLE.value(), "temporarily_unavailable",
        HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal_error");

    private final String unavailableRetryAfter;

    public ApiErrors(ShortenerProperties properties) {
        this.unavailableRetryAfter = Long.toString(properties.http().unavailableRetryAfter().toSeconds());
    }

    /** The stable code for a status; unlisted statuses derive one from their reason phrase. */
    private static String errorCode(HttpStatusCode status) {
        String known = CODES.get(status.value());
        if (known != null) return known;
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "http_" + status.value() : resolved.name().toLowerCase(Locale.ROOT);
    }

    @ExceptionHandler(InvalidLinkException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidLinkException failure) {
        return problem(HttpStatus.BAD_REQUEST, failure.getMessage(), new HttpHeaders());
    }

    @ExceptionHandler(LinkNotFoundException.class)
    ResponseEntity<ProblemDetail> missing(LinkNotFoundException failure) {
        return problem(HttpStatus.NOT_FOUND, failure.getMessage(), new HttpHeaders());
    }

    @ExceptionHandler(ShortenerService.AliasConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ShortenerService.AliasConflictException failure) {
        return problem(HttpStatus.CONFLICT, failure.getMessage(), new HttpHeaders());
    }

    @ExceptionHandler(CreationRateLimitExceededException.class)
    ResponseEntity<ProblemDetail> rateLimited(CreationRateLimitExceededException failure) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfterSeconds()));
        return problem(HttpStatus.TOO_MANY_REQUESTS, failure.getMessage(), headers);
    }

    /** Only failures that a retry can plausibly fix are reported as 503. */
    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class,
        ShortenerService.CapacityException.class})
    ResponseEntity<ProblemDetail> unavailable(RuntimeException failure) {
        LOG.warn("Link dependency temporarily unavailable: {}", failure.toString());
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, unavailableRetryAfter);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable", headers);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> dataAccess(DataAccessException failure) {
        LOG.error("Link persistence failed", failure);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", new HttpHeaders());
    }

    /** Last resort; {@link ErrorResponse} types the base class does not list keep their own status. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception failure, WebRequest request) {
        if (failure instanceof ErrorResponse response) {
            return handleExceptionInternal(failure, response.getBody(), response.getHeaders(), response.getStatusCode(), request);
        }
        LOG.error("Unexpected request failure", failure);
        ResponseEntity<ProblemDetail> problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", new HttpHeaders());
        return ResponseEntity.status(problem.getStatusCode()).headers(problem.getHeaders()).body(problem.getBody());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(failure, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty(ERROR_PROPERTY, errorCode(response.getStatusCode()));
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail, HttpHeaders headers) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty(ERROR_PROPERTY, errorCode(status));
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
