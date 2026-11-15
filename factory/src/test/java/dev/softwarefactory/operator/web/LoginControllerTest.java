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
    private final LoginController controller = new LoginController();

    @Test
    void anonymousLoginFormHasAccessibleLabelsAutocompleteAndMessageRegion() {
        String html = controller.login(null, false, false).getBody();
        assertNotNull(html, "the login page must render");
        assertTrue(html.contains("<label for=\"username\">Email</label>"), "email field needs a label");
        assertTrue(html.contains("<label for=\"password\">Password</label>"), "password field needs a label");
        assertTrue(html.contains("type=\"email\""), "email field should use type email");
        assertTrue(html.contains("autocomplete=\"current-password\""), "password field needs autocomplete");
        assertTrue(html.contains("role=\"alert\""), "status messages need an alert region");
        assertTrue(html.contains(":focus-visible"), "keyboard focus needs a visible outline");
    }

    @Test
    void authenticatedRequestIsRedirectedToTheFactoryHome() {
        ResponseEntity<String> response = controller.login(authenticated(), false, false);
        assertEquals(302, response.getStatusCode().value());
        assertEquals("/factory/", response.getHeaders().getFirst("Location"));
    }

    @Test
    void errorAndSignedOutStatesRenderTheirMessages() {
        String errorPage = controller.login(null, true, false).getBody();
        String outPage = controller.login(null, false, true).getBody();
        assertTrue(errorPage.contains("Invalid email or password."), "failed sign in needs an error message");
        assertTrue(outPage.contains("You have been signed out."), "sign out needs a confirmation message");
    }

    private static Authentication authenticated() {
        return new UsernamePasswordAuthenticationToken("operator", "credentials");
    }
}
