package com.sahaj.platform.domain;

/**
 * The 4-digit rule and the weak-PIN rules of the personal staff PIN (SAH-31, ADR-010 D4). Pure: no
 * state, no logging, nothing stored. One message covers every weak rule on the screen, so the rules
 * are not taught one by one.
 *
 * <p>A weak PIN is one of: four equal digits, a straight run up or down (no wrap), two repeated pairs
 * ({@code ABAB} or {@code AABB}), a year from 1900 to 2099, the keypad columns {@code 2580} and
 * {@code 0852}, or any four consecutive digits of the person's own mobile number.
 */
public final class PinRules {

    public static final int LENGTH = 4;

    private PinRules() {
    }

    /** True only for exactly four ASCII digits {@code 0-9} (not Arabic-Indic or other Unicode digits). */
    public static boolean isFourDigits(String pin) {
        if (pin == null || pin.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** True for exactly six ASCII digits (the one-time code). */
    public static boolean isSixDigitCode(String code) {
        if (code == null || code.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * True when a well-formed (four ASCII digits) PIN is too easy to guess. {@code mobile} is the person's
     * own mobile number read on the server; it may be null, in which case rule 7 is skipped.
     *
     * @throws IllegalArgumentException when {@code pin} is not four ASCII digits (check {@link #isFourDigits} first)
     */
    public static boolean isWeak(String pin, String mobile) {
        if (!isFourDigits(pin)) {
            throw new IllegalArgumentException("A PIN must be four digits");
        }
        int a = pin.charAt(0) - '0';
        int b = pin.charAt(1) - '0';
        int c = pin.charAt(2) - '0';
        int d = pin.charAt(3) - '0';
        return allEqual(a, b, c, d)
                || straightRun(a, b, c, d)
                || repeatedPairs(a, b, c, d)
                || isYear(a, b, c, d)
                || "2580".equals(pin) || "0852".equals(pin)
                || partOfMobile(pin, mobile);
    }

    private static boolean allEqual(int a, int b, int c, int d) {
        return a == b && b == c && c == d;
    }

    private static boolean straightRun(int a, int b, int c, int d) {
        boolean up = b - a == 1 && c - b == 1 && d - c == 1;
        boolean down = a - b == 1 && b - c == 1 && c - d == 1;
        return up || down;
    }

    /** ABAB or AABB with A different from B. */
    private static boolean repeatedPairs(int a, int b, int c, int d) {
        boolean abab = a == c && b == d && a != b;
        boolean aabb = a == b && c == d && a != c;
        return abab || aabb;
    }

    private static boolean isYear(int a, int b, int c, int d) {
        int value = a * 1000 + b * 100 + c * 10 + d;
        return value >= 1900 && value <= 2099;
    }

    private static boolean partOfMobile(String pin, String mobile) {
        if (mobile == null) {
            return false;
        }
        StringBuilder digits = new StringBuilder(mobile.length());
        for (int i = 0; i < mobile.length(); i++) {
            char ch = mobile.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        return digits.indexOf(pin) >= 0;
    }
}
