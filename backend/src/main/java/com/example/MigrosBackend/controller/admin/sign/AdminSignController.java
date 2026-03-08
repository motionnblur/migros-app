package com.example.MigrosBackend.controller.admin.sign;

import com.example.MigrosBackend.config.security.AuthCookieService;
import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.admin.sign.AdminSignDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.admin.sign.AdminSignupService;
import com.example.MigrosBackend.service.global.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("admin")
public class AdminSignController {
    private final AdminSignupService adminSupplyService;
    private final AuthCookieService authCookieService;
    private final AuthTokenResolver authTokenResolver;
    private final TokenService tokenService;

    @Autowired
    public AdminSignController(AdminSignupService adminSignupService,
                               AuthCookieService authCookieService,
                               AuthTokenResolver authTokenResolver,
                               TokenService tokenService) {
        this.adminSupplyService = adminSignupService;
        this.authCookieService = authCookieService;
        this.authTokenResolver = authTokenResolver;
        this.tokenService = tokenService;
    }

    @PostMapping("login")
    private ResponseEntity<Void> login(@RequestBody AdminSignDto adminSignDto, HttpServletRequest request) {
        String token = adminSupplyService.login(adminSignDto, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieService.createAdminSessionCookie(token).toString())
                .build();
    }

    @PostMapping("logout")
    private ResponseEntity<Void> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieService.clearAdminSessionCookie().toString())
                .build();
    }

    @GetMapping("session")
    private ResponseEntity<Map<String, String>> session(
            @CookieValue(name = AuthCookies.ADMIN_SESSION_COOKIE_NAME, required = false) String token) {
        String adminToken = authTokenResolver.requireToken(token);
        String adminName = tokenService.extractUsername(adminToken);
        return ResponseEntity.ok(Map.of("adminName", adminName));
    }
}
