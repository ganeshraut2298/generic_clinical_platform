package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.order.ReviewOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory {@link PharmacistReviewRepository} for unit tests. The list rows are given by the test (it knows the
 * order, the patient and the dose), the open and close rules are those of the database: one item per dose, closed once.
 */
class FakePharmacistReviews implements PharmacistReviewRepository {

    final List<ReviewRow> rows = new ArrayList<>();
    final List<ListRow> listRows = new ArrayList<>();
    Instant now = Instant.parse("2026-11-11T05:30:00Z");

    @Override
    public boolean insert(UUID tenantId, UUID orderId, int orderVersion, UUID doseId) {
        if (findByDose(tenantId, doseId).isPresent()) {
            return false;
        }
        rows.add(new ReviewRow(UUID.randomUUID(), orderId, orderVersion, doseId, now, null, null, null));
        return true;
    }

    @Override
    public Optional<ReviewRow> findById(UUID tenantId, UUID reviewId) {
        return rows.stream().filter(r -> r.id().equals(reviewId)).findFirst();
    }

    @Override
    public Optional<ReviewRow> findByDose(UUID tenantId, UUID doseId) {
        return rows.stream().filter(r -> r.doseId().equals(doseId)).findFirst();
    }

    @Override
    public List<ListRow> findOpen(UUID tenantId, Cursor after, int limit) {
        List<ListRow> found = new ArrayList<>();
        List<ListRow> ordered = new ArrayList<>(listRows);
        ordered.sort(java.util.Comparator.comparing((ListRow l) -> l.review().createdAt())
                .thenComparing(l -> l.review().id()));
        for (ListRow l : ordered) {
            ReviewRow current = findById(tenantId, l.review().id()).orElse(l.review());
            boolean past = after == null || current.createdAt().isAfter(after.createdAt())
                    || (current.createdAt().equals(after.createdAt()) && current.id().compareTo(after.id()) > 0);
            if (current.open() && past && found.size() < limit) {
                found.add(new ListRow(current, l.admissionId(), l.patientId(), l.bedCode(), l.drugKey(),
                        l.orderStatus(), l.orderedBy(), l.givenAt(), l.givenBy()));
            }
        }
        return found;
    }

    @Override
    public int countOpen(UUID tenantId) {
        return (int) rows.stream().filter(ReviewRow::open).count();
    }

    @Override
    public List<UUID> findOpenIds(UUID tenantId) {
        List<UUID> ids = new ArrayList<>();
        for (ReviewRow r : rows) {
            if (r.open()) {
                ids.add(r.id());
            }
        }
        return ids;
    }

    private void close(int index, UUID closedBy, ReviewOutcome outcome) {
        ReviewRow r = rows.get(index);
        rows.set(index, new ReviewRow(r.id(), r.orderId(), r.orderVersion(), r.doseId(), r.createdAt(), now,
                closedBy, outcome));
    }

    @Override
    public boolean closeByDose(UUID tenantId, UUID doseId, UUID closedBy, ReviewOutcome outcome) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).doseId().equals(doseId) && rows.get(i).open()) {
                close(i, closedBy, outcome);
                return true;
            }
        }
        return false;
    }

    @Override
    public int closeByOrder(UUID tenantId, UUID orderId, UUID closedBy, ReviewOutcome outcome) {
        int closed = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).orderId().equals(orderId) && rows.get(i).open()) {
                close(i, closedBy, outcome);
                closed++;
            }
        }
        return closed;
    }
}
