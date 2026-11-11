package com.sahaj.ipd.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.sahaj.ipd.application.AdmissionViews.DepositInput;
import com.sahaj.ipd.application.ScopeViews.EmergencyAccessInput;
import com.sahaj.ipd.application.ScopeViews.WardAssignmentInput;
import com.sahaj.ipd.application.ScopeViews.WardScopeInput;
import org.junit.jupiter.api.Test;

/** SAH-34: additionalProperties false for the three scope request bodies; every other body keeps the default. */
class ScopeStrictJsonModuleTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new ScopeStrictJsonModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void aDurationOrAnyOtherExtraFieldIsRefused() {
        assertThatThrownBy(() -> mapper.readValue(
                "{\"admissionId\":\"x\",\"reasonKey\":\"DETERIORATING\",\"confirmed\":true,\"minutes\":120}",
                EmergencyAccessInput.class)).isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"membershipIds\":[],\"wardId\":\"x\"}",
                WardAssignmentInput.class)).isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"on\":true,\"extra\":1}", WardScopeInput.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void theDocumentedFieldsAreReadAsTheyArrived() throws Exception {
        EmergencyAccessInput input = mapper.readValue(
                "{\"admissionId\":\"x\",\"reasonKey\":\"DETERIORATING\",\"confirmed\":true}", EmergencyAccessInput.class);

        assertThat(input.confirmed().isBoolean()).isTrue();
        assertThat(input.reasonKey().asText()).isEqualTo("DETERIORATING");
        assertThat(mapper.readValue("{\"on\":false}", WardScopeInput.class).on().booleanValue()).isFalse();
    }

    @Test
    void otherBodiesKeepTheApplicationDefaultAndIgnoreExtraFields() throws Exception {
        DepositInput deposit = mapper.readValue("{\"amountPaise\":1000,\"mode\":\"UPI\",\"extra\":1}", DepositInput.class);

        assertThat(deposit.amountPaise()).isEqualTo(1000L);
    }
}
