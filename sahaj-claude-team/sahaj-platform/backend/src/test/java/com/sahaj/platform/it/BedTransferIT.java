package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * SAH-26 moving a patient to another bed on the real {@code sahaj_test} database: what a move writes, the order of
 * the checks and their messages, the owner rule, a demoted owner, input errors, roles, module state, tenant isolation,
 * the patient access log, audit and privacy, and the free beds offered for the move. Money maths is in
 * {@link BedTransferBillIT}, concurrency in {@link BedTransferConcurrencyIT}, the table in {@link BedTransferTableIT}.
 * Test plan: docs/test-plans/SAH-26.md.
 */
@OnTestDatabase
@ExtendWith(OutputCaptureExtension.class)
class BedTransferIT extends BedTransferSupport {

    private static final String TEAM = "/api/v1/platform/team";

    private String codeOf(Clinic clinic, UUID bed) throws Exception {
        return values(clinic, "select code from ipd.bed where id = ?", bed).get(0);
    }

    private String ownerApproval(String category) {
        return "Moving to " + category + " needs the owner's approval. Ask the clinic owner.";
    }

    /** Everything a refused move must leave alone: no transfer row, no audit, the same bed and versions. */
    private record Snapshot(long transfers, long audits, UUID bed, long admissionVersion, String fromStatus,
                            String toStatus, long fromVersion, long toVersion) {
    }

    private Snapshot snapshot(Clinic clinic, String admissionId, UUID toBed) throws Exception {
        UUID from = bedOf(clinic, admissionId);
        return new Snapshot(transferRows(clinic), audits(clinic, "IPD_BED_TRANSFER"), from,
                admissionVersion(clinic, admissionId), bedStatus(clinic, from), bedStatus(clinic, toBed),
                bedVersion(clinic, from), bedVersion(clinic, toBed));
    }

    // ---------------- what a move writes ----------------

    @Test
    void aMoveWritesTheAdmissionTheTwoBedsTheTransferRowAndTheAnswerInOneStep() throws Exception {
        Clinic clinic = hospital();
        Person person = person(clinic, "Kishor Lonkar");
        UUID oldBed = newBed(clinic, "gen");
        String id = admitOk(clinic, body(person.id(), clinic.doctorMembershipId(), oldBed)).get("id").asText();
        UUID newBed = newBed(clinic, "semi");
        setRules(clinic, false, "same");
        long admissionVersion = admissionVersion(clinic, id);
        long oldVersion = bedVersion(clinic, oldBed);
        long newVersion = bedVersion(clinic, newBed);
        assertThat(bedStatus(clinic, oldBed)).isEqualTo("occ");
        assertThat(bedStatus(clinic, newBed)).isEqualTo("free");

        JsonNode answer = moveOk(clinic, id, newBed);

        // the answer is the whole admission sheet with the new bed
        assertThat(answer.get("id").asText()).isEqualTo(id);
        assertThat(answer.get("bedCode").asText()).isEqualTo(codeOf(clinic, newBed));
        assertThat(answer.get("categoryName").asText()).isEqualTo("Semi-private");
        assertThat(answer.get("wardName").asText()).isEqualTo("General ward");
        assertThat(answer.get("patientName").asText()).isEqualTo("Kishor Lonkar");
        assertThat(answer.get("canTransfer").asBoolean()).isTrue();
        assertThat(answer.get("transferRules").get("upgradeNeedsOwner").asBoolean()).isFalse();
        assertThat(answer.get("transferRules").get("newRateFrom").asText()).isEqualTo("same");
        assertThat(answer.get("bill").get("categoryName").asText()).isEqualTo("Semi-private");

        // database state
        assertThat(bedOf(clinic, id)).isEqualTo(newBed);
        assertThat(admissionVersion(clinic, id)).isEqualTo(admissionVersion + 1);
        assertThat(bedStatus(clinic, oldBed)).as("old bed goes to Cleaning").isEqualTo("clean");
        assertThat(bedStatus(clinic, newBed)).as("new bed is Occupied").isEqualTo("occ");
        assertThat(bedVersion(clinic, oldBed)).isEqualTo(oldVersion + 1);
        assertThat(bedVersion(clinic, newBed)).isEqualTo(newVersion + 1);
        assertThat(transferRows(clinic)).isOne();
        assertThat(values(clinic, "select from_bed_id::text, to_bed_id::text, from_category_key, to_category_key, "
                + "segment_days::text, new_rate_from, coalesce(reason, 'NULL'), transferred_by::text, "
                + "(transferred_at > now() - interval '1 minute')::text from ipd.bed_transfer where admission_id = ?::uuid", id))
                .containsExactly(oldBed.toString(), newBed.toString(), "gen", "semi", "0", "same", "NULL",
                        clinic.ownerUserId().toString(), "true");
        assertThat(audits(clinic, "IPD_BED_TRANSFER")).isOne();

        // the list, the sheet and the board agree
        JsonNode list = list(clinic);
        assertThat(row(list, id).get("bedCode").asText()).isEqualTo(codeOf(clinic, newBed));
        assertThat(detail(clinic, id).get("bedCode").asText()).isEqualTo(codeOf(clinic, newBed));
        JsonNode board = board(clinic);
        assertThat(tile(board, oldBed).get("status").asText()).isEqualTo("clean");
        assertThat(absent(tile(board, oldBed), "patientName")).isTrue();
        assertThat(absent(tile(board, oldBed), "admissionId")).isTrue();
        assertThat(tile(board, newBed).get("status").asText()).isEqualTo("occ");
        assertThat(tile(board, newBed).get("admissionId").asText()).isEqualTo(id);
        assertThat(tile(board, newBed).get("patientName").asText()).isEqualTo("Kishor Lonkar");
        assertThat(board.get("counts").get("clean").asInt()).isOne();
        assertThat(board.get("counts").get("occ").asInt()).isOne();
    }

    @Test
    void theOldBedStaysOutOfTheOptionsUntilItIsCleanedAndThenThePatientCanMoveBack() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        String id = quickAdmit(clinic).get("id").asText();
        UUID first = bedOf(clinic, id);
        UUID second = newBed(clinic, "gen");
        assertThat(offeredBeds(clinic)).containsExactly(second.toString());

        moveOk(clinic, id, second);
        assertThat(offeredBeds(clinic)).as("Cleaning and Occupied beds are not offered").isEmpty();
        problem(moveAs(clinic, "OWNER", id, first), 409);   // the old bed is Cleaning

        ok(HttpMethod.POST, BEDS + "/" + first + "/ready", clinic.owner(), null, 200);
        assertThat(offeredBeds(clinic)).containsExactly(first.toString());
        moveOk(clinic, id, first);
        assertThat(bedOf(clinic, id)).isEqualTo(first);
        assertThat(bedStatus(clinic, second)).isEqualTo("clean");
        assertThat(bedStatus(clinic, first)).isEqualTo("occ");
        assertThat(transferRows(clinic)).isEqualTo(2);
        assertThat(audits(clinic, "IPD_BED_TRANSFER")).isEqualTo(2);
    }

    @Test
    void theOptionalReasonIsStoredOnlyWhenItIsOneOfTheFourListedValues() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        String id = quickAdmit(clinic).get("id").asText();
        String[] reasons = {"Needs closer monitoring", "Moving to a step-down bed", "Patient or family request",
                "Isolation", null};
        for (String reason : reasons) {
            moveOk(clinic, id, newBed(clinic, "gen"), reason);
        }
        assertThat(values(clinic, "select string_agg(coalesce(reason, 'NULL'), '|' order by transferred_at, id) "
                + "from ipd.bed_transfer where admission_id = ?::uuid", id))
                .containsExactly("Needs closer monitoring|Moving to a step-down bed|Patient or family request|Isolation|NULL");

        // anything else is refused, nothing is written
        UUID target = newBed(clinic, "gen");
        Snapshot before = snapshot(clinic, id, target);
        for (String bad : new String[] {"", " ", "Fever", "isolation", "ISOLATION", " Isolation", "Isolation ",
                "Needs closer monitoring.", "x".repeat(5000), "Isolation\u0000"}) {
            JsonNode p = problem(call(HttpMethod.POST, transferUrl(id), clinic.owner(),
                    "{\"toBedId\":\"" + target + "\",\"reason\":" + objectMapper.writeValueAsString(bad) + "}"), 400);
            assertFieldError(p, "reason");
        }
        assertThat(snapshot(clinic, id, target)).isEqualTo(before);
        // the table holds nothing but the four values and null
        assertThat(count(clinic, "select count(*) from ipd.bed_transfer where reason is not null and reason not in "
                + "('Needs closer monitoring', 'Moving to a step-down bed', 'Patient or family request', 'Isolation')"))
                .isZero();
    }

    // ---------------- the order of the checks and their messages ----------------

    @Test
    void checksRunInOrderAdmission404ThenNotAdmitted409ThenSameBed400ThenBed404() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        String live = quickAdmit(clinic).get("id").asText();
        UUID free = newBed(clinic, "gen");
        String gone = quickAdmit(clinic).get("id").asText();
        UUID goneBed = bedOf(clinic, gone);
        discharge(clinic, gone);
        Clinic other = hospital();
        UUID foreignBed = newBed(other, "gen");
        UUID liveBed = bedOf(clinic, live);
        Snapshot before = snapshot(clinic, live, free);
        long audits = ipdAudits(clinic);

        // 1. the admission must exist in this clinic (a valid bed does not help)
        for (String role : MOVERS) {
            JsonNode p = problem(moveAs(clinic, role, UUID.randomUUID(), free), 404);
            assertThat(p.get("status").asInt()).isEqualTo(404);
        }
        // 2. and be Admitted: this comes before the same-bed and bed checks
        for (Object bed : new Object[] {free, goneBed, UUID.randomUUID(), foreignBed}) {
            JsonNode p = problem(moveAs(clinic, "OWNER", gone, bed), 409);
            assertType(p, "not-admitted");
            assertThat(p.get("detail").asText()).isEqualTo(NOT_ADMITTED);
        }
        // 3. the target must not be the current bed: 400 on toBedId, even though that bed is Occupied (not free)
        JsonNode same = problem(moveAs(clinic, "OWNER", live, liveBed), 400);
        assertFieldError(same, "toBedId");
        assertThat(same.get("fieldErrors").get(0).get("message").asText()).isEqualTo(SAME_BED);
        // 4. the target must exist in this clinic
        problem(moveAs(clinic, "OWNER", live, UUID.randomUUID()), 404);
        problem(moveAs(clinic, "OWNER", live, foreignBed), 404);

        assertThat(snapshot(clinic, live, free)).isEqualTo(before);
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
        assertThat(bedStatus(clinic, goneBed)).as("a discharged patient's bed is left alone").isEqualTo("occ");
    }

    @Test
    void aBedThatIsNotAvailableIs409BedNotFreeForOccupiedCleaningBlockedAndReserved() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        UUID occupied = bedOf(clinic, quickAdmit(clinic).get("id").asText());
        Map<String, UUID> others = Map.of("clean", newBed(clinic, "gen"), "blk", newBed(clinic, "gen"),
                "res", newBed(clinic, "gen"));
        for (Map.Entry<String, UUID> e : others.entrySet()) {
            setBedStatus(clinic, e.getValue(), e.getKey());
        }
        List<UUID> targets = new ArrayList<>(others.values());
        targets.add(occupied);
        Snapshot before = snapshot(clinic, id, occupied);
        for (UUID bed : targets) {
            for (String role : MOVERS) {
                JsonNode p = problem(moveAs(clinic, role, id, bed), 409);
                assertType(p, "bed-not-free");
                assertThat(p.get("detail").asText()).isEqualTo(BED_TAKEN);
            }
        }
        assertThat(snapshot(clinic, id, occupied)).isEqualTo(before);
        for (Map.Entry<String, UUID> e : others.entrySet()) {
            assertThat(bedStatus(clinic, e.getValue())).as("bed stays " + e.getKey()).isEqualTo(e.getKey());
        }
        assertThat(audits(clinic, "IPD_BED_TRANSFER")).isZero();
    }

    // ---------------- the owner rule ----------------

    @Test
    void withOwnerApprovesOnlyTheOwnerMovesToAHigherCategoryAndOthersGetTheExactMessage() throws Exception {
        Clinic clinic = hospital();
        assertThat(ok(HttpMethod.GET, TRANSFER_RULES, clinic.owner(), null, 200).get("upgradeNeedsOwner").asBoolean())
                .as("the default is Owner approves").isTrue();
        String id = quickAdmit(clinic).get("id").asText();
        Map<String, UUID> higher = Map.of("Semi-private", newBed(clinic, "semi"), "Private", newBed(clinic, "pvt"),
                "ICU", newBed(clinic, "icu"));
        UUID sample = higher.get("Private");
        Snapshot before = snapshot(clinic, id, sample);
        for (Map.Entry<String, UUID> e : higher.entrySet()) {
            for (String role : new String[] {"FRONT_DESK", "NURSE"}) {
                JsonNode p = problem(moveAs(clinic, role, id, e.getValue()), 403);
                assertType(p, "owner-approval-needed");
                assertThat(p.get("detail").asText()).as(role + " to " + e.getKey()).isEqualTo(ownerApproval(e.getKey()));
            }
        }
        assertThat(snapshot(clinic, id, sample)).as("a refused move changes nothing").isEqualTo(before);
        // a role that may not move at all gets a plain 403 (no business reason is given)
        for (String role : NON_MOVERS) {
            JsonNode p = problem(moveAs(clinic, role, id, sample), 403);
            assertThat(p.get("type").asText()).doesNotEndWith("owner-approval-needed");
        }
        // the owner can
        JsonNode ok = moveOk(clinic, id, higher.get("Semi-private"));
        assertThat(ok.get("categoryName").asText()).isEqualTo("Semi-private");
    }

    @Test
    void theOwnerRuleOnlyAppliesToAHigherCategorySameOrLowerAlwaysWorksForTheThreeRoles() throws Exception {
        Clinic clinic = hospital();      // rule: Owner approves (default)
        String id = admit(clinic, "pvt", "Self", 1_500_000).get("id").asText();
        // Private -> Private (same), Private -> Semi-private (lower), Semi-private -> General (lower)
        assertThat(moveAs(clinic, "NURSE", id, newBed(clinic, "pvt")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(moveAs(clinic, "FRONT_DESK", id, newBed(clinic, "semi")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(moveAs(clinic, "NURSE", id, newBed(clinic, "gen")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(moveAs(clinic, "OWNER", id, newBed(clinic, "gen")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(transferRows(clinic)).isEqualTo(4);
        // and a higher one is still refused for a nurse
        problem(moveAs(clinic, "NURSE", id, newBed(clinic, "icu")), 403);
        assertThat(transferRows(clinic)).isEqualTo(4);
    }

    @Test
    void withFrontDeskCanDoItOwnerFrontDeskAndNurseMayAllMoveToAHigherCategory() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        for (String role : MOVERS) {
            String id = quickAdmit(clinic).get("id").asText();
            JsonNode moved = ok(HttpMethod.POST, transferUrl(id), clinic.as(role), moveBody(newBed(clinic, "icu"), null), 200);
            assertThat(moved.get("categoryName").asText()).as(role).isEqualTo("ICU");
        }
        assertThat(transferRows(clinic)).isEqualTo(3);
        // switching back to Owner approves applies at once
        setRules(clinic, true, "same");
        String id = quickAdmit(clinic).get("id").asText();
        JsonNode p = problem(moveAs(clinic, "NURSE", id, newBed(clinic, "pvt")), 403);
        assertType(p, "owner-approval-needed");
    }

    @Test
    void aBedThatIsNotFreeIs409BeforeTheOwnerRuleIsLookedAt() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        UUID occupiedPrivate = bedOf(clinic, admit(clinic, "pvt", "Self", 1_500_000).get("id").asText());
        UUID blockedIcu = newBed(clinic, "icu");
        setBedStatus(clinic, blockedIcu, "blk");
        for (UUID bed : new UUID[] {occupiedPrivate, blockedIcu}) {
            JsonNode p = problem(moveAs(clinic, "NURSE", id, bed), 409);
            assertType(p, "bed-not-free");
        }
    }

    @Test
    void anOwnerDemotedAMomentAgoIsRefusedTheOwnerOnlyActionsEvenWithTheOldOwnerToken() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        String other = quickAdmit(clinic).get("id").asText();
        UUID privateBed = newBed(clinic, "pvt");
        UUID sameBed = newBed(clinic, "gen");
        UUID icuBed = newBed(clinic, "icu");      // bed set-up is owner-only, so it is all done before the demotion
        // a second owner demotes the first one; the first one's token still says OWNER
        Member second = addMember(clinic, "OWNER");
        UUID firstId = UUID.fromString(ok(HttpMethod.GET, "/api/v1/platform/me", clinic.owner(), null, 200)
                .get("membershipId").asText());
        Session secondSession = login(second.mobile());
        ok(HttpMethod.PATCH, TEAM + "/" + firstId, bearer(secondSession.accessToken()), "{\"role\":\"NURSE\"}", 200);

        Snapshot before = snapshot(clinic, id, privateBed);
        // Owner approves + higher category: the role in the token is not trusted for this
        problem(move(clinic, clinic.owner(), id, privateBed), 403);
        assertThat(snapshot(clinic, id, privateBed)).as("nothing changed").isEqualTo(before);
        // the rules can only be changed by a real owner
        problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), "{\"upgradeNeedsOwner\":false,\"newRateFrom\":\"next\"}"), 403);
        assertThat(ok(HttpMethod.GET, TRANSFER_RULES, bearer(secondSession.accessToken()), null, 200)
                .get("upgradeNeedsOwner").asBoolean()).isTrue();
        // a same-category move needs no owner, so the demoted person (now a nurse) can still do it
        ok(HttpMethod.POST, transferUrl(id), clinic.owner(), moveBody(sameBed, null), 200);
        // the real owner can still do everything
        ok(HttpMethod.PUT, TRANSFER_RULES, bearer(secondSession.accessToken()),
                "{\"upgradeNeedsOwner\":true,\"newRateFrom\":\"next\"}", 200);
        ok(HttpMethod.POST, transferUrl(id), bearer(secondSession.accessToken()), moveBody(privateBed, null), 200);
        // with the rule switched off the demoted person, now a nurse, may move to a higher category too
        ok(HttpMethod.PUT, TRANSFER_RULES, bearer(secondSession.accessToken()),
                "{\"upgradeNeedsOwner\":false,\"newRateFrom\":\"next\"}", 200);
        ok(HttpMethod.POST, transferUrl(other), clinic.owner(), moveBody(icuBed, null), 200);
    }

    // ---------------- input errors ----------------

    @Test
    void invalidBodiesGet400WithProblemDetailsAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        UUID target = newBed(clinic, "gen");
        Snapshot before = snapshot(clinic, id, target);
        long audits = ipdAudits(clinic);
        for (String body : new String[] {"{}", "{\"toBedId\":null}", "{\"reason\":\"Isolation\"}"}) {
            JsonNode p = problem(call(HttpMethod.POST, transferUrl(id), clinic.owner(), body), 400);
            assertFieldError(p, "toBedId");
            assertThat(p.get("fieldErrors").get(0).get("message").asText()).isEqualTo("Choose a bed");
        }
        for (String body : new String[] {"{\"toBedId\":\"not-a-uuid\"}", "{\"toBedId\":123}", "{\"toBedId\":\"\"}",
                "{\"toBedId\":\"" + target + "\",\"reason\":5}", "not json", "[]", "{"}) {
            problem(call(HttpMethod.POST, transferUrl(id), clinic.owner(), body), 400);
        }
        problem(call(HttpMethod.POST, transferUrl(id), clinic.owner(), null), 400);
        problem(call(HttpMethod.POST, transferUrl(id), clinic.owner(), "null"), 400);
        problem(call(HttpMethod.POST, ADMISSIONS + "/not-a-uuid/transfer", clinic.owner(), moveBody(target, null)), 400);
        assertThat(snapshot(clinic, id, target)).isEqualTo(before);
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
        // input errors are checked before the admission is looked up (as for extra charges in SAH-25)
        assertFieldError(problem(call(HttpMethod.POST, transferUrl(UUID.randomUUID()), clinic.owner(), "{}"), 400), "toBedId");
    }

    @Test
    void extraJsonFieldsAreIgnoredAndTheServerChoosesTheClinicTheActorAndTheCategories() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        setRules(clinic, false, "same");
        String id = quickAdmit(clinic).get("id").asText();
        UUID from = bedOf(clinic, id);
        UUID target = newBed(clinic, "icu");
        ok(HttpMethod.POST, transferUrl(id), clinic.owner(), "{\"toBedId\":\"" + target + "\",\"tenantId\":\""
                + other.tenantId() + "\",\"segmentDays\":99,\"fromCategoryKey\":\"icu\",\"toCategoryKey\":\"gen\","
                + "\"transferredBy\":\"" + UUID.randomUUID() + "\",\"fromBedId\":\"" + UUID.randomUUID() + "\"}", 200);
        assertThat(values(clinic, "select from_bed_id::text, to_bed_id::text, from_category_key, to_category_key, "
                + "segment_days::text, transferred_by::text from ipd.bed_transfer"))
                .containsExactly(from.toString(), target.toString(), "gen", "icu", "0", clinic.ownerUserId().toString());
        assertThat(transferRows(other)).isZero();
    }

    // ---------------- roles ----------------

    @Test
    void ownerFrontDeskAndNurseMayMoveEveryOtherRoleGets403AndAnonymousGets401() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        long moves = 0;
        for (String role : MOVERS) {
            String id = quickAdmit(clinic).get("id").asText();
            JsonNode d = ok(HttpMethod.POST, transferUrl(id), clinic.as(role), moveBody(newBed(clinic, "gen"), null), 200);
            moves++;
            assertThat(d.get("canTransfer").asBoolean()).as(role).isTrue();
            // the mobile number is for the owner and front desk only, also in this answer
            assertThat(d.hasNonNull("mobile")).as(role + " mobile").isEqualTo(!"NURSE".equals(role));
        }
        String id = quickAdmit(clinic).get("id").asText();
        UUID target = newBed(clinic, "gen");
        Snapshot before = snapshot(clinic, id, target);
        for (String role : NON_MOVERS) {
            problem(moveAs(clinic, role, id, target), 403);
            // also for input that would otherwise be a 400 or a 404: the role is checked first
            problem(move(clinic, clinic.as(role), UUID.randomUUID(), UUID.randomUUID()), 403);
        }
        mvc.perform(post(transferUrl(id)).contentType(MediaType.APPLICATION_JSON).content(moveBody(target, null)))
                .andExpect(status().isUnauthorized());
        assertThat(snapshot(clinic, id, target)).isEqualTo(before);
        assertThat(transferRows(clinic)).isEqualTo(moves);
        assertThat(audits(clinic, "IPD_BED_TRANSFER")).isEqualTo(moves);
        // a doctor can read the admission but not move it (OQ-26-2)
        JsonNode asDoctor = ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as("DOCTOR"), null, 200);
        assertThat(asDoctor.get("canTransfer").asBoolean()).isFalse();
        for (String role : MOVERS) {
            assertThat(ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as(role), null, 200).get("canTransfer").asBoolean())
                    .as(role).isTrue();
        }
    }

    @Test
    void canTransferIsFalseWhenDischargedOrReadOnlyAndTheFreeBedOptionsStayForTheThreeRolesOnly() throws Exception {
        Clinic clinic = hospital();
        String live = quickAdmit(clinic).get("id").asText();
        String gone = quickAdmit(clinic).get("id").asText();
        newBed(clinic, "gen");
        discharge(clinic, gone);
        assertThat(detail(clinic, live).get("canTransfer").asBoolean()).isTrue();
        assertThat(detail(clinic, gone).get("canTransfer").asBoolean()).isFalse();
        for (String role : MOVERS) {
            assertThat(ok(HttpMethod.GET, OPTIONS, clinic.as(role), null, 200).get("beds")).hasSize(1);
        }
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            problem(call(HttpMethod.GET, OPTIONS, clinic.as(role), null), 403);
        }
        expireTrial(clinic);
        for (String role : READERS) {
            assertThat(ok(HttpMethod.GET, ADMISSIONS + "/" + live, clinic.as(role), null, 200).get("canTransfer").asBoolean())
                    .as(role + " read-only").isFalse();
        }
    }

    // ---------------- module state ----------------

    @Test
    void ipdOffTheMoveAndBothRulesEndpointsAre403ModuleOffAndNothingIsWritten() throws Exception {
        Clinic clinic = newClinic();
        String id = UUID.randomUUID().toString();
        for (String role : MOVERS) {
            JsonNode p = problem(moveAs(clinic, role, id, UUID.randomUUID()), 403);
            assertType(p, "module-off");
        }
        assertType(problem(call(HttpMethod.GET, TRANSFER_RULES, clinic.owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(),
                "{\"upgradeNeedsOwner\":false,\"newRateFrom\":\"next\"}"), 403), "module-off");
        assertThat(transferRows(clinic)).isZero();
        assertThat(rows(clinic, "config")).isZero();
        assertThat(ipdAudits(clinic)).isZero();
        ok(HttpMethod.POST, TRIAL, clinic.owner(), null, 200);
        ok(HttpMethod.GET, TRANSFER_RULES, clinic.owner(), null, 200);
    }

    @Test
    void readOnlyIpdRefusesTheMoveBeforeAnyOtherCheckAndNothingChanges() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        UUID target = newBed(clinic, "gen");
        expireTrial(clinic);
        Snapshot before = snapshot(clinic, id, target);
        long audits = ipdAudits(clinic);
        String[][] attempts = {{id, moveBody(target, null)}, {id, moveBody(target, "Fever")}, {id, "{}"},
                {UUID.randomUUID().toString(), moveBody(target, null)}, {id, moveBody(UUID.randomUUID(), null)}};
        for (String role : MOVERS) {
            for (String[] a : attempts) {
                JsonNode p = problem(call(HttpMethod.POST, transferUrl(a[0]), clinic.as(role), a[1]), 409);
                assertType(p, "ipd-readonly");
                assertThat(p.get("detail").asText()).isEqualTo("IPD is read-only. Nothing can be changed.");
            }
        }
        assertThat(snapshot(clinic, id, target)).isEqualTo(before);
        assertThat(ipdAudits(clinic)).isEqualTo(audits);
        // reading still works
        assertThat(ok(HttpMethod.GET, TRANSFER_RULES, clinic.as("NURSE"), null, 200).get("newRateFrom").asText())
                .isEqualTo("same");
        ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.owner(), null, 200);
        assertThat(ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200).get("beds")).hasSize(1);
    }

    // ---------------- tenant isolation ----------------

    @Test
    void anotherClinicCannotSeeMoveOrTargetThisClinicsAdmissionsBedsAndTransfers() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        setRules(a, false, "next");
        String idA = quickAdmit(a).get("id").asText();
        UUID bedA = newBed(a, "gen");
        String idB = quickAdmit(b).get("id").asText();
        UUID bedB = newBed(b, "gen");
        moveOk(a, idA, bedA);
        assertThat(transferRows(a)).isOne();

        // B cannot move A's admission and cannot move its own patient to A's bed
        problem(move(b, b.owner(), idA, bedB), 404);
        problem(move(b, b.owner(), idB, bedA), 404);
        problem(call(HttpMethod.GET, ADMISSIONS + "/" + idA, b.owner(), null), 404);
        assertThat(transferRows(b)).isZero();
        assertThat(bedStatus(b, bedB)).isEqualTo("free");
        assertThat(bedOf(b, idB)).isNotEqualTo(bedA);

        // row level security on the new table: no tenant bound = no rows; B sees none of A's
        long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd.bed_transfer"));
        assertThat(unbound).isZero();
        assertThat(count(b, "select count(*) from ipd.bed_transfer where admission_id = ?::uuid", idA)).isZero();
        assertThat(count(a, "select count(*) from ipd.bed_transfer")).isOne();
        assertThat(count(b, "select count(*) from platform.audit_event where action = 'IPD_BED_TRANSFER'")).isZero();
        // A's rules are not B's
        assertThat(ok(HttpMethod.GET, TRANSFER_RULES, b.owner(), null, 200).get("upgradeNeedsOwner").asBoolean()).isTrue();
        assertThat(ok(HttpMethod.GET, TRANSFER_RULES, b.owner(), null, 200).get("newRateFrom").asText()).isEqualTo("same");
        assertThat(ok(HttpMethod.GET, TRANSFER_RULES, a.owner(), null, 200).get("newRateFrom").asText()).isEqualTo("next");
        // B's bed offers never include A's beds
        assertThat(offeredBeds(b)).containsExactly(bedB.toString());
        // and B's own move still works while A's rows exist
        moveOk(b, idB, bedB);
        assertThat(transferRows(a)).isOne();
        assertThat(transferRows(b)).isOne();
    }

    // ---------------- patient access log, audit and privacy ----------------

    @Test
    void aSuccessfulMoveWritesOnePatientAccessRowAndRefusedMovesWriteNone() throws Exception {
        Clinic clinic = hospital();
        Person p = person(clinic, "Kishor Lonkar");
        String id = admitOk(clinic, body(p.id(), clinic.doctorMembershipId(), newBed(clinic, "gen"))).get("id").asText();
        UUID semi = newBed(clinic, "semi");
        assertThat(recordAccess(clinic, p.id())).isZero();

        problem(moveAs(clinic, "NURSE", id, semi), 403);                 // owner rule
        problem(moveAs(clinic, "DOCTOR", id, semi), 403);                // role
        problem(moveAs(clinic, "OWNER", id, UUID.randomUUID()), 404);     // bed
        problem(moveAs(clinic, "OWNER", id, bedOf(clinic, id)), 400);     // same bed
        problem(call(HttpMethod.POST, transferUrl(id), clinic.owner(), "{}"), 400);
        ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200);
        ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200);
        ok(HttpMethod.GET, TRANSFER_RULES, clinic.owner(), null, 200);
        assertThat(recordAccess(clinic, p.id())).as("refused moves and lists write no access row").isZero();

        moveOk(clinic, id, semi);
        assertThat(recordAccess(clinic, p.id())).as("the answer is the full sheet, so it is logged like an open").isOne();
        moveOk(clinic, id, newBed(clinic, "semi"));
        assertThat(recordAccess(clinic, p.id())).isEqualTo(2);
    }

    @Test
    void theAuditRowsCarryIdsCodesCategoriesDaysAndRulesAndNeverTheNameTheMobileOrAnyReason(CapturedOutput output)
            throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String secretName = "Zubin Wadkar" + uniqueMobile().substring(5);
        String secretReason = "ZXQ-" + UUID.randomUUID();
        Person p = person(clinic, secretName);
        UUID oldBed = newBed(clinic, "gen");
        Map<String, Object> m = body(p.id(), clinic.doctorMembershipId(), oldBed);
        m.put("reason", secretReason);
        String id = admitOk(clinic, m).get("id").asText();
        UUID newBed = newBed(clinic, "pvt");

        ok(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), "{\"upgradeNeedsOwner\":true,\"newRateFrom\":\"next\"}", 200);
        moveOk(clinic, id, newBed, "Isolation");
        ok(HttpMethod.PUT, TRANSFER_RULES, clinic.owner(), "{\"upgradeNeedsOwner\":false,\"newRateFrom\":\"same\"}", 200);

        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        List<String[]> rows = audit(clinic, "IPD_BED_TRANSFER");
        assertThat(rows).hasSize(1);
        String[] r = rows.get(0);
        String transferId = values(clinic, "select id::text from ipd.bed_transfer").get(0);
        assertThat(r[0]).isEqualTo("IPD_BED_TRANSFER");
        assertThat(r[1]).isEqualTo(transferId);
        assertThat(r[2]).as("no before image").isNull();
        assertThat(r[4]).isEqualTo(clinic.ownerUserId().toString());
        JsonNode after = objectMapper.readTree(r[3]);
        Set<String> keys = new HashSet<>();
        after.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("admissionId", "ipNumber", "fromBedId", "fromBedCode", "toBedId",
                "toBedCode", "fromCategoryKey", "toCategoryKey", "billDays", "segmentDays", "newRateFrom",
                "upgradeNeedsOwner");
        assertThat(after.get("admissionId").asText()).isEqualTo(id);
        assertThat(after.get("fromBedId").asText()).isEqualTo(oldBed.toString());
        assertThat(after.get("toBedId").asText()).isEqualTo(newBed.toString());
        assertThat(after.get("fromBedCode").asText()).isEqualTo(codeOf(clinic, oldBed));
        assertThat(after.get("toBedCode").asText()).isEqualTo(codeOf(clinic, newBed));
        assertThat(after.get("fromCategoryKey").asText()).isEqualTo("gen");
        assertThat(after.get("toCategoryKey").asText()).isEqualTo("pvt");
        assertThat(after.get("billDays").asInt()).isOne();
        assertThat(after.get("segmentDays").asInt()).isOne();      // Next day: the whole first day stays General
        assertThat(after.get("newRateFrom").asText()).isEqualTo("next");
        assertThat(after.get("upgradeNeedsOwner").asBoolean()).isTrue();

        List<String[]> ruleRows = audit(clinic, "IPD_TRANSFER_RULES_UPDATED");
        assertThat(ruleRows).hasSize(2);
        assertThat(ruleRows.get(0)[0]).isEqualTo("IPD_CONFIG");
        assertThat(ruleRows.get(0)[1]).isNull();
        assertThat(objectMapper.readTree(ruleRows.get(0)[2])).isEqualTo(objectMapper.readTree(
                "{\"upgradeNeedsOwner\":true,\"newRateFrom\":\"same\"}"));
        assertThat(objectMapper.readTree(ruleRows.get(0)[3])).isEqualTo(objectMapper.readTree(
                "{\"upgradeNeedsOwner\":true,\"newRateFrom\":\"next\"}"));
        assertThat(objectMapper.readTree(ruleRows.get(1)[3])).isEqualTo(objectMapper.readTree(
                "{\"upgradeNeedsOwner\":false,\"newRateFrom\":\"same\"}"));

        // the reason of the transfer is stored in the transfer table, the reason for admission in the admission
        assertThat(count(clinic, "select count(*) from ipd.bed_transfer where reason = 'Isolation'")).isOne();
        assertThat(count(clinic, "select count(*) from ipd.admission where reason = ?", secretReason)).isOne();
        for (String secret : new String[] {secretReason, secretName, p.mobile(), "Zubin", "Isolation"}) {
            assertThat(count(clinic, "select count(*) from platform.audit_event where audit_event::text like ?",
                    "%" + secret + "%")).as("audit rows must not contain " + secret).isZero();
            assertThat(output.getAll()).as("log must not contain " + secret).doesNotContain(secret);
        }
    }

    // ---------------- the free beds offered for a move ----------------

    private Set<String> layoutBedIds(JsonNode layout) {
        Set<String> ids = new HashSet<>();
        layout.get("wards").forEach(w -> w.get("beds").forEach(b -> ids.add(b.get("id").asText())));
        return ids;
    }

    private Set<String> layoutRoomIds(JsonNode layout) {
        Set<String> ids = new HashSet<>();
        layout.get("wards").forEach(w -> w.get("rooms").forEach(r -> ids.add(r.get("id").asText())));
        return ids;
    }

    private String newWard(Clinic clinic, String name) throws Exception {
        JsonNode l = ok(HttpMethod.POST, WARDS, clinic.owner(), "{\"name\":\"" + name + "\"}", 201);
        for (JsonNode w : l.get("wards")) {
            if (name.equals(w.get("name").asText())) {
                return w.get("id").asText();
            }
        }
        throw new AssertionError("ward not found " + name);
    }

    private String newRoom(Clinic clinic, String wardId) throws Exception {
        Set<String> known = layoutRoomIds(layout(clinic));
        Set<String> now = layoutRoomIds(ok(HttpMethod.POST, WARDS + "/" + wardId + "/rooms", clinic.owner(), null, 201));
        now.removeAll(known);
        assertThat(now).hasSize(1);
        return now.iterator().next();
    }

    private UUID bedIn(Clinic clinic, String wardId, String roomId, String category) throws Exception {
        Set<String> known = layoutBedIds(layout(clinic));
        String request = "{\"wardId\":\"" + wardId + "\"" + (roomId == null ? "" : ",\"roomId\":\"" + roomId + "\"") + "}";
        Set<String> now = layoutBedIds(ok(HttpMethod.POST, BEDS, clinic.owner(), request, 201));
        now.removeAll(known);
        assertThat(now).hasSize(1);
        UUID id = UUID.fromString(now.iterator().next());
        if (!"gen".equals(category)) {
            ok(HttpMethod.PUT, BEDS + "/" + id, clinic.owner(), "{\"categoryKey\":\"" + category + "\"}", 200);
        }
        return id;
    }

    @Test
    void theFreeBedsAreOrderedByCategoryThenRoomCreationOrderThenCodeWithBedsWithoutARoomLast() throws Exception {
        Clinic clinic = hospital();          // "General ward" with Room 101
        String ward1 = layout(clinic).get("wards").get(0).get("id").asText();
        String ward2 = newWard(clinic, "Second ward");     // Room 201
        String room101 = layout(clinic).get("wards").get(0).get("rooms").get(0).get("id").asText();
        String room201 = null;
        for (JsonNode w : layout(clinic).get("wards")) {
            if (ward2.equals(w.get("id").asText())) {
                room201 = w.get("rooms").get(0).get("id").asText();
            }
        }
        String room102 = newRoom(clinic, ward1);           // created after Room 201: room order 101, 201, 102

        String occupiedId = admit(clinic, "gen", "Self", 1_000_000).get("id").asText();   // Room 101, Occupied
        UUID cleaning = bedIn(clinic, ward1, room101, "gen");
        UUID blocked = bedIn(clinic, ward1, room101, "gen");
        UUID reserved = bedIn(clinic, ward1, room101, "gen");
        setBedStatus(clinic, cleaning, "clean");
        setBedStatus(clinic, blocked, "blk");
        setBedStatus(clinic, reserved, "res");

        UUID genRoom201 = bedIn(clinic, ward2, room201, "gen");
        UUID genRoom101a = bedIn(clinic, ward1, room101, "gen");
        UUID genRoom102 = bedIn(clinic, ward1, room102, "gen");
        UUID pvt = bedIn(clinic, ward1, room101, "pvt");
        UUID icu = bedIn(clinic, ward1, room101, "icu");
        UUID semi = bedIn(clinic, ward1, room101, "semi");
        UUID genRoom101b = bedIn(clinic, ward1, room101, "gen");
        ok(HttpMethod.PUT, IPD + "/room-level", clinic.owner(), "{\"on\":false}", 200);
        UUID genNoRoom = bedIn(clinic, ward1, null, "gen");

        // category General to ICU; inside a category the room (101, then 201, then 102 by creation); no room last
        List<String> expected = List.of(genRoom101a.toString(), genRoom101b.toString(), genRoom201.toString(),
                genRoom102.toString(), genNoRoom.toString(), semi.toString(), pvt.toString(), icu.toString());
        JsonNode options = ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200);
        List<String> offered = new ArrayList<>();
        options.get("beds").forEach(b -> offered.add(b.get("id").asText()));
        assertThat(offered).containsExactlyElementsOf(expected);
        // the codes inside room 101 are ascending
        assertThat(codeOf(clinic, genRoom101a).compareTo(codeOf(clinic, genRoom101b))).isNegative();
        // each option has what the label "<code> · <category> · ₹<rate>" needs
        JsonNode first = options.get("beds").get(0);
        assertThat(first.get("code").asText()).isEqualTo(codeOf(clinic, genRoom101a));
        assertThat(first.get("categoryKey").asText()).isEqualTo("gen");
        assertThat(first.get("categoryName").asText()).isEqualTo("General");
        assertThat(first.get("ratePaise").asLong()).isEqualTo(80_000);
        assertThat(first.get("wardName").asText()).isEqualTo("General ward");
        JsonNode semiOption = options.get("beds").get(5);
        assertThat(semiOption.get("ratePaise").asLong()).isEqualTo(150_000);
        assertThat(semiOption.get("categoryName").asText()).isEqualTo("Semi-private");
        assertThat(options.get("beds").get(4).get("wardName").asText()).isEqualTo("General ward");
        // the daily rate shown is the one set in Clinic setup now
        setCategory(clinic, "gen", 85_000, "1");
        assertThat(ok(HttpMethod.GET, OPTIONS, clinic.owner(), null, 200).get("beds").get(0).get("ratePaise").asLong())
                .isEqualTo(85_000);
        // never offered: Occupied, Cleaning, Blocked, Reserved
        assertThat(offered).doesNotContain(cleaning.toString(), blocked.toString(), reserved.toString(),
                bedOf(clinic, occupiedId).toString());
    }
}
