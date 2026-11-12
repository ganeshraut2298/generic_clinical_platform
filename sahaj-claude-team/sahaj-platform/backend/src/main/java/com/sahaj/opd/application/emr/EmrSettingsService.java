package com.sahaj.opd.application.emr;

import com.sahaj.opd.application.emr.EmrSettingsRepository.SettingsRow;
import com.sahaj.opd.application.emr.EmrViews.Settings;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How the EMR opens in this clinic (SAH-44-3, ADR-019 D11). Defaults until the first save (COLLAPSED, CASE_PAPER,
 * ASSESSMENT, version 0). Every clinic role reads; the owner saves (capability {@value EmrAccess#EMR_SETUP_EDIT}).
 * The default part is stored as ASSESSMENT and is not accepted in a request. A stale version is a 409; a save that
 * changes nothing writes no audit entry and keeps the version.
 */
@Service
public class EmrSettingsService {

    public static final String COLLAPSED = "COLLAPSED";
    public static final String OPEN = "OPEN";
    public static final String CASE_PAPER = "CASE_PAPER";
    public static final String PAST_VISITS = "PAST_VISITS";
    public static final String ASSESSMENT = "ASSESSMENT";

    private static final List<String> SECTION_PANELS = List.of(COLLAPSED, OPEN);
    private static final List<String> RIGHT_PANELS = List.of(CASE_PAPER, PAST_VISITS);

    private final EmrSettingsRepository repository;
    private final EmrAccess access;
    private final AuditLog auditLog;

    public EmrSettingsService(EmrSettingsRepository repository, EmrAccess access, AuditLog auditLog) {
        this.repository = repository;
        this.access = access;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public Settings get(ClinicUser user) {
        return toView(current(user.tenantId()), access.canEditSetup(user));
    }

    @Transactional
    public Settings save(ClinicUser user, String sectionPanel, String rightPanelFirst, Long versionValue) {
        access.requireSetupEdit(user);
        checkAll(sectionPanel, rightPanelFirst, versionValue);
        long version = versionValue;
        UUID tenantId = user.tenantId();
        Optional<SettingsRow> stored = repository.find(tenantId);
        SettingsRow before = stored.orElse(defaults());
        if (before.version() != version) {
            throw conflict();
        }
        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        if (!before.sectionPanel().equals(sectionPanel)) {
            changedBefore.put("sectionPanel", before.sectionPanel());
            changedAfter.put("sectionPanel", sectionPanel);
        }
        if (!before.rightPanelFirst().equals(rightPanelFirst)) {
            changedBefore.put("rightPanelFirst", before.rightPanelFirst());
            changedAfter.put("rightPanelFirst", rightPanelFirst);
        }
        if (changedAfter.isEmpty()) {
            return toView(before, true);
        }
        boolean saved = stored.isPresent()
                ? repository.update(tenantId, sectionPanel, rightPanelFirst, version, user.userId())
                : repository.insert(tenantId, sectionPanel, rightPanelFirst, user.userId());
        if (!saved) {
            throw conflict();
        }
        auditLog.record("EMR_SETTINGS_UPDATED", "EMR_SETTINGS", null, changedBefore, changedAfter);
        return toView(current(tenantId), true);
    }

    private SettingsRow current(UUID tenantId) {
        return repository.find(tenantId).orElse(defaults());
    }

    private static SettingsRow defaults() {
        return new SettingsRow(COLLAPSED, CASE_PAPER, 0);
    }

    private static Settings toView(SettingsRow row, boolean canEdit) {
        return new Settings(row.sectionPanel(), row.rightPanelFirst(), ASSESSMENT, row.version(), canEdit);
    }

    private static ConflictException conflict() {
        return new ConflictException("emr-setup-changed", "Someone else changed these settings",
                "Someone else changed this setting. Reload and try again.");
    }

    /** Every wrong field is reported together (400 validation). */
    private static void checkAll(String sectionPanel, String rightPanelFirst, Long version) {
        List<InvalidFieldsException.FieldMessage> errors = new ArrayList<>();
        if (sectionPanel == null || !SECTION_PANELS.contains(sectionPanel)) {
            errors.add(new InvalidFieldsException.FieldMessage("sectionPanel", "Choose Collapsed or Open."));
        }
        if (rightPanelFirst == null || !RIGHT_PANELS.contains(rightPanelFirst)) {
            errors.add(new InvalidFieldsException.FieldMessage("rightPanelFirst", "Choose Case paper or Past visits."));
        }
        if (version == null || version < 0) {
            errors.add(new InvalidFieldsException.FieldMessage("version", "Reload the page and try again."));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }
}
