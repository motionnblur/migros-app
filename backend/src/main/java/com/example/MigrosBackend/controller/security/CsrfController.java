package com.example.MigrosBackend.controller.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public CSRF token bootstrap for the cross-origin SPA.
 *
 * <p>The Angular client and the API are different origins, so the SPA cannot
 * read the API's host-only {@code XSRF-TOKEN} cookie. Instead it fetches this
 * endpoint with credentials and receives the token in the response JSON, which
 * is readable only by origins allowed through CORS. The response also creates
 * the repository cookie that the browser replays automatically on later
 * mutations.
 *
 * <p>Only the CSRF token and its header name are returned; no session, JWT, or
 * other credential material is exposed.
 */
@RestController
public class CsrfController {

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok(Map.of(
                "token", csrfToken.getToken(),
                "headerName", csrfToken.getHeaderName()));
    }
}
