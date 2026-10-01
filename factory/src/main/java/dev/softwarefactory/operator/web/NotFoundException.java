package dev.softwarefactory.operator.web;

/** A requested operator resource (scenario specification, artifact) does not exist. */
final class NotFoundException extends RuntimeException {
    NotFoundException(String message) {
        super(message);
    }
}
