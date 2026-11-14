package com.sahaj.opd.application.emr.specialty;

import org.springframework.stereotype.Component;

/**
 * SAH-51-1, SAH-60 (ADR-025 D7): General Medicine writes nothing of its own at the end of a visit and has no profile facts for
 * the finish dialog (opd.yaml 0.6.1, EmrFinishGmEffects): the dialog shows the composer's section rows only. Called only by
 * the sign step.
 */
@Component
class GmFinishContributor implements SpecialtyFinishContributor {

    @Override
    public String profile() {
        return "gm";
    }

    @Override
    public ProfileEffects preview(FinishContext ctx) {
        return new ProfileEffects.Gm();
    }
}
