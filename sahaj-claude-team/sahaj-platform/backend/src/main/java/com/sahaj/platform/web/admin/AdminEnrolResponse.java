package com.sahaj.platform.web.admin;

/** Contract schema TotpEnrolment. The key is returned only here, only during enrolment. */
public record AdminEnrolResponse(String secretKey, String otpauthUri, String accountName, String issuer,
                                 int digits, int periodSeconds, String algorithm) {

    @Override
    public String toString() {
        return "AdminEnrolResponse[]";
    }
}
