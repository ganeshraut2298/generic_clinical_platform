package com.sahaj.billing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.billing.application.CourseAccountViews.CoursePaymentInput;
import com.sahaj.billing.web.CourseStrictJsonModule.UnknownFieldException;
import org.junit.jupiter.api.Test;

/** SAH-58: additionalProperties false for the course payment body; the SAH-10 billing bodies keep the default. */
class CourseStrictJsonModuleTest {

    private static final String BODY = "{\"type\":\"PART_PAYMENT\",\"amountPaise\":150000,\"mode\":\"UPI\","
            + "\"useCredit\":true,\"againstSittingId\":null}";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new CourseStrictJsonModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void anExtraFieldIsRefusedAndTheRefusalDoesNotCarryItsName() {
        assertThatThrownBy(() -> mapper.readValue(BODY.replace("{", "{\"patientName\":\"x\","),
                CoursePaymentInput.class)).isInstanceOf(UnknownFieldException.class)
                .hasMessageNotContaining("patientName");
    }

    @Test
    void theClinicFieldsAreAcceptedAndIgnored() throws Exception {
        CoursePaymentInput input = mapper.readValue(
                BODY.replace("{", "{\"tenantId\":\"t\",\"clinicId\":{\"a\":[1,2]},"), CoursePaymentInput.class);

        assertThat(input.type()).isEqualTo("PART_PAYMENT");
        assertThat(input.amountPaise()).isEqualTo(150000L);
        assertThat(input.useCredit()).isTrue();
    }

    @Test
    void theDocumentedFieldsAreReadAsTheyArrived() throws Exception {
        CoursePaymentInput input = mapper.readValue(BODY, CoursePaymentInput.class);

        assertThat(input.mode()).isEqualTo("UPI");
        assertThat(input.againstSittingId()).isNull();
    }

    @Test
    void otherBodiesKeepTheApplicationDefaultAndIgnoreExtraFields() throws Exception {
        Sample sample = mapper.readValue("{\"name\":\"a\",\"extra\":1}", Sample.class);

        assertThat(sample.getName()).isEqualTo("a");
    }

    /** Stands in for any other body type (the SAH-10 billing requests). */
    public static class Sample {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
