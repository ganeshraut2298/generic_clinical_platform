package com.sahaj.patient.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.application.PatientRepository.PatientRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-31: the clinical lookup port gives the UHID, name, age, sex and allergy text and nothing else. */
class PatientClinicalLookupTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private PatientRepository repository;
    private PatientClinicalLookup lookup;

    @BeforeEach
    void setUp() {
        repository = mock(PatientRepository.class);
        lookup = new PatientClinicalLookup(repository);
    }

    private static PatientRow row(UUID id, String allergy, UUID mergedInto) {
        return new PatientRow(id, "UH-000123", "Test Patient", 54, "F", "9000000001", "Area", "mr", allergy,
                "Diabetes", true, mergedInto, Instant.parse("2026-11-01T00:00:00Z"), 3L);
    }

    @Test
    void findGivesUhidNameAgeSexAndTheAllergyText() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "Penicillin (rash)", null)));

        PatientClinical found = lookup.find(TENANT, PATIENT).orElseThrow();

        assertThat(found.id()).isEqualTo(PATIENT);
        assertThat(found.uhid()).isEqualTo("UH-000123");
        assertThat(found.name()).isEqualTo("Test Patient");
        assertThat(found.age()).isEqualTo(54);
        assertThat(found.sex()).isEqualTo("F");
        assertThat(found.allergyText()).isEqualTo("Penicillin (rash)");
    }

    @Test
    void aMissingAllergyStaysNullAndAMergedAwayRecordIsStillFound() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, null, UUID.randomUUID())));

        assertThat(lookup.find(TENANT, PATIENT).orElseThrow().allergyText()).isNull();
    }

    @Test
    void anUnknownOrOtherClinicsPatientIsAbsent() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.empty());

        assertThat(lookup.find(TENANT, PATIENT)).isEmpty();
    }

    @Test
    void findAllIsOneQueryKeyedByIdAndEmptyForNoIds() {
        UUID other = UUID.randomUUID();
        when(repository.findByIds(TENANT, Set.of(PATIENT, other))).thenReturn(List.of(row(PATIENT, "None known", null),
                row(other, null, null)));

        Map<UUID, PatientClinical> all = lookup.findAll(TENANT, Set.of(PATIENT, other));

        assertThat(all).containsOnlyKeys(PATIENT, other);
        assertThat(all.get(PATIENT).allergyText()).isEqualTo("None known");
        assertThat(lookup.findAll(TENANT, Set.of())).isEmpty();
        verify(repository, never()).findByIds(TENANT, Set.of());
        verify(repository, org.mockito.Mockito.times(1)).findByIds(TENANT, Set.of(PATIENT, other));
    }

    @Test
    void toStringShowsTheIdOnly() {
        PatientClinical clinical = new PatientClinical(PATIENT, "UH-000123", "Test Patient", 54, "F", "Penicillin");

        assertThat(clinical.toString()).isEqualTo("PatientClinical[id=" + PATIENT + "]");
        assertThat(clinical.toString()).doesNotContain("Test Patient").doesNotContain("Penicillin")
                .doesNotContain("UH-000123");
    }

    // ---- S4: a merged-away record answers with the kept record ----

    private static final UUID KEPT = UUID.randomUUID();
    private static final UUID MIDDLE = UUID.randomUUID();

    private static PatientRow keptRow(UUID id, String uhid, String allergy, UUID mergedInto) {
        return new PatientRow(id, uhid, "Kept Patient", 55, "F", "9000000001", "Area", "mr", allergy, null, true,
                mergedInto, Instant.parse("2026-11-01T00:00:00Z"), 4L);
    }

    @Test
    void findFollowsMergedIntoIdToTheKeptRecordAndShowsItsAllergyText() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "None known", KEPT)));
        when(repository.findById(TENANT, KEPT)).thenReturn(Optional.of(keptRow(KEPT, "UH-000001", "Sulfa", null)));

        PatientClinical found = lookup.find(TENANT, PATIENT).orElseThrow();

        assertThat(found.id()).isEqualTo(KEPT);
        assertThat(found.uhid()).isEqualTo("UH-000001");
        assertThat(found.allergyText()).isEqualTo("Sulfa");
    }

    @Test
    void findFollowsAChainOfMerges() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, null, MIDDLE)));
        when(repository.findById(TENANT, MIDDLE)).thenReturn(Optional.of(keptRow(MIDDLE, "UH-2", "Old", KEPT)));
        when(repository.findById(TENANT, KEPT)).thenReturn(Optional.of(keptRow(KEPT, "UH-3", "Newest", null)));

        assertThat(lookup.find(TENANT, PATIENT).orElseThrow().allergyText()).isEqualTo("Newest");
    }

    @Test
    void findStopsAtALoopInsteadOfSpinning() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "A", KEPT)));
        when(repository.findById(TENANT, KEPT)).thenReturn(Optional.of(keptRow(KEPT, "UH-1", "B", PATIENT)));

        PatientClinical found = lookup.find(TENANT, PATIENT).orElseThrow();

        assertThat(found.id()).isEqualTo(KEPT);
        verify(repository, org.mockito.Mockito.times(1)).findById(TENANT, PATIENT);
        verify(repository, org.mockito.Mockito.times(1)).findById(TENANT, KEPT);
    }

    @Test
    void findStopsAtARecordPointingAtItself() {
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "A", PATIENT)));

        assertThat(lookup.find(TENANT, PATIENT).orElseThrow().id()).isEqualTo(PATIENT);
        verify(repository, org.mockito.Mockito.times(1)).findById(TENANT, PATIENT);
    }

    @Test
    void findStopsAfterTheHopLimitOnALongChain() {
        UUID[] ids = new UUID[PatientClinicalLookup.MAX_HOPS + 5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID();
        }
        for (int i = 0; i < ids.length; i++) {
            UUID next = i + 1 < ids.length ? ids[i + 1] : null;
            when(repository.findById(TENANT, ids[i])).thenReturn(Optional.of(keptRow(ids[i], "UH-" + i, "A" + i, next)));
        }

        PatientClinical found = lookup.find(TENANT, ids[0]).orElseThrow();

        assertThat(found.id()).isEqualTo(ids[PatientClinicalLookup.MAX_HOPS]);
    }

    @Test
    void findReadsOnlyThroughTheTenantItWasGiven() {
        UUID otherTenant = UUID.randomUUID();
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(row(PATIENT, "A", KEPT)));
        when(repository.findById(TENANT, KEPT)).thenReturn(Optional.of(keptRow(KEPT, "UH-1", "B", null)));

        lookup.find(TENANT, PATIENT);

        verify(repository, never()).findById(org.mockito.ArgumentMatchers.eq(otherTenant), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).findByIds(org.mockito.ArgumentMatchers.eq(otherTenant), anyCollection());
    }

    @Test
    void findAllFollowsMergesKeyedByTheIdAskedAndLeavesOtherIdsAlone() {
        UUID plain = UUID.randomUUID();
        when(repository.findByIds(TENANT, Set.of(PATIENT, plain))).thenReturn(List.of(row(PATIENT, "None known", KEPT),
                row(plain, "Peanuts", null)));
        when(repository.findByIds(TENANT, Set.of(KEPT))).thenReturn(List.of(keptRow(KEPT, "UH-1", "Sulfa", null)));

        Map<UUID, PatientClinical> all = lookup.findAll(TENANT, Set.of(PATIENT, plain));

        assertThat(all).containsOnlyKeys(PATIENT, plain);
        assertThat(all.get(PATIENT).id()).isEqualTo(KEPT);
        assertThat(all.get(PATIENT).allergyText()).isEqualTo("Sulfa");
        assertThat(all.get(plain).allergyText()).isEqualTo("Peanuts");
    }

    @Test
    void findAllFollowsAChainAndStopsAtALoop() {
        UUID looped = UUID.randomUUID();
        UUID loopedOther = UUID.randomUUID();
        when(repository.findByIds(TENANT, Set.of(PATIENT, looped))).thenReturn(List.of(row(PATIENT, "A", MIDDLE),
                row(looped, "L1", loopedOther)));
        when(repository.findByIds(TENANT, Set.of(MIDDLE, loopedOther))).thenReturn(List.of(
                keptRow(MIDDLE, "UH-2", "B", KEPT), keptRow(loopedOther, "UH-9", "L2", looped)));
        when(repository.findByIds(TENANT, Set.of(KEPT))).thenReturn(List.of(keptRow(KEPT, "UH-3", "C", null)));

        Map<UUID, PatientClinical> all = lookup.findAll(TENANT, Set.of(PATIENT, looped));

        assertThat(all.get(PATIENT).allergyText()).isEqualTo("C");
        assertThat(all.get(looped).id()).isEqualTo(loopedOther);
        assertThat(all.get(looped).allergyText()).isEqualTo("L2");
    }

    @Test
    void findAllWithAMissingKeptRecordKeepsTheMergedAwayRowAndAsksOnlyOnce() {
        when(repository.findByIds(TENANT, Set.of(PATIENT))).thenReturn(List.of(row(PATIENT, "A", KEPT)));
        when(repository.findByIds(TENANT, Set.of(KEPT))).thenReturn(List.of());

        assertThat(lookup.findAll(TENANT, Set.of(PATIENT)).get(PATIENT).allergyText()).isEqualTo("A");
        verify(repository, org.mockito.Mockito.times(1)).findByIds(TENANT, Set.of(KEPT));
    }

    @Test
    void theChronicTextOfThePatientFileIsReturnedForTheEmrHeader() {
        PatientRow withChronic = new PatientRow(PATIENT, "UH-000123", "Test Patient", 54, "F", "9000000001", "Area",
                "mr", "Penicillin", "Type 2 diabetes", false, null, Instant.parse("2026-11-01T00:00:00Z"), 1L);
        when(repository.findById(TENANT, PATIENT)).thenReturn(Optional.of(withChronic));

        PatientClinical found = lookup.find(TENANT, PATIENT).orElseThrow();

        assertThat(found.chronicText()).isEqualTo("Type 2 diabetes");
        assertThat(found.allergyText()).isEqualTo("Penicillin");
        assertThat(found.toString()).doesNotContain("diabetes");
        assertThat(new PatientClinical(PATIENT, "UH-1", "Name", 30, "M", null).chronicText()).isNull();
    }
}
