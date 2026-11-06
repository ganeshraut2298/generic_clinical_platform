package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionViews.ChargeService;
import java.util.List;
import java.util.Optional;

/** SAH-25 the fixed list of extra-charge services of the mockup ({@code IP_SERV}), base prices in paise. */
final class ChargeServices {

    static final int MAX_ROWS_PER_SERVICE = 50;

    static final List<ChargeService> ALL = List.of(
            new ChargeService("visit", "Extra doctor visit", 50_000L),
            new ChargeService("ivfluid", "IV fluids (per bottle)", 35_000L),
            new ChargeService("injection", "Injection (IV/IM)", 15_000L),
            new ChargeService("cbc", "CBC", 30_000L),
            new ChargeService("xray", "Chest X-ray", 45_000L),
            new ChargeService("oxygen", "Oxygen (per day)", 80_000L),
            new ChargeService("dressing", "Major dressing", 40_000L),
            new ChargeService("ecg", "ECG", 30_000L),
            new ChargeService("nebulisation", "Nebulisation", 20_000L),
            new ChargeService("physio", "Physiotherapy session", 40_000L));

    private ChargeServices() {
    }

    static Optional<ChargeService> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return ALL.stream().filter(s -> s.key().equals(key)).findFirst();
    }
}
