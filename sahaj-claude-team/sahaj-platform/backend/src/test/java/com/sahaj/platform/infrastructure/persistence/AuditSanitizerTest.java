package com.sahaj.platform.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditSanitizerTest {

    @Test
    void masksMobileAndPhoneValuesToTheLastFourDigits() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of(
                "mobile", "9123457410", "phone", "+91 98765 43210", "whatsapp", "9123451111"));

        assertThat(result.get("mobile")).isEqualTo("••••••7410");
        assertThat(result.get("phone")).isEqualTo("••••••3210");
        assertThat(result.get("whatsapp")).isEqualTo("••••••1111");
    }

    @Test
    void replacesOneTimeCodesPinsAndPasswords() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of(
                "otp", "123456", "pin", "4321", "password", "x", "secret", "y", "token", "z"));

        assertThat(result.values()).containsOnly(AuditSanitizer.REDACTED);
    }

    @Test
    void keepsThePinStateLabelOfTheStaffPinAuditRowsButNothingElseNamedLikeAPin() {
        for (String label : new String[] {"SET", "LOCKED", "NOT_SET"}) {
            assertThat(AuditSanitizer.sanitize("STAFF_PIN", Map.of("pinState", label))).containsEntry("pinState", label);
        }
        Map<String, Object> result = AuditSanitizer.sanitize("STAFF_PIN", Map.of(
                "pin", "SET", "pinHash", "$2a$12$abc", "pinState2", "SET", "newPin", "4821"));
        assertThat(result.values()).containsOnly(AuditSanitizer.REDACTED);
        assertThat(AuditSanitizer.sanitize("STAFF_PIN", Map.of("pinState", "4821")))
                .as("any other value under pinState is still replaced").containsEntry("pinState", AuditSanitizer.REDACTED);
        assertThat(AuditSanitizer.sanitize("STAFF_PIN", Map.of("reason", "RESET_BY_OWNER", "lockCount", 2,
                "lockedUntil", "2026-11-06T06:15:00Z", "membershipId", "3f0c9a52-6a1e-4f7e-9d1b-2b8f4e7c1a10")))
                .containsEntry("reason", "RESET_BY_OWNER").containsEntry("lockCount", 2)
                .containsEntry("lockedUntil", "2026-11-06T06:15:00Z");
    }

    @Test
    void keepsTheColleagueCheckAuditFieldsIdsAndTriesLeftIntact() {
        // SAH-32: PLATFORM_PIN_COLLEAGUE_CHECK_FAILED holds ids and a number only; none of the keys may be redacted
        Map<String, Object> result = AuditSanitizer.sanitize("STAFF_PIN", Map.of(
                "membershipId", "3f0c9a52-6a1e-4f7e-9d1b-2b8f4e7c1a10",
                "checkedByMembershipId", "8a1e2c44-0b7d-4c19-8f55-6d3e9a7b2c01",
                "triesLeft", 3));

        assertThat(result).containsEntry("membershipId", "3f0c9a52-6a1e-4f7e-9d1b-2b8f4e7c1a10")
                .containsEntry("checkedByMembershipId", "8a1e2c44-0b7d-4c19-8f55-6d3e9a7b2c01")
                .containsEntry("triesLeft", 3);
    }

    @Test
    void keepsTheColleagueHardeningAuditFieldsCausedByAndWrongTriesReadable() {
        // SAH-38 (ADR-014 D12): causedBy (SELF or COLLEAGUES) and wrongTries are ids-and-kinds data without "pin" in the key
        Map<String, Object> locked = AuditSanitizer.sanitize("STAFF_PIN", Map.of(
                "membershipId", "3f0c9a52-6a1e-4f7e-9d1b-2b8f4e7c1a10", "pinState", "LOCKED", "lockCount", 1,
                "causedBy", "COLLEAGUES", "lockedUntil", "2026-11-07T05:50:12Z"));
        Map<String, Object> right = AuditSanitizer.sanitize("STAFF_PIN", Map.of(
                "membershipId", "3f0c9a52-6a1e-4f7e-9d1b-2b8f4e7c1a10",
                "checkedByMembershipId", "8a1e2c44-0b7d-4c19-8f55-6d3e9a7b2c01", "wrongTries", 3));

        assertThat(locked).containsEntry("causedBy", "COLLEAGUES").containsEntry("pinState", "LOCKED")
                .containsEntry("lockCount", 1);
        assertThat(right).containsEntry("wrongTries", 3)
                .containsEntry("checkedByMembershipId", "8a1e2c44-0b7d-4c19-8f55-6d3e9a7b2c01");
    }

    @Test
    void aNameOrAPinPutNextToTheColleagueHardeningFieldsIsStillReplaced() {
        Map<String, Object> result = AuditSanitizer.sanitize("STAFF_PIN", Map.of(
                "causedBy", "COLLEAGUES", "staffName", "Sunita Pawar", "colleaguePin", "4821", "byName", "x"));

        assertThat(result).containsEntry("causedBy", "COLLEAGUES").containsEntry("staffName", AuditSanitizer.REDACTED)
                .containsEntry("colleaguePin", AuditSanitizer.REDACTED);
    }

    @Test
    void anIdWhoseLastGroupIsTenOrMoreDigitsIsNotMaskedAsAMobileNumber() {
        String id = "3f0c9a52-6a1e-4f7e-9d1b-123456789012";

        Map<String, Object> result = AuditSanitizer.sanitize("STAFF_PIN", Map.of("membershipId", id,
                "checkedByMembershipId", id.toUpperCase(java.util.Locale.ROOT), "ids", List.of(id)));

        assertThat(result.get("membershipId")).isEqualTo(id);
        assertThat(result.get("checkedByMembershipId")).isEqualTo(id.toUpperCase(java.util.Locale.ROOT));
        assertThat(result.get("ids")).isEqualTo(List.of(id));
    }

    @Test
    void aTenDigitRunInsideOtherTextIsStillMaskedEvenNextToAnId() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of("text", "call 9123457410 about "
                + "3f0c9a52-6a1e-4f7e-9d1b-123456789012"));

        assertThat(result.get("text").toString()).doesNotContain("9123457410").contains("••••••7410");
    }

    @Test
    void keepsOrdinaryValuesIncludingAFieldNamedCode() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of("specialty", "gp", "code", "dental", "count", 3));

        assertThat(result).containsEntry("specialty", "gp").containsEntry("code", "dental").containsEntry("count", 3);
    }

    @Test
    void looksInsideNestedMapsAndLists() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of(
                "owner", Map.of("mobile", "9123457410", "name", "Dr Rao"),
                "staff", List.of(Map.of("mobile", "9000000001"), "plain")));

        assertThat(result.toString()).doesNotContain("9123457410").doesNotContain("9000000001");
        assertThat(result.toString()).contains("••••••7410").contains("••••••0001").contains("plain");
    }

    @Test
    void passesNullThrough() {
        assertThat(AuditSanitizer.sanitize(null)).isNull();
    }

    @Test
    void aTooShortValueIsFullyMasked() {
        assertThat(AuditSanitizer.maskMobile("12")).isEqualTo("••••");
    }

    @Test
    void matchesKeysByNormalisedSubstringNotExactName() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of(
                "Mobile_Number", "9123457410", "WHATSAPP-NO", "9000000001",
                "abhaNumber", "9876500001", "patientName", "Dr Rao", "clinicalNotes", "Feels better"));

        assertThat(result.get("Mobile_Number")).isEqualTo("••••••7410");
        assertThat(result.get("WHATSAPP-NO")).isEqualTo("••••••0001");
        assertThat(result.get("abhaNumber")).isEqualTo("••••••0001");
        assertThat(result.get("patientName")).isEqualTo(AuditSanitizer.REDACTED);
        assertThat(result.get("clinicalNotes")).isEqualTo(AuditSanitizer.REDACTED);
    }

    @Test
    void keepsAClinicsOwnBusinessNameVisibleButRedactsAPersonsName() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of(
                "name", "Sahaj Clinic", "clinicName", "Sahaj Clinic", "tenantName", "Sahaj Clinic",
                "businessName", "Sahaj Clinic",
                "ownerName", "Dr Rao", "fullName", "Dr Rao", "contactName", "Dr Rao",
                "staffName", "Dr Rao", "doctorName", "Dr Rao", "patientName", "Dr Rao"));

        assertThat(result.get("name")).isEqualTo("Sahaj Clinic");
        assertThat(result.get("clinicName")).isEqualTo("Sahaj Clinic");
        assertThat(result.get("tenantName")).isEqualTo("Sahaj Clinic");
        assertThat(result.get("businessName")).isEqualTo("Sahaj Clinic");
        assertThat(result.get("ownerName")).isEqualTo(AuditSanitizer.REDACTED);
        assertThat(result.get("fullName")).isEqualTo(AuditSanitizer.REDACTED);
        assertThat(result.get("contactName")).isEqualTo(AuditSanitizer.REDACTED);
        assertThat(result.get("staffName")).isEqualTo(AuditSanitizer.REDACTED);
        assertThat(result.get("doctorName")).isEqualTo(AuditSanitizer.REDACTED);
        assertThat(result.get("patientName")).isEqualTo(AuditSanitizer.REDACTED);
    }

    @Test
    void redactsABarePatientNameOnlyForEntityTypePatient() {
        Map<String, Object> patientResult = AuditSanitizer.sanitize("PATIENT",
                Map.of("name", "Asha Rao", "mobile", "9123457410", "uhid", "PT-00001"));
        Map<String, Object> tenantResult = AuditSanitizer.sanitize("TENANT", Map.of("name", "Sahaj Clinic"));

        assertThat(patientResult.get("name")).isEqualTo(AuditSanitizer.REDACTED);
        assertThat(patientResult.get("mobile")).isEqualTo("••••••7410");
        assertThat(patientResult.get("uhid")).isEqualTo("PT-00001");
        assertThat(tenantResult.get("name")).isEqualTo("Sahaj Clinic");
    }

    @Test
    void redactsABareNameForEntityTypeVisitTooButKeepsThePatientId() {
        Map<String, Object> visitResult = AuditSanitizer.sanitize("VISIT",
                Map.of("name", "Asha Rao", "patientId", "p1", "stage", "WAITING"));

        assertThat(visitResult.get("name")).isEqualTo(AuditSanitizer.REDACTED);
        assertThat(visitResult.get("patientId")).isEqualTo("p1");
        assertThat(visitResult.get("stage")).isEqualTo("WAITING");
    }

    @Test
    void walksListsOfListsAsWellAsListsOfMaps() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of(
                "rows", List.of(List.of("plain", "9123457410"), List.of(Map.of("mobile", "9000000001")))));

        String text = result.toString();
        assertThat(text).doesNotContain("9123457410").doesNotContain("9000000001");
        assertThat(text).contains("plain").contains("••••••7410").contains("••••••0001");
    }

    @Test
    void masksATenDigitRunEmbeddedInAnOrdinaryStringValue() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of(
                "description", "Called on 9123457410 about the invoice"));

        assertThat(result.get("description")).isEqualTo("Called on ••••••7410 about the invoice");
    }

    @Test
    void keepsEntityIdentifiersAndFieldNamesAnAuditorNeeds() {
        Map<String, Object> result = AuditSanitizer.sanitize(Map.of(
                "id", "a1", "entityId", "a2", "tenantId", "a3", "specialty", "gp", "code", "dental", "count", 3));

        assertThat(result).containsEntry("id", "a1")
                .containsEntry("entityId", "a2")
                .containsEntry("tenantId", "a3")
                .containsEntry("specialty", "gp")
                .containsEntry("code", "dental")
                .containsEntry("count", 3);
    }

    // ---- S2: clinical free text is redacted for every entity type ----

    @Test
    void redactsAllergyChronicDiagnosisComplaintClinicalAndHistoryTextsForEveryEntityType() {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("allergy", "Penicillin");
        values.put("chronic", "Diabetes");
        values.put("diagnosis", "Typhoid");
        values.put("complaint", "Fever");
        values.put("clinical", "Stable");
        values.put("history", "Old MI");
        for (String entityType : new String[] {null, "PATIENT", "VISIT", "TENANT", "IPD_ADMISSION", "SOMETHING_NEW"}) {
            Map<String, Object> result = AuditSanitizer.sanitize(entityType, values);

            assertThat(result.keySet()).as(String.valueOf(entityType)).containsExactlyElementsOf(values.keySet());
            assertThat(result.values()).as(String.valueOf(entityType)).containsOnly(AuditSanitizer.REDACTED);
        }
    }

    @Test
    void redactsTheCamelCaseSnakeCaseAndSpacedVariantsOfThoseKeys() {
        Map<String, Object> result = AuditSanitizer.sanitize("PATIENT", Map.of(
                "allergyText", "Penicillin", "ALLERGY_TEXT", "Penicillin", "chronicConditions", "Diabetes",
                "chronic-text", "Diabetes", "diagnosisText", "Typhoid", "chiefComplaint", "Fever",
                "clinicalNotes", "Stable", "medicalHistory", "Old MI", "history_of_illness", "x",
                "allergyBefore", "Sulfa"));

        assertThat(result.values()).hasSize(10).containsOnly(AuditSanitizer.REDACTED);
    }

    @Test
    void redactsTheTextsInsideNestedMapsAndLists() {
        Map<String, Object> result = AuditSanitizer.sanitize("TENANT", Map.of(
                "before", Map.of("allergy", "Penicillin", "id", "p1"),
                "rows", List.of(Map.of("chronic", "Diabetes"), List.of(Map.of("diagnosis", "Typhoid")))));

        assertThat(result.toString()).doesNotContain("Penicillin").doesNotContain("Diabetes")
                .doesNotContain("Typhoid").contains("p1");
    }

    @Test
    void keepsTheTwoRecordedFlagsOfThePatientAuditRowsWhenTheyAreTrueOrFalse() {
        Map<String, Object> result = AuditSanitizer.sanitize("PATIENT", Map.of(
                "allergyRecorded", true, "chronicRecorded", false, "age", 54));

        assertThat(result).containsEntry("allergyRecorded", true).containsEntry("chronicRecorded", false)
                .containsEntry("age", 54);
    }

    @Test
    void aRecordedFlagHoldingTextIsStillRedactedAndNoOtherKeyWithTheWordGetsAFreePass() {
        Map<String, Object> result = AuditSanitizer.sanitize("PATIENT", Map.of(
                "allergyRecorded", "Penicillin", "chronicRecorded", "Diabetes", "allergyFlag", true,
                "allergyRecordedText", true, "diagnosisRecorded", true));

        assertThat(result.values()).containsOnly(AuditSanitizer.REDACTED);
    }

    @Test
    void thePinStateExceptionStillWorksNextToTheNewKeys() {
        assertThat(AuditSanitizer.sanitize("STAFF_PIN", Map.of("pinState", "LOCKED", "history", "x")))
                .containsEntry("pinState", "LOCKED").containsEntry("history", AuditSanitizer.REDACTED);
    }
}
