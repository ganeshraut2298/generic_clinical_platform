package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineViews.PharmacistReviewPage;
import com.sahaj.ipd.application.PharmacistReviewRepository.ListRow;
import com.sahaj.ipd.application.PharmacistReviewRepository.ReviewRow;
import com.sahaj.ipd.domain.order.OrderRef;
import com.sahaj.ipd.domain.order.ReviewOutcome;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-76-2 (ADR-027 D6; Q5): review after a dose given before the pharmacist check. The port opens and closes the items
 * in the dose transaction; the list shows the check run now; Mark reviewed is refused while a hard stop stands and the
 * order is not stopped; a repeat by the same user writes nothing; the audit and the event carry no clinical text.
 */
class PharmacistReviewServiceTest extends MedicineTestBase {

    private static final Instant GIVEN_AT = Instant.parse("2026-11-11T05:30:00Z");

    private PharmacistReviewService service;
    private OrderRow order;
    private UUID doseId;
    private UUID reviewId;

    @BeforeEach
    void setUp() {
        service = newReviewService();
        order = fake.seed(ADMISSION, "pcm", "PENDING", DOCTOR);
        openItem(order);
    }

    private void openItem(OrderRow forOrder) {
        doseId = UUID.randomUUID();
        service.openForDose(TENANT, ADMISSION, OrderRef.medicine(forOrder.id(), 1), doseId);
        ReviewRow row = reviewRepo.findByDose(TENANT, doseId).orElseThrow();
        reviewId = row.id();
        reviewRepo.listRows.add(new ListRow(row, ADMISSION, PATIENT, "202", forOrder.drugKey(), forOrder.status(),
                forOrder.orderedBy(), GIVEN_AT, NURSE));
    }

    private PharmacistReviewPage list() {
        return service.list(TENANT, PHARMACIST, "PHARMACIST", null, null);
    }

    // ---- the port ----

    @Test
    void openingAnItemForADoseIsIdempotent() {
        service.openForDose(TENANT, ADMISSION, OrderRef.medicine(order.id(), 1), doseId);

        assertThat(reviewRepo.rows).hasSize(1);
        assertThat(reviewRepo.countOpen(TENANT)).isEqualTo(1);
    }

    @Test
    void anUndoneDoseClosesItsItemWithOutcomeDoseUndone() {
        service.closeForDose(TENANT, doseId, ReviewOutcome.DOSE_UNDONE, NURSE);

        ReviewRow row = reviewRepo.findByDose(TENANT, doseId).orElseThrow();
        assertThat(row.outcome()).isEqualTo(ReviewOutcome.DOSE_UNDONE);
        assertThat(row.closedBy()).isEqualTo(NURSE);
        assertThat(reviewRepo.countOpen(TENANT)).isZero();
    }

    @Test
    void closingADoseWithNoItemDoesNothing() {
        service.closeForDose(TENANT, UUID.randomUUID(), ReviewOutcome.DOSE_UNDONE, NURSE);

        assertThat(reviewRepo.countOpen(TENANT)).isEqualTo(1);
    }

    // ---- the list ----

    @Test
    void theListShowsTheGivenLineTheDetailsAndTheCheckRunNow() {
        PharmacistReviewPage page = list();

        assertThat(page.open()).isEqualTo(1);
        assertThat(page.nextCursor()).isNull();
        var row = page.items().get(0);
        assertThat(row.id()).isEqualTo(reviewId);
        assertThat(row.drugName()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(row.givenAt()).isEqualTo(GIVEN_AT);
        assertThat(row.givenByName()).isEqualTo("Team member");
        assertThat(row.orderVersion()).isEqualTo(1);
        assertThat(row.orderStatus()).isEqualTo("PENDING");
        assertThat(row.patient().name()).isEqualTo("Test Patient");
        assertThat(row.details().kind()).isEqualTo("SCHEDULED");
        assertThat(row.check().hardStops()).isEmpty();
        assertThat(row.canMarkReviewed()).isTrue();
        assertThat(row.canQuery()).isTrue();
        verify(patientAccess).recordOpened(TENANT, PATIENT, PHARMACIST);
    }

    @Test
    void aHardStopMakesMarkReviewedUnavailableUntilTheOrderIsStopped() {
        allergyText = "Penicillin allergy";
        OrderRow amox = fake.seed(ADMISSION, "amox", "PENDING", DOCTOR);
        openItem(amox);

        PharmacistReviewPage page = list();

        var row = page.items().stream().filter(r -> r.drugKey().equals("amox")).findFirst().orElseThrow();
        assertThat(row.check().hardStops()).isNotEmpty();
        assertThat(row.canMarkReviewed()).isFalse();

        fake.markStopped(TENANT, amox.id(), DOCTOR, "Allergy");
        reviewRepo.listRows.removeIf(l -> l.review().id().equals(row.id()));
        reviewRepo.listRows.add(new ListRow(reviewRepo.findById(TENANT, row.id()).orElseThrow(), ADMISSION, PATIENT,
                "202", "amox", "STOPPED", DOCTOR, GIVEN_AT, NURSE));
        var stopped = list().items().stream().filter(r -> r.drugKey().equals("amox")).findFirst().orElseThrow();
        assertThat(stopped.canMarkReviewed()).isTrue();
        assertThat(stopped.canQuery()).isFalse();
    }

    @Test
    void theOrdererSeesNoQueryButtonOnHerOwnOrder() {
        memberRoles.put(DOCTOR, "OWNER");

        PharmacistReviewPage page = service.list(TENANT, DOCTOR, "OWNER", null, null);

        assertThat(page.items().get(0).canQuery()).isFalse();
    }

    @Test
    void theDoctorWhoChangedTheOrderSeesNoQueryButtonButMarkReviewedStays() {
        memberRoles.put(OTHER_DOCTOR, "OWNER");
        appendVersionBy(order, OTHER_DOCTOR);

        var row = service.list(TENANT, OTHER_DOCTOR, "OWNER", null, null).items().get(0);

        assertThat(row.canQuery()).isFalse();
        assertThat(row.canMarkReviewed()).isTrue();
        assertThat(list().items().get(0).canQuery()).isTrue();
    }

    @Test
    void theListIsPagedByTheKeyset() {
        OrderRow second = fake.seed(ADMISSION, "met", "PENDING", DOCTOR);
        reviewRepo.now = GIVEN_AT.plusSeconds(60);
        openItem(second);

        PharmacistReviewPage first = service.list(TENANT, PHARMACIST, "PHARMACIST", "1", null);
        PharmacistReviewPage next = service.list(TENANT, PHARMACIST, "PHARMACIST", "1", first.nextCursor());

        assertThat(first.items()).hasSize(1);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(next.items()).hasSize(1);
        assertThat(next.items().get(0).orderId()).isEqualTo(second.id());
        assertThat(next.nextCursor()).isNull();
        assertThat(first.open()).isEqualTo(2);
    }

    @Test
    void aBadLimitOrCursorIs400() {
        assertThatThrownBy(() -> service.list(TENANT, PHARMACIST, "PHARMACIST", "0", null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.list(TENANT, PHARMACIST, "PHARMACIST", null, "nope"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void onlyThePharmacistAndTheOwnerMayRead() {
        for (String role : new String[] {"DOCTOR", "NURSE", "FRONT_DESK"}) {
            assertThatThrownBy(() -> service.list(TENANT, DOCTOR, role, null, null)).isInstanceOf(ForbiddenException.class)
                    .hasMessage("Only the pharmacist or the clinic owner can check orders.");
        }
        memberRoles.put(PHARMACIST, "NURSE");
        assertThatThrownBy(() -> list()).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void theListIsAllowedInReadOnlyIpd() {
        ipdState("readonly");

        assertThat(list().items()).hasSize(1);
    }

    // ---- mark reviewed ----

    @Test
    void markReviewedClosesTheItemAndAnswersTheListNow() {
        PharmacistReviewPage page = service.markReviewed(TENANT, PHARMACIST, "PHARMACIST", reviewId);

        ReviewRow row = reviewRepo.findById(TENANT, reviewId).orElseThrow();
        assertThat(row.outcome()).isEqualTo(ReviewOutcome.REVIEWED);
        assertThat(row.closedBy()).isEqualTo(PHARMACIST);
        assertThat(page.items()).isEmpty();
        assertThat(page.open()).isZero();
        verify(events).insert(TENANT, ADMISSION, ClinicalEventRepository.KIND_VERIFY,
                "Reviewed the dose of Tab. Paracetamol 650 mg given before the pharmacist check", PHARMACIST);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_REVIEW_CLOSED"), anyString(), eq(reviewId), any(), after.capture());
        assertThat(after.getValue()).containsEntry("reviewId", reviewId).containsEntry("doseId", doseId)
                .containsEntry("orderVersion", 1).containsEntry("outcome", "REVIEWED").containsKey("device");
    }

    @Test
    void aRepeatBySameUserAnswers200AndWritesNothing() {
        service.markReviewed(TENANT, PHARMACIST, "PHARMACIST", reviewId);
        org.mockito.Mockito.clearInvocations(events, auditLog);

        PharmacistReviewPage again = service.markReviewed(TENANT, PHARMACIST, "PHARMACIST", reviewId);

        assertThat(again.items()).isEmpty();
        verifyNoInteractions(events, auditLog);
    }

    @Test
    void anItemClosedByAnotherPharmacistOrByAnUndoIs409() {
        service.markReviewed(TENANT, PHARMACIST, "PHARMACIST", reviewId);
        assertThatThrownBy(() -> service.markReviewed(TENANT, OTHER_PHARMACIST, "PHARMACIST", reviewId))
                .isInstanceOf(ConflictException.class).hasMessage("This dose was already reviewed.");

        OrderRow other = fake.seed(ADMISSION, "met", "PENDING", DOCTOR);
        openItem(other);
        service.closeForDose(TENANT, doseId, ReviewOutcome.DOSE_UNDONE, NURSE);
        UUID undone = reviewId;
        assertThatThrownBy(() -> service.markReviewed(TENANT, PHARMACIST, "PHARMACIST", undone))
                .isInstanceOf(ConflictException.class).hasMessage("The dose was undone.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-state"));
    }

    @Test
    void aHardStopWhileTheOrderIsNotStoppedIs409OrderBlocked() {
        allergyText = "Penicillin allergy";
        OrderRow amox = fake.seed(ADMISSION, "amox", "PENDING", DOCTOR);
        openItem(amox);
        UUID blocked = reviewId;

        assertThatThrownBy(() -> service.markReviewed(TENANT, PHARMACIST, "PHARMACIST", blocked))
                .isInstanceOf(ConflictException.class).hasMessage("Hard stop on this order. Query the doctor.")
                .satisfies(e -> assertThat(((ConflictException) e).problemType()).isEqualTo("order-blocked"));

        assertThat(reviewRepo.findById(TENANT, blocked).orElseThrow().open()).isTrue();
    }

    @Test
    void aHardStopOnAStoppedOrderCanBeMarkedReviewed() {
        allergyText = "Penicillin allergy";
        OrderRow amox = fake.seed(ADMISSION, "amox", "PENDING", DOCTOR);
        openItem(amox);
        fake.markStopped(TENANT, amox.id(), DOCTOR, "Allergy");

        service.markReviewed(TENANT, PHARMACIST, "PHARMACIST", reviewId);

        assertThat(reviewRepo.findById(TENANT, reviewId).orElseThrow().outcome()).isEqualTo(ReviewOutcome.REVIEWED);
    }

    @Test
    void markReviewedIsAllowedInReadOnlyAndOnADischargedAdmission() {
        ipdState("readonly");
        admission = admissionRow("DISCHARGED");

        service.markReviewed(TENANT, PHARMACIST, "PHARMACIST", reviewId);

        assertThat(reviewRepo.countOpen(TENANT)).isZero();
    }

    @Test
    void anItemOfAnotherClinicIs404AndOnlyThePharmacistAndOwnerMayClose() {
        assertThatThrownBy(() -> service.markReviewed(TENANT, PHARMACIST, "PHARMACIST", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.markReviewed(TENANT, DOCTOR, "DOCTOR", reviewId))
                .isInstanceOf(ForbiddenException.class);

        assertThat(reviewRepo.countOpen(TENANT)).isEqualTo(1);
    }
}
