package com.example.MigrosBackend.controller.user.sign;

import com.example.MigrosBackend.config.security.AuthCookieService;
import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.sign.UserSignupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("user")
public class UserSignController {
    private final UserSignupService userSignupService;
    private final TokenService tokenService;
    private final AuthCookieService authCookieService;
    private final AuthTokenResolver authTokenResolver;

    @Autowired
    public UserSignController(UserSignupService userSignupService,
                              TokenService tokenService,
                              AuthCookieService authCookieService,
                              AuthTokenResolver authTokenResolver) {
        this.userSignupService = userSignupService;
        this.tokenService = tokenService;
        this.authCookieService = authCookieService;
        this.authTokenResolver = authTokenResolver;
    }

    @PostMapping("signup")
    private ResponseEntity<Void> addItem(@RequestBody UserSignDto userSignDto) {
        userSignupService.signup(userSignDto);
        return ResponseEntity.ok().build();
    }

    @GetMapping("signup/confirm")
    private ResponseEntity<String> confirm(@RequestParam String token) {
        userSignupService.confirm(token);
        return ResponseEntity.ok("Your account has been created successfully, you can close this page now.");
    }

    @PostMapping("login")
    private ResponseEntity<Void> login(@RequestBody UserSignDto userSignDto) {
        String token = userSignupService.login(userSignDto);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieService.createSessionCookie(token).toString())
                .build();
    }

    @PostMapping("logout")
    private ResponseEntity<Void> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearSessionCookie().toString())
                .build();
    }

    @GetMapping("session")
    private ResponseEntity<Map<String, String>> session(
            @CookieValue(name = AuthCookies.SESSION_COOKIE_NAME, required = false) String token) {
        String userToken = authTokenResolver.requireToken(token);
        String userMail = tokenService.extractUsername(userToken);
        return ResponseEntity.ok(Map.of("userMail", userMail));
    }
}
