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

    private final LoginController controller = new LoginController();

    @Test
    void anonymousLoginFormHasAccessibleLabelsAndMessageRegion() {
        ResponseEntity<String> response = controller.login(null, false, false);
        assertEquals(200, response.getStatusCode().value());
        String html = response.getBody();
        assertNotNull(html);
        assertTrue(html.contains("<label for=\"username\">Email</label>"));
        assertTrue(html.contains("<label for=\"password\">Password</label>"));
        assertTrue(html.contains("type=\"email\""));
        assertTrue(html.contains("autocomplete=\"current-password\""));
        assertTrue(html.contains("role=\"alert\""));
        assertTrue(html.contains(":focus-visible"));
    }

    @Test
    void authenticatedRequestIsRedirectedToFactory() {
        ResponseEntity<String> response = controller.login(authenticated(), false, false);
        assertEquals(302, response.getStatusCode().value());
        assertEquals("/factory/", response.getHeaders().getFirst("Location"));
    }

    @Test
    void errorAndLoggedOutStatesRenderTheirMessages() {
        assertTrue(controller.login(null, true, false).getBody().contains("Invalid email or password."));
        assertTrue(controller.login(null, false, true).getBody().contains("You have been signed out."));
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
            public String getName() {
                return "operator";
            }

            @Override
            public Object getPrincipal() {
                return "operator";
            }

            @Override
            public boolean isAuthenticated() {
                return true;
            }

            @Override
            public void eraseCredentials() {
                // no credentials to erase
            }
        };
    }
}
