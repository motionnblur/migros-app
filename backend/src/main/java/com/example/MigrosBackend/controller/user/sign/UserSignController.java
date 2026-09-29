package com.example.MigrosBackend.controller.user.sign;

import com.example.MigrosBackend.config.security.AuthCookieService;
import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.user.sign.ResetPasswordDto;
import com.example.MigrosBackend.dto.user.sign.UserSessionDto;
import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.sign.UserSignupService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/user")
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
    public ResponseEntity<Void> signup(@Valid @RequestBody UserSignDto userSignDto) {
        userSignupService.signup(userSignDto);
        return ResponseEntity.ok().build();
    }

    @GetMapping("signup/confirm")
    public ResponseEntity<String> confirm(@RequestParam String token) {
        userSignupService.confirm(token);
        return ResponseEntity.ok("Your account has been created successfully, you can close this page now.");
    }

    @GetMapping("signup/confirmUserMail")
    public ResponseEntity<String> confirmUserMail(@RequestParam String token) {
        userSignupService.confirmUserMail(token);
        return ResponseEntity.ok("Your mail has been verified successfully, you can close this page now.");
    }

    @PostMapping("login")
    public ResponseEntity<Void> login(@Valid @RequestBody UserSignDto userSignDto) {
        String token = userSignupService.login(userSignDto);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieService.createUserSessionCookie(token).toString())
                .build();
    }

    @PostMapping("logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearUserSessionCookie().toString())
                .build();
    }

    @GetMapping("session")
    public ResponseEntity<UserSessionDto> session(
            @CookieValue(name = AuthCookies.USER_SESSION_COOKIE_NAME, required = false) String token) {
        String userToken = authTokenResolver.requireToken(token);
        String userMail = tokenService.validateAndExtractUser(userToken);
        return ResponseEntity.ok(new UserSessionDto(userMail));
    }

    @PostMapping("verifyUserMail")
    public ResponseEntity<Void> verifyUserMail(@RequestParam String userMail) {
        userSignupService.verifyUserMail(userMail);
        return ResponseEntity.ok().build();
    }

    @PostMapping("resetPassword")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordDto resetPasswordDto) {
        userSignupService.resetPassword(resetPasswordDto);
        return ResponseEntity.ok().build();
    }
}
