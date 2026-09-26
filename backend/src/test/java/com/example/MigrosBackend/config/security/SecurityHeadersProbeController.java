package com.example.MigrosBackend.config.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/user/profile/security-headers")
class SecurityHeadersProbeController {

    @GetMapping("/content-length")
    void contentLength(HttpServletResponse response) throws IOException {
        String body = "security-header-probe";
        response.setIntHeader("Content-Length", body.length());
        response.getWriter().write(body);
    }

    @GetMapping("/send-error")
    void sendError(HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "probe failure");
    }
}
