package dev.softwarefactory.validation;

import dev.softwarefactory.platform.InfrastructureException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Disposable PostgreSQL reachable only through its isolated loopback network namespace. */
final class ValidationDatabase {
    static final String IMAGE = "postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea";
    private final HostCommand host;

    ValidationDatabase(HostCommand host) {
        this.host = host;
    }

    void start(Path candidate, String name) throws IOException, InterruptedException {
        try {
            host.run(
                    candidate,
                    List.of(
                            "docker",
                            "run",
                            "--detach",
                            "--rm",
                            "--name",
                            name,
                            "--label",
                            DockerSandbox.OWNER_LABEL + "="
                                    + ProcessHandle.current().pid(),
                            "--network",
                            "none",
                            "--cpus",
                            "1",
                            "--memory",
                            "256m",
                            "--pids-limit",
                            "64",
                            "--cap-drop",
                            "ALL",
                            "--security-opt",
                            "no-new-privileges",
                            "--read-only",
                            "--user",
                            "postgres",
                            "--tmpfs",
                            "/var/lib/postgresql/data:rw,nosuid,size=256m,mode=1777",
                            "--tmpfs",
                            "/var/run/postgresql:rw,nosuid,size=16m,mode=1777",
                            "--tmpfs",
                            "/tmp:rw,nosuid,size=16m",
                            "--env",
                            "PGDATA=/var/lib/postgresql/data/pgdata",
                            "--env",
                            "POSTGRES_DB=shortener",
                            "--env",
                            "POSTGRES_USER=shortener",
                            "--env",
                            "POSTGRES_PASSWORD=shortener",
                            IMAGE),
                    Duration.ofSeconds(30));
            host.run(
                    candidate,
                    List.of(
                            "docker",
                            "exec",
                            name,
                            "sh",
                            "-c",
                            "for attempt in $(seq 1 30); do pg_isready -h 127.0.0.1 -U shortener -d shortener && exit 0; sleep 1; done; exit 1"),
                    Duration.ofSeconds(40));
        } catch (IOException unavailable) {
            throw new InfrastructureException("Isolated validation database could not start", unavailable);
        }
    }
}
