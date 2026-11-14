package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishBillLine;
import com.sahaj.opd.application.emr.finish.FinishModels.FinishPrint;
import com.sahaj.opd.application.emr.finish.FinishModels.FreeSlot;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The profile block of "Check before completing" (docs/api/opd.yaml 0.6.1, EmrFinishProfileEffects; ADR-025 D7, D19): one
 * record per specialty profile, written flat with a {@code profile} discriminator (gm, dental, og, paeds, physio, onco,
 * surgery) exactly as the contract's oneOf says. Each block is what {@link SpecialtyFinishContributor#preview} computes from
 * the record by reads only. Clinical values: screen only, never logged, audited or put in a problem detail, so every record
 * shows ids and the profile only in {@code toString}.
 *
 * <p>Three facts are for the sign step and are not part of the answer: the bill lines the sign adds, the follow-up date the
 * profile decides and the prints the profile adds. They are methods, not record components, so Jackson never writes them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "profile")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ProfileEffects.Gm.class, name = "gm"),
        @JsonSubTypes.Type(value = ProfileEffects.Dental.class, name = "dental"),
        @JsonSubTypes.Type(value = ProfileEffects.Og.class, name = "og"),
        @JsonSubTypes.Type(value = ProfileEffects.Paeds.class, name = "paeds"),
        @JsonSubTypes.Type(value = ProfileEffects.Physio.class, name = "physio"),
        @JsonSubTypes.Type(value = ProfileEffects.Onco.class, name = "onco"),
        @JsonSubTypes.Type(value = ProfileEffects.Surgery.class, name = "surgery")})
public sealed interface ProfileEffects {

    /** The discriminator value of this block. */
    String profile();

    /** The bill lines the sign adds for the profile (dental plan rows); they are also in the answer's {@code billLines}. */
    default List<FinishBillLine> billLines() {
        return List.of();
    }

    /** The follow-up date the profile decides, or null (dental next sitting, oncology next cycle). */
    default LocalDate followUpDate() {
        return null;
    }

    /** The prints this profile adds after the sign, in print order (oncology and surgery only). */
    default List<FinishPrint> prints() {
        return List.of();
    }

    /** General Medicine: no profile facts (SAH-51-1, SAH-60). */
    record Gm() implements ProfileEffects {

        @Override
        public String profile() {
            return "gm";
        }
    }

    /** One plan row done today, as the dialog's line "36 · Root canal treatment — sitting 2 of 3". */
    record DentalRow(UUID rowId, String name, Sitting sitting, Long amountPaise) {

        @Override
        public String toString() {
            return "DentalRow[rowId=" + rowId + "]";
        }
    }

    record Sitting(int no, int of) {
    }

    /** SAH-69-3: the dental finish dialog. {@code billLines} are for the sign step only (never written to the answer). */
    @JsonIgnoreProperties("billLines")
    record Dental(List<DentalRow> doneRows, long estimateTotalPaise, boolean allRowsDone, int carriedRowCount,
                  String nextSittingInterval, LocalDate nextSittingDate, FreeSlot nextSittingSlot,
                  List<FinishBillLine> billLines) implements ProfileEffects {

        @Override
        public String profile() {
            return "dental";
        }

        /** Clinical values: the profile only. */
        @Override
        public String toString() {
            return "ProfileEffects[dental]";
        }

        @Override
        public LocalDate followUpDate() {
            return nextSittingDate;
        }
    }

    record GestationalAge(int weeks, int days) {
    }

    /** SAH-61-1, SAH-61-3: the obstetrics and gynaecology finish dialog. */
    record Og(String mode, Integer ancVisitNo, GestationalAge gestationalAge, Integer bpSystolic, Integer bpDiastolic,
              Double hbGdl, List<String> orderedLabels, Integer nextAncWeeks) implements ProfileEffects {

        @Override
        public String profile() {
            return "og";
        }

        /** Clinical values: the profile only. */
        @Override
        public String toString() {
            return "ProfileEffects[og]";
        }
    }

    record GivenVaccine(String vaccineKey, String label) {
    }

    record NextDue(String vaccineKey, String label, String groupLabel) {
    }

    /** SAH-62-4: the paediatrics finish dialog. */
    record Paeds(Double weightKg, String weightNote, List<GivenVaccine> vaccinesGivenToday, NextDue nextDue)
            implements ProfileEffects {

        @Override
        public String profile() {
            return "paeds";
        }

        /** Clinical values: the profile only. */
        @Override
        public String toString() {
            return "ProfileEffects[paeds]";
        }
    }

    /** SAH-63-3: the physiotherapy finish dialog. */
    record Physio(UUID courseId, Integer sessionNo, Integer packageSize, Integer nextSessionNo, boolean packageComplete,
                  Integer painScore, Integer previousPainScore) implements ProfileEffects {

        @Override
        public String profile() {
            return "physio";
        }

        /** Clinical values: the profile only. */
        @Override
        public String toString() {
            return "ProfileEffects[physio]";
        }
    }

    /** SAH-70-1, SAH-70-2: the oncology finish dialog. The cycle record always prints; the calendar needs a next date. */
    record Onco(Integer cycleNo, String decision, String orderStatus, Integer nextCycleNo, String nextCycleLabel,
                LocalDate nextCycleDate, UUID nextCycleSittingId, Long cycleChargePaise, boolean followUpFromNextCycle)
            implements ProfileEffects {

        @Override
        public String profile() {
            return "onco";
        }

        /** Clinical values: the profile only. */
        @Override
        public String toString() {
            return "ProfileEffects[onco]";
        }

        /** The next cycle date decides the follow-up only when the doctor chose no interval (ADR-025 D7). */
        @Override
        public LocalDate followUpDate() {
            return followUpFromNextCycle ? nextCycleDate : null;
        }

        @Override
        public List<FinishPrint> prints() {
            return nextCycleDate == null ? List.of(FinishPrint.CYCLE_RECORD)
                    : List.of(FinishPrint.CYCLE_RECORD, FinishPrint.CHEMO_CALENDAR);
        }
    }

    /** SAH-71-3: the surgery finish dialog. The admission letter and pre-op sheet print once the OT is booked (H19). */
    record Surgery(String bookState, String procedureText, LocalDate otDate, String slotText, String admission,
                   boolean admissionRequested, String fitnessText) implements ProfileEffects {

        @Override
        public String profile() {
            return "surgery";
        }

        /** Clinical values: the profile only. */
        @Override
        public String toString() {
            return "ProfileEffects[surgery]";
        }

        @Override
        public List<FinishPrint> prints() {
            return "BOOKED".equals(bookState) ? List.of(FinishPrint.ADMISSION_LETTER, FinishPrint.PREOP_SHEET)
                    : List.of();
        }
    }
}
