package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.patient.api.PatientAge;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Everything a specialty component needs besides its own value (ADR-023 D2, D4): who saves and when, the patient's
 * date of birth and sex (read through patient.api), the global reference tables, the clinic tariff, the vaccines already
 * given in earlier records, the ANC course of the patient, and the other components of the same record. Screen data:
 * never logged.
 *
 * @param profile        the record's specialty profile (gm, dental, og, paeds, physio, onco, surgery)
 * @param recordDate     the day the record was opened (Asia/Kolkata): "done on this record's date"
 * @param sibling        the stored {@code specialty} value of another component of the same record, or null
 * @param earlierVaccines vaccine keys given in earlier signed records of the patient
 * @param ancCourseId    the patient's ACTIVE ANC course, or null
 * @param hooks          lazy reads that only some profiles need
 * @param ageYears       SAH-73 (ADR-024 D1): the patient's effective completed years from patient.api (the date of birth
 *                       as of today in India when one is recorded, else the stored whole number); null without a patient
 * @param dateOfBirthVisible SAH-73 (ADR-024 D3): true only when the signed-in role may see the date of birth; the
 *                       Paediatrics view carries the date for the vaccination card only then
 */
public record SpecialtyCtx(String profile, Instant now, LocalDate today, LocalDate recordDate, UUID membershipId,
                           LocalDate dateOfBirth, String sex, SpecialtyReferenceData ref, Tariff tariff,
                           Set<String> earlierVaccines, UUID ancCourseId, Function<String, JsonNode> sibling,
                           Hooks hooks, Integer ageYears, boolean dateOfBirthVisible) {

    /**
     * The shape before SAH-73: the effective years are worked out from the date of birth as of {@code today} (null
     * without one) and the date of birth is not visible.
     */
    public SpecialtyCtx(String profile, Instant now, LocalDate today, LocalDate recordDate, UUID membershipId,
                        LocalDate dateOfBirth, String sex, SpecialtyReferenceData ref, Tariff tariff,
                        Set<String> earlierVaccines, UUID ancCourseId, Function<String, JsonNode> sibling,
                        Hooks hooks) {
        this(profile, now, today, recordDate, membershipId, dateOfBirth, sex, ref, tariff, earlierVaccines,
                ancCourseId, sibling, hooks, dateOfBirth == null ? null : PatientAge.completedYears(dateOfBirth, today),
                false);
    }

    /** Personal data: no date of birth in a stray log line. */
    @Override
    public String toString() {
        return "SpecialtyCtx[" + profile + "]";
    }

    /** One active service of the clinic's price list, matched by name (ADR-023 H17). */
    public record TariffItem(UUID serviceId, long pricePaise) {
    }

    /** The clinic tariff: the active service with the same name ignoring case and spaces, if any. */
    public interface Tariff {
        Optional<TariffItem> match(String name);

        static Tariff none() {
            return name -> Optional.empty();
        }
    }

    /** A component value of an earlier SIGNED record of the same patient (empty until SAH-51 signs records). */
    public record Earlier(UUID recordId, LocalDate date, JsonNode value) {
    }

    /** The patient's active REHAB course: its name, this visit's session number and the sittings in the course. */
    public record RehabInfo(String packageName, Integer sessionNo, Integer sessionsPlanned) {
    }

    /**
     * What the oncology screen reads from the patient's chemotherapy orders: the weight of the first SENT order, the
     * doxorubicin already sent in this clinic (mg/m2 x level, summed) and the latest outside dose typed on an order, how
     * many orders were sent, which protocols were sent, this record's order status, protocol, level and the outside dose
     * typed on it (null = carry the latest forward).
     */
    public record ChemoInfo(Double cycle1WeightKg, double sentDoxorubicinMgM2, double latestOutsideMgM2, int sentCount,
                            Set<String> sentProtocols, String orderStatus, Integer linkedCycleNo, String protocolKey,
                            int levelPct, Double priorOutsideMgM2) {

        public static ChemoInfo none() {
            return new ChemoInfo(null, 0, 0, 0, Set.of(), "NONE", null, null, 100, null);
        }
    }

    /** Lazy reads. {@code section} is the generic values object of a section (for example menstrual). */
    public record Hooks(Function<String, JsonNode> section, Function<String, List<Earlier>> earlier,
                        Supplier<List<JsonNode>> prescriptionRows, BooleanSupplier hasActiveDiagnosis,
                        Supplier<RehabInfo> rehab, Supplier<ChemoInfo> chemo) {

        public static Hooks none() {
            return new Hooks(key -> null, component -> List.of(), List::of, () -> false, () -> null,
                    ChemoInfo::none);
        }
    }
}
