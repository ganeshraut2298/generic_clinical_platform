package com.sahaj.ipd.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MarViews.OverrideInput;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared set-up of the SAH-32 and SAH-76 service tests: the SAH-31 fixtures plus the dose repository, a team with a second
 * nurse, the PIN port as a mock, and the real {@link MedicineAdministration}, {@link DoseService}, {@link MarService} and
 * {@link MarAssembler} on top of in-memory fakes. No database. The clock is 8:10 AM IST on 11 Nov 2026.
 *
 * <p>The order timelines are derived from the seeded order rows: one verified scheduled version 1 that started long ago (a
 * built order). A test that needs a changed, held, STAT or PRN order puts its own timeline in {@link #timelineOverrides}.
 * The medicine code {@link #CODE} belongs to every medicine; the plain {@link #given} requests carry it.
 */
abstract class DoseTestBase extends MedicineTestBase {

    static final UUID SECOND_NURSE = UUID.randomUUID();
    static final UUID OWNER = UUID.randomUUID();
    static final UUID SECOND_NURSE_USER = SECOND_NURSE;
    static final String UHID = "UH-000123";
    static final String PIN = "4821";
    static final String CODE = "8901234567890";
    static final Instant EIGHT_AM = Instant.parse("2026-11-11T02:30:00Z");
    static final Instant TWO_PM = Instant.parse("2026-11-11T08:30:00Z");
    static final Instant EIGHT_PM = Instant.parse("2026-11-11T14:30:00Z");
    static final Instant CLOCK = Instant.parse("2026-11-11T02:40:00Z");
    static final Instant CHECKED_LONG_AGO = Instant.parse("2026-11-09T05:00:00Z");

    StaffPinPort staffPin;
    MarAssembler marAssembler;
    MedicineAdministration administration;
    DoseCorrectionService corrections;
    DoseService service;
    MarService marService;
    IpdAudit audit;
    DrugCodeRepository drugCodes;
    NursingSettingsReader settingsReader;
    OrderNotices notices;
    PharmacistReviews reviews;
    EffectiveNursingSettings settings = EffectiveNursingSettings.defaults();
    /** A timeline of a test's own, by order id; every other order gets the built version 1 derived from its row. */
    final Map<UUID, OrderTimeline> timelineOverrides = new HashMap<>();
    final List<String> trace = new ArrayList<>();
    UUID callerMembership;
    UUID secondMembership;

    @BeforeEach
    void doseSetUp() {
        staffPin = mock(StaffPinPort.class);
        drugCodes = mock(DrugCodeRepository.class);
        settingsReader = mock(NursingSettingsReader.class);
        notices = mock(OrderNotices.class);
        reviews = mock(PharmacistReviews.class);
        doses.orders = fake;
        when(drugCodes.codesOf(anyString())).thenReturn(Set.of(CODE));
        when(drugCodes.drugKeyOf(any())).thenAnswer(call -> CODE.equals(call.getArgument(0))
                ? Optional.of("pcm") : Optional.empty());
        when(drugCodes.drugKeysWithCodes(anyCollection())).thenAnswer(call -> Set.copyOf(call.<Collection<String>>getArgument(0)));
        when(settingsReader.effectiveFor(eq(TENANT), any())).thenAnswer(call -> settings);
        when(settingsReader.effectiveForAll(eq(TENANT), anyCollection())).thenAnswer(call -> {
            Map<UUID, EffectiveNursingSettings> all = new LinkedHashMap<>();
            for (UUID id : call.<Collection<UUID>>getArgument(1)) {
                all.put(id, settings);
            }
            return all;
        });
        audit = new IpdAudit(auditLog, new AuditDevice());
        OrderTimelineRepository timelines = this::timelinesOf;
        marAssembler = new MarAssembler(doses, assembler, actors, team, timelines, settingsReader, drugCodes);
        administration = new MedicineAdministration(fake, doses, admissions, events, patients, patientAccess, actors,
                team, audit, gate, assembler, marAssembler, guard, timelines, settingsReader, drugCodes, notices,
                reviews, catalogue);
        corrections = new DoseCorrectionService(fake, doses, admissions, events, patients, team, audit, gate, assembler,
                marAssembler, guard, reviews);
        service = new DoseService(administration, staffPin);
        marService = new MarService(admissions, fake, patients, patientAccess, team, gate, marAssembler, guard);

        memberRoles.put(OWNER, "OWNER");
        memberRoles.put(SECOND_NURSE, "NURSE");
        callerMembership = membership(NURSE);
        secondMembership = membership(SECOND_NURSE);
        roster.put(callerMembership, new TeamMember(callerMembership, "Sr. Anjali Pawar", "NURSE", true, 0L));
        roster.put(secondMembership, new TeamMember(secondMembership, "Sr. Rupali More", "NURSE", true, 0L));
        roster.put(membership(OWNER), new TeamMember(membership(OWNER), "Owner Kulkarni", "OWNER", true, 0L));

        Map<UUID, String> names = Map.of(DOCTOR, "Dr Meera Joshi", NURSE, "Sr. Anjali Pawar", SECOND_NURSE,
                "Sr. Rupali More", OWNER, "Owner Kulkarni");
        when(actors.displayNames(eq(TENANT), anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(1);
            Map<UUID, String> found = new HashMap<>();
            for (UUID id : ids) {
                if (names.containsKey(id)) {
                    found.put(id, names.get(id));
                }
            }
            return found;
        });
        when(actors.displayName(eq(TENANT), any())).thenAnswer(call -> Optional.ofNullable(names.get(call.<UUID>getArgument(1))));
        when(admissions.findForNoKeyUpdate(eq(TENANT), eq(ADMISSION))).thenAnswer(call -> {
            trace.add("lock");
            return Optional.of(admission);
        });
        when(staffPin.checkColleague(eq(TENANT), eq(NURSE), eq(secondMembership), eq("NURSE"), eq(PIN)))
                .thenAnswer(call -> {
                    trace.add("pin");
                    return ColleaguePinCheck.ok(SECOND_NURSE_USER);
                });
        fake.now = CLOCK;
        fake.trace = trace;
        doses.trace = trace;
    }

    /** The timelines of the orders of the admissions: the override of a test, else version 1 derived from the row. */
    private Map<UUID, OrderTimeline> timelinesOf(UUID tenantId, Collection<UUID> admissionIds) {
        Map<UUID, OrderTimeline> found = new HashMap<>();
        for (UUID admissionId : admissionIds) {
            for (OrderRow o : fake.findByAdmission(tenantId, admissionId)) {
                found.put(o.id(), timelineOverrides.containsKey(o.id()) ? timelineOverrides.get(o.id()) : derived(o));
            }
        }
        return found;
    }

    /** Version 1 of a built order: scheduled, started long ago, verified when the row says so. */
    OrderTimeline derived(OrderRow o) {
        return new OrderTimeline(o.id(), o.admissionId(), OrderStatus.valueOf(o.status()),
                List.of(version(o, 1, Kind.SCHEDULED, o.frequencyKey(), OrderPriority.ROUTINE)), List.of(),
                o.stoppedAt());
    }

    /** A version of the order with the row's verification, for a test that builds its own timeline. */
    MedicineOrderVersion version(OrderRow o, int no, Kind kind, String frequencyKey, OrderPriority priority) {
        String route = catalogue.catalogue().find(o.drugKey()).map(d -> d.routeKey().name()).orElse("ORAL");
        return new MedicineOrderVersion(o.id(), no, kind, frequencyKey, null, null, route, CHECKED_LONG_AGO, null,
                kind == Kind.PRN ? "Pain" : null, priority, kind == Kind.PRN ? 240 : null, kind == Kind.PRN ? 3 : null,
                no == 1 ? null : "Changed", DOCTOR, CHECKED_LONG_AGO, null, Verification.REQUIRED,
                o.verifiedAt() == null ? null : DOCTOR, o.verifiedAt());
    }

    /** An ACTIVE order checked long ago, of the drug and frequency. */
    OrderRow active(String drugKey, String frequencyKey) {
        return fake.seedAt(ADMISSION, drugKey, frequencyKey, MedicineOrderRepository.ACTIVE, DOCTOR, CHECKED_LONG_AGO);
    }

    OrderRow ofStatus(String status, Instant verifiedAt) {
        return fake.seedAt(ADMISSION, "pcm", "od", status, DOCTOR, verifiedAt);
    }

    /** A request as the SAH-32 tests built it, for order version 1 and the medicine code of the drug. */
    static DoseInput dose(String clientKey, String slotAt, String outcome, String wristband, Object confirmed,
                          String secondNurseId, String pin, String reasonKey) {
        return new DoseInput(clientKey, slotAt, 1, outcome, wristband, CODE, null, null, confirmed, secondNurseId, pin,
                reasonKey);
    }

    /** A plain Given request for the slot with the patient's own UHID, the medicine code and the box ticked. */
    DoseInput given(Instant slot) {
        return dose(UUID.randomUUID().toString(), slot.toString(), "GIVEN", UHID, Boolean.TRUE, null, null, null);
    }

    DoseInput givenWithKey(UUID key, Instant slot) {
        return dose(key.toString(), slot.toString(), "GIVEN", UHID, Boolean.TRUE, null, null, null);
    }

    /** A high-alert Given request: the second nurse and their PIN. */
    DoseInput givenHighAlert(Instant slot) {
        return dose(UUID.randomUUID().toString(), slot.toString(), "GIVEN", UHID, Boolean.TRUE,
                secondMembership.toString(), PIN, null);
    }

    DoseInput notGiven(Instant slot, String reasonKey) {
        return dose(UUID.randomUUID().toString(), slot.toString(), "NOT_GIVEN", null, null, null, null, reasonKey);
    }

    DoseInput with(DoseInput base, String wristband, Object confirmed, String secondNurseId, String pin,
                   String reasonKey) {
        return new DoseInput(base.clientKey(), base.slotAt(), base.orderVersion(), base.outcome(), wristband,
                base.medicineCode(), base.override(), base.note(), confirmed, secondNurseId, pin, reasonKey);
    }

    /** The same request with another medicine code (null: none). */
    DoseInput withCode(DoseInput base, String medicineCode) {
        return new DoseInput(base.clientKey(), base.slotAt(), base.orderVersion(), base.outcome(), base.wristband(),
                medicineCode, base.override(), base.note(), base.identityConfirmed(), base.secondNurseId(),
                base.secondNursePin(), base.reasonKey());
    }

    /** The same request for another order version. */
    DoseInput withVersion(DoseInput base, Object orderVersion) {
        return new DoseInput(base.clientKey(), base.slotAt(), orderVersion, base.outcome(), base.wristband(),
                base.medicineCode(), base.override(), base.note(), base.identityConfirmed(), base.secondNurseId(),
                base.secondNursePin(), base.reasonKey());
    }

    /** The same request with a manual override, and no wristband and no code unless the test sets them. */
    DoseInput withOverride(DoseInput base, String reasonKey, String note) {
        return new DoseInput(base.clientKey(), base.slotAt(), base.orderVersion(), base.outcome(), base.wristband(),
                base.medicineCode(), new OverrideInput(reasonKey, note), base.note(), base.identityConfirmed(),
                base.secondNurseId(), base.secondNursePin(), base.reasonKey());
    }

    /** The same request without a slot (a PRN dose). */
    DoseInput withoutSlot(DoseInput base) {
        return new DoseInput(base.clientKey(), null, base.orderVersion(), base.outcome(), base.wristband(),
                base.medicineCode(), base.override(), base.note(), base.identityConfirmed(), base.secondNurseId(),
                base.secondNursePin(), base.reasonKey());
    }

    /** The same request with a note (reason OTHER). */
    DoseInput withNote(DoseInput base, String note) {
        return new DoseInput(base.clientKey(), base.slotAt(), base.orderVersion(), base.outcome(), base.wristband(),
                base.medicineCode(), base.override(), note, base.identityConfirmed(), base.secondNurseId(),
                base.secondNursePin(), base.reasonKey());
    }

    /** A timeline of the order with one custom current version. */
    OrderTimeline timelineOf(OrderRow o, OrderStatus status, MedicineOrderVersion... versions) {
        return new OrderTimeline(o.id(), o.admissionId(), status, List.of(versions), List.of(), o.stoppedAt());
    }

    /** Frequency of the row as an enum, for readability in tests. */
    static Frequency freq(String key) {
        return Frequency.ofKey(key).orElseThrow();
    }
}
