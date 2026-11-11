package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.SafetyViews.Counts;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.MedicineRound;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-33 review fix R1: the change token and the one counts function behind the board, the summary and the banner. */
class SafetyRowsTest {

    private static final UUID ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z");

    private static Detail detail(UUID id, long version, Status status, int score, Instant raisedAt, int calls) {
        return new Detail(id, UUID.randomUUID(), version, score, Risk.HIGH, 15, status, raisedAt, UUID.randomUUID(),
                null, null, null, null, null, calls, null, null);
    }

    /** The token of escalations alone: no dose line (the SAH-33 token). */
    private static String token(Collection<Detail> notClosed) {
        return SafetyRows.changeToken(notClosed, List.of());
    }

    /** The counts of escalations alone: an empty Medicine round. */
    private static Counts counts(Collection<Detail> escalations, Instant asOf) {
        return SafetyRows.counts(escalations, MedicineRound.empty(), asOf);
    }

    // ---- the token: fixed vectors computed outside Java (SHA-256, first 16 bytes, base64url, no padding) ----

    @Test
    void anEmptySetIsTheHashOfTheEmptyText() {
        assertThat(token(List.of())).isEqualTo("47DEQpj8HBSa-_TImW-5JA");
    }

    @Test
    void oneEscalationIsTheHashOfItsIdColonVersionLine() {
        assertThat(token(List.of(detail(ID_1, 3, Status.OPEN, 8, NOW, 0))))
                .isEqualTo("vlRqavTRyNYizjMOcrtR2Q");
    }

    @Test
    void twoEscalationsAreSortedByIdAndJoinedByALineFeedWhateverTheInputOrder() {
        Detail one = detail(ID_1, 3, Status.OPEN, 8, NOW, 0);
        Detail two = detail(ID_2, 1, Status.ACK, 7, NOW, 0);

        assertThat(token(List.of(one, two))).isEqualTo("Ebon-9CnDtimWcfP_UlepQ");
        assertThat(token(List.of(two, one))).isEqualTo("Ebon-9CnDtimWcfP_UlepQ");
    }

    @Test
    void theTokenIs22UrlSafeCharactersWithNoPaddingForAnySet() {
        List<Detail> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(detail(UUID.randomUUID(), i, Status.OPEN, 8, NOW, 0));
            assertThat(token(many)).hasSize(22).matches("^[A-Za-z0-9_-]{22}$");
        }
    }

    @Test
    void theTokenChangesWhenAnEscalationJoinsLeavesOrChangesVersion() {
        Detail one = detail(ID_1, 3, Status.OPEN, 8, NOW, 0);
        Detail two = detail(ID_2, 1, Status.ACK, 7, NOW, 0);
        String both = token(List.of(one, two));

        assertThat(token(List.of(one))).isNotEqualTo(both);
        assertThat(token(List.of(two))).isNotEqualTo(both);
        Detail oneAfterScoreRise = detail(ID_1, 4, Status.OPEN, 10, NOW, 0);
        assertThat(token(List.of(oneAfterScoreRise, two))).isNotEqualTo(both);
        // acknowledge bumps the version
        Detail oneAcknowledged = detail(ID_1, 4, Status.ACK, 8, NOW, 0);
        assertThat(token(List.of(oneAcknowledged, two))).isNotEqualTo(both);
    }

    @Test
    void theOwnerTokenGainsOneBgLineOnlyWhenGrantsWaitAndChangesWithTheCountAndTheNewestId() {
        Detail one = detail(ID_1, 3, Status.OPEN, 8, NOW, 0);
        UUID newest = UUID.fromString("AAAAAAAA-0000-4000-8000-000000000001");
        UUID other = UUID.fromString("BBBBBBBB-0000-4000-8000-000000000002");
        String plain = counts(List.of(one), NOW).changeToken();

        Counts none = SafetyRows.counts(List.of(one), MedicineRound.empty(), NOW,
                new WardScopeRepository.UnreviewedMark(0, null));
        Counts two = SafetyRows.counts(List.of(one), MedicineRound.empty(), NOW,
                new WardScopeRepository.UnreviewedMark(2, newest));
        Counts three = SafetyRows.counts(List.of(one), MedicineRound.empty(), NOW,
                new WardScopeRepository.UnreviewedMark(3, newest));
        Counts twoOther = SafetyRows.counts(List.of(one), MedicineRound.empty(), NOW,
                new WardScopeRepository.UnreviewedMark(2, other));

        assertThat(none.changeToken()).isEqualTo(plain);
        assertThat(none.emergencyAccessWaiting()).isZero();
        assertThat(two.emergencyAccessWaiting()).isEqualTo(2);
        assertThat(two.changeToken()).hasSize(22).isNotEqualTo(plain);
        assertThat(three.changeToken()).isNotEqualTo(two.changeToken());
        assertThat(twoOther.changeToken()).isNotEqualTo(two.changeToken());
        assertThat(SafetyRows.changeToken(List.of(one), List.of("bg:2:" + newest.toString().toLowerCase())))
                .isEqualTo(two.changeToken());
        assertThat(counts(List.of(one), NOW).emergencyAccessWaiting()).isNull();
    }

    @Test
    void theTokenDoesNotChangeOnASeniorCallOrWhenOnlyTimePasses() {
        Detail before = detail(ID_1, 3, Status.OPEN, 8, NOW, 0);
        Detail afterCall = detail(ID_1, 3, Status.OPEN, 8, NOW, 5);
        assertThat(token(List.of(afterCall))).isEqualTo(token(List.of(before)));
        // the same set read at two clocks: the token has no time in it
        assertThat(counts(List.of(before), NOW).changeToken())
                .isEqualTo(counts(List.of(before), NOW.plusSeconds(3600)).changeToken());
    }

    @Test
    void theTokenIsIndependentOfScoreNamesAndPlan() {
        Detail low = detail(ID_1, 3, Status.OPEN, 5, NOW, 0);
        Detail high = detail(ID_1, 3, Status.OPEN, 12, NOW.minusSeconds(999), 0);
        assertThat(token(List.of(low))).isEqualTo(token(List.of(high)));
    }

    // ---- the counts: one function for the board, the summary and the banner ----

    @Test
    void theCountsIgnoreClosedRowsAndCountPastTargetAgainstAsOf() {
        Detail pastOpen = detail(ID_1, 3, Status.OPEN, 8, NOW.minusSeconds(16 * 60), 0);
        Detail freshOpen = detail(ID_2, 1, Status.OPEN, 8, NOW.minusSeconds(60), 0);
        Detail acked = detail(UUID.randomUUID(), 2, Status.ACK, 8, NOW.minusSeconds(3600), 0);
        Detail closed = detail(UUID.randomUUID(), 2, Status.DONE, 8, NOW.minusSeconds(3600), 0);

        Counts counts = counts(List.of(pastOpen, freshOpen, acked, closed), NOW);

        assertThat(counts.open()).isEqualTo(3);
        assertThat(counts.pastTarget()).isEqualTo(1);
        assertThat(counts.asOf()).isEqualTo(NOW);
        List<Detail> live = new ArrayList<>(List.of(pastOpen, freshOpen, acked));
        Collections.reverse(live);
        assertThat(counts.changeToken()).isEqualTo(token(live));
    }

    @Test
    void noEscalationsGiveZeroCountsAndTheEmptyToken() {
        Counts counts = counts(List.of(), NOW);

        assertThat(counts.open()).isZero();
        assertThat(counts.pastTarget()).isZero();
        assertThat(counts.changeToken()).isEqualTo("47DEQpj8HBSa-_TImW-5JA");
    }

    @Test
    void theClockStartIsTheReopenTimeWhenThereIsOne() {
        Detail reopened = new Detail(ID_1, UUID.randomUUID(), 5, 9, Risk.HIGH, 15, Status.OPEN,
                NOW.minusSeconds(3600), UUID.randomUUID(), NOW.minusSeconds(60), null, null, null, null, 0, null,
                null);

        assertThat(counts(List.of(reopened), NOW).pastTarget()).isZero();
    }
}
