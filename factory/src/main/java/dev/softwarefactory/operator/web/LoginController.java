package dev.softwarefactory.operator.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Login page for the public operator console.
 *
 * <p>The form posts username/password to the Spring Security form-login
 * endpoint ({@code POST /factory/login}); the same credentials that sign in to
 * the rest of the LakeLyon services are configured for this app via
 * {@code FACTORY_LOGIN_USER} / {@code FACTORY_LOGIN_PASSWORD}.
 */
@Controller
public class LoginController {

    private static final String PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Software Factory - Sign in</title>
            <style>
              body{margin:0;min-height:100vh;min-height:100svh;display:flex;align-items:center;justify-content:center;
                background:#0d1117;color:#e6edf3;font:16px/1.5 system-ui,sans-serif}
              .card{background:#161b22;border:1px solid #30363d;border-radius:12px;
                padding:clamp(1.25rem,4vw,2.5rem) clamp(1.25rem,4vw,2.25rem);
                width:min(22rem,100%%);max-width:100%%;box-sizing:border-box}
              @media (max-width:30rem){body{align-items:flex-start;padding:1rem 0}}
              .eyebrow{font-size:.72rem;letter-spacing:.12em;text-transform:uppercase;color:#7d8590}
              h1{font-size:1.3rem;margin:.35rem 0 1.4rem}
              label{display:block;font-size:.85rem;color:#9da7b1;margin:.9rem 0 .3rem}
              input{width:100%%;box-sizing:border-box;padding:.55rem .7rem;border-radius:8px;
                border:1px solid #30363d;background:#0d1117;color:#e6edf3;font:inherit}
              input:focus-visible,button:focus-visible{outline:3px solid #1f6feb;
                outline-offset:2px;border-color:transparent}
              button{margin-top:1.3rem;width:100%%;padding:.6rem;border:0;border-radius:8px;
                background:#238636;color:#fff;font:inherit;font-weight:600;cursor:pointer}
              button:hover{background:#2ea043}
              button:focus-visible{background:#2ea043}
              .msg{margin:1rem 0 0;font-size:.85rem;color:#f85149}
              .msg:empty{display:none}
              .out{margin-top:1rem;font-size:.85rem;color:#7d8590}
            </style></head>
            <body><form class="card" action="/factory/login" method="post">
              <span class="eyebrow">Software Factory</span>
              <h1>Sign in</h1>
              <label for="username">Email</label>
              <input id="username" name="username" type="email" autocomplete="username" required autofocus>
              <label for="password">Password</label>
              <input id="password" name="password" type="password" autocomplete="current-password" required>
              <button type="submit">Sign in</button>
              <p class="msg" role="alert" tabindex="-1">%s</p>
            </form></body></html>
            """;

    @GetMapping(value = "/factory/login", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> login(Authentication authentication,
            @RequestParam(value = "error", defaultValue = "") boolean error,
            @RequestParam(value = "loggedout", defaultValue = "") boolean loggedOut) {
        if (authentication != null) {
            return ResponseEntity.status(302).header("Location", "/factory/").build();
        }
        String message = error
                ? "Invalid email or password."
                : loggedOut ? "You have been signed out." : "";
        return ResponseEntity.ok(PAGE.formatted(message));
    }
}
