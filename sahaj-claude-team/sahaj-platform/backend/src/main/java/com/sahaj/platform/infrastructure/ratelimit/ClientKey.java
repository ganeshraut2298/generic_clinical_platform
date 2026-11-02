package com.sahaj.platform.infrastructure.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Turns the connection's remote address into a rate-limit key.
 *
 * <p>The address passed in must be {@code HttpServletRequest.getRemoteAddr()} and nothing else.
 * Forwarding headers ({@code X-Forwarded-For}, {@code Forwarded}, {@code X-Real-IP}) are never read
 * here, because any client can send them. When Sahaj runs behind a reverse proxy, the web server
 * itself rewrites the remote address from {@code X-Forwarded-For}, and only when the direct peer
 * is a proxy listed in {@code SAHAJ_TRUSTED_PROXIES} (see {@code application.yml}).
 *
 * <p>An IPv6 client is keyed by its /64 network, because one subscriber normally owns a whole
 * /64 and could otherwise rotate through addresses to get a fresh allowance every time.
 */
public final class ClientKey {

    static final String UNKNOWN = "unknown";

    private ClientKey() {
    }

    public static String of(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return UNKNOWN;
        }
        String address = remoteAddress.trim();
        if (address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return ipv4Key(address);
        }
        if (!address.matches("[0-9a-fA-F:.]+(%[\\w.-]+)?") || address.indexOf(':') < 0) {
            // Not an IP literal: never resolve it (that would be a DNS lookup); share one bucket.
            return UNKNOWN;
        }
        try {
            // Contains ':' so it can only be an IPv6 literal; the JDK does not do a lookup for it.
            byte[] bytes = InetAddress.getByName(address).getAddress();
            if (bytes.length == 4) {
                return ipv4Key(InetAddress.getByAddress(bytes).getHostAddress());
            }
            StringBuilder key = new StringBuilder("v6:");
            for (int i = 0; i < 8; i++) {
                key.append(String.format("%02x", bytes[i] & 0xFF));
            }
            return key.toString();
        } catch (UnknownHostException ex) {
            return UNKNOWN;
        }
    }

    private static String ipv4Key(String dotted) {
        for (String octet : dotted.split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                return UNKNOWN;
            }
        }
        return dotted;
    }
}
