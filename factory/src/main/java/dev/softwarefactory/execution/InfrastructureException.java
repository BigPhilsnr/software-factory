package dev.softwarefactory.execution;

import java.io.IOException;

/**
 * The local platform (Docker, validator image, Maven cache, control database) could not do its job.
 * Such failures say nothing about the candidate and must not consume its retry budget.
 */
public class InfrastructureException extends IOException {
    public InfrastructureException(String message) {
        super(message);
    }

    public InfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
