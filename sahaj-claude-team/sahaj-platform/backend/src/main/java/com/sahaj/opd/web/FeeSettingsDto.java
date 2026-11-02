package com.sahaj.opd.web;

import com.sahaj.opd.application.FeeSettingsInput;
import com.sahaj.opd.application.FeeSettingsRepository.FeeRow;
import java.util.Map;

/** {@code FeeSettings} in {@code docs/api/opd.yaml}, for the request and the response. */
public record FeeSettingsDto(String feeTiming, Integer followUpDays, String followUpFee,
                              Map<String, Integer> discountLimits, Boolean upiOnBill) {

    public static FeeSettingsDto from(FeeRow row) {
        return new FeeSettingsDto(row.feeTiming(), row.followUpDays(), row.followUpFee(), row.discountLimits(),
                row.upiOnBill());
    }

    public FeeSettingsInput toInput() {
        return new FeeSettingsInput(feeTiming, followUpDays, followUpFee, discountLimits, upiOnBill);
    }
}
