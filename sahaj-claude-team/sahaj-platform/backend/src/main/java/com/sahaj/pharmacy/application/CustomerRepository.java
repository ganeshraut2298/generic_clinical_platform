package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.customer} and the append-only {@code pharmacy.customer_account_entry} (SAH-80-2, SAH-80-4; ADR-030
 * S5). A customer is unit-wide. The caller locks the row with {@link #findForUpdate} before it changes a balance, so two
 * counters on credit are ordered (lock order step 3). Row level security scopes every statement to the bound clinic.
 */
public interface CustomerRepository {

    record Row(UUID id, UUID unitId, String kind, UUID patientId, String name, String phone, String allergyNote,
               long creditLimitPaise, long duePaise, long creditNoteBalancePaise, String status, Instant createdAt,
               long version) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    record Slice(List<Row> items, long total) {
    }

    record NewCustomer(UUID id, UUID unitId, String kind, UUID patientId, String name, String phone, String allergyNote,
                       long creditLimitPaise) {
    }

    /** An entry of the account as stored, with the store name and the reference (bill number or credit note number). */
    record Entry(UUID id, String kind, Instant at, UUID storeId, String storeName, String reference, String mode,
                 long amountPaise, long dueAfterPaise, long creditNoteBalanceAfterPaise, UUID actor) {
    }

    record EntrySlice(List<Entry> items, long total) {
    }

    record NewEntry(UUID id, UUID unitId, UUID customerId, UUID storeId, String kind, long amountPaise, long dueAfterPaise,
                    long creditNoteBalanceAfterPaise, UUID billId, UUID salesReturnId, String mode, UUID actor) {
    }

    Optional<Row> find(UUID tenantId, UUID id);

    /** The row locked {@code FOR UPDATE}: the first lock of every write that changes a balance. */
    Optional<Row> findForUpdate(UUID tenantId, UUID id);

    Optional<Row> findByPatient(UUID tenantId, UUID patientId);

    Map<UUID, Row> findAll(UUID tenantId, Collection<UUID> ids);

    /**
     * {@code like} is a lower-case LIKE pattern with the wildcards already escaped (empty: no filter); {@code phone} is ten digits
     * or empty; {@code patientId} may be null. {@code includeInactive} false keeps ACTIVE rows only. Newest first.
     */
    Slice search(UUID tenantId, String like, String phone, UUID patientId, boolean includeInactive, int limit, int offset);

    /** Walk-in customers with this phone, at most {@code limit}, other than {@code exceptId} (may be null). */
    List<Row> byPhone(UUID tenantId, String phone, UUID exceptId, int limit);

    void insert(UUID tenantId, NewCustomer customer, UUID actorMembershipId);

    /** Changes the editable fields and the version; false when the version is no longer the stored one. */
    boolean update(UUID tenantId, UUID id, long version, String name, String phone, String allergyNote,
                   long creditLimitPaise, UUID actorMembershipId);

    boolean switchOff(UUID tenantId, UUID id, long version, UUID actorMembershipId);

    /** Sets the due and the credit-note balance (the row is locked by the caller). The settings version is not touched. */
    void setBalances(UUID tenantId, UUID id, long duePaise, long creditNoteBalancePaise, UUID actorMembershipId);

    /** The sum of credit bills (CREDIT_BILL entries) saved since {@code since}, per customer; customers with none are absent. */
    Map<UUID, Long> creditBillsSince(UUID tenantId, Collection<UUID> customerIds, Instant since);

    void insertEntry(UUID tenantId, NewEntry entry);

    Optional<Entry> findEntry(UUID tenantId, UUID entryId);

    /** Newest first. */
    EntrySlice entries(UUID tenantId, UUID customerId, int limit, int offset);
}
