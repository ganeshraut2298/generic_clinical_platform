package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.DoseUnit;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.RouteKey;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * SAH-76-1 (ADR-027 D1, D4, D7): the checked values of an order or of a change: dose, unit, route, start, duration,
 * indication, priority and the PRN limits. One rule set for the order form and the change form; the texts are those of
 * {@code docs/api/ipd.yaml} (MedicineOrderInput). Pure: it reads nothing and writes nothing. The indication is clinical
 * text: {@code toString} hides it.
 *
 * @param kind        SCHEDULED, PRN or ONCE (a STAT priority is always ONCE)
 * @param frequency   the frequency of a SCHEDULED value; null for PRN and ONCE
 * @param startAt     the requested start, or null for "now" (the service reads the database clock)
 * @param durationDays whole days from the start, or null for "until stopped"
 */
record OrderValues(Kind kind, Frequency frequency, BigDecimal doseAmount, DoseUnit doseUnit, RouteKey route,
                   Instant startAt, Integer durationDays, String indication, OrderPriority priority,
                   Integer minIntervalMin, Integer maxPer24h) {

    static final String DOSE_MESSAGE = "Enter the dose.";
    static final String UNIT_MESSAGE = "Choose the unit.";
    static final String ROUTE_MESSAGE = "Choose the route.";
    static final String PAST_MESSAGE = "Start cannot be in the past.";
    static final String DURATION_MESSAGE = "Enter whole days, or choose Until stopped.";
    static final String INDICATION_REQUIRED = "Enter what it is for.";
    static final String INTERVAL_MESSAGE = "Enter the minimum time between doses.";
    static final String MAX_MESSAGE = "Enter the most doses in 24 hours.";
    static final String STAT_MESSAGE = "A STAT order is one dose, due now.";
    static final String INDICATION_LENGTH = "Keep it to 200 characters.";
    static final String TEXT_CHARACTERS = "Text cannot contain control or text-direction characters";

    private static final BigDecimal MAX_DOSE = new BigDecimal("100000");
    private static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);

    @Override
    public String toString() {
        return "OrderValues[kind=" + kind + ", priority=" + priority + "]";
    }

    /**
     * The values of an order placed through opd.api EmrIpdOrderPort (ADR-027 D14): the four dose times, no dose and
     * unit ("as in the medicine name"), the drug's route, now, until stopped, ROUTINE.
     */
    static OrderValues ofPort(Drug drug, Frequency frequency) {
        return new OrderValues(Kind.SCHEDULED, frequency, null, null, drug.routeKey(), null, null, null,
                OrderPriority.ROUTINE, null, null);
    }

    /** The end of a version that starts at {@code start}: start plus the duration, null when until stopped. */
    Instant endFor(Instant start) {
        return durationDays == null ? null : start.plus(Duration.ofDays(durationDays));
    }

    /**
     * The row to write for this value set as version {@code versionNo}. {@code start} is the stored start (the
     * service decides it from the database clock); {@code changeReason} is null for version 1.
     */
    OrderVersionRepository.NewVersion toVersion(java.util.UUID orderId, int versionNo, Instant start,
                                                String changeReason, java.util.UUID createdBy,
                                                com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification verification) {
        return new OrderVersionRepository.NewVersion(orderId, versionNo, kind, frequency == null ? null : frequency.key(),
                doseAmount, doseUnit == null ? null : doseUnit.key(), route.key(), start, endFor(start), indication,
                priority, minIntervalMin, maxPer24h, changeReason, createdBy, verification);
    }

    /** The raw fields of the two bodies; every field is text or a number so the server judges it. */
    record Raw(String frequencyKey, BigDecimal doseAmount, String doseUnit, String routeKey, String startAt,
               Integer durationDays, String indication, String priority, Integer prnMinIntervalMinutes,
               Integer prnMaxPer24h) {

        @Override
        public String toString() {
            return "Raw[redacted]";
        }
    }

    /**
     * Checks the values; every error is added to {@code errors} in the order frequencyKey, priority, doseAmount,
     * doseUnit, routeKey, startAt, durationDays, indication, prnMinIntervalMinutes, prnMaxPer24h. Returns null when
     * an error was added. {@code pastIsError}: a start before {@code now} minus 5 minutes is an error (a new order);
     * for a change the service takes the later of the start and the change time instead.
     */
    static OrderValues check(Drug drug, Raw raw, Instant now, boolean pastIsError, List<FieldMessage> errors) {
        int before = errors.size();
        OrderPriority priority = priority(raw.priority());
        boolean stat = priority == OrderPriority.STAT;
        Frequency frequency = null;
        if (stat) {
            if (raw.frequencyKey() != null || raw.durationDays() != null) {
                errors.add(new FieldMessage("priority", STAT_MESSAGE));
            }
        } else {
            frequency = Frequency.ofKey(raw.frequencyKey()).orElse(null);
            if (frequency == null) {
                errors.add(new FieldMessage("frequencyKey", MedicineRules.FREQUENCY_MESSAGE));
            }
        }
        if (priority == null) {
            errors.add(new FieldMessage("priority", MedicineRules.RELOAD_MESSAGE));
        }
        BigDecimal dose = raw.doseAmount();
        if (dose == null || dose.signum() <= 0 || dose.compareTo(MAX_DOSE) > 0 || dose.stripTrailingZeros().scale() > 3) {
            errors.add(new FieldMessage("doseAmount", DOSE_MESSAGE));
        }
        DoseUnit unit = DoseUnit.ofKey(raw.doseUnit()).orElse(null);
        if (unit == null || (drug != null && !drug.allowsUnit(unit))) {
            errors.add(new FieldMessage("doseUnit", UNIT_MESSAGE));
        }
        RouteKey route = RouteKey.ofKey(raw.routeKey()).orElse(null);
        if (route == null) {
            errors.add(new FieldMessage("routeKey", ROUTE_MESSAGE));
        }
        Instant start = null;
        if (!stat && raw.startAt() != null && !raw.startAt().isBlank()) {
            try {
                start = Instant.parse(raw.startAt().strip());
                if (pastIsError && start.isBefore(now.minus(PAST_TOLERANCE))) {
                    errors.add(new FieldMessage("startAt", PAST_MESSAGE));
                }
            } catch (DateTimeParseException e) {
                errors.add(new FieldMessage("startAt", MedicineRules.RELOAD_MESSAGE));
            }
        }
        Integer days = stat ? null : raw.durationDays();
        if (days != null && (days < 1 || days > 365)) {
            errors.add(new FieldMessage("durationDays", DURATION_MESSAGE));
        }
        String indication = text(raw.indication(), 200, "indication", INDICATION_LENGTH, errors);
        boolean prn = frequency == Frequency.PRN;
        if (prn && indication == null) {
            errors.add(new FieldMessage("indication", INDICATION_REQUIRED));
        }
        Integer min = prn ? raw.prnMinIntervalMinutes() : null;
        Integer max = prn ? raw.prnMaxPer24h() : null;
        if (prn && (min == null || min < 15 || min > 4320)) {
            errors.add(new FieldMessage("prnMinIntervalMinutes", INTERVAL_MESSAGE));
        }
        if (prn && (max == null || max < 1 || max > 24)) {
            errors.add(new FieldMessage("prnMaxPer24h", MAX_MESSAGE));
        }
        if (errors.size() > before) {
            return null;
        }
        Kind kind = stat ? Kind.ONCE : prn ? Kind.PRN : Kind.SCHEDULED;
        return new OrderValues(kind, kind == Kind.SCHEDULED ? frequency : null, dose, unit, route, start, days,
                indication, priority, min, max);
    }

    private static OrderPriority priority(String text) {
        if (text == null) {
            return null;
        }
        for (OrderPriority p : OrderPriority.values()) {
            if (p.name().equals(text)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Clinical text: composed to NFC, trimmed, empty is null; over {@code max} characters or holding a control or
     * text-direction character is an error (null result). Returns null for empty text without an error.
     */
    static String text(String raw, int max, String field, String lengthMessage, List<FieldMessage> errors) {
        if (raw == null) {
            return null;
        }
        String nfc = IpdText.nfc(raw).replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        if (IpdText.hasControlOrBidi(nfc.replace('\n', ' '))) {
            errors.add(new FieldMessage(field, TEXT_CHARACTERS));
            return null;
        }
        String trimmed = nfc.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > max) {
            errors.add(new FieldMessage(field, lengthMessage));
            return null;
        }
        return trimmed;
    }
}
