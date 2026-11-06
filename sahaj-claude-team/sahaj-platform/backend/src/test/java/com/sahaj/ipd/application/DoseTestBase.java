package com.sahaj.ipd.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.StaffPinPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared set-up of the SAH-32 service tests: the SAH-31 fixtures plus the dose repository, a team with a second nurse,
 * the PIN port as a mock, and the real {@link MedicineAdministration}, {@link DoseService}, {@link MarService} and
 * {@link MarAssembler} on top of in-memory fakes. No database. The clock is 8:10 AM IST on 11 Nov 2026.
 */
abstract class DoseTestBase extends MedicineTestBase {

    static final UUID SECOND_NURSE = UUID.randomUUID();
    static final UUID OWNER = UUID.randomUUID();
    static final UUID SECOND_NURSE_USER = SECOND_NURSE;
    static final String UHID = "UH-000123";
    static final String PIN = "4821";
    static final Instant EIGHT_AM = Instant.parse("2026-11-11T02:30:00Z");
    static final Instant TWO_PM = Instant.parse("2026-11-11T08:30:00Z");
    static final Instant EIGHT_PM = Instant.parse("2026-11-11T14:30:00Z");
    static final Instant CLOCK = Instant.parse("2026-11-11T02:40:00Z");
    static final Instant CHECKED_LONG_AGO = Instant.parse("2026-11-09T05:00:00Z");

    StaffPinPort staffPin;
    MarAssembler marAssembler;
    MedicineAdministration administration;
    DoseService service;
    MarService marService;
    final List<String> trace = new ArrayList<>();
    UUID callerMembership;
    UUID secondMembership;

    @BeforeEach
    void doseSetUp() {
        staffPin = mock(StaffPinPort.class);
        marAssembler = new MarAssembler(fake, doses, assembler, actors, team);
        administration = new MedicineAdministration(fake, doses, admissions, events, patients, patientAccess, actors,
                team, auditLog, gate, assembler, marAssembler);
        service = new DoseService(administration, staffPin);
        marService = new MarService(admissions, fake, patients, patientAccess, team, gate, marAssembler);

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
            java.util.Collection<UUID> ids = call.getArgument(1);
            Map<UUID, String> found = new java.util.HashMap<>();
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

    /** An ACTIVE order checked long ago, of the drug and frequency. */
    OrderRow active(String drugKey, String frequencyKey) {
        return fake.seedAt(ADMISSION, drugKey, frequencyKey, MedicineOrderRepository.ACTIVE, DOCTOR, CHECKED_LONG_AGO);
    }

    OrderRow ofStatus(String status, Instant verifiedAt) {
        return fake.seedAt(ADMISSION, "pcm", "od", status, DOCTOR, verifiedAt);
    }

    /** A plain Given request for the slot with the patient's own UHID and the box ticked. */
    DoseInput given(Instant slot) {
        return new DoseInput(UUID.randomUUID().toString(), slot.toString(), "GIVEN", UHID, Boolean.TRUE, null, null,
                null);
    }

    DoseInput givenWithKey(UUID key, Instant slot) {
        return new DoseInput(key.toString(), slot.toString(), "GIVEN", UHID, Boolean.TRUE, null, null, null);
    }

    /** A high-alert Given request: the second nurse and their PIN. */
    DoseInput givenHighAlert(Instant slot) {
        return new DoseInput(UUID.randomUUID().toString(), slot.toString(), "GIVEN", UHID, Boolean.TRUE,
                secondMembership.toString(), PIN, null);
    }

    DoseInput notGiven(Instant slot, String reasonKey) {
        return new DoseInput(UUID.randomUUID().toString(), slot.toString(), "NOT_GIVEN", null, null, null, null,
                reasonKey);
    }

    DoseInput with(DoseInput base, String wristband, Object confirmed, String secondNurseId, String pin,
                   String reasonKey) {
        return new DoseInput(base.clientKey(), base.slotAt(), base.outcome(), wristband, confirmed, secondNurseId, pin,
                reasonKey);
    }
}
