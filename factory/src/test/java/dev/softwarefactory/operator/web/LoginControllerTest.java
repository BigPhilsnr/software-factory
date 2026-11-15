package dev.softwarefactory.operator.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Renders the public login page and asserts its accessible markup. */
class LoginControllerTest {
    private static final String USERNAME_LABEL = "<label for=\"username\">Email</label>";
    private static final String PASSWORD_LABEL = "<label for=\"password\">Password</label>";
    private static final String EMAIL_INPUT = "type=\"email\"";
    private static final String PASSWORD_AUTOCOMPLETE = "autocomplete=\"current-password\"";
    private static final String ALERT_REGION = "role=\"alert\"";
    private static final String FOCUS_STYLE = ":focus-visible";
    private static final String ERROR_MESSAGE = "Invalid email or password.";
    private static final String SIGNED_OUT_MESSAGE = "You have been signed out.";
    private static final String FACTORY_HOME = "/factory/";

    private final LoginController controller = new LoginController();

    @Test
    void anonymousLoginFormHasAccessibleLabelsAndMessageRegion() {
        ResponseEntity<String> response = controller.login(null, false, false);
        assertEquals(200, response.getStatusCode().value());
        String html = response.getBody();
        assertNotNull(html);
        assertTrue(html.contains(USERNAME_LABEL));
        assertTrue(html.contains(PASSWORD_LABEL));
        assertTrue(html.contains(EMAIL_INPUT));
        assertTrue(html.contains(PASSWORD_AUTOCOMPLETE));
        assertTrue(html.contains(ALERT_REGION));
        assertTrue(html.contains(FOCUS_STYLE));
    }

    @Test
    void authenticatedRequestIsRedirectedToFactory() {
        ResponseEntity<String> response = controller.login(authenticated(), false, false);
        assertEquals(302, response.getStatusCode().value());
        assertEquals(FACTORY_HOME, response.getHeaders().getFirst("Location"));
    }

    @Test
    void errorAndLoggedOutStatesRenderTheirMessages() {
        String errorPage = controller.login(null, true, false).getBody();
        String outPage = controller.login(null, false, true).getBody();
        assertTrue(errorPage.contains(ERROR_MESSAGE));
        assertTrue(outPage.contains(SIGNED_OUT_MESSAGE));
    }

    private static Authentication authenticated() {
        return new Authentication() {
            @Override
            public Collection<? extends GrantedAuthority> getAuthorities() {
                return List.of();
            }

            @Override
            public Object getCredentials() {
                return "credentials";
            }

            @Override
            public Object getDetails() {
                return null;
            }

            @Override
            public Object getPrincipal() {
                return "operator";
            }

            @Override
            public boolean isAuthenticated() {
                return true;
            }
        };
    }
}
