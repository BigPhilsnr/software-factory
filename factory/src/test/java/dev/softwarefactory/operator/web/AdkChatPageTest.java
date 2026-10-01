package dev.softwarefactory.operator.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdkChatPageTest {
    @Test
    void extendsBundledPageAtBothEntryPointsWithoutCachingIt() throws Exception {
        var page = new AdkChatPage();
        var mvc = MockMvcBuilders.standaloneSetup(page).build();
        for (String path : new String[] {"/dev-ui/", "/dev-ui/index.html"}) {
            String html = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertTrue(html.contains("<app-root>"));
            assertTrue(html.contains("src=\"./main-"));
            assertTrue(html.contains("/chat/diagrams.css"));
            assertTrue(html.contains("/chat/vendor/mermaid.js"));
            assertTrue(html.indexOf("/chat/vendor/mermaid.js") < html.indexOf("/chat/diagrams.js"));
        }
        mvc.perform(get("/factory/")).andExpect(status().isNotFound());
    }
}
