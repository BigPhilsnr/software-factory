package dev.softwarefactory.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Removes validator and database containers on completion and after interrupted processes. */
final class ValidatorContainers {
    private static final Logger LOG = LoggerFactory.getLogger(ValidatorContainers.class);
    private static final String DOCKER = "docker";
    private static final String FORMAT = "--format";
    private static final Duration DOCKER_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern TAB = Pattern.compile("\t");
    private static final int LISTING_FIELDS = 3;
    private final HostCommand host;

    ValidatorContainers(HostCommand host) {
        this.host = host;
    }

    /**
     * Killing the Docker CLI on timeout does not stop the container. Remove the uniquely named container
     * even if this thread was interrupted: clear the flag for the cleanup command, then restore it.
     */
    void removeContainer(Path directory, String container) {
        boolean interrupted = Thread.interrupted();
        try {
            host.run(directory, List.of(DOCKER, "rm", "-f", container), DOCKER_TIMEOUT);
        } catch (IOException alreadyRemoved) {
            LOG.debug("Validator container {} was already removed: {}", container, alreadyRemoved.getMessage());
        } catch (InterruptedException cleanupInterrupted) {
            interrupted = true;
            LOG.warn("Interrupted while removing validator container {}", container);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Containers of other live factory processes are kept. */
    int sweepOrphanedContainers(boolean includeOwn) {
        Path here = Path.of(".").toAbsolutePath();
        long self = ProcessHandle.current().pid();
        int removed = 0;
        try {
            String listing = host.run(
                    here,
                    List.of(
                            DOCKER,
                            "ps",
                            "--all",
                            "--filter",
                            "name=" + DockerSandbox.CONTAINER_PREFIX,
                            FORMAT,
                            "{{.ID}}\t{{.Names}}\t{{.Label \"" + DockerSandbox.OWNER_LABEL + "\"}}"),
                    DOCKER_TIMEOUT);
            for (String line : listing.lines().filter(value -> !value.isBlank()).toList()) {
                String[] fields = TAB.split(line, -1);
                if (fields.length == LISTING_FIELDS
                        && fields[1].startsWith(DockerSandbox.CONTAINER_PREFIX)
                        && orphaned(fields[2], self, includeOwn)) {
                    host.run(here, List.of(DOCKER, "rm", "-f", fields[0]), DOCKER_TIMEOUT);
                    removed++;
                }
            }
        } catch (IOException unavailable) {
            LOG.debug("Skipped validator container sweep; Docker unavailable: {}", unavailable.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (removed > 0) LOG.warn("Removed {} orphaned validator container(s)", removed);
        return removed;
    }

    private static boolean orphaned(String ownerLabel, long self, boolean includeOwn) {
        long owner;
        try {
            owner = Long.parseLong(ownerLabel.strip());
        } catch (NumberFormatException unlabelled) {
            return true; // Created before ownership labels; --rm containers never outlive their run.
        }
        if (owner == self) return includeOwn;
        return ProcessHandle.of(owner).map(handle -> !handle.isAlive()).orElse(true);
    }
}
