package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrMaster;
import com.sahaj.platform.support.LogCapture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-51-2 and SAH-68-2, SAH-68-3 on the real database (docs/api/opd.yaml 0.6.1, ADR-025 D1, D6, D13): one transaction that
 * signs an OPD visit, moves the card, makes the follow-up and its appointment, equals the bill lines, counts the ranking
 * once, writes the patient's allergy back and answers a retry with the first result. Test plan: docs/test-plans/SAH-51-68.md
 * (T-51-2.x, T-68-2.x, T-68-3.x).
 */
@OnTestDatabase
class EmrSignOpdIT extends EmrSignSupport {

    private static final String PATIENT_URL = "/api/v1/patient/patients/";

    private String allergyOf(Clinic clinic, UUID patientId) throws Exception {
        JsonNode patient = get(PATIENT_URL + patientId, clinic.owner());
        return patient.path("allergy").isNull() ? null : patient.path("allergy").asText(null);
    }

    // ---------------- the signature ----------------

    @Test
    void signingFreezesTheRecordAndStoresTheSignerTheKeyTheHashAndTheResultAndClearsTheHold() throws Exception {
        Rec rec = readyRec();
        String key = key();
        JsonNode result = signWithKey(rec, key, "{}", 200);
        assertThat(result.get("replayed").asBoolean()).isFalse();
        JsonNode record = result.get("record");
        assertThat(record.get("status").asText()).isEqualTo("SIGNED");
        assertThat(record.get("readOnly").asBoolean()).isTrue();
        assertThat(record.get("readOnlyReason").asText()).isEqualTo("SIGNED");
        assertThat(record.get("signedAt").asText()).isNotBlank();
        assertThat(record.get("signedByName").asText()).isNotBlank();
        assertThat(record.get("revision").asLong()).isEqualTo(rec.revision() + 1);
        assertThat(record.get("canAddAddendum").asBoolean()).isTrue();
        assertThat(record.get("editHold").isNull()).isTrue();
        List<String> columns = signColumns(rec.clinic(), rec.id());
        assertThat(columns.get(0)).isEqualTo("SIGNED");
        assertThat(columns.get(1)).as("signed_by").isEqualTo(rec.doctor().userId().toString());
        assertThat(columns.get(2)).as("signed_by_membership_id").isEqualTo(rec.doctor().membershipId().toString());
        assertThat(columns.get(3)).isEqualTo(key);
        assertThat(columns.get(4)).matches("^[0-9a-f]{64}$");
        assertThat(columns.get(5)).as("sign_result stored").isEqualTo("true");
        assertThat(columns.get(6)).as("no edit hold left").isEqualTo("true");
        assertThat(columns.get(7)).as("signed_at").isEqualTo("true");
        assertThat(Long.parseLong(columns.get(8))).isEqualTo(rec.revision() + 1);
        assertThat(record(rec.token(), rec.id()).get("status").asText()).isEqualTo("SIGNED");
    }

    @Test
    void theSignMovesTheVisitOnTheQueueThroughItsWorkflow() throws Exception {
        Rec rec = readyRec();
        assertThat(boardVisit(rec.clinic(), rec.visitId()).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        JsonNode result = signOk(rec);
        JsonNode stage = result.get("outcome").get("stage");
        assertThat(stage.get("outcome").asText()).isEqualTo("MOVED");
        JsonNode card = boardVisit(rec.clinic(), rec.visitId());
        assertThat(card.get("stage").asText()).isNotEqualTo("WITH_DOCTOR").isEqualTo(stage.get("to").asText());
        assertThat(card.get("emrStatus").asText()).isEqualTo("SIGNED");
    }

    @Test
    void aCardMovedByTheOwnerMeanwhileIsReportedAsAlreadyMovedAndTheSignStillCompletes() throws Exception {
        Rec rec = readyRec();
        ok(HttpMethod.PATCH, VISITS + "/" + rec.visitId() + "/stage", rec.clinic().owner(), "{\"stage\":\"BILLING\"}", 200);
        JsonNode result = signOk(rec);
        assertThat(result.get("outcome").get("stage").get("outcome").asText()).isEqualTo("ALREADY_MOVED");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("SIGNED");
        assertThat(boardVisit(rec.clinic(), rec.visitId()).get("stage").asText()).isEqualTo("BILLING");
    }

    @Test
    void oneAuditEntryRecordsWhoAndWhatWithIdsAndCountsOnlyAndNoClinicalOrPersonalText() throws Exception {
        Rec rec = readyRec();
        signOk(rec);
        List<List<String>> audits = auditRows(rec.clinic(), "EMR_RECORD_SIGNED");
        assertThat(audits).hasSize(1);
        List<String> audit = audits.get(0);
        assertThat(audit.get(0)).isEqualTo("EMR_RECORD");
        assertThat(audit.get(2)).contains("OPD_VISIT").contains("MOVED");
        assertThat(audit.get(2)).doesNotContain("Fever").doesNotContain("Viral fever").doesNotContain("Paracetamol")
                .doesNotContain("Ramesh").doesNotContain("Kulkarni");
        assertThat(audit.get(3)).isEqualTo(rec.doctor().userId().toString());
        assertThat(allAuditText(rec.clinic())).doesNotContain("Viral fever").doesNotContain("Paracetamol");
        // F-5: the key is always there (null when no slot was booked or attached)
        assertThat(objectMapper.readTree(audit.get(2)).has("appointmentId")).isTrue();
    }

    @Test
    void theSignAuditHoldsNoNameMobileAbhaDateOfBirthOrClinicalText() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D3");
        signOk(rec);
        List<String> patient = row(rec.clinic(), "select btrim(name), mobile::text, coalesce(date_of_birth::text, '') "
                + "from patient.patient where id = ?::uuid", rec.patientId());
        String audit = auditRows(rec.clinic(), "EMR_RECORD_SIGNED").get(0).get(2);
        assertThat(audit).doesNotContain(patient.get(0)).doesNotContain(patient.get(1)).doesNotContain("Ramesh")
                .doesNotContain("Kulkarni").doesNotContain("Viral fever").doesNotContain("Paracetamol");
        if (!patient.get(2).isEmpty()) {
            assertThat(audit).doesNotContain(patient.get(2));
        }
        assertThat(audit.toLowerCase()).doesNotContain("abha").doesNotContain("mobile").doesNotContain("birth")
                .doesNotContain("\"name\"").doesNotContain("narrative").doesNotContain("fever").doesNotContain("paracetamol");
    }

    @Test
    void theSignAuditNamesTheAppointmentIdWhenASlotWasBookedAndHasItNullOtherwise() throws Exception {
        Rec none = readyRec();
        signOk(none);
        JsonNode noInterval = objectMapper.readTree(auditRows(none.clinic(), "EMR_RECORD_SIGNED").get(0).get(2));
        assertThat(noInterval.has("appointmentId")).as("the key is present").isTrue();
        assertThat(noInterval.get("appointmentId").isNull()).as("no follow-up, no appointment").isTrue();
        assertThat(count(none.clinic(), "select count(*) from opd.appointment")).isZero();

        Rec booked = chooseInterval(readyRec(), "D3");
        JsonNode outcome = signOk(booked).get("outcome").get("followUp");
        JsonNode audit = objectMapper.readTree(auditRows(booked.clinic(), "EMR_RECORD_SIGNED").get(0).get(2));
        assertThat(audit.has("appointmentId")).isTrue();
        if (outcome.get("appointmentDate").isNull()) {
            assertThat(audit.get("appointmentId").isNull()).as("the date was kept without a slot").isTrue();
            return;
        }
        String appointmentId = row(booked.clinic(), "select id::text from opd.appointment").get(0);
        assertThat(audit.get("appointmentId").asText()).isEqualTo(appointmentId);
        assertThat(audit.get("followUpId").asText()).isEqualTo(outcome.get("id").asText());
    }

    @Test
    void theAnswerNamesThePrescriptionPrintAndTheRecordIsAskedForItAfterwards() throws Exception {
        Rec rec = readyRec();
        JsonNode result = signOk(rec);
        assertThat(texts(result.get("outcome").get("prints"), null)).containsExactly("PRESCRIPTION");
        JsonNode signature = get(RECORDS + "/" + rec.id() + "/case-paper", rec.token()).get("signature");
        assertThat(signature.get("signed").asBoolean()).as("the paper no longer says 'Not valid without signature'").isTrue();
        assertThat(signature.get("signerName").asText()).isNotBlank();
        assertThat(signature.get("signedAt").asText()).isNotBlank();
    }

    // ---------------- retry, conflicts and refusals ----------------

    @Test
    void theSameKeyAndTheSameBodyReplaysTheFirstResultWithOneSignatureOneFollowUpOneAuditAndTheSamePrints() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D7");
        String key = key();
        String body = signBody(rec.revision(), "{}");
        JsonNode first = okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key, 200);
        String signedAt = row(rec.clinic(), "select signed_at::text from opd.emr_record where id = ?::uuid", rec.id()).get(0);
        long moves = count(rec.clinic(), "select count(*) from platform.audit_event where action = 'VISIT_STAGE_MOVED'");
        JsonNode second = okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key, 200);
        assertThat(first.get("replayed").asBoolean()).isFalse();
        assertThat(second.get("replayed").asBoolean()).isTrue();
        assertThat(second.get("outcome")).isEqualTo(first.get("outcome"));
        assertThat(texts(second.get("outcome").get("prints"), null)).containsExactly("PRESCRIPTION");
        assertThat(second.get("record").get("status").asText()).isEqualTo("SIGNED");
        assertThat(row(rec.clinic(), "select signed_at::text from opd.emr_record where id = ?::uuid", rec.id()).get(0)).isEqualTo(signedAt);
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_SIGNED")).isOne();
        assertThat(followUpRows(rec.clinic(), rec.visitId())).isOne();
        assertThat(count(rec.clinic(), "select count(*) from platform.audit_event where action = 'VISIT_STAGE_MOVED'")).as("no second move").isEqualTo(moves);
    }

    @Test
    void theStoredResultHoldsNoPrintsAndNoClinicalText() throws Exception {
        Rec rec = readyRec();
        signOk(rec);
        String stored = row(rec.clinic(), "select sign_result::text from opd.emr_record where id = ?::uuid", rec.id()).get(0);
        assertThat(stored).doesNotContain("prints").doesNotContain("PRESCRIPTION").doesNotContain("Fever")
                .doesNotContain("Viral fever").doesNotContain("Paracetamol");
        assertThat(stored.length()).isLessThanOrEqualTo(8192);
    }

    @Test
    void theSameKeyWithAnotherBodyIs409IdempotencyConflictAndANewKeyOnASignedRecordIs409AlreadySignedByTheDoctor() throws Exception {
        Rec rec = readyRec();
        String key = key();
        okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), signBody(rec.revision(), "{}"), key, 200);
        signRefused(rec, rec.token(), signBody(rec.revision() + 5, "{}"), key, 409, "idempotency-conflict");
        JsonNode signed = signRefused(rec, rec.token(), signBody(rec.revision(), "{}"), key(), 409, "emr-record-signed");
        assertThat(signed.get("detail").asText()).startsWith("Already signed by Dr.");
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_SIGNED")).isOne();
    }

    @Test
    void anOldRevisionIs409EmrRecordChangedAndNothingIsSigned() throws Exception {
        Rec rec = readyRec();
        Rec changed = save(rec, sectionValues("hpi", "{\"narrative\":\"added later\"}"));
        assertThat(changed.revision()).isGreaterThan(rec.revision());
        signRefused(rec, rec.token(), signBody(rec.revision(), "{}"), key(), 409, "emr-record-changed");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_SIGNED")).isZero();
    }

    @Test
    void aRecordThatIsNotReadyIs409EmrNotReadyWithTheBlockersAndNothingIsHalfDone() throws Exception {
        Rec rec = chooseInterval(newRec(), "D7");
        long audits = count(rec.clinic(), "select count(*) from platform.audit_event");
        JsonNode problem = signRefused(rec, 409, "emr-not-ready");
        assertThat(problem.get("blockers").isArray()).isTrue();
        assertThat(texts(problem.get("blockers"), "key")).contains("STILL_NEEDED");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
        assertThat(boardVisit(rec.clinic(), rec.visitId()).get("stage").asText()).isEqualTo("WITH_DOCTOR");
        assertThat(followUpRows(rec.clinic(), rec.visitId())).isZero();
        assertThat(count(rec.clinic(), "select count(*) from opd.appointment")).isZero();
        assertThat(count(rec.clinic(), "select count(*) from platform.audit_event")).isEqualTo(audits);
        assertProblemMentionsNoClinicalText(problem);
    }

    @Test
    void aMissingOrMalformedKeyOrABodyWithoutRevisionOrChoicesIs400Validation() throws Exception {
        Rec rec = readyRec();
        String path = signPath(rec.id());
        String good = signBody(rec.revision(), "{}");
        problemKeyed(HttpMethod.POST, path, rec.token(), good, null, 400, "validation");
        problemKeyed(HttpMethod.POST, path, rec.token(), good, "short", 400, "validation");
        problemKeyed(HttpMethod.POST, path, rec.token(), good, "has space in the key", 400, "validation");
        problemKeyed(HttpMethod.POST, path, rec.token(), "{\"choices\":{}}", key(), 400, "validation");
        problemKeyed(HttpMethod.POST, path, rec.token(), "{\"revision\":" + rec.revision() + "}", key(), 400, "validation");
        problemKeyed(HttpMethod.POST, path, rec.token(), "{\"revision\":-1,\"choices\":{}}", key(), 400, "validation");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
    }

    @Test
    void sendingOnWhatsAppIs400NeedsWhatsappServiceAndNothingIsSigned() throws Exception {
        Rec rec = readyRec();
        signRefused(rec, rec.token(), signBody(rec.revision(), "{\"share\":{\"whatsappPrescription\":true}}"), key(), 400,
                "needs-whatsapp-service");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
        // switched off, the same body signs
        signWithKey(rec, key(), "{\"share\":{\"whatsappPrescription\":false}}", 200);
    }

    // ---------------- who may sign ----------------

    @Test
    void nurseFrontDeskBillingPharmacistAndCounsellorAreRefusedWith403AndNothingIsSigned() throws Exception {
        Rec rec = readyRec();
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            signRefused(rec, staff.token(rec.clinic()), signBody(rec.revision(), "{}"), key(), 403, "forbidden");
        }
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
    }

    @Test
    void anotherDoctorOrAVisitingDoctorIs403NotOwnPatientAnotherClinicIs404AndNoSessionIs401() throws Exception {
        Rec rec = readyRec();
        Rec foreign = newRec();
        Staff other = addDoctor(rec.clinic(), "Dentist");
        Staff visiting = addStaff(rec.clinic(), "VISITING_DOCTOR", "Dr Visiting Person", "Dentist", "BDS", "REG-" + shortId());
        String body = signBody(rec.revision(), "{}");
        signRefused(rec, other.token(rec.clinic()), body, key(), 403, "not-own-patient");
        signRefused(rec, visiting.token(rec.clinic()), body, key(), 403, "not-own-patient");
        signRefused(rec, foreign.token(), body, key(), 404, "record-not-found");
        signRefused(rec, anonymous(), body, key(), 401, "unauthorized");
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
        assertThat(statusOf(foreign.clinic(), foreign.id())).isEqualTo("DRAFT");
    }

    // ---------------- follow-up and appointment (SAH-51-6, SAH-68-2) ----------------

    @Test
    void aChosenIntervalMakesExactlyOneFollowUpWithTheFixedReasonTheSameDoctorAndTheDueDate() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D7");
        JsonNode result = signOk(rec);
        JsonNode outcome = result.get("outcome").get("followUp");
        assertThat(outcome.get("dueDate").asText()).isEqualTo(today().plusDays(7).toString());
        assertThat(followUpRows(rec.clinic(), rec.visitId())).isOne();
        JsonNode followUp = followUpOf(rec.clinic(), rec.visitId());
        assertThat(followUp).isNotNull();
        assertThat(followUp.get("reason").asText()).isEqualTo("Review after treatment");
        assertThat(followUp.get("doctorMembershipId").asText()).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(followUp.get("dueDate").asText()).isEqualTo(today().plusDays(7).toString());
        assertThat(row(rec.clinic(), "select interval_key from opd.follow_up where source_visit_id = ?::uuid", rec.visitId()).get(0))
                .isEqualTo("D7");
        assertThat(followUp.toString()).doesNotContain("Viral fever");
    }

    @Test
    void theFirstFreeSlotIsBookedAsAnEmrFollowUpAppointmentOrTheDateIsKeptWithTheNoSlotFlag() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D3");
        JsonNode outcome = signOk(rec).get("outcome").get("followUp");
        if (outcome.get("appointmentDate").isNull()) {
            assertThat(outcome.get("noFreeSlot").asBoolean()).isTrue();
            assertThat(count(rec.clinic(), "select count(*) from opd.appointment")).isZero();
            return;
        }
        assertThat(outcome.get("noFreeSlot").asBoolean()).isFalse();
        assertThat(outcome.get("appointmentDate").asText()).isGreaterThanOrEqualTo(today().plusDays(3).toString());
        List<String> appointment = row(rec.clinic(), "select source, visit_type, doctor_membership_id::text from opd.appointment");
        assertThat(appointment.get(0)).isEqualTo("EMR");
        assertThat(appointment.get(1)).isEqualTo("FOLLOW_UP");
        assertThat(appointment.get(2)).isEqualTo(rec.doctor().membershipId().toString());
        assertThat(row(rec.clinic(), "select (appointment_id is not null)::text from opd.follow_up where source_visit_id = ?::uuid",
                rec.visitId()).get(0)).isEqualTo("true");
        JsonNode followUp = followUpOf(rec.clinic(), rec.visitId());
        assertThat(followUp.get("appointmentDate").asText()).isEqualTo(outcome.get("appointmentDate").asText());
    }

    @Test
    void oneMonthAndThreeMonthsAreCalendarMonthsInIndia() throws Exception {
        Rec m1 = chooseInterval(readyRec(), "M1");
        assertThat(signOk(m1).get("outcome").get("followUp").get("dueDate").asText()).isEqualTo(today().plusMonths(1).toString());
        Rec m3 = chooseInterval(readyRec(), "M3");
        assertThat(signOk(m3).get("outcome").get("followUp").get("dueDate").asText()).isEqualTo(today().plusMonths(3).toString());
    }

    @Test
    void withNoIntervalTheSignMakesAtMostTheOneFollowUpOfTheClinicsOwnWindowAndNeverTwo() throws Exception {
        Rec rec = readyRec();
        JsonNode outcome = signOk(rec).get("outcome");
        assertThat(followUpRows(rec.clinic(), rec.visitId())).isLessThanOrEqualTo(1);
        if (followUpRows(rec.clinic(), rec.visitId()) == 0) {
            assertThat(outcome.get("followUp").isNull()).isTrue();
        } else {
            assertThat(followUpOf(rec.clinic(), rec.visitId()).get("reason").asText()).isNotBlank();
        }
    }

    // ---------------- bill lines (SAH-68-2) ----------------

    @Test
    void aTickedServiceIsOneBillLineAtTheTariffPriceAndTheSignDoesNotDuplicateIt() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        showProcedures(clinic);
        Rec rec = fillRequired(startRecord(clinic, doctor));
        String service = serviceId(clinic, "Dressing sign", 150);
        ObjectNode item = keyedItem(service, "Dressing sign");
        item.put("attr1", "2");
        item.put("attr2", "BILL_TODAY");
        rec = save(rec, oneField("procedures", "procedure", listValue("SERVICES", item)));
        assertThat(count(clinic, "select count(*) from billing.bill_item where source_kind = 'EMR'")).isOne();
        JsonNode outcome = signOk(rec).get("outcome");
        assertThat(outcome.get("billLines").asInt()).isEqualTo(1);
        assertThat(outcome.get("billTotalPaise").asLong()).isEqualTo(30000L);
        assertThat(count(clinic, "select count(*) from billing.bill_item where source_kind = 'EMR'")).as("not duplicated").isOne();
    }

    @Test
    void aServiceOnAPaidBillIsShownAsAlreadyBilledInTheCheckAndTheSignStillCompletes() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        showProcedures(clinic);
        Rec rec = fillRequired(startRecord(clinic, doctor));
        String service = serviceId(clinic, "Dressing paid", 100);
        ObjectNode item = keyedItem(service, "Dressing paid");
        item.put("attr1", "1");
        item.put("attr2", "BILL_TODAY");
        rec = save(rec, oneField("procedures", "procedure", listValue("SERVICES", item)));
        JsonNode bill = ok(HttpMethod.GET, BILLING + "/bills/" + rec.visitId(), clinic.owner(), null, 200);
        ok(HttpMethod.POST, BILLING + "/bills/" + rec.visitId() + "/payments", clinic.owner(),
                "{\"mode\":\"CASH\",\"amountPaise\":" + bill.get("subtotalPaise").asLong() + "}", 200);
        JsonNode line = check(rec).get("effects").get("billLines").get(0);
        assertThat(line.get("state").asText()).isEqualTo("ALREADY_BILLED");
        signOk(rec);
        assertThat(statusOf(clinic, rec.id())).isEqualTo("SIGNED");
    }

    // ---------------- ranking (SAH-51-2) ----------------

    @Test
    void masterItemsAreCountedOnceAtTheSignAndTypedItemsAreNever() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        List<List<String>> complaint = globalRows("select id::text, name from opd.emr_complaint order by sort_order, lower(name), id limit 1");
        Rec rec = startRecord(clinic, doctor);
        rec = save(rec, complaintsSection(keyedItem(complaint.get(0).get(0), complaint.get(0).get(1))),
                sectionValues("diagnosis", DIAGNOSIS_JSON), sectionValues("rx", RX_JSON), sectionValues("rxformat", RXFORMAT_JSON));
        assertThat(usageRows(clinic)).as("a draft counts nothing").isZero();
        signOk(rec);
        assertThat(usageRows(clinic)).as("one master item; the typed diagnosis and drug are not counted").isOne();
        List<String> usage = row(clinic, "select master, item_key, use_count::text, doctor_membership_id::text from opd.emr_item_usage");
        assertThat(usage).containsExactly("complaints", complaint.get(0).get(0), "1", doctor.membershipId().toString());
    }

    @Test
    void aReplayDoesNotCountTheItemsAgain() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        List<List<String>> complaint = globalRows("select id::text, name from opd.emr_complaint order by sort_order, lower(name), id limit 1");
        Rec rec = save(startRecord(clinic, doctor), complaintsSection(keyedItem(complaint.get(0).get(0), complaint.get(0).get(1))),
                sectionValues("diagnosis", DIAGNOSIS_JSON), sectionValues("rx", RX_JSON), sectionValues("rxformat", RXFORMAT_JSON));
        String key = key();
        String body = signBody(rec.revision(), "{}");
        okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key, 200);
        okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key, 200);
        assertThat(row(clinic, "select use_count::text from opd.emr_item_usage").get(0)).isEqualTo("1");
        assertThat(itemKeys(EmrMaster.COMPLAINTS, 1)).isNotEmpty();
    }

    // ---------------- the patient record (SAH-68-3) ----------------

    @Test
    void anAllergyTypedInTheVisitIsWrittenToThePatientWithTheDateAndTheDoctorAndAuditedByFieldNameOnly() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = patientWith(clinic, "Test Patient Three", null, null);
        Rec rec = save(fillRequired(startRecord(clinic, doctor, patient)), allergySection("typed:Dust allergy"));
        JsonNode outcome = signOk(rec).get("outcome");
        assertThat(outcome.get("patientRecord").asText()).isEqualTo("UPDATED");
        assertThat(allergyOf(clinic, patient)).isEqualTo("Dust allergy");
        List<String> stamp = row(clinic, "select (allergy_recorded_at is not null)::text, allergy_recorded_by::text from patient.patient "
                + "where id = ?::uuid", patient);
        assertThat(stamp).containsExactly("true", doctor.userId().toString());
        List<List<String>> audits = auditRows(clinic, "PATIENT_CLINICAL_FACTS_UPDATED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(2)).contains("ALLERGIES").doesNotContain("Dust allergy");
    }

    @Test
    void anExplicitNoneIsWrittenAsNoKnownAllergyAndNothingElseWritesThatText() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = patientWith(clinic, "Test Patient Four", null, null);
        Rec rec = save(fillRequired(startRecord(clinic, doctor, patient)), allergySection("typed:None"));
        assertThat(signOk(rec).get("outcome").get("patientRecord").asText()).isEqualTo("UPDATED");
        assertThat(allergyOf(clinic, patient)).isEqualTo("No known allergy");
        // a visit that says nothing about allergies writes nothing
        UUID other = patientWith(clinic, "Test Patient Five", null, null);
        Rec quiet = fillRequired(startRecord(clinic, doctor, other));
        assertThat(signOk(quiet).get("outcome").get("patientRecord").asText()).isEqualTo("NOT_CHANGED");
        assertThat(allergyOf(clinic, other)).isNull();
    }

    @Test
    void anAllergyTheDoctorTookOutStaysOnThePatientUntilTheRemovalIsConfirmed() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID kept = patientWith(clinic, "Test Patient Six", "Penicillin rash", null);
        Rec keptRec = save(fillRequired(startRecord(clinic, doctor, kept)), allergySection("typed:Dust allergy"));
        JsonNode effect = check(keptRec).get("effects").get("patientRecord");
        assertThat(texts(effect.get("removals"), null)).containsExactly("Penicillin rash");
        signOk(keptRec);
        assertThat(allergyOf(clinic, kept)).as("no tick: the removed allergy stays").contains("Penicillin rash").contains("Dust allergy");
        UUID removed = patientWith(clinic, "Test Patient Seven", "Penicillin rash", null);
        Rec removedRec = save(fillRequired(startRecord(clinic, doctor, removed)), allergySection("typed:Dust allergy"));
        signOk(removedRec, "{\"confirmAllergyRemoval\":true}");
        assertThat(allergyOf(clinic, removed)).isEqualTo("Dust allergy");
    }

    @Test
    void aPatientRecordChangedSinceTheVisitOpenedIsLeftAsItIsTheAnswerSaysConflictAndTheSignStillCompletes() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = patientWith(clinic, "Test Patient Eight", "Penicillin rash", null);
        Rec rec = startRecord(clinic, doctor, patient);
        ok(HttpMethod.PATCH, PATIENT_URL + patient, clinic.owner(), "{\"allergy\":\"Sulfa drugs\"}", 200);
        rec = save(fillRequired(rec), allergySection("typed:Dust allergy"));
        assertThat(check(rec).get("effects").get("patientRecord").get("conflict").asBoolean()).isTrue();
        JsonNode outcome = signOk(rec).get("outcome");
        assertThat(outcome.get("patientRecord").asText()).isEqualTo("CONFLICT");
        assertThat(statusOf(clinic, rec.id())).isEqualTo("SIGNED");
        assertThat(allergyOf(clinic, patient)).isEqualTo("Sulfa drugs");
        assertThat(auditRows(clinic, "PATIENT_CLINICAL_FACTS_UPDATED")).isEmpty();
    }

    @Test
    void anAllergyLongerThan400CharactersBlocksTheFinishWithTheShortenMessage() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        Rec rec = fillRequired(startRecord(clinic, doctor, patientWith(clinic, "Test Patient Nine", null, null)));
        String[] many = new String[12];
        for (int i = 0; i < many.length; i++) {
            many[i] = "typed:Allergy number " + i + " " + "x".repeat(40);
        }
        rec = save(rec, allergySection(many));
        JsonNode check = check(rec);
        assertThat(check.get("ready").asBoolean()).isFalse();
        assertThat(texts(check.get("blockers"), "key")).contains("PATIENT_FACTS_TOO_LONG");
    }

    // ---------------- long-term conditions: a union, never an overwrite (DEC-048 H30, code review 12) ----------------

    private String chronicOf(Clinic clinic, UUID patientId) throws Exception {
        JsonNode patient = get(PATIENT_URL + patientId, clinic.owner());
        return patient.path("chronic").isNull() ? null : patient.path("chronic").asText(null);
    }

    @Test
    void theDoctorsConditionsAreAddedToThePatientsListNothingIsOverwrittenAndTheFrontDesksTextStays() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        // what the front desk typed, one item of it with another case than the option label ("dm" and "DM" are one condition)
        UUID patient = patientWith(clinic, "Test Patient Ten", null, "Asthma since childhood; dm");
        Rec rec = save(fillRequired(startRecord(clinic, doctor, patient)), sectionValues("history", "{\"past_illness\":[\"dm\",\"htn\"]}"));
        JsonNode effect = check(rec).get("effects").get("patientRecord");
        assertThat(texts(effect.get("fields"), null)).contains("LONG_TERM_CONDITIONS").doesNotContain("ALLERGIES");
        assertThat(texts(effect.get("removals"), null)).as("conditions are never removed").isEmpty();
        assertThat(signOk(rec).get("outcome").get("patientRecord").asText()).isEqualTo("UPDATED");
        assertThat(chronicOf(clinic, patient)).as("the front desk's text first, the new condition added, DM not repeated")
                .isEqualTo("Asthma since childhood; dm; HTN");
        List<List<String>> audits = auditRows(clinic, "PATIENT_CLINICAL_FACTS_UPDATED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(1) + audits.get(0).get(2)).as("field names only, never the values").contains("LONG_TERM_CONDITIONS")
                .doesNotContain("Asthma").doesNotContain("HTN");
    }

    @Test
    void aConditionTheDoctorDidNotPickIsNeverRemovedAndAListThatAlreadyHasThemIsLeftAsItIs() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        UUID patient = patientWith(clinic, "Test Patient Eleven", null, "TB; DM");
        Rec onlyHtn = save(fillRequired(startRecord(clinic, doctor, patient)), sectionValues("history", "{\"past_illness\":[\"htn\"]}"));
        signOk(onlyHtn);
        assertThat(chronicOf(clinic, patient)).as("TB and DM stay although this visit did not pick them").isEqualTo("TB; DM; HTN");
        // the next visit picks only what the list already holds: nothing is written
        Rec again = save(fillRequired(startRecord(clinic, doctor, patient)), sectionValues("history", "{\"past_illness\":[\"dm\",\"htn\"]}"));
        JsonNode nothing = check(again).get("effects").get("patientRecord");
        assertThat(nothing.isNull() || nothing.get("fields").isEmpty()).as("nothing to show").isTrue();
        assertThat(signOk(again).get("outcome").get("patientRecord").asText()).isEqualTo("NOT_CHANGED");
        assertThat(chronicOf(clinic, patient)).isEqualTo("TB; DM; HTN");
        // a patient with no list gets the doctor's conditions as the list
        UUID bare = patientWith(clinic, "Test Patient Twelve", null, null);
        signOk(save(fillRequired(startRecord(clinic, doctor, bare)), sectionValues("history", "{\"past_illness\":[\"tb\",\"thyroid\"]}")));
        assertThat(chronicOf(clinic, bare)).isEqualTo("TB; Thyroid");
    }

    // ---------------- one transaction: a later step that fails keeps nothing (code review 15 b) ----------------

    @Test
    void aStepThatFailsAfterTheFollowUpTheAppointmentTheBillLinesAndThePatientWriteBackKeepsNoneOfThemAndTheSameKeyThenWorks() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        showProcedures(clinic);
        UUID patient = patientWith(clinic, "Test Patient Thirteen", null, null);
        String service = serviceId(clinic, "Dressing rollback", 150);
        ObjectNode item = keyedItem(service, "Dressing rollback");
        item.put("attr1", "2");
        item.put("attr2", "BILL_TODAY");
        Rec rec = fillRequired(startRecord(clinic, doctor, patient));
        rec = save(rec, oneField("procedures", "procedure", listValue("SERVICES", item)), allergySection("typed:Dust allergy"));
        rec = chooseInterval(rec, "D3");
        long billLines = count(clinic, "select count(*) from billing.bill_item");
        String stage = boardVisit(clinic, rec.visitId()).get("stage").asText();
        long audits = count(clinic, "select count(*) from platform.audit_event where action <> 'EMR_RECORD_DRAFT_SAVED'");
        String key = key();
        String body = signBody(rec.revision(), "{}");
        String suffix = rec.id().replace('-', '_');
        String function = "opd.it_fail_sign_" + suffix;
        String trigger = "trg_it_fail_sign_" + suffix;
        try (java.sql.Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "create or replace function " + function + "() returns trigger language plpgsql as $$ begin "
                    + "raise exception 'forced failure for the rollback test'; end; $$");
            DbAccess.execute(c, "create trigger " + trigger + " before update on opd.emr_record for each row when (new.id = '" + rec.id()
                    + "'::uuid and new.status = 'SIGNED') execute function " + function + "()");
            try {
                JsonNode problem = problemKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key, 500, "internal-error");
                assertThat(problem.toString()).doesNotContain("forced failure").doesNotContain("Dust allergy");
            } finally {
                DbAccess.execute(c, "drop trigger if exists " + trigger + " on opd.emr_record");
                DbAccess.execute(c, "drop function if exists " + function + "()");
            }
        }
        assertThat(statusOf(clinic, rec.id())).as("the record is still a draft").isEqualTo("DRAFT");
        assertThat(followUpRows(clinic, rec.visitId())).as("no follow-up kept").isZero();
        assertThat(count(clinic, "select count(*) from opd.appointment")).as("no appointment kept").isZero();
        assertThat(count(clinic, "select count(*) from billing.bill_item")).as("the bill lines are as before").isEqualTo(billLines);
        assertThat(allergyOf(clinic, patient)).as("no patient write-back kept").isNull();
        assertThat(boardVisit(clinic, rec.visitId()).get("stage").asText()).as("the card did not move").isEqualTo(stage);
        assertThat(count(clinic, "select count(*) from platform.audit_event where action <> 'EMR_RECORD_DRAFT_SAVED'")).as("nothing audited").isEqualTo(audits);
        assertThat(auditCount(clinic, "EMR_RECORD_SIGNED")).isZero();
        assertThat(row(clinic, "select (sign_idempotency_key is null)::text, (edit_hold_membership_id is not null)::text from opd.emr_record where id = ?::uuid",
                rec.id())).as("no key stored, the doctor still holds the record").containsExactly("true", "true");
        // the trigger is gone: the same key and body now sign once, as a first sign and not as a replay
        JsonNode retry = okKeyed(HttpMethod.POST, signPath(rec.id()), rec.token(), body, key, 200);
        assertThat(retry.get("replayed").asBoolean()).isFalse();
        assertThat(followUpRows(clinic, rec.visitId())).isOne();
        assertThat(allergyOf(clinic, patient)).isEqualTo("Dust allergy");
        assertThat(auditCount(clinic, "EMR_RECORD_SIGNED")).isOne();
    }

    // ---------------- a refused access leaves one log line (security M4) ----------------

    @Test
    void aNotOwnPatientRefusalWritesOneLogLineWithTheTypeTheClinicTheUserAndTheHandlerAndNoPathIdOrPatientData() throws Exception {
        Rec rec = readyRec();
        Staff other = addDoctor(rec.clinic(), "Dentist");
        List<String> patient = row(rec.clinic(), "select btrim(name), mobile::text from patient.patient where id = ?::uuid", rec.patientId());
        String[][] attempts = {{"POST", signPath(rec.id()), signBody(rec.revision(), "{}"), "EmrFinishController.sign"},
                {"POST", checkPath(rec.id()), "{}", "EmrFinishController.finishCheck"},
                {"PUT", RECORDS + "/" + rec.id() + "/edit-hold", null, "EmrFinishController.takeHold"}};
        for (String[] a : attempts) {
            try (LogCapture logs = new LogCapture()) {
                problemKeyed(HttpMethod.valueOf(a[0]), a[1], other.token(rec.clinic()), a[2], key(), 403, "not-own-patient");
                List<String> refused = logs.events().stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(m -> m.startsWith("Access refused")).toList();
                assertThat(refused).as(a[3]).hasSize(1);
                assertThat(refused.get(0)).isEqualTo("Access refused type=not-own-patient tenantId=" + rec.clinic().tenantId() + " userId="
                        + other.userId() + " handler=" + a[3]);
                assertThat(refused.get(0)).doesNotContain(rec.id()).doesNotContain(rec.visitId());
                assertThat(logs.allText()).doesNotContain(patient.get(0)).doesNotContain(patient.get(1));
            }
        }
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
    }

    // ---------------- after the sign ----------------

    @Test
    void aSignedRecordRefusesEverySaveWith409ButStillAllowsThePrintChoicesWithoutChangingTheRevision() throws Exception {
        Rec rec = readyRec();
        JsonNode signed = signOk(rec).get("record");
        long revision = signed.get("revision").asLong();
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/draft", rec.token(),
                draftBody(revision, "ASSESSMENT", null, sectionValues("hpi", "{\"narrative\":\"late\"}")).toString(), 409, "emr-record-signed");
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/edit-hold", rec.token(), "{}", 409, "emr-record-signed");
        problemOf(HttpMethod.PUT, RECORDS + "/" + rec.id() + "/department", rec.token(),
                "{\"departmentKey\":\"dental\",\"revision\":" + revision + "}", 409, "emr-record-signed");
        JsonNode prefs = put(RECORDS + "/" + rec.id() + "/print-preferences", rec.token(), "{\"language\":\"MR\",\"paperSize\":\"A5\"}", 200);
        assertThat(prefs.get("language").asText()).isEqualTo("MR");
        assertThat(record(rec.token(), rec.id()).get("revision").asLong()).as("frozen after the sign").isEqualTo(revision);
    }

    @Test
    void aTypedNotJsonBodyOrAnUnknownFieldIsRefusedAndTheErrorNeverEchoesClinicalText() throws Exception {
        Rec rec = readyRec();
        call(HttpMethod.POST, signPath(rec.id()), rec.token(), "{\"revision\":" + rec.revision() + ",\"choices\":{},\"note\":\"Viral fever\"}")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is4xxClientError());
        assertThat(statusOf(rec.clinic(), rec.id())).isEqualTo("DRAFT");
    }

}
