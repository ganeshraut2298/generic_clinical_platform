package com.sahaj.platform.infrastructure.admin;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which addresses may call {@code /api/v1/admin/**} (ADR-003 D2.8). With
 * {@code SAHAJ_ADMIN_ALLOWED_NETWORKS} set (comma-separated CIDRs or single addresses, IPv4 or
 * IPv6), only those addresses pass. With it empty, only the {@code dev} profile allows everything
 * (the dev server listens on loopback); every other profile refuses everything (fail closed). The
 * address checked is the servlet remote address; forwarding headers are never read here.
 */
public final class AdminNetworkPolicy {

    private record Cidr(byte[] network, int prefixBits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefixBits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Cidr> allowed;
    private final boolean devProfile;

    public AdminNetworkPolicy(String allowedNetworks, boolean devProfile) {
        this.allowed = parse(allowedNetworks);
        this.devProfile = devProfile;
    }

    public boolean permits(String remoteAddress) {
        if (allowed.isEmpty()) {
            return devProfile;
        }
        Optional<InetAddress> address = IpLiterals.parse(remoteAddress);
        if (address.isEmpty()) {
            return false;
        }
        byte[] bytes = address.get().getAddress();
        for (Cidr cidr : allowed) {
            if (cidr.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    private static List<Cidr> parse(String text) {
        List<Cidr> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String part : text.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String host = entry;
            Integer bits = null;
            int slash = entry.indexOf('/');
            if (slash >= 0) {
                host = entry.substring(0, slash);
                try {
                    bits = Integer.parseInt(entry.substring(slash + 1).trim());
                } catch (NumberFormatException ex) {
                    throw invalid();
                }
            }
            InetAddress address = IpLiterals.parse(host).orElseThrow(AdminNetworkPolicy::invalid);
            byte[] bytes = address.getAddress();
            int max = bytes.length * 8;
            if (bits == null) {
                bits = max;
            }
            if (bits < 0 || bits > max) {
                throw invalid();
            }
            result.add(new Cidr(bytes, bits));
        }
        return result;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException(
                "SAHAJ_ADMIN_ALLOWED_NETWORKS has an entry that is not an IP address or CIDR (for example 203.0.113.0/24). "
                        + "Refusing to start the admin portal.");
    }
}
