package com.example.MigrosBackend.service.global;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class LogService {
    public String getClientIp(HttpServletRequest request) {
        String remoteAddr = "";
        if (request != null) {
            // Check for the X-Forwarded-For header, which is commonly used by proxies
            remoteAddr = request.getHeader("X-FORWARDED-FOR");
            if (remoteAddr == null || "".equals(remoteAddr)) {
                // Fallback to the standard remote address if header is not present
                remoteAddr = request.getRemoteAddr();
            }
        }
        return remoteAddr;
    }
}
