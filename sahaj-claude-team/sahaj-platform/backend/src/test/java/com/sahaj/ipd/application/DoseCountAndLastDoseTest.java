package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineViews.EmrSheet;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderRow;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAH-32 the count on the Nursing sheet's tab ({@code NursingSheet.doseCount}) and the EMR line "Last dose"
 * ({@code MedicineOrderRow.lastDose}, ADR-011 D11, OQ-32-4). No database.
 */
class DoseCountAndLastDoseTest extends DoseTestBase {

    private MedicineOrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new MedicineOrderService(fake, admissions, events, patients, patientAccess, actors, team,
                auditLog, gate, catalogue, assembler, guard);
    }

    private int count(boolean admitted) {
        return marAssembler.doseCount(TENANT, ADMISSION, admitted, fake.databaseNow());
    }

    private EmrSheet emr() {
        return orderService.emr(TENANT, DOCTOR, "DOCTOR", ADMISSION);
    }

    private static MedicineOrderRow row(EmrSheet sheet, UUID orderId) {
        return sheet.orders().stream().filter(o -> o.id().equals(orderId)).findFirst().orElseThrow();
    }

    // ---- the count ----

    @Test
    void theCountIsZeroWithNoOrdersAndRunsNoDoseQueryForAnAdmissionWithoutActiveOrders() {
        assertThat(count(true)).isZero();
        ofStatus("PENDING", null);
        assertThat(count(true)).isZero();
    }

    @Test
    void aDischargedAdmissionCountsNothing() {
        active("pcm", "od");

        assertThat(count(false)).isZero();
    }

    @Test
    void theCountIsTheDueAndLateSlotsOfTheActiveOrders() {
        active("pcm", "od");
        active("ns", "q8");
        ofStatus("PENDING", null);
        ofStatus("STOPPED", CHECKED_LONG_AGO);
        ofStatus("REJECTED", null);

        assertThat(count(true)).isEqualTo(2);
    }

    @Test
    void recordedSlotsAreNotCounted() {
        OrderRow order = active("ns", "q8");
        doses.seed(ADMISSION, order.id(), Instant.parse("2026-11-11T00:30:00Z"), "REFUSED", "PATIENT_REFUSED", NURSE,
                null, false);

        assertThat(count(true)).isZero();
    }

    @Test
    void theCountEqualsTheMarsOwnCountAtTheSameInstant() {
        active("pcm", "od");
        active("ns", "q8");
        OrderRow third = active("panto", "tds");
        doses.seed(ADMISSION, third.id(), EIGHT_AM, "GIVEN", null, NURSE, null, false);

        assertThat(count(true)).isEqualTo(marService.mar(TENANT, NURSE, "NURSE", ADMISSION).doseCount());
    }

    @Test
    void anOrderCheckedAtHalfPastTenDoesNotCountItsEightAmSlot() {
        fake.seedAt(ADMISSION, "pcm", "bd", "ACTIVE", DOCTOR, Instant.parse("2026-11-11T05:00:00Z"));
        fake.now = Instant.parse("2026-11-11T05:05:00Z");

        assertThat(count(true)).isZero();
    }

    // ---- the EMR line "Last dose" ----

    @Test
    void anOrderWithNoDoseHasNoLastDose() {
        OrderRow order = active("pcm", "od");

        assertThat(row(emr(), order.id()).lastDose()).isNull();
    }

    @Test
    void theLastDoseIsTheOneWithTheLatestSlotWhateverTheOutcome() {
        OrderRow order = active("pcm", "tds");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "GIVEN", null, NURSE, null, false);
        doses.seed(ADMISSION, order.id(), TWO_PM, "HELD", "NIL_BY_MOUTH", NURSE, null, false);
        doses.seed(ADMISSION, order.id(), Instant.parse("2026-11-10T14:30:00Z"), "GIVEN", null, NURSE, null, false);

        MedicineOrderRow row = row(emr(), order.id());

        assertThat(row.lastDose().slotAt()).isEqualTo(TWO_PM);
        assertThat(row.lastDose().outcome()).isEqualTo("HELD");
        assertThat(row.lastDose().reason().key()).isEqualTo("NIL_BY_MOUTH");
        assertThat(row.lastDose().reason().label()).isEqualTo("Nil by mouth");
        assertThat(row.lastDose().recordedByName()).isEqualTo("Sr. Anjali Pawar");
        assertThat(row.lastDose().secondCheckByName()).isNull();
    }

    @Test
    void aRefusalIsShownWithItsReason() {
        OrderRow order = active("pcm", "od");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "REFUSED", "PATIENT_REFUSED", NURSE, null, false);

        MedicineOrderRow row = row(emr(), order.id());

        assertThat(row.lastDose().outcome()).isEqualTo("REFUSED");
        assertThat(row.lastDose().reason().label()).isEqualTo("Patient refused");
    }

    @Test
    void aGivenHighAlertDoseNamesTheSecondNurse() {
        OrderRow order = active("hep", "bd");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "GIVEN", null, NURSE, SECOND_NURSE, true);

        assertThat(row(emr(), order.id()).lastDose().secondCheckByName()).isEqualTo("Sr. Rupali More");
    }

    @Test
    void aStoppedOrderKeepsItsLastDose() {
        OrderRow order = active("pcm", "od");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "GIVEN", null, NURSE, null, false);
        fake.markStopped(TENANT, order.id(), DOCTOR);

        MedicineOrderRow row = row(emr(), order.id());

        assertThat(row.status()).isEqualTo("STOPPED");
        assertThat(row.lastDose()).isNotNull();
        assertThat(row.lastDose().outcome()).isEqualTo("GIVEN");
    }

    @Test
    void eachOrderGetsItsOwnLastDose() {
        OrderRow first = active("pcm", "od");
        OrderRow second = active("panto", "od");
        OrderRow third = active("ns", "od");
        doses.seed(ADMISSION, first.id(), EIGHT_AM, "GIVEN", null, NURSE, null, false);
        doses.seed(ADMISSION, second.id(), EIGHT_AM, "HELD", "AWAY_FROM_WARD", OWNER, null, false);

        EmrSheet sheet = emr();

        assertThat(row(sheet, first.id()).lastDose().outcome()).isEqualTo("GIVEN");
        assertThat(row(sheet, second.id()).lastDose().outcome()).isEqualTo("HELD");
        assertThat(row(sheet, second.id()).lastDose().recordedByName()).isEqualTo("Owner Kulkarni");
        assertThat(row(sheet, third.id()).lastDose()).isNull();
    }

    @Test
    void anotherAdmissionsDoseIsNotShown() {
        OrderRow order = active("pcm", "od");
        doses.seed(UUID.randomUUID(), order.id(), EIGHT_AM, "GIVEN", null, NURSE, null, false);

        assertThat(row(emr(), order.id()).lastDose()).isNull();
    }

    @Test
    void openingTheEmrWritesNoDoseAndStillOneRecordAccessRow() {
        OrderRow order = active("pcm", "od");
        doses.seed(ADMISSION, order.id(), EIGHT_AM, "GIVEN", null, NURSE, null, false);

        emr();

        assertThat(doses.writes).isZero();
        org.mockito.Mockito.verify(patientAccess).recordOpened(TENANT, PATIENT, DOCTOR);
    }
}
