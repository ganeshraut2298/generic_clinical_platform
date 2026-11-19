package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The answers of the WP-C operations, field for field as docs/api/pharmacy.yaml (Vendor, VendorPage, ApprovalRules,
 * RuleVersionPage, DocumentSeries, ReasonLists, PrintTemplate). Plain records, never entities. Percentages are decimal
 * strings without trailing zeros ("10", "2.5").
 */
public final class PharmacyMasterViews {

    private PharmacyMasterViews() {
    }

    // ---- 78-8 vendors ----

    public record StoreName(UUID storeId, String name) {
    }

    /** {@code licenceState} VALID, EXPIRED or NOT_REQUIRED; {@code status} ACTIVE, BLOCKED or INACTIVE (computed in IST). */
    public record Vendor(UUID id, String name, String gstin, Boolean licenceRequired, String licenceNo,
                         LocalDate licenceValidTill, String licenceState, String contactName, String contactPhone,
                         Integer creditDays, Integer returnDaysBeforeExpiry, String returnCreditPercent,
                         String damagedCreditPercent, String expiredCreditPercent, List<StoreName> suppliesTo,
                         Integer productsCount, String status, boolean blocked, long version) {

        /**
         * 0.3.4 (DEC-082, security M3): what a caller sees whose vendor read comes only through a Central Store user mapping.
         * Only the id, the name, the status, the blocked flag and the version; every other member is null.
         */
        public static Vendor reduced(UUID id, String name, String status, boolean blocked, long version) {
            return new Vendor(id, name, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    status, blocked, version);
        }
    }

    public record VendorPage(List<Vendor> items, long total, int page, int size) {
    }

    // ---- 78-9 approval rules ----

    public record Condition(String kind, Long amountPaise, String percent, List<String> storeTypes) {
    }

    public record Level(int level, String approver, Condition condition) {
    }

    public record Rule(String transactionType, List<Level> levels, Integer escalateAfterHours,
                       Integer urgentEscalateAfterMinutes) {
    }

    public record FixedRule(String key, String text) {
    }

    public record StoreWithoutApprover(UUID storeId, String name) {
    }

    public record CurrentDelegate(UUID storeId, String storeName, UUID membershipId, String name, LocalDate to) {
    }

    /** One preset and the transaction types whose owner level needs a limit in it (DEC-078 item 3). */
    public record PresetInfo(String preset, List<String> limitTypes) {
    }

    /** {@code versionNo} 0 means the defaults, never saved. */
    public record ApprovalRules(int versionNo, String preset, boolean reviewNeeded, Instant savedAt,
                                PharmacyViews.Person savedBy, Long highValueThresholdPaise, List<Rule> rules,
                                List<PresetInfo> presets, List<FixedRule> fixedRules, List<StoreWithoutApprover> storesWithoutApprover,
                                List<CurrentDelegate> currentDelegates) {
    }

    public record RuleVersion(int versionNo, String preset, Instant savedAt, PharmacyViews.Person savedBy) {
    }

    public record RuleVersionPage(List<RuleVersion> items, long total, int page, int size) {
    }

    // ---- 78-10 numbering, reasons, print templates ----

    public record DocumentSeries(String docType, String prefix, boolean financialYearPart, int sequenceLength,
                                 boolean restartsEachFinancialYear, String example, int renderedLength,
                                 boolean numbersIssued, boolean saved, long version) {
    }

    public record DocumentSeriesList(List<DocumentSeries> items) {
    }

    /** {@code fixed}: the reason cannot be switched off (DEC-078 item 6). */
    public record Reason(String reasonKey, String label, boolean seeded, boolean active, boolean fixed) {
    }

    public record ReasonListView(String listKey, String label, List<Reason> reasons) {
    }

    public record ReasonLists(List<ReasonListView> lists) {
    }

    /** {@code missing} lists GSTIN and DRUG_LICENCE_NO when the invoice needs unit settings that are not set. */
    public record PrintTemplate(String docType, List<String> headerLines, List<String> defaultHeaderLines,
                                String footerNote, String paperSize, List<String> fixedFields, boolean printable,
                                List<String> missing, boolean saved, long version) {
    }

    public record PrintTemplateList(List<PrintTemplate> items) {
    }
}
