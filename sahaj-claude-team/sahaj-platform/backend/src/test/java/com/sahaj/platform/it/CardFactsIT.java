package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-37 the Admitted list answer for the patient card ({@code GET /api/v1/ipd/admissions}, ADR-015 D4, D6, D7;
 * docs/stories/SAH-37.md AC33, AC34, AC54 to AC61, AC65): the published setup travels in the answer, the default answer
 * is the SAH-36 one plus the setup (DEC-027), a new fact is sent only to the roles that may see its group and only when
 * the published setup shows it, and an absent fact is absent from the raw JSON, not null. Also {@code cardFacts=all}
 * (owner only), the tile marks at the band edges of the SAH-30 score (OQ-37-11), the Deposit ability and the role read
 * again from the membership. Test plan: docs/test-plans/SAH-37.md.
 */
@OnTestDatabase
@WithPinPepper
class CardFactsIT extends CardSetupSupport {

    private void publishWith(Clinic clinic, Consumer<ObjectNode> change) throws Exception {
        publishNow(clinic, edit(standard(clinic.owner()), change));
    }

    private static final Consumer<ObjectNode> ALL_FACTS_ON = s -> {
        for (String key : new String[] {"diagnosis", "bp", "pulse", "spo2", "temp", "rr", "lastRecorded", "mobile"}) {
            fieldOn(s, key, true);
        }
    };

    private String raw(RequestPostProcessor auth, String path) throws Exception {
        return call(HttpMethod.GET, path, auth, null).andReturn().getResponse().getContentAsString();
    }

    // ---------------- DEC-027, AC54, AC57: the default answer ----------------

    @Test
    void theDefaultAnswerIsTheSah36FactsPlusTheCardSetupAndNoDiagnosisVitalsOrMobileForAnyRole() throws Exception {
        Ward w = ward();
        save(w.clinic(), w.adm().id(), medium5());
        Staff frontDesk = staff(w.clinic(), "FRONT_DESK");
        Object[][] roles = {{"OWNER", w.clinic().owner()}, {"DOCTOR", w.doctor().auth()}, {"NURSE", w.nurse().auth()},
            {"FRONT_DESK", frontDesk.auth()}};
        for (Object[] who : roles) {
            String role = (String) who[0];
            String text = raw((RequestPostProcessor) who[1], ADMISSIONS);
            JsonNode l = objectMapper.readTree(text);
            for (String key : new String[] {"\"diagnosis\"", "\"latestVitals\"", "\"mobile\"", "\"recordedByName\""}) {
                assertThat(text).as(role + " default answer holds no " + key).doesNotContain(key);
            }
            JsonNode card = l.get("cardSetup");
            assertThat(card.get("version").asInt()).isEqualTo(1);
            assertThat(card.get("isStandard").asBoolean()).isTrue();
            assertThat(card.get("fallback").asBoolean()).isFalse();
            assertThat(card.get("layout").asText()).isEqualTo("standard");
            assertThat(card.get("cardsPerRow").asInt()).isEqualTo(3);
            List<String> sections = new ArrayList<>();
            card.get("sections").forEach(s -> sections.add(s.get("key").asText()));
            assertThat(sections).as(role + " billing is off").containsExactly("header", "status", "stay", "care", "vitals",
                    "clinical");
            assertThat(resolvedFields(card, "header")).containsExactly("name", "uhid", "ageSex", "bed");
            assertThat(resolvedFields(card, "status")).containsExactly("alerts");
            assertThat(resolvedFields(card, "stay")).containsExactly("admittedOn", "lengthOfStay", "ward", "room");
            assertThat(resolvedFields(card, "care")).containsExactly("doctor");
            assertThat(resolvedFields(card, "vitals")).containsExactly("ews");
            assertThat(resolvedFields(card, "clinical")).containsExactly("allergies");
            assertThat(texts(card.get("actions"))).containsExactly("nursing", "emr", "transfer", "discharge");
            for (JsonNode sec : card.get("sections")) {
                assertThat(sec.get("name").asText()).isNotBlank();
                for (JsonNode f : sec.get("fields")) {
                    assertThat(f.get("label").asText()).isNotBlank();
                    assertThat(f.get("group").asText()).isIn("patient", "contact", "clinical", "vitals", "billing");
                }
            }
            JsonNode row = rowOf(l, w.adm().id());
            assertThat(row.has("uhid") && row.has("runningBillPaise") && row.has("wardId") && row.has("roomName")).isTrue();
            if (!"FRONT_DESK".equals(role)) {
                assertThat(keys(row.get("clinical"))).as(role + " clinical keys are the SAH-36 ones")
                        .containsExactlyInAnyOrder("escalation", "latestEws", "vitalsDue", "allergy");
            }
        }
    }

    /**
     * The raw answer without {@code cardSetup}: the published setup names the fields that are switched on (setup metadata
     * the same for every role, not patient data), so a key such as "diagnosis" appears there as a value. The checks of what
     * a role must not receive run on everything else.
     */
    private String rawNoSetup(RequestPostProcessor auth) throws Exception {
        ObjectNode tree = (ObjectNode) objectMapper.readTree(raw(auth, ADMISSIONS));
        tree.remove("cardSetup");
        return tree.toString();
    }

    private static JsonNode rowOf(JsonNode list, String admissionId) {
        for (JsonNode r : list.get("items")) {
            if (admissionId.equals(r.get("id").asText())) {
                return r;
            }
        }
        throw new AssertionError("no row " + admissionId);
    }

    private static List<String> resolvedFields(JsonNode card, String sectionKey) {
        for (JsonNode sec : card.get("sections")) {
            if (sectionKey.equals(sec.get("key").asText())) {
                return texts(sec.get("fields").findValues("key"));
            }
        }
        throw new AssertionError("section " + sectionKey + " is not drawn");
    }

    @Test
    void theCardAbilitiesAndTheVisibleGroupsFollowTheRoleAndDepositFollowsReadOnly() throws Exception {
        Ward w = ward();
        Staff frontDesk = staff(w.clinic(), "FRONT_DESK");
        Object[][] expected = {
            // role, auth, deposit, depositLowFlag, groups
            {"OWNER", w.clinic().owner(), true, true, List.of("patient", "contact", "clinical", "vitals", "billing")},
            {"DOCTOR", w.doctor().auth(), false, false, List.of("patient", "clinical", "vitals")},
            {"NURSE", w.nurse().auth(), false, false, List.of("patient", "clinical", "vitals")},
            {"FRONT_DESK", frontDesk.auth(), true, true, List.of("patient", "contact", "billing")}};
        for (Object[] e : expected) {
            JsonNode card = listOf((RequestPostProcessor) e[1]).get("card");
            assertThat(card.get("deposit").asBoolean()).as(e[0] + " deposit").isEqualTo(e[2]);
            assertThat(card.get("depositLowFlag").asBoolean()).as(e[0] + " depositLowFlag").isEqualTo(e[3]);
            assertThat(texts(card.get("visibleGroups"))).as(e[0] + " groups").containsExactlyElementsOf((List<String>) e[4]);
        }
        expireTrial(w.clinic());
        for (Object[] e : expected) {
            JsonNode card = listOf((RequestPostProcessor) e[1]).get("card");
            assertThat(card.get("deposit").asBoolean()).as(e[0] + " read-only deposit").isFalse();
            assertThat(texts(card.get("visibleGroups"))).as(e[0] + " groups do not change in Read-only")
                    .containsExactlyElementsOf((List<String>) e[4]);
        }
    }

    // ---------------- AC33, AC56: a fact switched on reaches only the roles that may see its group ----------------

    @Test
    void factsSwitchedOnAreSentToTheRolesThatMaySeeThemAndAbsentFromTheRawJsonOfTheOthers() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        save(clinic, w.adm().id(), medium5());
        publishWith(clinic, ALL_FACTS_ON);
        Staff frontDesk = staff(clinic, "FRONT_DESK");
        String ownerName = nameOf(clinic, clinic.ownerUserId());
        String mobile = w.adm().mobile();

        for (Object[] who : new Object[][] {{"OWNER", clinic.owner()}, {"DOCTOR", w.doctor().auth()}, {"NURSE", w.nurse().auth()}}) {
            String text = rawNoSetup((RequestPostProcessor) who[1]);
            JsonNode row = rowOf(objectMapper.readTree(text), w.adm().id());
            JsonNode clinical = row.get("clinical");
            assertThat(clinical.get("diagnosis").asText()).as(who[0] + " diagnosis").isEqualTo("Fever");
            JsonNode tiles = clinical.get("latestVitals");
            assertThat(tiles.get("bp").get("systolic").asInt()).isEqualTo(120);
            assertThat(tiles.get("bp").get("diastolic").asInt()).isEqualTo(80);
            assertThat(tiles.get("pulse").get("value").asInt()).isEqualTo(95);
            assertThat(tiles.get("spo2").get("value").asInt()).isEqualTo(93);
            assertThat(tiles.get("temp").get("value").asDouble()).isEqualTo(37.0);
            assertThat(tiles.get("rr").get("value").asInt()).isEqualTo(22);
            assertThat(instant(tiles.get("recordedAt"))).isNotNull();
            assertThat(tiles.get("recordedByName").asText()).isEqualTo(ownerName);
            assertThat(keys(clinical)).containsExactlyInAnyOrder("escalation", "latestEws", "vitalsDue", "allergy", "diagnosis",
                    "latestVitals");
            if ("OWNER".equals(who[0])) {
                assertThat(row.has("mobile")).as("owner: the mobile property is sent when Mobile is on").isTrue();
            } else {
                assertThat(row.has("mobile")).as(who[0] + " never gets mobile").isFalse();
                assertThat(text).as(who[0] + " raw JSON holds no mobile number").doesNotContain(mobile).doesNotContain("\"mobile\"");
            }
        }
        String fd = rawNoSetup(frontDesk.auth());
        JsonNode fdRow = rowOf(objectMapper.readTree(fd), w.adm().id());
        assertThat(fdRow.has("mobile")).as("front desk: the mobile property is sent when Mobile is on").isTrue();
        for (String key : new String[] {"\"diagnosis\"", "\"latestVitals\"", "\"clinical\":", "\"recordedByName\"", "\"latestEws\"",
            "\"allergy\"", "Fever"}) {
            assertThat(fd).as("front desk raw JSON must not hold " + key).doesNotContain(key);
        }
        assertThat(fdRow.has("clinical")).isFalse();
        // the groups a role may see decide what the card draws, whatever the setup shows
        assertThat(texts(objectMapper.readTree(fd).get("card").get("visibleGroups"))).doesNotContain("clinical", "vitals");
        // the role is read again: a nurse moved to the front desk loses the clinical facts at once
        Staff moved = staff(clinic, "NURSE");
        assertThat(rowOf(listOf(moved.auth()), w.adm().id()).get("clinical").has("diagnosis")).isTrue();
        setMembershipRole(clinic, moved.userId(), "FRONT_DESK");
        String after = rawNoSetup(moved.auth());
        JsonNode movedRow = rowOf(objectMapper.readTree(after), w.adm().id());
        assertThat(movedRow.has("clinical")).isFalse();
        assertThat(after).doesNotContain("\"diagnosis\"").doesNotContain("\"latestVitals\"");
        // the token still says NURSE and a fact of the contact group needs the token role and the membership role to agree
        assertThat(movedRow.has("mobile")).as("no mobile until the person signs in again as front desk").isFalse();
        // a role with no list at all
        for (String role : NO_CARD_ROLES) {
            assertType(problem(call(HttpMethod.GET, ADMISSIONS, staff(clinic, role).auth(), null), 403), "forbidden");
        }
    }

    /**
     * DEFECT WITNESS (found by this test): the Mobile field is switched on and the property is sent, but its value is
     * null for OWNER and for FRONT_DESK. {@code PatientLookup.findAllCurrent} builds every {@code PatientSummary} with a
     * null mobile (ADR-013 D15: "no mobile number"), while {@code AdmittedCards.patients} copies the mobile from that
     * summary (ADR-015 D6). Owner: java-developer (patient module). The test stays red until a mobile is returned.
     */
    @Test
    void theMobileValueIsTheNumberOfThePatientForOwnerAndFrontDeskWhenMobileIsOnAndOfferedToOwnerInThePreview() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        String mobile = w.adm().mobile();
        publishWith(clinic, s -> fieldOn(s, "mobile", true));
        Staff frontDesk = staff(clinic, "FRONT_DESK");
        assertThat(rowOf(listOf(clinic.owner()), w.adm().id()).path("mobile").asText("")).as("owner").isEqualTo(mobile);
        assertThat(rowOf(listOf(frontDesk.auth()), w.adm().id()).path("mobile").asText("")).as("front desk").isEqualTo(mobile);
        assertThat(rowOf(listAll(clinic.owner()), w.adm().id()).path("mobile").asText("")).as("owner preview").isEqualTo(mobile);
    }

    @Test
    void aBlankReasonShowsAsUnderEvaluationAndAPatientWithNoVitalsHasNullTilesAndNoScore() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        JsonNode blank = admitWithReason(clinic, "");
        JsonNode detail = admitWithReason(clinic, "Chest pain on exertion");
        publishWith(clinic, ALL_FACTS_ON);
        JsonNode l = listOf(w.nurse().auth());
        JsonNode blankClinical = rowOf(l, blank.get("id").asText()).get("clinical");
        assertThat(blankClinical.get("diagnosis").asText()).isEqualTo("Under evaluation");
        assertThat(rowOf(l, detail.get("id").asText()).get("clinical").get("diagnosis").asText()).isEqualTo("Chest pain on exertion");
        // vitals fields on, nothing recorded: the key is there with null, so the card can say "No vitals recorded yet"
        assertThat(blankClinical.has("latestVitals")).isTrue();
        assertThat(blankClinical.get("latestVitals").isNull()).isTrue();
        assertThat(blankClinical.get("latestEws").isNull()).isTrue();
        assertThat(blankClinical.get("vitalsDue").asBoolean()).isTrue();
    }

    @Test
    void aFactIsAbsentWhenItsFieldIsOffOrItsSectionIsOffAndOnlyTheShownReadingsAreSent() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        save(clinic, w.adm().id(), medium5());
        // only SpO2: no other reading, no recorder name, no diagnosis, no mobile
        publishWith(clinic, s -> fieldOn(s, "spo2", true));
        JsonNode tiles = rowOf(listOf(w.nurse().auth()), w.adm().id()).get("clinical").get("latestVitals");
        assertThat(keys(tiles)).containsExactlyInAnyOrder("recordedAt", "spo2");
        assertThat(tiles.get("spo2").get("value").asInt()).isEqualTo(93);
        String ownerText = raw(clinic.owner(), ADMISSIONS);
        assertThat(ownerText).doesNotContain("\"diagnosis\"").doesNotContain("\"mobile\"").doesNotContain("\"recordedByName\"");
        // pulse and Last recorded only
        publishWith(clinic, s -> {
            fieldOn(s, "pulse", true);
            fieldOn(s, "lastRecorded", true);
        });
        JsonNode second = rowOf(listOf(w.doctor().auth()), w.adm().id()).get("clinical").get("latestVitals");
        assertThat(keys(second)).containsExactlyInAnyOrder("recordedAt", "recordedByName", "pulse");
        // Diagnosis and Mobile moved into sections that are off are not sent
        publishWith(clinic, s -> {
            moveField(s, "diagnosis", "care");
            moveField(s, "mobile", "care");
            sectionOn(s, "care", false);
            fieldOn(s, "diagnosis", true);
            fieldOn(s, "mobile", true);
        });
        String off = raw(clinic.owner(), ADMISSIONS);
        assertThat(off).doesNotContain("\"diagnosis\"").doesNotContain("\"mobile\"").doesNotContain("\"latestVitals\"");
        // the section back on: sent again
        publishWith(clinic, s -> {
            moveField(s, "diagnosis", "care");
            moveField(s, "mobile", "care");
            fieldOn(s, "diagnosis", true);
            fieldOn(s, "mobile", true);
        });
        String on = raw(clinic.owner(), ADMISSIONS);
        assertThat(on).contains("\"diagnosis\"").contains("\"mobile\"");
    }

    // ---------------- AC54, AC60: what the Admitted page draws ----------------

    @Test
    void thePublishedSetupTravelsInTheListAnswerWithLabelsOrderSectionsAndActionsAndReachesEveryRoleAtTheNextRead() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        Staff frontDesk = staff(clinic, "FRONT_DESK");
        RequestPostProcessor[] auths = {clinic.owner(), w.doctor().auth(), w.nurse().auth(), frontDesk.auth()};
        for (RequestPostProcessor a : auths) {
            assertThat(listOf(a).get("cardSetup").get("version").asInt()).isEqualTo(1);
        }
        publishWith(clinic, s -> {
            s.put("layout", "expanded");
            s.put("cardsPerRow", 2);
            fieldLabel(s, "bed", "Bay");
            fieldLabel(s, "doctor", "<b>Consultant</b>");
            fieldOn(s, "room", false);
            sectionOn(s, "stay", false);
            sectionOn(s, "care", true);
            moveSection(s, "clinical", 1);
            actionOn(s, "deposit", true);
            actionOn(s, "transfer", false);
            // order of the actions: deposit first
            com.fasterxml.jackson.databind.node.ArrayNode actions = (com.fasterxml.jackson.databind.node.ArrayNode) s.get("actions");
            actions.insert(0, actions.remove(4));
        });
        for (RequestPostProcessor a : auths) {
            JsonNode l = listOf(a);
            JsonNode card = l.get("cardSetup");
            assertThat(card.get("version").asInt()).isEqualTo(2);
            assertThat(card.get("isStandard").asBoolean()).isFalse();
            assertThat(card.get("layout").asText()).isEqualTo("expanded");
            assertThat(card.get("cardsPerRow").asInt()).isEqualTo(2);
            List<String> sections = new ArrayList<>();
            card.get("sections").forEach(sec -> sections.add(sec.get("key").asText()));
            assertThat(sections).containsExactly("header", "clinical", "status", "care", "vitals");
            JsonNode bed = null;
            JsonNode doctor = null;
            for (JsonNode sec : card.get("sections")) {
                for (JsonNode f : sec.get("fields")) {
                    if ("bed".equals(f.get("key").asText())) {
                        bed = f;
                    }
                    if ("doctor".equals(f.get("key").asText())) {
                        doctor = f;
                    }
                }
            }
            assertThat(bed.get("label").asText()).isEqualTo("Bay");
            assertThat(doctor.get("label").asText()).as("a label is text, kept as typed").isEqualTo("<b>Consultant</b>");
            assertThat(texts(card.get("actions"))).containsExactly("deposit", "nursing", "emr", "discharge");
            assertThat(rowOf(l, w.adm().id())).isNotNull();
        }
        // the owner's draft is never part of the list
        saveOk(clinic.owner(), 2, null, edit(standard(clinic.owner()), s -> s.put("layout", "compact")));
        for (RequestPostProcessor a : auths) {
            assertThat(listOf(a).get("cardSetup").get("layout").asText()).as("drafts are not on the ward").isEqualTo("expanded");
        }
    }

    @Test
    void aRoleWithoutTheListGets403AndAnInvalidFactsParameterIs400() throws Exception {
        Ward w = ward();
        assertType(problem(call(HttpMethod.GET, ADMISSIONS + "?cardFacts=bogus", w.clinic().owner(), null), 400), "validation");
        assertThat(listOf(w.clinic().owner()).get("cardSetup")).isNotNull();
        assertThat(ok(HttpMethod.GET, ADMISSIONS + "?cardFacts=setup", w.clinic().owner(), null, 200).get("cardSetup"))
                .isEqualTo(listOf(w.clinic().owner()).get("cardSetup"));
    }

    // ---------------- ADR-015 D6: cardFacts=all is the owner's ----------------

    @Test
    void cardFactsAllGivesTheOwnerEveryFactWhateverIsPublishedAndEveryoneElseGets403() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        save(clinic, w.adm().id(), medium5());
        long access = count(clinic, "select count(*) from patient.record_access");
        JsonNode all = listAll(clinic.owner());
        assertThat(all.get("cardSetup").get("version").asInt()).as("the published setup is still the default").isEqualTo(1);
        JsonNode row = rowOf(all, w.adm().id());
        assertThat(row.has("mobile")).isTrue();
        assertThat(row.get("clinical").get("diagnosis").asText()).isEqualTo("Fever");
        JsonNode tiles = row.get("clinical").get("latestVitals");
        assertThat(keys(tiles)).containsExactlyInAnyOrder("recordedAt", "recordedByName", "bp", "pulse", "spo2", "temp", "rr");
        // the normal read is still the default answer
        assertThat(raw(clinic.owner(), ADMISSIONS)).doesNotContain("\"diagnosis\"").doesNotContain("\"latestVitals\"");
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK"}) {
            RequestPostProcessor who = "DOCTOR".equals(role) ? w.doctor().auth()
                    : "NURSE".equals(role) ? w.nurse().auth() : staff(clinic, role).auth();
            String text = raw(who, ADMISSIONS + "?cardFacts=all");
            assertThat(text).as(role).doesNotContain("Fever").doesNotContain("\"latestVitals\"");
            forbiddenOf(call(HttpMethod.GET, ADMISSIONS + "?cardFacts=all", who, null));
        }
        // an owner token on a person who is no longer the owner
        Staff demoted = secondOwner(clinic);
        setMembershipRole(clinic, demoted.userId(), "NURSE");
        forbiddenOf(call(HttpMethod.GET, ADMISSIONS + "?cardFacts=all", demoted.auth(), null));
        assertThat(count(clinic, "select count(*) from patient.record_access")).as("no record-access row").isEqualTo(access);
    }

    // ---------------- OQ-37-11: tile marks from the SAH-30 per-reading points ----------------

    private static final Object[][] EDGES = {
        {"spo2", "91", "CRITICAL"}, {"spo2", "92", "CHECK"}, {"spo2", "93", "CHECK"}, {"spo2", "94", "CHECK"},
        {"spo2", "95", "CHECK"}, {"spo2", "96", null}, {"spo2", "100", null},
        {"pulse", "40", "CRITICAL"}, {"pulse", "41", "CHECK"}, {"pulse", "50", "CHECK"}, {"pulse", "51", null},
        {"pulse", "90", null}, {"pulse", "91", "CHECK"}, {"pulse", "110", "CHECK"}, {"pulse", "111", "CHECK"},
        {"pulse", "130", "CHECK"}, {"pulse", "131", "CRITICAL"},
        {"rr", "8", "CRITICAL"}, {"rr", "9", "CHECK"}, {"rr", "11", "CHECK"}, {"rr", "12", null}, {"rr", "20", null},
        {"rr", "21", "CHECK"}, {"rr", "24", "CHECK"}, {"rr", "25", "CRITICAL"},
        {"temp", "35.0", "CRITICAL"}, {"temp", "35.1", "CHECK"}, {"temp", "36.0", "CHECK"}, {"temp", "36.1", null},
        {"temp", "38.0", null}, {"temp", "38.1", "CHECK"}, {"temp", "39.0", "CHECK"}, {"temp", "39.1", "CHECK"},
        {"bp", "90/60", "CRITICAL"}, {"bp", "91/60", "CHECK"}, {"bp", "100/60", "CHECK"}, {"bp", "101/60", "CHECK"},
        {"bp", "110/60", "CHECK"}, {"bp", "111/60", null}, {"bp", "219/100", null}, {"bp", "220/100", "CRITICAL"},
        {"bp", "120/40", null}};

    @Test
    void theTileMarksFollowTheSah30PointsAtEveryBandEdgeAndTheDiastolicIsNeverMarked() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        String id = w.adm().id();
        List<String> wrong = new ArrayList<>();
        for (Object[] edge : EDGES) {
            String param = (String) edge[0];
            Map<String, Object> body = vit(param.equals("bp") ? (String) edge[1] : "120/80",
                    param.equals("pulse") ? (String) edge[1] : "80", param.equals("spo2") ? (String) edge[1] : "98",
                    param.equals("temp") ? (String) edge[1] : "37.0", param.equals("rr") ? (String) edge[1] : "16");
            save(clinic, id, body);
            JsonNode tiles = rowOf(listAll(clinic.owner()), id).get("clinical").get("latestVitals");
            JsonNode reading = tiles.get(param);
            String mark = reading.has("mark") ? reading.get("mark").asText() : null;
            if (!java.util.Objects.equals(mark, edge[2])) {
                wrong.add(param + " " + edge[1] + " expected " + edge[2] + " got " + mark);
            }
            // the other four readings are normal and carry no mark
            for (String other : new String[] {"bp", "pulse", "spo2", "temp", "rr"}) {
                if (!other.equals(param)) {
                    assertThat(tiles.get(other).has("mark")).as(param + " " + edge[1] + ": " + other + " has no mark").isFalse();
                }
            }
        }
        assertThat(wrong).as("tile marks off the SAH-30 points").isEmpty();
    }

    @Test
    void theMarkReachesTheCardOfADoctorWhenTheReadingIsShownAndTheLatestRecordWins() throws Exception {
        Ward w = ward();
        Clinic clinic = w.clinic();
        publishWith(clinic, s -> {
            fieldOn(s, "spo2", true);
            fieldOn(s, "bp", true);
        });
        save(clinic, w.adm().id(), medium3());      // SpO2 91: critical
        JsonNode first = rowOf(listOf(w.doctor().auth()), w.adm().id()).get("clinical").get("latestVitals");
        assertThat(first.get("spo2").get("mark").asText()).isEqualTo("CRITICAL");
        assertThat(first.get("bp").has("mark")).isFalse();
        save(clinic, w.adm().id(), low());          // the newer record is normal
        JsonNode second = rowOf(listOf(w.doctor().auth()), w.adm().id()).get("clinical").get("latestVitals");
        assertThat(second.get("spo2").get("value").asInt()).isEqualTo(98);
        assertThat(second.get("spo2").has("mark")).isFalse();
        // the early warning of the card is the SAH-30 score of the same record
        assertThat(rowOf(listOf(w.doctor().auth()), w.adm().id()).get("clinical").get("latestEws").get("score").asInt()).isZero();
    }
}
