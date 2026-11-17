package com.sahaj.ipd.domain;

import com.sahaj.ipd.domain.MedicineRound.RoundOrder;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Test builders of orders and their timelines for the slot rule (SAH-76): one verified version 1 of a built scheduled
 * order, as every pre-SAH-76 order reads. Not production code.
 */
public final class RoundOrders {

    /** When version 1 starts and was created: before every slot of the tests. */
    public static final Instant LONG_AGO = Instant.parse("2026-11-09T05:00:00Z");
    public static final UUID DOCTOR = UUID.fromString("d0c70000-0000-4000-8000-000000000001");

    private RoundOrders() {
    }

    /** A scheduled version 1 (route Oral, no dose), verified at {@code verifiedAt} (null: waiting for the pharmacist). */
    public static MedicineOrderVersion version(UUID orderId, Frequency frequency, Instant verifiedAt) {
        return new MedicineOrderVersion(orderId, 1, Kind.SCHEDULED, frequency.key(), null, null, "ORAL", LONG_AGO, null,
                null, OrderPriority.ROUTINE, null, null, null, DOCTOR, LONG_AGO, null, Verification.REQUIRED,
                verifiedAt == null ? null : DOCTOR, verifiedAt);
    }

    /** The timeline of a built ACTIVE order (or PENDING when {@code verifiedAt} is null). */
    public static OrderTimeline timeline(UUID orderId, UUID admissionId, Frequency frequency, Instant verifiedAt) {
        return new OrderTimeline(orderId, admissionId, verifiedAt == null ? OrderStatus.PENDING : OrderStatus.ACTIVE,
                List.of(version(orderId, frequency, verifiedAt)), List.of(), null);
    }

    /** A round order as the tests of SAH-35 built it: ACTIVE, one verified version, clinic defaults. */
    public static RoundOrder of(UUID orderId, UUID admissionId, UUID patientId, String bed, String drug,
                                String highAlert, Frequency frequency, Instant verifiedAt) {
        return new RoundOrder(orderId, admissionId, patientId, bed, drug, highAlert, OrderStatus.ACTIVE,
                timeline(orderId, admissionId, frequency, verifiedAt), EffectiveNursingSettings.defaults());
    }
}
