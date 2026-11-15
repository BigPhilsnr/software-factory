package dev.softwarefactory.operator.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/** Renders the public login page and asserts its accessible markup. */
class LoginControllerTest {
    private static final String EMAIL_LABEL = "<label for=\"username\">Email</label>";
    private static final String PASSWORD_LABEL = "<label for=\"password\">Password</label>";
    private static final String EMAIL_TYPE = "type=\"email\"";
    private static final String PASSWORD_AUTOCOMPLETE = "autocomplete=\"current-password\"";
    private static final String ALERT_REGION = "role=\"alert\"";
    private static final String FOCUS_STYLE = ":focus-visible";
    private static final String ERROR_TEXT = "Invalid email or password.";
    private static final String SIGNED_OUT_TEXT = "You have been signed out.";
    private static final String FACTORY_HOME = "/factory/";

    private final LoginController controller = new LoginController();

    @Test
    void anonymousLoginFormHasAccessibleLabelsAutocompleteAndMessageRegion() {
        String html = controller.login(null, false, false).getBody();
        assertNotNull(html, "the login page must render");
        assertTrue(html.contains(EMAIL_LABEL), "the email field needs a label");
        assertTrue(html.contains(PASSWORD_LABEL), "the password field needs a label");
        assertTrue(html.contains(EMAIL_TYPE), "the email field should use type email");
        assertTrue(html.contains(PASSWORD_AUTOCOMPLETE), "the password field needs autocomplete");
        assertTrue(html.contains(ALERT_REGION), "status messages need an alert region");
        assertTrue(html.contains(FOCUS_STYLE), "keyboard focus needs a visible outline");
    }

    @Test
    void authenticatedRequestIsRedirectedToTheFactoryHome() {
        ResponseEntity<String> response = controller.login(authenticated(), false, false);
        assertEquals(302, response.getStatusCode().value());
        assertEquals(FACTORY_HOME, response.getHeaders().getFirst("Location"));
    }

    @Test
    void errorAndSignedOutStatesRenderTheirMessages() {
        String errorPage = controller.login(null, true, false).getBody();
        String outPage = controller.login(null, false, true).getBody();
        assertTrue(errorPage.contains(ERROR_TEXT), "a failed sign in needs an error message");
        assertTrue(outPage.contains(SIGNED_OUT_TEXT), "sign out needs a confirmation message");
    }

    private static Authentication authenticated() {
        return new UsernamePasswordAuthenticationToken("operator", "credentials");
    }
}
