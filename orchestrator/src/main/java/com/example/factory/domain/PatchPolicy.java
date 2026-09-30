package com.example.factory.domain;

/** Deterministic A2 floor independent of model advice or scenario flags. */
public final class PatchPolicy {
    private PatchPolicy() {}

    public static boolean requiresApproval(TaskSpec task, String patch) {
        if (task.requiresApproval()) return true;
        return patch.lines().anyMatch(line -> {
            if (!line.startsWith("diff --git a/")) return false;
            int marker = line.indexOf(" b/");
            if (marker < 0) return true;
            String path = line.substring("diff --git a/".length(), marker);
            return path.equals("pom.xml") || path.endsWith("/pom.xml") ||
                path.equals("compose.yaml") || path.startsWith(".github/") ||
                path.startsWith("orchestrator/") || path.contains("/db/migration/") ||
                path.endsWith("application.yml") || path.endsWith("application.yaml") ||
                path.endsWith("application.properties") || path.endsWith("SecurityConfig.java");
        });
    }
}
