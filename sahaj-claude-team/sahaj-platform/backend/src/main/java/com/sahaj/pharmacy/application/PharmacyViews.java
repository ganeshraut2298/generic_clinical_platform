package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.DelegateState;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.StoreAccess;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The answers of the WP-A operations, field for field as docs/api/pharmacy.yaml 0.1.0 (schemas PharmacyContext,
 * SetupSummary, UnitSettings, Store, StoreList, StaffCandidate, StaffMapping). Plain records, never entities.
 */
public final class PharmacyViews {

    private PharmacyViews() {
    }

    // ---- GET /context, PUT /context/working-store ----

    public record Context(UnitHeader unit, CallerView caller, List<String> capabilities, List<MyStore> stores,
                          UUID workingStoreId, boolean mustPickWorkingStore, FixedRules fixedRules) {
    }

    public record UnitHeader(String name, String code, IntegrationMode integrationMode) {
    }

    /** {@code setupAccess} is EDIT, VIEW or NONE. */
    public record CallerView(UUID membershipId, String name, String role, String roleLabel, String setupAccess) {
    }

    /** The store this store's indents go to (0.3.5): the ACTIVE Central Store; null for the Central Store itself. */
    public record IssuesFrom(UUID id, String name) {
    }

    public record MyStore(UUID storeId, String name, String code, StoreType type, StoreRole storeRole,
                          StoreRole effectiveStoreRole, StoreAccess access, IssuesFrom issuesFrom) {
    }

    /** FEFO and the negative-stock block are always on (OQ-78-11). */
    public record FixedRules(boolean fefo, boolean negativeStockBlocked) {

        public static final FixedRules ON = new FixedRules(true, true);
    }

    // ---- GET /setup-summary ----

    public record SetupSummary(int storeCount, int mappedStaffCount, int activeVendorCount) {
    }

    // ---- GET and PUT /unit-settings ----

    public record UnitSettings(String unitName, String unitCode, String gstin, String drugLicenceNo,
                               LocalDate drugLicenceValidTill, boolean drugLicenceExpired,
                               IntegrationMode integrationMode, boolean fefo, boolean negativeStockBlocked,
                               boolean ipdAvailable, boolean saved, long version, Instant updatedAt) {
    }

    // ---- stores ----

    public record StorePerson(UUID membershipId, String name, String roleLabel, boolean canSignIn) {
    }

    public record StoreDelegate(UUID membershipId, String name, String roleLabel, boolean canSignIn, LocalDate from,
                                LocalDate to, DelegateState state) {
    }

    /** {@code approverState} is SET or NONE; {@code status} is ACTIVE or INACTIVE; {@code lockedFields} lists TYPE, CODE, ISSUES_FROM. */
    public record Store(UUID id, String name, String code, StoreType type, UUID issuesFromStoreId,
                        String issuesFromName, boolean saleAllowed, boolean consumptionAllowed, StorePerson hod,
                        StoreDelegate delegate, String approverState, int staffCount, String status, boolean hasStock,
                        List<String> lockedFields, long version) {
    }

    public record StoreList(List<Store> items, UUID centralStoreId) {
    }

    // ---- staff ----

    public record StaffCandidate(UUID membershipId, String name, String role, String roleLabel,
                                 List<StoreRole> allowedStoreRoles, boolean alreadyMappedHere) {
    }

    public record StaffCandidates(List<StaffCandidate> items) {
    }

    public record OtherStore(UUID storeId, String name) {
    }

    /** {@code memberState} is ACTIVE or CANNOT_SIGN_IN; {@code status} is ACTIVE or REMOVED. */
    public record StaffMapping(UUID id, UUID storeId, UUID membershipId, String name, String role, String roleLabel,
                               boolean isHod, StoreRole storeRole, StoreRole effectiveStoreRole, LocalDate delegateFrom,
                               LocalDate delegateTo, DelegateState delegateState, StoreAccess access,
                               List<OtherStore> otherStores, String memberState, boolean needsReview, String status,
                               long version) {
    }

    public record StaffMappings(List<StaffMapping> items) {
    }

    // ---- WP-B: products (docs/api/pharmacy.yaml ProductRow, ProductPage, ProductDetail, DuplicateCandidate) ----

    /** An answer and whether this call created the record (201) or found it already there (200). */
    public record Outcome<T>(T view, boolean created) {
    }

    /**
     * {@code source} GLOBAL or OWN; {@code inUnit} ADOPTED, NOT_ADOPTED, WITHDRAWN, ACTIVE or INACTIVE; {@code gstRate} a
     * percent string; {@code schedule} NONE, H, H1 or X.
     */
    public record ProductRow(String source, UUID catalogueProductId, UUID productId, String code, String name,
                             String manufacturer, boolean madeInHouse, String salt, String strength, String form,
                             String purchaseUnit, String issueUnit, int conversion, String hsn, String gstRate,
                             String schedule, boolean ndps, String inUnit, boolean sample, String alias) {
    }

    public record ProductPage(List<ProductRow> items, long total, int page, int size) {
    }

    public record StoreSetting(UUID storeId, String storeName, Integer reorderLevel, String rack) {
    }

    public record PreferredVendor(UUID id, String name, boolean needsReview) {
    }

    public record ProductLocalSettings(List<StoreSetting> storeSettings, PreferredVendor preferredVendor, String alias,
                                       String barcode, boolean returnable, boolean active) {
    }

    /** The fields of {@link ProductRow} and the detail members, flat as the contract's allOf. */
    public record ProductDetail(String source, UUID catalogueProductId, UUID productId, String code, String name,
                                String manufacturer, boolean madeInHouse, String salt, String strength, String form,
                                String purchaseUnit, String issueUnit, int conversion, String hsn, String gstRate,
                                String schedule, boolean ndps, String inUnit, boolean sample, String alias,
                                List<String> barcodes, ProductLocalSettings localSettings, boolean unitsLocked,
                                boolean hasStock, long version) {
    }

    public record DuplicateCandidate(String source, UUID catalogueProductId, UUID productId, String code, String name,
                                     String manufacturer, boolean madeInHouse, String inUnit) {
    }

    public record Duplicates(List<DuplicateCandidate> candidates) {
    }

    public record ProductOptions(List<String> units, List<String> forms) {
    }

    // ---- WP-B: opening stock (ImportStatus, OpeningStockImport, RowIssue, OpeningStockLine) ----

    public record Person(UUID membershipId, String name) {
    }

    /** {@code approveLockReason} is SELF_UPLOADED or NOT_OWNER, only while the import waits for approval; {@code posted} is true once SAH-79-1 posted the import (then postedAt and postedBy are set). */
    public record OpeningStockImport(UUID id, UUID storeId, String storeName, String status, int rowCount,
                                     int receivedRows, int lineCount, long totalQuantity, long totalValuePaise,
                                     int errorCount, int warningCount, Person uploadedBy, Instant createdAt,
                                     Instant submittedAt, Person decidedBy, Instant decidedAt, String rejectReasonKey,
                                     String rejectNote, UUID replacedByImportId, boolean canApprove,
                                     String approveLockReason, boolean posted, long version, Instant postedAt,
                                     Person postedBy) {

        /** An import that is not posted. */
        public OpeningStockImport(UUID id, UUID storeId, String storeName, String status, int rowCount, int receivedRows,
                                  int lineCount, long totalQuantity, long totalValuePaise, int errorCount, int warningCount,
                                  Person uploadedBy, Instant createdAt, Instant submittedAt, Person decidedBy,
                                  Instant decidedAt, String rejectReasonKey, String rejectNote, UUID replacedByImportId,
                                  boolean canApprove, String approveLockReason, boolean posted, long version) {
            this(id, storeId, storeName, status, rowCount, receivedRows, lineCount, totalQuantity, totalValuePaise,
                    errorCount, warningCount, uploadedBy, createdAt, submittedAt, decidedBy, decidedAt, rejectReasonKey,
                    rejectNote, replacedByImportId, canApprove, approveLockReason, posted, version, null, null);
        }
    }

    public record ImportPage(List<OpeningStockImport> items, long total, int page, int size) {
    }

    public record RowIssue(int rowNo, String field, String code, String severity, String message) {
    }

    public record LinesResult(int receivedRows, int rowCount, List<RowIssue> issues) {
    }

    public record OpeningStockLine(int rowNo, String storeCode, String productCode, UUID productId, String productName,
                                   String batch, LocalDate expiryDate, String mrp, String purchaseRate,
                                   Integer quantity, Long valuePaise, List<RowIssue> issues) {
    }

    public record OpeningStockLinePage(List<OpeningStockLine> items, long total, int page, int size) {
    }
}
