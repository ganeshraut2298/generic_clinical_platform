package com.sahaj.platform.web.admin;

/** Contract schema LoginResult. {@code next} is TOTP or ENROL. */
public record AdminLoginResponse(String next, String preAuthToken, int expiresInSeconds) {

    @Override
    public String toString() {
        return "AdminLoginResponse[next=" + next + "]";
    }
}
