
package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The drug-class allergy check (SAH-48-2, ADR-022 D5; rule C-2, tagged, not clinically reviewed). The visit's classes are
 * the items of {@code allergy.drug_allergy} whose item key is an allergy class key. A prescription row (in the
 * prescription or in a form's medicine list) whose medicine carries one of those classes is an alert until the doctor
 * reviews it for that row and class. Typed medicines and typed allergies are never checked. "No alert is not a
 * clearance": the check covers listed classes only. Pure: the medicine's class comes in as a function.
 */
public final class EmrAllergyCheck {

    public static final String ALLERGY_SECTION = "allergy";
    public static final String ALLERGY_FIELD = "drug_allergy";

    private EmrAllergyCheck() {
    }

    public record Alert(String sectionKey, String fieldKey, UUID rowId, String medicineKey, String medicineName,
                        String allergyClass, boolean reviewed, Instant reviewedAt, UUID reviewedByMembershipId) {

        /** A medicine name is clinical: ids only. */
        @Override
        public String toString() {
            return "Alert[rowId=" + rowId + ", allergyClass=" + allergyClass + ", reviewed=" + reviewed + "]";
        }
    }

    /** The allergy class keys picked in this record's Allergies section, in the order picked. */
    public static Set<String> visitClasses(EmrRecordDocument document, Set<String> knownClasses) {
        Set<String> classes = new LinkedHashSet<>();
        JsonNode list = document.values(ALLERGY_SECTION).get(ALLERGY_FIELD);
        if (list == null) {
            return classes;
        }
        JsonNode items = list.isObject() ? list.path("items") : list;
        if (items.isArray()) {
            for (JsonNode item : items) {
                String key = item.path("itemKey").asText(null);
                if (key != null && knownClasses.contains(key) && item.path("active").asBoolean(true)) {
                    classes.add(key);
                }
            }
        }
        return classes;
    }

    /** All alerts of the record: every shown section's prescription components against the visit's classes. */
    public static List<Alert> alerts(List<LayoutSection> shown, EmrRecordDocument document, Set<String> classes,
                                     Function<UUID, String> medicineClass) {
        List<Alert> alerts = new ArrayList<>();
        if (classes.isEmpty()) {
            return alerts;
        }
        for (LayoutSection section : shown) {
            JsonNode values = document.values(section.key());
            for (String fieldKey : prescriptionFieldKeys(section)) {
                JsonNode value = values.get(fieldKey);
                if (value == null || !value.path("rows").isArray()) {
                    continue;
                }
                for (JsonNode row : value.path("rows")) {
                    if (!row.path("medicineId").isTextual()) {
                        continue;
                    }
                    UUID medicineId = UUID.fromString(row.path("medicineId").asText());
                    String cls = medicineClass.apply(medicineId);
                    if (cls == null || !classes.contains(cls)) {
                        continue;
                    }
                    JsonNode review = row.path("allergyReview");
                    boolean reviewed = review.path("confirmed").asBoolean(false)
                            && cls.equals(review.path("allergyClass").asText());
                    alerts.add(new Alert(section.key(), fieldKey, UUID.fromString(row.path("id").asText()),
                            medicineId.toString(), row.path("name").asText(), cls, reviewed,
                            reviewed && review.path("reviewedAt").isTextual()
                                    ? Instant.parse(review.path("reviewedAt").asText()) : null,
                            reviewed && review.path("reviewedByMembershipId").isTextual()
                                    ? UUID.fromString(review.path("reviewedByMembershipId").asText()) : null));
                }
            }
        }
        return alerts;
    }

    /** The fields of a section that hold prescription rows: the library prescription, or a form's medicine lists. */
    static List<String> prescriptionFieldKeys(LayoutSection section) {
        if (section.formSection()) {
            return section.form() == null ? List.of()
                    : EmrFormValues.componentKeys(section.form(), EmrComponentBinding.PRESCRIPTION);
        }
        List<String> keys = new ArrayList<>();
        for (FieldDefinition field : section.fields()) {
            if (field.component() != null && EmrComponentBinding.PRESCRIPTION.equals(field.component().type())) {
                keys.add(field.key());
            }
        }
        return keys;
    }

    public static boolean blocked(List<Alert> alerts) {
        return alerts.stream().anyMatch(alert -> !alert.reviewed());
    }

    /**
     * Clears every review in the record's prescription components (the visit's allergy classes changed, SAH-48-2).
     * Returns true when something was cleared.
     */
    public static boolean clearReviews(JsonNode valuesOfSection) {
        boolean cleared = false;
        if (valuesOfSection == null || !valuesOfSection.isObject()) {
            return false;
        }
        for (JsonNode value : valuesOfSection) {
            if (value.isObject() && EmrComponentBinding.PRESCRIPTION.equals(value.path("component").asText())
                    && value.path("rows") instanceof ArrayNode rows) {
                for (JsonNode row : rows) {
                    if (row instanceof ObjectNode object && object.path("allergyReview").isObject()) {
                        object.putNull("allergyReview");
                        cleared = true;
                    }
                }
            }
        }
        return cleared;
    }

    /** Label lookup helper for the view. */
    public static String label(Map<String, String> labels, String key) {
        return labels.getOrDefault(key, key);
    }
}
