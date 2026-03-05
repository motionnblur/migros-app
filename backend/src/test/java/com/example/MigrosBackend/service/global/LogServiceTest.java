package com.example.MigrosBackend.service.global;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class LogServiceTest {
    private final LogService logService = new LogService();

    @Test
    void getClientIp_ShouldReturnForwardedHeader_WhenProxyIsUsed() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();
        String proxyIp = "192.168.1.100";
        request.addHeader("X-FORWARDED-FOR", proxyIp);
        request.setRemoteAddr("127.0.0.1"); // The direct connection (proxy IP)

        // Act
        String result = logService.getClientIp(request);

        // Assert
        assertEquals(proxyIp, result, "Should prioritize X-FORWARDED-FOR header");
    }

    @Test
    void getClientIp_ShouldReturnRemoteAddr_WhenNoHeaderPresent() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();
        String directIp = "203.0.113.1";
        request.setRemoteAddr(directIp);
        // Header is NOT added here

        // Act
        String result = logService.getClientIp(request);

        // Assert
        assertEquals(directIp, result, "Should fallback to remote address if header is missing");
    }

    @Test
    void getClientIp_ShouldReturnRemoteAddr_WhenHeaderIsEmpty() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-FORWARDED-FOR", "");
        request.setRemoteAddr("1.1.1.1");

        // Act
        String result = logService.getClientIp(request);

        // Assert
        assertEquals("1.1.1.1", result, "Should fallback to remote address if header is empty string");
    }

    @Test
    void getClientIp_ShouldReturnEmpty_WhenRequestIsNull() {
        // Act
        String result = logService.getClientIp(null);

        // Assert
        assertEquals("", result, "Should return empty string for null request");
    }
}