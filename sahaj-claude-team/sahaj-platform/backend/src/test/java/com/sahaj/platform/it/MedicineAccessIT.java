package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-31 who may do what, and the gates, on the real {@code sahaj_test} database: the seven endpoints of docs/api/ipd.yaml
 * (EMR, order, stop, verify, reject, pharmacist list and summary): each role's exact 403 text, the role in the token and
 * the role of the active membership both checked, a role change applying at once, IPD Off and Read-only, another clinic's
 * data invisible, and every 400. A refused call writes nothing. Test plan: docs/test-plans/SAH-31.md.
 */
@OnTestDatabase
@WithPinPepper
class MedicineAccessIT extends MedicineSupport {

    private static final String NONE_KNOWN = "None known";

    private JsonNode refusedWith(org.springframework.test.web.servlet.ResultActions actions, int status, String type,
                                 String detail) throws Exception {
        JsonNode p = problem(actions, status);
        assertType(p, type);
        if (detail != null) {
            assertThat(p.get("detail").asText()).isEqualTo(detail);
        }
        return p;
    }

    // ---------------- exact 403 texts per role ----------------

    @Test
    void emrIsOpenToOwnerDoctorNurseAndEveryOtherRoleGets403ClinicalDetailsAreHiddenWithNothingWritten() throws Exception {
        World w = world(NONE_KNOWN);
        MedSnap before = medSnap(w.clinic(), w.adm());
        for (String role : NON_EMR_ROLES) {
            refusedWith(call(HttpMethod.GET, emrUrl(w.adm().id()), staff(w.clinic(), role).auth(), null), 403, "forbidden",
                    EMR_HIDDEN);
        }
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(before);
        for (RequestPostProcessor allowed : new RequestPostProcessor[] {w.owner().auth(), w.doctor().auth(), w.nurse().auth()}) {
            assertThat(emr(allowed, w.adm().id()).get("admissionId").asText()).isEqualTo(w.adm().id());
        }
        // the three openings wrote one view event and one access row each
        assertThat(medSnap(w.clinic(), w.adm()).events()).isEqualTo(before.events() + 3);
        assertThat(medSnap(w.clinic(), w.adm()).access()).isEqualTo(before.access() + 3);
    }

    @Test
    void orderAndStopAreForOwnerAndDoctorOnlyAndEveryOtherRoleGets403OnlyADoctorCanDoThis() throws Exception {
        World w = world(NONE_KNOWN);
        String orderId = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        MedSnap before = medSnap(w.clinic(), w.adm());
        for (String role : NON_ORDER_ROLES) {
            RequestPostProcessor auth = staff(w.clinic(), role).auth();
            refusedWith(orderCall(auth, w.adm().id(), "panto"), 403, "forbidden", ONLY_DOCTOR);
            refusedWith(call(HttpMethod.POST, stopUrl(orderId), auth, null), 403, "forbidden", ONLY_DOCTOR);
        }
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(before);
        assertThat(state(w.clinic(), orderId).status()).isEqualTo("PENDING");
        // the owner and a doctor may
        place(w.owner().auth(), w.adm().id(), "panto");
        stopOk(w.doctor().auth(), orderId);
    }

    @Test
    void verifyRejectListAndSummaryAreForPharmacistAndOwnerOnlyAndEveryOtherRoleGets403WithNoPinTry() throws Exception {
        World w = world(NONE_KNOWN);
        String orderId = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        MedSnap before = medSnap(w.clinic(), w.adm());
        for (String role : NON_CHECK_ROLES) {
            RequestPostProcessor auth = staff(w.clinic(), role).auth();
            refusedWith(call(HttpMethod.POST, verifyUrl(orderId), auth, verifyBody(w.pin(), "AAAAAAAAAAAAAAAAAAAAAA")), 403,
                    "forbidden", ONLY_PHARMACIST);
            refusedWith(rejectCall(auth, orderId, "WRONG_DOSE"), 403, "forbidden", ONLY_PHARMACIST);
            refusedWith(call(HttpMethod.GET, CHECKS, auth, null), 403, "forbidden", ONLY_PHARMACIST);
            refusedWith(call(HttpMethod.GET, CHECKS_SUMMARY, auth, null), 403, "forbidden", ONLY_PHARMACIST);
        }
        assertThat(medSnap(w.clinic(), w.adm())).as("nothing written, no PIN row touched").isEqualTo(before);
        // the pharmacist and the owner may read
        assertThat(queue(w.pharmacist().auth()).get("waiting").asInt()).isOne();
        assertThat(queue(w.owner().auth()).get("waiting").asInt()).isOne();
        assertThat(summary(w.pharmacist().auth()).get("waiting").asInt()).isOne();
        assertThat(summary(w.owner().auth()).get("waiting").asInt()).isOne();
    }

    @Test
    void withoutASessionEverySevenEndpointsAnswers401() throws Exception {
        String id = UUID.randomUUID().toString();
        assertType(problem(call(HttpMethod.GET, emrUrl(id), noSession(), null), 401), "unauthorized");
        assertType(problem(call(HttpMethod.POST, ordersUrl(id), noSession(), "{}"), 401), "unauthorized");
        assertType(problem(call(HttpMethod.POST, stopUrl(id), noSession(), null), 401), "unauthorized");
        assertType(problem(call(HttpMethod.POST, verifyUrl(id), noSession(), "{}"), 401), "unauthorized");
        assertType(problem(call(HttpMethod.POST, rejectUrl(id), noSession(), "{}"), 401), "unauthorized");
        assertType(problem(call(HttpMethod.GET, CHECKS, noSession(), null), 401), "unauthorized");
        assertType(problem(call(HttpMethod.GET, CHECKS_SUMMARY, noSession(), null), 401), "unauthorized");
    }

    // ---------------- token role AND membership role ----------------

    @Test
    void theRoleInTheTokenAndTheRoleOfTheActiveMembershipAreBothChecked() throws Exception {
        World w = world(NONE_KNOWN);
        String orderId = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        MedSnap before = medSnap(w.clinic(), w.adm());
        // a nurse whose token claims DOCTOR or OWNER
        Staff nurse = staff(w.clinic(), "NURSE");
        for (String claim : new String[] {"DOCTOR", "OWNER"}) {
            RequestPostProcessor forged = tokenOf(w.clinic(), nurse.userId(), claim);
            refusedWith(orderCall(forged, w.adm().id(), "panto"), 403, "forbidden", ONLY_DOCTOR);
            refusedWith(call(HttpMethod.POST, stopUrl(orderId), forged, null), 403, "forbidden", ONLY_DOCTOR);
            refusedWith(call(HttpMethod.POST, verifyUrl(orderId), forged, verifyBody("4827", "AAAAAAAAAAAAAAAAAAAAAA")), 403,
                    "forbidden", ONLY_PHARMACIST);
            refusedWith(rejectCall(forged, orderId, "WRONG_DOSE"), 403, "forbidden", ONLY_PHARMACIST);
            refusedWith(call(HttpMethod.GET, CHECKS, forged, null), 403, "forbidden", ONLY_PHARMACIST);
            refusedWith(call(HttpMethod.GET, CHECKS_SUMMARY, forged, null), 403, "forbidden", ONLY_PHARMACIST);
        }
        // a front desk person whose token claims OWNER cannot open the EMR
        Staff desk = staff(w.clinic(), "FRONT_DESK");
        refusedWith(call(HttpMethod.GET, emrUrl(w.adm().id()), tokenOf(w.clinic(), desk.userId(), "OWNER"), null), 403,
                "forbidden", EMR_HIDDEN);
        // a doctor whose token claims PHARMACIST cannot check
        RequestPostProcessor doctorAsPharmacist = tokenOf(w.clinic(), w.doctor().userId(), "PHARMACIST");
        refusedWith(call(HttpMethod.GET, CHECKS, doctorAsPharmacist, null), 403, "forbidden", ONLY_PHARMACIST);
        refusedWith(rejectCall(doctorAsPharmacist, orderId, "WRONG_DOSE"), 403, "forbidden", ONLY_PHARMACIST);
        assertThat(medSnap(w.clinic(), w.adm()).orders()).isEqualTo(before.orders());
        assertThat(medSnap(w.clinic(), w.adm()).audits()).isEqualTo(before.audits());
        assertThat(medSnap(w.clinic(), w.adm()).pinRows()).isEqualTo(before.pinRows());
    }

    @Test
    void aRoleChangeAppliesAtOnceWhileTheOldTokenIsStillValid() throws Exception {
        World w = world(NONE_KNOWN);
        String orderId = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        // doctor -> nurse: ordering and stopping stop at once, reading the EMR stays allowed
        setMembershipRole(w.clinic(), w.doctor().userId(), "NURSE");
        refusedWith(orderCall(w.doctor().auth(), w.adm().id(), "panto"), 403, "forbidden", ONLY_DOCTOR);
        refusedWith(call(HttpMethod.POST, stopUrl(orderId), w.doctor().auth(), null), 403, "forbidden", ONLY_DOCTOR);
        emr(w.doctor().auth(), w.adm().id());
        // nurse -> front desk: the EMR is hidden
        setMembershipRole(w.clinic(), w.doctor().userId(), "FRONT_DESK");
        refusedWith(call(HttpMethod.GET, emrUrl(w.adm().id()), w.doctor().auth(), null), 403, "forbidden", EMR_HIDDEN);
        // back to doctor: everything works again
        setMembershipRole(w.clinic(), w.doctor().userId(), "DOCTOR");
        place(w.doctor().auth(), w.adm().id(), "panto");

        // pharmacist -> doctor: the pharmacist panel closes at once
        MedSnap before = medSnap(w.clinic(), w.adm());
        setMembershipRole(w.clinic(), w.pharmacist().userId(), "DOCTOR");
        refusedWith(call(HttpMethod.GET, CHECKS, w.pharmacist().auth(), null), 403, "forbidden", ONLY_PHARMACIST);
        refusedWith(call(HttpMethod.GET, CHECKS_SUMMARY, w.pharmacist().auth(), null), 403, "forbidden", ONLY_PHARMACIST);
        refusedWith(rejectCall(w.pharmacist().auth(), orderId, "WRONG_DOSE"), 403, "forbidden", ONLY_PHARMACIST);
        refusedWith(call(HttpMethod.POST, verifyUrl(orderId), w.pharmacist().auth(), verifyBody(w.pin(),
                "AAAAAAAAAAAAAAAAAAAAAA")), 403, "forbidden", ONLY_PHARMACIST);
        assertThat(medSnap(w.clinic(), w.adm()).pinRows()).as("no PIN try for a role that is gone").isEqualTo(before.pinRows());
        setMembershipRole(w.clinic(), w.pharmacist().userId(), "PHARMACIST");
        assertThat(queue(w.pharmacist().auth()).get("waiting").asInt()).isEqualTo(2);

        // a switched-off membership has no access at all
        setMembershipStatus(w.clinic().tenantId(), w.pharmacist().userId(), "INACTIVE");
        refusedWith(call(HttpMethod.GET, CHECKS, w.pharmacist().auth(), null), 403, "forbidden", ONLY_PHARMACIST);
        setMembershipStatus(w.clinic().tenantId(), w.doctor().userId(), "INACTIVE");
        refusedWith(orderCall(w.doctor().auth(), w.adm().id(), "ondan"), 403, "forbidden", ONLY_DOCTOR);
        refusedWith(call(HttpMethod.GET, emrUrl(w.adm().id()), w.doctor().auth(), null), 403, "forbidden", EMR_HIDDEN);
    }

    // ---------------- IPD Off and Read-only ----------------

    @Test
    void whenIpdIsOffEveryEndpointIsModuleOffForTheAllowedRolesAndTheRoleCheckComesFirst() throws Exception {
        Clinic off = newClinic(); // no trial: IPD is Off
        String id = UUID.randomUUID().toString();
        refusedWith(call(HttpMethod.GET, emrUrl(id), off.as("OWNER"), null), 403, "module-off", OFF_TEXT);
        refusedWith(call(HttpMethod.GET, emrUrl(id), off.as("NURSE"), null), 403, "module-off", OFF_TEXT);
        refusedWith(call(HttpMethod.POST, ordersUrl(id), off.as("DOCTOR"), "{}"), 403, "module-off", OFF_TEXT);
        refusedWith(call(HttpMethod.POST, stopUrl(id), off.as("OWNER"), null), 403, "module-off", OFF_TEXT);
        refusedWith(call(HttpMethod.POST, verifyUrl(id), off.as("PHARMACIST"), "{}"), 403, "module-off", OFF_TEXT);
        refusedWith(call(HttpMethod.POST, rejectUrl(id), off.as("OWNER"), "{}"), 403, "module-off", OFF_TEXT);
        refusedWith(call(HttpMethod.GET, CHECKS, off.as("PHARMACIST"), null), 403, "module-off", OFF_TEXT);
        refusedWith(call(HttpMethod.GET, CHECKS_SUMMARY, off.as("OWNER"), null), 403, "module-off", OFF_TEXT);
        // the role comes first
        refusedWith(call(HttpMethod.GET, emrUrl(id), off.as("FRONT_DESK"), null), 403, "forbidden", EMR_HIDDEN);
        refusedWith(call(HttpMethod.POST, ordersUrl(id), off.as("NURSE"), "{}"), 403, "forbidden", ONLY_DOCTOR);
        refusedWith(call(HttpMethod.GET, CHECKS, off.as("DOCTOR"), null), 403, "forbidden", ONLY_PHARMACIST);
    }

    @Test
    void inReadOnlyTheEmrOpensAndStopAndRejectStayAllowedWhileOrderAndVerifyAre409IpdReadonlyBeforeTheBodyIsLooked() throws Exception {
        World w = world(NONE_KNOWN);
        String toStop = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        String toReject = orderIdOf(place(w.doctor().auth(), w.adm().id(), "panto"));
        String toVerify = orderIdOf(place(w.doctor().auth(), w.adm().id(), "ondan"));
        expireTrial(w.clinic());

        JsonNode sheet = emr(w.doctor().auth(), w.adm().id());
        assertThat(sheet.get("state").asText()).isEqualTo("readonly");
        assertThat(sheet.get("canOrder").asBoolean()).isFalse();
        assertThat(sheet.get("orderLockReason").asText()).isEqualTo("IPD_READONLY");
        assertThat(sheetRow(sheet, toStop).get("canStop").asBoolean()).as("stop stays allowed").isTrue();

        MedSnap before = medSnap(w.clinic(), w.adm());
        refusedWith(call(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), "{}"), 409, "ipd-readonly", READONLY_TEXT);
        refusedWith(orderCall(w.doctor().auth(), w.adm().id(), "met"), 409, "ipd-readonly", READONLY_TEXT);
        refusedWith(call(HttpMethod.POST, verifyUrl(toVerify), w.pharmacist().auth(), "{}"), 409, "ipd-readonly", READONLY_TEXT);
        refusedWith(call(HttpMethod.POST, verifyUrl(toVerify), w.pharmacist().auth(), verifyBody(w.pin(),
                "AAAAAAAAAAAAAAAAAAAAAA")), 409, "ipd-readonly", READONLY_TEXT);
        assertThat(medSnap(w.clinic(), w.adm())).as("no PIN try either").isEqualTo(before);

        JsonNode list = queue(w.pharmacist().auth());
        assertThat(list.get("state").asText()).isEqualTo("readonly");
        JsonNode row = queueRow(list, toVerify);
        assertThat(row.get("canVerify").asBoolean()).isFalse();
        assertThat(row.get("verifyLockReason").asText()).isEqualTo("IPD_READONLY");
        assertThat(row.get("canReject").asBoolean()).isTrue();
        assertThat(summary(w.pharmacist().auth()).get("waiting").asInt()).isEqualTo(3);

        stopOk(w.doctor().auth(), toStop);
        rejectOk(w.pharmacist().auth(), toReject, "WRONG_DOSE");
        assertThat(state(w.clinic(), toStop).status()).isEqualTo("STOPPED");
        assertThat(state(w.clinic(), toReject).status()).isEqualTo("REJECTED");
        assertThat(state(w.clinic(), toVerify).status()).isEqualTo("PENDING");
    }

    // ---------------- other clinics ----------------

    @Test
    void anotherClinicsAdmissionsOrdersAndQueueAreInvisibleAndEveryCallAnswers404WithNoPinTry() throws Exception {
        World a = world(NONE_KNOWN);
        String orderId = orderIdOf(place(a.doctor().auth(), a.adm().id(), "pcm"));
        Clinic b = hospital();
        Staff bDoctor = staff(b, "DOCTOR");
        Staff bPharmacist = staff(b, "PHARMACIST");
        MedSnap before = medSnap(a.clinic(), a.adm());
        refusedWith(call(HttpMethod.GET, emrUrl(a.adm().id()), bDoctor.auth(), null), 404, "not-found", null);
        refusedWith(orderCall(bDoctor.auth(), a.adm().id(), "panto"), 404, "not-found", null);
        refusedWith(call(HttpMethod.POST, stopUrl(orderId), bDoctor.auth(), null), 404, "not-found", null);
        refusedWith(call(HttpMethod.POST, verifyUrl(orderId), bPharmacist.auth(), verifyBody("4827", "AAAAAAAAAAAAAAAAAAAAAA")),
                404, "not-found", null);
        refusedWith(rejectCall(bPharmacist.auth(), orderId, "WRONG_DOSE"), 404, "not-found", null);
        refusedWith(call(HttpMethod.GET, emrUrl(a.adm().id()), b.owner(), null), 404, "not-found", null);
        assertThat(medSnap(a.clinic(), a.adm())).isEqualTo(before);
        // B's queue and summary know nothing of A
        assertThat(queue(bPharmacist.auth()).get("waiting").asInt()).isZero();
        assertThat(queue(bPharmacist.auth()).get("items")).isEmpty();
        JsonNode s = summary(bPharmacist.auth());
        assertThat(s.get("waiting").asInt()).isZero();
        assertThat(s.get("changeToken").asText()).isEqualTo(EMPTY_TOKEN);
        // and A's queue knows nothing of B
        Adm inB = admitWithAllergy(b, NONE_KNOWN);
        place(bDoctor.auth(), inB.id(), "pcm");
        assertThat(queue(a.pharmacist().auth()).get("waiting").asInt()).isOne();
        assertThat(queue(bPharmacist.auth()).get("waiting").asInt()).isOne();
        // a clinic id forged in the query or body changes nothing
        assertThat(ok(HttpMethod.GET, CHECKS + "?tenantId=" + a.clinic().tenantId(), bPharmacist.auth(), null, 200)
                .get("waiting").asInt()).isOne();
    }

    // ---------------- every 400 ----------------

    @Test
    void orderBodyErrorsComeTogetherInTheContractOrderWithTheContractTexts() throws Exception {
        World w = world(NONE_KNOWN);
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode all = refusedWith(call(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(),
                "{\"clientKey\":\"x\",\"drugKey\":\"zzz\",\"frequencyKey\":\"qd\",\"overrideReasonKey\":\"OTHER\","
                        + "\"checkToken\":\"short\"}"), 400, "validation", RELOAD_TEXT);
        assertThat(fieldNames(all)).containsExactly("clientKey", "drugKey", "frequencyKey", "overrideReasonKey", "checkToken");
        assertThat(fieldMessage(all, "clientKey")).isEqualTo(RELOAD_TEXT);
        assertThat(fieldMessage(all, "drugKey")).isEqualTo("Choose a medicine from the list");
        assertThat(fieldMessage(all, "frequencyKey")).isEqualTo("Choose how often");
        assertThat(fieldMessage(all, "overrideReasonKey")).isEqualTo("Choose a reason from the list");
        assertThat(fieldMessage(all, "checkToken")).isEqualTo(RELOAD_TEXT);
        assertThat(fieldNames(problem(call(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), "{}"), 400)))
                .containsExactly("clientKey", "drugKey", "frequencyKey");
        for (String bad : new String[] {"{\"clientKey\":\"" + newKey() + "\",\"drugKey\":\"PCM\",\"frequencyKey\":\"od\"}",
                "{\"clientKey\":\"" + newKey() + "\",\"drugKey\":\"pcm\",\"frequencyKey\":\"OD\"}",
                "{\"clientKey\":\"" + newKey() + "\",\"drugKey\":\"pcm\",\"frequencyKey\":\"od\",\"checkToken\":\"" + "a".repeat(23)
                        + "\"}"}) {
            assertType(problem(call(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), bad), 400), "validation");
        }
        assertType(problem(call(HttpMethod.POST, ordersUrl(w.adm().id()), w.doctor().auth(), "{not json"), 400), "malformed-request");
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(before);
        // the body is checked before the admission is looked up
        assertType(problem(call(HttpMethod.POST, ordersUrl(UUID.randomUUID()), w.doctor().auth(), "{}"), 400), "validation");
        refusedWith(orderCall(w.doctor().auth(), UUID.randomUUID(), "pcm"), 404, "not-found", null);
    }

    @Test
    void verifyAndRejectBodyErrorsAreTheContractTextsAndComeBeforeAnyPinTryOrLookup() throws Exception {
        World w = world(NONE_KNOWN);
        String orderId = orderIdOf(place(w.doctor().auth(), w.adm().id(), "pcm"));
        MedSnap before = medSnap(w.clinic(), w.adm());
        JsonNode v = problem(call(HttpMethod.POST, verifyUrl(orderId), w.pharmacist().auth(),
                "{\"pin\":\"12a4\",\"checkToken\":\"x\"}"), 400);
        assertThat(fieldNames(v)).containsExactly("pin", "checkToken");
        assertThat(fieldMessage(v, "pin")).isEqualTo("Enter the 4-digit PIN.");
        assertThat(fieldMessage(v, "checkToken")).isEqualTo(RELOAD_TEXT);
        assertThat(fieldNames(problem(call(HttpMethod.POST, verifyUrl(orderId), w.pharmacist().auth(), "{}"), 400)))
                .containsExactly("pin", "checkToken");
        for (String pin : new String[] {"123", "12345", "١٢٣٤", " 123", ""}) {
            JsonNode p = problem(call(HttpMethod.POST, verifyUrl(orderId), w.pharmacist().auth(),
                    verifyBody(pin, "AAAAAAAAAAAAAAAAAAAAAA")), 400);
            assertThat(fieldMessage(p, "pin")).as(pin).isEqualTo("Enter the 4-digit PIN.");
        }
        // 400 before 404: an unknown order with a bad body is a 400
        assertType(problem(call(HttpMethod.POST, verifyUrl(UUID.randomUUID()), w.pharmacist().auth(), "{}"), 400), "validation");
        for (String reason : new String[] {null, "OTHER", "allergy_conflict", ""}) {
            JsonNode p = problem(rejectCall(w.pharmacist().auth(), orderId, reason), 400);
            assertThat(fieldNames(p)).containsExactly("reasonKey");
            assertThat(fieldMessage(p, "reasonKey")).isEqualTo("Choose a reason");
        }
        assertThat(fieldNames(problem(call(HttpMethod.POST, rejectUrl(orderId), w.pharmacist().auth(), "{}"), 400)))
                .containsExactly("reasonKey");
        assertThat(medSnap(w.clinic(), w.adm())).isEqualTo(before);
    }

    @Test
    void anUnknownOrderIsA404AndAnIdThatIsNoUuidIsA400ForStopVerifyAndReject() throws Exception {
        World w = world(NONE_KNOWN);
        UUID unknown = UUID.randomUUID();
        refusedWith(call(HttpMethod.POST, stopUrl(unknown), w.doctor().auth(), null), 404, "not-found", null);
        refusedWith(rejectCall(w.pharmacist().auth(), unknown.toString(), "WRONG_DOSE"), 404, "not-found", null);
        refusedWith(call(HttpMethod.POST, verifyUrl(unknown), w.pharmacist().auth(), verifyBody(w.pin(),
                "AAAAAAAAAAAAAAAAAAAAAA")), 404, "not-found", null);
        assertThat(pinRow(w.clinic(), w.pharmacist().membershipId()).failed()).as("no PIN try before the lookup").isZero();
        for (String url : new String[] {IPD + "/medicine-orders/not-a-uuid/stop", IPD + "/medicine-orders/not-a-uuid/verify",
                IPD + "/medicine-orders/not-a-uuid/reject", ADMISSIONS + "/not-a-uuid/emr"}) {
            JsonNode p = problem(call(url.endsWith("/emr") ? HttpMethod.GET : HttpMethod.POST, url,
                    w.owner().auth(), url.endsWith("/emr") || url.endsWith("/stop") ? null : "{}"), 400);
            assertThat(p.toString()).doesNotContain("Exception").doesNotContain("java.");
        }
        refusedWith(call(HttpMethod.GET, emrUrl(unknown), w.doctor().auth(), null), 404, "not-found", null);
    }
}
