package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyViews.UnitSettings;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.pharmacy.domain.UnitCodes;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicLetterheadPort;
import com.sahaj.platform.api.ClinicWebAddressPort;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.ModuleEntitlementPort;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-2: the unit settings (GSTIN, drug licence, Integration mode) and the lazy unit row every other master hangs on.
 * FEFO and the negative-stock block are constants, shown as facts, and no field can change them (OQ-78-11). The unit name
 * is read from the clinic profile through {@code platform.api}, never copied.
 */
@Service
public class UnitService {

    static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    static final int LICENCE_MAX = 40;
    static final String RELOAD = "Reload the page and try again.";
    static final String REQUIRED = "This field is required";
    private static final String FALLBACK_NAME = "Your clinic";

    private final PharmacyGate gate;
    private final UnitRepository units;
    private final ClinicLetterheadPort letterhead;
    private final ClinicWebAddressPort webAddress;
    private final ModuleEntitlementPort entitlements;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public UnitService(PharmacyGate gate, UnitRepository units, ClinicLetterheadPort letterhead,
                       ClinicWebAddressPort webAddress, ModuleEntitlementPort entitlements, AuditLog auditLog,
                       PharmacyTime time) {
        this.gate = gate;
        this.units = units;
        this.letterhead = letterhead;
        this.webAddress = webAddress;
        this.entitlements = entitlements;
        this.auditLog = auditLog;
        this.time = time;
    }

    /** Defaults until the first save: saved false, version 0, Integrated, nothing typed. Writes nothing. */
    @Transactional(readOnly = true)
    public UnitSettings get(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return view(tenantId, units.find(tenantId).orElse(null));
    }

    /** For tests: the body is already read. */
    UnitSettings save(UUID tenantId, UUID userId, PharmacyInputs.UnitSettings input) {
        return save(tenantId, userId, PharmacyInputs.Body.of(input));
    }

    /** The body is read after the gate (module off 403, owner only 403), so a bad body never beats a 403. */
    @Transactional
    public UnitSettings save(UUID tenantId, UUID userId, PharmacyInputs.Body<PharmacyInputs.UnitSettings> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        Checked checked = check(body.read());
        Unit unit = ensureUnit(tenantId, userId);
        if (checked.version() != unit.version()) {
            throw PharmacyProblemException.settingsChanged(view(tenantId, unit));
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        track(before, after, "gstin", unit.gstin(), checked.gstin());
        track(before, after, "drugLicenceNo", unit.drugLicenceNo(), checked.licenceNo());
        track(before, after, "drugLicenceValidTill", unit.drugLicenceValidTill(), checked.licenceTill());
        track(before, after, "integrationMode", unit.integrationMode(), checked.mode());
        if (before.isEmpty() && unit.saved()) {
            return view(tenantId, unit);
        }
        if (!units.updateSettings(tenantId, unit.id(), unit.version(), checked.gstin(), checked.licenceNo(),
                checked.licenceTill(), checked.mode(), userId)) {
            throw PharmacyProblemException.settingsChanged(view(tenantId, units.find(tenantId).orElse(unit)));
        }
        auditLog.record("PHARMACY_UNIT_SETTINGS_CHANGED", "PHARMACY_UNIT", unit.id(), before, after);
        if (unit.integrationMode() != checked.mode()) {
            auditLog.record("PHARMACY_INTEGRATION_MODE_CHANGED", "PHARMACY_UNIT", unit.id(),
                    Map.of("integrationMode", unit.integrationMode().name()),
                    Map.of("integrationMode", checked.mode().name()));
        }
        return view(tenantId, units.find(tenantId).orElseThrow());
    }

    /**
     * The clinic's unit row, created now when this is the first master write, and locked until the transaction ends. Every
     * master write of the clinic's stores starts here, so two of them never run side by side.
     */
    Unit ensureUnit(UUID tenantId, UUID actorUserId) {
        String code = UnitCodes.derive(webAddress.webAddress(tenantId).orElse(null));
        return units.ensureAndLock(tenantId, code, actorUserId);
    }

    /** The unit header of GET /context: name from the clinic profile, code and mode from the row or the defaults. */
    PharmacyViews.UnitHeader header(UUID tenantId, Unit unit) {
        return new PharmacyViews.UnitHeader(unitName(tenantId), unitCode(tenantId, unit), mode(unit));
    }

    private UnitSettings view(UUID tenantId, Unit unit) {
        LocalDate till = unit == null ? null : unit.drugLicenceValidTill();
        boolean ipdAvailable = !"off".equals(entitlements.forTenant(tenantId).ipdState());
        return new UnitSettings(unitName(tenantId), unitCode(tenantId, unit), unit == null ? null : unit.gstin(),
                unit == null ? null : unit.drugLicenceNo(), till, till != null && till.isBefore(time.today()),
                mode(unit), true, true, ipdAvailable, unit != null && unit.saved(), unit == null ? 0L : unit.version(),
                unit == null ? null : unit.updatedAt());
    }

    private String unitName(UUID tenantId) {
        return letterhead.find(tenantId).map(ClinicLetterheadPort.Letterhead::clinicName)
                .filter(name -> name != null && !name.isBlank()).orElse(FALLBACK_NAME);
    }

    private String unitCode(UUID tenantId, Unit unit) {
        return unit != null ? unit.code() : UnitCodes.derive(webAddress.webAddress(tenantId).orElse(null));
    }

    private static IntegrationMode mode(Unit unit) {
        return unit == null ? IntegrationMode.INTEGRATED : unit.integrationMode();
    }

    private static void track(Map<String, Object> before, Map<String, Object> after, String field, Object was,
                              Object now) {
        if (!Objects.equals(was, now)) {
            before.put(field, was == null ? null : was.toString());
            after.put(field, now == null ? null : now.toString());
        }
    }

    /** The validated values of a save. */
    private record Checked(long version, String gstin, String licenceNo, LocalDate licenceTill, IntegrationMode mode) {
    }

    /**
     * All field errors together (400 validation); nothing typed is echoed back. The contract requires all five fields:
     * one that the request left out is an error, never read as null, so a partial save clears nothing. An explicit null
     * of GSTIN, licence number or licence date is allowed and means "not set".
     */
    private static Checked check(PharmacyInputs.UnitSettings input) {
        List<FieldMessage> errors = new ArrayList<>();
        if (input.version() == null || input.version() < 0) {
            errors.add(new FieldMessage("version", RELOAD));
        }
        requireSent(input.gstin(), "gstin", errors);
        requireSent(input.drugLicenceNo(), "drugLicenceNo", errors);
        requireSent(input.drugLicenceValidTill(), "drugLicenceValidTill", errors);
        String rawGstin = Sent.valueOf(input.gstin());
        String gstin = rawGstin == null ? "" : rawGstin.trim().toUpperCase(Locale.ROOT);
        if (!gstin.isEmpty() && !GSTIN.matcher(gstin).matches()) {
            errors.add(new FieldMessage("gstin", "Enter a 15-character GSTIN"));
        }
        String rawLicence = Sent.valueOf(input.drugLicenceNo());
        String licence = rawLicence == null ? "" : rawLicence.trim();
        if (licence.length() > LICENCE_MAX) {
            errors.add(new FieldMessage("drugLicenceNo", "Keep the licence number to 40 characters"));
        }
        if (input.integrationMode() == null) {
            errors.add(new FieldMessage("integrationMode", "Choose an integration mode"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new Checked(input.version(), gstin.isEmpty() ? null : gstin, licence.isEmpty() ? null : licence,
                Sent.valueOf(input.drugLicenceValidTill()), input.integrationMode());
    }

    private static void requireSent(Sent<?> field, String name, List<FieldMessage> errors) {
        if (Sent.missing(field)) {
            errors.add(new FieldMessage(name, REQUIRED));
        }
    }
}
