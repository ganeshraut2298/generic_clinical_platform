package com.sahaj.ipd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Answer;
import com.sahaj.ipd.domain.EscalationState.Snapshot;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.EscalationState.VitalsEffect;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-33 the escalation state machine: every transition, refusal, replay and the check order (ADR-007 D2, D4, D7). */
class EscalationStateTest {

    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID OTHER_DOCTOR = UUID.randomUUID();
    private static final String PLAN = "Oxygen increased to 6 L, repeat vitals in 30 minutes";

    private static Snapshot open(long version, int calls) {
        return new Snapshot(Status.OPEN, version, 8, Risk.HIGH, calls, null, null, null, null);
    }

    private static Snapshot ack(long version, UUID by) {
        return new Snapshot(Status.ACK, version, 8, Risk.HIGH, 0, by, null, null, null);
    }

    private static Snapshot done(long version, UUID closedBy, ResponseAction action, String note) {
        return new Snapshot(Status.DONE, version, 8, Risk.HIGH, 0, closedBy, closedBy,
                action == null ? null : action.name(), note);
    }

    // ---- the transition table ----

    @Test
    void aReopenNeedsAnAcknowledgedMediumEscalationAndAHighReading() {
        assertThat(EscalationState.canReopen(Status.ACK, Risk.MEDIUM, Risk.HIGH)).isTrue();
        assertThat(EscalationState.canReopen(Status.ACK, Risk.MEDIUM, Risk.MEDIUM)).isFalse();
        assertThat(EscalationState.canReopen(Status.ACK, Risk.HIGH, Risk.HIGH)).isFalse();
        assertThat(EscalationState.canReopen(Status.ACK, Risk.MEDIUM, Risk.LOW)).isFalse();
        assertThat(EscalationState.canReopen(Status.OPEN, Risk.MEDIUM, Risk.HIGH)).isFalse();
        assertThat(EscalationState.canReopen(Status.DONE, Risk.MEDIUM, Risk.HIGH)).isFalse();
    }

    @Test
    void theStatusIsReadFromItsStoredWordAndAnUnknownWordFailsFast() {
        assertThat(Status.of("OPEN")).isEqualTo(Status.OPEN);
        assertThat(Status.of("ACK")).isEqualTo(Status.ACK);
        assertThat(Status.of("DONE")).isEqualTo(Status.DONE);
        assertThatThrownBy(() -> Status.of("GONE")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- what a vitals reading does ----

    @Test
    void aLowReadingOrAnEqualOrLowerScoreChangesNothing() {
        assertThat(EscalationState.onVitals(Status.OPEN, 8, Risk.HIGH, 0, Risk.LOW)).isEqualTo(VitalsEffect.NONE);
        assertThat(EscalationState.onVitals(Status.ACK, 8, Risk.HIGH, 2, Risk.LOW)).isEqualTo(VitalsEffect.NONE);
        assertThat(EscalationState.onVitals(Status.OPEN, 8, Risk.HIGH, 8, Risk.HIGH)).isEqualTo(VitalsEffect.NONE);
        assertThat(EscalationState.onVitals(Status.OPEN, 8, Risk.HIGH, 5, Risk.MEDIUM)).isEqualTo(VitalsEffect.NONE);
        assertThat(EscalationState.onVitals(Status.ACK, 8, Risk.HIGH, 7, Risk.HIGH)).isEqualTo(VitalsEffect.NONE);
        assertThat(EscalationState.onVitals(Status.DONE, 5, Risk.MEDIUM, 12, Risk.HIGH)).isEqualTo(VitalsEffect.NONE);
    }

    @Test
    void aHigherScoreUpdatesAnOpenEscalationEvenWhenTheRiskRises() {
        assertThat(EscalationState.onVitals(Status.OPEN, 5, Risk.MEDIUM, 6, Risk.MEDIUM))
                .isEqualTo(VitalsEffect.UPDATED);
        assertThat(EscalationState.onVitals(Status.OPEN, 5, Risk.MEDIUM, 12, Risk.HIGH))
                .isEqualTo(VitalsEffect.UPDATED);
        assertThat(EscalationState.onVitals(Status.OPEN, 8, Risk.HIGH, 9, Risk.HIGH)).isEqualTo(VitalsEffect.UPDATED);
    }

    @Test
    void aHighReadingReopensAnAcknowledgedMediumEscalation() {
        assertThat(EscalationState.onVitals(Status.ACK, 5, Risk.MEDIUM, 7, Risk.HIGH))
                .isEqualTo(VitalsEffect.REOPENED);
        assertThat(EscalationState.onVitals(Status.ACK, 3, Risk.MEDIUM, 15, Risk.HIGH))
                .isEqualTo(VitalsEffect.REOPENED);
    }

    @Test
    void aRiseInsideTheSameRiskKeepsAnAcknowledgedEscalationAcknowledged() {
        assertThat(EscalationState.onVitals(Status.ACK, 5, Risk.MEDIUM, 6, Risk.MEDIUM))
                .isEqualTo(VitalsEffect.UPDATED);
        assertThat(EscalationState.onVitals(Status.ACK, 7, Risk.HIGH, 9, Risk.HIGH)).isEqualTo(VitalsEffect.UPDATED);
    }

    // ---- acknowledge ----

    @Test
    void anOpenEscalationAtTheSentVersionIsAcknowledged() {
        assertThat(EscalationState.acknowledge(open(3, 0), 3, DOCTOR)).isEqualTo(Answer.WRITE);
    }

    @Test
    void anOpenEscalationAtAnotherVersionIsChangedAndNeverWritten() {
        assertThat(EscalationState.acknowledge(open(4, 0), 3, DOCTOR)).isEqualTo(Answer.CHANGED_SCORE);
        assertThat(EscalationState.acknowledge(open(2, 0), 3, DOCTOR)).isEqualTo(Answer.CHANGED_SCORE);
    }

    @Test
    void theSameDoctorRepeatingAfterALostAnswerGetsAReplay() {
        assertThat(EscalationState.acknowledge(ack(4, DOCTOR), 3, DOCTOR)).isEqualTo(Answer.REPLAY);
    }

    @Test
    void aRepeatIsNotAReplayForAnotherDoctorOrAnotherVersion() {
        assertThat(EscalationState.acknowledge(ack(4, DOCTOR), 3, OTHER_DOCTOR))
                .isEqualTo(Answer.ALREADY_ACKNOWLEDGED);
        assertThat(EscalationState.acknowledge(ack(5, DOCTOR), 3, DOCTOR)).isEqualTo(Answer.ALREADY_ACKNOWLEDGED);
        assertThat(EscalationState.acknowledge(ack(4, DOCTOR), 4, DOCTOR)).isEqualTo(Answer.ALREADY_ACKNOWLEDGED);
        assertThat(EscalationState.acknowledge(ack(4, DOCTOR), 3, null)).isEqualTo(Answer.ALREADY_ACKNOWLEDGED);
    }

    @Test
    void aClosedEscalationCannotBeAcknowledgedWhateverTheVersion() {
        Snapshot closed = done(5, DOCTOR, ResponseAction.NO_CHANGE, PLAN);

        assertThat(EscalationState.acknowledge(closed, 4, DOCTOR)).isEqualTo(Answer.ALREADY_CLOSED);
        assertThat(EscalationState.acknowledge(closed, 5, OTHER_DOCTOR)).isEqualTo(Answer.ALREADY_CLOSED);
    }

    @Test
    void theStateAnswerComesBeforeTheVersionAnswerOnAcknowledge() {
        // acknowledged by someone else AND a stale version: the state is what the doctor must be told
        assertThat(EscalationState.acknowledge(ack(9, OTHER_DOCTOR), 1, DOCTOR))
                .isEqualTo(Answer.ALREADY_ACKNOWLEDGED);
        assertThat(EscalationState.acknowledge(done(9, OTHER_DOCTOR, ResponseAction.NO_CHANGE, PLAN), 1, DOCTOR))
                .isEqualTo(Answer.ALREADY_CLOSED);
    }

    // ---- respond ----

    @Test
    void anOpenOrAcknowledgedEscalationAtTheSentVersionIsClosed() {
        assertThat(EscalationState.respond(open(3, 0), 3, DOCTOR, ResponseAction.BEDSIDE_REVIEW, PLAN))
                .isEqualTo(Answer.WRITE);
        assertThat(EscalationState.respond(ack(4, DOCTOR), 4, DOCTOR, ResponseAction.BEDSIDE_REVIEW, PLAN))
                .isEqualTo(Answer.WRITE);
        assertThat(EscalationState.respond(ack(4, OTHER_DOCTOR), 4, DOCTOR, ResponseAction.BEDSIDE_REVIEW, PLAN))
                .isEqualTo(Answer.WRITE);
    }

    @Test
    void aStaleVersionIsChangedByTheScoreUnlessAnotherDoctorAcknowledgedIt() {
        assertThat(EscalationState.respond(open(4, 0), 3, DOCTOR, ResponseAction.NO_CHANGE, PLAN))
                .isEqualTo(Answer.CHANGED_SCORE);
        assertThat(EscalationState.respond(ack(5, DOCTOR), 3, DOCTOR, ResponseAction.NO_CHANGE, PLAN))
                .isEqualTo(Answer.CHANGED_SCORE);
        assertThat(EscalationState.respond(ack(5, OTHER_DOCTOR), 3, DOCTOR, ResponseAction.NO_CHANGE, PLAN))
                .isEqualTo(Answer.CHANGED_ACKNOWLEDGED);
    }

    @Test
    void theClosingDoctorRepeatingTheSameAnswerAfterALostAnswerGetsAReplay() {
        Snapshot closed = done(5, DOCTOR, ResponseAction.TREATMENT_CHANGED, PLAN);

        assertThat(EscalationState.respond(closed, 4, DOCTOR, ResponseAction.TREATMENT_CHANGED, PLAN))
                .isEqualTo(Answer.REPLAY);
    }

    @Test
    void aRepeatIsNotAReplayWhenTheUserTheActionThePlanOrTheVersionDiffers() {
        Snapshot closed = done(5, DOCTOR, ResponseAction.TREATMENT_CHANGED, PLAN);

        assertThat(EscalationState.respond(closed, 4, OTHER_DOCTOR, ResponseAction.TREATMENT_CHANGED, PLAN))
                .isEqualTo(Answer.ALREADY_CLOSED);
        assertThat(EscalationState.respond(closed, 4, DOCTOR, ResponseAction.NO_CHANGE, PLAN))
                .isEqualTo(Answer.ALREADY_CLOSED);
        assertThat(EscalationState.respond(closed, 4, DOCTOR, ResponseAction.TREATMENT_CHANGED, PLAN + " more"))
                .isEqualTo(Answer.ALREADY_CLOSED);
        assertThat(EscalationState.respond(closed, 3, DOCTOR, ResponseAction.TREATMENT_CHANGED, PLAN))
                .isEqualTo(Answer.ALREADY_CLOSED);
        assertThat(EscalationState.respond(closed, 5, DOCTOR, ResponseAction.TREATMENT_CHANGED, PLAN))
                .isEqualTo(Answer.ALREADY_CLOSED);
    }

    @Test
    void anEscalationClosedAtDischargeIsNeverAReplayAndIsClosed() {
        Snapshot atDischarge = new Snapshot(Status.DONE, 5, 8, Risk.HIGH, 0, null, DOCTOR,
                ResponseAction.CLOSED_AT_DISCHARGE.name(), null);

        assertThat(EscalationState.respond(atDischarge, 4, DOCTOR, ResponseAction.NO_CHANGE, PLAN))
                .isEqualTo(Answer.ALREADY_CLOSED);
    }

    @Test
    void aClosedEscalationIsNotChangedByASecondCloseEvenWithAStaleVersion() {
        Snapshot closed = done(9, DOCTOR, ResponseAction.NO_CHANGE, PLAN);

        assertThat(EscalationState.respond(closed, 1, DOCTOR, ResponseAction.NO_CHANGE, PLAN))
                .isEqualTo(Answer.ALREADY_CLOSED);
    }

    // ---- call senior (ADR-007 R4) ----

    /** A history that answers from fixed values and counts how often the throttle was read. */
    private static final class History implements EscalationState.CallHistory {
        private final boolean ownAbove;
        private final boolean recent;

        History(boolean ownAbove, boolean recent) {
            this.ownAbove = ownAbove;
            this.recent = recent;
        }

        @Override
        public boolean ownCallAbove(int sentCalls) {
            return ownAbove;
        }

        @Override
        public boolean enteredRecently() {
            return recent;
        }
    }

    private static final History NOTHING = new History(false, false);

    @Test
    void aCallOnAnOpenEscalationWithTheVersionAndTheCountTheNurseSawIsWritten() {
        assertThat(EscalationState.callSenior(open(3, 0), 3, 0, NOTHING)).isEqualTo(Answer.WRITE);
        assertThat(EscalationState.callSenior(open(3, 2), 3, 2, NOTHING)).isEqualTo(Answer.WRITE);
    }

    @Test
    void aStoredCountLowerThanTheSentOneIsChangedAndNothingIsWritten() {
        assertThat(EscalationState.callSenior(open(3, 1), 3, 5, NOTHING)).isEqualTo(Answer.CHANGED_SCORE);
        // checked before the history is asked: even a caller with a recent entry gets the 409
        assertThat(EscalationState.callSenior(open(3, 0), 3, 1, new History(true, true)))
                .isEqualTo(Answer.CHANGED_SCORE);
    }

    @Test
    void aHigherStoredCountIsAReplayWhenTheCallersOwnCallIsAboveTheSentCount() {
        assertThat(EscalationState.callSenior(open(3, 1), 3, 0, new History(true, false))).isEqualTo(Answer.REPLAY);
        assertThat(EscalationState.callSenior(open(3, 1), 3, 0, new History(true, true))).isEqualTo(Answer.REPLAY);
    }

    @Test
    void aHigherStoredCountFromAnotherUsersCallIsSeenOtherCallUnlessThrottled() {
        assertThat(EscalationState.callSenior(open(3, 1), 3, 0, NOTHING)).isEqualTo(Answer.SEEN_OTHER_CALL);
        assertThat(EscalationState.callSenior(open(3, 4), 3, 2, NOTHING)).isEqualTo(Answer.SEEN_OTHER_CALL);
        assertThat(EscalationState.callSenior(open(3, 1), 3, 0, new History(false, true))).isEqualTo(Answer.REPLAY);
    }

    @Test
    void anEqualCountInsideTheThrottleIsAReplayAndWritesNothing() {
        assertThat(EscalationState.callSenior(open(3, 2), 3, 2, new History(false, true))).isEqualTo(Answer.REPLAY);
    }

    @Test
    void theCapAnswersOnlyForAnEqualCountOutsideTheThrottle() {
        int cap = EscalationState.MAX_SENIOR_CALLS;
        assertThat(cap).isEqualTo(32_767);
        assertThat(EscalationState.callSenior(open(3, cap), 3, cap, NOTHING)).isEqualTo(Answer.CALL_LIMIT);
        assertThat(EscalationState.callSenior(open(3, cap), 3, cap, new History(false, true)))
                .isEqualTo(Answer.REPLAY);
        assertThat(EscalationState.callSenior(open(3, cap - 1), 3, cap - 1, NOTHING)).isEqualTo(Answer.WRITE);
        // a stale screen at the cap is a seen-other-call, not the cap answer
        assertThat(EscalationState.callSenior(open(3, cap), 3, cap - 1, NOTHING)).isEqualTo(Answer.SEEN_OTHER_CALL);
    }

    @Test
    void aCallAtAnotherVersionIsChangedBeforeTheCountIsCompared() {
        assertThat(EscalationState.callSenior(open(4, 1), 3, 0, NOTHING)).isEqualTo(Answer.CHANGED_SCORE);
        assertThat(EscalationState.callSenior(open(4, 1), 3, 1, NOTHING)).isEqualTo(Answer.CHANGED_SCORE);
    }

    @Test
    void aCallOnAnAcknowledgedOrClosedEscalationIsAStateAnswerBeforeAnyVersionAnswer() {
        assertThat(EscalationState.callSenior(ack(9, DOCTOR), 1, 0, NOTHING)).isEqualTo(Answer.ALREADY_ACKNOWLEDGED);
        assertThat(EscalationState.callSenior(done(9, DOCTOR, ResponseAction.NO_CHANGE, PLAN), 1, 0, NOTHING))
                .isEqualTo(Answer.ALREADY_CLOSED);
    }

    @Test
    void theHistoryIsNotReadForAnAnswerThatDoesNotNeedIt() {
        EscalationState.CallHistory forbidden = new EscalationState.CallHistory() {
            @Override
            public boolean ownCallAbove(int sentCalls) {
                throw new AssertionError("must not be read");
            }

            @Override
            public boolean enteredRecently() {
                throw new AssertionError("must not be read");
            }
        };
        assertThat(EscalationState.callSenior(ack(9, DOCTOR), 1, 0, forbidden))
                .isEqualTo(Answer.ALREADY_ACKNOWLEDGED);
        assertThat(EscalationState.callSenior(open(4, 1), 3, 0, forbidden)).isEqualTo(Answer.CHANGED_SCORE);
        assertThat(EscalationState.callSenior(open(3, 0), 3, 1, forbidden)).isEqualTo(Answer.CHANGED_SCORE);
    }

    // ---- the answers ----

    @Test
    void onlyWriteIsAWriteAndOnlyReplayIsAReplay() {
        for (Answer a : Answer.values()) {
            assertThat(a.isWrite()).as(a.name()).isEqualTo(a == Answer.WRITE);
            assertThat(a.isReplay()).as(a.name()).isEqualTo(a == Answer.REPLAY);
        }
    }

    @Test
    void theSnapshotHidesThePlanFromToString() {
        Snapshot closed = done(5, DOCTOR, ResponseAction.NO_CHANGE, PLAN);

        assertThat(closed.toString()).doesNotContain(PLAN).doesNotContain(DOCTOR.toString());
    }
}
