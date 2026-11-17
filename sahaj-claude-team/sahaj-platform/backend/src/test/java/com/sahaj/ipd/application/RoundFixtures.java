package com.sahaj.ipd.application;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.MedicineRoundRepository.ActiveOrder;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test fixtures of the Medicine round after SAH-76: an {@link ActiveOrder} no longer carries a frequency, so a test that
 * builds one with {@link #order} also registers its order timeline (one verified scheduled version 1, started long ago) in
 * a shared map keyed by the random order id, which {@link #reader} reads. Not production code.
 */
final class RoundFixtures {

    private static final Instant LONG_AGO = Instant.parse("2026-11-09T05:00:00Z");
    private static final Map<UUID, OrderTimeline> TIMELINES = new ConcurrentHashMap<>();

    private RoundFixtures() {
    }

    /** An ACTIVE order of an admitted patient, with its version 1 verified at {@code verifiedAt}. */
    static ActiveOrder order(UUID orderId, UUID admissionId, UUID patientId, String bed, String drugKey,
                             String frequencyKey, Instant verifiedAt) {
        MedicineOrderVersion v1 = new MedicineOrderVersion(orderId, 1, Kind.SCHEDULED, frequencyKey, null, null, "ORAL",
                LONG_AGO, null, null, OrderPriority.ROUTINE, null, null, null, UUID.randomUUID(), LONG_AGO, null,
                Verification.REQUIRED, verifiedAt == null ? null : UUID.randomUUID(), verifiedAt);
        TIMELINES.put(orderId, new OrderTimeline(orderId, admissionId, verifiedAt == null ? OrderStatus.PENDING
                : OrderStatus.ACTIVE, List.of(v1), List.of(), null));
        return new ActiveOrder(orderId, admissionId, patientId, bed, drugKey, "ACTIVE");
    }

    static OrderTimelineRepository timelines() {
        return (tenantId, admissionIds) -> TIMELINES;
    }

    /** Settings in force: the built defaults for every patient. */
    static NursingSettingsReader settings() {
        NursingSettingsReader reader = mock(NursingSettingsReader.class);
        when(reader.effectiveForAll(any(), anyCollection())).thenAnswer(call -> {
            Map<UUID, EffectiveNursingSettings> all = new LinkedHashMap<>();
            for (UUID id : call.<Collection<UUID>>getArgument(1)) {
                all.put(id, EffectiveNursingSettings.defaults());
            }
            return all;
        });
        when(reader.effectiveFor(any(), any())).thenReturn(EffectiveNursingSettings.defaults());
        return reader;
    }

    static MedicineRoundReader reader(MedicineRoundRepository repository, MedicineCatalogue catalogue, Clock clock) {
        return new MedicineRoundReader(repository, catalogue, clock, timelines(), settings());
    }

}
