package com.sahaj.patient.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-73 (ADR-024 D3, BR73-12): the fixed allow-list of roles that may see a patient's date of birth. */
class DateOfBirthAccessTest {

    @Test
    void ownerDoctorVisitingDoctorNurseAndFrontDeskMaySeeTheDate() {
        for (String role : List.of("OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK")) {
            assertThat(DateOfBirthAccess.mayView(role)).as(role).isTrue();
        }
    }

    @Test
    void billingPharmacistCounsellorAnyCustomRoleAndNoRoleMayNot() {
        for (String role : new String[] {"BILLING", "PHARMACIST", "COUNSELLOR", "CUSTOM_RECEPTION", "owner", "", null}) {
            assertThat(DateOfBirthAccess.mayView(role)).as(String.valueOf(role)).isFalse();
        }
    }
}
