package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.BedBoardViews.BedBoard;
import com.sahaj.ipd.application.BedBoardViews.BoardBed;
import com.sahaj.ipd.application.BedBoardViews.BoardRoom;
import com.sahaj.ipd.application.BedBoardViews.BoardWard;
import com.sahaj.ipd.application.BedBoardViews.Counts;
import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.DischargeRepository.SignoffRow;
import com.sahaj.ipd.application.IpdBilling.Batch;
import com.sahaj.ipd.application.IpdSettingsRepository.CategoryRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.WardRepository.RoomRow;
import com.sahaj.ipd.application.WardRepository.WardRow;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-24 bed board. Reads the whole board in one call (patients and doctors are looked up in one batch each) and
 * moves a bed between Cleaning, Blocked and Available. State changes take the clinic's IPD advisory lock, then lock
 * the bed row. Audit rows carry the bed id, code and the old and new status only; patient names are never logged.
 */
@Service
public class BedBoardService {

    private static final Set<String> CHANGE_ROLES = AdmissionService.FLOOR_ROLES;
    private static final Map<String, String> LABELS = Map.of("free", "Available", "occ", "Occupied",
            "clean", "Cleaning", "res", "Reserved", "blk", "Blocked");

    private final BedRepository beds;
    private final WardRepository wards;
    private final AdmissionRepository admissions;
    private final AuditLog auditLog;
    private final PatientLookupPort patients;
    private final TeamMemberPort team;
    private final IpdGate gate;
    private final IpdCatalog catalog;
    private final IpdBilling billing;
    private final DischargeRepository discharges;

    BedBoardService(BedRepository beds, WardRepository wards, AdmissionRepository admissions, AuditLog auditLog,
                    PatientLookupPort patients, TeamMemberPort team, IpdGate gate, IpdCatalog catalog,
                    IpdBilling billing, DischargeRepository discharges) {
        this.discharges = discharges;
        this.billing = billing;
        this.beds = beds;
        this.wards = wards;
        this.admissions = admissions;
        this.auditLog = auditLog;
        this.patients = patients;
        this.team = team;
        this.gate = gate;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public BedBoard board(UUID tenantId, String role) {
        return build(tenantId, role, gate.check(tenantId, false));
    }

    /** Cleaning or Blocked to Available. */
    @Transactional
    public BedBoard markReady(UUID tenantId, String role, UUID bedId) {
        return change(tenantId, role, bedId, Set.of("clean", "blk"), "free", "IPD_BED_READY");
    }

    /** Cleaning to Blocked. */
    @Transactional
    public BedBoard block(UUID tenantId, String role, UUID bedId) {
        return change(tenantId, role, bedId, Set.of("clean"), "blk", "IPD_BED_BLOCKED");
    }

    private BedBoard change(UUID tenantId, String role, UUID bedId, Set<String> allowedFrom, String to,
                            String action) {
        // Defence in depth (security review L1): the controller already checks the role, so a caller that skips
        // it, or a token that outlived a demotion, still cannot change a bed.
        if (role == null || !CHANGE_ROLES.contains(role)) {
            throw new AccessDeniedException("You cannot change a bed.");
        }
        ModuleEntitlement ent = gate.check(tenantId, true);
        wards.lockTenant(tenantId);
        BedRow bed = beds.findForUpdate(tenantId, bedId)
                .orElseThrow(() -> new NotFoundException("Bed not found"));
        if (!allowedFrom.contains(bed.status())) {
            throw new ConflictException("bed-state", "Bed cannot be changed",
                    "This bed cannot be changed from " + label(bed.status()) + ".");
        }
        beds.updateStatus(tenantId, bedId, to);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("bedId", bed.id());
        after.put("bedCode", bed.code());
        after.put("from", bed.status());
        after.put("to", to);
        auditLog.record(action, "IPD_BED", bed.id(), null, after);
        return build(tenantId, role, ent);
    }

    private BedBoard build(UUID tenantId, String role, ModuleEntitlement ent) {
        String state = ent.ipdState();
        ConfigRow config = catalog.config(tenantId);
        boolean roomLevel = config.roomLevel();
        List<WardRow> wardRows = wards.findWards(tenantId);

        List<RoomRow> roomRows = wards.findRooms(tenantId);
        Map<UUID, RoomRow> roomById = new HashMap<>();
        for (RoomRow room : roomRows) {
            roomById.put(room.id(), room);
        }
        List<BedRow> bedRows = new ArrayList<>(beds.findAll(tenantId));
        bedRows.sort(BedOrder.byRoomThenCode(roomRows));

        Map<UUID, AdmissionRow> byBed = new HashMap<>();
        Set<UUID> patientIds = new HashSet<>();
        Set<UUID> doctorIds = new HashSet<>();
        for (AdmissionRow row : admissions.findAdmitted(tenantId)) {
            byBed.put(row.bedId(), row);
            patientIds.add(row.patientId());
            doctorIds.add(row.doctorMembershipId());
        }
        Map<UUID, PatientSummary> patientMap = patientIds.isEmpty() ? Map.of()
                : patients.findAll(tenantId, patientIds);
        Map<UUID, TeamMember> doctorMap = doctorIds.isEmpty() ? Map.of() : team.findAll(tenantId, doctorIds);

        // The settings, categories and deposit rules are read once per board and shared with the bill batch.
        Map<String, CategoryRow> categories = catalog.categories(tenantId);

        Instant now = gate.now();
        // SAH-25: one query for the charges of all admitted patients; the deposit sums come with the rows.
        Batch batch = billing.forAdmitted(tenantId, now, catalog.depositRules(tenantId).alertPercent(), categories,
                config);
        // SAH-27: the latest discharge sign-off of every admitted patient, in one query.
        Map<UUID, SignoffRow> signoffs = discharges.findLatestSignoffsOfAdmitted(tenantId);
        LocalDate today = DischargeRules.istDate(now);
        int[] counts = new int[5];
        List<BoardWard> wardViews = new ArrayList<>();
        for (WardRow ward : wardRows) {
            List<BoardBed> wardBeds = new ArrayList<>();
            Map<UUID, List<BoardBed>> byRoom = new LinkedHashMap<>();
            List<BoardBed> noRoom = new ArrayList<>();
            int occupied = 0;
            UUID firstFree = null;
            for (BedRow bed : bedRows) {
                if (!ward.id().equals(bed.wardId())) {
                    continue;
                }
                BoardBed view = toView(bed, categories.get(bed.categoryKey()), byBed.get(bed.id()), patientMap,
                        doctorMap, batch, signoffs, today);
                wardBeds.add(view);
                counts[index(bed.status())]++;
                if ("occ".equals(bed.status())) {
                    occupied++;
                }
                if (firstFree == null && "free".equals(bed.status())) {
                    firstFree = bed.id();
                }
                if (roomLevel && bed.roomId() != null && roomById.containsKey(bed.roomId())) {
                    byRoom.computeIfAbsent(bed.roomId(), k -> new ArrayList<>()).add(view);
                } else {
                    noRoom.add(view);
                }
            }
            List<BoardRoom> rooms = new ArrayList<>();
            if (roomLevel) {
                for (Map.Entry<UUID, List<BoardBed>> e : byRoom.entrySet()) {
                    rooms.add(new BoardRoom(e.getKey(), roomById.get(e.getKey()).name(), List.copyOf(e.getValue())));
                }
                if (!noRoom.isEmpty()) {
                    rooms.add(new BoardRoom(null, "", List.copyOf(noRoom)));
                }
            } else {
                rooms.add(new BoardRoom(null, "", List.copyOf(wardBeds)));
            }
            wardViews.add(new BoardWard(ward.id(), ward.name(), ward.station(), occupied, wardBeds.size(),
                    firstFree != null, firstFree, List.copyOf(rooms)));
        }
        boolean can = CHANGE_ROLES.contains(role) && !"readonly".equals(state);
        return new BedBoard(state, roomLevel, can, can,
                new Counts(counts[0], counts[1], counts[2], counts[3], counts[4]), List.copyOf(wardViews));
    }

    private BoardBed toView(BedRow bed, CategoryRow category, AdmissionRow admission,
                            Map<UUID, PatientSummary> patientMap, Map<UUID, TeamMember> doctorMap, Batch batch,
                            Map<UUID, SignoffRow> signoffs, LocalDate today) {
        long rate = category == null ? 0L : category.ratePaise();
        String name = IpdService.NAMES.get(bed.categoryKey());
        if (!"occ".equals(bed.status()) || admission == null) {
            return new BoardBed(bed.id(), bed.code(), bed.categoryKey(), name, rate, bed.status(), null, null, null,
                    null, null, null);
        }
        PatientSummary patient = patientMap.get(admission.patientId());
        TeamMember doctor = doctorMap.get(admission.doctorMembershipId());
        String doctorName = doctor == null ? "Doctor" : doctor.name();
        RunningBill bill = batch.bill(admission, doctorName);
        // One day number everywhere: the bill days of the clinic's bed-day cut (code review 3).
        return new BoardBed(bed.id(), bed.code(), bed.categoryKey(), name, rate, bed.status(), admission.id(),
                patient == null ? "Patient" : patient.name(), bill.billDays(), shortName(doctorName),
                bill.depositLow(admission.payer()),
                DischargeRules.goingHomeToday(signoffs.get(admission.id()), today));
    }

    static String shortName(String name) {
        String[] words = name.trim().split("\\s+");
        return words.length <= 2 ? String.join(" ", words) : words[0] + " " + words[1];
    }

    private static int index(String status) {
        switch (status) {
            case "free":
                return 0;
            case "occ":
                return 1;
            case "clean":
                return 2;
            case "res":
                return 3;
            case "blk":
                return 4;
            default:
                throw new IllegalStateException("Unknown bed status " + status);
        }
    }

    private static String label(String status) {
        return LABELS.getOrDefault(status, status);
    }
}
