package com.sahaj.platform.application;

/** Masks a mobile number to its last four digits, for example {@code ••••••7410} (SAH-6 AC6). */
final class MobileMask {

    private static final String BULLETS = "••••••";

    private MobileMask() {
    }

    static String mask(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return BULLETS;
        }
        return BULLETS + mobile.substring(mobile.length() - 4);
    }
}
