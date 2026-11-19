package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyMasterViews.StoreName;
import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.PrintDocType;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.pharmacy.domain.Vendor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory stand-ins for the WP-C repositories, wired into the real services over the {@link PharmacyFixture}. They mimic
 * row level security (rows carry their tenant) but not the database constraints: the services own the rules and the
 * constraints are tested against PostgreSQL by QA.
 */
final class MasterFixture {

    final PharmacyFixture f = new PharmacyFixture();
    final List<String> writes = new ArrayList<>();

    // ---- vendors ----

    final Map<UUID, Vendor> vendorRows = new LinkedHashMap<>();
    final Map<UUID, UUID> vendorClientKeys = new HashMap<>();
    final Map<UUID, UUID> vendorCreatedBy = new HashMap<>();
    final Map<UUID, Set<UUID>> vendorStores = new HashMap<>();
    final Map<UUID, Integer> productCounts = new HashMap<>();

    final VendorRepository vendorRepository = new VendorRepository() {
        @Override
        public Slice list(UUID tenantId, String like, Filter filter, LocalDate today, int limit, int offset) {
            String needle = like.replace("%", "").replace("\\", "").toLowerCase(Locale.ROOT);
            List<Row> all = vendorRows.values().stream()
                    .filter(v -> needle.isEmpty() || v.name().toLowerCase(Locale.ROOT).contains(needle))
                    .filter(v -> {
                        String status = v.status(today);
                        switch (filter) {
                            case ALL:
                                return true;
                            case INACTIVE:
                                return status.equals("INACTIVE");
                            case BLOCKED:
                                return status.equals("BLOCKED");
                            case ACTIVE:
                                return status.equals("ACTIVE");
                            default:
                                return !status.equals("INACTIVE");
                        }
                    })
                    .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                    .map(v -> new Row(v, productCounts.getOrDefault(v.id(), 0))).toList();
            int to = Math.min(all.size(), offset + limit);
            return new Slice(offset >= all.size() ? List.of() : all.subList(offset, to), all.size());
        }

        @Override
        public Slice listSupplying(UUID tenantId, String like, Filter filter, LocalDate today, UUID storeId, int limit,
                                   int offset) {
            Slice all = list(tenantId, like, filter, today, 1000, 0);
            List<Row> kept = all.items().stream().filter(r -> vendorStores.getOrDefault(r.vendor().id(), Set.of())
                    .contains(storeId)).toList();
            int to = Math.min(kept.size(), offset + limit);
            return new Slice(offset >= kept.size() ? List.of() : kept.subList(offset, to), kept.size());
        }

        @Override
        public Optional<Vendor> find(UUID tenantId, UUID id) {
            return Optional.ofNullable(vendorRows.get(id));
        }

        @Override
        public int productsCount(UUID tenantId, UUID vendorId) {
            return productCounts.getOrDefault(vendorId, 0);
        }

        @Override
        public Map<UUID, List<StoreName>> suppliesTo(UUID tenantId, Collection<UUID> vendorIds) {
            Map<UUID, List<StoreName>> found = new HashMap<>();
            for (UUID id : vendorIds) {
                List<StoreName> names = new ArrayList<>();
                for (UUID storeId : vendorStores.getOrDefault(id, Set.of())) {
                    f.storeRepository.find(tenantId, storeId).filter(Store::active)
                            .ifPresent(s -> names.add(new StoreName(s.id(), s.name())));
                }
                if (!names.isEmpty()) {
                    found.put(id, names);
                }
            }
            return found;
        }

        @Override
        public Optional<ClientKeyHit> findByClientKey(UUID tenantId, UUID clientKey) {
            return vendorClientKeys.entrySet().stream().filter(e -> e.getValue().equals(clientKey)).findFirst()
                    .map(e -> new ClientKeyHit(e.getKey(), vendorCreatedBy.get(e.getKey())));
        }

        @Override
        public boolean nameInUse(UUID tenantId, String name, UUID except) {
            return vendorRows.values().stream().anyMatch(v -> v.name().equalsIgnoreCase(name) && !v.id().equals(except));
        }

        @Override
        public UUID insert(UUID tenantId, UUID unitId, UUID clientKey, Fields x, UUID actor) {
            writes.add("vendor.insert");
            UUID id = UUID.randomUUID();
            vendorRows.put(id, of(id, x, true, 0L));
            vendorClientKeys.put(id, clientKey);
            vendorCreatedBy.put(id, actor);
            return id;
        }

        @Override
        public boolean update(UUID tenantId, UUID id, long version, Fields x, UUID actor) {
            Vendor v = vendorRows.get(id);
            if (v == null || v.version() != version) {
                return false;
            }
            writes.add("vendor.update");
            vendorRows.put(id, of(id, x, v.active(), version + 1));
            return true;
        }

        @Override
        public void switchOff(UUID tenantId, UUID id, UUID actor) {
            writes.add("vendor.switchOff");
            Vendor v = vendorRows.get(id);
            vendorRows.put(id, new Vendor(id, v.name(), v.gstin(), v.licenceRequired(), v.licenceNo(),
                    v.licenceValidTill(), v.contactName(), v.contactPhone(), v.creditDays(), v.returnDaysBeforeExpiry(),
                    v.returnCreditPercent(), v.damagedCreditPercent(), v.expiredCreditPercent(), false, v.version() + 1));
        }

        @Override
        public void switchOn(UUID tenantId, UUID id, UUID actor) {
            writes.add("vendor.switchOn");
            Vendor v = vendorRows.get(id);
            vendorRows.put(id, new Vendor(id, v.name(), v.gstin(), v.licenceRequired(), v.licenceNo(),
                    v.licenceValidTill(), v.contactName(), v.contactPhone(), v.creditDays(), v.returnDaysBeforeExpiry(),
                    v.returnCreditPercent(), v.damagedCreditPercent(), v.expiredCreditPercent(), true, v.version() + 1));
        }

        @Override
        public void replaceStores(UUID tenantId, UUID unitId, UUID vendorId, Collection<UUID> storeIds, UUID actor) {
            writes.add("vendor.stores");
            vendorStores.put(vendorId, new LinkedHashSet<>(storeIds));
        }

        private Vendor of(UUID id, Fields x, boolean active, long version) {
            return new Vendor(id, x.name(), x.gstin(), x.licenceRequired(), x.licenceNo(), x.licenceValidTill(),
                    x.contactName(), x.contactPhone(), x.creditDays(), x.returnDaysBeforeExpiry(),
                    x.returnCreditPercent(), x.damagedCreditPercent(), x.expiredCreditPercent(), active, version);
        }
    };

    // ---- approval rules ----

    final List<ApprovalRuleRepository.Version> versionRows = new ArrayList<>();
    final Map<UUID, List<ApprovalRule>> ruleRows = new HashMap<>();
    UUID pointer;

    final ApprovalRuleRepository ruleRepository = new ApprovalRuleRepository() {
        @Override
        public Optional<Version> current(UUID tenantId) {
            return pointer == null ? Optional.empty()
                    : versionRows.stream().filter(v -> v.id().equals(pointer)).findFirst();
        }

        @Override
        public Optional<Version> version(UUID tenantId, int versionNo) {
            return versionRows.stream().filter(v -> v.versionNo() == versionNo).findFirst();
        }

        @Override
        public VersionSlice versions(UUID tenantId, int limit, int offset) {
            List<Version> newestFirst = new ArrayList<>(versionRows);
            newestFirst.sort((a, b) -> Integer.compare(b.versionNo(), a.versionNo()));
            int to = Math.min(newestFirst.size(), offset + limit);
            return new VersionSlice(offset >= newestFirst.size() ? List.of() : newestFirst.subList(offset, to),
                    newestFirst.size());
        }

        @Override
        public List<ApprovalRule> rules(UUID tenantId, UUID versionId) {
            return ruleRows.getOrDefault(versionId, List.of());
        }

        @Override
        public UUID insertVersion(UUID tenantId, UUID unitId, int versionNo, RulePreset preset, Long high, UUID actor) {
            writes.add("rules.version");
            UUID id = UUID.randomUUID();
            versionRows.add(new Version(id, versionNo, preset, high, f.time.now(), actor));
            ruleRows.put(id, new ArrayList<>());
            pendingVersion = id;
            return id;
        }

        @Override
        public UUID insertRule(UUID tenantId, UUID unitId, UUID versionId, TransactionType type, Integer hours,
                               Integer urgent, UUID actor) {
            UUID id = UUID.randomUUID();
            pendingLevels.put(id, new ArrayList<>());
            pendingRules.put(id, new Object[] {versionId, type, hours, urgent});
            return id;
        }

        @Override
        public void insertLevel(UUID tenantId, UUID unitId, UUID ruleId, ApprovalLevel level, UUID actor) {
            pendingLevels.get(ruleId).add(level);
        }

        @Override
        public void insertSet(UUID tenantId, UUID unitId, UUID versionId, UUID actor) {
            flushRules();
            writes.add("rules.setInsert");
            pointer = versionId;
        }

        @Override
        public void updateSet(UUID tenantId, UUID unitId, UUID versionId, UUID actor) {
            flushRules();
            writes.add("rules.setUpdate");
            pointer = versionId;
        }
    };

    private UUID pendingVersion;
    private final Map<UUID, List<ApprovalLevel>> pendingLevels = new LinkedHashMap<>();
    private final Map<UUID, Object[]> pendingRules = new LinkedHashMap<>();

    private void flushRules() {
        List<ApprovalRule> rules = ruleRows.get(pendingVersion);
        pendingRules.forEach((ruleId, meta) -> rules.add(new ApprovalRule((TransactionType) meta[1],
                pendingLevels.get(ruleId), (Integer) meta[2], (Integer) meta[3])));
        pendingRules.clear();
        pendingLevels.clear();
    }

    // ---- numbering ----

    final Map<DocType, DocumentSeriesRepository.Row> seriesRows = new EnumMap<>(DocType.class);
    /** The types that took a number in the current financial year (the series lock). */
    final Set<DocType> issuedThisYear = EnumSet.noneOf(DocType.class);
    /** When the last number of a type was issued (a type here with an instant before the year start is not "this year"). */
    final Map<DocType, java.time.Instant> issuedAt = new EnumMap<>(DocType.class);
    /** The instants the series lock was asked about. */
    final List<java.time.Instant> lockAskedSince = new ArrayList<>();
    final List<DocType> seriesLocks = new ArrayList<>();

    final DocumentSeriesRepository seriesRepository = new DocumentSeriesRepository() {
        @Override
        public List<Row> list(UUID tenantId) {
            return new ArrayList<>(seriesRows.values());
        }

        @Override
        public Optional<Row> find(UUID tenantId, DocType docType) {
            return Optional.ofNullable(seriesRows.get(docType));
        }

        @Override
        public Optional<Row> findForUpdate(UUID tenantId, DocType docType) {
            seriesLocks.add(docType);
            return find(tenantId, docType);
        }

        @Override
        public boolean issuedSince(UUID tenantId, DocType docType, java.time.Instant since) {
            lockAskedSince.add(since);
            return issuedThisYear.contains(docType) || !issuedAt.getOrDefault(docType, java.time.Instant.MIN).isBefore(since);
        }

        @Override
        public Set<DocType> issuedSince(UUID tenantId, java.time.Instant since) {
            lockAskedSince.add(since);
            Set<DocType> types = EnumSet.copyOf(issuedThisYear);
            issuedAt.forEach((type, at) -> {
                if (!at.isBefore(since)) {
                    types.add(type);
                }
            });
            return types;
        }

        @Override
        public UUID insert(UUID tenantId, UUID unitId, DocType docType, String prefix, boolean fy, int length,
                           boolean restarts, UUID actor) {
            writes.add("series.insert");
            UUID id = UUID.randomUUID();
            seriesRows.put(docType, new Row(id, docType, prefix, fy, length, restarts, 1L));
            return id;
        }

        @Override
        public boolean update(UUID tenantId, UUID id, long version, String prefix, boolean fy, int length,
                              boolean restarts, UUID actor) {
            Row row = seriesRows.values().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
            if (row == null || row.version() != version) {
                return false;
            }
            writes.add("series.update");
            seriesRows.put(row.docType(), new Row(id, row.docType(), prefix, fy, length, restarts, version + 1));
            return true;
        }
    };

    // ---- reasons ----

    final List<ReasonRepository.SeededRow> seededRows = new ArrayList<>();
    final Map<String, Boolean> seededState = new HashMap<>();
    final List<ReasonRepository.OwnRow> ownRows = new ArrayList<>();

    final ReasonRepository reasonRepository = new ReasonRepository() {
        @Override
        public List<SeededRow> seeded(UUID tenantId) {
            return seededRows.stream().map(r -> new SeededRow(r.reasonKey(), r.listKey(), r.label(), r.sort(),
                    seededState.getOrDefault(r.reasonKey(), true))).toList();
        }

        @Override
        public List<OwnRow> own(UUID tenantId) {
            return List.copyOf(ownRows);
        }

        @Override
        public int nextOwnSort(UUID tenantId, String listKey) {
            return (int) ownRows.stream().filter(r -> r.listKey().equals(listKey)).count() + 1;
        }

        @Override
        public UUID insertOwn(UUID tenantId, UUID unitId, String listKey, String reasonKey, String label, int sort,
                              UUID actor) {
            writes.add("reason.insert");
            UUID id = UUID.randomUUID();
            ownRows.add(new OwnRow(id, listKey, reasonKey, label, true, sort));
            return id;
        }

        @Override
        public void setOwnActive(UUID tenantId, UUID id, boolean active, UUID actor) {
            writes.add("reason.ownActive");
            for (int i = 0; i < ownRows.size(); i++) {
                OwnRow r = ownRows.get(i);
                if (r.id().equals(id)) {
                    ownRows.set(i, new OwnRow(id, r.listKey(), r.reasonKey(), r.label(), active, r.sort()));
                }
            }
        }

        @Override
        public void setSeededActive(UUID tenantId, UUID unitId, String reasonKey, boolean active, UUID actor) {
            writes.add("reason.seededActive");
            seededState.put(reasonKey, active);
        }
    };

    // ---- print templates ----

    final Map<PrintDocType, PrintTemplateRepository.Row> templateRows = new EnumMap<>(PrintDocType.class);

    final PrintTemplateRepository templateRepository = new PrintTemplateRepository() {
        @Override
        public List<Row> list(UUID tenantId) {
            return new ArrayList<>(templateRows.values());
        }

        @Override
        public Optional<Row> find(UUID tenantId, PrintDocType docType) {
            return Optional.ofNullable(templateRows.get(docType));
        }

        @Override
        public UUID insert(UUID tenantId, UUID unitId, PrintDocType docType, List<String> lines, String footer,
                           String paper, UUID actor) {
            writes.add("template.insert");
            UUID id = UUID.randomUUID();
            templateRows.put(docType, new Row(id, docType, lines, footer, paper, 1L));
            return id;
        }

        @Override
        public boolean update(UUID tenantId, UUID id, long version, List<String> lines, String footer, String paper,
                              UUID actor) {
            Row row = templateRows.values().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
            if (row == null || row.version() != version) {
                return false;
            }
            writes.add("template.update");
            templateRows.put(row.docType(), new Row(id, row.docType(), lines, footer, paper, version + 1));
            return true;
        }
    };

    // ---- the real services over them ----

    final VendorService vendorService = new VendorService(f.gate, f.unitService, vendorRepository, f.storeRepository,
            f.auditLog, f.time);
    final ApprovalRulesService rulesService = new ApprovalRulesService(f.gate, f.unitService, ruleRepository,
            f.storeRepository, f.staffRepository, f.team, f.auditLog, f.time);
    final NumberingService numberingService = new NumberingService(f.gate, f.unitService, seriesRepository, f.auditLog,
            f.time);
    final ReasonService reasonService = new ReasonService(f.gate, f.unitService, reasonRepository, f.auditLog);
    final PrintTemplateService templateService = new PrintTemplateService(f.gate, f.unitService, f.unitRepository,
            templateRepository, f.letterhead, f.auditLog);

    MasterFixture() {
        seed("store-return", "excess-stock", "Excess stock", "near-expiry", "Near expiry", "not-needed",
                "Not needed (order stopped)");
        seed("sales-return", "doctor-changed-medicine", "Doctor changed medicine", "excess-bought", "Excess bought",
                "wrong-item-given", "Wrong item given", "adverse-reaction", "Adverse reaction");
        seed("damage", "damaged-leaking", "Damaged / leaking", "expired", "Expired", "cold-chain-break", "Cold-chain break");
        seed("stock-adjustment", "consumption-not-entered", "Consumption not entered", "breakage", "Breakage", "unknown",
                "Unknown");
    }

    /** Adds seeded reasons of one list: pairs of key suffix and label. */
    void seed(String list, String... pairs) {
        int sort = (int) seededRows.stream().filter(r -> r.listKey().equals(list)).count();
        for (int i = 0; i < pairs.length; i += 2) {
            seededRows.add(new ReasonRepository.SeededRow(list + "." + pairs[i], list, pairs[i + 1], ++sort, true));
        }
    }

    /** Time of the fixture, for expectations. */
    static Instant now() {
        return PharmacyFixture.NOW;
    }
}
