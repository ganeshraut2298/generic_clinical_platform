package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-31 placing a medicine order on the real {@code sahaj_test} database (docs/api/ipd.yaml
 * {@code POST /admissions/{id}/medicine-orders}, ADR-009 D2, D4, D7): PLACED, BLOCKED and NEEDS_REASON, the reason and the
 * check token, the replay rules of the client key, not admitted, what is written (order, clinical event, audit, record
 * access) and what never is (the allergy text, the patient's name, a mobile number) in an event, an audit row, a stored
 * warning or a log line. The texts of the findings are copied from the contract and ADR-009, not read from the application.
 * Test plan: docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@WithPinPepper
class MedicineOrderFlowIT extends MedicineSupport {

    private static final String NONE_KNOWN = "None known";
    private static final String MARKER = "Zq7Xk";
    private static final String HEPARIN_ASPIRIN = "Bleeding risk: heparin with aspirin (Inj. Heparin 5,000 IU is on the chart).";
    private static final String CROSS = "Penicillin allergy: possible cross-reaction with cephalosporins. "
            + "Confirm what reaction the patient had.";
    private static final String NOT_RECORDED = "Allergies are not recorded. Ask the patient and record them before the first dose.";

    private String dup(String key) {
        String name = drugName(key);
        return name + " is already on the chart. Change the existing order instead of adding a second one.";
    }

    // ---------------- placed ----------------

    @Test
    void aCleanOrderIsPlacedWith201PendingTheDoctorsNameAndExactlyOneOrderOneEventAndOneAuditRow() throws Exception {
        World w = world(NONE_KNOWN);
        MedSnap before = medSnap(w.clinic(), w.adm());
        String key = newKey();
        JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(key, "pcm", "bd", null, null)), 201);
        assertThat(a.get("outcome").asText()).isEqualTo("PLACED");
        assertThat(a.get("replayed").asBoolean()).isFalse();
        assertThat(a.get("check").get("hardStops")).isEmpty();
        assertThat(a.get("check").get("warnings")).isEmpty();
        assertThat(a.get("check").get("allergyStatus").asText()).isEqualTo("NONE_KNOWN");
        assertThat(a.get("check").get("checkToken").asText()).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        JsonNode order = a.get("order");
        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        assertThat(order.get("drugKey").asText()).isEqualTo("pcm");
        assertThat(order.get("drugName").asText()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(order.get("route").asText()).isEqualTo("Oral");
        assertThat(order.get("frequencyKey").asText()).isEqualTo("bd");
        assertThat(order.get("frequencyLabel").asText()).isEqualTo("Twice daily");
        assertThat(order.get("orderedByName").asText()).isEqualTo(w.doctor().name());
        assertThat(order.get("orderedAt").isNull()).isFalse();
        assertThat(order.get("overrideReason").isNull()).isTrue();
        assertThat(order.get("verifiedByName").isNull()).isTrue();
        assertThat(order.get("canStop").asBoolean()).isTrue();
        assertThat(a.get("sheet").get("orders")).hasSize(1);

        OrderState s = state(w.clinic(), order.get("id").asText());
        assertThat(s.status()).isEqualTo("PENDING");
        assertThat(s.version()).isZero();
        assertThat(s.orderedBy()).isEqualTo(w.doctor().userId().toString());
        assertThat(s.overrideReason()).isEmpty();
        assertThat(s.warnings()).isEqualTo("{}");
        assertThat(s.hasVerifiedAt() || s.hasRejectedAt() || s.hasStoppedAt()).isFalse();
        assertThat(values(w.clinic(), "select client_key::text from ipd.medicine_order where id = ?::uuid",
                order.get("id").asText()).get(0)).isEqualTo(key);

        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.events()).isEqualTo(before.events() + 1);
        assertThat(after.audits()).isEqualTo(before.audits() + 1);
        assertThat(after.access()).as("a placed order writes no record-access row").isEqualTo(before.access());
        assertThat(eventLines(w.clinic(), w.adm().id()).get(0)).isEqualTo("order|Ordered Tab. Paracetamol 650 mg twice daily");
        List<String[]> audit = audit(w.clinic(), "IPD_MED_ORDERED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[0]).isEqualTo("IPD_MEDICINE_ORDER");
        assertThat(audit.get(0)[1]).isEqualTo(order.get("id").asText());
        assertThat(audit.get(0)[4]).isEqualTo(w.doctor().userId().toString());
        assertThat(audit.get(0)[3]).contains("\"drugKey\"").contains("pcm").contains("\"frequencyKey\"").contains("bd")
                .contains("PENDING").contains("\"warningCount\"").contains(w.adm().id())
                .doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile()).doesNotContain(NONE_KNOWN);
    }

    @Test
    void theOwnerMayOrderToo() throws Exception {
        World w = world(NONE_KNOWN);
        JsonNode a = place(w.owner().auth(), w.adm().id(), "pcm");
        assertThat(a.get("order").get("orderedByName").asText()).isEqualTo(w.owner().name());
        assertThat(state(w.clinic(), orderIdOf(a)).orderedBy()).isEqualTo(w.clinic().ownerUserId().toString());
    }

    @Test
    void everyFrequencyIsAcceptedWithItsLabelAndTheEventSaysItInLowerCase() throws Exception {
        World w = world(NONE_KNOWN);
        String[][] f = {{"od", "Once daily", "once daily"}, {"bd", "Twice daily", "twice daily"},
                {"tds", "Three times daily", "three times daily"}, {"q8", "8-hourly", "8-hourly"}};
        String[] drugs = {"pcm", "panto", "ondan", "ns"};
        for (int i = 0; i < f.length; i++) {
            JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                    js(orderBody(newKey(), drugs[i], f[i][0], null, null)), 201);
            assertThat(a.get("order").get("frequencyLabel").asText()).isEqualTo(f[i][1]);
            assertThat(eventLines(w.clinic(), w.adm().id()).get(0)).isEqualTo("order|Ordered " + drugName(drugs[i]) + " " + f[i][2]);
        }
        JsonNode sheet = emr(w.doctor().auth(), w.adm().id());
        List<String> labels = new ArrayList<>();
        sheet.get("frequencies").forEach(x -> labels.add(x.get("key").asText() + ":" + x.get("label").asText()));
        assertThat(labels).containsExactly("od:Once daily", "bd:Twice daily", "tds:Three times daily", "q8:8-hourly");
    }

    @Test
    void ordersAreListedInChartOrderOldestFirst() throws Exception {
        World w = world(NONE_KNOWN);
        List<String> ids = new ArrayList<>();
        for (String drug : new String[] {"met", "pcm", "ns", "panto"}) {
            ids.add(orderIdOf(place(w.doctor().auth(), w.adm().id(), drug)));
        }
        List<String> listed = new ArrayList<>();
        emr(w.doctor().auth(), w.adm().id()).get("orders").forEach(r -> listed.add(r.get("id").asText()));
        assertThat(listed).isEqualTo(ids);
    }

    // ---------------- blocked ----------------

    @Test
    void aDocumentedAllergyIsAHardStop200BlockedWithTheFullTextForTheDoctorAndNothingStoredAndNoAllergyTextInTheEvent()
            throws Exception {
        World w = world("Penicillin " + MARKER + " rash");
        String amox = drugName("amox");
        MedSnap before = medSnap(w.clinic(), w.adm());
        String key = newKey();
        JsonNode a;
        try (LogCapture logs = new LogCapture()) {
            a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(key, "amox", "bd", null, null)), 200);
            assertThat(logs.allText()).doesNotContain(MARKER).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile());
        }
        assertThat(a.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(a.get("replayed").asBoolean()).isFalse();
        assertThat(a.get("order").isNull()).isTrue();
        JsonNode check = a.get("check");
        assertThat(check.get("allergyStatus").asText()).isEqualTo("RECORDED");
        assertThat(check.get("hardStops")).hasSize(1);
        JsonNode stop = check.get("hardStops").get(0);
        assertThat(stop.get("code").asText()).isEqualTo("ALLERGY_PENICILLIN");
        assertThat(stop.get("severity").asText()).isEqualTo("HARD_STOP");
        assertThat(stop.get("otherDrugKey").isNull()).isTrue();
        assertThat(stop.get("text").asText()).isEqualTo("Documented allergy: Penicillin " + MARKER + " rash. " + amox
                + " is a penicillin.");
        assertThat(check.get("warnings")).isEmpty();
        assertThat(orderCount(w.clinic(), w.adm().id())).isZero();

        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.events()).isEqualTo(before.events() + 1);
        assertThat(after.audits()).isEqualTo(before.audits() + 1);
        assertThat(after.access()).as("a blocked order writes no record-access row").isEqualTo(before.access());
        String event = eventLines(w.clinic(), w.adm().id()).get(0);
        assertThat(event).isEqualTo("deny|Order blocked: " + amox + " · Documented penicillin allergy. " + amox
                + " is a penicillin.");
        assertThat(event).doesNotContain(MARKER).doesNotContain("rash").doesNotContain("Penicillin ");
        List<String[]> audit = audit(w.clinic(), "IPD_MED_ORDER_BLOCKED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)[0]).isEqualTo("IPD_ADMISSION");
        assertThat(audit.get(0)[1]).isEqualTo(w.adm().id());
        assertThat(audit.get(0)[3]).contains("BLOCKED").contains("amox").contains("bd").doesNotContain(MARKER)
                .doesNotContain("rash").doesNotContain(w.adm().name());
        assertThat(allAuditText(w.clinic())).doesNotContain(MARKER).doesNotContain("rash");

        // the same key is free again: nothing was stored under it, so once the allergy is corrected it places
        setAllergy(w.clinic(), w.adm(), "None known");
        JsonNode placed = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(key, "amox", "bd", null, null)), 201);
        assertThat(placed.get("outcome").asText()).isEqualTo("PLACED");
    }

    @Test
    void everyPenicillinAndSulfaSpellingBlocksItsDrugsAndNoOtherDrug() throws Exception {
        World w = world("None known");
        String[][] cases = {{"Penicillin", "amox", "ALLERGY_PENICILLIN"}, {"PENICILLIN allergy", "pip", "ALLERGY_PENICILLIN"},
                {"Allergic to amoxicillin", "amox", "ALLERGY_PENICILLIN"}, {"AMOXIC reaction", "pip", "ALLERGY_PENICILLIN"},
                {"Sulfa drugs", "cotri", "ALLERGY_SULFA"}, {"Sulpha rash", "cotri", "ALLERGY_SULFA"},
                {"  SULFA  ", "cotri", "ALLERGY_SULFA"}};
        for (String[] c : cases) {
            setAllergy(w.clinic(), w.adm(), c[0]);
            JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                    js(orderBody(newKey(), c[1], "od", null, null)), 200);
            assertThat(a.get("outcome").asText()).as(c[0]).isEqualTo("BLOCKED");
            assertThat(a.get("check").get("hardStops").get(0).get("code").asText()).as(c[0]).isEqualTo(c[2]);
            assertThat(a.get("check").get("allergyStatus").asText()).isEqualTo("RECORDED");
        }
        assertThat(orderCount(w.clinic(), w.adm().id())).isZero();
        // a penicillin allergy does not stop a sulfa drug, and the other way round
        setAllergy(w.clinic(), w.adm(), "Penicillin");
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(newKey(), "cotri", "od", null, null)), 201).get("outcome").asText()).isEqualTo("PLACED");
        setAllergy(w.clinic(), w.adm(), "Sulfa");
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(newKey(), "pip", "od", null, null)), 201).get("outcome").asText()).isEqualTo("PLACED");
    }

    @Test
    void theSameMedicineTwiceIsAHardStopDuplicateAndAfterStopOrRejectItCanBeOrderedAgain() throws Exception {
        World w = world(NONE_KNOWN);
        String first = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode blocked = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(newKey(), "pcm", "tds", null, null)), 200);
        assertThat(blocked.get("outcome").asText()).isEqualTo("BLOCKED");
        JsonNode stop = blocked.get("check").get("hardStops").get(0);
        assertThat(stop.get("code").asText()).isEqualTo("DUPLICATE");
        assertThat(stop.get("otherDrugKey").asText()).isEqualTo("pcm");
        assertThat(stop.get("text").asText()).isEqualTo(dup("pcm"));
        assertThat(orderCount(w.clinic(), w.adm().id())).isOne();
        assertThat(eventLines(w.clinic(), w.adm().id()).get(0)).isEqualTo("deny|Order blocked: " + drugName("pcm") + " · " + dup("pcm"));
        assertThat(medSnap(w.clinic(), w.adm()).audits()).isEqualTo(before.audits() + 1);

        // active orders count too
        verifyOk(w.pharmacist(), w.pin(), first);
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(newKey(), "pcm", "od", null, null)), 200).get("outcome").asText()).isEqualTo("BLOCKED");
        // stopped: free again
        stopOk(w.doctor().auth(), first);
        String second = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        // rejected: free again
        rejectOk(w.pharmacist().auth(), second, "WRONG_DOSE");
        place(w.doctor().auth(), w.adm().id(), "pcm");
        // the same medicine in another admission of the clinic is fine
        Adm other = admitWithAllergy(w.clinic(), NONE_KNOWN);
        place(w.doctor().auth(), other.id(), "pcm");
    }

    // ---------------- needs a reason ----------------

    @Test
    void warningsNeedAReasonAndTheCurrentTokenAndThenThereasonIsStoredWithTheWarningLinesAndAuditedWithoutText()
            throws Exception {
        World w = world(NONE_KNOWN);
        place(w.doctor().auth(), w.adm().id(), "hep");
        MedSnap before = medSnap(w.clinic(), w.adm());
        String key = newKey();
        JsonNode first = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(key, "asp", "od", null, null)), 200);
        assertThat(first.get("outcome").asText()).isEqualTo("NEEDS_REASON");
        assertThat(first.get("order").isNull()).isTrue();
        JsonNode warning = first.get("check").get("warnings").get(0);
        assertThat(warning.get("code").asText()).isEqualTo("INTERACTION");
        assertThat(warning.get("severity").asText()).isEqualTo("MAJOR");
        assertThat(warning.get("otherDrugKey").asText()).isEqualTo("hep");
        assertThat(warning.get("text").asText()).isEqualTo(HEPARIN_ASPIRIN);
        assertThat(first.get("check").get("hardStops")).isEmpty();
        String token = first.get("check").get("checkToken").asText();
        assertThat(token).hasSize(22);
        MedSnap mid = medSnap(w.clinic(), w.adm());
        assertThat(mid.orders()).isEqualTo(before.orders());
        assertThat(mid.events()).isEqualTo(before.events());
        assertThat(mid.audits()).isEqualTo(before.audits());
        assertThat(mid.access()).as("NEEDS_REASON writes one record-access row").isEqualTo(before.access() + 1);

        // a reason without the token, a token without a reason, a stale token: all ask again, nothing stored
        for (JsonNode again : new JsonNode[] {
                ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                        js(orderBody(key, "asp", "od", "SPECIALIST_ADVISED", null)), 200),
                ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(key, "asp", "od", null, token)), 200),
                ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                        js(orderBody(key, "asp", "od", "SPECIALIST_ADVISED", "AAAAAAAAAAAAAAAAAAAAAA")), 200)}) {
            assertThat(again.get("outcome").asText()).isEqualTo("NEEDS_REASON");
            assertThat(again.get("check").get("checkToken").asText()).isEqualTo(token);
        }
        assertThat(orderCount(w.clinic(), w.adm().id())).isOne();

        JsonNode placed = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(key, "asp", "od", "SPECIALIST_ADVISED", token)), 201);
        assertThat(placed.get("outcome").asText()).isEqualTo("PLACED");
        assertThat(placed.get("order").get("overrideReason").get("key").asText()).isEqualTo("SPECIALIST_ADVISED");
        assertThat(placed.get("order").get("overrideReason").get("label").asText())
                .isEqualTo("Specialist advised this combination");
        OrderState s = state(w.clinic(), orderIdOf(placed));
        assertThat(s.overrideReason()).isEqualTo("SPECIALIST_ADVISED");
        assertThat(s.warnings()).contains("Major: " + HEPARIN_ASPIRIN);
        assertThat(eventLines(w.clinic(), w.adm().id()).get(0))
                .isEqualTo("order|Ordered Tab. Aspirin 75 mg once daily · override: Specialist advised this combination");
        List<String[]> audit = audit(w.clinic(), "IPD_MED_ORDERED");
        String[] last = audit.get(audit.size() - 1);
        assertThat(last[3]).contains("SPECIALIST_ADVISED").contains("\"warningCount\"").contains("1")
                .doesNotContain("Bleeding").doesNotContain("heparin");
    }

    @Test
    void everyOverrideReasonIsAccepted() throws Exception {
        World w = world(NONE_KNOWN);
        place(w.doctor().auth(), w.adm().id(), "hep");
        for (String[] reason : OVERRIDE_REASONS) {
            // hep stays; ibu is stopped each round so it can be ordered again
            JsonNode placed = placeWithReason(w.doctor().auth(), w.adm().id(), "ibu", reason[0]);
            assertThat(placed.get("order").get("overrideReason").get("label").asText()).isEqualTo(reason[1]);
            stopOk(w.doctor().auth(), orderIdOf(placed));
        }
        JsonNode sheet = emr(w.doctor().auth(), w.adm().id());
        List<String> keys = new ArrayList<>();
        sheet.get("overrideReasons").forEach(r -> keys.add(r.get("key").asText() + ":" + r.get("label").asText()));
        assertThat(keys).containsExactly("BENEFIT_MONITORED:Benefit outweighs the risk, monitoring in place",
                "SPECIALIST_ADVISED:Specialist advised this combination",
                "ALLERGY_HISTORY_CHECKED:Allergy history checked with the patient: mild rash only, no anaphylaxis",
                "CARDIOLOGY_STENT:Recent coronary stent, cardiology advised");
    }

    @Test
    void whenAnotherDoctorAddsAMedicineMeanwhileTheOldTokenIsRefusedAndTheNewWarningsAreShown() throws Exception {
        World w = world(NONE_KNOWN);
        place(w.doctor().auth(), w.adm().id(), "hep");
        String key = newKey();
        JsonNode seen = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(key, "asp", "od", null, null)), 200);
        String oldToken = seen.get("check").get("checkToken").asText();
        assertThat(seen.get("check").get("warnings")).hasSize(1);
        // the owner adds ibuprofen (a Major warning with heparin, handled with a reason)
        placeWithReason(w.owner().auth(), w.adm().id(), "ibu", "BENEFIT_MONITORED");
        JsonNode now = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(key, "asp", "od", "SPECIALIST_ADVISED", oldToken)), 200);
        assertThat(now.get("outcome").asText()).isEqualTo("NEEDS_REASON");
        assertThat(now.get("check").get("warnings")).hasSize(2);
        assertThat(now.get("check").get("checkToken").asText()).isNotEqualTo(oldToken);
        assertThat(finding(now.get("check"), "warnings", "INTERACTION")).isNotNull();
        assertThat(orderCount(w.clinic(), w.adm().id())).isEqualTo(2);
        JsonNode placed = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(key, "asp", "od", "SPECIALIST_ADVISED", now.get("check").get("checkToken").asText())), 201);
        assertThat(state(w.clinic(), orderIdOf(placed)).warnings()).contains("Major: Bleeding risk: heparin with aspirin")
                .contains("Moderate: Stomach bleeding risk: aspirin with an NSAID");
    }

    @Test
    void aReasonSentWithNoFindingIsIgnoredAndNotStored() throws Exception {
        World w = world(NONE_KNOWN);
        JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(newKey(), "pcm", "od", "CARDIOLOGY_STENT", null)), 201);
        assertThat(a.get("order").get("overrideReason").isNull()).isTrue();
        assertThat(state(w.clinic(), orderIdOf(a)).overrideReason()).isEmpty();
        assertThat(state(w.clinic(), orderIdOf(a)).warnings()).isEqualTo("{}");
        assertThat(eventLines(w.clinic(), w.adm().id()).get(0)).isEqualTo("order|Ordered Tab. Paracetamol 650 mg once daily");
    }

    @Test
    void anUnrecordedAllergyIsAModerateWarningAndTheChipSaysAllergiesNotRecorded() throws Exception {
        for (String allergy : new String[] {null, "   ", "Not recorded", "allergies NOT RECORDED"}) {
            World w = world(allergy);
            JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                    js(orderBody(newKey(), "pcm", "od", null, null)), 200);
            assertThat(a.get("outcome").asText()).as(String.valueOf(allergy)).isEqualTo("NEEDS_REASON");
            assertThat(a.get("check").get("allergyStatus").asText()).isEqualTo("NOT_RECORDED");
            JsonNode warning = a.get("check").get("warnings").get(0);
            assertThat(warning.get("code").asText()).isEqualTo("ALLERGIES_NOT_RECORDED");
            assertThat(warning.get("severity").asText()).isEqualTo("MODERATE");
            assertThat(warning.get("text").asText()).isEqualTo(NOT_RECORDED);
            assertThat(emr(w.doctor().auth(), w.adm().id()).get("patient").get("allergyStatus").asText()).isEqualTo("NOT_RECORDED");
            if (allergy == null) {
                JsonNode placed = placeWithReason(w.doctor().auth(), w.adm().id(), "pcm", "BENEFIT_MONITORED");
                assertThat(state(w.clinic(), orderIdOf(placed)).warnings()).contains("Moderate: " + NOT_RECORDED);
            }
        }
    }

    @Test
    void aPenicillinAllergyMakesACephalosporinAModerateCrossReactionWarningNotAHardStop() throws Exception {
        World w = world("Penicillin");
        JsonNode a = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "ceft", "od", null, null)), 200);
        assertThat(a.get("outcome").asText()).isEqualTo("NEEDS_REASON");
        assertThat(a.get("check").get("hardStops")).isEmpty();
        JsonNode warning = a.get("check").get("warnings").get(0);
        assertThat(warning.get("code").asText()).isEqualTo("ALLERGY_CROSS_CEPHALOSPORIN");
        assertThat(warning.get("severity").asText()).isEqualTo("MODERATE");
        assertThat(warning.get("text").asText()).isEqualTo(CROSS);
        JsonNode placed = placeWithReason(w.doctor().auth(), w.adm().id(), "ceft", "ALLERGY_HISTORY_CHECKED");
        assertThat(state(w.clinic(), orderIdOf(placed)).warnings()).contains("Moderate: " + CROSS);
    }

    @Test
    void aHardStopAndWarningsTogetherAreBlockedAndTheThreeInteractionsWorkInBothDirections() throws Exception {
        World w = world(NONE_KNOWN);
        // aspirin first, then heparin: the same warning in the other direction
        place(w.doctor().auth(), w.adm().id(), "asp");
        JsonNode hep = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "hep", "od", null, null)), 200);
        assertThat(finding(hep.get("check"), "warnings", "INTERACTION").get("text").asText())
                .isEqualTo("Bleeding risk: heparin with aspirin (Tab. Aspirin 75 mg is on the chart).");
        JsonNode ibu = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "ibu", "od", null, null)), 200);
        JsonNode moderate = finding(ibu.get("check"), "warnings", "INTERACTION");
        assertThat(moderate.get("severity").asText()).isEqualTo("MODERATE");
        assertThat(moderate.get("text").asText()).isEqualTo("Stomach bleeding risk: aspirin with an NSAID (Tab. Aspirin 75 mg is on the chart).");
        // heparin with an NSAID, in the other direction too
        World v = world(NONE_KNOWN);
        place(v.doctor().auth(), v.adm().id(), "ibu");
        JsonNode h = ok(HttpMethod.POST, ordersUrl(v.adm().id()), v.doctor().auth(), js(orderBody(newKey(), "hep", "od", null, null)), 200);
        assertThat(finding(h.get("check"), "warnings", "INTERACTION").get("text").asText())
                .isEqualTo("Bleeding risk: heparin with an NSAID (Tab. Ibuprofen 400 mg is on the chart).");
        // a hard stop wins over a warning: duplicate and an interaction together
        World x = world("Penicillin");
        place(x.doctor().auth(), x.adm().id(), "hep");
        placeWithReason(x.doctor().auth(), x.adm().id(), "asp", "SPECIALIST_ADVISED");
        JsonNode dupl = ok(HttpMethod.POST, ordersUrl(x.adm().id()), x.doctor().auth(), js(orderBody(newKey(), "asp", "od", null, null)), 200);
        assertThat(dupl.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(dupl.get("check").get("hardStops")).hasSize(1);
        assertThat(dupl.get("check").get("warnings")).isNotEmpty();
    }

    // ---------------- replay ----------------

    @Test
    void aRepeatOfAPlacedOrderIs200ReplayedWithTheSameOrderAndWritesOnlyOneRecordAccessRow() throws Exception {
        World w = world(NONE_KNOWN);
        String key = newKey();
        JsonNode first = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(key, "pcm", "od", null, null)), 201);
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode again = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(key, "pcm", "od", null, null)), 200);
        assertThat(again.get("outcome").asText()).isEqualTo("PLACED");
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(orderIdOf(again)).isEqualTo(orderIdOf(first));
        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.orders()).isEqualTo(before.orders());
        assertThat(after.events()).isEqualTo(before.events());
        assertThat(after.audits()).isEqualTo(before.audits());
        assertThat(after.access()).isEqualTo(before.access() + 1);
        // a reason sent with the retry of a request that stored none does not make it different
        JsonNode withReason = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(key, "pcm", "od", "CARDIOLOGY_STENT", null)), 200);
        assertThat(withReason.get("replayed").asBoolean()).isTrue();
        // another doctor retrying the same key gets the same order too
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.owner().auth(), js(orderBody(key, "pcm", "od", null, null)), 200)
                .get("replayed").asBoolean()).isTrue();
        assertThat(orderCount(w.clinic(), w.adm().id())).isOne();
    }

    @Test
    void aRepeatWithADifferentMedicineFrequencyOrReasonIs409ClientKeyReusedAndWritesNothing() throws Exception {
        World w = world(NONE_KNOWN);
        place(w.doctor().auth(), w.adm().id(), "hep");
        String key = newKey();
        JsonNode first = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(key, "asp", "od", null, null)), 200);
        ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(key, "asp", "od", "SPECIALIST_ADVISED", first.get("check").get("checkToken").asText())), 201);
        MedSnap before = medSnap(w.clinic(), w.adm());
        for (var body : List.of(orderBody(key, "ibu", "od", "SPECIALIST_ADVISED", null),
                orderBody(key, "asp", "bd", "SPECIALIST_ADVISED", null),
                orderBody(key, "asp", "od", "BENEFIT_MONITORED", null),
                orderBody(key, "asp", "od", null, null))) {
            JsonNode p = problem(orderCall(w.doctor().auth(), w.adm().id(), body), 409);
            assertType(p, "client-key-reused");
            assertThat(p.get("detail").asText()).isEqualTo(CLIENT_KEY_REUSED);
        }
        // the identical retry is still a replay
        JsonNode same = ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                js(orderBody(key, "asp", "od", "SPECIALIST_ADVISED", null)), 200);
        assertThat(same.get("replayed").asBoolean()).isTrue();
        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.orders()).isEqualTo(before.orders());
        assertThat(after.events()).isEqualTo(before.events());
        assertThat(after.audits()).isEqualTo(before.audits());
    }

    @Test
    void aClientKeyOfAnotherAdmissionIs400OnTheClientKeyField() throws Exception {
        World w = world(NONE_KNOWN);
        String key = newKey();
        ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(key, "pcm", "od", null, null)), 201);
        Adm other = admitWithAllergy(w.clinic(), NONE_KNOWN);
        MedSnap before = medSnap(w.clinic(), other);
        JsonNode p = problem(orderCall(w.doctor().auth(), other.id(), orderBody(key, "pcm", "od", null, null)), 400);
        assertType(p, "validation");
        assertThat(fieldNames(p)).containsExactly("clientKey");
        assertThat(fieldMessage(p, "clientKey")).isEqualTo(RELOAD_TEXT);
        assertThat(medSnap(w.clinic(), other)).isEqualTo(before);
        assertThat(orderCount(w.clinic(), other.id())).isZero();
    }

    // ---------------- not admitted ----------------

    @Test
    void anAdmissionThatIsNotAdmittedAnyMoreRefusesAnOrderWith409NotAdmittedButAnEarlierOrderStillReplays() throws Exception {
        World w = world(NONE_KNOWN);
        String key = newKey();
        ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(key, "pcm", "od", null, null)), 201);
        discharge(w.clinic(), w.adm().id());
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode p = problem(orderCall(w.doctor().auth(), w.adm().id(), "panto"), 409);
        assertType(p, "not-admitted");
        assertThat(p.get("detail").asText()).isEqualTo(NOT_ADMITTED_TEXT);
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(before);
        // the replay is answered before the admission state (contract order)
        assertThat(ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(key, "pcm", "od", null, null)), 200)
                .get("replayed").asBoolean()).isTrue();
        JsonNode sheet = emr(w.doctor().auth(), w.adm().id());
        assertThat(sheet.get("status").asText()).isEqualTo("DISCHARGED");
        assertThat(sheet.get("canOrder").asBoolean()).isFalse();
        assertThat(sheet.get("orderLockReason").asText()).isEqualTo("NOT_ADMITTED");
        assertThat(sheet.get("orders").get(0).get("canStop").asBoolean()).isFalse();
        assertThat(sheet.get("orders").get(0).get("status").asText()).as("a discharged admission keeps its order status")
                .isEqualTo("PENDING");
    }

    // ---------------- privacy ----------------

    @Test
    void theAllergyTextTheNameAndTheMobileAreNeverInAnEventAnAuditRowAStoredWarningOrALogLineOfTheWholeFlow() throws Exception {
        World w = world("Penicillin " + MARKER + " rash");
        List<String> logTexts = new ArrayList<>();
        try (LogCapture logs = new LogCapture()) {
            ok(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), js(orderBody(newKey(), "amox", "od", null, null)), 200);
            placeWithReason(w.doctor().auth(), w.adm().id(), "ceft", "ALLERGY_HISTORY_CHECKED");
            place(w.doctor().auth(), w.adm().id(), "pcm");
            emr(w.doctor().auth(), w.adm().id());
            String ceft = values(w.clinic(), "select id::text from ipd.medicine_order where drug_key = 'ceft' and admission_id = ?::uuid",
                    w.adm().id()).get(0);
            verifyOk(w.pharmacist(), w.pin(), ceft);
            queue(w.owner().auth());
            summary(w.owner().auth());
            stopOk(w.doctor().auth(), ceft);
            logTexts.add(logs.allText());
        }
        for (String text : logTexts) {
            assertThat(text).doesNotContain(MARKER).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile());
        }
        assertThat(String.join("\n", eventLines(w.clinic(), w.adm().id()))).doesNotContain(MARKER).doesNotContain(w.adm().name())
                .doesNotContain(w.adm().mobile());
        // since review S2 the patient rows hold flags only, so the WHOLE audit trail of the clinic is checked, not only the rows of this story
        assertThat(allAuditText(w.clinic())).doesNotContain(MARKER).doesNotContain(w.adm().name()).doesNotContain(w.adm().mobile());
        assertThat(values(w.clinic(), "select coalesce(string_agg(warnings::text, ','), '') from ipd.medicine_order where admission_id = ?::uuid",
                w.adm().id()).get(0)).doesNotContain(MARKER);
    }

    // ---------------- the EMR sheet ----------------

    @Test
    void theEmrSheetHasTheIdentityBarTheCatalogueInMockupOrderAndWritesOneViewEventAndOneAccessRowPerOpening() throws Exception {
        World w = world("Penicillin (rash)");
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode sheet = emr(w.doctor().auth(), w.adm().id());
        assertThat(sheet.get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(sheet.get("status").asText()).isEqualTo("ADMITTED");
        assertThat(sheet.get("state").asText()).isEqualTo("trial");
        assertThat(sheet.get("asOf").isNull()).isFalse();
        JsonNode patient = sheet.get("patient");
        assertThat(patient.get("patientId").asText()).isEqualTo(w.adm().patientId().toString());
        assertThat(patient.get("name").asText()).isEqualTo(w.adm().name());
        assertThat(patient.get("age").asInt()).isEqualTo(56);
        assertThat(patient.get("sex").asText()).isEqualTo("F");
        assertThat(patient.get("bedCode").asText()).isEqualTo(w.adm().bedCode());
        assertThat(patient.get("allergyText").asText()).isEqualTo("Penicillin (rash)");
        assertThat(patient.get("allergyStatus").asText()).isEqualTo("RECORDED");
        assertThat(patient.get("uhid").asText()).isNotBlank();
        assertThat(sheet.get("diagnosis").asText()).isEqualTo("Fever");
        assertThat(sheet.get("dayNumber").asInt()).isOne();
        assertThat(sheet.get("admittingDoctorName").asText()).isNotBlank();
        assertThat(sheet.get("canOrder").asBoolean()).isTrue();
        assertThat(sheet.get("orderLockReason").isNull()).isTrue();
        assertThat(sheet.get("orders")).isEmpty();
        JsonNode drugs = sheet.get("drugs");
        assertThat(drugs).hasSize(14);
        for (int i = 0; i < DRUGS.length; i++) {
            assertThat(drugs.get(i).get("key").asText()).isEqualTo(DRUGS[i][0]);
            assertThat(drugs.get(i).get("name").asText()).isEqualTo(DRUGS[i][1]);
            assertThat(drugs.get(i).get("route").asText()).isEqualTo(DRUGS[i][2]);
            assertThat(drugs.get(i).get("highAlert").isNull() ? null : drugs.get(i).get("highAlert").asText()).isEqualTo(DRUGS[i][3]);
        }
        MedSnap after = medSnap(w.clinic(), w.adm());
        assertThat(after.events()).isEqualTo(before.events() + 1);
        assertThat(after.access()).isEqualTo(before.access() + 1);
        assertThat(after.audits()).as("opening the record writes no audit row").isEqualTo(before.audits());
        assertThat(eventLines(w.clinic(), w.adm().id()).get(0)).isEqualTo("view|Opened the clinical record");
        emr(w.doctor().auth(), w.adm().id());
        assertThat(medSnap(w.clinic(), w.adm()).events()).isEqualTo(before.events() + 2);
        assertThat(medSnap(w.clinic(), w.adm()).access()).isEqualTo(before.access() + 2);
        // the order and stop answers return the sheet without a new view event
        MedSnap beforeOrder = medSnap(w.clinic(), w.adm());
        place(w.doctor().auth(), w.adm().id(), "pcm");
        assertThat(medSnap(w.clinic(), w.adm()).events()).isEqualTo(beforeOrder.events() + 1); // only the order event
    }

    @Test
    void theNurseSeesTheOrdersButCannotOrderOrStopAndTheChipFollowsTheAllergyText() throws Exception {
        World w = world(NONE_KNOWN);
        String id = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        JsonNode sheet = emr(w.nurse().auth(), w.adm().id());
        assertThat(sheet.get("canOrder").asBoolean()).isFalse();
        assertThat(sheet.get("orderLockReason").asText()).isEqualTo("ROLE");
        assertThat(sheetRow(sheet, id).get("canStop").asBoolean()).isFalse();
        assertThat(sheetRow(sheet, id).get("orderedByName").asText()).isEqualTo(w.doctor().name());
        assertThat(emr(w.doctor().auth(), w.adm().id()).get("canOrder").asBoolean()).isTrue();
        String[][] chips = {{"None known", "NONE_KNOWN"}, {"Penicillin; none known otherwise", "RECORDED"},
                {"Latex", "RECORDED"}, {"Sulfa", "RECORDED"}, {"not recorded", "NOT_RECORDED"}, {"  ", "NOT_RECORDED"}};
        for (String[] chip : chips) {
            setAllergy(w.clinic(), w.adm(), chip[0]);
            assertThat(emr(w.nurse().auth(), w.adm().id()).get("patient").get("allergyStatus").asText()).as(chip[0]).isEqualTo(chip[1]);
        }
    }
}
