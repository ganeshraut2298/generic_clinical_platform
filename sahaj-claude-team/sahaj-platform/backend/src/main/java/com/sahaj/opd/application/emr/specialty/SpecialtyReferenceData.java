package com.sahaj.opd.application.emr.specialty;

import com.sahaj.opd.domain.emr.specialty.ChemoRules.Protocol;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.GrowthBand;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Group;
import com.sahaj.opd.domain.emr.specialty.PaedsRules.Vaccine;
import java.util.List;

/**
 * The global reference tables of the specialty screens (ADR-023 D4, "Data needed" 1 to 6): the IAP schedule, growth
 * bands, milestones, joint reference and the chemotherapy protocol library. Read-only, changed by migration only, every
 * value tagged "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed". No clinic data.
 */
public record SpecialtyReferenceData(List<Group> groups, List<Vaccine> vaccines, List<GrowthBand> growthBands,
                                     List<Milestone> milestones, List<JointItem> jointItems,
                                     List<Protocol> protocols) {

    public record Milestone(int ageBandMonths, String key, String label, String domain, int position) {
    }

    /** kind ROM, MMT or SPECIAL_TEST; the normal degrees are set for ROM only. */
    public record JointItem(String jointKey, String kind, String itemKey, String label, Integer normalFromDeg,
                            Integer normalToDeg, int position) {
    }

    public static SpecialtyReferenceData empty() {
        return new SpecialtyReferenceData(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public Protocol protocol(String key) {
        return protocols.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }

    public Vaccine vaccine(String key) {
        return vaccines.stream().filter(v -> v.key().equals(key)).findFirst().orElse(null);
    }

    public List<Milestone> milestonesOf(int ageBandMonths) {
        return milestones.stream().filter(m -> m.ageBandMonths() == ageBandMonths).toList();
    }

    public List<JointItem> joint(String jointKey, String kind) {
        return jointItems.stream().filter(j -> j.jointKey().equals(jointKey) && j.kind().equals(kind)).toList();
    }
}
