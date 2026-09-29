package com.example.MigrosBackend.helper;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable allow-list of reverse proxies whose forwarding headers may be
 * trusted for client-address attribution.
 *
 * <p>An empty list means "trust nothing": the peer address reported by the
 * container is the only usable client address. That is the safe default, because
 * {@code X-Forwarded-For} is attacker-controlled unless a proxy in front of the
 * application is known to overwrite it.
 *
 * <p>Entries are either a plain address ({@code 10.0.0.7}) or a CIDR block
 * ({@code 10.0.0.0/8}). Both IPv4 and IPv6 are supported. Unparsable entries are
 * rejected at construction time rather than silently ignored, so a typo in
 * {@code app.trusted-proxies} fails fast instead of quietly degrading to
 * "trust nothing" or, worse, trusting everything.
 */
public final class TrustedProxyList {

    private static final TrustedProxyList EMPTY = new TrustedProxyList(List.of());

    private final List<CidrBlock> blocks;

    private TrustedProxyList(List<CidrBlock> blocks) {
        this.blocks = blocks;
    }

    public static TrustedProxyList empty() {
        return EMPTY;
    }

    /**
     * Parses a comma-separated address/CIDR list. Blank input yields the empty
     * (trust-nothing) list.
     *
     * @throws IllegalStateException if any non-blank entry cannot be parsed
     */
    public static TrustedProxyList parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return EMPTY;
        }

        List<CidrBlock> parsed = new ArrayList<>();
        for (String rawEntry : configured.split(",")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            parsed.add(CidrBlock.parse(entry));
        }
        return new TrustedProxyList(Collections.unmodifiableList(parsed));
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * @return {@code true} only when {@code address} is a syntactically valid
     *         literal that falls inside one of the configured blocks. Hostnames
     *         are never resolved, so this can not be turned into a DNS lookup or
     *         SSRF primitive by a crafted header.
     */
    public boolean trusts(String address) {
        if (address == null || address.isBlank() || blocks.isEmpty()) {
            return false;
        }

        byte[] candidate = literalBytes(address.trim());
        if (candidate == null) {
            return false;
        }

        for (CidrBlock block : blocks) {
            if (block.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] literalBytes(String address) {
        // InetAddress.getByName() would resolve names; only accept literals by
        // rejecting anything that is not an IP literal.
        if (!looksLikeIpLiteral(address)) {
            return null;
        }
        try {
            return InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static boolean looksLikeIpLiteral(String address) {
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            boolean allowed = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F')
                    || c == ':' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return address.indexOf(':') >= 0 || address.indexOf('.') >= 0;
    }

    private record CidrBlock(byte[] network, int prefixLength) {

        static CidrBlock parse(String entry) {
            String addressPart = entry;
            int prefix = -1;

            int slash = entry.lastIndexOf('/');
            if (slash >= 0) {
                addressPart = entry.substring(0, slash).trim();
                String prefixText = entry.substring(slash + 1).trim();
                try {
                    prefix = Integer.parseInt(prefixText);
                } catch (NumberFormatException ex) {
                    throw new IllegalStateException(
                            "app.trusted-proxies entry has an invalid prefix length: " + entry);
                }
            }

            byte[] address = literalBytes(addressPart);
            if (address == null) {
                throw new IllegalStateException(
                        "app.trusted-proxies entry is not an IP address or CIDR block: " + entry);
            }
            if (prefix < 0) {
                prefix = address.length * Byte.SIZE;
            }
            if (prefix > address.length * Byte.SIZE) {
                throw new IllegalStateException(
                        "app.trusted-proxies entry has an out-of-range prefix length: " + entry);
            }
            return new CidrBlock(address, prefix);
        }

        boolean contains(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            return samePrefix(candidate, network, prefixLength);
        }

        private static boolean samePrefix(byte[] left, byte[] right, int prefixBits) {
            int fullBytes = prefixBits / Byte.SIZE;
            for (int i = 0; i < fullBytes; i++) {
                if (left[i] != right[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % Byte.SIZE;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF00 >> remainingBits) & 0xFF;
            return (left[fullBytes] & mask) == (right[fullBytes] & mask);
        }
    }
}
