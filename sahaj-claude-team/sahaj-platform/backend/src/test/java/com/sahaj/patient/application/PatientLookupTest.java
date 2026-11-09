package com.sahaj.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAH-36 (ADR-013 D15): {@code findAllCurrent} gives name, UHID, age and sex (never the mobile number), and resolves a
 * merged-away record exactly as {@link PatientClinicalLookup#findAll} does, so every role sees the same identity.
 */
class PatientLookupTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OLD = UUID.randomUUID();
    private static final UUID KEPT = UUID.randomUUID();
    private static final UUID PLAIN = UUID.randomUUID();

    private final Map<UUID, PatientRow> rows = new HashMap<>();
    private PatientRepository repository;
    private PatientLookup lookup;
    private PatientClinicalLookup clinicalLookup;

    @BeforeEach
    void setUp() {
        repository = mock(PatientRepository.class);
        when(repository.findByIds(eq(TENANT), anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(1);
            List<PatientRow> found = new ArrayList<>();
            for (UUID id : ids) {
                if (rows.containsKey(id)) {
                    found.add(rows.get(id));
                }
            }
            return found;
        });
        lookup = new PatientLookup(repository);
        clinicalLookup = new PatientClinicalLookup(repository);
    }

    private PatientRow put(UUID id, String uhid, String name, int age, String sex, String allergy, UUID mergedInto) {
        PatientRow row = new PatientRow(id, uhid, name, age, sex, "9000000001", "Area", "mr", allergy, "Diabetes", true,
                mergedInto, Instant.parse("2026-11-01T00:00:00Z"), 3L);
        rows.put(id, row);
        return row;
    }

    @Test
    void findAllCurrentGivesNameUhidAgeAndSexAndNeverTheMobileNumber() {
        put(PLAIN, "UH-000200", "Plain Patient", 33, "M", "Penicillin", null);

        PatientSummary found = lookup.findAllCurrent(TENANT, Set.of(PLAIN)).get(PLAIN);

        assertThat(found.id()).isEqualTo(PLAIN);
        assertThat(found.uhid()).isEqualTo("UH-000200");
        assertThat(found.name()).isEqualTo("Plain Patient");
        assertThat(found.age()).isEqualTo(33);
        assertThat(found.sex()).isEqualTo("M");
        assertThat(found.mobile()).isNull();
        assertThat(found.merged()).isFalse();
    }

    @Test
    void aMergedAwayRecordAnswersWithTheRecordItWasMergedIntoUnderTheIdAskedFor() {
        put(OLD, "UH-000100", "Old Spelling", 40, "F", null, KEPT);
        put(KEPT, "UH-000101", "Kept Record", 41, "F", "Sulfa", null);

        Map<UUID, PatientSummary> all = lookup.findAllCurrent(TENANT, Set.of(OLD));

        assertThat(all).containsOnlyKeys(OLD);
        PatientSummary shown = all.get(OLD);
        assertThat(shown.id()).isEqualTo(KEPT);
        assertThat(shown.uhid()).isEqualTo("UH-000101");
        assertThat(shown.name()).isEqualTo("Kept Record");
        assertThat(shown.age()).isEqualTo(41);
        assertThat(shown.merged()).isFalse();
    }

    @Test
    void theIdentityIsTheSameAsTheClinicalPortGivesForTheSameIds() {
        put(OLD, "UH-000100", "Old Spelling", 40, "F", null, KEPT);
        put(KEPT, "UH-000101", "Kept Record", 41, "F", "Sulfa", null);
        put(PLAIN, "UH-000200", "Plain Patient", 33, "M", "None known", null);
        UUID unknown = UUID.randomUUID();
        Set<UUID> asked = Set.of(OLD, KEPT, PLAIN, unknown);

        Map<UUID, PatientSummary> lookupAnswer = lookup.findAllCurrent(TENANT, asked);
        Map<UUID, PatientClinical> clinicalAnswer = clinicalLookup.findAll(TENANT, asked);

        assertThat(lookupAnswer.keySet()).isEqualTo(clinicalAnswer.keySet()).doesNotContain(unknown);
        for (UUID id : clinicalAnswer.keySet()) {
            PatientSummary a = lookupAnswer.get(id);
            PatientClinical b = clinicalAnswer.get(id);
            assertThat(a.id()).as("id of %s", id).isEqualTo(b.id());
            assertThat(a.uhid()).isEqualTo(b.uhid());
            assertThat(a.name()).isEqualTo(b.name());
            assertThat(a.age()).isEqualTo(b.age());
            assertThat(a.sex()).isEqualTo(b.sex());
        }
    }

    @Test
    void aMergeLoopStopsAndDoesNotHang() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        put(a, "UH-1", "A", 1, "F", null, b);
        put(b, "UH-2", "B", 2, "F", null, a);

        Map<UUID, PatientSummary> all = lookup.findAllCurrent(TENANT, Set.of(a));

        assertThat(all).containsOnlyKeys(a);
    }

    @Test
    void noIdsGiveAnEmptyAnswerAndUnknownIdsAreAbsent() {
        assertThat(lookup.findAllCurrent(TENANT, Set.of())).isEmpty();
        assertThat(lookup.findAllCurrent(TENANT, Set.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void mobilesOfGivesTheNumberOfEachPatientKeyedByTheIdAskedFor() {
        put(PLAIN, "UH-000200", "Plain Patient", 33, "M", null, null);

        Map<UUID, String> mobiles = lookup.mobilesOf(TENANT, Set.of(PLAIN));

        assertThat(mobiles).containsOnlyKeys(PLAIN).containsEntry(PLAIN, "9000000001");
    }

    @Test
    void aMergedAwayRecordAnswersWithTheMobileOfTheRecordItWasMergedInto() {
        rows.put(OLD, new PatientRow(OLD, "UH-000100", "Old Spelling", 40, "F", "9000000100", "Area", "mr", null, null,
                true, KEPT, Instant.parse("2026-11-01T00:00:00Z"), 3L));
        rows.put(KEPT, new PatientRow(KEPT, "UH-000101", "Kept Record", 41, "F", "9000000101", "Area", "mr", null, null,
                true, null, Instant.parse("2026-11-01T00:00:00Z"), 3L));

        Map<UUID, String> mobiles = lookup.mobilesOf(TENANT, Set.of(OLD));

        assertThat(mobiles).containsOnlyKeys(OLD).containsEntry(OLD, "9000000101");
        assertThat(lookup.findAllCurrent(TENANT, Set.of(OLD)).get(OLD).name()).isEqualTo("Kept Record");
    }

    @Test
    void mobilesOfLeavesOutUnknownIdsAndPatientsWithoutANumberAndAnswersEmptyWithoutIds() {
        rows.put(PLAIN, new PatientRow(PLAIN, "UH-000200", "Plain Patient", 33, "M", null, "Area", "mr", null, null,
                true, null, Instant.parse("2026-11-01T00:00:00Z"), 3L));

        assertThat(lookup.mobilesOf(TENANT, Set.of(PLAIN, UUID.randomUUID()))).isEmpty();
        assertThat(lookup.mobilesOf(TENANT, Set.of())).isEmpty();
    }

    @Test
    void mobilesOfNeverMakesFindAllCurrentCarryANumber() {
        put(PLAIN, "UH-000200", "Plain Patient", 33, "M", null, null);

        assertThat(lookup.mobilesOf(TENANT, Set.of(PLAIN))).isNotEmpty();
        assertThat(lookup.findAllCurrent(TENANT, Set.of(PLAIN)).get(PLAIN).mobile()).isNull();
    }

    @Test
    void findAndFindAllKeepTheirOldBehaviourAndNowCarryTheUhid() {
        put(OLD, "UH-000100", "Old Spelling", 40, "F", null, KEPT);
        put(KEPT, "UH-000101", "Kept Record", 41, "F", "Sulfa", null);
        when(repository.findById(TENANT, OLD)).thenReturn(java.util.Optional.of(rows.get(OLD)));

        PatientSummary viaFindAll = lookup.findAll(TENANT, Set.of(OLD)).get(OLD);

        assertThat(viaFindAll.id()).isEqualTo(OLD);
        assertThat(viaFindAll.merged()).isTrue();
        assertThat(viaFindAll.uhid()).isEqualTo("UH-000100");
        assertThat(viaFindAll.mobile()).isEqualTo("9000000001");
        assertThat(lookup.find(TENANT, OLD).orElseThrow().uhid()).isEqualTo("UH-000100");
    }
}
