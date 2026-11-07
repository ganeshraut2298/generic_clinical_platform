package com.sahaj.ipd.application;

import com.sahaj.ipd.application.ClinicalEventRepository.EventRow;
import com.sahaj.ipd.application.ClinicalEventRepository.Position;
import com.sahaj.ipd.application.EscalationBoardRepository.BoardRow;
import com.sahaj.ipd.application.EscalationBoardRepository.Detail;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.application.SafetyRows.Access;
import com.sahaj.ipd.application.SafetyViews.ActionOption;
import com.sahaj.ipd.application.SafetyViews.Banner;
import com.sahaj.ipd.application.SafetyViews.BannerItem;
import com.sahaj.ipd.application.SafetyViews.Board;
import com.sahaj.ipd.application.SafetyViews.Counts;
import com.sahaj.ipd.application.SafetyViews.Event;
import com.sahaj.ipd.application.SafetyViews.EventPage;
import com.sahaj.ipd.application.SafetyViews.RoundPanel;
import com.sahaj.ipd.application.SafetyViews.RoundRow;
import com.sahaj.ipd.application.SafetyViews.Row;
import com.sahaj.ipd.domain.MedicineRound;
import com.sahaj.ipd.domain.ResponseAction;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-33 the reads of the Safety board (ADR-007 D8, D13): the board, the counts-only summary, the banner of the
 * Admitted patients tab and the clinical audit trail. Every call first re-checks the role in the token, then the IPD
 * state (Off answers 403 module-off; Read-only is allowed, DEC-015), then reads the role again from the caller's active
 * membership, so a role change applies at once. Each read runs in one REPEATABLE READ transaction, so the counts, the
 * notice and the rows of one answer agree, and every minute count and "past target" is taken against one database
 * clock reading ({@code asOf}), never the application or browser clock.
 *
 * <p>A read costs a fixed number of queries whatever the number of rows: one for the escalations (patient id and bed
 * code come with them), one for the patients, one for the staff names. Writes nothing: no audit and no
 * record-access row (DEC-015). The answers carry names and clinical text; they are never logged.
 */
@Service
public class SafetyService {

    static final int DEFAULT_LIMIT = 14;
    static final int MAX_LIMIT = 50;
    private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,3}$");
    private static final String PATIENT_FALLBACK = "Patient";

    private final EscalationBoardRepository board;
    private final AdmissionRepository admissions;
    private final ClinicalEventRepository events;
    private final PatientLookupPort patients;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;
    private final IpdGate gate;
    private final MedicineRoundReader round;

    SafetyService(EscalationBoardRepository board, AdmissionRepository admissions, ClinicalEventRepository events,
                  PatientLookupPort patients, ActorDirectoryPort actors, TeamMemberPort team, IpdGate gate,
                  MedicineRoundReader round) {
        this.board = board;
        this.admissions = admissions;
        this.events = events;
        this.patients = patients;
        this.actors = actors;
        this.team = team;
        this.gate = gate;
        this.round = round;
    }

    // ---- the board ----

    /**
     * The Safety board. Check order: role, module Off, membership role (all 403). Rows: every open and acknowledged
     * escalation of every ADMITTED admission, and every closed one closed within the 24 hours before {@code asOf}
     * (ADR-007 R5; older closed ones are in the audit trail only).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Board board(UUID tenantId, UUID actor, String role) {
        SafetySupport.requireRole(role, EscalationRules.READ_ROLES, "You cannot open the safety board.");
        ModuleEntitlement ent = gate.check(tenantId, false);
        String memberRole = SafetySupport.membershipRole(team, tenantId, actor, EscalationRules.READ_ROLES,
                "You cannot open the safety board.");
        Instant asOf = board.databaseNow();
        List<BoardRow> found = new ArrayList<>(board.findOnBoard(tenantId, asOf));
        found.sort((a, b) -> SafetyRows.ORDER.compare(a.escalation(), b.escalation()));

        // SAH-35: the Medicine round in the same transaction and against the same asOf (two statements).
        MedicineRound.Result doses = round.read(tenantId, asOf);
        Map<UUID, PatientSummary> patientMap = patientsOf(tenantId, found, doses);
        List<Detail> details = new ArrayList<>();
        for (BoardRow row : found) {
            details.add(row.escalation());
        }
        ActorNames names = ActorNames.forEscalations(actors, tenantId, details);
        Access access = new Access(role, memberRole);
        List<Row> rows = new ArrayList<>();
        for (BoardRow row : found) {
            rows.add(SafetyRows.row(row.escalation(), patientName(patientMap, row.patientId()), row.bedCode(), names,
                    asOf, access));
        }
        boolean nurse = SafetySupport.isNurseView(memberRole);
        Integer yourPatients = nurse ? admissions.countAdmitted(tenantId) : null;
        return new Board(ent.ipdState(), nurse ? "NURSE" : "DOCTOR", asOf, SafetyRows.counts(details, doses, asOf),
                yourPatients, actionOptions(), List.copyOf(rows), roundPanel(doses, access, ent.ipdState(), patientMap));
    }

    /**
     * SAH-35 (ADR-012 D6): the panel. {@code canGive} needs the role in the token and the role of the membership both
     * NURSE or OWNER, and IPD Trial or On; {@code giveLockReason} is then ROLE first, else IPD_READONLY. A patient
     * whose record is not found is shown as "Patient": a row is never dropped.
     */
    static RoundPanel roundPanel(MedicineRound.Result doses, Access access, String ipdState,
                                 Map<UUID, PatientSummary> patientMap) {
        boolean roleCanGive = DoseRules.GIVE_ROLES.contains(access.tokenRole())
                && DoseRules.GIVE_ROLES.contains(access.memberRole());
        boolean writable = "trial".equals(ipdState) || "on".equals(ipdState);
        boolean canGive = roleCanGive && writable;
        String lock = canGive ? null : !roleCanGive ? "ROLE" : "IPD_READONLY";
        List<RoundRow> rows = new ArrayList<>();
        for (MedicineRound.Entry e : doses.rows()) {
            rows.add(new RoundRow(e.admissionId(), e.orderId(), e.slotAt(), e.state(), e.lateHours(), e.drugName(),
                    e.highAlert(), patientName(patientMap, e.patientId()), e.bedCode()));
        }
        return new RoundPanel(canGive, lock, doses.truncated(), List.copyOf(rows));
    }

    /** Counts only: no names, no scores, no text (ADR-005 D3). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Counts summary(UUID tenantId, UUID actor, String role) {
        SafetySupport.requireRole(role, EscalationRules.READ_ROLES, "You cannot open the safety board.");
        gate.check(tenantId, false);
        SafetySupport.membershipRole(team, tenantId, actor, EscalationRules.READ_ROLES,
                "You cannot open the safety board.");
        Instant asOf = board.databaseNow();
        return countsOf(board.findLiveOnBoard(tenantId), round.read(tenantId, asOf), asOf);
    }

    /**
     * SAH-36 (ADR-013 D2): the live (OPEN and ACK) escalations of the clinic in the board order, one query. The Admitted
     * list reads them once and hands the same rows to the banner, the counts and the card flags.
     */
    List<BoardRow> liveEscalations(UUID tenantId) {
        List<BoardRow> live = new ArrayList<>(board.findLiveOnBoard(tenantId));
        live.sort((a, b) -> SafetyRows.ORDER.compare(a.escalation(), b.escalation()));
        return live;
    }

    /**
     * The banner of already-read rows, for a caller whose role and membership the caller has checked. {@code live} is in
     * the board order; {@code nameOf} gives the name shown for a row's patient.
     */
    Banner banner(UUID tenantId, Instant asOf, List<BoardRow> live, Function<BoardRow, String> nameOf) {
        List<BannerItem> past = new ArrayList<>();
        List<Detail> liveDetails = new ArrayList<>();
        for (BoardRow row : live) {
            Detail d = row.escalation();
            liveDetails.add(d);
            if (SafetyRows.pastTarget(d, asOf)) {
                past.add(new BannerItem(d.id(), d.admissionId(), nameOf.apply(row), d.ewsScore()));
            }
        }
        // SAH-35: the dose counts and the dose lines of the token, from the same function as the board and the summary.
        return new Banner(SafetyRows.counts(liveDetails, round.read(tenantId, asOf), asOf), List.copyOf(past));
    }

    /** SAH-36: the database clock for an answer that does not go through the board (the Admitted list). */
    Instant databaseNow() {
        return board.databaseNow();
    }

    private static Counts countsOf(List<BoardRow> live, MedicineRound.Result doses, Instant asOf) {
        List<Detail> details = new ArrayList<>();
        for (BoardRow row : live) {
            details.add(row.escalation());
        }
        return SafetyRows.counts(details, doses, asOf);
    }

    private static List<ActionOption> actionOptions() {
        List<ActionOption> options = new ArrayList<>();
        for (ResponseAction action : ResponseAction.doctorChoices()) {
            options.add(new ActionOption(action.name(), action.label()));
        }
        return List.copyOf(options);
    }

    // ---- the clinical audit trail ----

    /**
     * One page of the clinical event log, newest first. OWNER and DOCTOR read every entry of the clinic; NURSE her own
     * entries and the entries of admitted patients. {@code limit} is a whole number 1 to 50 (default 14); {@code cursor}
     * is the {@code nextCursor} of the page before it. Check order: role, module Off, membership role (403), then
     * limit and cursor (400). Names are read when the page is built, never stored in an entry.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventPage events(UUID tenantId, UUID actor, String role, String limitText, String cursorText) {
        SafetySupport.requireRole(role, EscalationRules.READ_ROLES, "You cannot open the audit trail.");
        gate.check(tenantId, false);
        String memberRole = SafetySupport.membershipRole(team, tenantId, actor, EscalationRules.READ_ROLES,
                "You cannot open the audit trail.");
        int limit = limit(limitText);
        Position after = null;
        if (cursorText != null) {
            after = EventCursor.decode(cursorText);
            if (after == null) {
                throw new InvalidInputException("cursor", EscalationRules.RELOAD_MESSAGE);
            }
        }
        Instant asOf = board.databaseNow();
        UUID onlyFor = SafetySupport.isNurseView(memberRole) ? actor : null;
        List<EventRow> found = events.findPage(tenantId, onlyFor, after, limit + 1);
        boolean more = found.size() > limit;
        List<EventRow> page = more ? found.subList(0, limit) : found;

        Set<UUID> patientIds = new HashSet<>();
        Set<UUID> actorIds = new HashSet<>();
        for (EventRow row : page) {
            actorIds.add(row.actor());
            if (row.patientId() != null) {
                patientIds.add(row.patientId());
            }
        }
        Map<UUID, PatientSummary> patientMap = patientIds.isEmpty() ? Map.of()
                : patients.findAll(tenantId, patientIds);
        ActorNames names = ActorNames.forIds(actors, tenantId, actorIds);
        List<Event> items = new ArrayList<>();
        for (EventRow row : page) {
            boolean admission = row.admissionId() != null;
            items.add(new Event(row.id(), row.kind(), row.text(), row.at(), names.of(row.actor()), row.admissionId(),
                    admission ? patientName(patientMap, row.patientId()) : null,
                    admission ? row.bedCode() : null));
        }
        String next = more && !page.isEmpty() ? EventCursor.encode(page.get(page.size() - 1).position()) : null;
        return new EventPage(asOf, List.copyOf(items), next);
    }

    private static int limit(String text) {
        if (text == null) {
            return DEFAULT_LIMIT;
        }
        if (!WHOLE.matcher(text).matches()) {
            throw new InvalidInputException("limit", EscalationRules.RELOAD_MESSAGE);
        }
        int value = Integer.parseInt(text);
        if (value < 1 || value > MAX_LIMIT) {
            throw new InvalidInputException("limit", EscalationRules.RELOAD_MESSAGE);
        }
        return value;
    }

    // ---- helpers ----

    /** One lookup for the escalation rows and the listed (at most 200) dose rows of the answer. */
    private Map<UUID, PatientSummary> patientsOf(UUID tenantId, List<BoardRow> rows, MedicineRound.Result doses) {
        Set<UUID> ids = new HashSet<>();
        for (BoardRow row : rows) {
            ids.add(row.patientId());
        }
        for (MedicineRound.Entry entry : doses.rows()) {
            ids.add(entry.patientId());
        }
        return ids.isEmpty() ? Map.of() : patients.findAll(tenantId, ids);
    }

    /** A patient the record cannot be found for is shown as "Patient": a safety board never drops a row. */
    private static String patientName(Map<UUID, PatientSummary> patients, UUID patientId) {
        PatientSummary patient = patientId == null ? null : patients.get(patientId);
        return patient == null ? PATIENT_FALLBACK : patient.name();
    }
}
