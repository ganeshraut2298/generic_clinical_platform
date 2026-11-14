package com.sahaj.opd.application.emr.specialty;

import com.sahaj.opd.application.emr.specialty.SpecialtyCtx.ChemoInfo;
import com.sahaj.opd.domain.emr.specialty.ChemoRules;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Canon;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.CanonDrug;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Check;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.CheckFacts;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Drug;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.DoseLine;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Lifetime;
import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import com.sahaj.opd.domain.emr.specialty.OncologyRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server-side computation of one chemotherapy order (SAH-66; rule C-8): the doses, the lifetime anthracycline dose,
 * the ten safety checks and the fingerprint of what the checker is shown, from the doctor's inputs, the oncology
 * assessment of the record and the patient's earlier orders. Pure: the same inputs always give the same fingerprint, so a
 * confirmation can be compared with it. Recomputed on every sheet save, record draft save and send; no value the client
 * computed is trusted. Clinical data: never logged.
 */
public final class ChemoOrderCompute {

    private ChemoOrderCompute() {
    }

    /** What the doctor entered (the {@code inputs} json of the order). */
    public record OrderInputs(String protocolKey, int levelPct, String reductionReasonKey, String reductionReasonText,
                              Map<String, Double> finalDoses, Set<String> premedsNotGiven, boolean allergyConfirmed,
                              boolean consentNewSigned, String scheme, Double priorOutsideMgM2) {
    }

    /** One drug with its dose line (null when there is no BSA yet). */
    public record DrugResult(Drug drug, DoseLine line) {
    }

    public record Computed(Protocol protocol, OncologyAssessment assessment, List<DrugResult> drugs,
                           double earlierMgM2, double thisCycleMgM2, Lifetime lifetime, List<Check> checks,
                           String decision, String fingerprint) {

        public boolean allChecksPass() {
            return ChemoRules.allPassed(checks);
        }
    }

    /**
     * @param decision          the recorded decision (GIVE_AS_PLANNED ...), or null
     * @param checkerMembership the named checker, or null (part of the fingerprint)
     */
    public static Computed compute(OrderInputs in, Protocol protocol, OncologyAssessment a, ChemoInfo info,
                                   String decision, UUID checkerMembership) {
        List<DrugResult> drugs = new ArrayList<>();
        List<CanonDrug> canonDrugs = new ArrayList<>();
        for (Drug drug : protocol.drugs()) {
            Double edited = in.finalDoses() == null ? null : in.finalDoses().get(drug.drugKey());
            DoseLine line = ChemoRules.doseLine(drug, a.bsaUsed(), in.levelPct(), edited);
            drugs.add(new DrugResult(drug, line));
            canonDrugs.add(line == null ? new CanonDrug(drug.drugKey(), null, null, null)
                    : new CanonDrug(drug.drugKey(), line.calculatedMg(), line.roundedMg(), line.finalMg()));
        }
        double outside = in.priorOutsideMgM2() == null ? info.latestOutsideMgM2() : in.priorOutsideMgM2();
        double earlier = outside + info.sentDoxorubicinMgM2();
        double thisCycle = ChemoRules.thisCycleMgM2(protocol, in.levelPct());
        boolean anthracycline = protocol.drugs().stream().anyMatch(Drug::anthracycline);
        Lifetime lifetime = anthracycline ? ChemoRules.lifetime(earlier, thisCycle) : null;
        boolean reason = in.reductionReasonKey() != null
                || (in.reductionReasonText() != null && !in.reductionReasonText().isBlank());
        CheckFacts facts = new CheckFacts(decision, OncologyRules.countOrOrganFail(a.fails()), a.fever(), a.labsFresh(),
                a.weightAndHeightRecorded(), anthracycline, lifetime, in.levelPct(), reason, in.allergyConfirmed(),
                info.sentProtocols().contains(protocol.key()), in.consentNewSigned(), in.scheme(),
                protocol.hasVesicant(), a.lineReady());
        List<Check> checks = ChemoRules.checks(facts);
        Canon canon = new Canon(protocol.key(), in.levelPct(), in.reductionReasonKey(), in.reductionReasonText(),
                in.finalDoses(), in.premedsNotGiven(), in.allergyConfirmed(), in.consentNewSigned(), in.scheme(),
                in.priorOutsideMgM2(), a.weightKg(), a.heightCm(), a.bsa(), a.bsaUsed(), a.capped(), a.capAt2(),
                a.weightAndHeightRecorded() ? "TODAYS_WEIGHT" : "NOT_RECORDED", canonDrugs, BigDecimal.valueOf(earlier).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                thisCycle, lifetime == null ? null : lifetime.totalMgM2(), a.labs(), a.drawnAt(), decision, checks,
                checkerMembership);
        return new Computed(protocol, a, List.copyOf(drugs), earlier, thisCycle, lifetime, List.copyOf(checks),
                decision, ChemoRules.fingerprint(canon));
    }

}
