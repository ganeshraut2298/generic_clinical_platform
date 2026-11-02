package com.sahaj.opd.application;

import com.sahaj.opd.api.DiscountLimitPort;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link DiscountLimitPort} for {@code billing} (SAH-10) over {@link FeeSettingsRepository}. */
@Service
class DiscountLimitAdapter implements DiscountLimitPort {

    private final FeeSettingsRepository feeSettingsRepository;

    DiscountLimitAdapter(FeeSettingsRepository feeSettingsRepository) {
        this.feeSettingsRepository = feeSettingsRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> discountLimits(UUID tenantId) {
        return feeSettingsRepository.find(tenantId)
                .map(FeeSettingsRepository.FeeRow::discountLimits)
                .orElse(FeeSettingsService.DEFAULTS.discountLimits());
    }
}
