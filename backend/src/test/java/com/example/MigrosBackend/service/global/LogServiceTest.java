package com.example.MigrosBackend.service.global;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogServiceTest {

    @Test
    void getClientIp_ignoresAForgedLeftMostValue_fromAnAppendingProxy() {
        // Nginx uses $proxy_add_x_forwarded_for, so an honest proxy appends the
        // real peer to whatever the client already sent. Everything on the left of
        // that append is caller-supplied and must never reach the audit log.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.7, 203.0.113.9");
        request.setRemoteAddr("192.168.1.1");

        assertEquals("203.0.113.9", service("192.168.1.1").getClientIp(request),
                "taking the left-most entry records an address the caller chose");
    }

    @Test
    void getClientIp_ignoresForgedValuesInFrontOfSeveralProxies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.7, 203.0.113.9, 10.0.0.7");
        request.setRemoteAddr("10.0.0.7");

        assertEquals("203.0.113.9", service("10.0.0.0/8").getClientIp(request));
    }

    @Test
    void getClientIp_fallsBackToRemoteAddr_whenEveryHopIsATrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "10.0.0.7, 10.0.0.8");
        request.setRemoteAddr("10.0.0.7");

        assertEquals("10.0.0.7", service("10.0.0.0/8").getClientIp(request),
                "an all-proxy chain attests nothing beyond the peer itself");
    }

    @Test
    void getClientIp_fallsBackToRemoteAddr_whenALaterHopIsMalformed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, not-an-address");
        request.setRemoteAddr("192.168.1.1");

        assertEquals("192.168.1.1", service("192.168.1.1").getClientIp(request),
                "a chain that cannot be walked with confidence must not be walked with a guess");
    }

    @Test
    void getClientIp_toleratesWhitespaceOnlySeparators() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9,   10.0.0.7");
        request.setRemoteAddr("10.0.0.7");

        assertEquals("203.0.113.9", service("10.0.0.0/8").getClientIp(request));
    }

    @Test
    void getClientIp_ignoresForwardedHeader_whenNoTrustedProxyIsConfigured() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.setRemoteAddr("127.0.0.1");

        assertEquals("127.0.0.1", service("").getClientIp(request));
    }

    @Test
    void getClientIp_ignoresForgedForwardedHeader_fromUntrustedPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.setRemoteAddr("10.1.2.3");

        assertEquals("10.1.2.3", service("192.168.0.0/16").getClientIp(request));
    }

    @Test
    void getClientIp_returnsForwardedClient_whenPeerIsAConfiguredProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 192.168.1.1");
        request.setRemoteAddr("192.168.1.1");

        assertEquals("203.0.113.9", service("192.168.1.1").getClientIp(request));
    }

    @Test
    void getClientIp_returnsForwardedClient_whenPeerMatchesProxyCidr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.setRemoteAddr("10.0.0.7");

        assertEquals("203.0.113.9", service("10.0.0.0/8, 192.168.0.0/16").getClientIp(request));
    }

    @Test
    void getClientIp_fallsBackToRemoteAddr_whenTrustedProxySendsNoHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.1");

        assertEquals("192.168.1.1", service("192.168.1.1").getClientIp(request));
    }

    @Test
    void getClientIp_fallsBackToRemoteAddr_whenTrustedProxySendsEmptyHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "");
        request.setRemoteAddr("192.168.1.1");

        assertEquals("192.168.1.1", service("192.168.1.1").getClientIp(request));
    }

    @Test
    void getClientIp_fallsBackToRemoteAddr_whenForwardedValueIsNotAnAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "unknown");
        request.setRemoteAddr("192.168.1.1");

        assertEquals("192.168.1.1", service("192.168.1.1").getClientIp(request));
    }

    @Test
    void getClientIp_fallsBackToRemoteAddr_whenForwardedHeaderIsOversized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, " + "x".repeat(500));
        request.setRemoteAddr("192.168.1.1");

        assertEquals("192.168.1.1", service("192.168.1.1").getClientIp(request));
    }

    @Test
    void getClientIp_returnsEmpty_whenRequestIsNull() {
        assertEquals("", service("192.168.1.1").getClientIp(null));
    }

    @Test
    void getClientIp_returnsRemoteAddr_whenTheContainerReportsNoPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertEquals("", service("192.168.1.1").getClientIp(request));
    }

    @Test
    void getClientIp_doesNotResolveHostnamesFromTheForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "evil.example.com");
        request.setRemoteAddr("192.168.1.1");

        assertEquals("192.168.1.1", service("192.168.1.1").getClientIp(request));
    }

    @Test
    void unparsableTrustedProxyConfigurationFailsFast() {
        assertThrows(IllegalStateException.class, () -> service("not-an-address"));
        assertThrows(IllegalStateException.class, () -> service("192.168.1.0/99"));
        assertThrows(IllegalStateException.class, () -> service("192.168.1.0/abc"));
    }

    private LogService service(String trustedProxies) {
        return new LogService(trustedProxies);
    }
}
