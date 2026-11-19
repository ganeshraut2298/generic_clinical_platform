package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The request bodies of the WP-C operations (docs/api/pharmacy.yaml, all closed with {@code additionalProperties: false}:
 * {@code PharmacyStrictJsonModule} refuses an unknown field). Fields are nullable so the services report every missing or
 * wrong field together (400 validation). Whole numbers are read as decimals so that 2.5 is refused instead of being cut to
 * 2 (see {@link PharmacyParams#wholeNumber}). A field the contract requires but allows to be null is a {@link Sent}: leaving
 * it out is 400, it is never read as null.
 */
public final class PharmacyMasterInputs {

    private PharmacyMasterInputs() {
    }

    // ---- 78-8 vendors ----

    /**
     * POST /vendors. {@code returnCreditPercent} is a decimal string such as "100" or "2.5". {@code damagedCreditPercent}
     * and {@code expiredCreditPercent} (0.2.1) are optional: absent or null both mean "no level" and are validated like it.
     */
    public record VendorCreate(UUID clientKey, String name, Sent<String> gstin, Boolean licenceRequired,
                               Sent<String> licenceNo, Sent<LocalDate> licenceValidTill, Sent<String> contactName,
                               Sent<String> contactPhone, BigDecimal creditDays, Sent<BigDecimal> returnDaysBeforeExpiry,
                               Sent<String> returnCreditPercent, Sent<String> damagedCreditPercent,
                               Sent<String> expiredCreditPercent, List<UUID> suppliesToStoreIds) {
    }

    /** PUT /vendors/{vendorId}: the same fields without the client key, with the version read. */
    public record VendorUpdate(Long version, String name, Sent<String> gstin, Boolean licenceRequired,
                               Sent<String> licenceNo, Sent<LocalDate> licenceValidTill, Sent<String> contactName,
                               Sent<String> contactPhone, BigDecimal creditDays, Sent<BigDecimal> returnDaysBeforeExpiry,
                               Sent<String> returnCreditPercent, Sent<String> damagedCreditPercent,
                               Sent<String> expiredCreditPercent, List<UUID> suppliesToStoreIds) {
    }

    // ---- 78-9 approval rules ----

    public record ConditionIn(ConditionKind kind, BigDecimal amountPaise, String percent, List<StoreType> storeTypes) {
    }

    public record LevelIn(BigDecimal level, ApproverKind approver, ConditionIn condition) {
    }

    public record RuleIn(TransactionType transactionType, List<LevelIn> levels, Sent<BigDecimal> escalateAfterHours,
                         Sent<BigDecimal> urgentEscalateAfterMinutes) {
    }

    /** PUT /approval-rules: all nine rows and the version read. */
    public record RulesSave(Long baseVersionNo, Sent<BigDecimal> highValueThresholdPaise, List<RuleIn> rules) {
    }

    public record LimitIn(TransactionType transactionType, BigDecimal amountPaise, String percent,
                          Boolean noAmountCondition) {
    }

    /** POST /approval-rules/preset. */
    public record PresetApply(Long baseVersionNo, RulePreset preset, List<LimitIn> limits) {
    }

    // ---- 78-10 numbering, reasons, print templates ----

    /** PUT /document-series/{docType}. */
    public record SeriesSave(Long version, String prefix, Boolean financialYearPart, BigDecimal sequenceLength,
                             Boolean restartsEachFinancialYear) {
    }

    /** POST /reason-lists/{listKey}/reasons. */
    public record ReasonAdd(String label) {
    }

    /** PUT /print-templates/{docType}. {@code paperSize} is text so a wrong value gets "Choose A4 or A5". */
    public record TemplateSave(Long version, List<String> headerLines, Sent<String> footerNote, String paperSize) {
    }
}
