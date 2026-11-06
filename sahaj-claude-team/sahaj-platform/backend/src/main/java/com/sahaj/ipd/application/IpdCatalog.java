package com.sahaj.ipd.application;

import com.sahaj.ipd.application.IpdSettingsRepository.CategoryRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A clinic's bed categories and settings with the defaults filled in where the clinic has not saved a row yet.
 * One place for the default rules, shared by {@link IpdService}, {@link AdmissionService} and
 * {@link BedBoardService}.
 */
@Component
class IpdCatalog {

    private final IpdSettingsRepository settings;

    IpdCatalog(IpdSettingsRepository settings) {
        this.settings = settings;
    }

    /** All four categories in the fixed order, a saved row replacing its default. */
    Map<String, CategoryRow> categories(UUID tenantId) {
        Map<String, CategoryRow> result = new LinkedHashMap<>();
        for (String key : IpdService.KEYS) {
            result.put(key, IpdService.DEFAULTS.get(key));
        }
        for (CategoryRow row : settings.findCategories(tenantId)) {
            result.put(row.key(), row);
        }
        return result;
    }

    /** The saved deposit rules, or the defaults (alert at 80 percent) when the clinic has none. */
    IpdSettingsRepository.DepositRules depositRules(UUID tenantId) {
        return settings.findDepositRules(tenantId).orElse(IpdSettingsRepository.DepositRules.DEFAULTS);
    }

    /** SAH-26: the saved transfer rules, or "Owner approves" and "Same day" when the clinic has none. */
    IpdSettingsRepository.TransferRules transferRules(UUID tenantId) {
        return settings.findTransferRules(tenantId).orElse(IpdSettingsRepository.TransferRules.DEFAULTS);
    }

    /** SAH-27: the saved Discharge setup, or no template, approval chain on and 7 days when the clinic has none. */
    IpdSettingsRepository.DischargeSettings dischargeSettings(UUID tenantId) {
        return settings.findDischargeSettings(tenantId).orElse(IpdSettingsRepository.DischargeSettings.DEFAULTS);
    }

    /** SAH-28: the saved Emergency setup, or 3 colours 5/30/120, 2 bays and unknown patients allowed. */
    IpdSettingsRepository.EmergencySetup emergencySetup(UUID tenantId) {
        return settings.findEmergencySetup(tenantId).orElse(IpdSettingsRepository.EmergencySetup.DEFAULTS);
    }

    /** The saved settings, or {@link IpdService#DEFAULT_CONFIG} (rooms on) when the clinic has none. */
    ConfigRow config(UUID tenantId) {
        return settings.findConfig(tenantId).orElse(IpdService.DEFAULT_CONFIG);
    }
}
