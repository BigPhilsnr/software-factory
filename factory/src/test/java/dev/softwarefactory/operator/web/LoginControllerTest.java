package dev.softwarefactory.operator.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Renders the public login page and asserts its accessible markup. */
class LoginControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LoginController()).build();

    @Test
    void anonymousLoginFormHasAccessibleLabelsAndMessageRegion() throws Exception {
        String html = mvc.perform(get("/factory/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(html.contains("<label for=\"username\">Email</label>"));
        assertTrue(html.contains("<label for=\"password\">Password</label>"));
        assertTrue(html.contains("type=\"email\""));
        assertTrue(html.contains("autocomplete=\"current-password\""));
        assertTrue(html.contains("role=\"alert\""));
        assertTrue(html.contains(":focus-visible"));
    }

    @Test
    void errorAndLoggedOutStatesRenderTheirMessages() throws Exception {
        String error = mvc.perform(get("/factory/login").param("error", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(error.contains("Invalid email or password."));

        String loggedOut = mvc.perform(get("/factory/login").param("loggedout", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertTrue(loggedOut.contains("You have been signed out."));
    }
}
