package com.sahaj.ipd.application;

import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.IpdSettingsRepository.CategoryRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.WardRepository.RoomRow;
import com.sahaj.ipd.application.WardRepository.WardRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-22 IPD setup: wards, rooms, beds, bed categories and charges. Every call first checks the IPD state
 * (Off answers 403 module-off; any write in Read-only answers 409 ipd-readonly). Only the owner writes
 * (re-read through {@link OwnerGuard}). Every write takes a per-clinic advisory lock so that bed codes, the
 * bed limit, ward names and room numbers stay correct under concurrent requests. Categories and settings are
 * lazy: absent rows are synthesized with the defaults and written on first change. Audit rows carry ids,
 * names of wards, rooms and beds and settings only.
 */
@Service
public class IpdService {

    static final long MAX_PAISE = 10_000_000L;
    static final long RUPEE = 100L;
    static final BigDecimal MIN_MULTIPLIER = new BigDecimal("0.5");
    static final BigDecimal MAX_MULTIPLIER = new BigDecimal("5");
    static final ConfigRow DEFAULT_CONFIG = new ConfigRow(true, "midnight", 30_000L, 50_000L);

    private static final Pattern DIGITS = Pattern.compile("\\d{1,9}");
    private static final Pattern CAPITALS = Pattern.compile("[A-Z]");
    private static final int ROOM_NAME_MAX = 40;
    private static final Set<String> DAY_CUTS = Set.of("midnight", "24h");
    static final List<String> KEYS = List.of("gen", "semi", "pvt", "icu");
    static final Map<String, String> NAMES = Map.of(
            "gen", "General", "semi", "Semi-private", "pvt", "Private", "icu", "ICU");
    static final Map<String, CategoryRow> DEFAULTS = Map.of(
            "gen", new CategoryRow("gen", 80_000L, new BigDecimal("1.00")),
            "semi", new CategoryRow("semi", 150_000L, new BigDecimal("1.25")),
            "pvt", new CategoryRow("pvt", 250_000L, new BigDecimal("1.50")),
            "icu", new CategoryRow("icu", 500_000L, new BigDecimal("2.00")));

    private final WardRepository wards;
    private final BedRepository beds;
    private final IpdSettingsRepository settings;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final IpdGate gate;
    private final IpdCatalog catalog;

    IpdService(WardRepository wards, BedRepository beds, IpdSettingsRepository settings, OwnerGuard ownerGuard,
               AuditLog auditLog, IpdGate gate, IpdCatalog catalog) {
        this.wards = wards;
        this.beds = beds;
        this.settings = settings;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.gate = gate;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public IpdLayout layout(UUID tenantId) {
        return buildLayout(tenantId, gate.check(tenantId, false));
    }

    @Transactional
    public IpdLayout addWard(UUID tenantId, UUID actor, String rawName) {
        ModuleEntitlement ent = beginWrite(tenantId, actor);
        // NFC first, then the raw text is checked before it is trimmed, so that stripping cannot hide a control or
        // text-direction character at an end (security review I5, L1).
        String raw = rawName == null ? "" : IpdText.nfc(rawName);
        if (IpdText.hasControlOrBidi(raw)) {
            throw new InvalidInputException("name", "Ward name cannot contain control or text-direction characters");
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            throw new InvalidInputException("name", "Type a ward name first");
        }
        if (name.length() > 80) {
            throw new InvalidInputException("name", "Ward name can be at most 80 characters");
        }
        if (wards.wardNameExists(tenantId, name)) {
            throw new ConflictException("ward-exists", "Ward already exists", "A ward with this name already exists.");
        }
        Instant now = now();
        UUID wardId = UUID.randomUUID();
        wards.insertWard(tenantId, wardId, name, actor, now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("station", "Nursing station 1");
        auditLog.record("IPD_WARD_ADDED", "IPD_WARD", wardId, null, after);
        if (config(tenantId).roomLevel()) {
            int wardCount = wards.findWards(tenantId).size();
            insertRoomAudited(tenantId, actor, wardId, "Room " + (100 * wardCount + 1), now);
        }
        return buildLayout(tenantId, ent);
    }

    @Transactional
    public IpdLayout addRoom(UUID tenantId, UUID actor, UUID wardId) {
        ModuleEntitlement ent = beginWrite(tenantId, actor);
        List<WardRow> allWards = wards.findWards(tenantId);
        int position = -1;
        for (int i = 0; i < allWards.size(); i++) {
            if (allWards.get(i).id().equals(wardId)) {
                position = i + 1;
            }
        }
        if (position < 0) {
            throw new NotFoundException("Ward not found");
        }
        int highest = -1;
        for (RoomRow room : wards.findRooms(tenantId)) {
            if (room.wardId().equals(wardId)) {
                highest = Math.max(highest, firstNumber(room.name()));
            }
        }
        long number = highest >= 0 ? highest + 1L : 100L * position + 1;
        insertRoomAudited(tenantId, actor, wardId, "Room " + number, now());
        return buildLayout(tenantId, ent);
    }

    @Transactional
    public IpdLayout addBed(UUID tenantId, UUID actor, UUID wardId, UUID roomId) {
        ModuleEntitlement ent = beginWrite(tenantId, actor);
        if (wardId == null) {
            throw new InvalidInputException("wardId", "Choose the ward");
        }
        WardRow ward = wards.findWard(tenantId, wardId).orElseThrow(() -> new NotFoundException("Ward not found"));
        RoomRow room = null;
        if (roomId != null) {
            room = wards.findRoom(tenantId, roomId).filter(r -> r.wardId().equals(wardId))
                    .orElseThrow(() -> new NotFoundException("Room not found"));
        }
        List<BedRow> existing = beds.findAll(tenantId);
        if (existing.size() >= ent.bedLimit()) {
            throw new ConflictException("bed-limit", "Bed limit reached",
                    "Your plan allows " + ent.bedLimit() + " beds. Ask Sahaj to raise the limit.");
        }
        Instant now = now();
        if (room == null && config(tenantId).roomLevel()) {
            room = firstRoomOrCreate(tenantId, actor, ward, now);
        }
        String prefix = room == null ? null : digitsOf(room.name());
        if (prefix == null) {
            prefix = capitals(ward.name());
        }
        String code = prefix + "-" + freeLetters(existing);
        String category = defaultCategory(ward.name());
        UUID bedId = UUID.randomUUID();
        UUID bedRoomId = room == null ? null : room.id();
        beds.insert(tenantId, bedId, wardId, bedRoomId, code, category, actor, now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", code);
        after.put("wardId", wardId);
        after.put("roomId", bedRoomId);
        after.put("categoryKey", category);
        auditLog.record("IPD_BED_ADDED", "IPD_BED", bedId, null, after);
        return buildLayout(tenantId, ent);
    }

    @Transactional
    public IpdLayout changeBedCategory(UUID tenantId, UUID actor, UUID bedId, String categoryKey) {
        ModuleEntitlement ent = beginWrite(tenantId, actor);
        if (categoryKey == null || !KEYS.contains(categoryKey)) {
            throw new InvalidInputException("categoryKey", "Choose General, Semi-private, Private or ICU");
        }
        BedRow bed = beds.find(tenantId, bedId).orElseThrow(() -> new NotFoundException("Bed not found"));
        if (!bed.categoryKey().equals(categoryKey)) {
            beds.updateCategory(tenantId, bedId, categoryKey);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("code", bed.code());
            before.put("categoryKey", bed.categoryKey());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("code", bed.code());
            after.put("categoryKey", categoryKey);
            auditLog.record("IPD_BED_CATEGORY_CHANGED", "IPD_BED", bedId, before, after);
        }
        return buildLayout(tenantId, ent);
    }

    @Transactional
    public IpdLayout removeBed(UUID tenantId, UUID actor, UUID bedId) {
        ModuleEntitlement ent = beginWrite(tenantId, actor);
        BedRow bed = beds.find(tenantId, bedId).orElseThrow(() -> new NotFoundException("Bed not found"));
        if ("occ".equals(bed.status())) {
            throw new ConflictException("bed-occupied", "Bed is occupied", "A patient is in this bed.");
        }
        if (beds.hasAdmissionHistory(tenantId, bedId)) {
            throw bedHasHistory();
        }
        try {
            beds.delete(tenantId, bedId);
        } catch (DataIntegrityViolationException e) {
            throw bedHasHistory();
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("code", bed.code());
        before.put("wardId", bed.wardId());
        before.put("roomId", bed.roomId());
        before.put("categoryKey", bed.categoryKey());
        auditLog.record("IPD_BED_REMOVED", "IPD_BED", bedId, before, null);
        return buildLayout(tenantId, ent);
    }

    @Transactional
    public IpdLayout setRoomLevel(UUID tenantId, UUID actor, Boolean on) {
        ModuleEntitlement ent = beginWrite(tenantId, actor);
        if (on == null) {
            throw new InvalidInputException("on", "Choose on or off");
        }
        ConfigRow current = config(tenantId);
        if (current.roomLevel() != on) {
            settings.upsertConfig(tenantId,
                    new ConfigRow(on, current.bedDayCut(), current.nursingPaise(), current.visitPaise()), actor, now());
            auditLog.record("IPD_ROOM_LEVEL_CHANGED", "IPD_CONFIG", null,
                    Map.of("roomLevel", current.roomLevel()), Map.of("roomLevel", on));
        }
        return buildLayout(tenantId, ent);
    }

    @Transactional
    public IpdLayout updateCategory(UUID tenantId, UUID actor, String key, Long ratePaise, BigDecimal multiplier) {
        ModuleEntitlement ent = beginWrite(tenantId, actor);
        if (key == null || !KEYS.contains(key)) {
            throw new NotFoundException("Bed category not found");
        }
        if (ratePaise == null || ratePaise < 0 || ratePaise > MAX_PAISE) {
            throw new InvalidInputException("ratePaise", "Enter a bed charge from ₹0 to ₹1,00,000");
        }
        if (ratePaise % RUPEE != 0) {
            throw new InvalidInputException("ratePaise", "Enter whole rupees");
        }
        if (multiplier == null || multiplier.compareTo(MIN_MULTIPLIER) < 0 || multiplier.compareTo(MAX_MULTIPLIER) > 0
                || multiplier.stripTrailingZeros().scale() > 2) {
            throw new InvalidInputException("multiplier", "Enter a rate from 0.5 to 5, with up to two decimals");
        }
        CategoryRow current = categories(tenantId).get(key);
        BigDecimal scaled = multiplier.setScale(2);
        if (current.ratePaise() != ratePaise || current.multiplier().compareTo(scaled) != 0) {
            settings.upsertCategory(tenantId, key, ratePaise, scaled, actor, now());
            auditLog.record("IPD_CATEGORY_CHARGE_CHANGED", "IPD_BED_CATEGORY", null,
                    categorySnapshot(current.key(), current.ratePaise(), current.multiplier()),
                    categorySnapshot(key, ratePaise, scaled));
        }
        return buildLayout(tenantId, ent);
    }

    @Transactional
    public IpdLayout updateCharges(UUID tenantId, UUID actor, String bedDayCut, Long nursingPaise, Long visitPaise) {
        ModuleEntitlement ent = beginWrite(tenantId, actor);
        if (bedDayCut == null || !DAY_CUTS.contains(bedDayCut)) {
            throw new InvalidInputException("bedDayCut", "Choose At midnight or Every 24 hours");
        }
        if (nursingPaise == null || nursingPaise < 0 || nursingPaise > MAX_PAISE) {
            throw new InvalidInputException("nursingPaise", "Enter a nursing charge from ₹0 to ₹1,00,000");
        }
        if (visitPaise == null || visitPaise < 0 || visitPaise > MAX_PAISE) {
            throw new InvalidInputException("visitPaise", "Enter a doctor visit charge from ₹0 to ₹1,00,000");
        }
        ConfigRow current = config(tenantId);
        ConfigRow next = new ConfigRow(current.roomLevel(), bedDayCut, nursingPaise, visitPaise);
        if (!next.equals(current)) {
            settings.upsertConfig(tenantId, next, actor, now());
            auditLog.record("IPD_CHARGES_CHANGED", "IPD_CONFIG", null, chargesSnapshot(current), chargesSnapshot(next));
        }
        return buildLayout(tenantId, ent);
    }

    // ---- helpers ----

    private static ConflictException bedHasHistory() {
        return new ConflictException("bed-has-history", "Bed has admission history",
                "This bed has admission history and cannot be removed.");
    }

    private ModuleEntitlement beginWrite(UUID tenantId, UUID actor) {
        ownerGuard.requireOwner(tenantId, actor);
        ModuleEntitlement ent = gate.check(tenantId, true);
        wards.lockTenant(tenantId);
        return ent;
    }

    private Instant now() {
        return gate.now();
    }

    private ConfigRow config(UUID tenantId) {
        return catalog.config(tenantId);
    }

    private Map<String, CategoryRow> categories(UUID tenantId) {
        return catalog.categories(tenantId);
    }

    private void insertRoomAudited(UUID tenantId, UUID actor, UUID wardId, String name, Instant now) {
        UUID roomId = UUID.randomUUID();
        wards.insertRoom(tenantId, roomId, wardId, name, actor, now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("wardId", wardId);
        after.put("name", name);
        auditLog.record("IPD_ROOM_ADDED", "IPD_ROOM", roomId, null, after);
    }

    private RoomRow firstRoomOrCreate(UUID tenantId, UUID actor, WardRow ward, Instant now) {
        for (RoomRow room : wards.findRooms(tenantId)) {
            if (room.wardId().equals(ward.id())) {
                return room;
            }
        }
        String name = ward.name().length() > ROOM_NAME_MAX ? ward.name().substring(0, ROOM_NAME_MAX) : ward.name();
        UUID roomId = UUID.randomUUID();
        wards.insertRoom(tenantId, roomId, ward.id(), name, actor, now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("wardId", ward.id());
        after.put("name", name);
        auditLog.record("IPD_ROOM_ADDED", "IPD_ROOM", roomId, null, after);
        return new RoomRow(roomId, ward.id(), name);
    }

    /** The first run of up to nine digits in the name, or -1 when there is none. */
    static int firstNumber(String name) {
        Matcher m = DIGITS.matcher(name);
        return m.find() ? Integer.parseInt(m.group()) : -1;
    }

    private static String digitsOf(String roomName) {
        Matcher m = DIGITS.matcher(roomName);
        return m.find() ? m.group() : null;
    }

    /** The first up to three capital letters of the ward name, or B. */
    static String capitals(String wardName) {
        StringBuilder sb = new StringBuilder();
        Matcher m = CAPITALS.matcher(wardName);
        while (sb.length() < 3 && m.find()) {
            sb.append(m.group());
        }
        return sb.length() == 0 ? "B" : sb.toString();
    }

    static String defaultCategory(String wardName) {
        String lower = wardName.toLowerCase(Locale.ROOT);
        if (lower.contains("icu")) {
            return "icu";
        }
        return lower.contains("private") ? "pvt" : "gen";
    }

    /** First of A to Z, then AA to ZZ, that no bed in the clinic uses after its last dash. */
    static String freeLetters(List<BedRow> existing) {
        Set<String> used = new HashSet<>();
        for (BedRow bed : existing) {
            used.add(bed.code().substring(bed.code().lastIndexOf('-') + 1));
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            if (!used.contains(String.valueOf(c))) {
                return String.valueOf(c);
            }
        }
        for (char a = 'A'; a <= 'Z'; a++) {
            for (char b = 'A'; b <= 'Z'; b++) {
                String s = "" + a + b;
                if (!used.contains(s)) {
                    return s;
                }
            }
        }
        throw new ConflictException("bed-code", "No bed code left", "No free bed code is left. Ask Sahaj for help.");
    }

    private static Map<String, Object> categorySnapshot(String key, long ratePaise, BigDecimal multiplier) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("ratePaise", ratePaise);
        m.put("multiplier", multiplier);
        return m;
    }

    private static Map<String, Object> chargesSnapshot(ConfigRow c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bedDayCut", c.bedDayCut());
        m.put("nursingPaise", c.nursingPaise());
        m.put("visitPaise", c.visitPaise());
        return m;
    }

    private IpdLayout buildLayout(UUID tenantId, ModuleEntitlement ent) {
        List<BedRow> allBeds = beds.findAll(tenantId);
        List<RoomRow> allRooms = wards.findRooms(tenantId);
        Map<UUID, List<BedRow>> bedsByWard = allBeds.stream().collect(Collectors.groupingBy(BedRow::wardId));
        Map<UUID, List<BedRow>> bedsByRoom = allBeds.stream().filter(b -> b.roomId() != null)
                .collect(Collectors.groupingBy(BedRow::roomId));
        Map<UUID, List<RoomRow>> roomsByWard = allRooms.stream().collect(Collectors.groupingBy(RoomRow::wardId));
        List<IpdLayout.Ward> wardViews = new ArrayList<>();
        for (WardRow ward : wards.findWards(tenantId)) {
            List<IpdLayout.Bed> flat = new ArrayList<>();
            for (BedRow bed : bedsByWard.getOrDefault(ward.id(), List.of())) {
                flat.add(toBed(bed));
            }
            List<IpdLayout.Room> roomViews = new ArrayList<>();
            for (RoomRow room : roomsByWard.getOrDefault(ward.id(), List.of())) {
                List<IpdLayout.Bed> inRoom = new ArrayList<>();
                for (BedRow bed : bedsByRoom.getOrDefault(room.id(), List.of())) {
                    inRoom.add(toBed(bed));
                }
                roomViews.add(new IpdLayout.Room(room.id(), room.name(), inRoom));
            }
            wardViews.add(new IpdLayout.Ward(ward.id(), ward.name(), ward.station(), flat.size(), roomViews, flat));
        }
        List<IpdLayout.Category> categoryViews = new ArrayList<>();
        for (CategoryRow row : categories(tenantId).values()) {
            int count = (int) allBeds.stream().filter(b -> b.categoryKey().equals(row.key())).count();
            categoryViews.add(new IpdLayout.Category(row.key(), NAMES.get(row.key()), row.ratePaise(),
                    row.multiplier(), count));
        }
        ConfigRow config = config(tenantId);
        return new IpdLayout(ent.ipdState(), config.roomLevel(), ent.bedLimit(), allBeds.size(), wardViews,
                categoryViews, new IpdLayout.Charges(config.bedDayCut(), config.nursingPaise(), config.visitPaise()));
    }

    private static IpdLayout.Bed toBed(BedRow bed) {
        return new IpdLayout.Bed(bed.id(), bed.code(), bed.categoryKey(), bed.status());
    }
}
