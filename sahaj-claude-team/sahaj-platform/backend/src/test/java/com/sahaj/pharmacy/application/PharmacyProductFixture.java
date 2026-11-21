package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.OpeningStockRepository.ImportRow;
import com.sahaj.pharmacy.application.PharmacyViews.DuplicateCandidate;
import com.sahaj.pharmacy.application.ProductRepository.CatalogueRef;
import com.sahaj.pharmacy.application.ProductRepository.ClientKeyHit;
import com.sahaj.pharmacy.application.ProductRepository.Full;
import com.sahaj.pharmacy.application.ProductRepository.NewOwn;
import com.sahaj.pharmacy.application.ProductRepository.OwnFields;
import com.sahaj.pharmacy.application.ProductRepository.ProductRef;
import com.sahaj.pharmacy.application.ProductRepository.Row;
import com.sahaj.pharmacy.application.ProductRepository.Slice;
import com.sahaj.pharmacy.application.ProductRepository.StoreSettingRow;
import com.sahaj.pharmacy.application.ProductRepository.VendorRef;
import com.sahaj.pharmacy.domain.ImportStatus;
import com.sahaj.pharmacy.domain.OpeningStockRules;
import com.sahaj.pharmacy.domain.ProductKeys;
import com.sahaj.pharmacy.domain.RejectReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * In-memory stand-ins for the product and opening-stock repositories (work package B), wired into the real services on top
 * of {@link PharmacyFixture}. Like the base fixture they keep rows per tenant but not the database constraints: the services
 * own the rules, and the constraints are tested against PostgreSQL by QA. The fake import repository records the order of the
 * writes so the write-order rule of the design note can be asserted.
 */
final class PharmacyProductFixture {

    final PharmacyFixture f = new PharmacyFixture();

    // ---- the catalogue (global) and the unit's products ----

    static final class Cat {
        UUID id = UUID.randomUUID();
        String code;
        String name;
        String salt;
        String strength;
        String form;
        String manufacturer = "Sample Labs (fake)";
        BigDecimal gst = new BigDecimal("12.00");
        String schedule = "H";
        String purchaseUnit = "Box of 10";
        String issueUnit = "vial";
        int conversion = 10;
        boolean active = true;
        List<String> barcodes = new ArrayList<>();
    }

    static final class P {
        UUID id = UUID.randomUUID();
        UUID tenant;
        UUID unitId;
        boolean own;
        UUID catalogueId;
        String code;
        OwnFields fields;
        UUID clientKey;
        String alias;
        boolean returnable = true;
        UUID vendorId;
        boolean hasStock;
        boolean active = true;
        UUID createdBy;
        long version;
        String barcode() {
            return fields == null ? localBarcode : fields.barcode();
        }
        String localBarcode;
    }

    record Vendor(UUID id, String name, boolean active, boolean licenceRequired, LocalDate till) {
    }

    final Map<UUID, Cat> catalogue = new LinkedHashMap<>();
    final Map<UUID, P> productRows = new LinkedHashMap<>();
    final Map<UUID, Vendor> vendors = new HashMap<>();
    final Map<String, StoreSettingRow> settings = new HashMap<>();
    final List<String> productWrites = new ArrayList<>();
    int ownNumber = 0;

    Cat catalogueProduct(String code, String name, String salt, String strength, String form) {
        Cat c = new Cat();
        c.code = code;
        c.name = name;
        c.salt = salt;
        c.strength = strength;
        c.form = form;
        catalogue.put(c.id, c);
        return c;
    }

    String catDup(Cat c) {
        return ProductKeys.dupKey(c.salt, c.strength, c.form);
    }

    private Row rowOf(P p) {
        if (!p.own) {
            Cat c = catalogue.get(p.catalogueId);
            String in = !c.active ? "WITHDRAWN" : p.active ? "ADOPTED" : "INACTIVE";
            return new Row("GLOBAL", c.id, p.id, c.code, c.name, c.manufacturer, false, c.salt, c.strength, c.form,
                    c.purchaseUnit, c.issueUnit, c.conversion, "3004", c.gst, c.schedule, false, in, true, p.alias);
        }
        OwnFields o = p.fields;
        return new Row("OWN", null, p.id, p.code, o.name(), o.manufacturer(), o.madeInHouse(), o.salt(), o.strength(),
                o.form(), o.purchaseUnit(), o.issueUnit(), o.conversion(), o.hsn(), o.gstRate(), o.schedule(), false,
                p.active ? "ACTIVE" : "INACTIVE", false, p.alias);
    }

    private Full fullOf(P p) {
        return new Full(rowOf(p), p.own, p.active, p.barcode(), p.returnable, p.vendorId, p.hasStock, p.version,
                p.createdBy);
    }

    private Optional<P> mine(UUID tenantId, UUID id) {
        return Optional.ofNullable(productRows.get(id)).filter(p -> p.tenant.equals(tenantId));
    }

    final ProductRepository productRepository = new ProductRepository() {
        @Override
        public boolean onOpenImport(UUID tenantId, UUID productId) {
            f.locks.add("open-import-check");
            return lineRows.stream().anyMatch(l -> productId.equals(l.in.productId()) && openImport(l.importId));
        }

        @Override
        public Slice<Row> search(UUID tenantId, String like, String exact, String source, int limit, int offset) {
            List<Row> all = new ArrayList<>();
            if (!"OWN".equals(source)) {
                for (Cat c : catalogue.values()) {
                    P adopted = productRows.values().stream()
                            .filter(p -> p.tenant.equals(tenantId) && c.id.equals(p.catalogueId)).findFirst().orElse(null);
                    if (!c.active && adopted == null) {
                        continue;
                    }
                    Row row = adopted != null ? rowOf(adopted) : new Row("GLOBAL", c.id, null, c.code, c.name,
                            c.manufacturer, false, c.salt, c.strength, c.form, c.purchaseUnit, c.issueUnit,
                            c.conversion, "3004", c.gst, c.schedule, false, "NOT_ADOPTED", true, null);
                    all.add(row);
                }
            }
            if (!"GLOBAL".equals(source)) {
                productRows.values().stream().filter(p -> p.tenant.equals(tenantId) && p.own)
                        .forEach(p -> all.add(rowOf(p)));
            }
            String needle = like.isEmpty() ? "" : like.substring(1, like.length() - 1).replace("\\", "");
            List<Row> hits = all.stream().filter(r -> needle.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(needle)
                    || r.salt().toLowerCase(Locale.ROOT).contains(needle)
                    || r.code().toLowerCase(Locale.ROOT).contains(needle)
                    || (r.alias() != null && r.alias().toLowerCase(Locale.ROOT).contains(needle)))
                    .sorted(Comparator.comparing((Row r) -> exact.isEmpty() || !r.code().equalsIgnoreCase(exact) ? 1 : 0)
                            .thenComparing(r -> r.name().toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
            int from = Math.min(offset, hits.size());
            return new Slice<>(hits.subList(from, Math.min(from + limit, hits.size())), hits.size());
        }

        @Override
        public Optional<Full> find(UUID tenantId, UUID productId) {
            return mine(tenantId, productId).map(PharmacyProductFixture.this::fullOf);
        }

        @Override
        public java.util.Map<UUID, Full> findAll(UUID tenantId, java.util.Collection<UUID> productIds) {
            java.util.Map<UUID, Full> out = new java.util.HashMap<>();
            productIds.forEach(id -> find(tenantId, id).ifPresent(f -> out.put(id, f)));
            return out;
        }

        @Override
        public Optional<Full> findForUpdate(UUID tenantId, UUID productId) {
            f.locks.add("product");
            return find(tenantId, productId);
        }

        @Override
        public Optional<Full> findAdopted(UUID tenantId, UUID catalogueProductId) {
            return productRows.values().stream()
                    .filter(p -> p.tenant.equals(tenantId) && catalogueProductId.equals(p.catalogueId)).findFirst()
                    .map(PharmacyProductFixture.this::fullOf);
        }

        @Override
        public Optional<CatalogueRef> catalogue(UUID catalogueProductId) {
            return Optional.ofNullable(catalogue.get(catalogueProductId)).map(c -> new CatalogueRef(c.id, c.code, c.active));
        }

        @Override
        public List<String> catalogueBarcodes(UUID catalogueProductId) {
            return catalogue.get(catalogueProductId).barcodes;
        }

        @Override
        public Optional<ClientKeyHit> findByClientKey(UUID tenantId, UUID clientKey) {
            return productRows.values().stream().filter(p -> p.tenant.equals(tenantId) && clientKey.equals(p.clientKey))
                    .findFirst().map(p -> new ClientKeyHit(p.id, p.createdBy));
        }

        @Override
        public List<DuplicateCandidate> duplicatesInCatalogue(UUID tenantId, String dupKey, int limit) {
            return catalogue.values().stream().filter(c -> c.active && catDup(c).equals(dupKey))
                    .sorted(Comparator.comparing(c -> c.code)).limit(limit).map(c -> {
                        boolean adopted = productRows.values().stream()
                                .anyMatch(p -> p.tenant.equals(tenantId) && c.id.equals(p.catalogueId));
                        return new DuplicateCandidate("GLOBAL", c.id, null, c.code, c.name, c.manufacturer, false,
                                adopted ? "ADOPTED" : "NOT_ADOPTED");
                    }).toList();
        }

        @Override
        public List<DuplicateCandidate> duplicatesOwn(UUID tenantId, String dupKey, UUID exceptProductId, int limit) {
            return productRows.values().stream().filter(p -> p.tenant.equals(tenantId) && p.own && p.active
                    && p.fields.dupKey().equals(dupKey) && !p.id.equals(exceptProductId))
                    .sorted(Comparator.comparing(p -> p.code)).limit(limit)
                    .map(p -> new DuplicateCandidate("OWN", null, p.id, p.code, p.fields.name(), p.fields.manufacturer(),
                            p.fields.madeInHouse(), "ACTIVE")).toList();
        }

        @Override
        public Optional<String> barcodeOwner(UUID tenantId, String barcode, UUID exceptProductId) {
            return productRows.values().stream().filter(p -> p.tenant.equals(tenantId) && p.active
                    && p.barcode() != null && p.barcode().equalsIgnoreCase(barcode) && !p.id.equals(exceptProductId))
                    .map(p -> rowOf(p).name()).findFirst();
        }

        @Override
        public List<StoreSettingRow> storeSettings(UUID tenantId, UUID productId) {
            return settings.entrySet().stream().filter(e -> e.getKey().startsWith(productId + "/"))
                    .map(Map.Entry::getValue).toList();
        }

        @Override
        public Optional<VendorRef> vendor(UUID tenantId, UUID vendorId) {
            return Optional.ofNullable(vendors.get(vendorId))
                    .map(v -> new VendorRef(v.id(), v.name(), v.active(), v.licenceRequired(), v.till()));
        }

        @Override
        public List<String> usedUnits(UUID tenantId) {
            return productRows.values().stream().filter(p -> p.tenant.equals(tenantId) && p.own)
                    .flatMap(p -> java.util.stream.Stream.of(p.fields.purchaseUnit(), p.fields.issueUnit())).toList();
        }

        @Override
        public List<String> usedForms(UUID tenantId) {
            return productRows.values().stream().filter(p -> p.tenant.equals(tenantId) && p.own)
                    .map(p -> p.fields.form()).toList();
        }

        @Override
        public Map<String, ProductRef> activeByCodesForShare(UUID tenantId, Collection<String> lowerCodes) {
            f.locks.add("product-share");
            Map<String, ProductRef> found = new HashMap<>();
            for (P p : productRows.values()) {
                if (p.tenant.equals(tenantId) && p.active) {
                    Row row = rowOf(p);
                    if (lowerCodes.contains(row.code().toLowerCase(Locale.ROOT))) {
                        found.put(row.code().toLowerCase(Locale.ROOT), new ProductRef(p.id, row.code(), row.name()));
                    }
                }
            }
            return found;
        }

        @Override
        public int takeOwnNumber(UUID tenantId, UUID unitId, UUID actorUserId) {
            productWrites.add("unit.takeNumber");
            return ++ownNumber;
        }

        @Override
        public UUID insertAdopted(UUID tenantId, UUID unitId, UUID catalogueProductId, UUID actorUserId) {
            productWrites.add("product.insertAdopted");
            P p = new P();
            p.tenant = tenantId;
            p.unitId = unitId;
            p.catalogueId = catalogueProductId;
            p.createdBy = actorUserId;
            productRows.put(p.id, p);
            return p.id;
        }

        @Override
        public UUID insertOwn(UUID tenantId, NewOwn product, UUID actorUserId) {
            productWrites.add("product.insertOwn");
            P p = new P();
            p.tenant = tenantId;
            p.unitId = product.unitId();
            p.own = true;
            p.code = product.code();
            p.fields = product.fields();
            p.clientKey = product.clientKey();
            p.createdBy = actorUserId;
            productRows.put(p.id, p);
            return p.id;
        }

        @Override
        public boolean updateOwn(UUID tenantId, UUID id, long version, OwnFields fields, UUID actorUserId) {
            P p = mine(tenantId, id).orElse(null);
            if (p == null || !p.own || p.version != version) {
                return false;
            }
            productWrites.add("product.updateOwn");
            p.fields = fields;
            p.version++;
            return true;
        }

        @Override
        public boolean updateLocal(UUID tenantId, UUID id, long version, String alias, String barcode, boolean returnable,
                                   UUID preferredVendorId, boolean active, UUID actorUserId) {
            P p = mine(tenantId, id).orElse(null);
            if (p == null || p.version != version) {
                return false;
            }
            productWrites.add("product.updateLocal");
            p.alias = alias;
            if (p.own) {
                p.fields = new OwnFields(p.fields.name(), p.fields.salt(), p.fields.strength(), p.fields.form(),
                        p.fields.manufacturer(), p.fields.madeInHouse(), p.fields.hsn(), p.fields.gstRate(),
                        p.fields.schedule(), p.fields.purchaseUnit(), p.fields.issueUnit(), p.fields.conversion(),
                        p.fields.dupKey(), barcode);
            } else {
                p.localBarcode = barcode;
            }
            p.returnable = returnable;
            p.vendorId = preferredVendorId;
            p.active = active;
            p.version++;
            return true;
        }

        @Override
        public void saveStoreSetting(UUID tenantId, UUID unitId, UUID productId, UUID storeId, Integer reorderLevel,
                                     String rack, UUID actorUserId) {
            productWrites.add("setting.save");
            settings.put(productId + "/" + storeId, new StoreSettingRow(storeId, reorderLevel, rack));
        }
    };

    // ---- opening stock ----

    static final class Imp {
        ImportRow row;
        UUID tenant;
        UUID unitId;
    }

    static final class Line {
        UUID id = UUID.randomUUID();
        UUID importId;
        OpeningStockRepository.LineIn in;
        int version;
    }

    record Issue(UUID lineId, int rowNo, String field, String code, String severity, boolean superseded) {
    }

    final Map<UUID, Imp> importRows = new LinkedHashMap<>();

    private boolean openImport(UUID importId) {
        ImportStatus status = importRows.get(importId).row.status();
        return status == ImportStatus.DRAFT || status == ImportStatus.WAITING_APPROVAL;
    }

    {
        f.openImportOfStore = storeId -> importRows.values().stream().anyMatch(i -> i.row.storeId().equals(storeId)
                && (i.row.status() == ImportStatus.DRAFT || i.row.status() == ImportStatus.WAITING_APPROVAL));
    }

    final List<Line> lineRows = new ArrayList<>();
    final List<Issue> issueRows = new ArrayList<>();
    /** Changed rows written per import (DEC-077, {@code line_rows_written}). */
    final java.util.Map<UUID, Integer> lineRowsWritten = new java.util.HashMap<>();
    /** The order of the writes of the import tables, for the write-order rule. */
    final List<String> importWrites = new ArrayList<>();

    private ImportRow live(Imp imp) {
        ImportRow r = imp.row;
        if (r.status() != ImportStatus.DRAFT) {
            return r;
        }
        List<Line> mine = lineRows.stream().filter(l -> l.importId.equals(r.id())).toList();
        long qty = mine.stream().filter(l -> l.in.parsed().quantity() != null).mapToLong(l -> l.in.parsed().quantity()).sum();
        long value = mine.stream().filter(l -> l.in.parsed().valuePaise() != null)
                .mapToLong(l -> l.in.parsed().valuePaise()).sum();
        long errors = mine.stream().filter(l -> hasIssue(l.id, "ERROR")).count();
        long warnings = mine.stream().filter(l -> hasIssue(l.id, "WARNING")).count();
        return new ImportRow(r.id(), r.storeId(), r.status(), r.rowCount(), r.uploadedBy(), r.clientKey(), r.createdAt(),
                r.submittedAt(), r.decidedBy(), r.decidedAt(), r.rejectReason(), r.rejectNote(), r.replacedByImportId(),
                mine.size(), mine.size(), qty, value, (int) errors, (int) warnings, r.version());
    }

    private boolean hasIssue(UUID lineId, String severity) {
        return issueRows.stream().anyMatch(i -> i.lineId().equals(lineId) && !i.superseded() && i.severity().equals(severity));
    }

    private void waitingCheck(UUID tenantId, UUID storeId, UUID exceptId) {
        long waiting = importRows.values().stream().filter(i -> i.tenant.equals(tenantId) && i.row.storeId().equals(storeId)
                && i.row.status() == ImportStatus.WAITING_APPROVAL && !i.row.id().equals(exceptId)).count();
        if (waiting > 0) {
            throw new IllegalStateException("uq_opening_stock_import_one_waiting: a second WAITING_APPROVAL row for the store");
        }
    }

    private void update(Imp imp, java.util.function.UnaryOperator<ImportRow> change) {
        imp.row = change.apply(imp.row);
    }

    final OpeningStockRepository importRepository = new OpeningStockRepository() {
        private Optional<Imp> mine(UUID tenantId, UUID storeId, UUID importId) {
            return Optional.ofNullable(importRows.get(importId))
                    .filter(i -> i.tenant.equals(tenantId) && i.row.storeId().equals(storeId));
        }

        @Override
        public Optional<ImportRow> find(UUID tenantId, UUID storeId, UUID importId) {
            return mine(tenantId, storeId, importId).map(PharmacyProductFixture.this::live);
        }

        @Override
        public void lockUnitShared(UUID tenantId) {
            f.locks.add("unit-share");
        }

        @Override
        public List<OpeningStockRepository.PostingLine> postingLines(UUID tenantId, UUID importId) {
            return List.of();
        }

        @Override
        public boolean markPosted(UUID tenantId, UUID id, UUID postedBy) {
            return false;
        }

        @Override
        public Optional<ImportRow> findForUpdate(UUID tenantId, UUID storeId, UUID importId) {
            f.locks.add("import");
            return find(tenantId, storeId, importId);
        }

        @Override
        public List<ImportRow> findByClientKey(UUID tenantId, UUID clientKey) {
            return importRows.values().stream().filter(i -> i.tenant.equals(tenantId) && i.row.clientKey().equals(clientKey))
                    .map(PharmacyProductFixture.this::live).toList();
        }

        @Override
        public Optional<ImportRow> findWithStatus(UUID tenantId, UUID storeId, ImportStatus status) {
            return importRows.values().stream().filter(i -> i.tenant.equals(tenantId) && i.row.storeId().equals(storeId)
                    && i.row.status() == status).findFirst().map(PharmacyProductFixture.this::live);
        }

        @Override
        public List<ImportRow> draftsOf(UUID tenantId, UUID storeId, UUID uploadedBy) {
            f.locks.add("drafts");
            return importRows.values().stream().filter(i -> i.tenant.equals(tenantId) && i.row.storeId().equals(storeId)
                    && i.row.uploadedBy().equals(uploadedBy) && i.row.status() == ImportStatus.DRAFT)
                    .map(PharmacyProductFixture.this::live).toList();
        }

        @Override
        public int countStarted(UUID tenantId, UUID storeId, UUID uploadedBy, Instant from, Instant to) {
            return (int) importRows.values().stream().filter(i -> i.tenant.equals(tenantId)
                    && i.row.storeId().equals(storeId) && i.row.uploadedBy().equals(uploadedBy)
                    && !i.row.createdAt().isBefore(from) && i.row.createdAt().isBefore(to)).count();
        }

        @Override
        public Slice<ImportRow> listForStore(UUID tenantId, UUID storeId, int limit, int offset) {
            List<ImportRow> all = importRows.values().stream()
                    .filter(i -> i.tenant.equals(tenantId) && i.row.storeId().equals(storeId))
                    .map(PharmacyProductFixture.this::live).sorted(Comparator.comparing(ImportRow::createdAt).reversed())
                    .toList();
            return page(all, limit, offset);
        }

        @Override
        public Slice<ImportRow> listAll(UUID tenantId, ImportStatus status, int limit, int offset) {
            List<ImportRow> all = importRows.values().stream()
                    .filter(i -> i.tenant.equals(tenantId) && (status == null || i.row.status() == status))
                    .map(PharmacyProductFixture.this::live).sorted(Comparator.comparing(ImportRow::createdAt).reversed())
                    .toList();
            return page(all, limit, offset);
        }

        private Slice<ImportRow> page(List<ImportRow> all, int limit, int offset) {
            int from = Math.min(offset, all.size());
            return new Slice<>(all.subList(from, Math.min(from + limit, all.size())), all.size());
        }

        @Override
        public UUID insert(UUID tenantId, UUID unitId, UUID storeId, int rowCount, UUID uploadedBy, UUID clientKey) {
            importWrites.add("import.insert");
            f.locks.add("insert");
            Imp imp = new Imp();
            imp.tenant = tenantId;
            imp.unitId = unitId;
            UUID id = UUID.randomUUID();
            imp.row = new ImportRow(id, storeId, ImportStatus.DRAFT, rowCount, uploadedBy, clientKey,
                    PharmacyFixture.NOW.plusSeconds(importRows.size()), null, null, null, null, null, null, 0, 0, 0, 0, 0,
                    0, 0);
            importRows.put(id, imp);
            return id;
        }

        @Override
        public boolean markReplaced(UUID tenantId, UUID id, long version, UUID replacedBy, UUID actor) {
            Imp imp = importRows.get(id);
            if (imp == null || imp.row.version() != version || (imp.row.status() != ImportStatus.DRAFT
                    && imp.row.status() != ImportStatus.WAITING_APPROVAL)) {
                return false;
            }
            importWrites.add("import.replaced");
            ImportRow r = imp.row;
            update(imp, x -> new ImportRow(r.id(), r.storeId(), ImportStatus.REPLACED, r.rowCount(), r.uploadedBy(),
                    r.clientKey(), r.createdAt(), r.submittedAt(), null, null, null, null, replacedBy, r.receivedRows(),
                    r.lineCount(), r.totalQuantity(), r.totalValuePaise(), r.errorCount(), r.warningCount(),
                    r.version() + 1));
            return true;
        }

        @Override
        public boolean markSubmitted(UUID tenantId, UUID id, long version, ImportStatus status, int lineCount,
                                     long totalQuantity, long totalValuePaise, int errorCount, int warningCount,
                                     Instant submittedAt, UUID actor) {
            Imp imp = importRows.get(id);
            if (imp == null || imp.row.version() != version || imp.row.status() != ImportStatus.DRAFT) {
                return false;
            }
            if (status == ImportStatus.WAITING_APPROVAL) {
                waitingCheck(tenantId, imp.row.storeId(), id);
            }
            importWrites.add("import.submitted:" + status);
            ImportRow r = imp.row;
            update(imp, x -> new ImportRow(r.id(), r.storeId(), status, r.rowCount(), r.uploadedBy(), r.clientKey(),
                    r.createdAt(), submittedAt, null, null, null, null, null, lineCount, lineCount, totalQuantity,
                    totalValuePaise, errorCount, warningCount, r.version() + 1));
            return true;
        }

        @Override
        public boolean markApproved(UUID tenantId, UUID id, long version, UUID decidedBy, Instant decidedAt) {
            Imp imp = importRows.get(id);
            if (imp == null || imp.row.version() != version || imp.row.status() != ImportStatus.WAITING_APPROVAL
                    || imp.row.uploadedBy().equals(decidedBy)) {
                return false;
            }
            importWrites.add("import.approved");
            ImportRow r = imp.row;
            update(imp, x -> new ImportRow(r.id(), r.storeId(), ImportStatus.APPROVED, r.rowCount(), r.uploadedBy(),
                    r.clientKey(), r.createdAt(), r.submittedAt(), decidedBy, decidedAt, null, null, null,
                    r.receivedRows(), r.lineCount(), r.totalQuantity(), r.totalValuePaise(), r.errorCount(),
                    r.warningCount(), r.version() + 1));
            return true;
        }

        @Override
        public boolean markRejected(UUID tenantId, UUID id, long version, UUID decidedBy, Instant decidedAt,
                                    RejectReason reason, String note) {
            Imp imp = importRows.get(id);
            if (imp == null || imp.row.version() != version || (imp.row.status() != ImportStatus.WAITING_APPROVAL
                    && imp.row.status() != ImportStatus.DRAFT)) {
                return false;
            }
            importWrites.add("import.rejected");
            ImportRow r = imp.row;
            update(imp, x -> new ImportRow(r.id(), r.storeId(), ImportStatus.REJECTED, r.rowCount(), r.uploadedBy(),
                    r.clientKey(), r.createdAt(), r.submittedAt(), decidedBy, decidedAt, reason, note, null,
                    r.receivedRows(), r.lineCount(), r.totalQuantity(), r.totalValuePaise(), r.errorCount(),
                    r.warningCount(), r.version() + 1));
            return true;
        }

        @Override
        public void markProductsHasStock(UUID tenantId, UUID importId, UUID actor) {
            importWrites.add("products.hasStock");
            lineRows.stream().filter(l -> l.importId.equals(importId) && l.in.productId() != null)
                    .forEach(l -> productRows.get(l.in.productId()).hasStock = true);
        }

        @Override
        public UUID upsertLine(UUID tenantId, UUID unitId, UUID importId, LineIn line, UUID actor) {
            Imp imp = importRows.get(importId);
            if (imp.row.status() != ImportStatus.DRAFT) {
                throw new IllegalStateException("opening stock lines can change only while the import is a draft");
            }
            importWrites.add("line.upsert");
            Line existing = lineRows.stream().filter(l -> l.importId.equals(importId) && l.in.rowNo() == line.rowNo())
                    .findFirst().orElse(null);
            if (existing != null) {
                existing.in = line;
                existing.version++;
                return existing.id;
            }
            Line l = new Line();
            l.importId = importId;
            l.in = line;
            lineRows.add(l);
            return l.id;
        }

        @Override
        public int lineRowsWritten(UUID tenantId, UUID importId) {
            return PharmacyProductFixture.this.lineRowsWritten.getOrDefault(importId, 0);
        }

        @Override
        public void addLineRowsWritten(UUID tenantId, UUID importId, int rows, UUID actor) {
            if (importRows.get(importId).row.status() != ImportStatus.DRAFT) {
                throw new IllegalStateException("line_rows_written is frozen once the import has left DRAFT");
            }
            if (rows < 1) {
                throw new IllegalStateException("line_rows_written can only go up");
            }
            importWrites.add("import.rowsWritten:" + rows);
            PharmacyProductFixture.this.lineRowsWritten.merge(importId, rows, Integer::sum);
        }

        @Override
        public List<LineRef> linesWithInactiveProduct(UUID tenantId, UUID importId) {
            return lineRows.stream().filter(l -> l.importId.equals(importId) && l.in.productId() != null
                    && !productRows.get(l.in.productId()).active)
                    .sorted(Comparator.comparingInt(l -> l.in.rowNo())).map(l -> new LineRef(l.id, l.in.rowNo())).toList();
        }

        @Override
        public List<StoredRow> storedRows(UUID tenantId, UUID importId, Collection<Integer> rowNos) {
            return lineRows.stream().filter(l -> l.importId.equals(importId) && rowNos.contains(l.in.rowNo()))
                    .map(l -> new StoredRow(l.id, l.in.rowNo(), l.in.raw())).toList();
        }

        @Override
        public void supersedeIssues(UUID tenantId, UUID importId, Collection<Integer> rowNos) {
            importWrites.add("issue.supersede");
            for (int i = 0; i < issueRows.size(); i++) {
                Issue x = issueRows.get(i);
                Line line = lineRows.stream().filter(l -> l.id.equals(x.lineId())).findFirst().orElseThrow();
                if (line.importId.equals(importId) && rowNos.contains(x.rowNo()) && !x.superseded()) {
                    issueRows.set(i, new Issue(x.lineId(), x.rowNo(), x.field(), x.code(), x.severity(), true));
                }
            }
        }

        @Override
        public void insertIssue(UUID tenantId, UUID unitId, UUID lineId, String field, String code, String severity,
                                UUID actor) {
            Line line = lineRows.stream().filter(l -> l.id.equals(lineId)).findFirst().orElseThrow();
            if (importRows.get(line.importId).row.status() != ImportStatus.DRAFT) {
                throw new IllegalStateException("opening stock issues can change only while the import is a draft");
            }
            importWrites.add("issue.insert");
            issueRows.add(new Issue(lineId, line.in.rowNo(), field, code, severity, false));
        }

        @Override
        public List<LineRef> repeatedBatchLines(UUID tenantId, UUID importId) {
            List<Line> mine = lineRows.stream().filter(l -> l.importId.equals(importId) && l.in.productId() != null
                    && l.in.parsed().batchNo() != null).toList();
            return mine.stream().filter(l -> mine.stream().anyMatch(o -> o != l && o.in.productId().equals(l.in.productId())
                    && o.in.parsed().batchNo().equalsIgnoreCase(l.in.parsed().batchNo())))
                    .sorted(Comparator.comparingInt(l -> l.in.rowNo())).map(l -> new LineRef(l.id, l.in.rowNo())).toList();
        }

        @Override
        public List<UUID> linesWithIssue(UUID tenantId, UUID importId, String code) {
            return issueRows.stream().filter(i -> !i.superseded() && i.code().equals(code)).map(Issue::lineId).toList();
        }

        @Override
        public Slice<LineRow> lines(UUID tenantId, UUID importId, boolean withIssues, int limit, int offset) {
            List<LineRow> all = lineRows.stream().filter(l -> l.importId.equals(importId))
                    .filter(l -> !withIssues || issueRows.stream().anyMatch(i -> i.lineId().equals(l.id) && !i.superseded()))
                    .sorted(Comparator.comparingInt(l -> l.in.rowNo())).map(l -> {
                        OpeningStockRules.Raw raw = l.in.raw();
                        String name = l.in.productId() == null ? null : rowOf(productRows.get(l.in.productId())).name();
                        return new LineRow(l.id, l.in.rowNo(), raw.storeCode(), raw.productCode(), l.in.productId(), name,
                                raw.batch(), l.in.parsed().expiry(), l.in.parsed().mrp(), l.in.parsed().purchaseRate(),
                                l.in.parsed().quantity(), l.in.parsed().valuePaise());
                    }).toList();
            int from = Math.min(offset, all.size());
            return new Slice<>(all.subList(from, Math.min(from + limit, all.size())), all.size());
        }

        @Override
        public List<IssueRow> issuesOfLines(UUID tenantId, Collection<UUID> lineIds) {
            return issueRows.stream().filter(i -> !i.superseded() && lineIds.contains(i.lineId()))
                    .map(i -> new IssueRow(i.lineId(), i.rowNo(), i.field(), i.code(), i.severity())).toList();
        }

        @Override
        public List<IssueRow> errorIssues(UUID tenantId, UUID importId, int limit) {
            return issueRows.stream().filter(i -> !i.superseded() && i.severity().equals("ERROR")
                    && lineRows.stream().anyMatch(l -> l.id.equals(i.lineId()) && l.importId.equals(importId)))
                    .sorted(Comparator.comparingInt(Issue::rowNo)).limit(limit)
                    .map(i -> new IssueRow(i.lineId(), i.rowNo(), i.field(), i.code(), i.severity())).toList();
        }
    };

    // ---- the real services ----

    final DuplicateFinder duplicateFinder = new DuplicateFinder(productRepository);
    final ProductAssembler productAssembler = new ProductAssembler(productRepository, f.storeRepository, f.time);
    final ProductService productService = new ProductService(f.gate, f.unitService, productRepository, productAssembler,
            duplicateFinder, f.auditLog, f.time);
    final ProductSearchService searchService = new ProductSearchService(f.gate, productRepository, productAssembler,
            duplicateFinder);
    final OpeningStockAssembler importAssembler = new OpeningStockAssembler(f.storeRepository, f.team);
    final OpeningStockService openingStock = new OpeningStockService(f.gate, f.guard, f.storeRepository, importRepository,
            productRepository, f.team, importAssembler, f.auditLog, f.time);

    UUID tenant() {
        return PharmacyFixture.TENANT;
    }
}
