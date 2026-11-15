package dev.softwarefactory.operator.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

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
              body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                background:#0d1117;color:#e6edf3;font:16px/1.5 system-ui,sans-serif}
              .card{background:#161b22;border:1px solid #30363d;border-radius:12px;
                padding:2.5rem 2.25rem;width:min(22rem,92vw)}
              .eyebrow{font-size:.72rem;letter-spacing:.12em;text-transform:uppercase;color:#7d8590}
              h1{font-size:1.3rem;margin:.35rem 0 1.4rem}
              label{display:block;font-size:.85rem;color:#9da7b1;margin:.9rem 0 .3rem}
              input{width:100%;box-sizing:border-box;padding:.55rem .7rem;border-radius:8px;
                border:1px solid #30363d;background:#0d1117;color:#e6edf3;font:inherit}
              input:focus{outline:2px solid #1f6feb;border-color:transparent}
              button{margin-top:1.3rem;width:100%;padding:.6rem;border:0;border-radius:8px;
                background:#238636;color:#fff;font:inherit;font-weight:600;cursor:pointer}
              button:hover{background:#2ea043}
              .msg{margin-top:1rem;font-size:.85rem;color:#f85149}
              .out{margin-top:1rem;font-size:.85rem;color:#7d8590}
            </style></head>
            <body><form class="card" action="/factory/login" method="post">
              <span class="eyebrow">Software Factory</span>
              <h1>Sign in</h1>
              <label for="username">Email</label>
              <input id="username" name="username" type="text" autocomplete="username" required autofocus>
              <label for="password">Password</label>
              <input id="password" name="password" type="password" autocomplete="current-password" required>
              <button type="submit">Sign in</button>
              <span class="msg">%s</span>
            </form></body></html>
            """;

    @GetMapping(value = "/factory/login", produces = "text/html;charset=UTF-8")
    public org.springframework.http.ResponseEntity<String> login(Authentication authentication,
            @org.springframework.web.bind.annotation.RequestParam(value = "error", defaultValue = "") boolean error,
            @org.springframework.web.bind.annotation.RequestParam(value = "loggedout", defaultValue = "") boolean loggedOut) {
        if (authentication != null) {
            return org.springframework.http.ResponseEntity.status(302)
                    .header("Location", "/factory/").build();
        }
        String message = error
                ? "Invalid email or password."
                : loggedOut ? "You have been signed out." : "";
        return org.springframework.http.ResponseEntity.ok(PAGE.formatted(message));
    }
}
