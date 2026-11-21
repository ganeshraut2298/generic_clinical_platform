package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for the compliance reads and writes of SAH-80-19: the pharmacist registrations, the Schedule X licence of the unit and the
 * activity feed over {@code pharmacy.document_event}. A registration is updated in place, never deleted. The feed is read only; it
 * names ids, document numbers and reason keys and never a patient name, a note or an allergy text.
 */
public interface ComplianceRepository {

    record Registration(UUID id, UUID unitId, UUID membershipId, String registrationNo, LocalDate validTill, long version) {
    }

    record ScheduleXLicence(String number, LocalDate validTill) {
    }

    /** One feed row before the sentence is built: the document number, the discount percent and the quantities it needs. */
    record ActivityEvent(Instant at, String type, String docType, String docNo, BigDecimal percent, String event, UUID actor,
                         String actingAs, UUID storeId, String reasonKey, Integer qtyBefore, Integer qtyAfter) {
    }

    record ActivitySlice(List<ActivityEvent> items, long total) {
    }

    List<Registration> registrations(UUID tenantId);

    Optional<Registration> registration(UUID tenantId, UUID id);

    Optional<Registration> registrationOfMember(UUID tenantId, UUID membershipId);

    void insertRegistration(UUID tenantId, Registration registration, UUID actor);

    /** False when the version is not the stored one. */
    boolean updateRegistration(UUID tenantId, UUID id, long version, String registrationNo, LocalDate validTill, UUID actor);

    Optional<ScheduleXLicence> scheduleXLicence(UUID tenantId);

    /** Newest first. {@code type} (one of the ActivityType values) and {@code membershipId} are optional filters. */
    ActivitySlice activity(UUID tenantId, Collection<UUID> storeIds, Instant from, Instant to, UUID membershipId, String type,
                           int limit, int offset);
}
