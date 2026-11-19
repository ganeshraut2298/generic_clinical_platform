package com.sahaj.pharmacy.domain;

import java.util.Locale;

/** The three-character unit code used in own-product codes {@code U-<code>-nnnn} (ADR-028 D2). */
public final class UnitCodes {

    private UnitCodes() {
    }

    /**
     * First three letters or digits of the clinic web address, upper case, padded with X; {@code XXX} when there is none.
     * Derived once, when the unit row is created, and never changed.
     */
    public static String derive(String webAddress) {
        StringBuilder code = new StringBuilder(3);
        if (webAddress != null) {
            for (char c : webAddress.toUpperCase(Locale.ROOT).toCharArray()) {
                if (code.length() == 3) {
                    break;
                }
                if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                    code.append(c);
                }
            }
        }
        while (code.length() < 3) {
            code.append('X');
        }
        return code.toString();
    }
}
