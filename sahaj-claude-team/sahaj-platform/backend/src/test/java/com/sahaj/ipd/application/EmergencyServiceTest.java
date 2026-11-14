package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.ipd.application.EmergencyCaseRepository.Created;
import com.sahaj.ipd.application.EmergencyCaseRepository.NewCase;
import com.sahaj.ipd.application.EmergencyViews.Case;
import com.sahaj.ipd.application.EmergencyViews.CaseInput;
import com.sahaj.ipd.application.EmergencyViews.CaseRef;
import com.sahaj.ipd.application.EmergencyViews.LevelInput;
import com.sahaj.ipd.application.EmergencyViews.Queue;
import com.sahaj.ipd.application.EmergencyViews.Registration;
import com.sahaj.ipd.application.EmergencyViews.Settings;
import com.sahaj.ipd.application.EmergencyViews.SettingsInput;
import com.sahaj.ipd.application.IpdSettingsRepository.EmergencySetup;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-28 emergency service with an in-memory case table that enforces the same unique rules as the database (one open
 * case per bay, one case per client key), so that the bay and key rules are tested end to end in the service.
 */
class EmergencyServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    /** 11 Nov 2026, 11:00 AM IST. */
    private static final Instant NOW = Instant.parse("2026-11-11T05:30:00.123456789Z");
    private static final String SECRET_NAME = "Ramesh Kulkarni";
    private static final String SECRET_COMPLAINT = "Crushing chest pain since morning";
    private static final String SECRET_MOBILE = "9876543210";

    private FakeCases cases;
    private TriageEventRepository events;
    private WardRepository wards;
    private IpdSettingsRepository settings;
    private OwnerGuard ownerGuard;
    private AuditLog auditLog;
    private ModuleEntitlementPort entitlements;
    private EmergencyService service;
    private EmergencySetup setup = EmergencySetup.DEFAULTS;

    @BeforeEach
    void setUp() {
        cases = new FakeCases();
        events = mock(TriageEventRepository.class);
        wards = mock(WardRepository.class);
        settings = mock(IpdSettingsRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        auditLog = mock(AuditLog.class);
        entitlements = mock(ModuleEntitlementPort.class);
        org.mockito.Mockito.doAnswer(call -> {
            cases.locked = true;
            return null;
        }).when(wards).lockTenant(TENANT);
        when(settings.findEmergencySetup(TENANT)).thenAnswer(call -> Optional.of(setup));
        org.mockito.Mockito.doAnswer(call -> {
            setup = call.getArgument(1);
            return null;
        }).when(settings).upsertEmergencySetup(any(), any(), any(), any());
        IpdCatalog catalog = new IpdCatalog(settings);
        IpdGate gate = new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC));
        service = new EmergencyService(cases, events, wards, settings, catalog, ownerGuard, auditLog, gate);
        state("on");
    }

    private void state(String ipdState) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(ipdState, false, 25));
    }

    /** A request body with the typed fields as JSON numbers and booleans (as the contract wants them). */
    private static CaseInput caseInput(String key, Boolean unknown, String name, BigDecimal age, String sex,
                                       String mobile, String complaint, BigDecimal level) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        return new CaseInput(key, unknown == null ? null : json.booleanNode(unknown), name,
                age == null ? null : json.numberNode(age), sex, mobile, complaint,
                level == null ? null : json.numberNode(level));
    }

    /** The acted case as the full card. */
    private static Case full(Queue queue) {
        assertThat(queue.actedCase()).isInstanceOf(Case.class);
        return (Case) queue.actedCase();
    }

    private static CaseInput input(UUID key, String name, int level) {
        return caseInput(key.toString(), false, name, new BigDecimal("52"), "M", SECRET_MOBILE,
                SECRET_COMPLAINT, BigDecimal.valueOf(level));
    }

    private CaseRow register(int level) {
        Registration r = service.register(TENANT, ACTOR, "NURSE", input(UUID.randomUUID(), SECRET_NAME, level));
        return cases.rows.get(r.queue().actedCase().id());
    }

    // ---- register ----

    @Test
    void registerStoresAWaitingCaseLogsTheRegisteredEventAndAuditsIdsAndLevelOnly() {
        UUID key = UUID.randomUUID();

        Registration result = service.register(TENANT, ACTOR, "FRONT_DESK", input(key, "  " + SECRET_NAME + " ", 2));

        assertThat(result.created()).isTrue();
        Case acted = full(result.queue());
        assertThat(acted.displayName()).isEqualTo(SECRET_NAME);
        assertThat(acted.status()).isEqualTo("WAITING");
        assertThat(acted.level()).isEqualTo(2);
        assertThat(acted.levelName()).isEqualTo("Yellow");
        assertThat(acted.bay()).isNull();
        assertThat(acted.identityPending()).isFalse();
        assertThat(result.queue().cases()).hasSize(1);
        NewCase saved = cases.lastInsert;
        assertThat(saved.registeredBy()).isEqualTo(ACTOR);
        assertThat(saved.clientKey()).isEqualTo(key);
        assertThat(saved.mobile()).isEqualTo(SECRET_MOBILE);
        verify(events).insert(TENANT, acted.id(), null, 2, 3, "REGISTERED", ACTOR);
        Map<String, Object> after = lastAudit("IPD_ER_REGISTERED", acted.id());
        assertThat(after.keySet()).containsExactlyInAnyOrder("caseId", "level", "scale", "bay", "arrivedAt");
        assertThat(after.get("level")).isEqualTo(2);
        assertThat(after.get("scale")).isEqualTo(3);
    }

    @Test
    void registerAuditNeverHoldsTheNameMobileAgeOrComplaint() {
        service.register(TENANT, ACTOR, "NURSE", input(UUID.randomUUID(), SECRET_NAME, 1));

        ArgumentCaptor<Map<String, Object>> after = captorOfMap();
        verify(auditLog).record(eq("IPD_ER_REGISTERED"), any(), any(), isNull(), after.capture());
        Map<String, Object> audit = after.getValue();
        String text = audit.toString();
        assertThat(text).doesNotContain(SECRET_NAME).doesNotContain(SECRET_COMPLAINT).doesNotContain(SECRET_MOBILE);
        assertThat(audit.keySet()).doesNotContain("age", "name", "displayName", "mobile", "complaint");
        // The age (52) is checked against the values that are not random ids or clock times.
        Map<String, Object> fixedValues = new java.util.LinkedHashMap<>(audit);
        fixedValues.remove("caseId");
        fixedValues.remove("arrivedAt");
        assertThat(fixedValues.values()).extracting(String::valueOf).doesNotContain("52");
        assertThat(fixedValues.toString()).doesNotContain("52");
    }

    @Test
    void onlyLevel1GetsABayAndTheLowestFreeOneUntilTheBaysRunOut() {
        CaseRow first = register(1);
        CaseRow second = register(1);
        CaseRow third = register(1);
        CaseRow yellow = register(2);

        assertThat(first.bay()).isEqualTo("ER-1");
        assertThat(second.bay()).isEqualTo("ER-2");
        assertThat(third.bay()).isNull();
        assertThat(yellow.bay()).isNull();
    }

    @Test
    void twoRedArrivalsNeverShareABayAndAFreedBayIsReused() {
        CaseRow first = register(1);
        register(1);

        service.release(TENANT, ACTOR, "NURSE", first.id());
        CaseRow next = register(1);

        assertThat(next.bay()).isEqualTo("ER-1");
        assertThat(cases.rows.values().stream().filter(CaseRow::open).map(CaseRow::bay).toList())
                .containsExactlyInAnyOrder("ER-1", "ER-2");
    }

    @Test
    void withNoBaysNobodyGetsOne() {
        setup = new EmergencySetup(3, List.of(5, 30, 120), List.of(0, 10, 30, 60, 120), 0, true);

        assertThat(register(1).bay()).isNull();
    }

    @Test
    void levelOneOfTheFiveLevelScaleIsTheMostUrgentAndGetsABay() {
        setup = new EmergencySetup(5, List.of(5, 30, 120), List.of(0, 10, 30, 60, 120), 2, true);

        assertThat(register(1).bay()).isEqualTo("ER-1");
        assertThat(register(2).bay()).isNull();
    }

    @Test
    void anUnknownPatientIsNamedBySexAndAgeAndIsIdentityPending() {
        CaseInput unknown = caseInput(UUID.randomUUID().toString(), true, "ignored name", new BigDecimal("40"),
                "F", null, "Unconscious", BigDecimal.valueOf(2));

        Case acted = full(service.register(TENANT, ACTOR, "NURSE", unknown).queue());

        assertThat(acted.displayName()).isEqualTo("Unknown female, about 40");
        assertThat(acted.identityPending()).isTrue();
        assertThat(cases.lastInsert.displayName()).doesNotContain("ignored");
    }

    @Test
    void anUnknownPatientWithNoAgeShowsAQuestionMark() {
        CaseInput unknown = caseInput(UUID.randomUUID().toString(), true, null, null, "O", null, "Found down",
                BigDecimal.valueOf(1));

        assertThat(full(service.register(TENANT, ACTOR, "NURSE", unknown).queue()).displayName())
                .isEqualTo("Unknown person, about ?");
    }

    @Test
    void aRepeatOfTheClientKeyReturnsTheFirstCaseAndWritesNothing() {
        UUID key = UUID.randomUUID();
        Registration first = service.register(TENANT, ACTOR, "NURSE", input(key, SECRET_NAME, 1));
        int inserts = cases.inserts;

        Registration again = service.register(TENANT, ACTOR, "NURSE", input(key, "Someone Else", 3));

        assertThat(again.created()).isFalse();
        assertThat(again.queue().actedCase().id()).isEqualTo(first.queue().actedCase().id());
        assertThat(full(again.queue()).displayName()).isEqualTo(SECRET_NAME);
        assertThat(cases.inserts).isEqualTo(inserts);
        verify(auditLog, org.mockito.Mockito.times(1)).record(eq("IPD_ER_REGISTERED"), any(), any(), any(), any());
        verify(events, org.mockito.Mockito.times(1)).insert(any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void aRepeatOfTheKeyIgnoresAnInvalidBodyAndAnswersAClosedCaseInItsCurrentState() {
        UUID key = UUID.randomUUID();
        UUID id = service.register(TENANT, ACTOR, "NURSE", input(key, SECRET_NAME, 2)).queue().actedCase().id();
        service.release(TENANT, ACTOR, "NURSE", id);
        CaseInput broken = caseInput(key.toString(), false, "", null, null, null, "", null);

        Registration again = service.register(TENANT, ACTOR, "NURSE", broken);

        assertThat(again.created()).isFalse();
        assertThat(again.queue().actedCase().status()).isEqualTo("RELEASED");
        assertThat(again.queue().cases()).isEmpty();
    }

    @Test
    void validationReportsAllFieldErrorsTogetherInTheContractOrder() {
        setup = new EmergencySetup(3, List.of(5, 30, 120), List.of(0, 10, 30, 60, 120), 2, false);
        CaseInput bad = caseInput(UUID.randomUUID().toString(), true, null, new BigDecimal("121"), "X", "12345",
                "  ", BigDecimal.valueOf(4));

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", bad))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                        new FieldMessage("identityUnknown", "Unknown patient registration is off. Enter a name."),
                        new FieldMessage("age", "Enter the age as a whole number from 0 to 120, or leave it empty"),
                        new FieldMessage("sex", "Choose male, female or other"),
                        new FieldMessage("mobile", "Enter a 10-digit mobile number, or leave it empty"),
                        new FieldMessage("complaint", "Enter the main complaint"),
                        new FieldMessage("level", "Choose a triage level")));
        assertThat(cases.inserts).isZero();
        verifyNoInteractions(auditLog);
    }

    @Test
    void aNameOrComplaintOfNoBreakSpacesIsEmptyAndIs400() {
        CaseInput bad = caseInput(UUID.randomUUID().toString(), false, "\u00a0\u2007\u202f", null, "M", null,
                "\u3000\u00a0", BigDecimal.valueOf(2));

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", bad))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                        new FieldMessage("name", "Enter a name, or tick \"Name not known yet\""),
                        new FieldMessage("complaint", "Enter the main complaint")));
    }

    @Test
    void aBlankNameWithoutTheUnknownBoxGetsTheContractMessage() {
        CaseInput bad = caseInput(UUID.randomUUID().toString(), false, "   ", null, "M", null, "Fever",
                BigDecimal.valueOf(2));

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", bad))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                        new FieldMessage("name", "Enter a name, or tick \"Name not known yet\"")));
    }

    @Test
    void nameAndComplaintLengthsCountCodePoints() {
        String name80 = "a".repeat(79) + "😀";
        String complaint200 = "c".repeat(199) + "😀";
        service.register(TENANT, ACTOR, "NURSE", caseInput(UUID.randomUUID().toString(), false, name80, null, "M",
                null, complaint200, BigDecimal.valueOf(2)));

        CaseInput tooLong = caseInput(UUID.randomUUID().toString(), false, name80 + "b", null, "M", null,
                complaint200 + "c", BigDecimal.valueOf(2));
        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", tooLong))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                        new FieldMessage("name", "Enter a name of up to 80 characters"),
                        new FieldMessage("complaint", "Enter the main complaint in up to 200 characters")));
    }

    @Test
    void ageMustBeAWholeNumberAndMobileMustStartWithSixToNine() {
        for (String age : new String[] {"40.5", "-1", "121"}) {
            CaseInput bad = caseInput(UUID.randomUUID().toString(), false, "A", new BigDecimal(age), "M", null,
                    "Fever", BigDecimal.valueOf(2));
            assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", bad))
                    .as(age).isInstanceOf(InvalidFieldsException.class);
        }
        for (String mobile : new String[] {"5876543210", "98765432", "98765432101", "98765 43210", "abcdefghij"}) {
            CaseInput bad = caseInput(UUID.randomUUID().toString(), false, "A", null, "M", mobile, "Fever",
                    BigDecimal.valueOf(2));
            assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", bad))
                    .as(mobile).isInstanceOf(InvalidFieldsException.class);
        }
        CaseInput ok = caseInput(UUID.randomUUID().toString(), false, "A", new BigDecimal("0"), "M", "  ", "Fever",
                BigDecimal.valueOf(2));
        service.register(TENANT, ACTOR, "NURSE", ok);
        assertThat(cases.lastInsert.mobile()).isNull();
        assertThat(cases.lastInsert.age()).isZero();
    }

    @Test
    void controlAndTextDirectionCharactersAreRefusedInNameAndComplaint() {
        CaseInput bad = caseInput(UUID.randomUUID().toString(), false, "Ra‮mesh", null, "M", null,
                "Chest\u0007pain", BigDecimal.valueOf(2));

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", bad))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting(FieldMessage::field).containsExactly("name", "complaint"));
    }

    @Test
    void theLevelMustBeInsideTheClinicsScale() {
        CaseInput bad = input(UUID.randomUUID(), "A", 4);

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", bad))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .containsExactly(new FieldMessage("level", "Choose a triage level")));
        setup = new EmergencySetup(5, List.of(5, 30, 120), List.of(0, 10, 30, 60, 120), 2, true);
        service.register(TENANT, ACTOR, "NURSE", bad);
        assertThat(cases.lastInsert.level()).isEqualTo(4);
    }

    @Test
    void checkOrderIsRoleThenModuleOffThenReadOnlyThenClientKeyThenBody() {
        CaseInput noKey = caseInput(null, false, "A", null, "M", null, "Fever", BigDecimal.valueOf(2));
        CaseInput badKey = caseInput("not-a-uuid", false, "A", null, "M", null, "Fever", BigDecimal.valueOf(2));

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "BILLING", noKey))
                .isInstanceOf(AccessDeniedException.class);
        state("off");
        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", noKey)).isInstanceOf(ModuleOffException.class);
        state("readonly");
        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", noKey)).isInstanceOfSatisfying(
                ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        state("on");
        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", noKey)).isInstanceOfSatisfying(
                InvalidInputException.class, e -> {
                    assertThat(e.field()).isEqualTo("clientKey");
                    assertThat(e.getMessage()).isEqualTo("Reload the page and try again.");
                });
        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", badKey))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", null))
                .isInstanceOf(InvalidInputException.class);
        verifyNoInteractions(wards);
    }

    @Test
    void aClientKeyNotInTheCanonicalUuidFormIsRefusedOnTheFieldClientKey() {
        for (String key : new String[] {"1-1-1-1-1", "12345678-1234-1234-1234-12345678901",
                "1234567g-1234-1234-1234-123456789012", "+1234567-1234-1234-1234-123456789012"}) {
            CaseInput bad = caseInput(key, false, "A", null, "M", null, "Fever", BigDecimal.valueOf(2));

            assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", bad)).isInstanceOfSatisfying(
                    InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("clientKey");
                        assertThat(e.getMessage()).isEqualTo("Reload the page and try again.");
                    });
        }
    }

    @Test
    void everyRegisterRoleMayRegisterAndOthersMayNot() {
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            service.register(TENANT, ACTOR, role, input(UUID.randomUUID(), "A", 3));
        }
        for (String role : new String[] {"BILLING", "PHARMACIST", "COUNSELLOR", "VISITING_DOCTOR", null}) {
            assertThatThrownBy(() -> service.register(TENANT, ACTOR, role, input(UUID.randomUUID(), "A", 3)))
                    .as(String.valueOf(role)).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void theClinicLockIsTakenBeforeTheKeyLookup() {
        UUID key = UUID.randomUUID();
        FakeCases spyCases = cases;
        service.register(TENANT, ACTOR, "NURSE", input(key, "A", 2));

        InOrder order = inOrder(wards);
        order.verify(wards).lockTenant(TENANT);
        assertThat(spyCases.rows).hasSize(1);
    }

    @Test
    void aUniqueViolationUnderTheLockIsReportedAsAServerErrorNotIgnored() {
        cases.failNextInsertWith = new DuplicateKeyException("ux_emergency_case_tenant_bay_open");

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", input(UUID.randomUUID(), "A", 1)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(auditLog);
    }

    // ---- queue ----

    @Test
    void theQueueIsOrderedByLevelThenLongestWaitThenId() {
        CaseRow green = cases.seed(3, "WAITING", 50, null);
        CaseRow yellowNew = cases.seed(2, "WAITING", 5, null);
        CaseRow yellowOld = cases.seed(2, "SEEN", 60, null);
        CaseRow red = cases.seed(1, "WAITING", 1, "ER-1");

        Queue queue = service.queue(TENANT, "NURSE");

        assertThat(queue.cases()).extracting(Case::id)
                .containsExactly(red.id(), yellowOld.id(), yellowNew.id(), green.id());
    }

    @Test
    void waitingMinutesAndOverTargetComeFromTheServerClockAndTheClinicTargets() {
        CaseRow within = cases.seed(2, "WAITING", 30, null);
        CaseRow over = cases.seed(2, "WAITING", 31, null);
        CaseRow seen = cases.seed(2, "SEEN", 300, null);

        Queue queue = service.queue(TENANT, "NURSE");

        Case withinView = byId(queue, within.id());
        assertThat(withinView.waitingMinutes()).isEqualTo(30);
        assertThat(withinView.targetMinutes()).isEqualTo(30);
        assertThat(withinView.overTarget()).isFalse();
        assertThat(byId(queue, over.id()).overTarget()).isTrue();
        assertThat(byId(queue, seen.id()).overTarget()).isFalse();
        assertThat(queue.counts().overTarget()).isEqualTo(1);
        assertThat(queue.counts().queue()).isEqualTo(3);
        assertThat(queue.asOf()).isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void aTargetOfNowMakesAnyWaitOfOneMinuteOverTarget() {
        setup = new EmergencySetup(5, List.of(5, 30, 120), List.of(0, 10, 30, 60, 120), 2, true);
        CaseRow fresh = cases.seed(1, "WAITING", 0, "ER-1");
        CaseRow minute = cases.seed(1, "WAITING", 1, "ER-2");

        Queue queue = service.queue(TENANT, "OWNER");

        assertThat(byId(queue, fresh.id()).targetMinutes()).isZero();
        assertThat(byId(queue, fresh.id()).overTarget()).isFalse();
        assertThat(byId(queue, minute.id()).overTarget()).isTrue();
    }

    @Test
    void theQueueCarriesLevelsDefaultLevelBaysAndTheSetupSwitch() {
        cases.seed(1, "WAITING", 1, "ER-1");

        Queue queue = service.queue(TENANT, "DOCTOR");

        assertThat(queue.state()).isEqualTo("on");
        assertThat(queue.scale()).isEqualTo(3);
        assertThat(queue.levels()).hasSize(3);
        assertThat(queue.defaultLevel()).isEqualTo(2);
        assertThat(queue.unknownPatientRegistration()).isTrue();
        assertThat(queue.baysTotal()).isEqualTo(2);
        assertThat(queue.baysFree()).isEqualTo(1);
        assertThat(queue.actedCase()).isNull();
    }

    @Test
    void anEmptyQueueHasNoCasesAndZeroCounts() {
        Queue queue = service.queue(TENANT, "NURSE");

        assertThat(queue.cases()).isEmpty();
        assertThat(queue.counts().queue()).isZero();
        assertThat(queue.counts().overTarget()).isZero();
        assertThat(queue.baysFree()).isEqualTo(2);
    }

    @Test
    void theFlagsFollowTheRoleAndAreAllFalseInReadOnly() {
        assertFlags("OWNER", true, true, true, true);
        assertFlags("FRONT_DESK", true, true, false, true);
        assertFlags("NURSE", true, true, true, true);
        assertFlags("DOCTOR", true, true, true, false);
        state("trial");
        assertFlags("NURSE", true, true, true, true);
        state("readonly");
        for (String role : new String[] {"OWNER", "FRONT_DESK", "NURSE", "DOCTOR"}) {
            assertFlags(role, false, false, false, false);
        }
        assertThat(service.queue(TENANT, "NURSE").counts().writable()).isFalse();
    }

    private void assertFlags(String role, boolean register, boolean retriage, boolean act, boolean admit) {
        Queue queue = service.queue(TENANT, role);
        assertThat(queue.canRegister()).as(role + " register").isEqualTo(register);
        assertThat(queue.canRetriage()).as(role + " retriage").isEqualTo(retriage);
        assertThat(queue.canAct()).as(role + " act").isEqualTo(act);
        assertThat(queue.canAdmit()).as(role + " admit").isEqualTo(admit);
    }

    @Test
    void readingAllowedInReadOnlyButNotWhenOffOrForOtherRoles() {
        state("readonly");
        assertThat(service.queue(TENANT, "NURSE").state()).isEqualTo("readonly");
        state("off");
        assertThatThrownBy(() -> service.queue(TENANT, "NURSE")).isInstanceOf(ModuleOffException.class);
        assertThatThrownBy(() -> service.summary(TENANT, "NURSE")).isInstanceOf(ModuleOffException.class);
        state("on");
        assertThatThrownBy(() -> service.queue(TENANT, "BILLING")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.summary(TENANT, "VISITING_DOCTOR")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void readingTakesNoLockAndWritesNothing() {
        cases.seed(2, "WAITING", 10, null);

        service.queue(TENANT, "NURSE");
        service.summary(TENANT, "NURSE");

        verifyNoInteractions(wards, auditLog, events);
        assertThat(cases.updates).isZero();
    }

    @Test
    void theSummaryHoldsCountsOnly() {
        cases.seed(2, "WAITING", 40, null);
        cases.seed(3, "SEEN", 400, null);
        cases.seed(3, "RELEASED", 400, null);

        EmergencyViews.Counts counts = service.summary(TENANT, "FRONT_DESK");

        assertThat(counts).isEqualTo(new EmergencyViews.Counts(true, 2, 1));
    }

    @Test
    void theCardsNeverCarryTheMobile() {
        cases.seed(2, "WAITING", 1, null);

        assertThat(service.queue(TENANT, "NURSE").cases().get(0).toString()).doesNotContain(SECRET_MOBILE);
        assertThat(java.util.Arrays.stream(Case.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("mobile");
    }

    // ---- seen ----

    @Test
    void doctorHasSeenMarksTheCaseSeenOnceAndAuditsIt() {
        CaseRow waiting = cases.seed(2, "WAITING", 10, null);

        Queue queue = service.seen(TENANT, ACTOR, "DOCTOR", waiting.id());

        assertThat(queue.actedCase().status()).isEqualTo("SEEN");
        assertThat(full(queue).seenAt()).isNotNull();
        assertThat(queue.cases()).hasSize(1);
        CaseRow stored = cases.rows.get(waiting.id());
        assertThat(stored.seenBy()).isEqualTo(ACTOR);
        Map<String, Object> after = lastAudit("IPD_ER_SEEN", waiting.id());
        assertThat(after.keySet()).containsExactlyInAnyOrder("caseId", "level", "seenAt");
    }

    @Test
    void aSecondDoctorHasSeenIsAnsweredAsItIsAndWritesNothing() {
        CaseRow waiting = cases.seed(2, "WAITING", 10, null);
        service.seen(TENANT, ACTOR, "DOCTOR", waiting.id());
        int updates = cases.updates;

        Queue again = service.seen(TENANT, ACTOR, "NURSE", waiting.id());

        assertThat(again.actedCase().status()).isEqualTo("SEEN");
        assertThat(cases.updates).isEqualTo(updates);
        verify(auditLog, org.mockito.Mockito.times(1)).record(eq("IPD_ER_SEEN"), any(), any(), any(), any());
    }

    @Test
    void seenIsForOwnerNurseAndDoctorOnly() {
        CaseRow waiting = cases.seed(2, "WAITING", 10, null);

        assertThatThrownBy(() -> service.seen(TENANT, ACTOR, "FRONT_DESK", waiting.id()))
                .isInstanceOf(AccessDeniedException.class);
        service.seen(TENANT, ACTOR, "OWNER", waiting.id());
    }

    @Test
    void anActionOnAClosedCaseAnswers409CaseClosedWithTheContractMessage() {
        for (String status : new String[] {"ADMITTED", "RELEASED", "REFERRED"}) {
            CaseRow closed = cases.seed(2, status, 10, null);
            assertCaseClosed(() -> service.seen(TENANT, ACTOR, "NURSE", closed.id()));
            assertCaseClosed(() -> service.release(TENANT, ACTOR, "NURSE", closed.id()));
            assertCaseClosed(() -> service.refer(TENANT, ACTOR, "NURSE", closed.id()));
            assertCaseClosed(() -> service.retriage(TENANT, ACTOR, "NURSE", closed.id(), level(1)));
        }
        assertThat(cases.updates).isZero();
        verifyNoInteractions(auditLog);
    }

    private void assertCaseClosed(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConflictException.class, e -> {
            assertThat(e.problemType()).isEqualTo("case-closed");
            assertThat(e.getMessage()).isEqualTo("This patient is no longer in the queue.");
        });
    }

    @Test
    void anUnknownCaseAnswers404AfterTheLock() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> service.seen(TENANT, ACTOR, "NURSE", missing)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.release(TENANT, ACTOR, "NURSE", missing)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.retriage(TENANT, ACTOR, "NURSE", missing, level(2)))
                .isInstanceOf(NotFoundException.class);
        verify(wards, org.mockito.Mockito.times(3)).lockTenant(TENANT);
    }

    @Test
    void writesCheckModuleOffThenReadOnlyBeforeTouchingTheCase() {
        CaseRow waiting = cases.seed(2, "WAITING", 10, null);
        state("off");
        assertThatThrownBy(() -> service.seen(TENANT, ACTOR, "NURSE", waiting.id())).isInstanceOf(ModuleOffException.class);
        state("readonly");
        assertThatThrownBy(() -> service.seen(TENANT, ACTOR, "NURSE", waiting.id())).isInstanceOfSatisfying(
                ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        assertThatThrownBy(() -> service.release(TENANT, ACTOR, "NURSE", waiting.id()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.retriage(TENANT, ACTOR, "NURSE", waiting.id(), level(1)))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(wards);
    }

    // ---- re-triage ----

    private static LevelInput level(int value) {
        return levelInput(BigDecimal.valueOf(value));
    }

    private static LevelInput levelInput(BigDecimal value) {
        return new LevelInput(value == null ? null : JsonNodeFactory.instance.numberNode(value));
    }

    @Test
    void retriageChangesOnlyTheLevelAndLogsWhoAndWhen() {
        CaseRow red = cases.seed(2, "WAITING", 25, null);

        Queue queue = service.retriage(TENANT, ACTOR, "FRONT_DESK", red.id(), level(1));

        Case acted = full(queue);
        assertThat(acted.level()).isEqualTo(1);
        assertThat(acted.levelName()).isEqualTo("Red");
        assertThat(acted.arrivedAt()).isEqualTo(red.arrivedAt());
        assertThat(acted.waitingMinutes()).isEqualTo(25);
        assertThat(acted.bay()).isNull();
        verify(events).insert(TENANT, red.id(), 2, 1, 3, "RETRIAGED", ACTOR);
        ArgumentCaptor<Map<String, Object>> before = captorOfMap();
        ArgumentCaptor<Map<String, Object>> after = captorOfMap();
        verify(auditLog).record(eq("IPD_ER_RETRIAGED"), any(), eq(red.id()), before.capture(), after.capture());
        assertThat(before.getValue()).containsEntry("level", 2);
        assertThat(after.getValue().keySet()).containsExactlyInAnyOrder("caseId", "level", "scale", "at");
    }

    @Test
    void retriageNeverGivesOrTakesABay() {
        CaseRow red = cases.seed(1, "WAITING", 25, "ER-1");

        assertThat(full(service.retriage(TENANT, ACTOR, "NURSE", red.id(), level(3))).bay()).isEqualTo("ER-1");
        CaseRow yellow = cases.seed(2, "WAITING", 25, null);
        assertThat(full(service.retriage(TENANT, ACTOR, "NURSE", yellow.id(), level(1))).bay()).isNull();
    }

    @Test
    void theSameLevelWritesNothing() {
        CaseRow yellow = cases.seed(2, "WAITING", 25, null);

        Queue queue = service.retriage(TENANT, ACTOR, "NURSE", yellow.id(), level(2));

        assertThat(queue.actedCase().level()).isEqualTo(2);
        assertThat(cases.updates).isZero();
        verifyNoInteractions(auditLog, events);
    }

    @Test
    void retriageWorksOnASeenCase() {
        CaseRow seen = cases.seed(2, "SEEN", 25, null);

        assertThat(service.retriage(TENANT, ACTOR, "DOCTOR", seen.id(), level(3)).actedCase().level()).isEqualTo(3);
        assertThat(cases.rows.get(seen.id()).status()).isEqualTo("SEEN");
    }

    @Test
    void retriageRefusesALevelOutsideTheScaleBeforeAndAfterTheLock() {
        CaseRow yellow = cases.seed(2, "WAITING", 25, null);

        for (BigDecimal bad : new BigDecimal[] {null, new BigDecimal("0"), new BigDecimal("6"), new BigDecimal("2.5")}) {
            assertThatThrownBy(() -> service.retriage(TENANT, ACTOR, "NURSE", yellow.id(), levelInput(bad)))
                    .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                        assertThat(e.field()).isEqualTo("level");
                        assertThat(e.getMessage()).isEqualTo("Choose a triage level");
                    });
        }
        verifyNoInteractions(wards);
        assertThatThrownBy(() -> service.retriage(TENANT, ACTOR, "NURSE", yellow.id(), level(4)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.retriage(TENANT, ACTOR, "NURSE", yellow.id(), null))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aBadLevelIsAnsweredBeforeAMissingCase() {
        assertThatThrownBy(() -> service.retriage(TENANT, ACTOR, "NURSE", UUID.randomUUID(), level(9)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void retriageIsForTheFourReadRoles() {
        CaseRow yellow = cases.seed(2, "WAITING", 25, null);

        assertThatThrownBy(() -> service.retriage(TENANT, ACTOR, "BILLING", yellow.id(), level(1)))
                .isInstanceOf(AccessDeniedException.class);
        service.retriage(TENANT, ACTOR, "DOCTOR", yellow.id(), level(1));
    }

    // ---- release and refer ----

    @Test
    void releaseClosesTheCaseFreesTheBayAndAuditsIdsLevelBayAndTime() {
        CaseRow red = cases.seed(1, "SEEN", 30, "ER-1");

        Queue queue = service.release(TENANT, ACTOR, "NURSE", red.id());

        assertThat(queue.actedCase().status()).isEqualTo("RELEASED");
        assertThat(queue.cases()).isEmpty();
        assertThat(queue.baysFree()).isEqualTo(2);
        CaseRow stored = cases.rows.get(red.id());
        assertThat(stored.closedBy()).isEqualTo(ACTOR);
        assertThat(stored.seenAt()).isEqualTo(red.seenAt());
        Map<String, Object> after = lastAudit("IPD_ER_RELEASED", red.id());
        assertThat(after.keySet()).containsExactlyInAnyOrder("caseId", "level", "bay", "closedAt");
        assertThat(after.get("bay")).isEqualTo("ER-1");
    }

    @Test
    void referClosesTheCaseAsReferred() {
        CaseRow red = cases.seed(2, "WAITING", 30, null);

        Queue queue = service.refer(TENANT, ACTOR, "DOCTOR", red.id());

        assertThat(queue.actedCase().status()).isEqualTo("REFERRED");
        lastAudit("IPD_ER_REFERRED", red.id());
    }

    @Test
    void aRepeatedTapOnReleaseAnswersCaseClosed() {
        CaseRow yellow = cases.seed(2, "WAITING", 30, null);
        service.release(TENANT, ACTOR, "NURSE", yellow.id());

        assertCaseClosed(() -> service.release(TENANT, ACTOR, "NURSE", yellow.id()));
        assertCaseClosed(() -> service.refer(TENANT, ACTOR, "NURSE", yellow.id()));
    }

    @Test
    void releaseAndReferAreForOwnerNurseAndDoctorOnly() {
        CaseRow yellow = cases.seed(2, "WAITING", 30, null);

        assertThatThrownBy(() -> service.release(TENANT, ACTOR, "FRONT_DESK", yellow.id()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.refer(TENANT, ACTOR, "FRONT_DESK", yellow.id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theStatusIsCheckedOnTheLockedRowAfterTheClinicLock() {
        CaseRow yellow = cases.seed(2, "WAITING", 30, null);

        service.release(TENANT, ACTOR, "NURSE", yellow.id());

        InOrder order = inOrder(wards);
        order.verify(wards).lockTenant(TENANT);
        assertThat(cases.lockedReads).isEqualTo(1);
    }

    // ---- Emergency setup ----

    @Test
    void settingsDefaultsAndCanEditOnlyForTheOwnerOutsideReadOnly() {
        Settings owner = service.settings(TENANT, "OWNER");

        assertThat(owner.scale()).isEqualTo(3);
        assertThat(owner.targets3()).containsExactly(5, 30, 120);
        assertThat(owner.targets5()).containsExactly(0, 10, 30, 60, 120);
        assertThat(owner.bays()).isEqualTo(2);
        assertThat(owner.unknownPatientRegistration()).isTrue();
        assertThat(owner.levels()).hasSize(3);
        assertThat(owner.canEdit()).isTrue();
        assertThat(service.settings(TENANT, "NURSE").canEdit()).isFalse();
        state("readonly");
        assertThat(service.settings(TENANT, "OWNER").canEdit()).isFalse();
        assertThatThrownBy(() -> service.settings(TENANT, "BILLING")).isInstanceOf(AccessDeniedException.class);
        state("off");
        assertThatThrownBy(() -> service.settings(TENANT, "OWNER")).isInstanceOf(ModuleOffException.class);
    }

    private static SettingsInput settingsInput(int scale, int[] t3, int[] t5, int bays, boolean unknown) {
        return new SettingsInput(BigDecimal.valueOf(scale), toList(t3), toList(t5), BigDecimal.valueOf(bays), unknown);
    }

    private static List<BigDecimal> toList(int[] values) {
        List<BigDecimal> list = new ArrayList<>();
        for (int v : values) {
            list.add(BigDecimal.valueOf(v));
        }
        return list;
    }

    @Test
    void anUnchangedSettingWritesAndAuditsNothing() {
        Settings saved = service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(3, new int[] {5, 30, 120}, new int[] {0, 10, 30, 60, 120}, 2, true));

        assertThat(saved.canEdit()).isTrue();
        verify(settings, never()).upsertEmergencySetup(any(), any(), any(), any());
        verifyNoInteractions(auditLog, events);
    }

    @Test
    void aChangeSavesTheWholeSettingAndAuditsBeforeAndAfter() {
        Settings saved = service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(3, new int[] {10, 30, 120}, new int[] {0, 10, 30, 60, 120}, 4, false));

        assertThat(saved.bays()).isEqualTo(4);
        assertThat(saved.levels().get(0).targetMinutes()).isEqualTo(10);
        ArgumentCaptor<EmergencySetup> stored = ArgumentCaptor.forClass(EmergencySetup.class);
        verify(settings).upsertEmergencySetup(eq(TENANT), stored.capture(), eq(ACTOR), any());
        assertThat(stored.getValue().unknownPatientRegistration()).isFalse();
        ArgumentCaptor<Map<String, Object>> before = captorOfMap();
        ArgumentCaptor<Map<String, Object>> after = captorOfMap();
        verify(auditLog).record(eq("IPD_ER_SETTINGS_UPDATED"), eq("IPD_CONFIG"), isNull(), before.capture(),
                after.capture());
        assertThat(before.getValue().keySet())
                .containsExactly("scale", "targets3", "targets5", "bays", "unknownPatientRegistration");
        assertThat(after.getValue().keySet()).containsExactly("scale", "targets3", "targets5", "bays",
                "unknownPatientRegistration", "remappedCases");
        assertThat(before.getValue().get("bays")).isEqualTo(2);
        assertThat(after.getValue().get("bays")).isEqualTo(4);
        assertThat(after.getValue().get("remappedCases")).isEqualTo(0);
    }

    @Test
    void bothTargetSetsAreKeptAndStoredAsSent() {
        service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(5, new int[] {15, 45, 90}, new int[] {0, 5, 20, 60, 240}, 2, true));

        ArgumentCaptor<EmergencySetup> stored = ArgumentCaptor.forClass(EmergencySetup.class);
        verify(settings).upsertEmergencySetup(eq(TENANT), stored.capture(), eq(ACTOR), any());
        assertThat(stored.getValue().targets3()).containsExactly(15, 45, 90);
        assertThat(stored.getValue().targets5()).containsExactly(0, 5, 20, 60, 240);
    }

    @Test
    void movingFromThreeColoursToFiveLevelsMapsEveryOpenCaseAndLogsIt() {
        CaseRow red = cases.seed(1, "WAITING", 5, "ER-1");
        CaseRow yellow = cases.seed(2, "SEEN", 5, null);
        CaseRow green = cases.seed(3, "WAITING", 5, null);
        CaseRow closed = cases.seed(1, "RELEASED", 5, null);

        service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(5, new int[] {5, 30, 120}, new int[] {0, 10, 30, 60, 120}, 2, true));

        assertThat(cases.rows.get(red.id()).level()).isEqualTo(2);
        assertThat(cases.rows.get(yellow.id()).level()).isEqualTo(3);
        assertThat(cases.rows.get(green.id()).level()).isEqualTo(5);
        assertThat(cases.rows.get(closed.id()).level()).isEqualTo(1);
        verify(events).insert(TENANT, red.id(), 1, 2, 5, "SCALE_CHANGED", ACTOR);
        verify(events).insert(TENANT, yellow.id(), 2, 3, 5, "SCALE_CHANGED", ACTOR);
        verify(events).insert(TENANT, green.id(), 3, 5, 5, "SCALE_CHANGED", ACTOR);
        verify(events, never()).insert(any(), eq(closed.id()), any(), anyInt(), anyInt(), any(), any());
        assertThat(cases.rows.get(red.id()).bay()).isEqualTo("ER-1");
        assertThat(cases.rows.get(red.id()).arrivedAt()).isEqualTo(red.arrivedAt());
        assertThat(cases.rows.get(red.id()).status()).isEqualTo("WAITING");
        ArgumentCaptor<Map<String, Object>> after = captorOfMap();
        verify(auditLog).record(eq("IPD_ER_SETTINGS_UPDATED"), any(), any(), any(), after.capture());
        assertThat(after.getValue().get("remappedCases")).isEqualTo(3);
    }

    @Test
    void movingFromFiveLevelsToThreeColoursMapsEveryOpenCaseAndLogsIt() {
        setup = new EmergencySetup(5, List.of(5, 30, 120), List.of(0, 10, 30, 60, 120), 2, true);
        CaseRow l1 = cases.seed(1, "WAITING", 5, "ER-1");
        CaseRow l2 = cases.seed(2, "WAITING", 5, null);
        CaseRow l3 = cases.seed(3, "WAITING", 5, null);
        CaseRow l4 = cases.seed(4, "WAITING", 5, null);
        CaseRow l5 = cases.seed(5, "SEEN", 5, null);

        service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(3, new int[] {5, 30, 120}, new int[] {0, 10, 30, 60, 120}, 2, true));

        assertThat(cases.rows.get(l1.id()).level()).isEqualTo(1);
        assertThat(cases.rows.get(l2.id()).level()).isEqualTo(1);
        assertThat(cases.rows.get(l3.id()).level()).isEqualTo(2);
        assertThat(cases.rows.get(l4.id()).level()).isEqualTo(3);
        assertThat(cases.rows.get(l5.id()).level()).isEqualTo(3);
        // Level 1 stays 1: the event is still written (from and to may be equal for a scale change).
        verify(events).insert(TENANT, l1.id(), 1, 1, 3, "SCALE_CHANGED", ACTOR);
        verify(events).insert(TENANT, l2.id(), 2, 1, 3, "SCALE_CHANGED", ACTOR);
    }

    @Test
    void newTargetsApplyAtOnceToTheQueue() {
        CaseRow yellow = cases.seed(2, "WAITING", 31, null);
        assertThat(byId(service.queue(TENANT, "NURSE"), yellow.id()).overTarget()).isTrue();

        service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(3, new int[] {5, 60, 120}, new int[] {0, 10, 30, 60, 120}, 2, true));

        Case after = byId(service.queue(TENANT, "NURSE"), yellow.id());
        assertThat(after.targetMinutes()).isEqualTo(60);
        assertThat(after.overTarget()).isFalse();
    }

    @Test
    void loweringTheBaysNeverTakesABayFromACase() {
        CaseRow red = cases.seed(1, "WAITING", 5, "ER-2");

        service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(3, new int[] {5, 30, 120}, new int[] {0, 10, 30, 60, 120}, 1, true));

        assertThat(cases.rows.get(red.id()).bay()).isEqualTo("ER-2");
        assertThat(cases.updates).isZero();
        Queue queue = service.queue(TENANT, "NURSE");
        assertThat(queue.baysTotal()).isEqualTo(1);
        assertThat(queue.baysFree()).isEqualTo(1);
    }

    @Test
    void settingsCheckOrderIsRoleThenModuleOffThenReadOnlyThenBody() {
        SettingsInput bad = new SettingsInput(null, null, null, null, null);

        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, "NURSE", bad))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(ownerGuard);
        state("off");
        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, "OWNER", bad)).isInstanceOf(ModuleOffException.class);
        state("readonly");
        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, "OWNER", bad)).isInstanceOfSatisfying(
                ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("ipd-readonly"));
        state("on");
        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, "OWNER", bad))
                .isInstanceOf(InvalidFieldsException.class);
        verify(ownerGuard, org.mockito.Mockito.atLeastOnce()).requireOwner(TENANT, ACTOR);
        verifyNoInteractions(wards);
    }

    @Test
    void aDemotedOwnerIsRefusedByTheOwnerReRead() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT, ACTOR);

        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(3, new int[] {5, 30, 120}, new int[] {0, 10, 30, 60, 120}, 2, true)))
                .isInstanceOf(AccessDeniedException.class);
        verify(settings, never()).upsertEmergencySetup(any(), any(), any(), any());
    }

    @Test
    void settingsValidationReportsEveryBadFieldWithTheContractMessages() {
        SettingsInput bad = new SettingsInput(new BigDecimal("4"), toList(new int[] {5, 30}),
                toList(new int[] {0, 10, 30, 60, 121}), new BigDecimal("11"), null);

        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, "OWNER", bad))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                        new FieldMessage("scale", "Choose 3 colours or 5 levels"),
                        new FieldMessage("targets3", "Choose a target from now to 240 minutes, in steps of 5"),
                        new FieldMessage("targets5", "Choose a target from now to 240 minutes, in steps of 5"),
                        new FieldMessage("bays", "Choose 0 to 10 bays"),
                        new FieldMessage("unknownPatientRegistration", "Choose on or off")));
    }

    @Test
    void aTargetMustBeAMultipleOfFiveAndAtMost240() {
        for (int bad : new int[] {7, 245, -5}) {
            SettingsInput input = settingsInput(3, new int[] {5, 30, bad}, new int[] {0, 10, 30, 60, 120}, 2, true);
            assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, "OWNER", input))
                    .as(String.valueOf(bad)).isInstanceOf(InvalidFieldsException.class);
        }
        service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(3, new int[] {0, 240, 5}, new int[] {0, 10, 30, 60, 120}, 0, true));
        SettingsInput fractional = new SettingsInput(new BigDecimal("3"), toList(new int[] {5, 30, 120}),
                toList(new int[] {0, 10, 30, 60, 120}), new BigDecimal("2.5"), true);
        assertThatThrownBy(() -> service.updateSettings(TENANT, ACTOR, "OWNER", fractional))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void settingsAreChangedUnderTheClinicLockBeforeTheCasesAreRead() {
        cases.seed(1, "WAITING", 5, null);

        service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(5, new int[] {5, 30, 120}, new int[] {0, 10, 30, 60, 120}, 2, true));

        InOrder order = inOrder(wards, settings, auditLog);
        order.verify(wards).lockTenant(TENANT);
        order.verify(settings).upsertEmergencySetup(eq(TENANT), any(), eq(ACTOR), any());
        order.verify(auditLog).record(eq("IPD_ER_SETTINGS_UPDATED"), any(), any(), any(), any());
    }

    // ---- the admit hand-off ----

    @Test
    void lockOpenCaseGives404ForUnknownAnd409ForClosed() {
        CaseRow closed = cases.seed(2, "ADMITTED", 5, null);
        CaseRow open = cases.seed(2, "WAITING", 5, null);

        assertThatThrownBy(() -> service.lockOpenCase(TENANT, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        assertCaseClosed(() -> service.lockOpenCase(TENANT, closed.id()));
        assertThat(service.lockOpenCase(TENANT, open.id()).id()).isEqualTo(open.id());
        assertThat(cases.lockedReads).isEqualTo(3);
    }

    @Test
    void findOpenCaseReadsWithoutALock() {
        CaseRow open = cases.seed(2, "SEEN", 5, null);
        CaseRow released = cases.seed(2, "RELEASED", 5, null);

        assertThat(service.findOpenCase(TENANT, open.id()).id()).isEqualTo(open.id());
        assertCaseClosed(() -> service.findOpenCase(TENANT, released.id()));
        assertThat(cases.lockedReads).isZero();
    }

    @Test
    void markAdmittedLinksTheAdmissionAndPatientInOneUpdateAndAuditsIdsOnly() {
        CaseRow red = cases.seed(1, "WAITING", 5, "ER-1");
        UUID admission = UUID.randomUUID();
        UUID patient = UUID.randomUUID();

        service.markAdmitted(TENANT, ACTOR, red, admission, patient);

        CaseRow stored = cases.rows.get(red.id());
        assertThat(stored.status()).isEqualTo("ADMITTED");
        assertThat(stored.admissionId()).isEqualTo(admission);
        assertThat(stored.patientId()).isEqualTo(patient);
        assertThat(stored.closedBy()).isEqualTo(ACTOR);
        assertThat(cases.updates).isEqualTo(1);
        Map<String, Object> after = lastAudit("IPD_ER_ADMITTED", red.id());
        assertThat(after.keySet()).containsExactlyInAnyOrder("caseId", "admissionId", "level", "bay", "closedAt");
        assertThat(after.toString()).doesNotContain(patient.toString());
    }

    @Test
    void handoffCarriesThePrefillAndTheSuggestedBed() {
        CaseRow red = cases.seed(1, "WAITING", 5, "ER-1");
        UUID icu = UUID.randomUUID();
        List<AdmissionViews.BedOption> free = List.of(
                new AdmissionViews.BedOption(UUID.randomUUID(), "101-A", "Ward", "gen", "General", 1L),
                new AdmissionViews.BedOption(icu, "ICU-1", "Ward", "icu", "ICU", 1L));

        EmergencyViews.Handoff handoff = service.handoff(TENANT, red, free);

        assertThat(handoff.caseId()).isEqualTo(red.id());
        assertThat(handoff.levelName()).isEqualTo("Red");
        assertThat(handoff.suggestedBedId()).isEqualTo(icu);
        assertThat(handoff.mobile()).isEqualTo(red.mobile());
        assertThat(handoff.toString()).doesNotContain(red.complaint()).doesNotContain(red.displayName());
    }

    // ---- review fixes: repeated key, strict types, mobile, one clock, one snapshot ----

    @Test
    void aRepeatOfTheKeyByTheRegistrantWhileTheCaseIsOpenAnswersTheFullCard() {
        UUID key = UUID.randomUUID();
        service.register(TENANT, ACTOR, "NURSE", input(key, SECRET_NAME, 2));

        Registration again = service.register(TENANT, ACTOR, "NURSE", input(key, "Other", 3));

        assertThat(again.created()).isFalse();
        assertThat(full(again.queue()).displayName()).isEqualTo(SECRET_NAME);
    }

    @Test
    void aRepeatOfTheKeyByAnotherUserAnswersTheReferenceOnly() {
        UUID key = UUID.randomUUID();
        UUID id = service.register(TENANT, ACTOR, "NURSE", input(key, SECRET_NAME, 2)).queue().actedCase().id();

        Registration again = service.register(TENANT, UUID.randomUUID(), "FRONT_DESK", input(key, "Other", 3));

        assertThat(again.created()).isFalse();
        assertThat(again.queue().actedCase()).isEqualTo(new CaseRef(id, 2, "WAITING"));
        assertThat(again.queue().actedCase().toString()).doesNotContain(SECRET_NAME);
        // the queue itself is the same list every reader gets; only the acted case is cut down
        assertThat(again.queue().cases()).hasSize(1);
    }

    @Test
    void aRepeatOfTheKeyAfterTheCaseClosedAnswersTheReferenceEvenForTheRegistrant() {
        UUID key = UUID.randomUUID();
        UUID id = service.register(TENANT, ACTOR, "NURSE", input(key, SECRET_NAME, 1)).queue().actedCase().id();
        service.release(TENANT, ACTOR, "NURSE", id);

        Registration again = service.register(TENANT, ACTOR, "NURSE", input(key, SECRET_NAME, 1));

        assertThat(again.queue().actedCase()).isEqualTo(new CaseRef(id, 1, "RELEASED"));
        assertThat(cases.inserts).isEqualTo(1);
    }

    @Test
    void aMissingNullOrNonBooleanIdentityUnknownIsAFieldErrorAndNeverFalse() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        JsonNode[] bad = {null, json.nullNode(), json.textNode("true"), json.textNode("false"), json.numberNode(1),
                json.objectNode()};
        for (JsonNode flag : bad) {
            CaseInput body = new CaseInput(UUID.randomUUID().toString(), flag, SECRET_NAME,
                    json.numberNode(40), "M", null, "Fever", json.numberNode(2));

            assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", body)).as(String.valueOf(flag))
                    .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                            new FieldMessage("identityUnknown", "Reload the page and try again.")));
        }
        assertThat(cases.inserts).isZero();
        verifyNoInteractions(auditLog, events);
    }

    @Test
    void anInvalidIdentityUnknownSkipsTheNameChecksButTheOtherFieldsAreStillChecked() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        CaseInput body = new CaseInput(UUID.randomUUID().toString(), json.textNode("yes"), "",
                json.numberNode(500), "M", null, "Fever", json.numberNode(2));

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", body))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                        new FieldMessage("identityUnknown", "Reload the page and try again."),
                        new FieldMessage("age", "Enter the age as a whole number from 0 to 120, or leave it empty")));
    }

    @Test
    void ageAndLevelSentAsJsonStringsAreRefusedWithTheFieldMessageAndNeverConverted() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        CaseInput body = new CaseInput(UUID.randomUUID().toString(), json.booleanNode(false), SECRET_NAME,
                json.textNode("40"), "M", null, "Fever", json.textNode("2"));

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", body))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                        new FieldMessage("age", "Enter the age as a whole number from 0 to 120, or leave it empty"),
                        new FieldMessage("level", "Choose a triage level")));
        assertThat(cases.inserts).isZero();
    }

    @Test
    void anAgeThatIsJsonNullOrMissingIsEmptyAndWholeNumbersWrittenAsDecimalsAreWhole() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        service.register(TENANT, ACTOR, "NURSE", new CaseInput(UUID.randomUUID().toString(), json.booleanNode(false),
                "A", json.nullNode(), "M", null, "Fever", json.numberNode(2)));
        assertThat(cases.lastInsert.age()).isNull();
        service.register(TENANT, ACTOR, "NURSE", new CaseInput(UUID.randomUUID().toString(), json.booleanNode(false),
                "A", json.numberNode(40.0), "M", null, "Fever", json.numberNode(2.0)));
        assertThat(cases.lastInsert.age()).isEqualTo(40);
        assertThat(cases.lastInsert.level()).isEqualTo(2);
    }

    @Test
    void aLevelSentAsAJsonStringIsRefusedOnRetriage() {
        CaseRow yellow = cases.seed(2, "WAITING", 25, null);

        assertThatThrownBy(() -> service.retriage(TENANT, ACTOR, "NURSE", yellow.id(),
                new LevelInput(JsonNodeFactory.instance.textNode("1"))))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> assertThat(e.field()).isEqualTo("level"));
        verifyNoInteractions(wards);
    }

    @Test
    void aNameWithoutALetterAndAComplaintWithoutALetterOrDigitAreRefusedWithTheBlankMessages() {
        CaseInput punctuation = caseInput(UUID.randomUUID().toString(), false, "-- . ..", null, "M", null, "?!",
                BigDecimal.valueOf(2));

        assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", punctuation))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).containsExactly(
                        new FieldMessage("name", "Enter a name, or tick \"Name not known yet\""),
                        new FieldMessage("complaint", "Enter the main complaint")));
        // a digit is enough for a complaint ("108"), a letter of another script is enough for a name
        service.register(TENANT, ACTOR, "NURSE", caseInput(UUID.randomUUID().toString(), false,
                "\u0930\u092E\u0947\u0936", null, "M", null, "108", BigDecimal.valueOf(2)));
        assertThat(cases.lastInsert.complaint()).isEqualTo("108");
    }

    @Test
    void invisibleAndBlankLookingCharactersAreRefusedInNameAndComplaint() {
        for (String bad : new String[] {"Ra\u3164mesh", "Ra\u2800mesh", "Ra\uDB40\uDC01mesh", "Ra\u00ADmesh",
                "Ra\uE000mesh"}) {
            CaseInput body = caseInput(UUID.randomUUID().toString(), false, bad, null, "M", null, bad,
                    BigDecimal.valueOf(2));
            assertThatThrownBy(() -> service.register(TENANT, ACTOR, "NURSE", body)).as(bad)
                    .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                            .extracting(FieldMessage::field).containsExactly("name", "complaint"));
        }
    }

    @Test
    void aLevelFiveCaseAgainstAThreeColourSetupNeverFailsAReadAndShowsTheLastColour() {
        CaseRow stale = cases.seed(5, "WAITING", 200, null);

        Queue queue = service.queue(TENANT, "NURSE");
        EmergencyViews.Counts counts = service.summary(TENANT, "NURSE");

        Case view = byId(queue, stale.id());
        assertThat(view.level()).isEqualTo(3);
        assertThat(view.levelName()).isEqualTo("Green");
        assertThat(view.targetMinutes()).isEqualTo(120);
        assertThat(view.overTarget()).isTrue();
        assertThat(counts).isEqualTo(new EmergencyViews.Counts(true, 1, 1));
    }

    @Test
    void aLevelFourCaseAgainstAThreeColourSetupAlsoDoesNotFailTheAdmitHandoff() {
        CaseRow stale = cases.seed(4, "WAITING", 5, null);

        EmergencyViews.Handoff handoff = service.handoff(TENANT, stale, List.of());

        assertThat(handoff.level()).isEqualTo(3);
        assertThat(handoff.levelName()).isEqualTo("Green");
    }

    @Test
    void closingACaseClearsItsMobileInTheSameUpdateAndAdmittingDoes() {
        CaseRow released = register(2);
        CaseRow referred = register(3);
        CaseRow admitted = register(2);
        assertThat(cases.rows.get(released.id()).mobile()).isEqualTo(SECRET_MOBILE);

        service.release(TENANT, ACTOR, "NURSE", released.id());
        service.refer(TENANT, ACTOR, "NURSE", referred.id());
        service.markAdmitted(TENANT, ACTOR, admitted, UUID.randomUUID(), UUID.randomUUID());

        assertThat(cases.rows.get(released.id()).mobile()).isNull();
        assertThat(cases.rows.get(referred.id()).mobile()).isNull();
        assertThat(cases.rows.get(admitted.id()).mobile()).isNull();
        assertThat(cases.rows.get(released.id()).status()).isEqualTo("RELEASED");
    }

    @Test
    void onlyTheHandoffReadsTheMobileAndEveryOtherAnswerWorksFromReadsWithoutIt() {
        CaseRow waiting = register(2);
        service.queue(TENANT, "NURSE");
        service.summary(TENANT, "NURSE");
        service.seen(TENANT, ACTOR, "DOCTOR", waiting.id());
        service.retriage(TENANT, ACTOR, "NURSE", waiting.id(), level(3));
        service.lockOpenCase(TENANT, waiting.id());
        assertThat(cases.handoffReads).isZero();

        CaseRow forHandoff = service.findOpenCase(TENANT, waiting.id());

        assertThat(cases.handoffReads).isEqualTo(1);
        assertThat(forHandoff.mobile()).isEqualTo(SECRET_MOBILE);
    }

    @Test
    void openingTheHandoffAuditsTheCaseIdAndNothingElse() {
        CaseRow red = cases.seed(1, "WAITING", 5, "ER-1");

        service.handoff(TENANT, red, List.of());

        Map<String, Object> after = lastAudit("IPD_ER_HANDOFF_OPENED", red.id());
        assertThat(after).containsOnlyKeys("caseId").containsEntry("caseId", red.id());
        assertThat(after.toString()).doesNotContain(red.displayName()).doesNotContain(red.complaint())
                .doesNotContain(SECRET_MOBILE);
        verify(auditLog).record(eq("IPD_ER_HANDOFF_OPENED"), eq(EmergencyService.ENTITY_CASE), eq(red.id()),
                isNull(), any());
    }

    @Test
    void aHandoffThatAnswers404Or409AuditsNothing() {
        CaseRow closed = cases.seed(2, "RELEASED", 5, null);

        assertThatThrownBy(() -> service.findOpenCase(TENANT, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        assertCaseClosed(() -> service.findOpenCase(TENANT, closed.id()));

        verifyNoInteractions(auditLog);
    }

    @Test
    void everyWriteStampsWithTheDatabaseClockReadOnceAfterTheClinicLockAndNeverTheApplicationClock() {
        Instant dbNow = NOW.plusSeconds(7).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        cases.clock = dbNow;
        CaseRow seenCase = cases.seed(2, "WAITING", 10, null);
        CaseRow released = cases.seed(2, "WAITING", 10, null);
        CaseRow referred = cases.seed(2, "WAITING", 10, null);
        CaseRow retriaged = cases.seed(2, "WAITING", 10, null);

        Queue seenAnswer = service.seen(TENANT, ACTOR, "DOCTOR", seenCase.id());
        assertThat(cases.clockReads).isEqualTo(1);
        service.release(TENANT, ACTOR, "NURSE", released.id());
        service.refer(TENANT, ACTOR, "NURSE", referred.id());
        service.retriage(TENANT, ACTOR, "NURSE", retriaged.id(), level(1));

        assertThat(cases.clockReadBeforeLock).isFalse();
        assertThat(cases.clockReads).isEqualTo(4);
        assertThat(cases.rows.get(seenCase.id()).seenAt()).isEqualTo(dbNow);
        assertThat(cases.rows.get(released.id()).closedAt()).isEqualTo(dbNow);
        assertThat(cases.rows.get(referred.id()).closedAt()).isEqualTo(dbNow);
        assertThat(seenAnswer.asOf()).isEqualTo(dbNow);
        assertThat(full(seenAnswer).seenAt()).isEqualTo(dbNow);
        assertThat(full(seenAnswer).waitingMinutes()).isEqualTo(10);
        assertThat(lastAudit("IPD_ER_SEEN", seenCase.id()).get("seenAt")).isEqualTo(dbNow.toString());
        assertThat(lastAudit("IPD_ER_RELEASED", released.id()).get("closedAt")).isEqualTo(dbNow.toString());
        assertThat(lastAudit("IPD_ER_REFERRED", referred.id()).get("closedAt")).isEqualTo(dbNow.toString());
        assertThat(lastAudit("IPD_ER_RETRIAGED", retriaged.id()).get("at")).isEqualTo(dbNow.toString());
    }

    @Test
    void anAdmissionStampsTheClosureWithTheDatabaseClock() {
        Instant dbNow = NOW.plusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        cases.clock = dbNow;
        CaseRow red = cases.seed(1, "WAITING", 5, "ER-1");

        service.markAdmitted(TENANT, ACTOR, red, UUID.randomUUID(), UUID.randomUUID());

        assertThat(cases.rows.get(red.id()).closedAt()).isEqualTo(dbNow);
        assertThat(lastAudit("IPD_ER_ADMITTED", red.id()).get("closedAt")).isEqualTo(dbNow.toString());
    }

    @Test
    void aNewCaseIsStampedByTheDatabaseAndTheAnswerIsAsOfThatInstantWithTheSameArrivalInTheAudit() {
        Instant dbNow = NOW.plusSeconds(9).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        cases.clock = dbNow;

        Registration result = service.register(TENANT, ACTOR, "NURSE", input(UUID.randomUUID(), SECRET_NAME, 2));

        Case acted = full(result.queue());
        assertThat(acted.arrivedAt()).isEqualTo(dbNow);
        assertThat(acted.waitingMinutes()).isZero();
        assertThat(result.queue().asOf()).isEqualTo(dbNow);
        assertThat(lastAudit("IPD_ER_REGISTERED", acted.id()).get("arrivedAt")).isEqualTo(dbNow.toString());
        assertThat(cases.rows.get(acted.id()).arrivedAt()).isEqualTo(dbNow);
    }

    @Test
    void aRepeatOfTheKeyReadsTheClockOnceAfterTheLockAndTheSettingsSaveStampsWithIt() {
        Instant dbNow = NOW.plusSeconds(11).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        cases.clock = dbNow;
        UUID key = UUID.randomUUID();
        service.register(TENANT, ACTOR, "NURSE", input(key, SECRET_NAME, 2));
        int reads = cases.clockReads;

        Queue repeated = service.register(TENANT, ACTOR, "NURSE", input(key, SECRET_NAME, 2)).queue();

        assertThat(cases.clockReads).isEqualTo(reads + 1);
        assertThat(repeated.asOf()).isEqualTo(dbNow);
        cases.seed(1, "WAITING", 5, null);
        service.updateSettings(TENANT, ACTOR, "OWNER",
                settingsInput(5, new int[] {5, 30, 120}, new int[] {0, 10, 30, 60, 120}, 2, true));
        verify(settings).upsertEmergencySetup(eq(TENANT), any(), eq(ACTOR), eq(dbNow));
        assertThat(cases.clockReadBeforeLock).isFalse();
    }

    @Test
    void theQueueSummaryAndCountsAreReadInOneSnapshotTransactionAtRepeatableRead() throws NoSuchMethodException {
        for (String name : new String[] {"queue", "summary"}) {
            Transactional tx = EmergencyService.class.getMethod(name, UUID.class, String.class)
                    .getAnnotation(Transactional.class);
            assertThat(tx.readOnly()).as(name).isTrue();
            assertThat(tx.isolation()).as(name).isEqualTo(Isolation.REPEATABLE_READ);
        }
        Transactional list = AdmissionService.class
                .getMethod("list", UUID.class, UUID.class, String.class, String.class, String.class).getAnnotation(Transactional.class);
        assertThat(list.readOnly()).isTrue();
        assertThat(list.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        // the hand-off read writes one audit row, so it is read-write; still one snapshot
        Transactional options = AdmissionService.class.getMethod("options", UUID.class, String.class, UUID.class)
                .getAnnotation(Transactional.class);
        assertThat(options.readOnly()).isFalse();
        assertThat(options.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    // ---- redaction ----

    @Test
    void personalTextNeverReachesToString() {
        CaseRow row = cases.seed(2, "WAITING", 1, null);
        CaseInput in = input(UUID.randomUUID(), SECRET_NAME, 2);

        assertThat(in.toString()).doesNotContain(SECRET_NAME).doesNotContain(SECRET_COMPLAINT).doesNotContain(SECRET_MOBILE);
        assertThat(row.toString()).doesNotContain(row.displayName()).doesNotContain(row.complaint())
                .doesNotContain(row.mobile());
        NewCase made = new NewCase(UUID.randomUUID(), SECRET_NAME, 52, "M", SECRET_MOBILE, SECRET_COMPLAINT, false, 2,
                null, ACTOR);
        assertThat(made.toString()).doesNotContain(SECRET_NAME).doesNotContain(SECRET_COMPLAINT)
                .doesNotContain(SECRET_MOBILE);
        Case view = service.queue(TENANT, "NURSE").cases().get(0);
        assertThat(view.toString()).doesNotContain(view.displayName()).doesNotContain(view.complaint());
    }

    // ---- helpers ----

    private static Case byId(Queue queue, UUID id) {
        return queue.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> captorOfMap() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private Map<String, Object> lastAudit(String action, UUID entityId) {
        ArgumentCaptor<Map<String, Object>> after = captorOfMap();
        verify(auditLog).record(eq(action), eq(EmergencyService.ENTITY_CASE), eq(entityId), any(), after.capture());
        return after.getValue();
    }

    /** In-memory {@code ipd.emergency_case} with the database's unique rules. */
    private static final class FakeCases implements EmergencyCaseRepository {

        final Map<UUID, CaseRow> rows = new LinkedHashMap<>();
        NewCase lastInsert;
        int inserts;
        int updates;
        int lockedReads;
        int handoffReads;
        int clockReads;
        /** The database clock the service sees; the application clock of the gate is NOW. */
        Instant clock = NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        /** Set by the clinic lock; a clock read while false was taken before the lock. */
        boolean locked;
        boolean clockReadBeforeLock;
        RuntimeException failNextInsertWith;

        /** A read without the mobile number, as every query except the hand-off returns it. */
        private static CaseRow noMobile(CaseRow r) {
            return new CaseRow(r.id(), r.patientId(), r.displayName(), r.age(), r.sex(), null, r.complaint(),
                    r.identityPending(), r.level(), r.bay(), r.status(), r.arrivedAt(), r.registeredBy(), r.seenAt(),
                    r.seenBy(), r.closedAt(), r.closedBy(), r.admissionId(), r.clientKey(), r.version());
        }

        CaseRow seed(int level, String status, long minutesAgo, String bay) {
            UUID id = UUID.randomUUID();
            boolean closed = !"WAITING".equals(status) && !"SEEN".equals(status);
            Instant seen = "WAITING".equals(status) ? null : NOW.minusSeconds(60);
            CaseRow row = new CaseRow(id, "ADMITTED".equals(status) ? UUID.randomUUID() : null, SECRET_NAME, 52, "M",
                    SECRET_MOBILE, SECRET_COMPLAINT, false, level, bay, status,
                    NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS).minusSeconds(minutesAgo * 60),
                    ACTOR, seen == null ? null : seen, seen == null ? null : ACTOR, closed ? NOW.minusSeconds(30) : null,
                    closed ? ACTOR : null, "ADMITTED".equals(status) ? UUID.randomUUID() : null, UUID.randomUUID(), 0);
            rows.put(id, row);
            return row;
        }

        @Override
        public Optional<CaseRow> findByClientKey(UUID tenantId, UUID clientKey) {
            return rows.values().stream().filter(r -> r.clientKey().equals(clientKey)).findFirst()
                    .map(FakeCases::noMobile);
        }

        @Override
        public Created insert(UUID tenantId, NewCase c) {
            if (failNextInsertWith != null) {
                RuntimeException e = failNextInsertWith;
                failNextInsertWith = null;
                throw e;
            }
            for (CaseRow r : rows.values()) {
                if (r.clientKey().equals(c.clientKey())) {
                    throw new DuplicateKeyException("uq_emergency_case_tenant_client_key");
                }
                if (c.bay() != null && r.open() && c.bay().equals(r.bay())) {
                    throw new DuplicateKeyException("ux_emergency_case_tenant_bay_open");
                }
            }
            lastInsert = c;
            inserts++;
            UUID id = UUID.randomUUID();
            Instant arrived = clock;
            rows.put(id, new CaseRow(id, null, c.displayName(), c.age(), c.sex(), c.mobile(), c.complaint(),
                    c.identityPending(), c.level(), c.bay(), "WAITING", arrived,
                    c.registeredBy(), null, null, null, null, null, c.clientKey(), 0));
            return new Created(id, arrived);
        }

        @Override
        public Instant databaseNow() {
            clockReads++;
            if (!locked) {
                clockReadBeforeLock = true;
            }
            return clock;
        }

        @Override
        public Optional<CaseRow> find(UUID tenantId, UUID caseId) {
            return Optional.ofNullable(rows.get(caseId)).map(FakeCases::noMobile);
        }

        @Override
        public Optional<CaseRow> findForHandoff(UUID tenantId, UUID caseId) {
            handoffReads++;
            return Optional.ofNullable(rows.get(caseId));
        }

        @Override
        public Optional<CaseRow> findForUpdate(UUID tenantId, UUID caseId) {
            lockedReads++;
            return Optional.ofNullable(rows.get(caseId)).map(FakeCases::noMobile);
        }

        @Override
        public List<CaseRow> findOpen(UUID tenantId) {
            return rows.values().stream().filter(CaseRow::open).map(FakeCases::noMobile).toList();
        }

        private boolean replace(UUID id, java.util.function.UnaryOperator<CaseRow> change, boolean onlyWaiting) {
            CaseRow row = rows.get(id);
            if (row == null || (onlyWaiting ? !"WAITING".equals(row.status()) : !row.open())) {
                return false;
            }
            rows.put(id, change.apply(row));
            updates++;
            return true;
        }

        @Override
        public boolean markSeen(UUID tenantId, UUID caseId, Instant at, UUID by) {
            return replace(caseId, r -> new CaseRow(r.id(), r.patientId(), r.displayName(), r.age(), r.sex(),
                    r.mobile(), r.complaint(), r.identityPending(), r.level(), r.bay(), "SEEN", r.arrivedAt(),
                    r.registeredBy(), at, by, null, null, null, r.clientKey(), r.version() + 1), true);
        }

        @Override
        public boolean updateLevel(UUID tenantId, UUID caseId, int level) {
            return replace(caseId, r -> new CaseRow(r.id(), r.patientId(), r.displayName(), r.age(), r.sex(),
                    r.mobile(), r.complaint(), r.identityPending(), level, r.bay(), r.status(), r.arrivedAt(),
                    r.registeredBy(), r.seenAt(), r.seenBy(), null, null, null, r.clientKey(), r.version() + 1), false);
        }

        @Override
        public boolean close(UUID tenantId, UUID caseId, String status, Instant at, UUID by) {
            return replace(caseId, r -> new CaseRow(r.id(), r.patientId(), r.displayName(), r.age(), r.sex(),
                    null, r.complaint(), r.identityPending(), r.level(), r.bay(), status, r.arrivedAt(),
                    r.registeredBy(), r.seenAt(), r.seenBy(), at, by, null, r.clientKey(), r.version() + 1), false);
        }

        @Override
        public boolean closeSentToOpd(UUID tenantId, UUID caseId, UUID patientId, UUID opdVisitId, Instant at, UUID by) {
            return replace(caseId, r -> new CaseRow(r.id(), patientId, r.displayName(), r.age(), r.sex(), null,
                    r.complaint(), r.identityPending(), r.level(), r.bay(), "SENT_TO_OPD", r.arrivedAt(),
                    r.registeredBy(), r.seenAt(), r.seenBy(), at, by, null, r.clientKey(), r.version() + 1), false);
        }

        @Override
        public boolean markAdmitted(UUID tenantId, UUID caseId, UUID admissionId, UUID patientId, Instant at, UUID by) {
            return replace(caseId, r -> new CaseRow(r.id(), patientId, r.displayName(), r.age(), r.sex(), null,
                    r.complaint(), r.identityPending(), r.level(), r.bay(), "ADMITTED", r.arrivedAt(),
                    r.registeredBy(), r.seenAt(), r.seenBy(), at, by, admissionId, r.clientKey(), r.version() + 1),
                    false);
        }
    }
}
