package com.sahaj.opd.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.opd.application.emr.EmrInputs.PrintPreferencesInput;
import com.sahaj.platform.api.InvalidFieldsException;
import org.junit.jupiter.api.Test;

/**
 * PUT /emr/records/{id}/print-preferences (docs/api/opd.yaml EmrPrintPreferences requires both properties; QA finding
 * F-2): an empty or partial body is a 400, it never resets the choice to the clinic's default.
 */
class EmrPrintPreferencesBodyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void bothPropertiesPresentGiveTheInputAndNullMeansTheClinicDefault() throws Exception {
        assertThat(EmrRecordController.printPreferencesInput(json("{\"language\":\"MR\",\"paperSize\":\"A5\"}")))
                .isEqualTo(new PrintPreferencesInput("MR", "A5"));
        assertThat(EmrRecordController.printPreferencesInput(json("{\"language\":null,\"paperSize\":null}")))
                .isEqualTo(new PrintPreferencesInput(null, null));
    }

    @Test
    void anEmptyOrPartialBodyIsRefusedWithTheMissingFieldNamed() throws Exception {
        assertThatThrownBy(() -> EmrRecordController.printPreferencesInput(json("{}")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(m -> m.field()).containsExactly("language", "paperSize"));
        assertThatThrownBy(() -> EmrRecordController.printPreferencesInput(json("{\"language\":\"EN\"}")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(m -> m.field()).containsExactly("paperSize"));
        assertThatThrownBy(() -> EmrRecordController.printPreferencesInput(json("{\"paperSize\":\"A4\"}")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(m -> m.field()).containsExactly("language"));
    }

    @Test
    void anUnknownFieldOrAWrongTypeOrANonObjectBodyIsRefused() throws Exception {
        assertThatThrownBy(() -> EmrRecordController.printPreferencesInput(
                json("{\"language\":\"EN\",\"paperSize\":\"A4\",\"colour\":true}")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    // DEC-051 M5: the client's property name is never echoed, only the generic body field
                    assertThat(e.errors()).extracting(m -> m.field()).containsExactly("body");
                    assertThat(e.errors().toString()).doesNotContain("colour");
                });
        assertThatThrownBy(() -> EmrRecordController.printPreferencesInput(
                json("{\"language\":\"EN\",\"paperSize\":\"A4\",\"zxqextra\":1,\"another\":2}")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.errors()).hasSize(1);
                    assertThat(e.errors().toString()).doesNotContain("zxqextra").doesNotContain("another");
                });
        assertThatThrownBy(() -> EmrRecordController.printPreferencesInput(json("{\"language\":5,\"paperSize\":\"A4\"}")))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> EmrRecordController.printPreferencesInput(json("[]")))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> EmrRecordController.printPreferencesInput(null))
                .isInstanceOf(InvalidFieldsException.class);
    }
}
