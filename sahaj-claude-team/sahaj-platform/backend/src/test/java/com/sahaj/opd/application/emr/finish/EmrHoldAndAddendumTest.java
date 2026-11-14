package com.sahaj.opd.application.emr.finish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sahaj.opd.api.EmrEncounterSource.AdmissionFacts;
import com.sahaj.opd.api.EmrEncounterSource.Access;
import com.sahaj.opd.application.VisitRepository.VisitRow;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.EmrRecordService.Encounter;
import com.sahaj.opd.application.emr.EmrRecordService.SignContext;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.AddendumRow;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RecordState;
import com.sahaj.opd.application.emr.finish.FinishModels.AddendumInput;
import com.sahaj.opd.application.emr.finish.FinishModels.EditHold;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-51-4 and SAH-51-5 (ADR-025 D3, D4): one editor at a time with an owner's audited take-over, and append-only addenda to a
 * signed record. The addendum text is never in the audit entry.
 */
class EmrHoldAndAddendumTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-13T05:30:00Z");
    private static final ClinicUser CALLER = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrRecordService records = mock(EmrRecordService.class);
    private final EmrFinishRepository finish = mock(EmrFinishRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private EmrEditHoldService holds;
    private EmrAddendumService addenda;

    @BeforeEach
    void setUp() {
        holds = new EmrEditHoldService(records, finish, team, audit);
        addenda = new EmrAddendumService(records, finish, team, audit);
        when(team.find(TENANT, ME)).thenReturn(Optional.of(new TeamMember(ME, "Dr Me", "DOCTOR", true, 0)));
        when(team.find(TENANT, OTHER)).thenReturn(Optional.of(new TeamMember(OTHER, "Dr Other", "DOCTOR", true, 0)));
    }

    private void caller(String role) {
        TeamMember member = new TeamMember(ME, "Dr Me", role, true, 0);
        when(records.authorize(any(), eq(RECORD))).thenReturn(new Authorized(member, mock(RecordRow.class), null));
    }

    private static RecordState state(String status, UUID holder, boolean active) {
        return new RecordState(RECORD, status, null, null, null, null, null, holder, null, holder == null ? null
                : NOW.plusSeconds(90), active, null);
    }

    // ---- edit hold ----

    @Test
    void aFreeHoldIsTakenForTheCallerAndWritesNoAudit() {
        caller("DOCTOR");
        when(finish.stateForUpdate(TENANT, RECORD)).thenReturn(Optional.of(state("DRAFT", null, false)));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("DRAFT", ME, true)));
        when(finish.takeHold(TENANT, RECORD, ME, USER, 120, false)).thenReturn(true);

        EditHold hold = holds.take(CALLER, RECORD, false);

        assertThat(hold.mine()).isTrue();
        assertThat(hold.expiresAt()).isEqualTo(NOW.plusSeconds(90));
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anActiveHoldOfAnotherDoctorAnswers409WithTheirName() {
        caller("DOCTOR");
        when(finish.stateForUpdate(TENANT, RECORD)).thenReturn(Optional.of(state("DRAFT", OTHER, true)));
        when(finish.takeHold(TENANT, RECORD, ME, USER, 120, false)).thenReturn(false);

        assertThatThrownBy(() -> holds.take(CALLER, RECORD, false)).isInstanceOfSatisfying(EmrProblemException.class, e -> {
            assertThat(e.slug()).isEqualTo("emr-record-held");
            assertThat(e.extensions().get("holderName")).isEqualTo("Dr. Dr Other");
        });
    }

    @Test
    void onlyAnOwnerMayTakeOverAndTheTakeOverIsAuditedWithTheIdsOfThePreviousHolder() {
        caller("DOCTOR");
        assertThatThrownBy(() -> holds.take(CALLER, RECORD, true)).isInstanceOf(AccessDeniedException.class);

        caller("OWNER");
        when(finish.stateForUpdate(TENANT, RECORD)).thenReturn(Optional.of(state("DRAFT", OTHER, true)));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("DRAFT", ME, true)));
        when(finish.takeHold(TENANT, RECORD, ME, USER, 120, true)).thenReturn(true);

        holds.take(new ClinicUser(USER, TENANT, "OWNER"), RECORD, true);

        verify(audit).record("EMR_EDIT_HOLD_TAKEN_OVER", "EMR_RECORD", RECORD, null, Map.of("previousHolderMembershipId", OTHER));
    }

    @Test
    void theAdmittingDoctorOfAnIpdRoundMayTakeOverItsHoldAndTheTakeOverIsAudited() {
        TeamMember member = new TeamMember(ME, "Dr Me", "DOCTOR", true, 0);
        when(records.authorize(any(), eq(RECORD))).thenReturn(new Authorized(member, mock(RecordRow.class), null, true));
        when(finish.stateForUpdate(TENANT, RECORD)).thenReturn(Optional.of(state("DRAFT", OTHER, true)));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(state("DRAFT", ME, true)));
        when(finish.takeHold(TENANT, RECORD, ME, USER, 120, true)).thenReturn(true);

        holds.take(CALLER, RECORD, true);

        verify(audit).record("EMR_EDIT_HOLD_TAKEN_OVER", "EMR_RECORD", RECORD, null, Map.of("previousHolderMembershipId", OTHER));
    }

    @Test
    void theHolderIsReadUnderTheRecordLockAndAHoldTakeOnARecordSignedMeanwhileIsSignedNotHeld() {
        caller("DOCTOR");
        when(finish.stateForUpdate(TENANT, RECORD)).thenReturn(Optional.of(state("SIGNED", OTHER, true)));

        assertThatThrownBy(() -> holds.take(CALLER, RECORD, false)).extracting(e -> ((EmrProblemException) e).slug())
                .isEqualTo("emr-record-signed");
        verify(finish, never()).state(any(), any());
    }

    @Test
    void theMayTakeOverRulesAreTheOwnerAndTheAdmittingDoctorOfAnIpdRoundOnly() {
        TeamMember doctor = new TeamMember(ME, "Dr Me", "DOCTOR", true, 0);
        AdmissionFacts admission = new AdmissionFacts(UUID.randomUUID(), UUID.randomUUID(), "IP/1", "ADMITTED", 1, ME,
                Access.ALLOWED);
        Encounter round = new Encounter("IPD_ROUND", UUID.randomUUID(), null, admission, null, false, "on");
        Encounter opd = new Encounter("OPD_VISIT", UUID.randomUUID(), null, null, null, false, "on");

        assertThat(EmrRecordService.mayTakeOverHold(doctor, round)).isTrue();
        assertThat(EmrRecordService.mayTakeOverHold(doctor, opd)).isFalse();
        assertThat(EmrRecordService.mayTakeOverHold(new TeamMember(OTHER, "Dr Other", "DOCTOR", true, 0), round)).isFalse();
        assertThat(EmrRecordService.mayTakeOverHold(new TeamMember(OTHER, "Owner", "OWNER", true, 0), opd)).isTrue();
    }

    @Test
    void aSignedRecordCannotBeHeldAndReleaseOnlyReleasesTheCallersOwn() {
        caller("DOCTOR");
        when(finish.stateForUpdate(TENANT, RECORD)).thenReturn(Optional.of(state("SIGNED", null, false)));
        assertThatThrownBy(() -> holds.take(CALLER, RECORD, false)).extracting(e -> ((EmrProblemException) e).slug())
                .isEqualTo("emr-record-signed");
        verify(finish, never()).takeHold(any(), any(), any(), any(), anyInt(), anyBoolean());

        holds.release(CALLER, RECORD);
        verify(finish).releaseHold(TENANT, RECORD, ME);
    }

    // ---- addenda ----

    private void signedRecord(String role, UUID visitDoctor) {
        TeamMember member = new TeamMember(ME, "Dr Me", role, true, 0);
        RecordRow row = mock(RecordRow.class);
        when(row.status()).thenReturn("SIGNED");
        VisitRow visit = new VisitRow(UUID.randomUUID(), UUID.randomUUID(), visitDoctor, "NEW", "DONE", 1, 0, NOW, 0);
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(member, row,
                new Encounter("OPD_VISIT", UUID.randomUUID(), visit, null, null, false, "on"), null,
                EmrRecordDocument.empty(JsonMapper.builder().build())));
    }

    private void signedIpdRecord(String type, String role, UUID signer, UUID admittingDoctor) {
        TeamMember member = new TeamMember(ME, "Dr Me", role, true, 0);
        RecordRow row = mock(RecordRow.class);
        when(row.status()).thenReturn("SIGNED");
        AdmissionFacts admission = "IPD_ROUND".equals(type) ? new AdmissionFacts(UUID.randomUUID(), UUID.randomUUID(),
                "IP/1", "ADMITTED", 1, admittingDoctor, Access.ALLOWED) : null;
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(member, row,
                new Encounter(type, UUID.randomUUID(), null, admission, null, false, "on"), null,
                EmrRecordDocument.empty(JsonMapper.builder().build())));
        when(finish.state(TENANT, RECORD)).thenReturn(Optional.of(new RecordState(RECORD, "SIGNED", signer, null, "k", "h",
                null, null, null, null, false, null)));
        when(finish.addendumByKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        when(finish.insertAddendum(eq(TENANT), eq(RECORD), anyString(), anyString(), eq(ME), eq(USER), anyString(),
                anyString())).thenReturn(stored("key-0001-abcd", "h"));
    }

    @Test
    void anAddendumToAnIpdRoundOrEmergencyRecordNeedsTheSignerTheAdmittingDoctorOrAnOwner() {
        AddendumInput body = new AddendumInput("Wrong dose", "Dose is 5 mg");
        // any other doctor with IPD scope: 403 (DEC-048 H29)
        signedIpdRecord("IPD_ROUND", "DOCTOR", OTHER, OTHER);
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", body))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.slug()).isEqualTo("not-own-patient");
                });
        signedIpdRecord("ER_ASSESSMENT", "DOCTOR", OTHER, null);
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", body))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("not-own-patient");
        verify(finish, never()).insertAddendum(any(), any(), anyString(), anyString(), any(), any(), anyString(),
                anyString());

        // the signer, the admitting doctor (IPD round) and an owner may
        signedIpdRecord("IPD_ROUND", "DOCTOR", ME, OTHER);
        assertThat(addenda.add(CALLER, RECORD, "key-0001-abcd", body).created()).isTrue();
        signedIpdRecord("IPD_ROUND", "DOCTOR", OTHER, ME);
        assertThat(addenda.add(CALLER, RECORD, "key-0001-abcd", body).created()).isTrue();
        signedIpdRecord("ER_ASSESSMENT", "DOCTOR", ME, null);
        assertThat(addenda.add(CALLER, RECORD, "key-0001-abcd", body).created()).isTrue();
        signedIpdRecord("ER_ASSESSMENT", "OWNER", OTHER, null);
        assertThat(addenda.add(new ClinicUser(USER, TENANT, "OWNER"), RECORD, "key-0001-abcd", body).created()).isTrue();
    }

    @Test
    void theAdmittingDoctorIsNotTheAdmittingDoctorOfAnEmergencyRecord() {
        TeamMember doctor = new TeamMember(ME, "Dr Me", "DOCTOR", true, 0);
        Encounter er = new Encounter("ER_ASSESSMENT", UUID.randomUUID(), null, null, null, false, "on");
        assertThat(EmrRecordService.mayAddAddendum(doctor, er, OTHER)).isFalse();
        assertThat(EmrRecordService.mayAddAddendum(doctor, er, ME)).isTrue();
    }

    private AddendumRow stored(String key, String hash) {
        return new AddendumRow(UUID.randomUUID(), RECORD, "Wrong dose", "Dose is 5 mg", NOW, ME, key, hash);
    }

    @Test
    void anAddendumIsStoredTrimmedStampedByTheDoctorAndAuditedWithIdsOnly() {
        signedRecord("DOCTOR", ME);
        when(finish.addendumByKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        when(finish.countAddenda(TENANT, RECORD)).thenReturn(3);
        AddendumRow row = stored("key-0001-abcd", "h");
        when(finish.insertAddendum(eq(TENANT), eq(RECORD), eq("Wrong dose"), eq("Dose is 5 mg"), eq(ME), eq(USER),
                eq("key-0001-abcd"), anyString())).thenReturn(row);

        EmrAddendumService.Result result = addenda.add(CALLER, RECORD, "key-0001-abcd",
                new AddendumInput("  Wrong dose ", " Dose is 5 mg\n"));

        assertThat(result.created()).isTrue();
        assertThat(result.addendum().createdByName()).isEqualTo("Dr Me");
        verify(audit).record("EMR_ADDENDUM_ADDED", "EMR_RECORD", RECORD, null, Map.of("addendumId", row.id()));
    }

    @Test
    void theSameKeyReturnsTheFirstResultAndAnotherBodyUnderItIsAConflict() {
        signedRecord("DOCTOR", ME);
        AddendumInput body = new AddendumInput("Wrong dose", "Dose is 5 mg");
        String hash = java.util.HexFormat.of().formatHex(sha("Wrong dose\nDose is 5 mg"));
        AddendumRow first = stored("key-0001-abcd", hash);
        when(finish.addendumByKey(TENANT, "key-0001-abcd")).thenReturn(Optional.of(first));

        EmrAddendumService.Result again = addenda.add(CALLER, RECORD, "key-0001-abcd", body);
        assertThat(again.created()).isFalse();
        assertThat(again.addendum().id()).isEqualTo(first.id());
        verify(finish, never()).insertAddendum(any(), any(), anyString(), anyString(), any(), any(), anyString(), anyString());

        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", new AddendumInput("Other", "Text")))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("idempotency-conflict");
    }

    @Test
    void emptyOrTooLongTextsAndControlCharactersAreValidationErrorsAndTheLimitIsFifty() {
        signedRecord("DOCTOR", ME);
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", new AddendumInput(" ", "text")))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", new AddendumInput("r", "")))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", new AddendumInput("r", "a".repeat(4001))))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", new AddendumInput("r\nline", "t")))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", new AddendumInput("r", "bell\u0007")))
                .isInstanceOf(InvalidFieldsException.class);

        when(finish.addendumByKey(eq(TENANT), anyString())).thenReturn(Optional.empty());
        when(finish.countAddenda(TENANT, RECORD)).thenReturn(50);
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", new AddendumInput("r", "t")))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("addendum-limit");
    }

    @Test
    void aDraftHasNoAddendaAndOnlyTheRecordsDoctorOrAnOwnerAddsOne() {
        TeamMember member = new TeamMember(ME, "Dr Me", "DOCTOR", true, 0);
        RecordRow draft = mock(RecordRow.class);
        when(draft.status()).thenReturn("DRAFT");
        when(records.signContext(any(ClinicUser.class), eq(RECORD), eq(true))).thenReturn(new SignContext(member, draft,
                new Encounter("OPD_VISIT", UUID.randomUUID(), null, null, null, false, "on"), null, null));
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", new AddendumInput("r", "t")))
                .extracting(e -> ((EmrProblemException) e).slug()).isEqualTo("emr-record-not-signed");

        signedRecord("DOCTOR", OTHER);
        assertThatThrownBy(() -> addenda.add(CALLER, RECORD, "key-0001-abcd", new AddendumInput("r", "t")))
                .isInstanceOfSatisfying(EmrProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.slug()).isEqualTo("not-own-patient");
                });
        assertThat(new AddendumInput("secret reason", "secret text").toString()).doesNotContain("secret");
    }

    private static byte[] sha(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
