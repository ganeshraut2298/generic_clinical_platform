package com.sahaj.platform.infrastructure.admin;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads an IP literal and nothing else: a host name is never resolved (that would be a DNS lookup
 * from an attacker-controlled string). Accepts dotted-quad IPv4 and IPv6, with an optional
 * {@code %zone} suffix that is dropped.
 */
public final class IpLiterals {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

    private IpLiterals() {
    }

    public static Optional<InetAddress> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = text.trim();
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        try {
            if (IPV4.matcher(value).matches()) {
                for (String octet : value.split("\\.")) {
                    if (Integer.parseInt(octet) > 255) {
                        return Optional.empty();
                    }
                }
                return Optional.of(InetAddress.getByName(value));
            }
            if (value.indexOf(':') >= 0 && IPV6.matcher(value).matches()) {
                return Optional.of(InetAddress.getByName(value));
            }
        } catch (UnknownHostException | NumberFormatException ex) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** The address as text for an {@code inet} column, or null when it is not an IP literal. */
    public static String forDatabase(String text) {
        return parse(text).map(InetAddress::getHostAddress).map(IpLiterals::dropIpv6Zone).orElse(null);
    }

    private static String dropIpv6Zone(String address) {
        int zone = address.indexOf('%');
        return zone >= 0 ? address.substring(0, zone) : address;
    }
}
