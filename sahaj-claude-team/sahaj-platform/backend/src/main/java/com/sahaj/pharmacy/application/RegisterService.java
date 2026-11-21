package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.RegisterReadRepository.H1Row;
import com.sahaj.pharmacy.application.RegisterReadRepository.XBalance;
import com.sahaj.pharmacy.application.RegisterReadRepository.XCount;
import com.sahaj.pharmacy.application.RegisterReadRepository.XRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-9 and 80-10, the legal registers (docs/api/pharmacy-sales.yaml pharmacyH1Register, pharmacyExportH1Register,
 * pharmacyScheduleXRegister, pharmacyScheduleXSaleEntries, pharmacyExportScheduleXRegister; ADR-030 S7, S8, S11).
 *
 * <p>Access: the capability readRegisters of the store (the owner, a pharmacist member, the HOD or the current delegate; a cashier is
 * refused); the H1 download is for the owner or a pharmacist member only. The patient names the law needs are shown here and in the
 * H1 CSV only; each read writes {@code PHARMACY_REGISTER_VIEWED} and, for a registered patient on the page, a record-access entry; a
 * download writes {@code PHARMACY_REGISTER_EXPORTED}. The audit rows hold the store id, the filter and the row count, never a name.
 * The Schedule X balance register shows products and quantities only (no patient); a blind caller (rule 32) gets null quantities for
 * an audited product. The range is at most 12 months; a CSV is cut off at 50,000 rows with 409 {@code export-too-large}.
 */
@Service
public class RegisterService {

    static final int MAX_DAYS = 366;
    static final int EXPORT_CAP = 50_000;
    static final String X_NOTE = "Two-person sign-off on issue. The register is kept as per applicable state rules.";
    private static final int MAX_SIZE = 25;
    private static final int PRESCRIBER_MAX = 80;
    private static final String ENTITY_TYPE = "PHARMACY_STORE";

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final RegisterReadRepository registers;
    private final ProductRepository products;
    private final StockRepository stock;
    private final SaleSupport support;
    private final ActiveReasonService reasons;
    private final BlindCount blind;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public RegisterService(PharmacyGate gate, StoreAccessGuard guard, RegisterReadRepository registers,
                           ProductRepository products, StockRepository stock, SaleSupport support, ActiveReasonService reasons,
                           BlindCount blind, AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.registers = registers;
        this.products = products;
        this.stock = stock;
        this.support = support;
        this.reasons = reasons;
        this.blind = blind;
        this.auditLog = auditLog;
        this.time = time;
    }

    /** A date range: both ends are IST days; {@code from} is inclusive and {@code toExclusive} is the day after {@code to}. */
    record Range(LocalDate from, LocalDate to) {

        Instant fromInstant() {
            return from.atStartOfDay(PharmacyTime.IST).toInstant();
        }

        Instant toInstant() {
            return to.plusDays(1).atStartOfDay(PharmacyTime.IST).toInstant();
        }
    }

    // ---- H1 register ----

    @Transactional
    public SaleCheckViews.H1RegisterPage h1(UUID tenantId, UUID userId, String rawStoreId, String rawFrom, String rawTo,
                                            String rawProduct, String rawPrescriber, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.READ_REGISTERS);
        Range range = range(rawFrom, rawTo, time.today(), time.today(), false);
        UUID productId = PharmacyParams.optionalUuid("productId", rawProduct, "Choose a product");
        String prescriber = prescriber(rawPrescriber);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);

        long total = registers.h1Count(tenantId, storeId, range.fromInstant(), range.toInstant(), productId, prescriber);
        List<H1Row> rows = registers.h1Page(tenantId, storeId, range.fromInstant(), range.toInstant(), productId, prescriber,
                size, page * size);
        List<SaleCheckViews.H1RegisterRow> items = h1Views(tenantId, rows);
        Set<UUID> patients = new LinkedHashSet<>();
        rows.forEach(r -> {
            if (r.patientId() != null) {
                patients.add(r.patientId());
            }
        });
        patients.forEach(id -> support.recordOpened(tenantId, id, userId));
        Instant dayStart = time.dayStart();
        Instant dayEnd = time.today().plusDays(1).atStartOfDay(PharmacyTime.IST).toInstant();
        int entriesToday = registers.h1Entries(tenantId, storeId, dayStart, dayEnd, false);
        int missingToday = registers.h1Entries(tenantId, storeId, dayStart, dayEnd, true);
        audit("PHARMACY_REGISTER_VIEWED", storeId, "H1", range, productId, rows.size());
        return new SaleCheckViews.H1RegisterPage(items, total, page, size, entriesToday, missingToday);
    }

    @Transactional
    public ReportViews.CsvFile exportH1(UUID tenantId, UUID userId, String rawStoreId, String rawFrom, String rawTo,
                                        String rawProduct, String rawPrescriber) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.READ_REGISTERS);
        if (!caller.isOwner() && !caller.isPharmacist()) {
            throw guard.refuse(caller, storeId, StoreCapability.READ_REGISTERS);
        }
        Range range = range(rawFrom, rawTo, null, null, true);
        UUID productId = PharmacyParams.optionalUuid("productId", rawProduct, "Choose a product");
        String prescriber = prescriber(rawPrescriber);
        List<H1Row> rows = registers.h1All(tenantId, storeId, range.fromInstant(), range.toInstant(), productId, prescriber,
                EXPORT_CAP + 1);
        if (rows.size() > EXPORT_CAP) {
            throw PharmacyProblemException.exportTooLarge();
        }
        List<List<String>> lines = new ArrayList<>();
        for (SaleCheckViews.H1RegisterRow r : h1Views(tenantId, rows)) {
            lines.add(List.of(ReportCsv.time(r.at()), ReportCsv.neutralise(r.billNo()), r.kind(),
                    ReportCsv.neutralise(r.patientName()), r.patientAge() == null ? "" : r.patientAge().toString(),
                    ReportCsv.neutralise(r.patientAddress()), ReportCsv.neutralise(r.prescriberName()),
                    ReportCsv.neutralise(r.prescriberRegNo()), r.prescriptionDate() == null ? "" : r.prescriptionDate().toString(),
                    ReportCsv.neutralise(r.drug().name()), r.prescribedDrug() == null ? "" : ReportCsv.neutralise(
                            r.prescribedDrug().name()), ReportCsv.neutralise(r.batch().batchNo()), Integer.toString(r.qty()),
                    r.rxCopy() == null || r.rxCopy().source() == null ? "" : r.rxCopy().source(),
                    ReportCsv.neutralise(r.pharmacist().name()), r.override() == null ? "" : ReportCsv.neutralise(
                            r.override().label())));
        }
        audit("PHARMACY_REGISTER_EXPORTED", storeId, "H1", range, productId, rows.size());
        return new ReportViews.CsvFile("sahaj-h1-register-" + range.from() + "-" + range.to() + ".csv", ReportCsv.write(
                List.of("Date and time", "Bill number", "Kind", "Patient name", "Age", "Address", "Prescriber",
                        "Registration number", "Prescription date", "Drug", "Prescribed drug", "Batch", "Quantity", "Rx copy",
                        "Pharmacist", "Override reason"), lines));
    }

    private List<SaleCheckViews.H1RegisterRow> h1Views(UUID tenantId, List<H1Row> rows) {
        Set<UUID> productIds = new LinkedHashSet<>();
        Set<UUID> people = new LinkedHashSet<>();
        rows.forEach(r -> {
            productIds.add(r.productId());
            if (r.prescribedProductId() != null) {
                productIds.add(r.prescribedProductId());
            }
            people.add(r.pharmacistMembershipId());
        });
        Map<UUID, ProductRepository.Full> found = productIds.isEmpty() ? Map.of() : products.findAll(tenantId, productIds);
        Map<UUID, TeamMember> members = support.members(tenantId, people);
        Map<String, String> labels = reasons.labels(tenantId);
        Map<UUID, StockViews.BatchRef> batches = new HashMap<>();
        List<SaleCheckViews.H1RegisterRow> out = new ArrayList<>();
        for (H1Row r : rows) {
            boolean returned = "RETURNED".equals(r.reversalReason());
            out.add(new SaleCheckViews.H1RegisterRow(r.id(), r.kind(), r.reversesRowId(), r.reversalReason(), r.occurredAt(),
                    returned && r.reversalRef() != null ? r.reversalRef() : r.billNo(), r.patientName(), r.patientAge(),
                    r.patientAddress(), r.prescriberName(), r.prescriberRegNo(), r.prescriptionDate(),
                    productRef(found, r.productId()), r.prescribedProductId() == null ? null
                            : productRef(found, r.prescribedProductId()),
                    batches.computeIfAbsent(r.batchId(), id -> batchRef(tenantId, id)), r.qty(),
                    r.rxSource() == null ? null : new SaleCheckViews.RxCopy(r.rxSource(), r.rxRecordId()),
                    support.person(r.pharmacistMembershipId(), members), r.overrideReasonKey() == null ? null
                            : new StockViews.ReasonRef(r.overrideReasonKey(), labels.getOrDefault(r.overrideReasonKey(),
                                    r.overrideReasonKey()), null)));
        }
        return out;
    }

    // ---- Schedule X ----

    /** The balance register: one row per Schedule X or NDPS product with stock or a movement (not paged). */
    @Transactional
    public SaleCheckViews.ScheduleXRegister scheduleX(UUID tenantId, UUID userId, String rawStoreId, String rawFrom,
                                                      String rawTo, String rawProduct) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.READ_REGISTERS);
        Range range = range(rawFrom, rawTo, time.today().withDayOfMonth(1), time.today(), false);
        UUID productId = PharmacyParams.optionalUuid("productId", rawProduct, "Choose a product");
        SaleCheckViews.ScheduleXRegister view = balance(caller, storeId, range, productId);
        audit("PHARMACY_REGISTER_VIEWED", storeId, "SCHEDULE_X", range, productId, view.items().size());
        return view;
    }

    @Transactional
    public ReportViews.CsvFile exportScheduleX(UUID tenantId, UUID userId, String rawStoreId, String rawFrom, String rawTo,
                                               String rawProduct) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.READ_REGISTERS);
        Range range = range(rawFrom, rawTo, null, null, true);
        UUID productId = PharmacyParams.optionalUuid("productId", rawProduct, "Choose a product");
        SaleCheckViews.ScheduleXRegister view = balance(caller, storeId, range, productId);
        if (view.items().size() > EXPORT_CAP) {
            throw PharmacyProblemException.exportTooLarge();
        }
        List<List<String>> lines = new ArrayList<>();
        for (SaleCheckViews.ScheduleXBalanceRow r : view.items()) {
            lines.add(List.of(ReportCsv.neutralise(r.product().name()), r.ndps() ? "Yes" : "No", num(r.opening()),
                    num(r.received()), num(r.issued()), num(r.book()), num(r.physical()),
                    r.physicalCountedOn() == null ? "" : r.physicalCountedOn().toString(), r.status(), num(r.difference())));
        }
        audit("PHARMACY_REGISTER_EXPORTED", storeId, "SCHEDULE_X", range, productId, view.items().size());
        return new ReportViews.CsvFile("sahaj-schedule-x-register-" + range.from() + "-" + range.to() + ".csv",
                ReportCsv.write(List.of("Product", "NDPS", "Opening", "Received", "Issued", "Book", "Physical", "Counted on",
                        "Status", "Difference"), lines));
    }

    private SaleCheckViews.ScheduleXRegister balance(PharmacyCaller caller, UUID storeId, Range range, UUID productId) {
        UUID tenantId = caller.tenantId();
        List<XBalance> balances = registers.xBalances(tenantId, storeId, range.fromInstant(), range.toInstant(), productId);
        Set<UUID> ids = new LinkedHashSet<>();
        balances.forEach(b -> ids.add(b.productId()));
        Map<UUID, ProductRepository.Full> found = ids.isEmpty() ? Map.of() : products.findAll(tenantId, ids);
        Map<UUID, XCount> counts = registers.latestCounts(tenantId, storeId, ids);
        BlindCount.Mask mask = blind.at(caller, storeId);
        List<SaleCheckViews.ScheduleXBalanceRow> items = new ArrayList<>();
        int toReview = 0;
        for (XBalance b : balances) {
            ProductRepository.Full full = found.get(b.productId());
            if (full == null) {
                continue;
            }
            XCount count = counts.get(b.productId());
            String status = count == null ? "NOT_COUNTED" : count.countedQty() == b.closing() ? "MATCH" : "REVIEW";
            toReview += "REVIEW".equals(status) ? 1 : 0;
            boolean hidden = mask.hides(b.productId());
            Long difference = count == null || hidden ? null : count.countedQty() - b.closing();
            items.add(new SaleCheckViews.ScheduleXBalanceRow(IndentService.productRef(full.row()), b.ndps(),
                    hidden ? null : b.opening(), hidden ? null : b.received(), hidden ? null : b.issued(),
                    hidden ? null : b.closing(), count == null || hidden ? null : count.countedQty(),
                    count == null ? null : count.countedOn(), status, difference));
        }
        return new SaleCheckViews.ScheduleXRegister(range.from(), range.to(), toReview, items, X_NOTE);
    }

    /** The register rows written by bills, cancels, sales returns, IPD issues and ward returns, newest first. */
    @Transactional
    public SaleCheckViews.ScheduleXEntryPage scheduleXSales(UUID tenantId, UUID userId, String rawStoreId, String rawFrom,
                                                            String rawTo, String rawProduct, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.READ_REGISTERS);
        Range range = range(rawFrom, rawTo, time.today().withDayOfMonth(1), time.today(), false);
        UUID productId = PharmacyParams.optionalUuid("productId", rawProduct, "Choose a product");
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        long total = registers.xCount(tenantId, storeId, range.fromInstant(), range.toInstant(), productId);
        List<XRow> rows = registers.xPage(tenantId, storeId, range.fromInstant(), range.toInstant(), productId, size,
                page * size);
        Set<UUID> productIds = new LinkedHashSet<>();
        Set<UUID> people = new LinkedHashSet<>();
        Set<UUID> patients = new LinkedHashSet<>();
        for (XRow r : rows) {
            productIds.add(r.productId());
            people.add(r.firstSigner());
            people.add(r.secondSigner());
            if (r.patientId() != null) {
                patients.add(r.patientId());
            }
        }
        Map<UUID, ProductRepository.Full> found = productIds.isEmpty() ? Map.of() : products.findAll(tenantId, productIds);
        Map<UUID, TeamMember> members = support.members(tenantId, people);
        Map<UUID, StockViews.BatchRef> batches = new HashMap<>();
        List<SaleCheckViews.ScheduleXEntry> items = new ArrayList<>();
        for (XRow r : rows) {
            String sourceType = "BILL".equals(r.sourceType()) && "CANCELLED".equals(r.reversalReason()) ? "BILL_CANCEL"
                    : r.sourceType();
            items.add(new SaleCheckViews.ScheduleXEntry(r.id(), r.kind(), r.occurredAt(), r.sourceNo(), sourceType,
                    r.patientName(), r.patientAge(), r.prescriberName(), r.prescriberRegNo(), r.prescriptionDate(),
                    productRef(found, r.productId()), batches.computeIfAbsent(r.batchId(), id -> batchRef(tenantId, id)),
                    r.qty(), support.person(r.firstSigner(), members), support.person(r.secondSigner(), members)));
        }
        patients.forEach(id -> support.recordOpened(tenantId, id, userId));
        audit("PHARMACY_REGISTER_VIEWED", storeId, "SCHEDULE_X_ENTRIES", range, productId, rows.size());
        return new SaleCheckViews.ScheduleXEntryPage(items, total, page, size);
    }

    // ---- helpers ----

    private StockViews.ProductRef productRef(Map<UUID, ProductRepository.Full> found, UUID id) {
        ProductRepository.Full full = found.get(id);
        return full == null ? null : IndentService.productRef(full.row());
    }

    private StockViews.BatchRef batchRef(UUID tenantId, UUID batchId) {
        return stock.findBatch(tenantId, batchId).map(b -> new StockViews.BatchRef(b.id(), b.batchNo(), b.expiryDate()))
                .orElse(new StockViews.BatchRef(batchId, "", null));
    }

    private static String num(Long value) {
        return value == null ? "" : value.toString();
    }

    private static String prescriber(String raw) {
        String text = SaleSupport.blank(raw);
        if (text != null && text.length() > PRESCRIBER_MAX) {
            throw new InvalidFieldsException(List.of(new FieldMessage("prescriber", "Type at most 80 characters.")));
        }
        return text;
    }

    /** Both dates, or the defaults when neither is given (an export has no default); at most 12 months. */
    static Range range(String rawFrom, String rawTo, LocalDate defaultFrom, LocalDate defaultTo, boolean required) {
        List<FieldMessage> errors = new ArrayList<>();
        LocalDate from = date("from", rawFrom, errors);
        LocalDate to = date("to", rawTo, errors);
        if (required && rawFrom == null) {
            errors.add(new FieldMessage("from", "Enter a valid date"));
        }
        if (required && rawTo == null) {
            errors.add(new FieldMessage("to", "Enter a valid date"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        LocalDate start = from != null ? from : defaultFrom;
        LocalDate end = to != null ? to : defaultTo;
        if (start.isAfter(end)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("from", "Choose a start date on or before the end date")));
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
            throw PharmacyProblemException.rangeTooLong();
        }
        return new Range(start, end);
    }

    private static LocalDate date(String name, String raw, List<FieldMessage> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException notADate) {
            errors.add(new FieldMessage(name, "Enter a valid date"));
            return null;
        }
    }

    /** Ids, the register, the range, the product filter and the row count; never a name. */
    private void audit(String action, UUID storeId, String register, Range range, UUID productId, int rows) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("storeId", storeId.toString());
        after.put("register", register);
        after.put("from", range.from().toString());
        after.put("to", range.to().toString());
        after.put("productId", productId == null ? null : productId.toString());
        after.put("rows", rows);
        auditLog.record(action, ENTITY_TYPE, storeId, null, after);
    }
}
