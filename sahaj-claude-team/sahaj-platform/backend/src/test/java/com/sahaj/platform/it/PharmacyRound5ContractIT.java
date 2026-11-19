package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The contract points of pharmacy.yaml 0.3.1 that the programme asked QA to prove (docs/handoffs/SAH-78.md, QA clarifications):
 * the high-value threshold limit, a new vendor answers version 0, and a series that has no number issued says so. The two bodies
 * after the opening-stock row cap (D-3) are in {@code PharmacyHardeningImportIT}; the financial-year reading of
 * {@code numbersIssued} (a fixed clock around 1 April) is in the unit test {@code NumberingServiceTest}. Test plan:
 * docs/test-plans/SAH-78.md and SAH-79.md.
 */
@OnTestDatabase
class PharmacyRound5ContractIT extends Sah78CSupport {

    @Test
    void theHighValueThresholdAcceptsNineNineNineNineNineNineNineHundredPaiseAndRefusesOneMore() throws Exception {
        Masters w = mastersClinic();
        JsonNode saved = ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, 9_999_999_900L, Map.of())), 200);
        assertThat(saved.get("highValueThresholdPaise").asLong()).isEqualTo(9_999_999_900L);
        JsonNode refused = problem(call(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(1, 9_999_999_901L, Map.of()))), 400);
        assertType(refused, "validation");
        assertFieldError(refused, "highValueThresholdPaise");
        assertThat(ok(HttpMethod.GET, RULES_URL, w.clinic().owner(), null, 200).get("highValueThresholdPaise").asLong()).as("nothing was saved").isEqualTo(9_999_999_900L);
    }

    @Test
    void aRuleLevelAmountHasTheSameLimit() throws Exception {
        Masters w = mastersClinic();
        ok(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(0, null, only(rule("PURCHASE_ORDER", null, null,
                level(1, "OWNER", "ABOVE_AMOUNT", 9_999_999_900L, null, null))))), 200);
        problem(call(HttpMethod.PUT, RULES_URL, w.clinic().owner(), js(rulesBody(1, null, only(rule("PURCHASE_ORDER", null, null,
                level(1, "OWNER", "ABOVE_AMOUNT", 9_999_999_901L, null, null)))))), 400);
    }

    @Test
    void aNewVendorAnswersVersionZeroAndTheFirstUpdateMakesItOne() throws Exception {
        Masters w = mastersClinic();
        JsonNode vendor = createVendor(w.clinic(), vendorBody("Version Vendor (fake)"));
        assertThat(vendor.get("version").asLong()).isZero();
        Map<String, Object> update = vendorUpdate(vendor);
        update.put("contactName", "Another Contact");
        JsonNode updated = ok(HttpMethod.PUT, vendorUrl(idOf(vendor)), w.clinic().owner(), js(update), 200);
        assertThat(updated.get("version").asLong()).isOne();
    }

    @Test
    void aSeriesWithNoNumberIssuedSaysSoAndOneWithANumberIssuedIsLockedAndAnotherTypeIsNot() throws Exception {
        Masters w = mastersClinic();
        JsonNode before = ok(HttpMethod.GET, SERIES_URL, w.clinic().owner(), null, 200);
        assertThat(seriesOf(before, "GRN").get("numbersIssued").asBoolean()).isFalse();
        issueNumber(w.clinic(), "GRN");
        JsonNode after = ok(HttpMethod.GET, SERIES_URL, w.clinic().owner(), null, 200);
        assertThat(seriesOf(after, "GRN").get("numbersIssued").asBoolean()).isTrue();
        assertThat(seriesOf(after, "INDENT").get("numbersIssued").asBoolean()).isFalse();
        assertType(problem(call(HttpMethod.PUT, SERIES_URL + "/GRN", w.clinic().owner(), js(seriesBody(0, "GRY-", false, 4, false))), 409), "series-locked");
        ok(HttpMethod.PUT, SERIES_URL + "/INDENT", w.clinic().owner(), js(seriesBody(0, "INX-", false, 4, false)), 200);
    }
}
