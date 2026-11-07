package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionRepository.AdmittedRow;
import com.sahaj.ipd.application.AdmissionViews.AdmittedClinicalFlags;
import com.sahaj.ipd.application.AdmissionViews.AdmittedPatientRow;
import com.sahaj.ipd.application.AdmissionViews.CardAllergy;
import com.sahaj.ipd.application.AdmissionViews.CardEscalationFlag;
import com.sahaj.ipd.application.AdmissionViews.Summary;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.VitalsRepository.LatestScore;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.MedicineSafetyCheck.AllergyStatus;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-36 the flags of one card: escalation minutes from the response clock, the latest score, "vitals due" at 240 and 241
 * minutes, the allergy status and its text, a missing patient record, the JSON shape and the redacted {@code toString}.
 */
class AdmittedCardsTest {

    private static final Instant AS_OF = Instant.parse("2026-11-11T06:00:00Z");
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private static Detail escalation(Status status, int target, Instant raisedAt, Instant reopenedAt) {
        return new Detail(UUID.randomUUID(), ADMISSION, 3, 8, Risk.HIGH, target, status, raisedAt, UUID.randomUUID(),
                reopenedAt, null, null, null, null, 0, null, null);
    }

    private static PatientClinical patient(String allergy) {
        return new PatientClinical(PATIENT, "UH-0001", "Meena Patil", 41, "F", allergy);
    }

    private static LatestScore score(int score, Risk risk, Instant at) {
        return new LatestScore(ADMISSION, score, risk, at);
    }

    private static AdmittedClinicalFlags flagsWithVitalsAt(Instant recordedAt) {
        return AdmittedCards.flags(null, recordedAt == null ? null : score(2, Risk.LOW, recordedAt), patient(null), AS_OF);
    }

    // ---- escalation ----

    @Test
    void noOpenEscalationIsNullNotAbsent() {
        assertThat(AdmittedCards.flags(null, null, patient(null), AS_OF).escalation()).isNull();
    }

    @Test
    void anOpenEscalationShowsWholeMinutesRoundedDownTheTargetAndWithinTarget() {
        Instant raised = AS_OF.minusSeconds(12 * 60 + 59);

        CardEscalationFlag flag = AdmittedCards.flags(escalation(Status.OPEN, 15, raised, null), null, patient(null),
                AS_OF).escalation();

        assertThat(flag.status()).isEqualTo(Status.OPEN);
        assertThat(flag.waitingMinutes()).isEqualTo(12);
        assertThat(flag.targetMinutes()).isEqualTo(15);
        assertThat(flag.pastTarget()).isFalse();
    }

    @Test
    void pastTargetIsTrueOnlyAfterTheExactTargetAndTheMinutesStillRoundDown() {
        Instant exactlyFifteen = AS_OF.minusSeconds(15 * 60);
        Instant justOver = AS_OF.minusSeconds(15 * 60 + 1);

        CardEscalationFlag atTarget = AdmittedCards.flags(escalation(Status.OPEN, 15, exactlyFifteen, null), null,
                patient(null), AS_OF).escalation();
        CardEscalationFlag over = AdmittedCards.flags(escalation(Status.OPEN, 15, justOver, null), null, patient(null),
                AS_OF).escalation();

        assertThat(atTarget.waitingMinutes()).isEqualTo(15);
        assertThat(atTarget.pastTarget()).isFalse();
        assertThat(over.waitingMinutes()).isEqualTo(15);
        assertThat(over.pastTarget()).isTrue();
    }

    @Test
    void aReopenedEscalationCountsFromTheReopenNotTheFirstRaise() {
        Instant raisedLongAgo = AS_OF.minusSeconds(3 * 3600);
        Instant reopened = AS_OF.minusSeconds(4 * 60 + 10);

        CardEscalationFlag flag = AdmittedCards.flags(escalation(Status.OPEN, 15, raisedLongAgo, reopened), null,
                patient(null), AS_OF).escalation();

        assertThat(flag.waitingMinutes()).isEqualTo(4);
        assertThat(flag.pastTarget()).isFalse();
    }

    @Test
    void anAcknowledgedEscalationIsNeverPastTarget() {
        CardEscalationFlag flag = AdmittedCards.flags(escalation(Status.ACK, 15, AS_OF.minusSeconds(3600), null), null,
                patient(null), AS_OF).escalation();

        assertThat(flag.status()).isEqualTo(Status.ACK);
        assertThat(flag.waitingMinutes()).isEqualTo(60);
        assertThat(flag.pastTarget()).isFalse();
    }

    @Test
    void theMinutesAgreeWithTheRowOfTheSafetyBoard() {
        Detail d = escalation(Status.OPEN, 15, AS_OF.minusSeconds(20 * 60 + 5), AS_OF.minusSeconds(7 * 60 + 30));
        SafetyViews.Row boardRow = SafetyRows.row(d, "x", "9", SafetyRows.ActorNames.forIds(null, UUID.randomUUID(),
                java.util.List.of()), AS_OF, new SafetyRows.Access("NURSE", "NURSE"));

        CardEscalationFlag flag = AdmittedCards.flags(d, null, patient(null), AS_OF).escalation();

        assertThat(flag.waitingMinutes()).isEqualTo(boardRow.waitingMinutes());
        assertThat(flag.pastTarget()).isEqualTo(boardRow.pastTarget());
        assertThat(flag.targetMinutes()).isEqualTo(boardRow.targetMinutes());
    }

    // ---- score and vitals due ----

    @Test
    void noVitalsMeansNoScoreAndVitalsDue() {
        AdmittedClinicalFlags flags = flagsWithVitalsAt(null);

        assertThat(flags.latestEws()).isNull();
        assertThat(flags.vitalsDue()).isTrue();
    }

    @Test
    void theLatestScoreCarriesScoreRiskAndTime() {
        Instant at = AS_OF.minusSeconds(600);

        AdmittedClinicalFlags flags = AdmittedCards.flags(null, score(6, Risk.MEDIUM, at), patient(null), AS_OF);

        assertThat(flags.latestEws().score()).isEqualTo(6);
        assertThat(flags.latestEws().risk()).isEqualTo(Risk.MEDIUM);
        assertThat(flags.latestEws().recordedAt()).isEqualTo(at);
        assertThat(flags.vitalsDue()).isFalse();
    }

    @Test
    void vitalsAreDueOnlyAfterMoreThan240WholeMinutes() {
        assertThat(flagsWithVitalsAt(AS_OF.minusSeconds(239 * 60)).vitalsDue()).isFalse();
        assertThat(flagsWithVitalsAt(AS_OF.minusSeconds(240 * 60)).vitalsDue()).isFalse();
        // 4 h 59 s is 240 whole minutes: not yet due (the SAH-30 rule rounds down)
        assertThat(flagsWithVitalsAt(AS_OF.minusSeconds(240 * 60 + 59)).vitalsDue()).isFalse();
        assertThat(flagsWithVitalsAt(AS_OF.minusSeconds(241 * 60)).vitalsDue()).isTrue();
    }

    // ---- allergy ----

    @Test
    void aRecordedAllergySendsItsTextAsStored() {
        CardAllergy allergy = AdmittedCards.flags(null, null, patient("Penicillin (rash)"), AS_OF).allergy();

        assertThat(allergy.status()).isEqualTo(AllergyStatus.RECORDED);
        assertThat(allergy.text()).isEqualTo("Penicillin (rash)");
    }

    @Test
    void noneKnownAndNotRecordedSendNoText() {
        for (String text : new String[] {"None known", "no known allergies - none known", "Not recorded", "unknown",
            "?", "", "   ", null}) {
            CardAllergy allergy = AdmittedCards.allergy(patient(text));
            assertThat(allergy.status()).as(String.valueOf(text)).isNotEqualTo(AllergyStatus.RECORDED);
            assertThat(allergy.text()).as(String.valueOf(text)).isNull();
        }
        assertThat(AdmittedCards.allergy(patient("None known")).status()).isEqualTo(AllergyStatus.NONE_KNOWN);
        assertThat(AdmittedCards.allergy(patient(null)).status()).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(AdmittedCards.allergy(patient("  ")).status()).isEqualTo(AllergyStatus.NOT_RECORDED);
    }

    @Test
    void theStatusIsTheFunctionOfTheEmrAndTheMedicineCheckForADrugClassName() {
        // A named allergy class is RECORDED even when the text also says "none known" (the ADR-009 rule, not a copy).
        assertThat(AdmittedCards.allergy(patient("Sulfa drugs")).status()).isEqualTo(AllergyStatus.RECORDED);
        assertThat(AdmittedCards.allergy(patient("Amoxicillin")).text()).isEqualTo("Amoxicillin");
    }

    @Test
    void aPatientRecordThatCannotBeFoundIsNotRecordedAndNeverDropsTheRow() {
        AdmittedClinicalFlags flags = AdmittedCards.flags(null, null, null, AS_OF);

        assertThat(flags.allergy().status()).isEqualTo(AllergyStatus.NOT_RECORDED);
        assertThat(flags.allergy().text()).isNull();
    }

    // ---- JSON shape and privacy ----

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static Summary summary() {
        return new Summary(ADMISSION, "IP/26-27/0042", PATIENT, "Meena Patil", 41, "F", "Dr Test", "204", "General",
                "General ward", "planned", "Self", 1, 0L, 0L, "OK", AS_OF.minusSeconds(3600), 0L, false, false);
    }

    @Test
    void theClinicalPropertyIsWrittenWhenSetAndWithNullFactsAsNull() throws Exception {
        AdmittedClinicalFlags flags = AdmittedCards.flags(null, null, patient("None known"), AS_OF);

        JsonNode row = mapper().valueToTree(AdmittedPatientRow.of(summary(), "UH-0001", UUID.randomUUID(), null, flags));

        assertThat(row.has("clinical")).isTrue();
        JsonNode clinical = row.get("clinical");
        assertThat(clinical.has("escalation")).isTrue();
        assertThat(clinical.get("escalation").isNull()).isTrue();
        assertThat(clinical.has("latestEws")).isTrue();
        assertThat(clinical.get("latestEws").isNull()).isTrue();
        assertThat(clinical.get("vitalsDue").asBoolean()).isTrue();
        assertThat(clinical.get("allergy").get("status").asText()).isEqualTo("NONE_KNOWN");
        assertThat(clinical.get("allergy").has("text")).isFalse();
        assertThat(row.has("roomName")).isTrue();
        assertThat(row.get("roomName").isNull()).isTrue();
    }

    @Test
    void theClinicalPropertyIsAbsentNotNullWhenTheCallerMayNotReadIt() throws Exception {
        String json = mapper().writeValueAsString(AdmittedPatientRow.of(summary(), "UH-0001", UUID.randomUUID(), "R1",
                null));

        assertThat(json).doesNotContain("clinical", "escalation", "latestEws", "vitalsDue", "allergy");
        assertThat(mapper().readTree(json).has("clinical")).isFalse();
        assertThat(json).contains("\"uhid\":\"UH-0001\"").contains("\"roomName\":\"R1\"");
    }

    @Test
    void namesUhidAllergyTextAndScoresNeverReachToString() {
        AdmittedClinicalFlags flags = AdmittedCards.flags(escalation(Status.OPEN, 15, AS_OF.minusSeconds(60), null),
                score(9, Risk.HIGH, AS_OF.minusSeconds(60)), patient("Penicillin"), AS_OF);
        AdmittedPatientRow row = AdmittedPatientRow.of(summary(), "UH-0001", UUID.randomUUID(), "Room 12", flags);
        AdmissionRow admission = new AdmissionRow(ADMISSION, "IP/26-27/0042", PATIENT, UUID.randomUUID(), "204",
                "gen", "General ward", UUID.randomUUID(), "planned", "Self", "Chest pain", "ADMITTED", AS_OF, 0L);
        AdmittedRow placed = new AdmittedRow(admission, UUID.randomUUID(), "Room 12");

        String text = row + " " + flags + " " + flags.allergy() + " " + placed + " " + score(9, Risk.HIGH, AS_OF)
                + " " + patient("Penicillin");

        assertThat(text).doesNotContain("Meena", "Patil", "UH-0001", "Penicillin", "Room 12", "Chest pain", "HIGH",
                "General ward");
        assertThat(row.toString()).contains(ADMISSION.toString());
        assertThat(placed.toString()).contains(ADMISSION.toString());
    }
}
