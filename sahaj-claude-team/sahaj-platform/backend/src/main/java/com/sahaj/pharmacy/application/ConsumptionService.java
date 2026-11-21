package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ConsumptionRepository.Row;
import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-16, ward consumption (docs/api/pharmacy.yaml 0.3.6: pharmacyListAdmittedPatients, pharmacyOrderedByOptions,
 * pharmacyListConsumptions, pharmacyCreateConsumption, pharmacyGetConsumption). A ward records the stock it used: GENERAL (a
 * purpose, not billed) or PATIENT (an admission of the ward linked to the store, CHARGE_PENDING until SAH-80 posts the charge). One
 * transaction picks the FEFO batches, takes the units out of AVAILABLE (ledger WARD_CONSUMPTION, or WARD_CONSUMPTION_PATIENT with the
 * admission id and the IP number as the reference) and inserts the entry. Entries are never edited or deleted.
 *
 * <p>The patient list and the patient entry are Integrated-only (rule 5): 409 {@code integration-off} while the unit is Standalone,
 * the store has no linked ward, or the IPD reader is the no-op. A Schedule X line needs a pharmacist and the verified second signer
 * (rule 19): a nurse cannot record it. Names are never logged or stored by pharmacy, except the typed "Other" ordered-by name.
 * Order: the gate, the path ids, the body, the store guard (capability consume), the client key, the validation, then the locks:
 * the client key, the store row, then the stock rows inside {@link StockPosting}.
 */
@Service
public class ConsumptionService {

    static final String OP_CREATE = "pharmacyCreateConsumption";
    static final String NOT_ADMITTED = "This patient is not admitted to this ward.";
    static final String GENERAL_REFERENCE = "Ward general";
    private static final Logger log = LoggerFactory.getLogger(ConsumptionService.class);
    private static final String ENTITY_TYPE = "PHARMACY_CONSUMPTION";
    private static final String DOC_TYPE = "CONSUMPTION";
    private static final int MAX_LINES = 200;
    private static final int MAX_QTY = 999_999;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final UnitRepository units;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final ScheduleXSignature signature;
    private final ConsumptionRepository consumptions;
    private final AdmittedPatientsReader admitted;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final ApprovalEngine engine;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final StockAuditService stockAudits;
    private final BlindCount blind;

    public ConsumptionService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, UnitRepository units,
                              ProductRepository products, StockRepository stock, StockPosting posting,
                              ScheduleXSignature signature, ConsumptionRepository consumptions, AdmittedPatientsReader admitted,
                              ClientRequestRepository clients, DocumentEventRepository events, ActiveReasonService reasons,
                              ApprovalEngine engine, TeamMemberPort team, AuditLog auditLog, PharmacyTime time,
                              StockAuditService stockAudits, BlindCount blind) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.units = units;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.signature = signature;
        this.consumptions = consumptions;
        this.admitted = admitted;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.engine = engine;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
        this.stockAudits = stockAudits;
        this.blind = blind;
    }

    // ---- reads ----

    /** The ADMITTED patients of the ward linked to this store; q (2 to 30) filters by IP number or name. Audited without names. */
    @Transactional // read-write on purpose: it writes the audit row, and a read-only transaction refuses that on PostgreSQL
    public ConsumptionViews.AdmittedList admittedPatients(UUID tenantId, UUID userId, String rawStoreId, String rawQ) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.CONSUME);
        String q = rawQ == null || rawQ.isBlank() ? null : rawQ.trim();
        if (q != null && (q.length() < 2 || q.length() > 30)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("q", "Type 2 to 30 characters.")));
        }
        UUID ward = linkedWardOrOff(tenantId, storeId);
        List<AdmittedPatientsReader.Admitted> all = admitted.admittedTo(tenantId, ward);
        String needle = q == null ? null : q.toLowerCase(java.util.Locale.ROOT);
        List<ConsumptionViews.AdmittedPatient> items = all.stream()
                .filter(a -> needle == null || a.admissionNo() != null && a.admissionNo().toLowerCase(java.util.Locale.ROOT).contains(needle)
                        || a.patientName() != null && a.patientName().toLowerCase(java.util.Locale.ROOT).contains(needle))
                .limit(200).map(a -> new ConsumptionViews.AdmittedPatient(a.admissionId(), a.admissionNo(), a.patientName(), a.bed()))
                .toList();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("storeId", storeId.toString());
        after.put("wardId", ward.toString());
        after.put("count", items.size());
        auditLog.record("PHARMACY_ADMITTED_PATIENTS_READ", ENTITY_TYPE, storeId, null, after);
        return new ConsumptionViews.AdmittedList(items);
    }

    /** ACTIVE doctors and visiting doctors of the unit, in name order; the client adds "Other" with a typed name. */
    @Transactional(readOnly = true)
    public ConsumptionViews.OrderedByList orderedByOptions(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        List<PharmacyViews.Person> items = team.findActiveDoctors(tenantId).stream()
                .sorted(java.util.Comparator.comparing(TeamMemberPort.TeamMember::name, String.CASE_INSENSITIVE_ORDER))
                .map(m -> new PharmacyViews.Person(m.membershipId(), m.name())).toList();
        return new ConsumptionViews.OrderedByList(items);
    }

    /** "Today · store": the entries of an IST day (default today), newest first. */
    @Transactional(readOnly = true)
    public ConsumptionViews.Page list(UUID tenantId, UUID userId, String rawStoreId, String rawDate, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        Store store = guard.require(caller, storeId, StoreCapability.CONSUME);
        LocalDate date = time.today();
        if (rawDate != null && !rawDate.isBlank()) {
            try {
                date = LocalDate.parse(rawDate.trim());
            } catch (DateTimeParseException bad) {
                throw new InvalidFieldsException(List.of(new FieldMessage("date", "Enter a date")));
            }
        }
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        Instant from = date.atStartOfDay(PharmacyTime.IST).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(PharmacyTime.IST).toInstant();
        ConsumptionRepository.Slice slice = consumptions.page(tenantId, storeId, from, to, size, page * size);
        return new ConsumptionViews.Page(views(tenantId, store, slice.items()), slice.total(), page, size);
    }

    /** One entry; capability consume or readStock at the store. */
    @Transactional(readOnly = true)
    public ConsumptionViews.Consumption get(UUID tenantId, UUID userId, String rawStoreId, String rawConsumptionId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID id = PharmacyParams.pathId(rawConsumptionId);
        Store store = StoreAccessGuard.holds(caller, storeId, StoreCapability.CONSUME)
                ? guard.require(caller, storeId, StoreCapability.CONSUME)
                : guard.require(caller, storeId, StoreCapability.READ_STOCK);
        Row row = consumptions.find(tenantId, storeId, id).orElseThrow(ConsumptionService::notFound);
        return views(tenantId, store, List.of(row)).get(0);
    }

    // ---- create ----

    /**
     * The second signer to verify before the transaction: a pharmacist recording a Schedule X line, key not used yet. A caller who
     * is not a pharmacist answers 403 schedule-x-pharmacist here, before any PIN is checked.
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId,
                                                     PharmacyInputs.Body<PharmacyInputs.ConsumptionCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.ConsumptionCreate in = body.read();
        guard.require(caller, storeId, StoreCapability.CONSUME);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        boolean scheduleX = in.lines() != null && in.lines().stream().anyMatch(l -> l != null && l.productId() != null
                && isScheduleX(tenantId, l.productId()));
        if (!scheduleX) {
            return null;
        }
        if (!caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        return in.secondSigner();
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public PharmacyViews.Outcome<ConsumptionViews.Consumption> create(UUID tenantId, UUID userId, String rawStoreId,
                                                                      PharmacyInputs.Body<PharmacyInputs.ConsumptionCreate> body,
                                                                      UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.ConsumptionCreate in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.CONSUME);
        UUID me = caller.actorId();
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_CREATE.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Row first = consumptions.find(tenantId, storeId, own.subjectId()).orElseThrow(ConsumptionService::notFound);
                return new PharmacyViews.Outcome<>(views(tenantId, store, List.of(first)).get(0), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }

        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        boolean patient = "PATIENT".equals(in.type());
        if (!patient && !"GENERAL".equals(in.type())) {
            errors.add(new FieldMessage("type", "Choose general or patient-wise"));
        }
        String note = StoreUseSupport.blank(in.note());
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        String purposeKey = null;
        UUID orderedByMember = null;
        String orderedByName = null;
        if (!patient && "GENERAL".equals(in.type())) {
            if (!reasons.isActive(tenantId, ReasonList.CONSUMPTION_PURPOSE, in.purposeKey())) {
                errors.add(new FieldMessage("purposeKey", "Choose a purpose"));
            } else {
                purposeKey = in.purposeKey();
                if (purposeKey.endsWith(".other") && (note == null || note.length() < 4)) {
                    errors.add(new FieldMessage("note", "Write at least 4 characters"));
                }
            }
        }
        if (patient) {
            if (in.admissionId() == null) {
                errors.add(new FieldMessage("admissionId", "Choose a patient"));
            }
            PharmacyInputs.OrderedByInput by = in.orderedBy();
            String other = by == null || by.otherName() == null ? null : by.otherName().trim();
            boolean member = by != null && by.membershipId() != null;
            if (member == (other != null && !other.isEmpty())) {
                errors.add(new FieldMessage("orderedBy", "Choose who ordered it"));
            } else if (member) {
                boolean listed = team.findActiveDoctors(tenantId).stream().anyMatch(m -> m.membershipId().equals(by.membershipId()));
                if (listed) {
                    orderedByMember = by.membershipId();
                } else {
                    errors.add(new FieldMessage("orderedBy", "Choose who ordered it"));
                }
            } else if (other.length() < 2 || other.length() > 80) {
                errors.add(new FieldMessage("orderedBy.otherName", "Type 2 to 80 characters."));
            } else {
                orderedByName = other;
            }
        }
        List<Checked> checked = checkLines(tenantId, in.lines(), errors);
        // "Post as consumption instead" (SAH-79-17): the line of the audit waiting for review that this entry corrects
        StockAuditRepository.Line auditLine = null;
        if (in.stockAuditLineId() != null) {
            auditLine = stockAudits.linkableLine(tenantId, storeId, in.stockAuditLineId());
            UUID auditedProduct = auditLine == null ? null : auditLine.productId();
            if (auditLine == null || checked.stream().noneMatch(c -> c.productId().equals(auditedProduct))) {
                errors.add(new FieldMessage("stockAuditLineId", "Choose a line of the open stock audit"));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        AdmittedPatientsReader.Admitted admission = null;
        if (patient) {
            UUID ward = linkedWardOrOff(tenantId, storeId);
            UUID wanted = in.admissionId();
            admission = admitted.admittedTo(tenantId, ward).stream().filter(a -> wanted.equals(a.admissionId())).findFirst()
                    .orElse(null);
            if (admission == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("admissionId", NOT_ADMITTED)));
            }
        }
        boolean scheduleX = checked.stream().anyMatch(Checked::scheduleX);
        if (scheduleX && !caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        if (scheduleX && verifiedSignerMembershipId == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
        }

        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_CREATE, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body, verifiedSignerMembershipId); // L15: answer as the replay
        }
        // lock order: the client key, the store row, then the stock rows inside StockPosting
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(ConsumptionService::notFound);
        StockAuditRepository.Audit linkedAudit = auditLine == null ? null : stockAudits.lockLinked(tenantId, auditLine);
        UUID signer = null;
        if (scheduleX) {
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }
        List<FailingLine> failing = new ArrayList<>();
        Map<UUID, List<Fefo.Pick>> picks = new LinkedHashMap<>();
        int rowNo = 0;
        for (Checked c : checked) {
            rowNo++;
            Fefo.Result result = posting.pick(stock.availableOfProduct(tenantId, storeId, c.productId()), c.quantity());
            if (result.shortBy() > 0) {
                failing.add(new FailingLine(null, rowNo, c.productId(), result.available()));
                continue;
            }
            picks.put(c.productId(), result.picks());
        }
        if (!failing.isEmpty()) {
            throw blind.scrub(caller, storeId, PharmacyProblemException.insufficientStock(failing.get(0).available(), failing));
        }

        String txn = patient ? "WARD_CONSUMPTION_PATIENT" : "WARD_CONSUMPTION";
        String reference = patient ? admission.admissionNo() : GENERAL_REFERENCE;
        UUID admissionId = patient ? admission.admissionId() : null;
        List<StockPosting.Line> moves = new ArrayList<>();
        List<ConsumptionRepository.Line> lines = new ArrayList<>();
        int number = 0;
        for (Checked c : checked) {
            for (Fefo.Pick pick : picks.get(c.productId())) {
                number++;
                UUID lineId = UUID.randomUUID();
                StockPosting.Doc doc = new StockPosting.Doc(DOC_TYPE, id, reference, lineId, admissionId);
                moves.add(new StockPosting.Line(storeId, c.productId(), pick.batchId(), StockState.AVAILABLE, -pick.quantity(), txn,
                        purposeKey, UUID.randomUUID(), null, doc, number, c.scheduleX() ? signer : null));
                lines.add(new ConsumptionRepository.Line(lineId, id, c.productId(), pick.batchId(), pick.quantity()));
            }
        }
        try {
            posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), moves);
        } catch (PharmacyProblemException refused) {
            throw blind.scrub(caller, storeId, refused);
        }
        if (auditLine != null) {
            UUID auditedBatch = auditLine.batchId();
            int fromBatch = lines.stream().filter(l -> l.batchId().equals(auditedBatch)).mapToInt(ConsumptionRepository.Line::quantity)
                    .sum();
            stockAudits.lowerBookForConsumption(tenantId, linkedAudit, auditLine, fromBatch, me);
        }
        consumptions.insert(tenantId, new Row(id, store.unitId(), storeId, patient ? "PATIENT" : "GENERAL", purposeKey, note,
                admissionId, orderedByMember, orderedByName, patient ? "CHARGE_PENDING" : "NOT_APPLICABLE", auditLine == null ? null : auditLine.id(), signer, null, me));
        lines.forEach(l -> consumptions.insertLine(tenantId, store.unitId(), l, me));
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "RECORDED", storeId, me, null, null, null, null, purposeKey);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("storeId", storeId.toString());
        after.put("type", patient ? "PATIENT" : "GENERAL");
        after.put("lineCount", lines.size());
        if (admissionId != null) {
            after.put("admissionId", admissionId.toString());
        }
        auditLog.record("PHARMACY_CONSUMPTION_RECORDED", ENTITY_TYPE, id, null, after);
        log.info("Consumption recorded consumptionId={} type={} lines={}", id, patient ? "PATIENT" : "GENERAL", lines.size());
        return new PharmacyViews.Outcome<>(views(tenantId, store, List.of(consumptions.find(tenantId, storeId, id).orElseThrow()))
                .get(0), true);
    }

    // ---- checks and views ----

    record Checked(UUID productId, int quantity, boolean scheduleX) {
    }

    /** All the line errors together (rule 21): 1 to 200 lines, the product ACTIVE and once, the quantity a whole number 1 to 999999. */
    List<Checked> checkLines(UUID tenantId, List<PharmacyInputs.TransferLineInput> lines, List<FieldMessage> errors) {
        List<PharmacyInputs.TransferLineInput> given = lines == null ? List.of() : lines;
        if (given.isEmpty() || given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", "Add 1 to 200 items"));
            return List.of();
        }
        List<Checked> checked = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (int i = 0; i < given.size(); i++) {
            PharmacyInputs.TransferLineInput line = given.get(i);
            String at = "lines[" + i + "]";
            if (line == null) {
                errors.add(new FieldMessage(at, UnitService.REQUIRED));
                continue;
            }
            ProductRepository.Row product = line.productId() == null ? null
                    : products.find(tenantId, line.productId()).map(ProductRepository.Full::row).orElse(null);
            if (product == null) {
                errors.add(new FieldMessage(at + ".productId", "Choose an active product"));
                continue;
            }
            if (!seen.add(line.productId())) {
                errors.add(new FieldMessage(at + ".productId", "Already on this entry"));
                continue;
            }
            Integer qty = PharmacyParams.wholeNumber(line.quantity(), 1, MAX_QTY);
            if (qty == null) {
                errors.add(new FieldMessage(at + ".quantity", "Enter a whole number from 1 to " + MAX_QTY));
                continue;
            }
            checked.add(new Checked(line.productId(), qty, GoodsReceiptService.isScheduleX(product)));
        }
        return checked;
    }

    private List<ConsumptionViews.Consumption> views(UUID tenantId, Store store, List<Row> rows) {
        List<UUID> ids = rows.stream().map(Row::id).toList();
        Map<UUID, List<ConsumptionRepository.Line>> linesOf = consumptions.linesOf(tenantId, ids);
        Map<UUID, String> ipOf = consumptions.ipNumbers(tenantId, ids);
        Set<UUID> people = new LinkedHashSet<>();
        for (Row r : rows) {
            people.add(r.recordedBy());
            if (r.orderedByMembershipId() != null) {
                people.add(r.orderedByMembershipId());
            }
            if (r.secondSignerMembershipId() != null) {
                people.add(r.secondSignerMembershipId());
            }
        }
        Map<UUID, TeamMemberPort.TeamMember> members = people.isEmpty() ? Map.of() : team.findAll(tenantId, people);
        Map<UUID, ProductRepository.Row> productOf = new HashMap<>();
        Map<UUID, StockRepository.BatchRow> batchOf = new HashMap<>();
        List<ConsumptionViews.Consumption> out = new ArrayList<>();
        for (Row r : rows) {
            List<ConsumptionViews.Line> lines = new ArrayList<>();
            for (ConsumptionRepository.Line l : linesOf.getOrDefault(r.id(), List.of())) {
                ProductRepository.Row product = productOf.computeIfAbsent(l.productId(),
                        p -> products.find(tenantId, p).map(ProductRepository.Full::row).orElseThrow());
                StockRepository.BatchRow batch = batchOf.computeIfAbsent(l.batchId(),
                        b -> stock.findBatch(tenantId, b).orElseThrow());
                lines.add(new ConsumptionViews.Line(IndentService.productRef(product),
                        new StockViews.BatchRef(batch.id(), batch.batchNo(), batch.expiryDate()), l.quantity()));
            }
            TeamMemberPort.TeamMember orderedBy = r.orderedByMembershipId() == null ? null : members.get(r.orderedByMembershipId());
            out.add(new ConsumptionViews.Consumption(r.id(), OpeningStockPostService.storeRef(store), r.type(),
                    engine.reasonRef(tenantId, r.purposeKey(), r.note()), ipOf.get(r.id()),
                    r.orderedByMembershipId() == null ? r.orderedByName() : orderedBy == null ? null : orderedBy.name(),
                    r.chargeStatus(), StoreUseSupport.person(r.recordedBy(), members), r.recordedAt(), store.name(), lines,
                    r.secondSignerMembershipId() == null ? null : StoreUseSupport.person(r.secondSignerMembershipId(), members)));
        }
        return out;
    }

    /** The IPD ward of the store, or 409 integration-off (Standalone unit, no linked ward). */
    private UUID linkedWardOrOff(UUID tenantId, UUID storeId) {
        boolean integrated = units.find(tenantId).map(u -> u.integrationMode() == IntegrationMode.INTEGRATED).orElse(false);
        if (!integrated) {
            throw new IntegrationOffException();
        }
        return consumptions.linkedWard(tenantId, storeId).orElseThrow(IntegrationOffException::new);
    }

    private boolean isScheduleX(UUID tenantId, UUID productId) {
        return products.find(tenantId, productId).map(f -> GoodsReceiptService.isScheduleX(f.row())).orElse(false);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
