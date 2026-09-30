package com.example.factory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RunEngineRegressionTest {
    @Test
    void derivesTheGeneratedTestClassFromThePatch() {
        String patch = "diff --git a/shortener/src/test/java/com/example/shortener/api/RedirectRegressionTest.java b/shortener/src/test/java/com/example/shortener/api/RedirectRegressionTest.java\n"
            + "--- /dev/null\n"
            + "+++ b/shortener/src/test/java/com/example/shortener/api/RedirectRegressionTest.java\n";
        assertEquals("RedirectRegressionTest", RunEngine.parseRegressionTestClass(patch));
    }

    @Test
    void rejectsAmbiguousTestPatches() {
        String patch = "+++ b/shortener/src/test/java/AaaTest.java\n"
            + "+++ b/shortener/src/test/java/BbbTest.java\n";
        assertThrows(IllegalArgumentException.class, () -> RunEngine.parseRegressionTestClass(patch));
    }
}
