package dev.softwarefactory.operator.web;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class FactoryServiceTest {
    @TempDir Path root;
    @Test void fullCapacityRejectsBeforeTouchingDecisionPersistence() {
        // A null repository makes any attempted persistence fail differently.
        try(var service=new FactoryService(root,null,Set.of("one","two"))) {
            assertThrows(IllegalStateException.class,()->service.approve("third","hash"));
            assertThrows(IllegalStateException.class,()->service.clarify("third","answer"));
            assertThrows(IllegalStateException.class,()->service.revise("third","task","feedback"));
        }
    }
}
