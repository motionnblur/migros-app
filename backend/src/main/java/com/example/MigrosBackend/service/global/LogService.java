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
     * <p>When the header <em>is</em> consulted, the chain is walked from the
     * right and the first entry that is not itself a trusted proxy wins. Every
     * proxy <em>appends</em> to the header - the bundled Nginx config uses
     * {@code $proxy_add_x_forwarded_for} - so the entries on the right are the
     * ones written by infrastructure and the entries on the left are whatever
     * the client sent. Taking the left-most value, as this used to, therefore
     * attributes the failure to an address the caller chose: a client that sends
     * {@code X-Forwarded-For: 198.51.100.7} through one honest proxy produces a
     * chain of {@code 198.51.100.7, <real client>} and the log records the
     * forged address.
     *
     * <p>Everything is fail-closed: a malformed entry, an over-long header, or a
     * chain consisting only of trusted proxies falls back to the peer address.
     * Hostnames are never resolved, so a crafted header cannot turn this into a
     * DNS lookup.
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

        String[] hops = forwardedHeader.split(",", MAX_FORWARDED_HOPS);
        for (int i = 0; i < hops.length; i++) {
            String hop = normalize(hops[hops.length - 1 - i]);
            if (hop.isEmpty()) {
                continue;
            }
            if (!isIpLiteral(hop)) {
                // A hop that is not an address means the chain cannot be walked
                // with any confidence; trusting the rest of it would be guessing.
                return remoteAddress;
            }
            if (!trustedProxies.trusts(hop)) {
                return hop;
            }
        }
        // Every hop is a trusted proxy, so the peer itself is the closest thing
        // to an origin address this deployment can attest to.
        return remoteAddress;
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
