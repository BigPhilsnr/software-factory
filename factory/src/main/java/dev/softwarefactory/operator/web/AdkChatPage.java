package dev.softwarefactory.operator.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Extends the bundled ADK page without copying or modifying its Angular application. */
@RestController
public final class AdkChatPage {
    private final String html;

    public AdkChatPage() throws IOException {
        String bundled = new ClassPathResource("browser/index.html").getContentAsString(StandardCharsets.UTF_8);
        if (!bundled.contains("</head>")) throw new IOException("ADK page is missing its head element");
        html = bundled.replace("</head>", """
                <link rel="stylesheet" href="/chat/diagrams.css">
                <script defer src="/chat/vendor/mermaid.js"></script>
                <script defer src="/chat/diagrams.js"></script>
                </head>""");
    }

    @GetMapping(
            value = {"/dev-ui/", "/dev-ui/index.html"},
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(html);
    }
}
