package com.example.MigrosBackend.service.global;

import com.example.MigrosBackend.helper.TrustedProxyList;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LogService {

    private static final Logger LOG = LoggerFactory.getLogger(LogService.class);

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final int MAX_FORWARDED_HEADER_LENGTH = 200;
    private static final int MAX_FORWARDED_HOPS = 20;

    private final TrustedProxyList trustedProxies;

    public LogService(@Value("${app.trusted-proxies:}") String trustedProxies) {
        this.trustedProxies = TrustedProxyList.parse(trustedProxies);
        if (trustedProxies != null && !trustedProxies.isBlank() && trustedProxies.contains(",")) {
            LOG.info("app.trusted-proxies configured with {} proxy entries", trustedProxies.split(",").length);
        }
    }

    /**
     * Resolves the client address for audit logging.
     *
     * <p>Forwarding headers are attacker-controlled, so {@code X-Forwarded-For}
     * is consulted only when the immediate peer is an explicitly configured
     * trusted proxy. Without such a configuration the container's
     * {@code remoteAddr} is returned unchanged, which means a forged header can
     * never spoof the address recorded next to a login failure.
     *
     * <p>The left-most entry of the chain is used because each trusted proxy
     * appends to the header, so the left-most value is the original client.
     * The right-most value (the hop closest to this application) is deliberately
     * ignored: an untrusted hop in front of the trusted proxy could have
     * prepended a forged value.
     */
    public String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }

        String remoteAddress = normalize(request.getRemoteAddr());
        if (remoteAddress.isEmpty() || !trustedProxies.trusts(remoteAddress)) {
            return remoteAddress;
        }

        String forwardedHeader = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedHeader == null || forwardedHeader.isBlank()) {
            return remoteAddress;
        }
        if (forwardedHeader.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return remoteAddress;
        }

        String clientAddress = normalize(forwardedHeader.split(",", MAX_FORWARDED_HOPS)[0]);
        if (clientAddress.isEmpty() || !isIpLiteral(clientAddress)) {
            return remoteAddress;
        }
        return clientAddress;
    }

    private static boolean isIpLiteral(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F')
                    || c == ':' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return value.indexOf(':') >= 0 || value.indexOf('.') >= 0;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return "";
        }
        return trimmed;
    }
}
