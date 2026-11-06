package com.sahaj.ipd.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code ipd.bed_category} and {@code ipd.config} (SAH-22). Both are lazy: absent rows mean the defaults. */
public interface IpdSettingsRepository {

    /** Only the categories that were saved. */
    List<CategoryRow> findCategories(UUID tenantId);

    Optional<ConfigRow> findConfig(UUID tenantId);

    void upsertCategory(UUID tenantId, String key, long ratePaise, BigDecimal multiplier, UUID actorUserId, Instant now);

    void upsertConfig(UUID tenantId, ConfigRow config, UUID actorUserId, Instant now);

    /** SAH-23: the minimum deposit rules of {@code ipd.config}; absent row means the defaults. */
    Optional<DepositRules> findDepositRules(UUID tenantId);

    /** Writes the deposit columns only, creating the lazy config row (other columns keep their defaults). */
    void upsertDepositRules(UUID tenantId, DepositRules rules, UUID actorUserId, Instant now);

    /** SAH-26: the transfer rules of {@code ipd.config}; an absent row means the defaults. */
    Optional<TransferRules> findTransferRules(UUID tenantId);

    /** Writes the two transfer columns only, creating the lazy config row (other columns keep their defaults). */
    void upsertTransferRules(UUID tenantId, TransferRules rules, UUID actorUserId, Instant now);

    /** SAH-27: the Discharge setup columns of {@code ipd.config}; an absent row means the defaults. */
    Optional<DischargeSettings> findDischargeSettings(UUID tenantId);

    /** Writes the three discharge columns only, creating the lazy config row (other columns keep their defaults). */
    void upsertDischargeSettings(UUID tenantId, DischargeSettings settings, UUID actorUserId, Instant now);

    /** SAH-28: the Emergency setup columns of {@code ipd.config}; an absent row means the defaults. */
    Optional<EmergencySetup> findEmergencySetup(UUID tenantId);

    /** Writes the five emergency columns only, creating the lazy config row (other columns keep their defaults). */
    void upsertEmergencySetup(UUID tenantId, EmergencySetup setup, UUID actorUserId, Instant now);

    /**
     * SAH-28 Emergency and triage setup. {@code scale} is 3 or 5; each target list holds minutes (0 to 240, steps of
     * 5) for its own scale and is kept when the other scale is in use; {@code bays} is 0 to 10.
     */
    record EmergencySetup(int scale, List<Integer> targets3, List<Integer> targets5, int bays,
                          boolean unknownPatientRegistration) {

        public static final EmergencySetup DEFAULTS = new EmergencySetup(3, List.of(5, 30, 120),
                List.of(0, 10, 30, 60, 120), 2, true);

        /** The target list of the scale in use. */
        public List<Integer> targets() {
            return scale == 5 ? targets5 : targets3;
        }
    }

    /**
     * SAH-27 Discharge setup. {@code templateKey} is general, surgical, maternity, daycare or null (none chosen);
     * {@code followUpDays} is 1 to 30.
     */
    record DischargeSettings(String templateKey, boolean approvalChain, int followUpDays) {

        public static final DischargeSettings DEFAULTS = new DischargeSettings(null, true, 7);
    }

    /**
     * SAH-26 contract {@code TransferRules}. {@code upgradeNeedsOwner} true is "Owner approves"; {@code newRateFrom}
     * is {@code same} or {@code next}.
     */
    record TransferRules(boolean upgradeNeedsOwner, String newRateFrom) {

        public static final TransferRules DEFAULTS = new TransferRules(true, "same");
    }

    /** All amounts in paise. */
    record DepositRules(long planned, long emergency, long daycare, long maternity,
                        long catGen, long catSemi, long catPvt, long catIcu,
                        boolean emergencyWithoutDeposit, int alertPercent, boolean confirmed) {

        public static final DepositRules DEFAULTS = new DepositRules(1_000_000L, 500_000L, 300_000L, 1_500_000L,
                0L, 200_000L, 500_000L, 1_000_000L, true, 80, false);

        /** Base deposit for the admission type plus the add-on of the bed category. */
        public long minimum(String admissionType, String categoryKey) {
            long base;
            switch (admissionType) {
                case "emergency" -> base = emergency;
                case "daycare" -> base = daycare;
                case "maternity" -> base = maternity;
                default -> base = planned;
            }
            long add;
            switch (categoryKey) {
                case "semi" -> add = catSemi;
                case "pvt" -> add = catPvt;
                case "icu" -> add = catIcu;
                default -> add = catGen;
            }
            return base + add;
        }
    }

    record CategoryRow(String key, long ratePaise, BigDecimal multiplier) {
    }

    record ConfigRow(boolean roomLevel, String bedDayCut, long nursingPaise, long visitPaise) {
    }
}
