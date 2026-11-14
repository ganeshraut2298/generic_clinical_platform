package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Context;
import com.sahaj.opd.application.emr.EmrComponentValues.Lookups;
import com.sahaj.opd.application.emr.EmrModel.ComponentRef;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-51-6 and SAH-67-1 (ADR-025 D6, D8): the FOLLOW_UP interval component of an OPD visit and the IPD_ORDERS component of a
 * round. The interval is one of five keys; the order rows keep only ids and ipd catalogue keys, and the server alone stamps
 * the order id and time.
 */
class EmrPhase5ComponentsTest {

    private static final UUID WHO = UUID.randomUUID();
    private final EmrComponentValues values = new EmrComponentValues(EmrTestData.MAPPER);

    private Context context(boolean keysKnown) {
        return new Context(Instant.parse("2026-11-13T05:00:00Z"), WHO, null, new Lookups() {
            @Override
            public Optional<EmrComponentValues.MedicineRef> medicine(UUID medicineId) {
                return Optional.empty();
            }

            @Override
            public boolean activeService(UUID serviceId) {
                return false;
            }

            @Override
            public boolean ipdOrderKeys(String drugKey, String frequencyKey) {
                return keysKnown && "pcm".equals(drugKey);
            }
        });
    }

    private static FieldDefinition field(String component) {
        return EmrTestData.field("x", "DATE", false).asComponent(new ComponentRef(component, null));
    }

    private JsonNode json(String text) throws Exception {
        return EmrTestData.MAPPER.readTree(text);
    }

    // ---- binding ----

    @Test
    void theFollowUpNextVisitFieldIsTheFollowUpComponentAndItsReasonIsCovered() {
        List<FieldDefinition> bound = EmrComponentBinding.bind("followup", List.of(
                EmrTestData.field("next_visit", "DATE", false), EmrTestData.field("reason", "TEXT", false)));

        assertThat(bound.get(0).component().type()).isEqualTo("FOLLOW_UP");
        assertThat(bound.get(0).valueKind()).isEqualTo(EmrValueKind.COMPONENT);
        assertThat(bound.get(1).valueKind()).isEqualTo(EmrValueKind.COVERED);
        assertThat(EmrComponentBinding.bind("other", List.of(EmrTestData.field("next_visit", "DATE", false))).get(0)
                .component()).isNull();
    }

    @Test
    void anIpdRoundRebindsThePrescriptionToIpdOrders() {
        FieldDefinition rx = EmrTestData.field("drug", "SEARCH_LIST", false).asComponent(new ComponentRef("PRESCRIPTION", null));
        FieldDefinition plain = EmrTestData.field("note", "TEXT", false);

        List<FieldDefinition> round = EmrComponentBinding.forIpdRound(List.of(rx, plain));

        assertThat(round.get(0).component().type()).isEqualTo("IPD_ORDERS");
        assertThat(round.get(1)).isEqualTo(plain);
    }

    // ---- follow-up ----

    @Test
    void anIntervalOfTheFiveIsKeptAndDoneAndAnythingElseIsRefused() throws Exception {
        for (String interval : List.of("D3", "D7", "D15", "M1", "M3")) {
            EmrComponentValues.Result result = values.normalize(field("FOLLOW_UP"), json("{\"component\":\"FOLLOW_UP\","
                    + "\"schemaVersion\":1,\"interval\":\"" + interval + "\"}"), null, context(true));
            assertThat(result.value().path("interval").asText()).isEqualTo(interval);
            assertThat(EmrComponentValues.isFilled(result.value())).isTrue();
        }
        assertThatThrownBy(() -> values.normalize(field("FOLLOW_UP"), json("{\"component\":\"FOLLOW_UP\","
                + "\"schemaVersion\":1,\"interval\":\"D4\"}"), null, context(true)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
    }

    @Test
    void aNullIntervalIsNotDoneAndTheServerIgnoresTheSentDueDateAndAnOldDateIsNotAnInterval() throws Exception {
        EmrComponentValues.Result none = values.normalize(field("FOLLOW_UP"), json("{\"component\":\"FOLLOW_UP\","
                + "\"schemaVersion\":1,\"interval\":null,\"dueDate\":\"2026-12-01\"}"), null, context(true));

        assertThat(EmrComponentValues.isFilled(none.value())).isFalse();
        assertThat(none.value().has("dueDate")).isFalse();
        assertThat(values.upgrade(field("FOLLOW_UP"), EmrTestData.MAPPER.getNodeFactory().textNode("2026-12-01"))).isNull();
    }

    // ---- IPD orders ----

    private static final String ROW = "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"drugKey\":\"pcm\","
            + "\"frequencyKey\":\"od\"}";

    @Test
    void roundMedicinesKeepTheirKeysAndNeverTakeAnOrderIdFromTheClient() throws Exception {
        ObjectNode input = (ObjectNode) json("{\"component\":\"IPD_ORDERS\",\"schemaVersion\":1,\"rows\":[" + ROW + "]}");
        ((ObjectNode) input.path("rows").get(0)).put("orderId", "22222222-2222-2222-2222-222222222222");

        EmrComponentValues.Result result = values.normalize(field("IPD_ORDERS"), input, null, context(true));

        JsonNode row = result.value().path("rows").get(0);
        assertThat(row.path("drugKey").asText()).isEqualTo("pcm");
        assertThat(row.path("orderId").isNull()).isTrue();
        assertThat(EmrComponentValues.isFilled(result.value())).isTrue();
    }

    @Test
    void aStoredOrderIdSurvivesAnotherSaveOfTheSameRow() throws Exception {
        JsonNode stored = json("{\"component\":\"IPD_ORDERS\",\"schemaVersion\":1,\"rows\":[{\"id\":\""
                + "11111111-1111-1111-1111-111111111111\",\"drugKey\":\"pcm\",\"frequencyKey\":\"od\","
                + "\"orderId\":\"22222222-2222-2222-2222-222222222222\",\"orderedAt\":\"2026-11-13T05:00:00Z\"}]}");

        EmrComponentValues.Result result = values.normalize(field("IPD_ORDERS"), json("{\"component\":\"IPD_ORDERS\","
                + "\"schemaVersion\":1,\"rows\":[" + ROW + "]}"), stored, context(true));

        assertThat(result.value().path("rows").get(0).path("orderId").asText())
                .isEqualTo("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void unknownKeysADuplicateRowABadIdAndMoreThanTwentyRowsAreRefused() throws Exception {
        String wrapped = "{\"component\":\"IPD_ORDERS\",\"schemaVersion\":1,\"rows\":[" + ROW + "]}";
        assertThatThrownBy(() -> values.normalize(field("IPD_ORDERS"), json(wrapped), null, context(false)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
        assertThatThrownBy(() -> values.normalize(field("IPD_ORDERS"), json("{\"component\":\"IPD_ORDERS\","
                + "\"schemaVersion\":1,\"rows\":[" + ROW + "," + ROW + "]}"), null, context(true)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
        assertThatThrownBy(() -> values.normalize(field("IPD_ORDERS"), json("{\"component\":\"IPD_ORDERS\","
                + "\"schemaVersion\":1,\"rows\":[{\"id\":\"nope\",\"drugKey\":\"pcm\",\"frequencyKey\":\"od\"}]}"), null,
                context(true))).isInstanceOf(EmrValueRules.InvalidValue.class);
        StringBuilder many = new StringBuilder("{\"component\":\"IPD_ORDERS\",\"schemaVersion\":1,\"rows\":[");
        for (int i = 0; i < 21; i++) {
            many.append(i == 0 ? "" : ",").append("{\"id\":\"").append(UUID.randomUUID())
                    .append("\",\"drugKey\":\"pcm\",\"frequencyKey\":\"od\"}");
        }
        many.append("]}");
        assertThatThrownBy(() -> values.normalize(field("IPD_ORDERS"), json(many.toString()), null, context(true)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
    }
}
