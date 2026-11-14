package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.FieldOption;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.TableColumn;
import java.math.BigDecimal;
import java.util.List;

/** Small builders for the EMR unit tests: made-up sections and fields, no real clinical content. */
final class EmrTestData {

    static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    static final EmrLayoutJson JSON = new EmrLayoutJson(MAPPER);

    private EmrTestData() {
    }

    /** The finish repository of a unit test where nobody else holds the record: every take of the edit hold succeeds. */
    static com.sahaj.opd.application.emr.finish.EmrFinishRepository finishRepositoryWithFreeHold() {
        com.sahaj.opd.application.emr.finish.EmrFinishRepository finish =
                org.mockito.Mockito.mock(com.sahaj.opd.application.emr.finish.EmrFinishRepository.class);
        org.mockito.Mockito.when(finish.takeHold(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        return finish;
    }

    /** A provider with no bean (the optional port of another module is not there). */
    @SuppressWarnings("unchecked")
    static <T> org.springframework.beans.factory.ObjectProvider<T> noBean() {
        return org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    static FieldDefinition field(String key, String type, boolean required) {
        return new FieldDefinition(key, key, type, EmrFieldType.valueKind(type), "", required, "NONE", null, false,
                List.of(), List.of(), null, null);
    }

    static FieldDefinition choice(String key, boolean required, String... optionKeys) {
        List<FieldOption> options = new java.util.ArrayList<>();
        for (String option : optionKeys) {
            options.add(new FieldOption(option, option, false));
        }
        return new FieldDefinition(key, key, "DROPDOWN", EmrValueKind.CHOICE, "", required, "FIELD", null, false,
                options, List.of(), null, null);
    }

    static FieldDefinition table(String key, String... columns) {
        List<TableColumn> list = new java.util.ArrayList<>();
        for (String column : columns) {
            list.add(new TableColumn(column, column));
        }
        return new FieldDefinition(key, key, "TABLE", EmrValueKind.TABLE, "", false, "NONE", null, false, List.of(),
                list, null, null);
    }

    static FieldDefinition number(String key, BigDecimal min, BigDecimal max) {
        return new FieldDefinition(key, key, "NUMBER", EmrValueKind.NUMBER, "", false, "NONE", null, false, List.of(),
                List.of(), min, max);
    }

    static LayoutSection section(String key, String kind, String part, boolean shown, boolean required,
                                 boolean locked, FieldDefinition... fields) {
        return new LayoutSection(key, "LIBRARY", key.toUpperCase(), kind, "", part, 1, shown, required, false, false,
                false, List.of("DOCTOR"), locked, locked ? key.toUpperCase() + " cannot be hidden." : null,
                List.of(fields));
    }

    static LayoutSection section(String key, boolean shown, boolean required, FieldDefinition... fields) {
        return section(key, "COMMON", "ASSESSMENT", shown, required, false, fields);
    }
}
