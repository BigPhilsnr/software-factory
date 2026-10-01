package dev.softwarefactory.execution;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class SandboxValidatorTest {
    @TempDir Path candidate;
    private SandboxValidator validator(Exception failure) {
        return new SandboxValidator(candidate, (directory,args,timeout) -> {
            if (args.contains("rm")) return "";
            Path report = candidate.resolve("shortener/target/surefire-reports/TEST-demo.RegressionTest.xml");
            Files.createDirectories(report.getParent());
            Files.writeString(report, "<testsuite tests='1' failures='1' errors='0' skipped='0'><testcase><failure message='expected failure'/></testcase></testsuite>");
            throw failure;
        });
    }
    @Test void timeoutAndDockerFailureNeverCountAsRedEvenWithAnAssertionReport() {
        assertThrows(IOException.class, () -> validator(new IOException("timeout")).expectRegression(candidate, "RegressionTest"));
        assertThrows(CommandRunner.Failed.class, () -> validator(new CommandRunner.Failed(125,"Docker unavailable")).expectRegression(candidate,"RegressionTest"));
    }
    @Test void requiresACompletedFailingTestCommand() throws Exception {
        assertTrue(validator(new CommandRunner.Failed(1,"test failure")).expectRegression(candidate,"RegressionTest").contains("Expected red regression"));
        assertThrows(IllegalArgumentException.class, () -> SandboxValidator.validateMount(Path.of("/tmp/source,target=/etc")));
    }
}
