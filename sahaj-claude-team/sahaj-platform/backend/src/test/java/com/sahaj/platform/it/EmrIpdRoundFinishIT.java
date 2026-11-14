package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-67-1 and SAH-68-1 on the real database (docs/api/opd.yaml 0.6.1, ipd.yaml 0.3.0, ADR-025 D8, D12): the doctor signs
 * the day's progress note of an admitted patient. New medicines go through ipd's own SAH-31 order path inside the sign
 * transaction, the round is marked done ("Round done" on the Admitted card, "Latest round" on the nursing sheet), nothing
 * is billed, a refused medicine or a discharged admission leaves the note a draft, and the nurse's vitals are read for the
 * pre-fill. Test plan: docs/test-plans/SAH-51-68.md (T-67-1.x, T-68-1.x).
 */
@OnTestDatabase
class EmrIpdRoundFinishIT extends EmrIpdSupport {

    private record Round(Clinic clinic, Adm adm, RequestPostProcessor doctor, JsonNode record) {

        String id() {
            return record.get("id").asText();
        }
    }

    /** A hospital, a patient with this allergy text on a General bed, and the admitting doctor's open round note. */
    private Round round(String allergy) throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, allergy);
        RequestPostProcessor doctor = admittingDoctor(clinic);
        JsonNode record = openRecord(doctor, "IPD_ROUND", adm.id(), 201).get("record");
        return new Round(clinic, adm, doctor, record);
    }

    /** The round with one paracetamol order row filled in (the required Prescription of an IPD round). */
    private Round readyRound() throws Exception {
        Round r = round(NONE_KNOWN_ALLERGY);
        return new Round(r.clinic(), r.adm(), r.doctor(), fillWith(r.doctor(), r.record(), ipdOrders(ipdRow(UUID.randomUUID().toString(), "pcm", "od"))));
    }

    private long orders(Clinic clinic, String admissionId) throws Exception {
        return count(clinic, "select count(*) from ipd.medicine_order where admission_id = ?::uuid", UUID.fromString(admissionId));
    }

    private long notes(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from ipd.round_note");
    }

    // ---------------- the check ----------------

    @Test
    void theOrderOptionsComeFromIpdsOwnCatalogueAndTheCheckShowsEachRowsOutcomeWithoutOrderingAnything() throws Exception {
        Round r = readyRound();
        JsonNode options = ok(HttpMethod.GET, EMR_RECORDS + "/" + r.id() + "/ipd-order-options", r.doctor(), null, 200);
        assertThat(texts(options.get("drugs"), "key")).contains("pcm", "amox");
        assertThat(texts(options.get("frequencies"), "key")).contains("od", "bd");
        assertThat(options.get("overrideReasons")).isNotEmpty();
        JsonNode check = check(r.doctor(), r.record(), "{}", 200);
        assertThat(check.get("ready").asBoolean()).isTrue();
        assertThat(check.get("encounterType").asText()).isEqualTo("IPD_ROUND");
        assertThat(check.get("effects").get("ipdOrders").get(0).get("outcome").asText()).isEqualTo("PASS");
        assertThat(check.get("effects").get("stage").isNull()).isTrue();
        assertThat(check.get("effects").get("billLines")).isEmpty();
        assertThat(check.get("effects").get("prints")).as("an IPD round prints nothing").isEmpty();
        assertThat(orders(r.clinic(), r.adm().id())).as("a check orders nothing").isZero();
        assertThat(notes(r.clinic())).isZero();
    }

    // ---------------- the sign ----------------

    @Test
    void signingPlacesTheOrderThroughSah31MarksTheRoundDoneAndSignsTheNoteInOneAction() throws Exception {
        Round r = readyRound();
        JsonNode result = sign(r.doctor(), r.record(), "{}");
        JsonNode outcome = result.get("outcome");
        assertThat(result.get("replayed").asBoolean()).isFalse();
        assertThat(result.get("record").get("status").asText()).isEqualTo("SIGNED");
        assertThat(outcome.get("round").get("admissionId").asText()).isEqualTo(r.adm().id());
        assertThat(outcome.get("round").get("roundDate").asText()).isEqualTo(todayIst().toString());
        assertThat(outcome.get("ipdOrders")).hasSize(1);
        assertThat(outcome.get("ipdOrders").get(0).get("orderId").asText()).isNotBlank();
        assertThat(outcome.get("billLines").asInt()).as("no bill line for a round (DEC-042 H8)").isZero();
        assertThat(outcome.get("stage").isNull()).isTrue();
        assertThat(outcome.get("prints")).isEmpty();
        assertThat(orders(r.clinic(), r.adm().id())).isOne();
        List<String> note = values(r.clinic(), "select round_date::text, emr_record_id::text, orders_placed::text, signed_by_user_id::text "
                + "from ipd.round_note where admission_id = ?::uuid", UUID.fromString(r.adm().id()));
        assertThat(note.get(0)).isEqualTo(todayIst().toString());
        assertThat(note.get(1)).isEqualTo(r.id());
        assertThat(note.get(2)).isEqualTo("1");
        assertThat(count(r.clinic(), "select count(*) from billing.bill_item")).isZero();
        assertThat(values(r.clinic(), "select status from ipd.admission where id = ?::uuid", UUID.fromString(r.adm().id())).get(0)).isEqualTo("ADMITTED");
    }

    @Test
    void theSignedNoteShowsRoundDoneOnTheAdmittedCardAndLatestRoundOnTheNursingSheetWithTheInvestigationsAsText() throws Exception {
        Round r = readyRound();
        JsonNode withTests = withInvestigation(r.doctor(), r.record());
        JsonNode before = row(ok(HttpMethod.GET, ADMISSIONS, r.clinic().owner(), null, 200), r.adm().id());
        assertThat(before.get("clinical").get("roundDoneToday").asBoolean()).isFalse();
        sign(r.doctor(), withTests, "{}");
        JsonNode card = row(ok(HttpMethod.GET, ADMISSIONS, r.clinic().owner(), null, 200), r.adm().id());
        assertThat(card.get("clinical").get("roundDoneToday").asBoolean()).isTrue();
        JsonNode sheet = ok(HttpMethod.GET, nursingUrl(r.adm().id()), r.clinic().owner(), null, 200);
        JsonNode latest = sheet.get("latestRound");
        assertThat(latest.get("roundDate").asText()).isEqualTo(todayIst().toString());
        assertThat(latest.get("doctorName").asText()).isNotBlank();
        assertThat(latest.get("investigationsAdvised").asText()).isEqualTo("Zxqtest blood count");
        assertThat(values(r.clinic(), "select investigations_advised from ipd.round_note").get(0)).isEqualTo("Zxqtest blood count");
        assertThat(count(r.clinic(), "select count(*) from ipd.clinical_event where admission_id = ?::uuid and kind = 'info' and text = 'Signed the daily progress note'",
                UUID.fromString(r.adm().id()))).as("one clinical event for the sign").isOne();
    }

    @Test
    void aRetryWithTheSameKeyAndBodyIsAReplayWithOneNoteOneOrderAndOneRoundMark() throws Exception {
        Round r = readyRound();
        String key = idemKey();
        String body = signBody(r.record().get("revision").asLong(), "{}");
        String path = EMR_RECORDS + "/" + r.id() + "/sign";
        JsonNode first = okKeyed(HttpMethod.POST, path, r.doctor(), body, key, 200);
        JsonNode again = okKeyed(HttpMethod.POST, path, r.doctor(), body, key, 200);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(again.get("outcome").get("ipdOrders")).isEqualTo(first.get("outcome").get("ipdOrders"));
        assertThat(orders(r.clinic(), r.adm().id())).isOne();
        assertThat(notes(r.clinic())).isOne();
        assertThat(count(r.clinic(), "select count(*) from platform.audit_event where action = 'IPD_ROUND_SIGNED'")).isOne();
        signRefused(r.doctor(), r.record(), "{}", 409, "emr-record-signed");
    }

    @Test
    void aSignedNoteOpensReadOnlyTheSameDayWithAddAddendumAndOnlyOneNoteExistsForTheDay() throws Exception {
        Round r = readyRound();
        sign(r.doctor(), r.record(), "{}");
        JsonNode again = openRecord(r.doctor(), "IPD_ROUND", r.adm().id(), 200);
        assertThat(again.get("record").get("id").asText()).isEqualTo(r.id());
        assertThat(again.get("record").get("readOnly").asBoolean()).isTrue();
        assertThat(again.get("record").get("canAddAddendum").asBoolean()).isTrue();
        JsonNode added = okKeyed(HttpMethod.POST, EMR_RECORDS + "/" + r.id() + "/addenda", r.doctor(),
                "{\"reason\":\"Correction\",\"text\":\"Dose to be checked\"}", idemKey(), 201);
        assertThat(added.get("id").asText()).isNotBlank();
        assertThat(notes(r.clinic())).isOne();
        assertThat(orders(r.clinic(), r.adm().id())).as("an addendum orders nothing").isOne();
    }

    @Test
    void theAuditEntryHoldsTheOrderCountAndNoMedicineNameOrClinicalText() throws Exception {
        Round r = readyRound();
        sign(r.doctor(), r.record(), "{}");
        List<List<String>> audits = db().inTenant(r.clinic().tenantId(), c -> EmrSupport.allRows(c,
                "select after_json::text from platform.audit_event where action in ('EMR_RECORD_SIGNED', 'IPD_ROUND_SIGNED')"));
        assertThat(audits).hasSize(2);
        for (List<String> a : audits) {
            assertThat(a.get(0)).doesNotContain("Paracetamol").doesNotContain("pcm").doesNotContain("Viral fever")
                    .doesNotContain(r.adm().name());
        }
        assertThat((audits.get(0).get(0) + audits.get(1).get(0)).replace(" ", "")).contains("\"ordersPlaced\":1");
    }

    // ---------------- refusals that leave the note a draft ----------------

    @Test
    void aMedicineThatSah31BlocksIsShownAsNotOrderedInTheCheckAndTheSignAnswers409WithNothingKept() throws Exception {
        Round r = round("Penicillin");
        String row = UUID.randomUUID().toString();
        JsonNode filled = fillWith(r.doctor(), r.record(), ipdOrders(ipdRow(row, "amox", "bd")));
        JsonNode check = check(r.doctor(), filled, "{}", 200);
        assertThat(check.get("ready").asBoolean()).isFalse();
        JsonNode blocker = null;
        for (JsonNode b : check.get("blockers")) {
            if ("IPD_ORDER_BLOCKED".equals(b.get("key").asText())) {
                blocker = b;
            }
        }
        assertThat(blocker).isNotNull();
        assertThat(blocker.get("message").asText()).startsWith("Not ordered:");
        assertThat(texts(blocker.get("rowIds"), null)).containsExactly(row);
        assertThat(check.get("effects").get("ipdOrders").get(0).get("outcome").asText()).isEqualTo("BLOCKED");
        JsonNode problem = signRefused(r.doctor(), filled, "{}", 409, "ipd-order-not-placed");
        assertThat(problem.get("rows").isArray()).isTrue();
        assertThat(recordRow(r.clinic(), r.id()).get(0)).isEqualTo("DRAFT");
        assertThat(orders(r.clinic(), r.adm().id())).isZero();
        assertThat(notes(r.clinic())).isZero();
        assertThat(count(r.clinic(), "select count(*) from platform.audit_event where action = 'EMR_RECORD_SIGNED'")).isZero();
        assertThat(problem.toString()).doesNotContain("Penicillin");
    }

    /**
     * DEC-048 H31 (security M1): a hard stop met at the sign leaves its deny event and its IPD_MED_ORDER_BLOCKED audit row even
     * though the sign is refused (H37: the order check runs and commits in its own short transaction before the sign transaction
     * opens, so the audit row is already committed when the sign is refused).
     */
    @Test
    void aBlockedMedicineMetAtTheSignWithoutAnyFinishCheckLeavesOneBlockedAuditRowAndOneDenyEventWhileTheSignAnswers409() throws Exception {
        Round r = round("Penicillin");
        String row = UUID.randomUUID().toString();
        JsonNode filled = fillWith(r.doctor(), r.record(), ipdOrders(ipdRow(row, "amox", "bd")));
        UUID admission = UUID.fromString(r.adm().id());
        assertThat(count(r.clinic(), "select count(*) from platform.audit_event where action = 'IPD_MED_ORDER_BLOCKED'")).as("no finish check was made").isZero();
        JsonNode problem = signRefused(r.doctor(), filled, "{}", 409, "ipd-order-not-placed");
        assertThat(problem.get("rows").isArray()).isTrue();
        assertThat(recordRow(r.clinic(), r.id()).get(0)).as("the note stays a draft").isEqualTo("DRAFT");
        assertThat(orders(r.clinic(), r.adm().id())).isZero();
        assertThat(notes(r.clinic())).isZero();
        assertThat(count(r.clinic(), "select count(*) from platform.audit_event where action = 'EMR_RECORD_SIGNED'")).isZero();
        assertThat(count(r.clinic(), "select count(*) from platform.audit_event where action = 'IPD_MED_ORDER_BLOCKED'"))
                .as("the hard stop is recorded although the sign rolled back").isOne();
        assertThat(count(r.clinic(), "select count(*) from ipd.clinical_event where admission_id = ? and kind = 'deny'", admission))
                .as("and so is the deny event").isOne();
        String audit = values(r.clinic(), "select coalesce(before_json::text, '') || coalesce(after_json::text, '') from platform.audit_event "
                + "where action = 'IPD_MED_ORDER_BLOCKED'").get(0);
        assertThat(audit).as("ids and keys only").doesNotContain("Penicillin").doesNotContain(r.adm().name());
        assertThat(problem.toString()).doesNotContain("Penicillin");
    }

    /**
     * DEC-048 H37 (QA F-9): the order pre-check never holds a second pool connection while the sign transaction has one. As many
     * round signs at the same moment as the test request pool holds (5, application-test.yml) all finish with 200, well inside the
     * 30 second connection timeout; if each sign waited for a second connection, they would all wait until it timed out.
     */
    @Test
    void asManyRoundSignsAtTheSameMomentAsThePoolHoldsAllFinishWithoutAConnectionTimeout() throws Exception {
        final int signs = 5;
        Clinic clinic = hospital();
        RequestPostProcessor doctor = admittingDoctor(clinic);
        List<Round> rounds = new ArrayList<>();
        for (int i = 0; i < signs; i++) {
            Adm adm = admitWithAllergy(clinic, NONE_KNOWN_ALLERGY);
            JsonNode opened = openRecord(doctor, "IPD_ROUND", adm.id(), 201).get("record");
            JsonNode filled = fillWith(doctor, opened, ipdOrders(ipdRow(UUID.randomUUID().toString(), "pcm", "od")));
            rounds.add(new Round(clinic, adm, doctor, filled));
        }
        ExecutorService pool = Executors.newFixedThreadPool(signs);
        CountDownLatch ready = new CountDownLatch(signs);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (Round r : rounds) {
                String body = signBody(r.record().get("revision").asLong(), "{}");
                String path = EMR_RECORDS + "/" + r.id() + "/sign";
                String key = idemKey();
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return keyed(HttpMethod.POST, path, r.doctor(), body, key).andReturn().getResponse().getStatus();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<Integer> f : futures) {
                assertThat(f.get(25, TimeUnit.SECONDS)).as("every concurrent round sign is answered 200, none times out").isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).as("inside the connection timeout").isLessThan(25);
        assertThat(notes(clinic)).isEqualTo(signs);
        for (Round r : rounds) {
            assertThat(orders(clinic, r.adm().id())).isOne();
            assertThat(recordRow(clinic, r.id()).get(0)).isEqualTo("SIGNED");
        }
    }

    /** Security L2 (opd.yaml 0.6.2): a lapsed subscription makes IPD Read-only; a round is not signed then. */
    @Test
    void whenIpdIsReadOnlyTheSignOfARoundIs409IpdReadOnlyWithNoOrderNoNoteAndNoAudit() throws Exception {
        Round r = readyRound();
        expireTrial(r.clinic());
        long audits = count(r.clinic(), "select count(*) from platform.audit_event");
        JsonNode problem = signRefused(r.doctor(), r.record(), "{}", 409, "ipd-read-only");
        assertThat(problem.get("detail").asText()).isNotBlank();
        assertThat(recordRow(r.clinic(), r.id()).get(0)).isEqualTo("DRAFT");
        assertThat(orders(r.clinic(), r.adm().id())).isZero();
        assertThat(notes(r.clinic())).isZero();
        assertThat(count(r.clinic(), "select count(*) from platform.audit_event")).as("nothing audited").isEqualTo(audits);
        assertThat(count(r.clinic(), "select count(*) from ipd.clinical_event where admission_id = ? and text = 'Signed the daily progress note'",
                UUID.fromString(r.adm().id()))).isZero();
        // reading the note still works
        assertThat(getRecord(r.doctor(), r.id()).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void aWarningNeedsTheReasonFromTheCheckAndThenThePlacedOrderCarriesTheOverrideReason() throws Exception {
        Round r = round(null);
        String row = UUID.randomUUID().toString();
        JsonNode filled = fillWith(r.doctor(), r.record(), ipdOrders(ipdRow(row, "pcm", "od")));
        JsonNode first = check(r.doctor(), filled, "{}", 200);
        assertThat(first.get("ready").asBoolean()).isFalse();
        assertThat(texts(first.get("blockers"), "key")).contains("IPD_ORDER_NEEDS_REASON");
        JsonNode line = first.get("effects").get("ipdOrders").get(0);
        assertThat(line.get("outcome").asText()).isEqualTo("NEEDS_REASON");
        String choices = "{\"ipdOrderReasons\":[{\"rowId\":\"" + row + "\",\"overrideReasonKey\":\"BENEFIT_MONITORED\",\"checkToken\":\""
                + line.get("checkToken").asText() + "\"}]}";
        assertThat(check(r.doctor(), filled, choices, 200).get("ready").asBoolean()).isTrue();
        JsonNode result = sign(r.doctor(), filled, choices);
        assertThat(result.get("outcome").get("ipdOrders")).hasSize(1);
        assertThat(values(r.clinic(), "select override_reason_key from ipd.medicine_order where admission_id = ?::uuid",
                UUID.fromString(r.adm().id())).get(0)).isEqualTo("BENEFIT_MONITORED");
    }

    @Test
    void anAdmissionDischargedMeanwhileIs409EncounterClosedAndTheNoteStaysADraftWithNoOrder() throws Exception {
        Round r = readyRound();
        signAndDischarge(r.clinic(), r.adm().id());
        JsonNode problem = signRefused(r.doctor(), r.record(), "{}", 409, "encounter-closed");
        assertThat(problem.get("detail").asText()).contains("closed");
        assertThat(recordRow(r.clinic(), r.id()).get(0)).isEqualTo("DRAFT");
        assertThat(orders(r.clinic(), r.adm().id())).isZero();
        assertThat(notes(r.clinic())).isZero();
        assertThat(check(r.doctor(), r.record(), "{}", 200).get("ready").asBoolean()).isFalse();
    }

    @Test
    void aRecordThatIsNotReadyIs409WithTheBlockersAndOrdersNothing() throws Exception {
        Round r = round(NONE_KNOWN_ALLERGY);
        JsonNode problem = signRefused(r.doctor(), r.record(), "{}", 409, "emr-not-ready");
        assertThat(texts(problem.get("blockers"), "key")).contains("STILL_NEEDED");
        assertThat(orders(r.clinic(), r.adm().id())).isZero();
    }

    // ---------------- who may sign ----------------

    @Test
    void onlyTheAdmittingDoctorOrTheOwnerSignsADoctorWhoIsNotTheAdmittingDoctorIs403NotOwnPatientAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        Pn other = newPn(clinic, "DOCTOR");
        JsonNode opened = openRecord(other.auth(), "IPD_ROUND", adm.id(), 201).get("record");
        JsonNode filled = fillWith(other.auth(), opened, ipdOrders(ipdRow(UUID.randomUUID().toString(), "pcm", "od")));
        signRefused(other.auth(), filled, "{}", 403, "not-own-patient");
        assertThat(recordRow(clinic, filled.get("id").asText()).get(0)).isEqualTo("DRAFT");
        assertThat(orders(clinic, adm.id())).isZero();
        assertThat(notes(clinic)).isZero();
    }

    @Test
    void theOwnerWithDoctorDetailsSignsAnyAdmissionsNote() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitWithAllergy(clinic, NONE_KNOWN_ALLERGY);
        giveOwnerDoctorDetails(clinic);
        JsonNode opened = openRecord(clinic.owner(), "IPD_ROUND", adm.id(), 201).get("record");
        JsonNode filled = fillWith(clinic.owner(), opened, ipdOrders(ipdRow(UUID.randomUUID().toString(), "pcm", "od")));
        JsonNode result = sign(clinic.owner(), filled, "{}");
        assertThat(result.get("record").get("status").asText()).isEqualTo("SIGNED");
        assertThat(notes(clinic)).isOne();
    }

    @Test
    void nurseFrontDeskBillingPharmacistCounsellorAndVisitingDoctorAre403AnotherClinicIs404AndNoSessionIs401() throws Exception {
        Round r = readyRound();
        String path = EMR_RECORDS + "/" + r.id() + "/sign";
        String body = signBody(r.record().get("revision").asLong(), "{}");
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR", "VISITING_DOCTOR"}) {
            problemKeyed(HttpMethod.POST, path, newPn(r.clinic(), role).auth(), body, idemKey(), 403, "forbidden");
            problemOf(HttpMethod.POST, EMR_RECORDS + "/" + r.id() + "/finish-check", newPn(r.clinic(), role).auth(), "{}", 403, "forbidden");
        }
        Clinic other = hospital();
        problemKeyed(HttpMethod.POST, path, newPn(other, "DOCTOR").auth(), body, idemKey(), 404, "record-not-found");
        problemKeyed(HttpMethod.POST, path, request -> request, body, idemKey(), 401, "unauthorized");
        assertThat(recordRow(r.clinic(), r.id()).get(0)).isEqualTo("DRAFT");
        assertThat(orders(r.clinic(), r.adm().id())).isZero();
    }

    @Test
    void aSignOfAnIpdRoundWithAnErOutcomeOrAnAdmissionRequestIsRefused() throws Exception {
        Round r = readyRound();
        problemKeyed(HttpMethod.POST, EMR_RECORDS + "/" + r.id() + "/sign", r.doctor(),
                signBody(r.record().get("revision").asLong(), "{\"erOutcome\":{\"kind\":\"DISCHARGE\"}}"), idemKey(), 400, "validation");
        problemKeyed(HttpMethod.POST, EMR_RECORDS + "/" + r.id() + "/admission-request", r.doctor(),
                "{\"admissionType\":\"planned\"}", idemKey(), 400, "not-applicable");
        assertThat(recordRow(r.clinic(), r.id()).get(0)).isEqualTo("DRAFT");
    }

    // ---------------- vitals read (SAH-68-1) ----------------

    @Test
    void theNursesLatestVitalsOfTheDayAreReadWithTheRecorderAndTheSourcesSayIpdIsAvailableAndOpdIsNotCaptured() throws Exception {
        Round r = readyRound();
        JsonNode none = ok(HttpMethod.GET, EMR_RECORDS + "/" + r.id() + "/encounter-vitals", r.doctor(), null, 200);
        assertThat(none.get("current").isNull()).isTrue();
        assertThat(none.get("earlier").isNull()).isTrue();
        assertThat(none.get("sources").get("ipd").asText()).isEqualTo("AVAILABLE");
        assertThat(none.get("sources").get("opd").asText()).isEqualTo("NOT_CAPTURED");
        assertThat(none.get("sources").get("er").asText()).isEqualTo("NOT_CAPTURED");
        Pn nurse = newPn(r.clinic(), "NURSE");
        ok(HttpMethod.POST, vitalsUrl(r.adm().id()), nurse.auth(), js(low()), 201);
        JsonNode vitals = ok(HttpMethod.GET, EMR_RECORDS + "/" + r.id() + "/encounter-vitals", r.doctor(), null, 200);
        JsonNode current = vitals.get("current");
        assertThat(current.get("source").asText()).isEqualTo("IPD_NURSING_CHART");
        assertThat(current.get("sourceEntryId").asText()).isNotBlank();
        assertThat(current.get("bpSystolic").asInt()).isEqualTo(120);
        assertThat(current.get("bpDiastolic").asInt()).isEqualTo(80);
        assertThat(current.get("pulse").asInt()).isEqualTo(80);
        assertThat(current.get("spo2").asInt()).isEqualTo(98);
        assertThat(current.get("respiratoryRate").asInt()).isEqualTo(16);
        assertThat(current.get("temperature").get("unit").asText()).isNotBlank();
        assertThat(current.get("recordedByName").asText()).isNotBlank();
        assertThat(current.get("recordedAt").asText()).isNotBlank();
    }

    @Test
    void acceptedNurseValuesAreStampedWithTheNursesEntryAndAChangedValueDropsTheStamp() throws Exception {
        Round r = readyRound();
        Pn nurse = newPn(r.clinic(), "NURSE");
        ok(HttpMethod.POST, vitalsUrl(r.adm().id()), nurse.auth(), js(low()), 201);
        JsonNode vitals = ok(HttpMethod.GET, EMR_RECORDS + "/" + r.id() + "/encounter-vitals", r.doctor(), null, 200);
        String entry = vitals.get("current").get("sourceEntryId").asText();
        String same = "{\"key\":\"vitals\",\"values\":{\"bp_systolic\":120,\"bp_diastolic\":80,\"pulse\":80,\"spo2\":98,\"temperature\":98.6},"
                + "\"prefill\":{\"source\":\"IPD_NURSING_CHART\",\"sourceEntryId\":\"" + entry + "\"}}";
        JsonNode stamped = saveDraft(r.doctor(), r.id(), draft(r.record().get("revision").asLong(), same), 200);
        JsonNode from = null;
        for (JsonNode s : stamped.get("sections")) {
            if ("vitals".equals(s.get("key").asText())) {
                from = s.get("prefilledFrom");
            }
        }
        assertThat(from).isNotNull();
        assertThat(from.isNull()).isFalse();
        assertThat(from.get("source").asText()).isEqualTo("IPD_NURSING_CHART");
        assertThat(from.get("sourceEntryId").asText()).isEqualTo(entry);
        assertThat(from.get("recordedByName").asText()).isNotBlank();
        // the doctor changes the pulse: the doctor is now the recorder and the stamp goes
        String changed = same.replace("\"pulse\":80", "\"pulse\":90");
        JsonNode unstamped = saveDraft(r.doctor(), r.id(), draft(stamped.get("revision").asLong(), changed), 200);
        for (JsonNode s : unstamped.get("sections")) {
            if ("vitals".equals(s.get("key").asText())) {
                assertThat(s.get("prefilledFrom") == null || s.get("prefilledFrom").isNull()).isTrue();
            }
        }
        // the nurse's own entry is untouched
        assertThat(count(r.clinic(), "select count(*) from ipd.vitals where admission_id = ?::uuid", UUID.fromString(r.adm().id()))).isOne();
    }

    @Test
    void nobodyButTheRecordsDoctorReadsTheEncounterVitalsAndNoVitalValueIsLogged() throws Exception {
        Round r = readyRound();
        Pn nurse = newPn(r.clinic(), "NURSE");
        ok(HttpMethod.POST, vitalsUrl(r.adm().id()), nurse.auth(), js(noted(low(), "Zxqvitalnote")), 201);
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problemOf(HttpMethod.GET, EMR_RECORDS + "/" + r.id() + "/encounter-vitals", newPn(r.clinic(), role).auth(), null, 403, "forbidden");
        }
        try (LogCapture logs = new LogCapture()) {
            ok(HttpMethod.GET, EMR_RECORDS + "/" + r.id() + "/encounter-vitals", r.doctor(), null, 200);
            assertThat(logs.allText()).doesNotContain("Zxqvitalnote").doesNotContain(r.adm().name());
        }
    }
}
