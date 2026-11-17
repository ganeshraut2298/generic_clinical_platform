package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.MedicineViews.OrderChangeLine;
import com.sahaj.ipd.application.OrderNoticeRepository.Cursor;
import com.sahaj.ipd.application.OrderNoticeRepository.NoticeRow;
import com.sahaj.ipd.application.OrderNoticeRepository.Stats;
import com.sahaj.ipd.application.OrderNoticeViews.NoticeCount;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNotice;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticeList;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticePage;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticePageRow;
import com.sahaj.ipd.application.OrderNoticeViews.OrderNoticeSummary;
import com.sahaj.ipd.application.OrderNoticeViews.StatLine;
import com.sahaj.ipd.application.OrderNoticeViews.SummaryAdmission;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.Fingerprint;
import com.sahaj.ipd.domain.NoticeClock;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.NoticeKind;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderRef;
import com.sahaj.ipd.domain.order.OrderTimeline;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-76-3 (ADR-027 D5): the nurse's order inbox and its acknowledgement.
 *
 * <ul>
 *   <li>The port {@link OrderNotices} for the order actions (WP1: {@link #record}), the dose path (WP3:
 *       {@link #acknowledgeByDose}) and the badges ({@link #openCounts}, {@link #openStats}); the port methods run in
 *       the caller's transaction under the admission row lock the caller already holds.</li>
 *   <li>{@code GET /admissions/{id}/order-notices} (the Orders tab), {@code GET /order-notices} (the Safety panel),
 *       {@code GET /order-notices/summary} (counts, polled every 30 seconds) and
 *       {@code POST /order-notices/{id}/acknowledge}.</li>
 * </ul>
 *
 * "Not acknowledged for N min" and the STAT line are computed at read time from the database clock and the settings in
 * force ({@link NoticeClock}); nothing is stored or sent. The ward scope guard filters every list and refuses a by-id
 * action outside the caller's scope. The acknowledgement takes the one admission row lock like every IPD write
 * (ADR-006 D4); the summary writes nothing; the two lists write one record-access row per distinct patient shown. The
 * reason of a notice is clinical text: on screen only, never logged or audited. A patient name is never logged.
 */
@Service
public class OrderNoticeService implements OrderNotices {

    static final String LIST_HIDDEN = "You cannot open the order list.";
    static final String ACK_ONLY = "Only nurses and the clinic owner can acknowledge orders.";
    static final String ENTITY_NOTICE = "IPD_ORDER_NOTICE";
    static final int ACKED_LIMIT = 100;
    private static final Set<String> LIST_ROLES = Set.of("OWNER", "NURSE", "DOCTOR");
    private static final Set<String> ACK_ROLES = Set.of("OWNER", "NURSE");
    private static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9_-]{34}$");
    private static final Set<String> LIVE = Set.of(MedicineOrderRepository.PENDING, MedicineOrderRepository.QUERIED,
            MedicineOrderRepository.ACTIVE, MedicineOrderRepository.ON_HOLD);

    private final OrderNoticeRepository notices;
    private final OrderTimelineRepository timelines;
    private final AdmissionRepository admissions;
    private final MedicineOrderRepository orders;
    private final PatientClinicalPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;
    private final IpdAudit audit;
    private final IpdGate gate;
    private final ScopeGuard guard;
    private final NursingSettingsReader settings;
    private final MedicineCatalogue catalogue;

    OrderNoticeService(OrderNoticeRepository notices, OrderTimelineRepository timelines,
                       AdmissionRepository admissions, MedicineOrderRepository orders, PatientClinicalPort patients,
                       PatientAccessPort patientAccess, ActorDirectoryPort actors, TeamMemberPort team, IpdAudit audit,
                       IpdGate gate, ScopeGuard guard, NursingSettingsReader settings, MedicineCatalogue catalogue) {
        this.notices = notices;
        this.timelines = timelines;
        this.admissions = admissions;
        this.orders = orders;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.team = team;
        this.audit = audit;
        this.gate = gate;
        this.guard = guard;
        this.settings = settings;
        this.catalogue = catalogue;
    }

    // ---- the port ----

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID tenantId, UUID admissionId, OrderRef order, NoticeKind kind, OrderPriority priority,
                       UUID actor) {
        notices.insert(tenantId, admissionId, order.id(), order.version(), kind.name(), priority.name(), actor);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int acknowledgeByDose(UUID tenantId, UUID orderId, UUID actor) {
        return notices.acknowledgeByOrder(tenantId, orderId, actor);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> openCounts(UUID tenantId, Collection<UUID> admissionIds) {
        return notices.countOpen(tenantId, admissionIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OpenCount> openStats(UUID tenantId, Collection<UUID> admissionIds, Instant asOf) {
        EffectiveNursingSettings clinic = settings.clinicDefaults(tenantId);
        Map<UUID, OpenCount> counts = new LinkedHashMap<>();
        for (Map.Entry<UUID, Stats> e : notices.stats(tenantId, admissionIds, cut(asOf, clinic.unackMinutes()),
                cut(asOf, clinic.unackStatMinutes())).entrySet()) {
            counts.put(e.getKey(), new OpenCount(e.getValue().count(), e.getValue().stat(),
                    e.getValue().overdue()));
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, AckState> ackStates(UUID tenantId, UUID admissionId) {
        return notices.findAckStates(tenantId, admissionId);
    }

    /** A notice created at or before this instant has waited more than {@code limitMinutes} whole minutes. */
    private static Instant cut(Instant asOf, int limitMinutes) {
        return asOf.minus(Duration.ofMinutes(limitMinutes + 1L));
    }

    // ---- the Orders tab of one patient ----

    /**
     * The Orders tab. Check order: role (403 "You cannot open the order list."), module Off (403), membership (403),
     * admission (404), patient (404), the scope guard, then one record-access row. Allowed in Read-only and for a
     * DISCHARGED admission (the list is read-only then). The open notices come first (STAT first, then oldest), then
     * the 100 newest acknowledged ones.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderNoticeList forAdmission(UUID tenantId, UUID actor, String role, UUID admissionId) {
        MedicineOrderService.requireRole(role, LIST_ROLES, LIST_HIDDEN);
        ModuleEntitlement ent = gate.check(tenantId, false);
        String memberRole = MedicineOrderService.membershipRole(team, tenantId, actor, LIST_ROLES, LIST_HIDDEN);
        AdmissionRow admission = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        patients.find(tenantId, admission.patientId()).orElseThrow(() -> new NotFoundException("Patient not found"));
        guard.requireAdmission(tenantId, actor, role, memberRole, admissionId, "order-notices");
        patientAccess.recordOpened(tenantId, admission.patientId(), actor);
        return list(tenantId, role, memberRole, ent, admission);
    }

    private OrderNoticeList list(UUID tenantId, String role, String memberRole, ModuleEntitlement ent,
                                 AdmissionRow admission) {
        Instant asOf = orders.databaseNow();
        EffectiveNursingSettings clinic = settings.clinicDefaults(tenantId);
        boolean writable = "trial".equals(ent.ipdState()) || "on".equals(ent.ipdState());
        boolean listCanAck = ACK_ROLES.contains(role) && ACK_ROLES.contains(memberRole) && writable
                && "ADMITTED".equals(admission.status());
        List<NoticeRow> rows = notices.findForAdmission(tenantId, admission.id(), ACKED_LIMIT);
        Map<UUID, OrderTimeline> timeline = rows.isEmpty() ? Map.of()
                : timelines.findTimelines(tenantId, List.of(admission.id()));
        List<OrderNotice> views = views(tenantId, rows, timeline, asOf, clinic, listCanAck);
        int open = 0;
        int stat = 0;
        int overdue = 0;
        for (OrderNotice v : views) {
            if (v.acknowledgedAt() == null) {
                open++;
                stat += "STAT".equals(v.priority()) ? 1 : 0;
                overdue += v.overdue() ? 1 : 0;
            }
        }
        return new OrderNoticeList(admission.id(), asOf, ent.ipdState(), admission.status(),
                new NoticeCount(open, stat, overdue), List.copyOf(views), listCanAck);
    }

    // ---- acknowledge ----

    /**
     * Acknowledge one notice. Check order: role (403), module Off (403), Read-only (409), membership (403), the notice by
     * id (404), the bare admission row locked, the notice again, the scope guard (403 outside-scope), not admitted
     * (409). Already acknowledged by the caller answers 200 with nothing written; by another user 409 order-state.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderNoticeList acknowledge(UUID tenantId, UUID actor, String role, UUID noticeId) {
        MedicineOrderService.requireRole(role, ACK_ROLES, ACK_ONLY);
        ModuleEntitlement ent = gate.check(tenantId, true);
        String memberRole = MedicineOrderService.membershipRole(team, tenantId, actor, ACK_ROLES, ACK_ONLY);
        NoticeRow first = notices.findById(tenantId, noticeId)
                .orElseThrow(() -> new NotFoundException("Notice not found"));
        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, first.admissionId())
                .orElseThrow(() -> new NotFoundException("Notice not found"));
        guard.requireAdmissionLocked(tenantId, actor, role, memberRole, admission.id(), "order-notice-acknowledge");
        NoticeRow notice = notices.findById(tenantId, noticeId)
                .orElseThrow(() -> new NotFoundException("Notice not found"));
        if (!"ADMITTED".equals(admission.status())) {
            throw OrderLocking.notAdmitted();
        }
        if (!notice.open()) {
            if (actor.equals(notice.acknowledgedBy())) {
                return list(tenantId, role, memberRole, ent, admission);
            }
            String name = actors.displayName(tenantId, notice.acknowledgedBy()).orElse("Team member");
            throw OrderLocking.stateConflict("Notice already acknowledged", name + " already acknowledged this.");
        }
        if (!notices.acknowledge(tenantId, noticeId, actor)) {
            throw new IllegalStateException("The notice was not acknowledged");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("noticeId", notice.id());
        after.put("orderId", notice.orderId());
        after.put("orderVersion", notice.orderVersion());
        after.put("kind", notice.kind());
        audit.record("IPD_ORDER_NOTICE_ACKNOWLEDGED", ENTITY_NOTICE, notice.id(), null, after);
        return list(tenantId, role, memberRole, ent, admission);
    }

    // ---- the Safety tab panel ----

    /**
     * Orders to acknowledge across patients. NURSE and OWNER only. One REPEATABLE READ transaction that writes one
     * record-access row per distinct patient of the page (names and medicines are shown). Allowed in Read-only. A scoped
     * nurse sees only the admissions of her wards and her live emergency access.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public OrderNoticePage page(UUID tenantId, UUID actor, String role, String limitText, String cursorText) {
        MedicineOrderService.requireRole(role, ACK_ROLES, ACK_ONLY);
        ModuleEntitlement ent = gate.check(tenantId, false);
        Integer limit = MedicineRules.limit(limitText);
        if (limit == null) {
            throw new InvalidInputException("limit", MedicineRules.RELOAD_MESSAGE);
        }
        Cursor after = null;
        if (cursorText != null) {
            after = decodeCursor(cursorText);
            if (after == null) {
                throw new InvalidInputException("cursor", MedicineRules.RELOAD_MESSAGE);
            }
        }
        String memberRole = MedicineOrderService.membershipRole(team, tenantId, actor, ACK_ROLES, ACK_ONLY);
        Set<UUID> visible = guard.visibleAdmissionIds(tenantId, guard.scope(tenantId, actor, role, memberRole));
        Instant asOf = orders.databaseNow();
        EffectiveNursingSettings clinic = settings.clinicDefaults(tenantId);
        boolean writable = "trial".equals(ent.ipdState()) || "on".equals(ent.ipdState());
        List<NoticeRow> found = visible != null && visible.isEmpty() ? List.of()
                : notices.findOpenPage(tenantId, visible, after, limit + 1);
        boolean more = found.size() > limit;
        List<NoticeRow> rows = more ? found.subList(0, limit) : found;
        Set<UUID> patientIds = new LinkedHashSet<>();
        Set<UUID> admissionIds = new LinkedHashSet<>();
        for (NoticeRow r : rows) {
            patientIds.add(r.patientId());
            admissionIds.add(r.admissionId());
        }
        Map<UUID, PatientClinical> patientMap = patientIds.isEmpty() ? Map.of()
                : patients.findAll(tenantId, patientIds);
        for (UUID patientId : patientIds) {
            if (patientMap.containsKey(patientId)) {
                patientAccess.recordOpened(tenantId, patientId, actor);
            }
        }
        Map<UUID, OrderTimeline> timeline = admissionIds.isEmpty() ? Map.of()
                : timelines.findTimelines(tenantId, admissionIds);
        List<OrderNotice> views = views(tenantId, rows, timeline, asOf, clinic, writable);
        List<OrderNoticePageRow> items = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PatientClinical patient = patientMap.get(rows.get(i).patientId());
            items.add(new OrderNoticePageRow(views.get(i), patient == null ? MedicineAssembler.PATIENT_FALLBACK
                    : patient.name(), rows.get(i).bedCode()));
        }
        NoticeCount total = total(notices.stats(tenantId, visible, cut(asOf, clinic.unackMinutes()),
                cut(asOf, clinic.unackStatMinutes())).values());
        String next = more && !rows.isEmpty() ? encodeCursor(rows.get(rows.size() - 1).cursor()) : null;
        return new OrderNoticePage(asOf, total, List.copyOf(items), next);
    }

    // ---- the summary ----

    /**
     * Counts only, for the 30-second poll: the total, one line per admission with an open notice, and the change token
     * (a hash of the sorted ids of the open notices in scope, computed by the database). OWNER, NURSE, DOCTOR; DOCTOR and OWNER see every ADMITTED
     * admission, a scoped NURSE only hers. Writes nothing; allowed in Read-only.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderNoticeSummary summary(UUID tenantId, UUID actor, String role) {
        MedicineOrderService.requireRole(role, LIST_ROLES, LIST_HIDDEN);
        gate.check(tenantId, false);
        String memberRole = MedicineOrderService.membershipRole(team, tenantId, actor, LIST_ROLES, LIST_HIDDEN);
        Set<UUID> visible = guard.visibleAdmissionIds(tenantId, guard.scope(tenantId, actor, role, memberRole));
        Instant asOf = orders.databaseNow();
        EffectiveNursingSettings clinic = settings.clinicDefaults(tenantId);
        Map<UUID, Stats> stats = visible != null && visible.isEmpty() ? Map.of()
                : notices.stats(tenantId, visible, cut(asOf, clinic.unackMinutes()),
                        cut(asOf, clinic.unackStatMinutes()));
        List<SummaryAdmission> byAdmission = new ArrayList<>();
        for (Map.Entry<UUID, Stats> e : stats.entrySet()) {
            Stats s = e.getValue();
            Instant dueBy = s.earliestStatSlot() == null ? null
                    : DoseSlots.statDueBy(s.earliestStatSlot(), clinic.statWindowMin());
            byAdmission.add(new SummaryAdmission(e.getKey(), s.count(), s.stat(), s.overdue(), dueBy));
        }
        String digest = visible != null && visible.isEmpty() ? OrderNoticeRepository.EMPTY_DIGEST
                : notices.openDigest(tenantId, visible);
        return new OrderNoticeSummary(asOf, total(stats.values()), List.copyOf(byAdmission), changeToken(digest));
    }

    /**
     * ADR-007 R1 over the open notices: the database hashes the sorted ids (md5, one text), this wraps it in the same
     * 22-character fingerprint. Holds no name, drug or text. It changes when a notice is added to or leaves the open set.
     */
    static String changeToken(String openDigest) {
        return Fingerprint.of(List.of(openDigest));
    }

    private static NoticeCount total(Collection<Stats> stats) {
        int count = 0;
        int stat = 0;
        int overdue = 0;
        for (Stats s : stats) {
            count += s.count();
            stat += s.stat();
            overdue += s.overdue();
        }
        return new NoticeCount(count, stat, overdue);
    }

    // ---- the views ----

    /** One view per row, in the order of the rows. The versions come from the timelines already read. */
    private List<OrderNotice> views(UUID tenantId, List<NoticeRow> rows, Map<UUID, OrderTimeline> timeline,
                                    Instant asOf, EffectiveNursingSettings clinic, boolean listCanAck) {
        Set<UUID> staff = new HashSet<>();
        for (NoticeRow r : rows) {
            staff.add(r.createdBy());
            if (r.acknowledgedBy() != null) {
                staff.add(r.acknowledgedBy());
            }
        }
        ActorNames names = ActorNames.forIds(actors, tenantId, staff);
        List<OrderNotice> views = new ArrayList<>();
        for (NoticeRow r : rows) {
            OrderTimeline t = timeline.get(r.orderId());
            if (t == null) {
                throw new IllegalStateException("A notice names an order with no version row");
            }
            MedicineOrderVersion version = t.version(r.orderVersion())
                    .orElseThrow(() -> new IllegalStateException("A notice names a missing version"));
            List<OrderChangeLine> changes = List.of();
            String reason = null;
            if (NoticeKind.CHANGED.name().equals(r.kind())) {
                changes = t.version(r.orderVersion() - 1).map(prev -> OrderViews.changes(prev, version))
                        .orElse(List.of());
                reason = version.changeReason();
            } else if (NoticeKind.HELD.name().equals(r.kind())) {
                reason = holdReason(t, r.createdAt());
            } else if (NoticeKind.STOPPED.name().equals(r.kind())) {
                reason = r.stopReason();
            }
            boolean open = r.open();
            boolean statNotice = "STAT".equals(r.priority());
            Integer waiting = open ? NoticeClock.waitingMinutes(r.createdAt(), asOf) : null;
            boolean overdue = open && NoticeClock.overdue(r.createdAt(), statNotice, clinic.unackMinutes(),
                    clinic.unackStatMinutes(), asOf);
            StatLine stat = statLine(r, version, asOf, clinic);
            views.add(new OrderNotice(r.id(), r.admissionId(), r.orderId(), r.orderVersion(), r.kind(), r.priority(),
                    r.drugKey(), catalogue.catalogue().find(r.drugKey()).map(d -> d.name())
                            .orElseThrow(() -> new IllegalStateException("A notice names a medicine not in the catalogue")),
                    OrderViews.details(version), changes, reason, r.createdAt(), names.of(r.createdBy()),
                    r.acknowledgedAt(), r.acknowledgedBy() == null ? null : names.of(r.acknowledgedBy()), waiting,
                    overdue, stat, listCanAck && open && "ADMITTED".equals(r.admissionStatus())));
        }
        return views;
    }

    /** The hold whose start is the latest one not after the notice: the notice is written right after the hold. */
    private static String holdReason(OrderTimeline timeline, Instant noticeAt) {
        String reason = null;
        for (OrderHoldInterval h : timeline.holds()) {
            if (!h.heldAt().isAfter(noticeAt)) {
                reason = h.holdReason();
            }
        }
        return reason;
    }

    /** For a STAT (one-time) version still current, not yet given or refused and not stopped: the countdown line. */
    private static StatLine statLine(NoticeRow r, MedicineOrderVersion version, Instant asOf,
                                     EffectiveNursingSettings clinic) {
        boolean stat = version.kind() == Kind.ONCE && version.priority() == OrderPriority.STAT
                && version.versionNo() == r.orderCurrentVersion() && !r.hasLiveDose()
                && LIVE.contains(r.orderStatus());
        if (!stat) {
            return null;
        }
        NoticeClock.StatLine line = NoticeClock.statLine(DoseSlots.onceSlot(version),
                clinic.statWindowMin(), asOf);
        return new StatLine(line.dueBy(), line.windowMinutes(), line.minutesLeft(), line.minutesOverdue());
    }

    // ---- the cursor ----

    /** One byte of rank, 8 of time (epoch microseconds) and 16 of id: 34 characters of base64url, not signed. */
    static String encodeCursor(Cursor cursor) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, cursor.createdAt());
        ByteBuffer bytes = ByteBuffer.allocate(25);
        bytes.put((byte) cursor.rank()).putLong(micros).putLong(cursor.id().getMostSignificantBits())
                .putLong(cursor.id().getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** The cursor, or null when the text is not one this server made. */
    static Cursor decodeCursor(String text) {
        if (text == null || !CURSOR.matcher(text).matches()) {
            return null;
        }
        ByteBuffer bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(text));
        int rank = bytes.get();
        long micros = bytes.getLong();
        if ((rank != 0 && rank != 1) || micros < 0) {
            return null;
        }
        return new Cursor(rank, Instant.EPOCH.plus(micros, ChronoUnit.MICROS), new UUID(bytes.getLong(), bytes.getLong()));
    }

}
