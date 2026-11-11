package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-34 story 34B on the real {@code sahaj_test} database: with the clinic switch on, a nurse reaches only the patients whose
 * bed is in one of her live wards. The list, the bed board, the Safety board and the clinical events are narrowed; every guarded
 * read and write of another ward's patient answers 403 outside-scope with a fixed body and writes nothing; a nurse with no ward
 * sees nothing; the owner and the doctor are never narrowed; with the switch off every answer is as before SAH-34. The scope is
 * built on the server from the signed-in user, never from the request. Test plan: docs/test-plans/SAH-34.md.
 */
@OnTestDatabase
@WithPinPepper
class WardScopeEnforcementIT extends ScopeSupport {

    /** One guarded endpoint with a valid request for a patient and the roles that may use it. */
    private record Endpoint(String name, HttpMethod method, String url, String body, boolean nurseMayUse) {
    }

    private List<Endpoint> byIdEndpoints(Clinic clinic, Adm adm, String orderId, String escalationId) throws Exception {
        UUID freeBed = newBed(clinic, "gen");
        List<Endpoint> list = new ArrayList<>();
        list.add(new Endpoint("detail", HttpMethod.GET, ADMISSIONS + "/" + adm.id(), null, true));
        list.add(new Endpoint("nursing", HttpMethod.GET, nursingUrl(adm.id()), null, true));
        list.add(new Endpoint("emr", HttpMethod.GET, emrUrl(adm.id()), null, true));
        list.add(new Endpoint("mar", HttpMethod.GET, marUrl(adm.id()), null, true));
        list.add(new Endpoint("discharge sheet", HttpMethod.GET, dischargeUrl(adm.id()), null, true));
        list.add(new Endpoint("vitals", HttpMethod.POST, vitalsUrl(adm.id()), js(low()), true));
        list.add(new Endpoint("deposit", HttpMethod.POST, ADMISSIONS + "/" + adm.id() + "/deposits",
                "{\"amountPaise\":100000,\"mode\":\"Cash\"}", false));
        list.add(new Endpoint("charge", HttpMethod.POST, ADMISSIONS + "/" + adm.id() + "/charges", "{\"serviceKey\":\"ecg\"}", true));
        list.add(new Endpoint("transfer", HttpMethod.POST, transferUrl(adm.id()), moveBody(freeBed, null), true));
        list.add(new Endpoint("dose", HttpMethod.POST, dosesUrl(orderId), "{\"clientKey\":\"" + newKey() + "\",\"slotAt\":\""
                + ist(8, 0) + "\",\"outcome\":\"NOT_GIVEN\",\"reasonKey\":\"NIL_BY_MOUTH\"}", true));
        list.add(new Endpoint("call senior", HttpMethod.POST, callUrl(escalationId), "{\"version\":0,\"seniorCalls\":0}", true));
        // endpoints a nurse may not use at all: the role answer comes first, never outside-scope (AC 14)
        list.add(new Endpoint("order", HttpMethod.POST, ordersUrl(adm.id()), js(orderBody(newKey(), "Paracetamol 500 mg", "od", null,
                null)), false));
        list.add(new Endpoint("stop", HttpMethod.POST, stopUrl(orderId), null, false));
        list.add(new Endpoint("discharge", HttpMethod.POST, dischargeUrl(adm.id()), dischargeBody("normal", "general", 1L, 1L, "UPI"),
                false));
        list.add(new Endpoint("sign off", HttpMethod.PUT, signoffUrl(adm.id()), signBody("normal", "general"), false));
        list.add(new Endpoint("acknowledge", HttpMethod.POST, ackUrl(escalationId), "{\"version\":0}", false));
        list.add(new Endpoint("respond", HttpMethod.POST, respondUrl(escalationId), respondJson(0, "NO_CHANGE", "Reviewed, repeat"), false));
        return list;
    }

    // ---------------- the list ----------------

    @Test
    void aScopedNurseSeesOnlyThePatientsOfHerWardsAndTheScopeObjectSaysHowMany() throws Exception {
        Hosp h = hosp();
        JsonNode list = listOf(h.nurseA().auth());
        assertThat(idsOf(list)).containsExactly(h.inA().id());
        JsonNode scope = list.get("scope");
        assertThat(scope.get("kind").asText()).isEqualTo("WARDS");
        assertThat(scope.get("visible").asInt()).isOne();
        assertThat(scope.get("admitted").asInt()).isEqualTo(2);
        assertThat(scope.get("hasWard").asBoolean()).isTrue();
        assertThat(scope.get("canOpenEmergencyAccess").asBoolean()).isTrue();
        assertThat(list.get("items").get(0).get("mine").asBoolean()).as("her ward: mine").isTrue();
        assertThat(list.get("items").get(0).has("emergencyAccess")).isFalse();
        // the other nurse is the mirror image
        assertThat(idsOf(listOf(h.nurseB().auth()))).containsExactly(h.inB().id());
        // bed counts and the discharged-today count stay clinic-wide, and the wards select keeps every ward (OQ-34-22)
        assertThat(list.get("bedsOccupied").asInt()).isEqualTo(2);
        assertThat(list.get("wards")).hasSize(2);
        String raw = call(HttpMethod.GET, ADMISSIONS, h.nurseA().auth(), null).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(h.inB().name()).doesNotContain(h.inB().mobile()).doesNotContain(h.inB().id());
    }

    @Test
    void theScopeObjectIsPresentForEveryRoleAndMineFollowsTheRoleRules() throws Exception {
        Hosp h = hosp();
        Staff doctor = staff(h.clinic(), "DOCTOR");
        for (String role : READERS) {
            RequestPostProcessor auth = "NURSE".equals(role) ? h.nurseA().auth() : "DOCTOR".equals(role) ? doctor.auth()
                    : "FRONT_DESK".equals(role) ? newPn(h.clinic(), "FRONT_DESK").auth() : h.clinic().as(role);
            JsonNode list = listOf(auth);
            JsonNode scope = list.get("scope");
            assertThat(scope).as(role + " has scope").isNotNull();
            assertThat(scope.get("visible").asInt()).isEqualTo(list.get("items").size());
            assertThat(scope.get("admitted").asInt()).isEqualTo(2);
            if ("NURSE".equals(role)) {
                assertThat(scope.get("kind").asText()).isEqualTo("WARDS");
            } else {
                assertThat(scope.get("kind").asText()).as(role).isEqualTo("ALL");
                assertThat(scope.has("hasWard")).as(role + ": hasWard is for a nurse only").isFalse();
                assertThat(scope.get("canOpenEmergencyAccess").asBoolean()).isFalse();
                assertThat(list.get("items")).as(role + " sees everyone").hasSize(2);
            }
            for (JsonNode r : list.get("items")) {
                assertThat(r.has("emergencyAccess")).isFalse();
                if ("FRONT_DESK".equals(role)) {
                    assertThat(r.has("mine")).as("mine is absent for FRONT_DESK").isFalse();
                } else if ("OWNER".equals(role) || "DOCTOR".equals(role)) {
                    assertThat(r.get("mine").isBoolean()).as(role).isTrue();
                }
            }
        }
    }

    @Test
    void theOwnerAndTheDoctorSeeAllWardsAndTheirSafetyBoardHasNoScopeEvenWithAnEscalationInAWardNobodyIsAssignedTo() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        Staff doctor = staff(clinic, "DOCTOR");
        String ward = newWard(clinic, "Orphan ward");
        Adm orphan = admitInNamed(clinic, ward, "Chitra Orphan");
        String esc = raise(clinic, orphan, high7());
        for (RequestPostProcessor auth : new RequestPostProcessor[] {clinic.owner(), doctor.auth()}) {
            JsonNode board = boardOf(auth);
            assertThat(board.get("view").asText()).isEqualTo("DOCTOR");
            assertThat(board.has("scope")).as("SafetyBoard.scope is absent for OWNER and DOCTOR").isFalse();
            assertThat(board.has("otherWardsPastTarget")).isFalse();
            assertThat(rowIds(board)).contains(esc);
            assertThat(board.get("counts").get("open").asInt()).isOne();
            assertThat(listOf(auth).get("items")).hasSize(3);
            assertThat(summaryOf(auth).get("open").asInt()).isOne();
        }
        // and the nurse of ward A, who has no part in that ward, does not see it
        JsonNode nurse = boardOf(h.nurseA().auth());
        assertThat(rowIds(nurse)).doesNotContain(esc);
        assertThat(nurse.get("counts").get("open").asInt()).isZero();
        assertThat(summaryOf(h.nurseA().auth()).get("open").asInt()).isZero();
        assertThat(nurse.get("scope").get("kind").asText()).isEqualTo("WARDS");
        assertThat(nurse.get("scope").get("visible").asInt()).isOne();
        assertThat(nurse.get("scope").get("admitted").asInt()).isEqualTo(3);
        assertThat(nurse.get("yourPatients").asInt()).isOne();
        String raw = call(HttpMethod.GET, SAFETY, h.nurseA().auth(), null).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(orphan.name()).doesNotContain(esc).doesNotContain(orphan.id());
    }

    @Test
    void aNurseWithNoWardSeesNothingEverywhereAndEveryByIdRouteIs403OutsideScopeNeverEveryPatient() throws Exception {
        Full f = full();
        Clinic clinic = f.h().clinic();
        Pn lone = newPn(clinic, "NURSE"); // joins after the switch went on: no ward
        JsonNode list = listOf(lone.auth());
        assertThat(list.get("items")).isEmpty();
        assertThat(list.get("scope").get("kind").asText()).isEqualTo("WARDS");
        assertThat(list.get("scope").get("hasWard").asBoolean()).isFalse();
        assertThat(list.get("scope").get("visible").asInt()).isZero();
        assertThat(list.get("scope").get("admitted").asInt()).isEqualTo(2);
        assertThat(list.get("scope").get("canOpenEmergencyAccess").asBoolean()).as("break-glass is her way in").isTrue();
        JsonNode board = boardOf(lone.auth());
        assertThat(board.get("escalations")).isEmpty();
        assertThat(board.get("scope").get("hasWard").asBoolean()).isFalse();
        assertThat(board.get("counts").get("open").asInt()).isZero();
        assertThat(board.get("yourPatients").asInt()).isZero();
        assertThat(eventsOf(lone.auth(), null).get("items")).isEmpty();
        List<Endpoint> endpoints = byIdEndpoints(clinic, f.h().inB(), f.orderId(), f.escalationId());
        String written = written(clinic);
        for (Endpoint e : endpoints) {
            if (e.nurseMayUse()) {
                outside(call(e.method(), e.url(), lone.auth(), e.body()));
            }
        }
        assertThat(written(clinic)).as("nothing was written by any refused call").isEqualTo(written);
        // the bed board shows every bed and masks every occupied one
        JsonNode beds = ok(HttpMethod.GET, BOARD, lone.auth(), null, 200);
        int masked = 0;
        for (JsonNode w : beds.get("wards")) {
            for (JsonNode r : w.get("rooms")) {
                for (JsonNode b : r.get("beds")) {
                    if ("occ".equals(b.get("status").asText())) {
                        assertThat(b.get("outsideScope").asBoolean()).isTrue();
                        masked++;
                    }
                }
            }
        }
        assertThat(masked).isEqualTo(2);
    }

    // ---------------- the by-id reads and writes ----------------

    @Test
    void everyGuardedReadAndWriteOfAnotherWardsPatientIs403OutsideScopeWithAFixedBodyAndWritesNothing() throws Exception {
        Full f = full();
        Clinic clinic = f.h().clinic();
        Pn nurse = f.h().nurseA();
        Adm theirs = f.h().inB();
        String uhid = values(clinic, "select uhid from patient.patient where id = ?::uuid", theirs.patientId()).get(0);
        String written = written(clinic);
        List<Endpoint> endpoints = byIdEndpoints(clinic, theirs, f.orderId(), f.escalationId());
        written = written(clinic);
        for (Endpoint e : endpoints) {
            var response = call(e.method(), e.url(), nurse.auth(), e.body()).andReturn().getResponse();
            String raw = response.getContentAsString();
            assertThat(response.getStatus()).as(e.name() + " " + raw).isEqualTo(403);
            JsonNode p = objectMapper.readTree(raw);
            if (e.nurseMayUse()) {
                assertType(p, "outside-scope");
                assertThat(p.get("title").asText()).as(e.name()).isEqualTo(OUTSIDE_TITLE);
                assertThat(p.get("detail").asText()).as(e.name()).isEqualTo(OUTSIDE);
                // the raw body holds no name, UHID, mobile, bed, ward or clinical word (AC 12)
                assertThat(raw).as(e.name()).doesNotContain(theirs.name()).doesNotContain(theirs.mobile()).doesNotContain(uhid)
                        .doesNotContain(theirs.bedCode()).doesNotContain("Surgical").doesNotContain("diagnosis")
                        .doesNotContain("allerg").doesNotContain("stackTrace");
                assertThat(p.fieldNames()).toIterable().as(e.name() + " fields")
                        .containsOnly("type", "title", "status", "detail");
            } else {
                assertThat(p.get("type").asText()).as(e.name() + ": the role answer comes first").doesNotEndWith("outside-scope");
            }
            assertThat(written(clinic)).as(e.name() + " wrote nothing").isEqualTo(written);
        }
        // the owner can use every one of them for the same patient (so the refusal is about the nurse, not the patient)
        assertThat(ok(HttpMethod.GET, ADMISSIONS + "/" + theirs.id(), clinic.owner(), null, 200).get("id").asText()).isEqualTo(theirs.id());
    }

    @Test
    void theNursesOwnWardPatientStillWorksOnEveryEndpointAndAnUnknownOrForeignIdIs404NotOutsideScope() throws Exception {
        Hosp h = hosp();
        Pn nurse = h.nurseA();
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inA().id(), nurse.auth(), null, 200);
        ok(HttpMethod.GET, nursingUrl(h.inA().id()), nurse.auth(), null, 200);
        ok(HttpMethod.GET, emrUrl(h.inA().id()), nurse.auth(), null, 200);
        ok(HttpMethod.GET, marUrl(h.inA().id()), nurse.auth(), null, 200);
        ok(HttpMethod.POST, vitalsUrl(h.inA().id()), nurse.auth(), js(low()), 201);
        ok(HttpMethod.POST, ADMISSIONS + "/" + h.inA().id() + "/deposits", h.clinic().owner(), "{\"amountPaise\":100000,\"mode\":\"Cash\"}", 200);
        ok(HttpMethod.POST, ADMISSIONS + "/" + h.inA().id() + "/charges", nurse.auth(), "{\"serviceKey\":\"ecg\"}", 200);
        Hosp other = hosp();
        problem(call(HttpMethod.GET, ADMISSIONS + "/" + UUID.randomUUID(), nurse.auth(), null), 404);
        problem(call(HttpMethod.GET, ADMISSIONS + "/" + other.inA().id(), nurse.auth(), null), 404);
        problem(call(HttpMethod.POST, vitalsUrl(other.inA().id()), nurse.auth(), js(low())), 404);
        problem(call(HttpMethod.POST, stopUrl(UUID.randomUUID()), clinicOwner(h), null), 404);
    }

    private RequestPostProcessor clinicOwner(Hosp h) {
        return h.clinic().owner();
    }

    @Test
    void aWriteThatStartsInScopeReturnsItsNormalAnswerAndTheNextRequestIsRefused() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        setRules(clinic, false, "same");
        UUID bedInB = bedIn(clinic, h.wardB());
        // nurse A transfers her own patient to a bed of ward B: 200; the next request for that patient is refused
        ok(HttpMethod.POST, transferUrl(h.inA().id()), h.nurseA().auth(), moveBody(bedInB, null), 200);
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inA().id(), h.nurseA().auth(), null));
        assertThat(idsOf(listOf(h.nurseA().auth()))).isEmpty();
        assertThat(idsOf(listOf(h.nurseB().auth()))).contains(h.inA().id(), h.inB().id());
    }

    // ---------------- the switch off ----------------

    @Test
    void withTheSwitchOffTheNurseSeesEveryoneAndEveryAnswerIsAsItWasBeforeSah34() throws Exception {
        Full f = full();
        Clinic clinic = f.h().clinic();
        switchScope(clinic, false);
        Pn nurse = f.h().nurseA();
        Adm theirs = f.h().inB();
        JsonNode list = listOf(nurse.auth());
        assertThat(idsOf(list)).containsExactlyInAnyOrder(f.h().inA().id(), theirs.id());
        assertThat(list.get("scope").get("kind").asText()).isEqualTo("ALL");
        assertThat(list.get("scope").get("canOpenEmergencyAccess").asBoolean()).isFalse();
        assertThat(list.get("scope").get("hasWard").asBoolean()).as("hasWard is still a fact about her").isTrue();
        // "mine" still marks her ward (with the switch on or off)
        for (JsonNode r : list.get("items")) {
            assertThat(r.get("mine").asBoolean()).isEqualTo(f.h().inA().id().equals(r.get("id").asText()));
        }
        JsonNode board = boardOf(nurse.auth());
        assertThat(board.get("scope").get("kind").asText()).isEqualTo("ALL");
        assertThat(board.has("otherWardsPastTarget")).isFalse();
        assertThat(rowIds(board)).contains(f.escalationId());
        JsonNode beds = ok(HttpMethod.GET, BOARD, nurse.auth(), null, 200);
        assertThat(beds.toString()).doesNotContain("outsideScope").contains(theirs.name());
        // every read and write of the other ward's patient works as for the owner
        for (Endpoint e : byIdEndpoints(clinic, theirs, f.orderId(), f.escalationId())) {
            if (!e.nurseMayUse() || e.name().equals("transfer")) {
                continue;
            }
            var status = call(e.method(), e.url(), nurse.auth(), e.body()).andReturn().getResponse();
            assertThat(status.getStatus()).as(e.name() + ": " + status.getContentAsString()).isLessThan(300);
        }
        assertThat(eventsOf(nurse.auth(), null).get("items").size()).isGreaterThan(0);
        // the picker is for scoped staff only
        JsonNode p = problem(call(HttpMethod.GET, EA_PICKER, nurse.auth(), null), 403);
        assertThat(p.get("detail").asText()).isEqualTo(EA_ONLY);
        problem(call(HttpMethod.POST, EA, nurse.auth(), eaBody(theirs.id(), "DETERIORATING", true)), 403);
        assertThat(grantRows(clinic)).isZero();
    }

    @Test
    void switchingOnAndOffAppliesAtTheNextRequestOfTheSameToken() throws Exception {
        Hosp h = hosp();
        assertThat(idsOf(listOf(h.nurseA().auth()))).hasSize(1);
        switchScope(h.clinic(), false);
        assertThat(idsOf(listOf(h.nurseA().auth()))).hasSize(2);
        switchScope(h.clinic(), true);
        assertThat(idsOf(listOf(h.nurseA().auth()))).hasSize(1);
        // her ward is taken away: at once she sees nothing
        ok(HttpMethod.PUT, WARD_ASSIGNMENTS + "/" + h.wardA(), h.clinic().owner(), "{\"membershipIds\":[]}", 200);
        assertThat(idsOf(listOf(h.nurseA().auth()))).isEmpty();
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inA().id(), h.nurseA().auth(), null));
        // given back: at once she sees her ward again
        assign(h.clinic(), h.wardA(), h.nurseA());
        assertThat(idsOf(listOf(h.nurseA().auth()))).containsExactly(h.inA().id());
    }

    @Test
    void aNurseWhoseMembershipIsNotActiveGets403AndNeverTheWholeHospital() throws Exception {
        Hosp h = hosp();
        setMembershipStatus(h.clinic().tenantId(), h.nurseA().userId(), "INACTIVE");
        for (String url : new String[] {ADMISSIONS, SAFETY, BOARD, ADMISSIONS + "/" + h.inA().id()}) {
            forbidden(call(HttpMethod.GET, url, h.nurseA().auth(), null));
        }
    }

    @Test
    void anAssignmentToAWardIsAboutTheMembershipOfThatClinicAndAnotherClinicsNurseWithTheSameWardNameSeesNothingHere() throws Exception {
        Hosp one = hosp();
        Hosp two = hosp();
        // nurse A of clinic one with a token of clinic one is refused clinic two's patients (404 by row level security)
        problem(call(HttpMethod.GET, ADMISSIONS + "/" + two.inA().id(), one.nurseA().auth(), null), 404);
        assertThat(idsOf(listOf(two.nurseA().auth()))).containsExactly(two.inA().id());
    }

    // ---------------- the Safety board, the summary and the events ----------------

    @Test
    void theSafetyBoardTheSummaryTheTokenAndTheEventsAreNarrowedAndTheOtherWardsPastTargetIsANumberOnly() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String inA = raise(clinic, h.inA(), medium3());
        Adm secondInB = admitInNamed(clinic, h.wardB(), "Dev Second-B");
        String inB = raise(clinic, h.inB(), high7());
        String lateInB = openEscalationAgo(clinic, secondInB, 7, "HIGH", 40 * 60);

        JsonNode board = boardOf(h.nurseA().auth());
        assertThat(rowIds(board)).containsExactly(inA);
        assertThat(board.get("counts").get("open").asInt()).isOne();
        assertThat(board.get("counts").get("pastTarget").asInt()).isZero();
        assertThat(board.get("scope").get("kind").asText()).isEqualTo("WARDS");
        assertThat(board.get("otherWardsPastTarget").asInt()).as("the late one of ward B, as a number").isOne();
        assertThat(board.get("counts").get("open").asInt()).as("not in the tile").isOne();
        String raw = call(HttpMethod.GET, SAFETY, h.nurseA().auth(), null).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(inB).doesNotContain(lateInB).doesNotContain(h.inB().name()).doesNotContain(secondInB.name());
        JsonNode summary = summaryOf(h.nurseA().auth());
        assertThat(summary.get("open").asInt()).isOne();
        // the change token follows the scoped set: B's escalations are not in A's token, and a change in B does not change it
        String tokenA = board.get("counts").get("changeToken").asText();
        assertThat(summary.get("changeToken").asText()).isEqualTo(tokenA);
        assertThat(tokenOfLines(escalationLinesOf(clinic, inA))).isEqualTo(tokenA);
        respondOk(staff(clinic, "DOCTOR").auth(), inB, 0, "NO_CHANGE", "Reviewed, repeat vitals in 1 hour");
        assertThat(boardOf(h.nurseA().auth()).get("counts").get("changeToken").asText()).isEqualTo(tokenA);
        assertThat(boardOf(clinic.owner()).get("counts").get("changeToken").asText()).isNotEqualTo(tokenA);
        // the banner of the admitted list carries the scoped counts
        assertThat(listOf(h.nurseA().auth()).get("safety").get("counts").get("open").asInt()).isOne();
        // events: the nurse sees the entries of her ward and her own, not another ward's
        JsonNode events = eventsOf(h.nurseA().auth(), "limit=50");
        long ofWardB = count(clinic, "select count(*) from ipd.clinical_event where admission_id in (?::uuid, ?::uuid)", h.inB().id(), secondInB.id());
        long all = count(clinic, "select count(*) from ipd.clinical_event");
        assertThat(ofWardB).isPositive();
        assertThat(events.get("items").size()).isEqualTo((int) (all - ofWardB));
        assertThat(eventsOf(clinic.owner(), "limit=50").get("items").size()).isEqualTo((int) all);
    }

    private List<String> escalationLinesOf(Clinic clinic, String escalationId) throws Exception {
        return new ArrayList<>(column(clinic, "select lower(id::text) || ':' || version::text from ipd.ews_escalation where id = ?::uuid",
                escalationId));
    }

    // ---------------- the bed board ----------------

    @Test
    void theBedBoardKeepsEveryBedAndMasksTheOccupiedBedsOfOtherWardsWithNoPatientFields() throws Exception {
        Hosp h = hosp();
        JsonNode beds = ok(HttpMethod.GET, BOARD, h.nurseA().auth(), null, 200);
        int total = 0;
        int masked = 0;
        for (JsonNode w : beds.get("wards")) {
            for (JsonNode r : w.get("rooms")) {
                for (JsonNode b : r.get("beds")) {
                    total++;
                    boolean isB = h.inB().id().equals(b.path("admissionId").asText(null));
                    if (b.has("outsideScope")) {
                        masked++;
                        assertThat(b.get("outsideScope").asBoolean()).as("present only as true, never false").isTrue();
                        assertThat(b.get("status").asText()).isEqualTo("occ");
                        for (String field : new String[] {"admissionId", "patientName", "dayNumber", "doctorShortName", "depositLow",
                            "goingHomeToday"}) {
                            assertThat(b.path(field).isNull() || b.path(field).isMissingNode()).as(field).isTrue();
                        }
                        assertThat(isB).isFalse();
                    }
                }
            }
        }
        assertThat(total).isEqualTo(2);
        assertThat(masked).isOne();
        String raw = call(HttpMethod.GET, BOARD, h.nurseA().auth(), null).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(h.inB().name()).doesNotContain(h.inB().id());
        assertThat(raw).contains(h.inA().name());
        // owner and doctor: no tile is masked
        assertThat(ok(HttpMethod.GET, BOARD, h.clinic().owner(), null, 200).toString()).doesNotContain("outsideScope");
        assertThat(ok(HttpMethod.GET, BOARD, staff(h.clinic(), "DOCTOR").auth(), null, 200).toString()).doesNotContain("outsideScope");
    }

    // ---------------- the medicine round and the card flags ----------------

    @Test
    void theMedicineRoundOfAScopedNurseHoldsOnlyTheDosesOfHerWards() throws Exception {
        Full f = full();
        Clinic clinic = f.h().clinic();
        JsonNode nurseBoard = boardOf(f.h().nurseA().auth());
        assertThat(roundKeys(nurseBoard)).as("the order of ward B is not in A's round").isEmpty();
        JsonNode bNurse = boardOf(f.h().nurseB().auth());
        assertThat(roundKeys(bNurse)).isNotEmpty();
        assertThat(boardOf(clinic.owner()).get("counts").get("dosesDue").asInt() + boardOf(clinic.owner()).get("counts").get("dosesLate").asInt())
                .isEqualTo(bNurse.get("counts").get("dosesDue").asInt() + bNurse.get("counts").get("dosesLate").asInt());
        assertThat(boardOf(f.h().nurseA().auth()).get("counts").get("dosesLate").asInt()).isZero();
    }

    @Test
    void theLogOfARefusedCallHoldsIdsOnlyAndNoNameMobileOrUhid() throws Exception {
        Full f = full();
        Adm theirs = f.h().inB();
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + theirs.id(), f.h().nurseA().auth(), null));
        assertThat(allAuditText(f.h().clinic())).doesNotContain(theirs.name()).doesNotContain(theirs.mobile());
        assertThat(allEventText(f.h().clinic())).doesNotContain(theirs.name());
    }
}
