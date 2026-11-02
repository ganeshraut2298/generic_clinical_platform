package com.sahaj.opd.application;

import java.util.Map;

/** Fields of {@code PUT /fee-settings} (SAH-7). All are needed. */
public record FeeSettingsInput(String feeTiming, Integer followUpDays, String followUpFee,
                                Map<String, Integer> discountLimits, Boolean upiOnBill) {
}
