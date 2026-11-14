package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sahaj.ipd.application.MedicineOrderService.CheckOnly;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderAnswer;
import com.sahaj.ipd.application.MedicineViews.MedicineOrderInput;
import com.sahaj.platform.api.ForbiddenException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-67-1 (ADR-025 D8): the EMR's "Check before completing" runs SAH-31's own check on a round's new medicine: a hard stop
 * writes the deny event and the IPD_MED_ORDER_BLOCKED audit row, a warning writes the record-access row, a pass writes nothing,
 * and nothing is ever ordered by the check. An order already placed under the same client key answers ORDERED.
 */
class MedicineOrderCheckOnlyTest extends MedicineTestBase {

    private MedicineOrderService service;
    private UUID clientKey;

    @BeforeEach
    void setUp() {
        service = new MedicineOrderService(fake, admissions, events, patients, patientAccess, actors, team, auditLog, gate,
                catalogue, assembler, guard);
        clientKey = UUID.randomUUID();
    }

    private MedicineOrderInput input(String drug, String frequency) {
        return new MedicineOrderInput(clientKey.toString(), drug, frequency, null, null);
    }

    private CheckOnly check(String drug, String frequency) {
        return service.checkOnly(TENANT, DOCTOR, "DOCTOR", ADMISSION, input(drug, frequency));
    }

    @Test
    void aHardStopIsReportedWithSah31sDenyEventAndAuditAndNothingIsStored() {
        allergyText = "Penicillin (rash)";

        CheckOnly result = check("amox", "bd");

        assertThat(result.outcome()).isEqualTo("BLOCKED");
        assertThat(result.check().hardStops()).hasSize(1);
        verify(events).insert(eq(TENANT), eq(ADMISSION), eq(ClinicalEventRepository.KIND_DENY), any(), eq(DOCTOR));
        verify(auditLog).record(eq("IPD_MED_ORDER_BLOCKED"), eq("IPD_ADMISSION"), eq(ADMISSION), eq(null), anyMap());
        assertThat(fake.rows).isEmpty();
        assertThat(fake.writes).isZero();
    }

    @Test
    void aWarningNeedsAReasonWritesTheRecordAccessRowAndCarriesTheToken() {
        allergyText = null;

        CheckOnly result = check("pcm", "od");

        assertThat(result.outcome()).isEqualTo("NEEDS_REASON");
        assertThat(result.check().checkToken()).hasSize(22);
        verify(patientAccess).recordOpened(TENANT, PATIENT, DOCTOR);
        assertThat(fake.writes).isZero();
    }

    @Test
    void aCleanMedicinePassesWithoutAnyWrite() {
        allergyText = "No known allergy";

        CheckOnly result = check("pcm", "od");

        assertThat(result.outcome()).isEqualTo("PASS");
        verify(events, never()).insert(any(), any(), any(), any(), any());
        verify(auditLog, never()).record(any(), any(), any(), any(), any());
        assertThat(fake.writes).isZero();
    }

    @Test
    void aClientKeyAlreadyOrderedAnswersOrderedAndAVisitingDoctorIsRefused() {
        allergyText = "No known allergy";
        MedicineOrderAnswer placed = service.order(TENANT, DOCTOR, "DOCTOR", ADMISSION, input("pcm", "od"));
        assertThat(placed.outcome()).isEqualTo("PLACED");

        assertThat(check("pcm", "od").outcome()).isEqualTo("ORDERED");

        assertThatThrownBy(() -> service.checkOnly(TENANT, DOCTOR, "VISITING_DOCTOR", ADMISSION, input("pcm", "od")))
                .isInstanceOfAny(ForbiddenException.class, AccessDeniedException.class);
    }
}
