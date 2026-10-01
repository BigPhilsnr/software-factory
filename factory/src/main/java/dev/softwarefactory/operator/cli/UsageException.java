package dev.softwarefactory.operator.cli;

/** Malformed command-line arguments; reported as usage with exit code 2. */
final class UsageException extends Exception {
    UsageException() {
        super("usage");
    }

    UsageException(Throwable cause) {
        super("usage", cause);
    }
}
