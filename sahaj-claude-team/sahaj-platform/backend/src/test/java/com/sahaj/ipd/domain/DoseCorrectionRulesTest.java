package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.domain.DoseCorrectionRules.Refusal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-76-5 undo and "recorded in error" (ADR-027 D3, OQ-76-25): the recorder, the windows and the dose state. */
class DoseCorrectionRulesTest {

    private static final UUID NURSE = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Instant SLOT = Instant.parse("2026-11-11T02:30:00Z");
    private static final Instant RECORDED = Instant.parse("2026-11-11T02:34:00Z");
    private static final Instant UNDO_UNTIL = RECORDED.plusSeconds(10);

    @Test
    void theRecorderCanUndoWithinTheWindowIncludingTheLastSecond() {
        assertThat(DoseCorrectionRules.undo(true, false, NURSE, NURSE, UNDO_UNTIL, RECORDED.plusSeconds(3))).isNull();
        assertThat(DoseCorrectionRules.undo(true, false, NURSE, NURSE, UNDO_UNTIL, UNDO_UNTIL)).isNull();
    }

    @Test
    void afterTheWindowUndoIsExpired() {
        assertThat(DoseCorrectionRules.undo(true, false, NURSE, NURSE, UNDO_UNTIL, UNDO_UNTIL.plusMillis(1)))
                .isEqualTo(Refusal.UNDO_EXPIRED);
    }

    @Test
    void aDoseWithNoWindowCannotBeUndone() {
        assertThat(DoseCorrectionRules.undo(true, false, NURSE, NURSE, null, RECORDED)).isEqualTo(Refusal.UNDO_EXPIRED);
    }

    @Test
    void anotherNurseCannotUndo() {
        assertThat(DoseCorrectionRules.undo(true, false, NURSE, OTHER, UNDO_UNTIL, RECORDED))
                .isEqualTo(Refusal.NOT_RECORDER);
    }

    @Test
    void aNotGivenOrAlreadyCorrectedDoseIsRefusedBeforeTheRecorderRule() {
        assertThat(DoseCorrectionRules.undo(false, false, NURSE, OTHER, UNDO_UNTIL, RECORDED))
                .isEqualTo(Refusal.NOT_GIVEN_OR_CORRECTED);
        assertThat(DoseCorrectionRules.undo(true, true, NURSE, OTHER, UNDO_UNTIL, RECORDED))
                .isEqualTo(Refusal.NOT_GIVEN_OR_CORRECTED);
        assertThat(DoseCorrectionRules.inError(false, false, SLOT, RECORDED)).isEqualTo(Refusal.NOT_GIVEN_OR_CORRECTED);
        assertThat(DoseCorrectionRules.inError(true, true, SLOT, RECORDED)).isEqualTo(Refusal.NOT_GIVEN_OR_CORRECTED);
    }

    @Test
    void inErrorIsAllowedOnTheIstDayOfTheSlotOnly() {
        assertThat(DoseCorrectionRules.inError(true, false, SLOT, Instant.parse("2026-11-11T18:29:00Z"))).isNull();
        assertThat(DoseCorrectionRules.inError(true, false, SLOT, Instant.parse("2026-11-11T18:30:00Z")))
                .isEqualTo(Refusal.ERROR_WINDOW_CLOSED);
    }

    @Test
    void theSecondsLeftNeverGoBelowZero() {
        assertThat(DoseCorrectionRules.undoSecondsLeft(UNDO_UNTIL, RECORDED)).isEqualTo(10);
        assertThat(DoseCorrectionRules.undoSecondsLeft(UNDO_UNTIL, RECORDED.plusSeconds(4))).isEqualTo(6);
        assertThat(DoseCorrectionRules.undoSecondsLeft(UNDO_UNTIL, UNDO_UNTIL)).isZero();
        assertThat(DoseCorrectionRules.undoSecondsLeft(UNDO_UNTIL, UNDO_UNTIL.plusSeconds(5))).isZero();
        assertThat(DoseCorrectionRules.undoSecondsLeft(null, RECORDED)).isZero();
    }
}
