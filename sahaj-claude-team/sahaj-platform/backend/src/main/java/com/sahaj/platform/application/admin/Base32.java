package com.sahaj.platform.application.admin;

import java.util.Locale;

/**
 * RFC 4648 Base32 (alphabet A-Z, 2-7) written by hand because the JDK has no codec (ADR-003 D2.2).
 * Used for the authenticator secret and the recovery codes. Encoding never pads; decoding accepts
 * lower case and ignores spaces, hyphens and {@code =}. Error messages never echo the input.
 */
public final class Base32 {

    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    public static byte[] decode(String text) {
        String clean = text.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = ALPHABET.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Not a Base32 text");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        return out;
    }

    /** {@code JBSWY3DP...} to {@code JBSW Y3DP ...}: the "Key for manual entry". */
    public static String inGroupsOfFour(String text) {
        StringBuilder out = new StringBuilder(text.length() + text.length() / 4);
        for (int i = 0; i < text.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(text.charAt(i));
        }
        return out.toString();
    }
}
