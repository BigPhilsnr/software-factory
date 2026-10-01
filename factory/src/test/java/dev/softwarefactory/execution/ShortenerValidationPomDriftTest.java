package dev.softwarefactory.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The validator builds candidates with a trusted copy of the product POM. If the product POM gains a
 * dependency or changes test selection, this fails so the trusted copy is updated deliberately.
 */
class ShortenerValidationPomDriftTest {
    @Test void trustedValidationPomMatchesTheProductBuild() throws Exception {
        Document product = parse(Files.newInputStream(productPom()));
        Document trusted;
        try (InputStream input = SandboxValidator.class.getResourceAsStream("/validation/shortener-pom.xml")) {
            trusted = parse(input);
        }
        assertEquals(parentVersion(product), parentVersion(trusted), "Spring Boot parent version");
        assertEquals(dependencies(product), dependencies(trusted), "Dependency set (groupId:artifactId:version:scope)");
        assertEquals(text(product, "test.excludedGroups"), text(trusted, "test.excludedGroups"), "Default excluded test groups");
        assertEquals(surefireSetting(product, "excludedGroups"), surefireSetting(trusted, "excludedGroups"), "Surefire excludedGroups");
        assertEquals("/workspace/shortener", surefireSetting(trusted, "workingDirectory"));
        assertEquals(SandboxValidator.REPORTS_MOUNT, surefireSetting(trusted, "reportsDirectory"));
    }

    private static Path productPom() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path candidate = directory.resolve("shortener/pom.xml");
        Path pom = Files.isRegularFile(candidate) ? candidate : directory.resolveSibling("shortener").resolve("pom.xml");
        assertTrue(Files.isRegularFile(pom), "shortener/pom.xml not found from " + directory);
        return pom;
    }

    private static Document parse(InputStream input) throws Exception {
        try (input) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static String parentVersion(Document pom) {
        Element parent = (Element) pom.getDocumentElement().getElementsByTagName("parent").item(0);
        return child(parent, "artifactId") + ":" + child(parent, "version");
    }

    private static Set<String> dependencies(Document pom) {
        Set<String> result = new TreeSet<>();
        var nodes = pom.getElementsByTagName("dependency");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element dependency = (Element) nodes.item(index);
            result.add(child(dependency, "groupId") + ":" + child(dependency, "artifactId") + ":"
                + child(dependency, "version") + ":" + (child(dependency, "scope").isEmpty() ? "compile" : child(dependency, "scope")));
        }
        return result;
    }

    private static String surefireSetting(Document pom, String name) {
        var plugins = pom.getElementsByTagName("plugin");
        for (int index = 0; index < plugins.getLength(); index++) {
            Element plugin = (Element) plugins.item(index);
            if ("maven-surefire-plugin".equals(child(plugin, "artifactId"))) {
                var values = plugin.getElementsByTagName(name);
                return values.getLength() == 0 ? "" : values.item(0).getTextContent().strip();
            }
        }
        return "";
    }

    private static String text(Document pom, String element) {
        var properties = (Element) pom.getDocumentElement().getElementsByTagName("properties").item(0);
        return child(properties, element);
    }

    private static String child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) return element.getTextContent().strip();
        }
        return "";
    }
}
