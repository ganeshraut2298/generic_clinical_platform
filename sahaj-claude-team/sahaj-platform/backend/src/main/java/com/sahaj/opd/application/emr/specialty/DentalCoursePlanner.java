package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrRecordDocument;
import com.sahaj.opd.application.emr.EmrRecordRepository;
import com.sahaj.opd.application.emr.EmrRecordRepository.RecordRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds a DENTAL treatment course from the record's treatment plan (SAH-49-2, SAH-69-1; ADR-023 D7, H11): one sitting
 * per planned sitting of each open row, in plan order. A row still Planned or In progress is open; a sitting that is
 * already past (the row's current sitting) is not planned again. The charge of a sitting is its share of the row's
 * estimate (equal parts rounded to Rs 50, the last takes the rest: tagged B-2, analyst reading). When the patient signed the
 * estimate the course note says so. Items are clinical plan text: never logged or audited.
 */
@Component
public class DentalCoursePlanner {

    /** The note under the course name when the patient signed the estimate. */
    public static final String SIGNED_NOTE = "estimate signed by patient";

    private final EmrRecordRepository records;
    private final ObjectMapper mapper;

    public DentalCoursePlanner(EmrRecordRepository records, ObjectMapper mapper) {
        this.records = records;
        this.mapper = mapper;
    }

    /** One planned sitting of the course. */
    public record Sitting(String item, long chargePaise) {
    }

    /** The sittings and whether the estimate is signed; empty sittings mean there is no open row. */
    public record Plan(List<Sitting> sittings, boolean estimateSigned) {
    }

    /** True when the record is an OPD visit of the dental profile. */
    public boolean isDentalRecord(RecordRow row) {
        return SpecialtyProfiles.profileOf(row.encounterType(), row.starterKey()).filter("dental"::equals).isPresent();
    }

    public Plan plan(RecordRow row) {
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        JsonNode plan = SpecialtyRecordSupport.componentValue("dental", document, "DENTAL_PLAN");
        List<Sitting> sittings = new ArrayList<>();
        if (plan == null) {
            return new Plan(sittings, false);
        }
        for (JsonNode planRow : plan.path("rows")) {
            if ("DONE".equals(planRow.path("status").asText())) {
                continue;
            }
            int planned = planRow.path("sittingsPlanned").asInt(1);
            int current = planRow.path("currentSitting").asInt(1);
            for (int number = current; number <= planned; number++) {
                long share = planRow.path("sittingSharesPaise").path(number - 1).asLong(0);
                String item = planRow.path("procedureText").asText() + " · tooth " + planRow.path("tooth").asInt()
                        + " · sitting " + number + " of " + planned;
                sittings.add(new Sitting(item.length() > 200 ? item.substring(0, 200) : item, share));
            }
        }
        return new Plan(List.copyOf(sittings), "SIGNED".equals(plan.path("estimate").path("state").asText()));
    }

    /** Records the course on the plan ("the DENTAL course started from this plan"); the record stays a draft. */
    public void linkCourse(UUID tenantId, UUID actorUserId, RecordRow row, UUID courseId, UUID membershipId,
                           Instant now) {
        EmrRecordDocument document = EmrRecordDocument.parse(mapper, row.sections());
        String section = SpecialtyProfiles.sectionOf("dental", "DENTAL_PLAN");
        JsonNode plan = SpecialtyRecordSupport.componentValue("dental", document, "DENTAL_PLAN");
        if (plan == null) {
            return;
        }
        ObjectNode values = document.mutableValues(section);
        ((ObjectNode) values.path("specialty")).put("courseId", courseId.toString());
        document.put(section, values, false, now, membershipId);
        records.saveSectionsLocked(tenantId, row.id(), document.toJson(), actorUserId);
    }
}
