package com.sahaj.opd.application.emr;

import java.math.BigDecimal;
import java.util.List;

/**
 * The form definition of docs/api/opd.yaml 0.4.0 (ADR-022 D11) as the application holds it. The same records are the JSON
 * of the API bodies and of the stored drafts, versions and starters ({@code schemaVersion} 1). They hold setup text only,
 * never patient data. Request bodies are closed (an unknown property is a 400) except {@code tenantId} and
 * {@code clinicId}, which are accepted and ignored.
 */
public final class EmrFormModel {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_FIELDS = 80;
    public static final int MAX_FORMS = 100;

    private EmrFormModel() {
    }

    public record Option(String key, String label, String labelMr, String labelHi, Integer score) {
    }

    public record ScoreRule(String kind, String fieldKey, BigDecimal threshold, Integer points) {
    }

    public record ShowIf(String fieldKey, String optionKey) {
    }

    public record Score(List<String> sourceFieldKeys, List<ScoreRule> rules) {
    }

    public record Band(BigDecimal from, String label) {
    }

    public record BandSetup(String sourceFieldKey, List<Band> bands) {
    }

    public record Field(String key, String type, String label, String labelMr, String labelHi, String help,
                        Boolean required, Integer width, String unit, BigDecimal min, BigDecimal max,
                        BigDecimal normalFrom, BigDecimal normalTo, List<Option> options, Boolean print,
                        Boolean copyFromLast, List<String> whoCanFill, String standardCode, ShowIf showIf,
                        Score score, BandSetup band) {
    }

    public record UseIn(List<String> departmentKeys, List<String> places) {
    }

    public record Settings(UseIn useIn, String emrPart, List<String> visitTypes, List<String> whoCanFill,
                           String printLanguage, Boolean approvalRequired) {
    }

    /** Stored and returned definition. {@code schemaVersion} is 1. */
    public record Definition(Integer schemaVersion, String name, Settings settings, List<Field> fields) {
    }

    /** PUT /emr/forms/{formKey}/draft. */
    public record DraftInput(Long revision, String name, Settings settings, List<Field> fields) {
    }

    public record RevisionInput(Long revision) {
    }

    public record SendBackInput(Long revision, String reason) {
    }

    public record CreateInput(String starterKey, String name) {
    }
}
