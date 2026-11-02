package com.sahaj.platform.web;

import com.sahaj.platform.application.SpecialtyService.SpecialtyView;
import java.util.List;

/** {@code Specialty} in {@code docs/api/platform.yaml} (SAH-7): phase 1 can be applied, phase 2 shows as "Phase 2". */
public record SpecialtyResponse(String code, String name, List<String> features, int phase) {

    public static SpecialtyResponse from(SpecialtyView view) {
        return new SpecialtyResponse(view.code(), view.name(), view.features(), view.phase());
    }
}
