package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-27 security review M1 on the real {@code sahaj_test} database: the clinical tail of the reason of a discharge
 * follow-up ("Review after discharge · Fever") is returned only to the roles that may see IPD patients; BILLING,
 * PHARMACIST and COUNSELLOR read the list but get "Review after discharge" without the tail.
 */
@OnTestDatabase
class DischargeFollowUpMaskingIT extends DischargeSupport {

    private static final String FULL = "Review after discharge · Fever";
    private static final String MASKED = "Review after discharge";

    private JsonNode itemAs(Clinic clinic, String role, String admissionId) throws Exception {
        JsonNode list = ok(HttpMethod.GET, FOLLOW_UPS + "?status=UPCOMING", clinic.as(role), null, 200);
        JsonNode item = followUpOf(list, admissionId);
        assertThat(item).as(role + " sees the follow-up row").isNotNull();
        return item;
    }

    @Test
    void rolesWithoutIpdAccessGetTheReasonWithoutTheClinicalTail() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        sign(clinic, id, "normal", "general");
        dischargeNow(clinic, id, "normal", "general", "UPI");

        for (String role : new String[] {"BILLING", "PHARMACIST", "COUNSELLOR"}) {
            JsonNode item = itemAs(clinic, role, id);
            assertThat(item.get("reason").asText()).as(role).isEqualTo(MASKED);
            assertThat(item.get("patientName").asText()).as(role).isNotBlank();
        }
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR", "VISITING_DOCTOR"}) {
            assertThat(itemAs(clinic, role, id).get("reason").asText()).as(role).isEqualTo(FULL);
        }
    }
}
