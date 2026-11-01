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
}
