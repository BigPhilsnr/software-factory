package dev.softwarefactory.governance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PatchPolicyTest {
    private static String header(String path) { return "diff --git a/" + path + " b/" + path + "\n"; }

    @ParameterizedTest
    @ValueSource(strings = {
        "factory/src/Change.java",
        "orchestrator/src/Change.java",
        "pom.xml",
        "shortener/pom.xml",
        "shortener/src/main/resources/db/migration/V2.sql",
        "shortener/src/main/java/com/example/bootstrap/Startup.java",
        "bootstrap/init.sh",
        "shortener/src/main/java/com/example/config/SecurityConfig.java",
        "shortener/src/main/java/com/example/api/WebSecurityRules.java",
        "shortener/src/main/resources/application.yml",
        "shortener/src/main/resources/application-prod.properties",
        "shortener/src/main/resources/logback-spring.xml",
        "shortener/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
        "shortener/src/main/resources/static/index.html",
        "shortener/src/test/resources/junit-platform.properties",
        "shortener/src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension",
        ".gitignore",
        "shortener/.gitattributes",
        ".github/workflows/ci.yml",
        "compose.yaml",
        "docker-compose.override.yml",
        "shortener/Dockerfile",
        "Dockerfile.validator"
    })
    void sensitivePathsRequireOperatorApproval(String path) {
        assertTrue(PatchPolicy.requiresApproval(false, header(path)), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "shortener/src/main/java/com/example/shortener/api/ShortenerController.java",
        "shortener/src/test/java/com/example/shortener/api/RedirectRateLimitRegressionTest.java",
        "shortener/openapi.yaml",
        "docs/PLAN.md"
    })
    void ordinarySourceAndTestChangesFollowTheTaskFlag(String path) {
        assertFalse(PatchPolicy.requiresApproval(false, header(path)), path);
        assertTrue(PatchPolicy.requiresApproval(true, header(path)), path);
    }

    @Test
    void unparseableHeadersAndRenamesIntoSensitivePathsRequireApproval() {
        assertTrue(PatchPolicy.requiresApproval(false, "diff --git a/shortener/a b.java b/shortener/a b.java\n"));
        assertTrue(PatchPolicy.requiresApproval(false, "diff --git a/shortener/Fix.java b/shortener/pom.xml\n"));
    }
}
