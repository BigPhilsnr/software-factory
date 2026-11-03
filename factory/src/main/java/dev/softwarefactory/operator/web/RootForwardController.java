package dev.softwarefactory.operator.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the operator console at the site root.
 *
 * <p>ADK's dev UI registers a view controller that redirects {@code /} to
 * {@code /dev-ui/}; a request-mapping controller takes precedence over view
 * controllers, so the public address (e.g. https://factory.lakelyon.com)
 * lands on the factory console with a 200 instead of a redirect.
 */
@Controller
public class RootForwardController {

    @GetMapping("/")
    public String root() {
        return "forward:/factory/";
    }
}
