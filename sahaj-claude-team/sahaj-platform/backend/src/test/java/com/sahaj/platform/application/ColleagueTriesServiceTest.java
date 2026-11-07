package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.application.StaffPinColleagueEventRepository.History;
import com.sahaj.platform.application.StaffPinColleagueEventRepository.Line;
import com.sahaj.platform.application.StaffPinColleagueEventRepository.Unseen;
import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.PinState;
import com.sahaj.platform.domain.StaffPinRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-38, ADR-014 D8 and D9: the notice, "Got it", and the owner's history. */
class ColleagueTriesServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final UUID OWNER_MEMBER = UUID.randomUUID();
    private static final UUID NURSE_A = UUID.randomUUID();
    private static final UUID NURSE_B = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-07T06:00:00.123456Z");
    private static final Instant TRY_AT = Instant.parse("2026-11-07T05:35:12.123456Z");

    private TeamRepository teamRepository;
    private StaffPinRepository pins;
    private StaffPinColleagueEventRepository events;
    private ColleagueTriesService service;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        pins = mock(StaffPinRepository.class);
        events = mock(StaffPinColleagueEventRepository.class);
        service = new ColleagueTriesService(teamRepository, pins, events, Clock.fixed(NOW, ZoneOffset.UTC));
        when(teamRepository.findActiveByUser(TENANT, USER)).thenReturn(Optional.of(
                row(ME, USER, "Rahul", MembershipRole.NURSE, MembershipStatus.ACTIVE)));
        when(teamRepository.findByUser(TENANT, OWNER_USER)).thenReturn(Optional.of(
                row(OWNER_MEMBER, OWNER_USER, "Asha", MembershipRole.OWNER, MembershipStatus.ACTIVE)));
        when(events.latestSeenThrough(TENANT, ME)).thenReturn(Optional.empty());
        when(events.unseen(any(), any(), any())).thenReturn(new Unseen(0, null));
    }

    private static TeamRow row(UUID id, UUID userId, String name, MembershipRole role, MembershipStatus status) {
        return new TeamRow(id, userId, name, "9822077410", role, status, null, false, null, null, null, 0L);
    }

    private static StaffPinRow pin(String hash, Instant lockedUntil, String reason) {
        return new StaffPinRow(ME, hash, 1, 0, lockedUntil, 0, null, hash == null ? null : NOW.minusSeconds(3600),
                reason, null, 3L, NOW);
    }

    // ---- notice ----

    @Test
    void theNoticeCountsEveryKeptTryBeforeTheFirstGotItAndSendsBackTheNewestTime() {
        when(events.unseen(TENANT, ME, Instant.EPOCH)).thenReturn(new Unseen(3, TRY_AT));
        when(pins.find(TENANT, ME, false)).thenReturn(Optional.of(pin("$2a$12$" + "a".repeat(53), null, null)));

        ColleagueTriesNoticeView notice = service.notice(TENANT, USER);

        assertThat(notice.unseenCount()).isEqualTo(3);
        assertThat(notice.latestAt()).isEqualTo(TRY_AT);
        assertThat(notice.state()).isEqualTo(PinState.SET);
        assertThat(notice.lockedUntil()).isNull();
        assertThat(notice.notSetReason()).isNull();
    }

    @Test
    void theNoticeCountsOnlyAfterTheLastGotIt() {
        Instant marker = Instant.parse("2026-11-06T10:00:00Z");
        when(events.latestSeenThrough(TENANT, ME)).thenReturn(Optional.of(marker));
        when(events.unseen(TENANT, ME, marker)).thenReturn(new Unseen(1, TRY_AT));
        when(pins.find(TENANT, ME, false)).thenReturn(Optional.of(pin("$2a$12$" + "a".repeat(53), null, null)));

        assertThat(service.notice(TENANT, USER).unseenCount()).isEqualTo(1);
    }

    @Test
    void withNothingUnseenThereIsNoLatestTimeAndNoBanner() {
        when(events.unseen(TENANT, ME, Instant.EPOCH)).thenReturn(new Unseen(0, TRY_AT));
        when(pins.find(TENANT, ME, false)).thenReturn(Optional.of(pin("$2a$12$" + "a".repeat(53), null, null)));

        ColleagueTriesNoticeView notice = service.notice(TENANT, USER);

        assertThat(notice.unseenCount()).isZero();
        assertThat(notice.latestAt()).isNull();
    }

    @Test
    void aLockedPinShowsTheEndOfTheLock() {
        when(events.unseen(TENANT, ME, Instant.EPOCH)).thenReturn(new Unseen(5, TRY_AT));
        when(pins.find(TENANT, ME, false)).thenReturn(Optional.of(
                pin("$2a$12$" + "a".repeat(53), NOW.plusSeconds(840), null)));

        ColleagueTriesNoticeView notice = service.notice(TENANT, USER);

        assertThat(notice.state()).isEqualTo(PinState.LOCKED);
        assertThat(notice.lockedUntil()).isEqualTo(NOW.plusSeconds(840));
    }

    @Test
    void aLockThatEndedIsJustSetAndShowsNoEndTime() {
        when(pins.find(TENANT, ME, false)).thenReturn(Optional.of(
                pin("$2a$12$" + "a".repeat(53), NOW.minusSeconds(5), null)));

        ColleagueTriesNoticeView notice = service.notice(TENANT, USER);

        assertThat(notice.state()).isEqualTo(PinState.SET);
        assertThat(notice.lockedUntil()).isNull();
    }

    @Test
    void aSwitchedOffPinShowsTheReasonAndNoRowIsNeverSetNoReason() {
        when(pins.find(TENANT, ME, false)).thenReturn(Optional.of(pin(null, null, "TOO_MANY_LOCKS")));
        ColleagueTriesNoticeView off = service.notice(TENANT, USER);
        assertThat(off.state()).isEqualTo(PinState.NOT_SET);
        assertThat(off.notSetReason()).isEqualTo("TOO_MANY_LOCKS");

        when(pins.find(TENANT, ME, false)).thenReturn(Optional.empty());
        ColleagueTriesNoticeView none = service.notice(TENANT, USER);
        assertThat(none.state()).isEqualTo(PinState.NOT_SET);
        assertThat(none.notSetReason()).isNull();
        assertThat(none.unseenCount()).isZero();
    }

    @Test
    void theNoticeIsOwnDataOnlyAndNeedsAnActiveMembership() {
        when(teamRepository.findActiveByUser(TENANT, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.notice(TENANT, USER)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(events, pins);
    }

    @Test
    void theNoticeWritesNothing() {
        service.notice(TENANT, USER);

        verify(events, never()).insertNoticeSeen(any(), any(), any());
        verify(events, never()).insertWrong(any(), any(), any());
    }

    // ---- Got it ----

    @Test
    void gotItRecordsTheMarkerWhenAnUnseenTryLiesUpToTheTimeShown() {
        when(events.existsWrongBetween(TENANT, ME, Instant.EPOCH, TRY_AT)).thenReturn(true);

        service.markSeen(TENANT, USER, "2026-11-07T05:35:12.123456Z");

        verify(events).insertNoticeSeen(ME, TRY_AT, USER);
    }

    @Test
    void gotItAcceptsAnyUtcOffsetAndKeepsTheMicroseconds() {
        when(events.existsWrongBetween(any(), any(), any(), any())).thenReturn(true);

        service.markSeen(TENANT, USER, "2026-11-07T11:05:12.123456+05:30");

        verify(events).insertNoticeSeen(ME, Instant.parse("2026-11-07T05:35:12.123456Z"), USER);
    }

    @Test
    void gotItWritesNothingWhenNoUnseenTryLiesInTheRangeSoADoubleTapIsSafe() {
        Instant marker = TRY_AT;
        when(events.latestSeenThrough(TENANT, ME)).thenReturn(Optional.of(marker));

        service.markSeen(TENANT, USER, "2026-11-07T05:35:12.123456Z");
        service.markSeen(TENANT, USER, "2026-11-07T05:00:00Z");

        verify(events, never()).insertNoticeSeen(any(), any(), any());
        verify(events, never()).existsWrongBetween(any(), any(), any(), any());
    }

    @Test
    void gotItWritesNothingWhenNoWrongTryLiesBetweenTheMarkerAndTheTimeShown() {
        when(events.existsWrongBetween(any(), any(), any(), any())).thenReturn(false);

        service.markSeen(TENANT, USER, "2026-11-07T05:35:12.123456Z");

        verify(events, never()).insertNoticeSeen(any(), any(), any());
    }

    private void assertRejectedAsSeenThroughWithNoDatabaseCall(String bad) {
        assertThatThrownBy(() -> service.markSeen(TENANT, USER, bad)).as("[%s]", bad)
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> {
                    assertThat(ex.errors()).hasSize(1);
                    assertThat(ex.errors().get(0).field()).isEqualTo("seenThrough");
                    assertThat(ex.errors().get(0).message()).isEqualTo("Reload the page and try again.");
                });
        verifyNoInteractions(events, teamRepository, pins);
    }

    @Test
    void aTimeOneHourAheadIsAcceptedAndPassedOnBecauseTheDatabaseCapsItAtItsOwnClock() {
        when(events.existsWrongBetween(any(), any(), any(), any())).thenReturn(true);
        Instant oneHourAhead = NOW.plusSeconds(3600);

        service.markSeen(TENANT, USER, oneHourAhead.toString());

        verify(events).insertNoticeSeen(eq(ME), eq(oneHourAhead), eq(USER));
    }

    @Test
    void aTimeExactlyOneDayAheadIsStillAccepted() {
        when(events.existsWrongBetween(any(), any(), any(), any())).thenReturn(true);

        service.markSeen(TENANT, USER, NOW.plus(Duration.ofDays(1)).toString());

        verify(events).insertNoticeSeen(eq(ME), eq(NOW.plus(Duration.ofDays(1))), eq(USER));
    }

    @Test
    void aTimeMoreThanADayAheadIs400() {
        assertRejectedAsSeenThroughWithNoDatabaseCall(NOW.plus(Duration.ofDays(2)).toString());
        assertRejectedAsSeenThroughWithNoDatabaseCall(NOW.plus(Duration.ofDays(1)).plusSeconds(1).toString());
    }

    @Test
    void aTimeInTheYear200000Is400WithoutReachingTheDatabase() {
        assertRejectedAsSeenThroughWithNoDatabaseCall("+200000-01-01T00:00:00Z");
        assertRejectedAsSeenThroughWithNoDatabaseCall("200000-01-01T00:00:00Z");
        assertRejectedAsSeenThroughWithNoDatabaseCall("+999999999-12-31T23:59:59Z");
    }

    @Test
    void aTextLongerThanFortyCharactersIs400EvenWhenItIsAValidTime() {
        String valid = "2026-11-07T05:35:12.123456Z"; // 27 characters
        String fortyOne = " ".repeat(14) + valid;

        assertThat(fortyOne).hasSize(41);
        assertRejectedAsSeenThroughWithNoDatabaseCall(fortyOne);
        assertRejectedAsSeenThroughWithNoDatabaseCall(" ".repeat(200) + valid);
        assertRejectedAsSeenThroughWithNoDatabaseCall("1".repeat(41));
    }

    @Test
    void aTextOfExactlyFortyCharactersIsStillRead() {
        when(events.existsWrongBetween(any(), any(), any(), any())).thenReturn(true);
        String forty = " ".repeat(13) + "2026-11-07T05:35:12.123456Z";

        assertThat(forty).hasSize(40);
        service.markSeen(TENANT, USER, forty);

        verify(events).insertNoticeSeen(eq(ME), eq(TRY_AT), eq(USER));
    }

    @Test
    void aTimeBefore2020Is400() {
        assertRejectedAsSeenThroughWithNoDatabaseCall("2019-12-31T23:59:59Z");
        assertRejectedAsSeenThroughWithNoDatabaseCall("2019-01-01T00:00:00Z");
        assertRejectedAsSeenThroughWithNoDatabaseCall("1970-01-01T00:00:00Z");
        assertRejectedAsSeenThroughWithNoDatabaseCall("-000001-01-01T00:00:00Z");
    }

    @Test
    void theFirstInstantOf2020IsAccepted() {
        when(events.existsWrongBetween(any(), any(), any(), any())).thenReturn(true);

        service.markSeen(TENANT, USER, "2020-01-01T00:00:00Z");

        verify(events).insertNoticeSeen(eq(ME), eq(Instant.parse("2020-01-01T00:00:00Z")), eq(USER));
    }

    @Test
    void aMissingOrUnreadableTimeIs400OnSeenThroughWithTheReloadText() {
        for (String bad : new String[] {null, "", "  ", "yesterday", "2026-11-07", "2026-13-40T00:00:00Z", "12345"}) {
            assertRejectedAsSeenThroughWithNoDatabaseCall(bad);
        }
    }

    @Test
    void gotItNeedsAnActiveMembership() {
        when(teamRepository.findActiveByUser(TENANT, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markSeen(TENANT, USER, "2026-11-07T05:35:12Z"))
                .isInstanceOf(ForbiddenException.class);
        verify(events, never()).insertNoticeSeen(any(), any(), any());
    }

    // ---- owner history ----

    private void historyIs(List<Line> lines, int wrong) {
        when(teamRepository.findById(TENANT, ME)).thenReturn(Optional.of(
                row(ME, USER, "Rahul", MembershipRole.NURSE, MembershipStatus.ACTIVE)));
        when(events.history(TENANT, ME, 101)).thenReturn(new History(NOW, lines, wrong));
        when(teamRepository.findAll(TENANT)).thenReturn(List.of(
                row(OWNER_MEMBER, OWNER_USER, "Asha", MembershipRole.OWNER, MembershipStatus.ACTIVE),
                row(NURSE_A, UUID.randomUUID(), "Sunita Pawar", MembershipRole.NURSE, MembershipStatus.ACTIVE),
                row(NURSE_B, UUID.randomUUID(), "Meena Kale", MembershipRole.NURSE, MembershipStatus.REMOVED)));
    }

    @Test
    void theHistoryShowsEachKindWithNamesLookedUpNowAndKeepsARemovedColleaguesName() {
        historyIs(List.of(
                new Line("RIGHT_AFTER_WRONG", TRY_AT.plusSeconds(300), NURSE_A, 1),
                new Line("LOCKED", TRY_AT, null, 1),
                new Line("WRONG", TRY_AT, NURSE_A, 5),
                new Line("WRONG", TRY_AT.minusSeconds(86400), NURSE_B, 1)), 6);

        ColleagueTriesHistoryView view = service.history(TENANT, OWNER_USER, ME);

        assertThat(view.membershipId()).isEqualTo(ME);
        assertThat(view.asOf()).isEqualTo(NOW);
        assertThat(view.wrongTries7d()).isEqualTo(6);
        assertThat(view.truncated()).isFalse();
        assertThat(view.entries()).extracting(ColleagueTriesHistoryView.Entry::kind, ColleagueTriesHistoryView.Entry::byName,
                ColleagueTriesHistoryView.Entry::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("RIGHT_AFTER_WRONG", "Sunita Pawar", null),
                        org.assertj.core.groups.Tuple.tuple("LOCKED", null, null),
                        org.assertj.core.groups.Tuple.tuple("WRONG_TRIES", "Sunita Pawar", 5),
                        org.assertj.core.groups.Tuple.tuple("WRONG_TRIES", "Meena Kale", 1));
        assertThat(view.entries().get(1).byMembershipId()).isNull();
        assertThat(view.entries().get(2).byMembershipId()).isEqualTo(NURSE_A);
    }

    @Test
    void formerStaffMemberIsUsedOnlyWhenNoNameCanBeFound() {
        UUID unknown = UUID.randomUUID();
        historyIs(List.of(new Line("WRONG", TRY_AT, unknown, 2), new Line("WRONG", TRY_AT, NURSE_A, 1)), 3);
        when(teamRepository.findAll(TENANT)).thenReturn(List.of(
                row(NURSE_A, UUID.randomUUID(), " ", MembershipRole.NURSE, MembershipStatus.ACTIVE)));

        ColleagueTriesHistoryView view = service.history(TENANT, OWNER_USER, ME);

        assertThat(view.entries()).extracting(ColleagueTriesHistoryView.Entry::byName)
                .containsExactly("Former staff member", "Former staff member");
    }

    @Test
    void moreThanAHundredLinesAreCutAtAHundredAndMarkedTruncated() {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            lines.add(new Line("LOCKED", TRY_AT.minusSeconds(i), null, 1));
        }
        historyIs(lines, 0);

        ColleagueTriesHistoryView view = service.history(TENANT, OWNER_USER, ME);

        assertThat(view.truncated()).isTrue();
        assertThat(view.entries()).hasSize(100);
        assertThat(view.entries().get(0).at()).isEqualTo(TRY_AT);
    }

    @Test
    void exactlyAHundredLinesAreNotTruncated() {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            lines.add(new Line("LOCKED", TRY_AT.minusSeconds(i), null, 1));
        }
        historyIs(lines, 0);

        assertThat(service.history(TENANT, OWNER_USER, ME).truncated()).isFalse();
    }

    @Test
    void anEmptyHistoryIsAnEmptyListWithTheCountZero() {
        historyIs(List.of(), 0);

        ColleagueTriesHistoryView view = service.history(TENANT, OWNER_USER, ME);

        assertThat(view.entries()).isEmpty();
        assertThat(view.wrongTries7d()).isZero();
        verify(teamRepository, never()).findAll(any());
    }

    @Test
    void onlyAnActiveOwnerMaySeeTheHistoryAndTheRoleIsReadAgain() {
        for (MembershipRole role : MembershipRole.values()) {
            if (role == MembershipRole.OWNER) {
                continue;
            }
            when(teamRepository.findByUser(TENANT, OWNER_USER)).thenReturn(Optional.of(
                    row(OWNER_MEMBER, OWNER_USER, "Asha", role, MembershipStatus.ACTIVE)));

            assertThatThrownBy(() -> service.history(TENANT, OWNER_USER, ME)).as(String.valueOf(role))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessage()).isEqualTo("Only the clinic owner can do this."));
        }
        when(teamRepository.findByUser(TENANT, OWNER_USER)).thenReturn(Optional.of(
                row(OWNER_MEMBER, OWNER_USER, "Asha", MembershipRole.OWNER, MembershipStatus.INACTIVE)));
        assertThatThrownBy(() -> service.history(TENANT, OWNER_USER, ME)).isInstanceOf(ForbiddenException.class);
        when(teamRepository.findByUser(TENANT, OWNER_USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.history(TENANT, OWNER_USER, ME)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(events);
    }

    @Test
    void anUnknownOrOtherClinicsMemberIs404() {
        when(teamRepository.findById(TENANT, ME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.history(TENANT, OWNER_USER, ME)).isInstanceOf(NotFoundException.class);
        verify(events, never()).history(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void theHistoryWritesNothingAndNoEntryPrintsAName() {
        historyIs(List.of(new Line("WRONG", TRY_AT, NURSE_A, 5)), 5);

        ColleagueTriesHistoryView view = service.history(TENANT, OWNER_USER, ME);

        verify(events, never()).insertNoticeSeen(any(), any(), any());
        assertThat(view.entries().get(0).toString()).doesNotContain("Sunita");
    }
}
