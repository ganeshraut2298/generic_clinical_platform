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
                .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module())
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

    // ---- SAH-37: new card facts, role-gated and set-gated ----

    private static final UUID RECORDER = UUID.randomUUID();

    private static LatestScore readings(Instant at) {
        return new LatestScore(ADMISSION, 5, Risk.MEDIUM, at,
                new VitalsRepository.Readings(85, 60, 95, 96, new java.math.BigDecimal("37.0"), 22), RECORDER);
    }

    private static AdmittedCards.FactsPlan plan(String... fields) {
        return AdmittedCards.FactsPlan.of(java.util.Set.of(fields), "OWNER", "OWNER");
    }

    private static ObjectMapper springLikeMapper() {
        return mapper().registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());
    }

    @Test
    void theFactsPlanFollowsTheDataGroupOfTheRoleAndTheShownFields() {
        java.util.Set<String> all = java.util.Set.copyOf(com.sahaj.ipd.domain.card.CardCatalogue.fieldKeys());

        assertThat(AdmittedCards.FactsPlan.of(all, "OWNER", "OWNER")).isEqualTo(
                new AdmittedCards.FactsPlan(true, true, true, true, true, true, true, true));
        assertThat(AdmittedCards.FactsPlan.of(all, "NURSE", "NURSE")).isEqualTo(
                new AdmittedCards.FactsPlan(true, true, true, true, true, true, true, false));
        assertThat(AdmittedCards.FactsPlan.of(all, "DOCTOR", "DOCTOR")).isEqualTo(
                new AdmittedCards.FactsPlan(true, true, true, true, true, true, true, false));
        assertThat(AdmittedCards.FactsPlan.of(all, "FRONT_DESK", "FRONT_DESK")).isEqualTo(
                new AdmittedCards.FactsPlan(false, false, false, false, false, false, false, true));
        // the token says OWNER but the membership now says NURSE: only what both allow
        assertThat(AdmittedCards.FactsPlan.of(all, "OWNER", "NURSE")).isEqualTo(
                new AdmittedCards.FactsPlan(true, true, true, true, true, true, true, false));
        assertThat(AdmittedCards.FactsPlan.of(all, "OWNER", "FRONT_DESK")).isEqualTo(
                new AdmittedCards.FactsPlan(false, false, false, false, false, false, false, true));
        assertThat(AdmittedCards.FactsPlan.of(all, "BILLING", "BILLING")).isEqualTo(AdmittedCards.FactsPlan.NONE);
        assertThat(AdmittedCards.FactsPlan.of(java.util.Set.of("name", "uhid", "ews", "allergies"), "OWNER", "OWNER"))
                .isEqualTo(AdmittedCards.FactsPlan.NONE);
        assertThat(AdmittedCards.FactsPlan.NONE.anyVitals()).isFalse();
        assertThat(plan("lastRecorded").anyVitals()).isTrue();
    }

    @Test
    void withoutAPlanNoNewFactIsInTheRawJson() throws Exception {
        AdmittedClinicalFlags flags = AdmittedCards.flags(null, readings(AS_OF.minusSeconds(60)), patient("x"), AS_OF);

        String json = springLikeMapper().writeValueAsString(AdmittedPatientRow.of(summary(), "UH-0001",
                UUID.randomUUID(), "R1", flags));

        assertThat(json).doesNotContain("diagnosis", "latestVitals", "mobile");
        assertThat(springLikeMapper().readTree(json).get("clinical").has("latestEws")).isTrue();
    }

    @Test
    void theDiagnosisIsTheReasonAndUnderEvaluationWhenBlank() {
        AdmittedClinicalFlags flags = AdmittedCards.flags(null, null, patient(null), AS_OF, plan("diagnosis"),
                "Chest pain", java.util.Map.of());
        AdmittedClinicalFlags blank = AdmittedCards.flags(null, null, patient(null), AS_OF, plan("diagnosis"), " ",
                java.util.Map.of());
        AdmittedClinicalFlags off = AdmittedCards.flags(null, null, patient(null), AS_OF, plan("bp"), "Chest pain",
                java.util.Map.of());

        assertThat(flags.diagnosis()).isEqualTo("Chest pain");
        assertThat(blank.diagnosis()).isEqualTo("Under evaluation");
        assertThat(off.diagnosis()).isNull();
        assertThat(flags.toString()).doesNotContain("Chest");
    }

    @Test
    void eachReadingIsPresentOnlyWhenItsFieldIsOnAndCarriesTheSah30Mark() throws Exception {
        AdmittedClinicalFlags flags = AdmittedCards.flags(null, readings(AS_OF.minusSeconds(120)), patient(null), AS_OF,
                plan("bp", "pulse", "spo2", "temp", "rr", "lastRecorded"), "x",
                java.util.Map.of(RECORDER, "Sr. Anjali Pawar"));

        JsonNode row = springLikeMapper().valueToTree(AdmittedPatientRow.of(summary(), "UH-0001", UUID.randomUUID(),
                null, flags));
        JsonNode tiles = row.get("clinical").get("latestVitals");

        assertThat(tiles.get("recordedAt").asText()).isEqualTo("2026-11-11T05:58:00Z");
        assertThat(tiles.get("recordedByName").asText()).isEqualTo("Sr. Anjali Pawar");
        assertThat(tiles.get("bp").get("systolic").asInt()).isEqualTo(85);
        assertThat(tiles.get("bp").get("diastolic").asInt()).isEqualTo(60);
        assertThat(tiles.get("bp").get("mark").asText()).isEqualTo("CRITICAL");
        assertThat(tiles.get("pulse").get("value").asInt()).isEqualTo(95);
        assertThat(tiles.get("pulse").get("mark").asText()).isEqualTo("CHECK");
        assertThat(tiles.get("spo2").get("value").asInt()).isEqualTo(96);
        assertThat(tiles.get("spo2").has("mark")).as("0 points: no mark").isFalse();
        assertThat(tiles.get("temp").get("value").decimalValue()).isEqualByComparingTo("37.0");
        assertThat(tiles.get("temp").has("mark")).isFalse();
        assertThat(tiles.get("rr").get("value").asInt()).isEqualTo(22);
        assertThat(tiles.get("rr").get("mark").asText()).isEqualTo("CHECK");
        assertThat(row.get("clinical").get("latestEws").get("score").asInt()).isEqualTo(5);
    }

    @Test
    void aReadingThatIsOffIsAbsentAndNeitherRecorderNorOtherReadingsLeak() throws Exception {
        AdmittedClinicalFlags flags = AdmittedCards.flags(null, readings(AS_OF.minusSeconds(120)), patient(null), AS_OF,
                plan("spo2"), "x", java.util.Map.of(RECORDER, "Sr. Anjali Pawar"));

        JsonNode tiles = springLikeMapper().valueToTree(AdmittedPatientRow.of(summary(), "UH-0001", UUID.randomUUID(),
                null, flags)).get("clinical").get("latestVitals");

        assertThat(tiles.has("spo2")).isTrue();
        assertThat(tiles.has("bp")).isFalse();
        assertThat(tiles.has("pulse")).isFalse();
        assertThat(tiles.has("temp")).isFalse();
        assertThat(tiles.has("rr")).isFalse();
        assertThat(tiles.has("recordedByName")).as("Last recorded is off").isFalse();
        assertThat(tiles.toString()).doesNotContain("Anjali");
    }

    @Test
    void noVitalsEverRecordedIsNullWhenAReadingIsOnAndAbsentWhenNoneIs() throws Exception {
        AdmittedClinicalFlags wanted = AdmittedCards.flags(null, null, patient(null), AS_OF, plan("bp"), "x",
                java.util.Map.of());
        AdmittedClinicalFlags notWanted = AdmittedCards.flags(null, null, patient(null), AS_OF, plan("diagnosis"), "x",
                java.util.Map.of());

        JsonNode withNull = springLikeMapper().valueToTree(AdmittedPatientRow.of(summary(), "UH-0001",
                UUID.randomUUID(), null, wanted)).get("clinical");
        JsonNode without = springLikeMapper().valueToTree(AdmittedPatientRow.of(summary(), "UH-0001",
                UUID.randomUUID(), null, notWanted)).get("clinical");

        assertThat(withNull.has("latestVitals")).isTrue();
        assertThat(withNull.get("latestVitals").isNull()).isTrue();
        assertThat(without.has("latestVitals")).isFalse();
    }

    @Test
    void aMissingRecorderNameIsTeamMember() {
        AdmittedClinicalFlags flags = AdmittedCards.flags(null, readings(AS_OF.minusSeconds(60)), patient(null), AS_OF,
                plan("lastRecorded"), "x", java.util.Map.of());

        assertThat(flags.latestVitals().get().recordedByName()).isEqualTo("Team member");
        assertThat(flags.latestVitals().get().bp()).isNull();
    }

    @Test
    void theMobileIsAbsentWhenNotSentNullWhenTheRecordHasNoneAndTheNumberWhenSent() throws Exception {
        AdmittedPatientRow absent = AdmittedPatientRow.of(summary(), "UH-0001", UUID.randomUUID(), null, null);
        AdmittedPatientRow none = AdmittedPatientRow.of(summary(), "UH-0001", UUID.randomUUID(), null, null,
                java.util.Optional.empty());
        AdmittedPatientRow sent = AdmittedPatientRow.of(summary(), "UH-0001", UUID.randomUUID(), null, null,
                java.util.Optional.of("9876543210"));

        assertThat(springLikeMapper().valueToTree(absent).has("mobile")).isFalse();
        JsonNode noneJson = springLikeMapper().valueToTree(none);
        assertThat(noneJson.has("mobile")).isTrue();
        assertThat(noneJson.get("mobile").isNull()).isTrue();
        assertThat(springLikeMapper().valueToTree(sent).get("mobile").asText()).isEqualTo("9876543210");
        assertThat(sent.toString()).doesNotContain("9876543210");
    }

    @Test
    void theNewFactsNeverReachToString() {
        AdmittedClinicalFlags flags = AdmittedCards.flags(null, readings(AS_OF.minusSeconds(60)), patient(null), AS_OF,
                plan("diagnosis", "bp", "lastRecorded"), "Chest pain", java.util.Map.of(RECORDER, "Sr. Anjali Pawar"));

        String text = flags + " " + flags.latestVitals().get() + " " + flags.latestVitals().get().bp() + " "
                + readings(AS_OF);

        assertThat(text).doesNotContain("Chest", "Anjali", "37.0");
    }
}
