package com.example.MigrosBackend.config.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal authenticated mutation/read target for the CSRF filter tests. It sits
 * under {@code /user/profile/**} so the existing authorization rules require a
 * valid {@code ROLE_USER} session, isolating CSRF enforcement from route
 * authorization.
 */
@RestController
@RequestMapping("/user/profile/csrf-probe")
class CsrfMutationProbeController {

    @PostMapping("/mutate")
    ResponseEntity<String> mutate() {
        return ResponseEntity.ok("mutated");
    }

    @GetMapping("/read")
    ResponseEntity<String> read() {
        return ResponseEntity.ok("read");
    }
}
