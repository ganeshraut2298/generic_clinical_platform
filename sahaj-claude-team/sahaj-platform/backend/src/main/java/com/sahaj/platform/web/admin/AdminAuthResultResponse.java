package com.sahaj.platform.web.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Contract schema AdminAuthResult. {@code recoveryCodes} is present only on the response that
 * confirms enrolment and is left out otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminAuthResultResponse(String accessToken, int expiresInSeconds, AdminMeResponse admin,
                                      List<String> recoveryCodes) {

    @Override
    public String toString() {
        return "AdminAuthResultResponse[adminId=" + admin.id() + "]";
    }
}
