package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * DEC-054 / SAH-75 review fix (single none-word list): the allergy chip on the IPD medicine order check. A text that only
 * contains "none known" is RECORDED; the whole text being a none word is NONE_KNOWN. Real endpoints on {@code sahaj_test}.
 * Test plan: docs/test-plans/DEC-054-backend-gates-2026-11-15.md.
 */
@OnTestDatabase
@WithPinPepper
class Sah75AllergyChipIT extends MedicineSupport {

    private String chipFor(String allergy) throws Exception {
        World w = world(allergy);
        JsonNode body = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "pcm", "od", null, null)), 201);
        assertThat(body.get("check").get("hardStops")).as(allergy).isEmpty();
        String status = body.get("check").get("allergyStatus").asText();
        assertThat(emr(w.doctor().auth(), w.adm().id()).get("patient").get("allergyStatus").asText()).as("the EMR header agrees").isEqualTo(status);
        return status;
    }

    @Test
    void aTextThatOnlyContainsNoneKnownIsRecordedNotNone() throws Exception {
        assertThat(chipFor("Peanuts; none known otherwise")).isEqualTo("RECORDED");
        assertThat(chipFor("Rash. None known to penicillin? unsure")).isEqualTo("RECORDED");
    }

    @Test
    void theWholeTextBeingANoneWordIsNoneKnown() throws Exception {
        for (String text : new String[] {"No known allergy", "none known", "NKDA", "Nil", "No known drug allergies."}) {
            assertThat(chipFor(text)).as(text).isEqualTo("NONE_KNOWN");
        }
    }
}
