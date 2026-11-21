package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ComplianceRepository.ActivityEvent;
import com.sahaj.pharmacy.application.ComplianceRepository.Registration;
import com.sahaj.pharmacy.application.SaleInputs.PharmacistRegistrationCreate;
import com.sahaj.pharmacy.application.SaleInputs.PharmacistRegistrationUpdate;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.TeamRoles;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-19, licences and renewals, pharmacist registrations and the audit-log feed (docs/api/pharmacy-sales.yaml
 * pharmacyListLicences, pharmacyAddPharmacistRegistration, pharmacyUpdatePharmacistRegistration, pharmacyActivityFeed; DEC-074
 * OQ-80-19, OQ-80-35).
 *
 * <p>Licences and the feed are for the owner and a pharmacist member; a registration is the owner's to add or change (SETUP_EDIT).
 * Nothing is blocked or sent when a date passes: the status is worked out on every read (VALID more than 60 days away, DUE within 60,
 * EXPIRED after the date, NO_DATE). A registration is changed in place, never deleted; Sahaj verifies nothing with any authority. The
 * feed reads {@code pharmacy.document_event}, one page at a time, with sentences built from ids, document numbers and reason labels
 * only: never a patient name, a free-text note or an allergy text.
 */
@Service
public class ComplianceService {

    static final int WARNING_DAYS = 60;
    static final int MAX_DAYS = 366;
    static final int DEFAULT_DAYS = 30;
    static final int MAX_SIZE = 25;
    static final String ADD = "pharmacyAddPharmacistRegistration";
    static final String RETAIL = "Retail drug licence (20B / 21B)";
    static final String SCHEDULE_X = "Schedule X licence (20F / 21F)";
    static final String GST = "GST registration";
    static final String PHARMACIST = "Registered pharmacist";
    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);
    private static final String ENTITY_TYPE = "PHARMACY_PHARMACIST_REGISTRATION";
    private static final Set<String> TYPES = Set.of("ALLERGY_OVERRIDE", "H1_OVERRIDE", "DISCOUNT_REQUESTED", "DISCOUNT_DECIDED",
            "STOCK_ADJUSTED", "BILL_CANCELLED", "SALES_RETURN", "SECOND_SIGNATURE");

    private final PharmacyGate gate;
    private final ComplianceRepository repository;
    private final UnitRepository units;
    private final StoreRepository stores;
    private final TeamMemberPort team;
    private final SaleSupport support;
    private final ActiveReasonService reasons;
    private final ClientRequestRepository clients;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final BlindCount blind;

    public ComplianceService(PharmacyGate gate, ComplianceRepository repository, UnitRepository units, StoreRepository stores,
                             TeamMemberPort team, SaleSupport support, ActiveReasonService reasons,
                             ClientRequestRepository clients, AuditLog auditLog, PharmacyTime time, BlindCount blind) {
        this.gate = gate;
        this.repository = repository;
        this.units = units;
        this.stores = stores;
        this.team = team;
        this.support = support;
        this.reasons = reasons;
        this.clients = clients;
        this.auditLog = auditLog;
        this.time = time;
        this.blind = blind;
    }

    // ---- licences ----

    @Transactional(readOnly = true)
    public SalesReturnViews.Licences licences(UUID tenantId, UUID userId) {
        requireOwnerOrPharmacist(gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS));
        LocalDate today = time.today();
        Unit unit = units.find(tenantId).orElse(null);
        List<SalesReturnViews.LicenceRow> rows = new ArrayList<>();
        List<String> expired = new ArrayList<>();
        SalesReturnViews.LicenceStatus retail = status(unit == null ? null : unit.drugLicenceValidTill(), today);
        rows.add(new SalesReturnViews.LicenceRow("RETAIL_DRUG_LICENCE", RETAIL, unit == null ? null : unit.drugLicenceNo(),
                unit == null ? null : unit.drugLicenceValidTill(), retail, null));
        addExpired(expired, RETAIL, retail);
        repository.scheduleXLicence(tenantId).ifPresent(x -> {
            SalesReturnViews.LicenceStatus s = status(x.validTill(), today);
            rows.add(new SalesReturnViews.LicenceRow("SCHEDULE_X_LICENCE", SCHEDULE_X, x.number(), x.validTill(), s, null));
            addExpired(expired, SCHEDULE_X, s);
        });
        rows.add(new SalesReturnViews.LicenceRow("GST_REGISTRATION", GST, unit == null ? null : unit.gstin(), null,
                new SalesReturnViews.LicenceStatus("NONE", null), null));
        List<Registration> registrations = repository.registrations(tenantId);
        Map<UUID, TeamMember> members = support.members(tenantId,
                registrations.stream().map(Registration::membershipId).collect(Collectors.toSet()));
        for (Registration r : registrations) {
            SalesReturnViews.PharmacistRegistration view = registration(r, members);
            SalesReturnViews.LicenceStatus s = status(r.validTill(), today);
            rows.add(new SalesReturnViews.LicenceRow("PHARMACIST", PHARMACIST, r.registrationNo(), r.validTill(), s, view));
            addExpired(expired, PHARMACIST + " " + view.member().name(), s);
        }
        return new SalesReturnViews.Licences(WARNING_DAYS, expired, rows);
    }

    /** VALID more than 60 days away, DUE within 60 days, EXPIRED after the date, NO_DATE without one. */
    static SalesReturnViews.LicenceStatus status(LocalDate validTill, LocalDate today) {
        if (validTill == null) {
            return new SalesReturnViews.LicenceStatus("NO_DATE", null);
        }
        long days = ChronoUnit.DAYS.between(today, validTill);
        if (days < 0) {
            return new SalesReturnViews.LicenceStatus("EXPIRED", null);
        }
        return new SalesReturnViews.LicenceStatus(days <= WARNING_DAYS ? "DUE" : "VALID", (int) days);
    }

    private static void addExpired(List<String> expired, String document, SalesReturnViews.LicenceStatus status) {
        if ("EXPIRED".equals(status.state())) {
            expired.add(document);
        }
    }

    // ---- pharmacist registrations ----

    @Transactional
    public PharmacyViews.Outcome<SalesReturnViews.PharmacistRegistration> add(UUID tenantId, UUID userId,
                                                                             PharmacyInputs.Body<PharmacistRegistrationCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        PharmacistRegistrationCreate in = body.read();
        UUID me = caller.actorId();
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!ADD.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Registration first = repository.registration(tenantId, own.subjectId()).orElseThrow(ComplianceService::notFound);
                return new PharmacyViews.Outcome<>(registration(first, support.members(tenantId, List.of(first.membershipId()))),
                        false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        TeamMember member = in.membershipId() == null ? null : team.find(tenantId, in.membershipId()).filter(TeamMember::active)
                .filter(m -> TeamRoles.PHARMACIST.equals(m.role())).orElse(null);
        if (member == null) {
            errors.add(new FieldMessage("membershipId", "Choose a pharmacist."));
        }
        String number = number(in.registrationNo(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        Unit unit = units.find(tenantId).orElseThrow(ComplianceService::notFound);
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, unit.id(), me, in.clientKey(), ADD, id, false) != null) {
            return add(tenantId, userId, body); // L15
        }
        if (repository.registrationOfMember(tenantId, member.membershipId()).isPresent()) {
            throw PharmacyProblemException.saleConflict("already-registered", "Already registered",
                    "This pharmacist already has a registration. Change it instead.");
        }
        Registration created = new Registration(id, unit.id(), member.membershipId(), number, in.validTill(), 0L);
        repository.insertRegistration(tenantId, created, me);
        auditLog.record("PHARMACY_PHARMACIST_REGISTRATION_ADDED", ENTITY_TYPE, id, null, values(created));
        log.info("Pharmacist registration added registrationId={}", id);
        return new PharmacyViews.Outcome<>(registration(created, Map.of(member.membershipId(), member)), true);
    }

    @Transactional
    public SalesReturnViews.PharmacistRegistration update(UUID tenantId, UUID userId, String rawId,
                                                          PharmacyInputs.Body<PharmacistRegistrationUpdate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID id = PharmacyParams.pathId(rawId);
        PharmacistRegistrationUpdate in = body.read();
        List<FieldMessage> errors = new ArrayList<>();
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        String number = number(in.registrationNo(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        Registration before = repository.registration(tenantId, id).orElseThrow(ComplianceService::notFound);
        if (version != before.version() || !repository.updateRegistration(tenantId, id, version, number, in.validTill(),
                caller.actorId())) {
            Registration current = repository.registration(tenantId, id).orElse(before);
            throw PharmacyProblemException.settingsChanged(registration(current, support.members(tenantId,
                    List.of(current.membershipId()))));
        }
        Registration after = repository.registration(tenantId, id).orElseThrow();
        auditLog.record("PHARMACY_PHARMACIST_REGISTRATION_CHANGED", ENTITY_TYPE, id, values(before), values(after));
        return registration(after, support.members(tenantId, List.of(after.membershipId())));
    }

    private static String number(String raw, List<FieldMessage> errors) {
        String text = SaleSupport.blank(raw);
        if (text == null || text.length() > 40) {
            errors.add(new FieldMessage("registrationNo", "Enter the registration number (up to 40 characters)"));
        }
        return text;
    }

    private SalesReturnViews.PharmacistRegistration registration(Registration r, Map<UUID, TeamMember> members) {
        return new SalesReturnViews.PharmacistRegistration(r.id(), support.person(r.membershipId(), members), r.registrationNo(),
                r.validTill(), r.version());
    }

    private static Map<String, Object> values(Registration r) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("registrationId", r.id().toString());
        values.put("membershipId", r.membershipId().toString());
        values.put("registrationNo", r.registrationNo());
        values.put("validTill", r.validTill() == null ? null : r.validTill().toString());
        return values;
    }

    // ---- activity feed ----

    @Transactional(readOnly = true)
    public SalesReturnViews.ActivityPage activity(UUID tenantId, UUID userId, String rawStoreId, String rawFrom, String rawTo,
                                                  String rawMembershipId, String rawType, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        requireOwnerOrPharmacist(caller);
        LocalDate today = time.today();
        RegisterService.Range range = RegisterService.range(rawFrom, rawTo, today.minusDays(DEFAULT_DAYS), today, false);
        UUID membershipId = PharmacyParams.optionalUuid("membershipId", rawMembershipId, "Choose a person");
        String type = SaleSupport.blank(rawType);
        if (type != null && !TYPES.contains(type)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("type", "Choose a valid option")));
        }
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, MAX_SIZE, 1, MAX_SIZE);
        List<Store> reachable = caller.isOwner() ? stores.list(tenantId, true).stream().filter(Store::active).toList()
                : caller.mappings().stream().map(PharmacyCaller.Mapping::store).toList();
        UUID storeId = PharmacyParams.optionalUuid("storeId", rawStoreId, "Choose a store");
        if (storeId != null) {
            reachable = reachable.stream().filter(s -> s.id().equals(storeId)).findFirst().map(List::of)
                    .orElseThrow(StoreAccessGuard::notFound);
        }
        Map<UUID, Store> storeById = new HashMap<>();
        reachable.forEach(s -> storeById.put(s.id(), s));
        ComplianceRepository.ActivitySlice slice = repository.activity(tenantId, storeById.keySet(), range.fromInstant(),
                range.toInstant(), membershipId, type, size, page * size);
        Map<UUID, TeamMember> members = support.members(tenantId, slice.items().stream().map(ActivityEvent::actor)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        Map<String, String> labels = reasons.labels(tenantId);
        List<SalesReturnViews.ActivityRow> items = new ArrayList<>();
        // L22: while a store has an open audit a blind caller must not read "adjusted ADJ-.. 40 to 38" of that store (rule 24)
        Map<UUID, Boolean> blindAt = new HashMap<>();
        for (ActivityEvent e : slice.items()) {
            Store store = storeById.get(e.storeId());
            TeamMember who = e.actor() == null ? null : members.get(e.actor());
            boolean hideQuantities = e.storeId() != null
                    && blindAt.computeIfAbsent(e.storeId(), id -> blind.at(caller, id).active());
            items.add(new SalesReturnViews.ActivityRow(e.at(), support.person(e.actor(), members), roleLabel(who, e.actingAs()),
                    e.type(), sentence(e, labels, store == null ? "" : store.name(), hideQuantities),
                    store == null ? null : OpeningStockPostService.storeRef(store)));
        }
        return new SalesReturnViews.ActivityPage(items, slice.total(), page, size);
    }

    /** The sentence of one feed row, built from the document number, the reason label and the store name. */
    static String sentence(ActivityEvent e, Map<String, String> labels, String storeName) {
        return sentence(e, labels, storeName, false);
    }

    /** {@code hideQuantities}: the before and after quantities are left out (a blind caller at a store with an open audit). */
    static String sentence(ActivityEvent e, Map<String, String> labels, String storeName, boolean hideQuantities) {
        String reason = e.reasonKey() == null ? null : labels.getOrDefault(e.reasonKey(), e.reasonKey());
        String where = storeName.isEmpty() ? "" : " · " + storeName;
        String doc = e.docNo() == null ? "" : e.docNo();
        String percent = e.percent() == null ? "" : SaleRules.percent(e.percent()) + "% ";
        switch (e.type()) {
            case "ALLERGY_OVERRIDE":
                return "overrode allergy block on bill " + doc + (reason == null ? "" : " · reason: " + reason) + where;
            case "H1_OVERRIDE":
                return "used a Schedule H1 override on bill " + doc + (reason == null ? "" : " · reason: " + reason) + where;
            case "DISCOUNT_REQUESTED":
                return "requested " + percent + "discount · sent for approval" + where;
            case "DISCOUNT_DECIDED":
                return (reason == null ? "approved " : "rejected ") + percent + "discount"
                        + (reason == null ? "" : " · reason: " + reason) + where;
            case "STOCK_ADJUSTED":
                return "adjusted " + doc + (hideQuantities || e.qtyBefore() == null || e.qtyAfter() == null ? ""
                        : " " + e.qtyBefore() + " to " + e.qtyAfter()) + (reason == null ? "" : " · " + reason) + where;
            case "BILL_CANCELLED":
                return "cancelled bill " + doc + (reason == null ? "" : " · reason: " + reason) + where;
            case "SALES_RETURN":
                return e.event().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " sales return of bill " + doc + where;
            default:
                return "gave the second signature on " + e.docType().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " "
                        + doc + where;
        }
    }

    private static String roleLabel(TeamMember who, String actingAs) {
        if (actingAs != null) {
            return actingAs.substring(0, 1) + actingAs.substring(1).toLowerCase(java.util.Locale.ROOT);
        }
        if (who == null) {
            return "";
        }
        return TeamRoles.OWNER.equals(who.role()) ? "Owner" : TeamRoles.PHARMACIST.equals(who.role()) ? "Pharmacist"
                : who.role() == null ? "" : who.role().substring(0, 1) + who.role().substring(1).toLowerCase(java.util.Locale.ROOT)
                        .replace('_', ' ');
    }

    private static void requireOwnerOrPharmacist(PharmacyCaller caller) {
        if (!caller.isOwner() && !caller.isPharmacist()) {
            throw new ForbiddenException("forbidden", "Not allowed", PharmacyGate.NOT_ALLOWED);
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException(SaleSupport.NOT_FOUND);
    }
}
