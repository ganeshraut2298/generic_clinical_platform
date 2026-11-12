package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** SAH-43-2, ADR-019 D10: the only writer of the ranking counts. */
class EmrUsageRecorderTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final Instant SIGNED = Instant.parse("2026-11-11T06:30:00Z");
    private static final String COMPLAINT_ID = "3f0c2a52-6a8e-4c1b-9d43-2b7f0d5e9a11";

    private final EmrUsageRepository repository = mock(EmrUsageRepository.class);
    private final EmrUsageRecorder recorder = new EmrUsageRecorder(repository);

    @Test
    void eachItemIsCountedOnceWithTheSignatureTime() {
        when(repository.recordUse(any(), any(), any(), any(), any())).thenReturn(1);
        Set<MasterItemRef> items = new LinkedHashSet<>();
        items.add(new MasterItemRef(EmrMaster.COMPLAINTS, COMPLAINT_ID));
        items.add(new MasterItemRef(EmrMaster.DIAGNOSES, "J06.9"));

        int counted = recorder.recordSignedVisit(TENANT, DOCTOR, SIGNED, items);

        assertThat(counted).isEqualTo(2);
        verify(repository).recordUse(TENANT, DOCTOR, EmrMaster.COMPLAINTS, COMPLAINT_ID, SIGNED);
        verify(repository).recordUse(TENANT, DOCTOR, EmrMaster.DIAGNOSES, "J06.9", SIGNED);
    }

    @Test
    void theSameItemTwiceOnOneVisitCountsOnce() {
        when(repository.recordUse(any(), any(), any(), any(), any())).thenReturn(1);
        Set<MasterItemRef> items = new LinkedHashSet<>();
        items.add(new MasterItemRef(EmrMaster.DIAGNOSES, "J06.9"));
        items.add(new MasterItemRef(EmrMaster.DIAGNOSES, "J06.9"));

        recorder.recordSignedVisit(TENANT, DOCTOR, SIGNED, items);

        verify(repository, org.mockito.Mockito.times(1)).recordUse(any(), any(), any(), any(), any());
    }

    @Test
    void anItemThatIsNotInItsMasterIsNotCounted() {
        when(repository.recordUse(any(), any(), any(), any(), any())).thenReturn(0);

        int counted = recorder.recordSignedVisit(TENANT, DOCTOR, SIGNED,
                Set.of(new MasterItemRef(EmrMaster.ADVICE, COMPLAINT_ID)));

        assertThat(counted).isZero();
    }

    @Test
    void itemsAreTakenInAFixedOrderSoTwoSignaturesCannotDeadlock() {
        when(repository.recordUse(any(), any(), any(), any(), any())).thenReturn(1);
        Set<MasterItemRef> items = new LinkedHashSet<>();
        items.add(new MasterItemRef(EmrMaster.MEDICINES, "b"));
        items.add(new MasterItemRef(EmrMaster.COMPLAINTS, "z"));
        items.add(new MasterItemRef(EmrMaster.COMPLAINTS, "a"));
        items.add(new MasterItemRef(EmrMaster.ADVICE, "m"));

        recorder.recordSignedVisit(TENANT, DOCTOR, SIGNED, items);

        InOrder order = inOrder(repository);
        order.verify(repository).recordUse(TENANT, DOCTOR, EmrMaster.ADVICE, "m", SIGNED);
        order.verify(repository).recordUse(TENANT, DOCTOR, EmrMaster.COMPLAINTS, "a", SIGNED);
        order.verify(repository).recordUse(TENANT, DOCTOR, EmrMaster.COMPLAINTS, "z", SIGNED);
        order.verify(repository).recordUse(TENANT, DOCTOR, EmrMaster.MEDICINES, "b", SIGNED);
    }

    @Test
    void anEmptySetWritesNothing() {
        assertThat(recorder.recordSignedVisit(TENANT, DOCTOR, SIGNED, Set.of())).isZero();
        verify(repository, never()).recordUse(any(), any(), any(), any(), any());
    }

    @Test
    void itRunsOnlyInsideTheSignTransaction() throws NoSuchMethodException {
        Method method = EmrUsageRecorder.class.getMethod("recordSignedVisit", UUID.class, UUID.class, Instant.class,
                Set.class);

        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void aReferenceCanOnlyNameAMasterItem() {
        assertThatThrownBy(() -> new MasterItemRef(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MasterItemRef(EmrMaster.ADVICE, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MasterItemRef(EmrMaster.ADVICE, "x".repeat(41)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new MasterItemRef(EmrMaster.ADVICE, "x".repeat(40)).itemKey()).hasSize(40);
    }
}
