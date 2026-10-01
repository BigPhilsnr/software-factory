package dev.softwarefactory.platform;

import java.nio.file.Path;

/** Locates the repository checkout that owns scenarios, candidates ({@code .runs/}) and evidence. */
public final class WorkspaceRoot {
    private static final String MODULE_DIRECTORY = "factory";

    private WorkspaceRoot() {}

    /** The given directory, or its parent when the process was started inside the {@code factory} module. */
    public static Path from(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        Path name = root.getFileName();
        Path parent = root.getParent();
        return name != null && parent != null && MODULE_DIRECTORY.equals(name.toString()) ? parent : root;
    }
}
