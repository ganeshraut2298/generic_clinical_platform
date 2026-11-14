package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrComponentValues.Context;
import com.sahaj.opd.application.emr.EmrComponentValues.Lookups;
import com.sahaj.opd.application.emr.EmrComponentValues.MedicineRef;
import com.sahaj.opd.application.emr.EmrComponentValues.Result;
import com.sahaj.opd.application.emr.EmrModel.ComponentRef;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmrComponentValuesTest {

    private static final Instant T1 = Instant.parse("2026-11-12T06:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-12T06:05:00Z");
    private static final UUID DOC = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID TELMI = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();

    private final EmrComponentValues values = new EmrComponentValues(EmrTestData.MAPPER);
    private final Lookups lookups = new Lookups() {
        @Override
        public Optional<MedicineRef> medicine(UUID id) {
            return TELMI.equals(id) ? Optional.of(new MedicineRef(TELMI, "Telmisartan 40 mg", "Tablet", null, "1-0-0",
                    30, "AFTER_FOOD")) : Optional.empty();
        }

        @Override
        public boolean activeService(UUID id) {
            return SERVICE.equals(id);
        }
    };

    private static Context at(Instant now, UUID who) {
        return new Context(now, who, null, null);
    }

    private Context ctx(Instant now, UUID who) {
        return new Context(now, who, null, lookups);
    }

    private static FieldDefinition field(String type, String kind) {
        return new FieldDefinition("f", "F", "SEARCH_LIST", EmrValueKind.COMPONENT, "", false, "MASTER", "x", true,
                java.util.List.of(), java.util.List.of(), null, null, new ComponentRef(type, kind));
    }

    private JsonNode json(String text) {
        try {
            return EmrTestData.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String list(String kind, String items) {
        return "{\"component\":\"EMR_LIST\",\"kind\":\"" + kind + "\",\"schemaVersion\":1,\"items\":[" + items + "]}";
    }

    private static String item(String id, String key, String text, String extra) {
        return "{\"id\":\"" + id + "\",\"itemKey\":" + (key == null ? "null" : "\"" + key + "\"") + ",\"text\":\"" + text
                + "\",\"newToList\":" + (key == null) + extra + "}";
    }

    // ---- EMR list ----

    @Test
    void aTypedItemGetsACapitalAndTheDefaultsAndTheServerStamps() {
        UUID id = UUID.randomUUID();
        Result result = values.normalize(field("EMR_LIST", "COMPLAINTS"),
                json(list("COMPLAINTS", item(id.toString(), null, "knee pain", ""))), null, ctx(T1, DOC));

        JsonNode saved = result.value().path("items").get(0);
        assertThat(saved.path("text").asText()).isEqualTo("Knee pain");
        assertThat(saved.path("attr1").asText()).isEqualTo("D3");
        assertThat(saved.path("attr2").asText()).isEqualTo("MODERATE");
        assertThat(saved.path("active").asBoolean()).isTrue();
        assertThat(saved.path("addedAt").asText()).isEqualTo(T1.toString());
        assertThat(saved.path("addedByMembershipId").asText()).isEqualTo(DOC.toString());
    }

    @Test
    void stampsSentByTheClientAreIgnoredAndTheFirstSaveStampStays() {
        UUID id = UUID.randomUUID();
        JsonNode first = values.normalize(field("EMR_LIST", "COMPLAINTS"),
                json(list("COMPLAINTS", item(id.toString(), "k1", "Fever", ""))), null, ctx(T1, DOC)).value();
        String forged = ",\"addedAt\":\"2020-01-01T00:00:00Z\",\"addedByMembershipId\":\"" + OTHER + "\"";

        JsonNode second = values.normalize(field("EMR_LIST", "COMPLAINTS"),
                json(list("COMPLAINTS", item(id.toString(), "k1", "Fever", forged))), first, ctx(T2, OTHER)).value();

        assertThat(second.path("items").get(0).path("addedAt").asText()).isEqualTo(T1.toString());
        assertThat(second.path("items").get(0).path("addedByMembershipId").asText()).isEqualTo(DOC.toString());
    }

    @Test
    void theSameTextTwiceIgnoringCaseIsDuplicateItem() {
        String items = item(UUID.randomUUID().toString(), "a", "Fever", "") + ","
                + item(UUID.randomUUID().toString(), null, "fever", "");

        assertThatThrownBy(() -> values.normalize(field("EMR_LIST", "COMPLAINTS"), json(list("COMPLAINTS", items)),
                null, ctx(T1, DOC))).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("duplicate-item"));
    }

    @Test
    void anAttributeOutsideItsListIs400AndMoreThanFiftyItemsIsRefused() {
        String bad = item(UUID.randomUUID().toString(), "a", "Fever", ",\"attr1\":\"D4\"");
        assertThatThrownBy(() -> values.normalize(field("EMR_LIST", "COMPLAINTS"), json(list("COMPLAINTS", bad)), null,
                ctx(T1, DOC))).isInstanceOf(EmrValueRules.InvalidValue.class);

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            many.append(i > 0 ? "," : "").append(item(UUID.randomUUID().toString(), "k" + i, "Item " + i, ""));
        }
        assertThatThrownBy(() -> values.normalize(field("EMR_LIST", "COMPLAINTS"),
                json(list("COMPLAINTS", many.toString())), null, ctx(T1, DOC)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
    }

    @Test
    void adviceByWhatsappIsRefusedUntilTheWhatsappService() {
        String advice = item(UUID.randomUUID().toString(), "a", "Rest", ",\"attr2\":\"PRINT_WHATSAPP\"");

        assertThatThrownBy(() -> values.normalize(field("EMR_LIST", "ADVICE"), json(list("ADVICE", advice)), null,
                ctx(T1, DOC))).isInstanceOfSatisfying(EmrProblemException.class,
                ex -> assertThat(ex.slug()).isEqualTo("needs-whatsapp-service"));
    }

    @Test
    void aDiagnosisCodeIsSetFromTheMasterKeyAndAServiceMustBeActive() {
        JsonNode diagnosis = values.normalize(field("EMR_LIST", "DIAGNOSIS"),
                json(list("DIAGNOSIS", item(UUID.randomUUID().toString(), "J06.9", "Acute URI", ""))), null,
                ctx(T1, DOC)).value();
        assertThat(diagnosis.path("items").get(0).path("code").asText()).isEqualTo("J06.9");

        String unknown = item(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Dressing", "");
        assertThatThrownBy(() -> values.normalize(field("EMR_LIST", "SERVICES"), json(list("SERVICES", unknown)),
                null, ctx(T1, DOC))).isInstanceOf(EmrValueRules.InvalidValue.class);
        String known = item(UUID.randomUUID().toString(), SERVICE.toString(), "Dressing", ",\"attr1\":\"2\"");
        assertThat(values.normalize(field("EMR_LIST", "SERVICES"), json(list("SERVICES", known)), null, ctx(T1, DOC))
                .value().path("items").get(0).path("attr2").asText()).isEqualTo("BILL_TODAY");
    }

    @Test
    void aPhaseTwoItemArrayIsReadAsActiveItemsWithDefaults() {
        JsonNode legacy = json("[{\"itemKey\":\"k1\",\"text\":\"Fever\",\"newToList\":false}]");

        JsonNode upgraded = values.upgrade(field("EMR_LIST", "COMPLAINTS"), legacy);

        assertThat(upgraded.path("component").asText()).isEqualTo("EMR_LIST");
        assertThat(upgraded.path("items").get(0).path("active").asBoolean()).isTrue();
        assertThat(EmrComponentValues.isFilled(upgraded)).isTrue();
    }

    // ---- Done rule ----

    @Test
    void aListIsFilledOnlyByAnActiveItemAndABodyMapByAPinOrNoFindings() {
        assertThat(EmrComponentValues.isFilled(json(list("COMPLAINTS",
                item("a", "k", "Fever", ",\"active\":false"))))).isFalse();
        assertThat(EmrComponentValues.isFilled(json(list("COMPLAINTS", item("a", "k", "Fever", ""))))).isTrue();
        assertThat(EmrComponentValues.isFilled(json("{\"component\":\"PRESCRIPTION\",\"rows\":[]}"))).isFalse();
        assertThat(EmrComponentValues.isFilled(json("{\"component\":\"BODY_MAP\",\"pins\":[],"
                + "\"noFindingsConfirmed\":true}"))).isTrue();
        assertThat(EmrValueRules.isEmpty(json("{\"component\":\"BODY_MAP\",\"pins\":[],\"noFindingsConfirmed\":false}")))
                .isTrue();
    }

    // ---- prescription ----

    private static String row(String id, String medicine, String name, String extra) {
        return "{\"id\":\"" + id + "\",\"medicineId\":" + (medicine == null ? "null" : "\"" + medicine + "\"")
                + ",\"name\":\"" + name + "\",\"form\":null,\"frequency\":\"1-0-1\",\"days\":5,"
                + "\"when\":\"AFTER_FOOD\",\"source\":\"SEARCH\"" + extra + "}";
    }

    private static String rx(String rows) {
        return "{\"component\":\"PRESCRIPTION\",\"schemaVersion\":1,\"rows\":[" + rows + "]}";
    }

    @Test
    void aMasterMedicineKeepsTheMasterNameAndATypedOneIsTyped() {
        String rows = row(UUID.randomUUID().toString(), TELMI.toString(), "anything", "") + ","
                + row(UUID.randomUUID().toString(), null, "Home remedy", "");

        JsonNode saved = values.normalize(field("PRESCRIPTION", null), json(rx(rows)), null, ctx(T1, DOC)).value();

        assertThat(saved.path("rows").get(0).path("name").asText()).isEqualTo("Telmisartan 40 mg");
        assertThat(saved.path("rows").get(0).path("form").asText()).isEqualTo("Tablet");
        assertThat(saved.path("rows").get(1).path("source").asText()).isEqualTo("TYPED");
        assertThat(saved.path("rows").get(1).path("medicineId").isNull()).isTrue();
    }

    @Test
    void daysOutsideOneTo365AndAnUnknownFrequencyAreRefusedAndNothingIsKept() {
        String zero = rx(row(UUID.randomUUID().toString(), null, "A", "").replace("\"days\":5", "\"days\":0"));
        String freq = rx(row(UUID.randomUUID().toString(), null, "B", "").replace("1-0-1", "2-0-2"));

        assertThatThrownBy(() -> values.normalize(field("PRESCRIPTION", null), json(zero), null, ctx(T1, DOC)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
        assertThatThrownBy(() -> values.normalize(field("PRESCRIPTION", null), json(freq), null, ctx(T1, DOC)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
    }

    @Test
    void theSameMedicineNameTwiceIsDuplicateItemAndAnUnknownMedicineIsRefused() {
        String twice = rx(row(UUID.randomUUID().toString(), null, "Aspirin", "") + ","
                + row(UUID.randomUUID().toString(), null, "ASPIRIN", ""));
        assertThatThrownBy(() -> values.normalize(field("PRESCRIPTION", null), json(twice), null, ctx(T1, DOC)))
                .isInstanceOfSatisfying(EmrProblemException.class,
                        ex -> assertThat(ex.slug()).isEqualTo("duplicate-item"));
        String unknown = rx(row(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "x", ""));
        assertThatThrownBy(() -> values.normalize(field("PRESCRIPTION", null), json(unknown), null, ctx(T1, DOC)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
    }

    @Test
    void anAllergyReviewIsStampedOnceAuditedOnceAndKeptOnLaterSaves() {
        UUID id = UUID.randomUUID();
        String review = ",\"allergyReview\":{\"allergyClass\":\"SULFA\",\"confirmed\":true}";

        Result first = values.normalize(field("PRESCRIPTION", null),
                json(rx(row(id.toString(), TELMI.toString(), "x", review))), null, ctx(T1, DOC));
        Result second = values.normalize(field("PRESCRIPTION", null),
                json(rx(row(id.toString(), TELMI.toString(), "x", review))), first.value(), ctx(T2, OTHER));

        assertThat(first.notes()).hasSize(1);
        assertThat(first.notes().get(0).kind()).isEqualTo("ALLERGY_REVIEWED");
        assertThat(first.notes().get(0).values()).doesNotContainValue("Telmisartan 40 mg");
        assertThat(second.notes()).isEmpty();
        assertThat(second.value().path("rows").get(0).path("allergyReview").path("reviewedAt").asText())
                .isEqualTo(T1.toString());
        assertThat(second.value().path("rows").get(0).path("allergyReview").path("reviewedByMembershipId").asText())
                .isEqualTo(DOC.toString());
    }

    // ---- body map ----

    private static String pin(int no, String cause, boolean mlc, String extra) {
        return "{\"no\":" + no + ",\"side\":\"FRONT\",\"x\":0.5,\"y\":0.4,\"region\":\"head\",\"where\":\"Head\","
                + "\"finding\":\"WOUND\",\"cause\":\"" + cause + "\",\"severity\":\"MILD\",\"sizeCm\":2.5,"
                + "\"note\":\"n\",\"mlc\":" + mlc + extra + "}";
    }

    private static String map(String pins, boolean none) {
        return "{\"component\":\"BODY_MAP\",\"schemaVersion\":1,\"pins\":[" + pins + "],\"noFindingsConfirmed\":"
                + none + "}";
    }

    @Test
    void aRoadAccidentPinIsMlcWhateverWasSentAndOneAuditNoteIsMade() {
        Result result = values.normalize(field("BODY_MAP", null), json(map(pin(1, "ROAD_ACCIDENT", false, ""), false)),
                null, ctx(T1, DOC));

        assertThat(result.value().path("pins").get(0).path("mlc").asBoolean()).isTrue();
        assertThat(result.value().path("nextPinNo").asInt()).isEqualTo(2);
        assertThat(result.notes()).hasSize(1);
        assertThat(result.notes().get(0).values()).containsEntry("pinNo", 1).containsEntry("mlc", true)
                .doesNotContainKeys("note", "cause", "finding");
        assertThat(EmrComponentValues.hasMlcPin(result.value())).isTrue();
    }

    @Test
    void theDoctorCanClearTheFlagByHandAndThatIsAudited() {
        JsonNode stored = values.normalize(field("BODY_MAP", null), json(map(pin(1, "ASSAULT", false, ""), false)),
                null, ctx(T1, DOC)).value();

        Result cleared = values.normalize(field("BODY_MAP", null), json(map(pin(1, "ASSAULT", false, ""), false)),
                stored, ctx(T2, DOC));

        assertThat(cleared.value().path("pins").get(0).path("mlc").asBoolean()).isFalse();
        assertThat(cleared.notes()).hasSize(1);
        assertThat(cleared.notes().get(0).values()).containsEntry("mlc", false);
    }

    @Test
    void aRemovedPinNumberIsNeverReusedAndRemovingAnMlcPinIsAudited() {
        JsonNode stored = values.normalize(field("BODY_MAP", null),
                json(map(pin(1, "FALL", false, "") + "," + pin(2, "BURN", false, ""), false)), null, ctx(T1, DOC))
                .value();

        Result removed = values.normalize(field("BODY_MAP", null), json(map(pin(1, "FALL", false, ""), false)),
                stored, ctx(T2, DOC));
        assertThat(removed.notes()).hasSize(1);
        assertThat(removed.value().path("nextPinNo").asInt()).isEqualTo(3);

        assertThatThrownBy(() -> values.normalize(field("BODY_MAP", null),
                json(map(pin(1, "FALL", false, "") + "," + pin(2, "FALL", false, ""), false)), removed.value(),
                ctx(T2, DOC))).isInstanceOf(EmrValueRules.InvalidValue.class);
    }

    @Test
    void pinFieldsOutsideTheirListsAreRefused() {
        assertThatThrownBy(() -> values.normalize(field("BODY_MAP", null),
                json(map(pin(1, "FALL", false, "").replace("\"head\"", "\"elbow\""), false)), null, ctx(T1, DOC)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
        assertThatThrownBy(() -> values.normalize(field("BODY_MAP", null),
                json(map(pin(1, "FALL", false, "").replace("2.5", "2.3"), false)), null, ctx(T1, DOC)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
        assertThatThrownBy(() -> values.normalize(field("BODY_MAP", null),
                json(map(pin(1, "FALL", false, "").replace("0.5", "1.5"), false)), null, ctx(T1, DOC)))
                .isInstanceOf(EmrValueRules.InvalidValue.class);
    }

    @Test
    void aPhaseTwoTextBodyMapBecomesAMapWithNoPinsAndKeepsTheText() {
        JsonNode upgraded = values.upgrade(field("BODY_MAP", null), json("{\"text\":\"Scar on knee\"}"));

        assertThat(upgraded.path("pins")).isEmpty();
        assertThat(upgraded.path("legacyText").asText()).isEqualTo("Scar on knee");
        Result saved = values.normalize(field("BODY_MAP", null), json(map("", true)), json("{\"text\":\"Scar\"}"),
                ctx(T1, DOC));
        assertThat(saved.value().path("legacyText").asText()).isEqualTo("Scar");
        assertThat(at(T1, DOC).membershipId()).isEqualTo(DOC);
    }
}
