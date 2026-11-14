package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.billing.api.CourseCreditPort;
import com.sahaj.billing.api.CourseCreditPort.Transfer;
import com.sahaj.ipd.application.AdmissionRepository.CourseLink;
import com.sahaj.opd.api.TreatmentCoursePort;
import com.sahaj.opd.api.TreatmentCoursePort.AdmittableSitting;
import com.sahaj.platform.api.ConflictException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** SAH-58 (ADR-022 D12; DEC-042 H1): an admission for a treatment course sitting. */
class AdmissionCourseLinkTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID COURSE = UUID.randomUUID();
    private static final UUID SITTING = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID TRANSFER = UUID.randomUUID();
    private static final UUID DEPOSIT = UUID.randomUUID();

    private final TreatmentCoursePort courses = mock(TreatmentCoursePort.class);
    private final CourseCreditPort credit = mock(CourseCreditPort.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private AdmissionCourseLink link;

    @BeforeEach
    void setUp() {
        link = new AdmissionCourseLink(courses, credit, admissions);
    }

    @Test
    void aPlannedUnlinkedSittingOfThePatientIsPrepared() {
        when(courses.admittableSitting(TENANT, PATIENT, SITTING))
                .thenReturn(Optional.of(new AdmittableSitting(COURSE, "TC-0412", SITTING, 3)));

        AdmissionCourseLink.Prepared prepared = link.prepare(TENANT, PATIENT, SITTING);

        assertThat(prepared.courseId()).isEqualTo(COURSE);
        assertThat(prepared.courseNo()).isEqualTo("TC-0412");
        assertThat(prepared.sittingId()).isEqualTo(SITTING);
    }

    @Test
    void anyOtherSittingIs409CourseSittingNotAvailable() {
        when(courses.admittableSitting(TENANT, PATIENT, SITTING)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> link.prepare(TENANT, PATIENT, SITTING)).isInstanceOfSatisfying(
                ConflictException.class, ex -> assertThat(ex.problemType()).isEqualTo("course-sitting-not-available"));
    }

    @Test
    void completingLinksTheAdmissionThenCarriesTheCreditThenRecordsACourseDepositWithNoMode() {
        AdmissionCourseLink.Prepared prepared = new AdmissionCourseLink.Prepared(COURSE, "TC-0412", SITTING);
        when(credit.carryToAdmission(TENANT, ACTOR, COURSE, SITTING, ADMISSION))
                .thenReturn(Optional.of(new Transfer(TRANSFER, 120_000)));
        when(admissions.insertCourseDeposit(TENANT, ADMISSION, 120_000, TRANSFER, ACTOR)).thenReturn(DEPOSIT);

        AdmissionCourseLink.Carried carried = link.complete(TENANT, ACTOR, prepared, ADMISSION);

        assertThat(carried.amountPaise()).isEqualTo(120_000);
        assertThat(carried.depositId()).isEqualTo(DEPOSIT);
        InOrder order = inOrder(courses, credit, admissions);
        order.verify(courses).linkAdmission(TENANT, ACTOR, SITTING, ADMISSION);
        order.verify(credit).carryToAdmission(TENANT, ACTOR, COURSE, SITTING, ADMISSION);
        order.verify(admissions).insertCourseDeposit(TENANT, ADMISSION, 120_000, TRANSFER, ACTOR);
    }

    @Test
    void withNoCreditToCarryNoDepositIsRecordedButTheSittingIsStillLinked() {
        AdmissionCourseLink.Prepared prepared = new AdmissionCourseLink.Prepared(COURSE, "TC-0412", SITTING);
        when(credit.carryToAdmission(TENANT, ACTOR, COURSE, SITTING, ADMISSION)).thenReturn(Optional.empty());

        AdmissionCourseLink.Carried carried = link.complete(TENANT, ACTOR, prepared, ADMISSION);

        assertThat(carried.amountPaise()).isZero();
        assertThat(carried.depositId()).isNull();
        verify(courses).linkAdmission(TENANT, ACTOR, SITTING, ADMISSION);
        verify(admissions, never()).insertCourseDeposit(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(),
                any());
    }

    @Test
    void theCourseNumberOfAnAdmissionComesFromItsCourseLink() {
        when(admissions.findCourseLink(TENANT, ADMISSION)).thenReturn(Optional.of(new CourseLink(COURSE, SITTING)));
        when(courses.courseNo(TENANT, COURSE)).thenReturn(Optional.of("TC-0412"));

        assertThat(link.courseNo(TENANT, ADMISSION)).contains("TC-0412");
        when(admissions.findCourseLink(TENANT, ADMISSION)).thenReturn(Optional.empty());
        assertThat(link.courseNo(TENANT, ADMISSION)).isEmpty();
    }
}
