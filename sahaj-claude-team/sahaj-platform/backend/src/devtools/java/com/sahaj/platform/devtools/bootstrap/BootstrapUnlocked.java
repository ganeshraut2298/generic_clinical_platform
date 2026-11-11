package com.sahaj.platform.devtools.bootstrap;

/** Contract schema {@code BootstrapUnlocked}. {@code toString} prints no value, so a framework debug line shows nothing. */
public record BootstrapUnlocked(String ticket, int expiresInSeconds) {

    @Override
    public String toString() {
        return "BootstrapUnlocked[]";
    }
}
