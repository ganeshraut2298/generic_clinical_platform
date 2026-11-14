package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.sahaj.opd.domain.emr.specialty.PaedsRules;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * SAH-62-4 (ADR-025 D7): the finish dialog's facts for Paediatrics (opd.yaml 0.6.1, EmrFinishPaedsEffects): today's weight
 * and its note, the vaccines given today with their reference labels and the next due vaccine with its age group. Reads only;
 * nothing is written at the sign. Clinical values: screen only, never logged. Called only by the sign step.
 */
@Component
class PaedsFinishContributor implements SpecialtyFinishContributor {

    private final SpecialtyRecordSupport support;

    PaedsFinishContributor(SpecialtyRecordSupport support) {
        this.support = support;
    }

    @Override
    public String profile() {
        return "paeds";
    }

    @Override
    public ProfileEffects preview(FinishContext ctx) {
        SpecialtyCtx specialty = support.context(ctx.tenantId(), ctx.record(), "paeds", ctx.membershipId(), ctx.role(),
                ctx.now(), ctx.document());
        JsonNode paeds = support.view("paeds", specialty, ctx.record()).path("paeds");
        JsonNode growth = orEmpty(SpecialtyRecordSupport.componentValue("paeds", ctx.document(), "GROWTH"));
        Double weight = growth.path("weightKg").isNumber() ? growth.path("weightKg").asDouble() : null;
        JsonNode notes = paeds.path("growthNotes");
        String note = weight != null && notes.isArray() && !notes.isEmpty() && notes.get(0).path("label").isTextual()
                ? notes.get(0).path("label").asText() : null;
        List<ProfileEffects.GivenVaccine> given = new ArrayList<>();
        for (JsonNode vaccine : orEmpty(SpecialtyRecordSupport.componentValue("paeds", ctx.document(), "IMMUNIZATION"))
                .path("givenToday")) {
            String key = vaccine.path("vaccineKey").asText();
            PaedsRules.Vaccine reference = specialty.ref().vaccine(key);
            given.add(new ProfileEffects.GivenVaccine(key, reference == null ? key : reference.label()));
        }
        ProfileEffects.NextDue nextDue = null;
        if (paeds.path("nextDue").isObject()) {
            String key = paeds.path("nextDue").path("vaccineKey").asText();
            PaedsRules.Vaccine reference = specialty.ref().vaccine(key);
            nextDue = new ProfileEffects.NextDue(key, reference == null ? key : reference.label(),
                    paeds.path("nextDue").path("groupLabel").asText(""));
        }
        return new ProfileEffects.Paeds(weight, note, List.copyOf(given), nextDue);
    }

    private static JsonNode orEmpty(JsonNode node) {
        return node == null ? MissingNode.getInstance() : node;
    }
}
