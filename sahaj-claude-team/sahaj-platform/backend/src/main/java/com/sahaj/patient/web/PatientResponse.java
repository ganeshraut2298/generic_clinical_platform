package com.sahaj.patient.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sahaj.patient.application.MergeOutcome;
import com.sahaj.patient.application.PatientView;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code Patient} (and {@code PatientMergeResult}) in {@code docs/api/patient.yaml}. SAH-73 (ADR-024 D3): {@code age}
 * is the effective completed years and {@code ageDetail} the unit and value. {@code dateOfBirth} is a date, or JSON null
 * when not recorded, for OWNER, DOCTOR, VISITING_DOCTOR, NURSE and FRONT_DESK; for every other role the property is
 * absent (not null), because null already means "not recorded". It is a {@link JsonNode} so that a Java null (absent)
 * and a JSON null (not recorded) can be told apart. {@code datesOfBirthDiffered} is sent by the merge only.
 * {@code toString} shows the id only (the date of birth is personal data).
 */
public record PatientResponse(UUID id, String uhid, String name, int age, AgeDetailResponse ageDetail,
                               @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode dateOfBirth, String sex,
                               String mobile, String area, String language, String allergy, String chronic,
                               boolean abhaLinked, Instant createdAt, UUID possibleDuplicateOfId,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Boolean datesOfBirthDiffered) {

    /** The answer for a caller with this role: the date of birth only when the role may see it. */
    public static PatientResponse from(PatientView view, String role) {
        return build(view, role, null);
    }

    /** The merge answer: the kept record plus the date of birth notice. */
    public static PatientResponse from(MergeOutcome outcome, String role) {
        return build(outcome.patient(), role, outcome.datesOfBirthDiffered());
    }

    private static PatientResponse build(PatientView view, String role, Boolean differed) {
        JsonNode dateOfBirth = null;
        if (PatientRoles.mayViewDateOfBirth(role)) {
            dateOfBirth = view.dateOfBirth() == null ? JsonNodeFactory.instance.nullNode()
                    : JsonNodeFactory.instance.textNode(view.dateOfBirth().toString());
        }
        return new PatientResponse(view.id(), view.uhid(), view.name(), view.age(),
                AgeDetailResponse.from(view.ageDetail()), dateOfBirth, view.sex(), view.mobile(), view.area(),
                view.language(), view.allergy(), view.chronic(), view.abhaLinked(), view.createdAt(),
                view.possibleDuplicateOfId(), differed);
    }

    @Override
    public String toString() {
        return "PatientResponse[id=" + id + "]";
    }
}
